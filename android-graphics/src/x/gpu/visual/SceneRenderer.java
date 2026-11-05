package x.gpu.visual;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Debug;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dos proyecciones del mismo estado; el driver GL es un backend de salida. */
public final class SceneRenderer implements GLSurfaceView.Renderer {
    public interface Listener {
        void live(String text);
        void progress(String text);
        void result(File file,JSONObject report,String summary);
    }
    private final AssetManager assets;
    private final File cache;
    private final Listener listener;
    private volatile int mode=1,objects=16384;
    private volatile boolean benchmarkRequested;
    private int width=1,height=1,program,compute,vbo,vao;
    private int paramsAt,detailsAt,modeAt,cameraAt,viewportAt;
    private final int[] framebuffers=new int[2],textures=new int[2];
    private ByteBuffer denseBytes;
    private IntBuffer denseInts;
    private String renderer="Sin identificar",vendor="",version="";
    private boolean hardware,fatal,software,emulated;
    private long animationStart,liveStart,lastLive;
    private int liveFrames;
    private Bench bench;
    private String vertexSource,fragmentSource,computeSource;
    private static final int MAX_OBJECTS=65536,SIDE=512,SSBO=0x90D2;
    private static final int SEED=0x00c0511a;
    public SceneRenderer(AssetManager assets,File cache,Listener listener){this.assets=assets;this.cache=cache;this.listener=listener;}
    public int mode(){return mode;}
    public void mode(int value){mode=value==0?0:1;liveStart=0;liveFrames=0;}
    public void objects(int value){if(value<1||value>MAX_OBJECTS)throw new IllegalArgumentException("Fuera del perfil C05");objects=value;liveStart=0;liveFrames=0;}
    public void compare(){benchmarkRequested=true;}
    public void cancel(){if(bench!=null)bench.fail(new IllegalStateException("Comparacion interrumpida al salir de la escena"));benchmarkRequested=false;}
    private static void need(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private String asset(String path)throws Exception {
        try(InputStream in=assets.open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] data=new byte[8192];for(int n;(n=in.read(data))!=-1;)out.write(data,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static String sha(byte[] bytes)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static boolean has(String value,String...parts){for(String p:parts)if(value.contains(p))return true;return false;}
    private static void check(){int e=GLES30.glGetError();need(e==0,"Error GL 0x"+Integer.toHexString(e));}
    private static int shader(int type,String source) {
        int id=GLES30.glCreateShader(type);GLES30.glShaderSource(id,source);GLES30.glCompileShader(id);
        int[] ok=new int[1];GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String log=GLES30.glGetShaderInfoLog(id);GLES30.glDeleteShader(id);throw new IllegalStateException(log);}
        return id;
    }
    private static int link(int...shaders) {
        int id=GLES30.glCreateProgram();for(int s:shaders)GLES30.glAttachShader(id,s);GLES30.glLinkProgram(id);
        for(int s:shaders)GLES30.glDeleteShader(s);
        int[] ok=new int[1];GLES30.glGetProgramiv(id,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){String log=GLES30.glGetProgramInfoLog(id);GLES30.glDeleteProgram(id);throw new IllegalStateException(log);}
        return id;
    }
    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
        try {
            if(bench!=null)bench.fail(new IllegalStateException("Se recreo el contexto grafico"));
            fatal=false;renderer=GLES30.glGetString(GLES30.GL_RENDERER);vendor=GLES30.glGetString(GLES30.GL_VENDOR);version=GLES30.glGetString(GLES30.GL_VERSION);
            int[] major=new int[1],minor=new int[1];GLES30.glGetIntegerv(0x821B,major,0);GLES30.glGetIntegerv(0x821C,minor,0);
            need(major[0]>3||(major[0]==3&&minor[0]>=1),"Esta prueba requiere OpenGL ES 3.1");
            String identity=(renderer+" "+vendor).toLowerCase(Locale.ROOT);
            String device=(Build.FINGERPRINT+" "+Build.HARDWARE+" "+Build.MODEL).toLowerCase(Locale.ROOT);
            software=has(identity,"llvmpipe","softpipe","swiftshader","lavapipe","software rasterizer");
            emulated=has(device,"ranchu","goldfish","emulator","sdk_gphone","google_sdk","vbox86");
            hardware=!software&&!emulated&&has(identity,"mali","adreno","powervr","nvidia","tegra","xclipse","vivante","qualcomm","arm");
            need(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN,"El perfil requiere orden de bytes little-endian");
            String common=asset("scene.glsl");
            vertexSource="#version 310 es\n"+common+"\n"+asset("vertex.glsl");fragmentSource=asset("fragment.glsl");
            computeSource="#version 310 es\n"+common+"\nlayout(local_size_x=64) in;\nlayout(std430,binding=0) writeonly buffer Values { uvec4 values[]; };\nvoid main(){uint id=gl_GlobalInvocationID.x;if(id<parameters.x)values[id]=sceneValue(id);}\n";
            program=link(shader(GLES30.GL_VERTEX_SHADER,vertexSource),shader(GLES30.GL_FRAGMENT_SHADER,fragmentSource));
            compute=link(shader(GLES31.GL_COMPUTE_SHADER,computeSource));
            paramsAt=GLES30.glGetUniformLocation(program,"parameters");detailsAt=GLES30.glGetUniformLocation(program,"details[0]");
            modeAt=GLES30.glGetUniformLocation(program,"compactMode");cameraAt=GLES30.glGetUniformLocation(program,"camera");viewportAt=GLES30.glGetUniformLocation(program,"viewportSize");
            need(paramsAt>=0&&detailsAt>=0&&modeAt>=0&&cameraAt>=0&&viewportAt>=0,"Faltan parametros de la escena");
            int[] ids=new int[1];GLES30.glGenVertexArrays(1,ids,0);vao=ids[0];GLES30.glBindVertexArray(vao);
            GLES30.glGenBuffers(1,ids,0);vbo=ids[0];GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,MAX_OBJECTS*16,null,GLES30.GL_STREAM_DRAW);
            denseBytes=ByteBuffer.allocateDirect(MAX_OBJECTS*16).order(ByteOrder.LITTLE_ENDIAN);denseInts=denseBytes.asIntBuffer();
            GLES30.glGenFramebuffers(2,framebuffers,0);GLES30.glGenTextures(2,textures,0);
            for(int i=0;i<2;i++) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[i]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,SIDE,SIDE,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffers[i]);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[i],0);
                need(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE,"Framebuffer incompleto");
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);check();
            animationStart=System.nanoTime();liveStart=0;liveFrames=0;lastLive=0;
        } catch(Exception error){fatal=true;listener.progress("No se pudo abrir la escena:\n"+error);}
    }
    @Override public void onSurfaceChanged(GL10 unused,int w,int h){width=Math.max(1,w);height=Math.max(1,h);}
    private static final class Sample {
        final double preparationMs,submissionFinishMs,totalMs;
        final SceneState scene;
        Sample(long begin,long prepared,long done,SceneState scene){preparationMs=(prepared-begin)/1e6;submissionFinishMs=(done-prepared)/1e6;totalMs=(done-begin)/1e6;this.scene=scene;}
        JSONObject json()throws Exception{return new JSONObject().put("prepare_ms",preparationMs).put("submit_and_finish_ms",submissionFinishMs).put("complete_frame_ms",totalMs);}
    }
    private Sample draw(int route,int n,int tick,int target,int w,int h,boolean finish) {
        long begin=System.nanoTime();SceneState scene=new SceneState(n,tick,SEED);
        if(route==0){scene.materialize(denseInts);denseBytes.position(0);denseBytes.limit(n*16);}
        long prepared=System.nanoTime();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,w,h);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE);
        GLES30.glClearColor(0.007f,0.012f,0.035f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);GLES30.glBindVertexArray(vao);
        GLES30.glUniform1i(modeAt,route);GLES30.glUniform4uiv(paramsAt,1,scene.parameters(),0);
        if(route==0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,n*16,denseBytes);
            GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribIPointer(0,4,GLES30.GL_UNSIGNED_INT,16,0);GLES30.glVertexAttribDivisor(0,1);
        } else {
            GLES30.glDisableVertexAttribArray(0);GLES30.glVertexAttribI4ui(0,0,0,0,0);
            GLES30.glUniform4uiv(detailsAt,4,scene.details,0);
        }
        double angle=tick*0.003,tilt=0.23+Math.sin(tick*0.001)*0.18;
        GLES30.glUniform4f(cameraAt,(float)Math.cos(angle),(float)Math.sin(angle),(float)Math.cos(tilt),(float)Math.sin(tilt));
        GLES30.glUniform2f(viewportAt,w,h);GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES,0,6,n);
        if(finish)GLES30.glFinish();
        long done=System.nanoTime();check();return new Sample(begin,prepared,done,scene);
    }
    @Override public void onDrawFrame(GL10 unused) {
        if(fatal)return;
        try {
            if(benchmarkRequested&&bench==null){benchmarkRequested=false;bench=new Bench();}
            if(bench!=null){bench.step();return;}
            long now=System.nanoTime();int tick=(int)(((now-animationStart)*60)/1_000_000_000L);
            draw(mode,objects,tick,0,width,height,false);
            if(liveStart==0){liveStart=now;liveFrames=0;}liveFrames++;
            if(now-lastLive>=1_000_000_000L) {
                double fps=(now>liveStart&&liveFrames>1)?(liveFrames-1)*1e9/(now-liveStart):0;
                long bytes=mode==0?16L*objects+16:80;
                listener.live(String.format(Locale.ROOT,"%s · %,d luces\n%s · %.1f FPS app · %,d B de escena/cuadro",renderer,objects,mode==1?"REGLAS + CAMBIOS":"DATOS COMPLETOS",fps,bytes));
                lastLive=now;liveStart=now;liveFrames=1;
            }
        }catch(Exception error){if(bench!=null)bench.fail(error);else{fatal=true;listener.progress("Error de dibujo:\n"+error);}}
    }
    private byte[] pixels(int framebuffer) {
        ByteBuffer out=ByteBuffer.allocateDirect(SIDE*SIDE*4);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffer);
        GLES30.glReadPixels(0,0,SIDE,SIDE,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,out);check();
        byte[] bytes=new byte[out.capacity()];out.position(0);out.get(bytes);return bytes;
    }
    private byte[] computeValues(SceneState scene) {
        int[] ids=new int[1];GLES30.glGenBuffers(1,ids,0);int id=ids[0];
        try {
            GLES31.glBindBuffer(SSBO,id);GLES31.glBufferData(SSBO,scene.count*16,null,GLES30.GL_STREAM_DRAW);GLES31.glBindBufferBase(SSBO,0,id);
            GLES31.glUseProgram(compute);GLES31.glUniform4uiv(GLES31.glGetUniformLocation(compute,"parameters"),1,scene.parameters(),0);
            GLES31.glUniform4uiv(GLES31.glGetUniformLocation(compute,"details[0]"),4,scene.details,0);
            GLES31.glDispatchCompute((scene.count+63)/64,1,1);GLES31.glMemoryBarrier(0x2000|0x200);GLES31.glFinish();
            Buffer mapped=GLES30.glMapBufferRange(SSBO,0,scene.count*16,GLES30.GL_MAP_READ_BIT);
            need(mapped instanceof ByteBuffer,"No se pudo leer la escena calculada");byte[] result=new byte[scene.count*16];((ByteBuffer)mapped).position(0);((ByteBuffer)mapped).get(result);
            need(GLES30.glUnmapBuffer(SSBO),"Buffer invalido");check();return result;
        }finally{GLES30.glDeleteBuffers(1,ids,0);}
    }
    private static double percentile(double[] values,double q){double[] copy=values.clone();Arrays.sort(copy);return copy[(int)Math.ceil(q*(copy.length-1))];}
    private static JSONObject stats(double[] values)throws Exception {
        double sum=0;for(double v:values)sum+=v;
        return new JSONObject().put("median_ms",percentile(values,0.5)).put("p95_ms",percentile(values,0.95)).put("mean_ms",sum/values.length);
    }
    private final class Bench {
        final int[] counts={4096,16384,65536};
        final int warmup=8,samples=48;
        int workload=0,index=-warmup,verifiedPairs=0;
        long checkedWords=0;
        final JSONObject report=new JSONObject();
        final JSONArray cases=new JSONArray(),captures=new JSONArray(),rows=new JSONArray();
        final double[][] totals=new double[2][samples],preparation=new double[2][samples],submission=new double[2][samples];
        final File archive=new File(cache,"GPU-VIRTUAL-C05-RESULTADO.zip");
        final ZipOutputStream zip;
        boolean closed;
        Bench()throws Exception {
            zip=new ZipOutputStream(new FileOutputStream(archive));zip.setLevel(1);
            report.put("format","GPU-VIRTUAL-C05-graphics-v1");report.put("source_commit",BuildInfo.SOURCE_SHA);
            report.put("hardware_graphics_test_passed",false);report.put("status","RUNNING");report.put("device",Build.MODEL);
            report.put("manufacturer",Build.MANUFACTURER);report.put("fingerprint",Build.FINGERPRINT);report.put("android_api",Build.VERSION.SDK_INT);
            report.put("gl_vendor",vendor);report.put("gl_renderer",renderer);report.put("gl_version",version);report.put("hardware_driver_identified",hardware);
            report.put("software_renderer_detected",software);report.put("emulator_detected",emulated);
            report.put("hardware_evidence_basis","Android device profile, identified GL driver, compute buffer and framebuffer readback; no physical performance counters");
            report.put("width",SIDE);report.put("height",SIDE);report.put("warmup_pairs_per_workload",warmup);report.put("measured_pairs_per_workload",samples);
            report.put("cases",cases);report.put("captures",captures);report.put("samples",rows);
            report.put("timing_scope","CPU scene preparation + GL submission + glFinish; excludes validation/readback, ZIP, screen swap and presentation");
            report.put("ordering","Paired identical tick; alternates dense-first and compact-first; fixed workload order; no thermal stabilization");
            report.put("dense_baseline","CPU materializes 4 uint32 per light and uploads a reusable instanced buffer each frame; shared draw shader");
            report.put("compact_route","GPU evaluates C05 scene rules and four exact local changes; same instanced triangles and fragment shader");
            report.put("scope","Specialized C05 scene profile, own code. Does not run arbitrary shaders or import RFS1. No C01/Ether integration or automatic improvement of other games.");
            report.put("authoritative_scene_packet_bytes",84);report.put("scene_upload_basis","dense 16*N+16 bytes; compact 80 bytes; excludes other draw state including mode, camera, viewport, constant attributes and API overhead");
            report.put("dense_buffer_retained_for_comparison",true);report.put("dense_buffer_capacity_bytes",MAX_OBJECTS*16);
            report.put("pss_kib_start",Debug.getPss());report.put("pss_is_snapshot_not_peak",true);
            put("vertex.glsl",vertexSource.getBytes(StandardCharsets.UTF_8));put("fragment.glsl",fragmentSource.getBytes(StandardCharsets.UTF_8));put("compute.glsl",computeSource.getBytes(StandardCharsets.UTF_8));
            report.put("vertex_sha256",sha(vertexSource.getBytes(StandardCharsets.UTF_8)));report.put("fragment_sha256",sha(fragmentSource.getBytes(StandardCharsets.UTF_8)));report.put("compute_sha256",sha(computeSource.getBytes(StandardCharsets.UTF_8)));
            listener.progress("Comparando las dos rutas. Misma escena, 512 × 512.\nLa lectura de capturas queda fuera de los tiempos.");
        }
        void put(String name,byte[] bytes)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
        void step()throws Exception {
            int n=counts[workload],tick=1024+(index+warmup)*11;Sample dense,compact;
            if((index&1)==0){dense=draw(0,n,tick,framebuffers[0],SIDE,SIDE,true);compact=draw(1,n,tick,framebuffers[1],SIDE,SIDE,true);}
            else{compact=draw(1,n,tick,framebuffers[1],SIDE,SIDE,true);dense=draw(0,n,tick,framebuffers[0],SIDE,SIDE,true);}
            if(index>=0) {
                Sample[] pair={dense,compact};
                for(int route=0;route<2;route++){totals[route][index]=pair[route].totalMs;preparation[route][index]=pair[route].preparationMs;submission[route][index]=pair[route].submissionFinishMs;}
                rows.put(new JSONObject().put("n",n).put("sample",index).put("tick",tick).put("dense_first",(index&1)==0).put("dense",dense.json()).put("compact",compact.json()));
                if(index==0||index==samples/2||index==samples-1) {
                    byte[] a=pixels(framebuffers[0]),b=pixels(framebuffers[1]);String prefix=n+"-"+tick;
                    put(prefix+"-dense.rgba",a);put(prefix+"-compact.rgba",b);put(prefix+".xscene",dense.scene.packet());
                    boolean equal=Arrays.equals(a,b);
                    captures.put(new JSONObject().put("prefix",prefix).put("n",n).put("tick",tick).put("pixel_equal",equal).put("dense_sha256",sha(a)).put("compact_sha256",sha(b)));
                    need(equal,"Las dos rutas produjeron pixeles distintos");verifiedPairs++;
                    byte[] actual=computeValues(dense.scene);dense.scene.materialize(denseInts);denseBytes.position(0);denseBytes.limit(n*16);byte[] expected=new byte[n*16];denseBytes.get(expected);
                    put(prefix+"-gpu-state.u32",actual);need(Arrays.equals(actual,expected),"El estado GPU difiere de la proyeccion CPU");checkedWords+=n*4L;
                }
            }
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,framebuffers[mode]);GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,0);
            GLES30.glBlitFramebuffer(0,0,SIDE,SIDE,0,0,width,height,GLES30.GL_COLOR_BUFFER_BIT,GLES30.GL_LINEAR);check();
            index++;
            if(index%8==0)listener.progress("Comparacion "+(workload+1)+" / 3 · "+n+" luces\n"+Math.max(0,index)+" / "+samples+" pares medidos");
            if(index==samples) {
                JSONObject result=new JSONObject().put("n",n).put("dense_complete",stats(totals[0])).put("compact_complete",stats(totals[1]))
                    .put("dense_prepare",stats(preparation[0])).put("compact_prepare",stats(preparation[1]))
                    .put("dense_submit_finish",stats(submission[0])).put("compact_submit_finish",stats(submission[1]))
                    .put("dense_scene_upload_bytes",16L*n+16).put("compact_scene_upload_bytes",80)
                    .put("median_time_ratio_dense_over_compact",percentile(totals[0],0.5)/percentile(totals[1],0.5));
                cases.put(result);workload++;index=-warmup;
                if(workload==counts.length)finish();
            }
        }
        void finish()throws Exception {
            need(verifiedPairs==9&&checkedWords==1_032_192L,"Cobertura incompleta");
            report.put("hardware_graphics_test_passed",hardware);report.put("all_sampled_framebuffers_exact",true);report.put("gpu_state_matches_cpu",true);
            report.put("status",hardware?"PASSED_ON_IDENTIFIED_HARDWARE_DRIVER":"SOFTWARE_OR_UNIDENTIFIED_DRIVER");
            close();
        }
        void fail(Exception error) {
            if(closed)return;
            try{report.put("hardware_graphics_test_passed",false);report.put("status","NOT_VERIFIED");report.put("error",error.toString());close();}
            catch(Exception secondary){closed=true;bench=null;listener.progress("No se pudo completar el informe: "+secondary);}
        }
        void close()throws Exception {
            report.put("verified_image_pairs",verifiedPairs);report.put("gpu_state_words_checked",checkedWords);report.put("pss_kib_end",Debug.getPss());
            put("report.json",report.toString(2).getBytes(StandardCharsets.UTF_8));zip.close();closed=true;bench=null;
            StringBuilder text=new StringBuilder();
            if(!report.optBoolean("hardware_graphics_test_passed"))text.append("Resultado sin verificar en GPU fisica.\n").append(report.optString("error","Driver sin clasificar")).append("\n");
            else text.append("Imagenes y estado coinciden en la GPU.\n");
            for(int i=0;i<cases.length();i++) {
                JSONObject c=cases.getJSONObject(i);double d=c.getJSONObject("dense_complete").getDouble("median_ms"),s=c.getJSONObject("compact_complete").getDouble("median_ms");
                text.append(String.format(Locale.ROOT,"%,d luces: %.2f → %.2f ms/cuadro\n",c.getInt("n"),d,s));
            }
            text.append("Completo → compacto. Tiempos sin presentacion de pantalla.");
            listener.result(archive,report,text.toString());liveStart=0;liveFrames=0;
        }
    }
}
