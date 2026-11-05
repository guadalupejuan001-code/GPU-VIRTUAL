package x.gpu.probe;

import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Ejecuta compute shaders en el driver del dispositivo y lee todos los valores. */
public final class GpuProbe {
    public interface Progress { void update(String value); }
    private final AssetManager assets;
    private final Progress progress;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext context=EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface=EGL14.EGL_NO_SURFACE;
    private static final int SSBO=0x90D2;
    public GpuProbe(AssetManager a,Progress p){assets=a;progress=p;}
    private static void need(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private byte[] asset(String name)throws Exception {
        try(InputStream in=assets.open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] data=new byte[8192];for(int n;(n=in.read(data))!=-1;)out.write(data,0,n);return out.toByteArray();
        }
    }
    private static String sha(byte[] data)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void zip(ZipOutputStream zip,String name,byte[] data)throws Exception {
        zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();
    }
    private void open() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version=new int[2];need(EGL14.eglInitialize(display,version,0,version,1),"No se pudo iniciar EGL");
        int[] attrs={EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,0x40,
                     EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE};
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        need(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)&&count[0]>0,"El dispositivo no ofrece una configuracion OpenGL ES 3");
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        need(context!=EGL14.EGL_NO_CONTEXT,"No se pudo crear el contexto ES3");
        surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        need(surface!=EGL14.EGL_NO_SURFACE,"No se pudo crear la superficie de prueba");
        need(EGL14.eglMakeCurrent(display,surface,surface,context),"No se pudo activar el contexto");
        int[] major=new int[1],minor=new int[1];GLES30.glGetIntegerv(0x821B,major,0);GLES30.glGetIntegerv(0x821C,minor,0);
        need(major[0]>3||(major[0]==3&&minor[0]>=1),"Se requiere OpenGL ES 3.1 para compute shaders");
        checkGl();
    }
    private void close() {
        if(display!=EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
        }
        display=EGL14.EGL_NO_DISPLAY;context=EGL14.EGL_NO_CONTEXT;surface=EGL14.EGL_NO_SURFACE;
    }
    private static void checkGl(){int error=GLES30.glGetError();need(error==GLES30.GL_NO_ERROR,"Error GL 0x"+Integer.toHexString(error));}
    private static int compile(String source) {
        int shader=GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);GLES31.glShaderSource(shader,source);GLES31.glCompileShader(shader);
        int[] ok=new int[1];GLES31.glGetShaderiv(shader,GLES31.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String log=GLES31.glGetShaderInfoLog(shader);GLES31.glDeleteShader(shader);throw new IllegalStateException("Shader: "+log);}
        int program=GLES31.glCreateProgram();GLES31.glAttachShader(program,shader);GLES31.glLinkProgram(program);GLES31.glDeleteShader(shader);
        GLES31.glGetProgramiv(program,GLES31.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){String log=GLES31.glGetProgramInfoLog(program);GLES31.glDeleteProgram(program);throw new IllegalStateException("Enlace: "+log);}
        return program;
    }
    private static ByteBuffer direct(byte[] data){ByteBuffer b=ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());b.put(data).flip();return b;}
    private static byte[] read(int buffer,int size) {
        GLES31.glBindBuffer(SSBO,buffer);
        Buffer mapped=GLES30.glMapBufferRange(SSBO,0,size,GLES30.GL_MAP_READ_BIT);
        need(mapped instanceof ByteBuffer,"El driver no permitio leer el buffer");
        byte[] bytes=new byte[size];((ByteBuffer)mapped).position(0);((ByteBuffer)mapped).get(bytes);
        need(GLES30.glUnmapBuffer(SSBO),"El buffer perdio validez al leer");checkGl();return bytes;
    }
    private static void verify(byte[] actual,byte[] expected,String what) {
        need(actual.length==expected.length,"Tamano incorrecto: "+what);
        if(!Arrays.equals(actual,expected)) {
            int p=0;while(p<actual.length&&actual[p]==expected[p])p++;
            throw new IllegalStateException(what+": diferencia en palabra "+(p/4));
        }
    }
    private static boolean contains(String value,String... parts){for(String part:parts)if(value.contains(part))return true;return false;}
    private static boolean emulator() {
        String identity=(Build.FINGERPRINT+" "+Build.MODEL+" "+Build.HARDWARE+" "+Build.PRODUCT).toLowerCase(Locale.ROOT);
        return contains(identity,"goldfish","ranchu","emulator","google_sdk","sdk_gphone","vbox86","sdk_phone");
    }
    public JSONObject run(File archive)throws Exception {
        JSONObject report=new JSONObject();report.put("format","GPU-VIRTUAL-C03-physical-device-v1");
        report.put("source_commit",BuildInfo.SOURCE_SHA);report.put("hardware_gpu_test_passed",false);
        report.put("manufacturer",Build.MANUFACTURER);report.put("model",Build.MODEL);report.put("device",Build.DEVICE);
        report.put("android_api",Build.VERSION.SDK_INT);report.put("android_release",Build.VERSION.RELEASE);
        report.put("hardware",Build.HARDWARE);report.put("fingerprint",Build.FINGERPRINT);
        report.put("status","NOT_RUN");JSONArray cases=new JSONArray();report.put("cases",cases);
        long checked=0,filtered=0,raw=0;int dispatches=0;
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(archive))) {
            try {
                progress.update("Identificando el dispositivo y su GPU...");open();
                String renderer=GLES31.glGetString(GLES31.GL_RENDERER),vendor=GLES31.glGetString(GLES31.GL_VENDOR),version=GLES31.glGetString(GLES31.GL_VERSION);
                report.put("gl_renderer",renderer);report.put("gl_vendor",vendor);report.put("gl_version",version);
                String lower=(renderer+" "+vendor).toLowerCase(Locale.ROOT);
                boolean software=contains(lower,"llvmpipe","softpipe","swiftshader","software rasterizer","lavapipe");
                boolean emulated=emulator();
                boolean known=contains(lower,"mali","adreno","powervr","tegra","xclipse","vivante","nvidia","qualcomm","arm");
                report.put("software_renderer_detected",software);report.put("emulator_detected",emulated);report.put("hardware_driver_identified",known&&!software&&!emulated);
                need(!software,"Se rechazo un renderizador por software");need(!emulated,"Se rechazo un emulador: esta prueba exige un telefono fisico");
                need(known,"Driver sin clasificar: no se certifica GPU fisica");
                need(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN,"Orden de bytes no admitido por este ensayo");
                int[] limit=new int[1];GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS,limit,0);int maxGroup=limit[0];
                GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE,limit,0);int maxBuffer=limit[0];
                report.put("max_workgroup_invocations",maxGroup);report.put("max_ssbo_bytes",maxBuffer);
                byte[] inventoryBytes=asset("inventory.json");JSONObject inventory=new JSONObject(new String(inventoryBytes,StandardCharsets.UTF_8));
                zip(output,"inventory.json",inventoryBytes);report.put("inventory_sha256",sha(inventoryBytes));
                JSONArray items=inventory.getJSONArray("cases");
                for(int k=0;k<items.length();k++) {
                    if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Prueba interrumpida");
                    JSONObject item=items.getJSONObject(k);String key=item.getString("key");int n=item.getInt("n"),local=item.getInt("local_size"),points=item.getInt("points");
                    JSONArray nameList=item.getJSONArray("names");String[] names=new String[nameList.length()];for(int j=0;j<names.length;j++)names[j]=nameList.getString(j);
                    progress.update("GPU: "+renderer+"\nPrueba "+(k+1)+" / "+items.length()+" — "+item.getString("name"));
                    byte[] input=asset(key+"/input.u32"),expectedTrace=asset(key+"/trace.u32"),expectedOutput=asset(key+"/output.u32");
                    need(local<=maxGroup&&Math.max(input.length,expectedTrace.length)<=maxBuffer,"La captura supera la capacidad declarada del driver");
                    int[] buffers=new int[4];GLES31.glGenBuffers(4,buffers,0);byte[] trace=null,actualOutput=null;
                    JSONArray programs=item.getJSONArray("programs");JSONArray shaderHashes=new JSONArray();
                    long started=System.nanoTime();
                    try {
                        byte[][] initial={input,new byte[expectedTrace.length],new byte[expectedOutput.length],new byte[n*8]};
                        Arrays.fill(initial[1],(byte)0xA5);Arrays.fill(initial[2],(byte)0x5A);
                        for(int j=0;j<4;j++){GLES31.glBindBuffer(SSBO,buffers[j]);GLES31.glBufferData(SSBO,initial[j].length,direct(initial[j]),GLES31.GL_DYNAMIC_DRAW);GLES31.glBindBufferBase(SSBO,j,buffers[j]);}
                        for(int j=0;j<programs.length();j++) {
                            byte[] source=asset(programs.getString(j));shaderHashes.put(sha(source));zip(output,key+"/program-"+j+".glsl",source);
                            int program=compile(new String(source,StandardCharsets.UTF_8));
                            try {GLES31.glUseProgram(program);int at=GLES31.glGetUniformLocation(program,"count");need(at>=0,"Falta el dominio en el shader");GLES31.glUniform1ui(at,n);GLES31.glDispatchCompute((n+local-1)/local,1,1);dispatches++;GLES31.glMemoryBarrier(0x2000|0x200);checkGl();}
                            finally{GLES31.glDeleteProgram(program);}
                        }
                        GLES31.glFinish();checkGl();trace=read(buffers[1],expectedTrace.length);actualOutput=read(buffers[2],expectedOutput.length);
                    } finally {GLES31.glDeleteBuffers(4,buffers,0);}
                    verify(trace,expectedTrace,key+"/trace");verify(actualOutput,expectedOutput,key+"/output");
                    long elapsed=System.nanoTime()-started;
                    checked+=trace.length/4+actualOutput.length/4;raw+=trace.length;
                    int[] flat=new int[trace.length/4];ByteBuffer.wrap(trace).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(flat);
                    long compressed=0;
                    for(int point=0;point<points;point++) {
                        byte[] encoded=FilterCodec.encodeState(point,names,flat,n,point*n*names.length);
                        compressed+=encoded.length;zip(output,key+"/point-"+point+".tfs",encoded);
                    }
                    filtered+=compressed;zip(output,key+"/input.u32",input);zip(output,key+"/trace.u32",trace);zip(output,key+"/output.u32",actualOutput);
                    JSONObject result=new JSONObject();result.put("key",key);result.put("name",item.getString("name"));result.put("n",n);result.put("local_size",local);
                    result.put("checked_words",trace.length/4+actualOutput.length/4);result.put("trace_sha256",sha(trace));result.put("output_sha256",sha(actualOutput));
                    result.put("raw_capture_bytes",trace.length);result.put("filtered_capture_bytes",compressed);result.put("shader_sha256",shaderHashes);
                    result.put("elapsed_ms",elapsed/1000000.0);result.put("timing_is_not_an_acceleration_claim",true);result.put("passed",true);cases.put(result);
                }
                report.put("status","PASSED_ON_IDENTIFIED_HARDWARE_DRIVER");report.put("hardware_gpu_test_passed",true);
                report.put("source_checkpoints_match_reference",true);report.put("filtered_reconstruction_exact",true);
            } catch(Exception error) {report.put("status","NOT_VERIFIED");report.put("error",error.toString());progress.update(error.getMessage());}
            finally {close();}
            report.put("checked_words",checked);report.put("compute_dispatches",dispatches);report.put("raw_capture_bytes",raw);report.put("filtered_capture_bytes",filtered);
            report.put("physical_register_trace",false);report.put("simultaneous_device_snapshot",false);report.put("complete_gpu_state",false);
            report.put("hardware_evidence_basis","Android device profile, hardware GL driver identification, compute dispatch and bit-exact buffer readback; no physical performance counters");
            report.put("float_order_method","Two compute dispatches with stored float32 intermediate values and an explicit memory barrier");
            zip(output,"report.json",report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return report;
    }
}
