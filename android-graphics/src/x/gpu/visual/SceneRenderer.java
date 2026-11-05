package x.gpu.visual;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Debug;
import android.util.Log;
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
import java.util.concurrent.CancellationException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dos proyecciones del mismo estado; el driver GL es un backend de salida. */
public final class SceneRenderer implements GLSurfaceView.Renderer {
    public interface Listener {
        void live(String text);
        void progress(String text);
        void result(File file,JSONObject report,String summary);
        void failure(String text);
    }
    private final AssetManager assets;
    private final File cache;
    private final Listener listener;
    private volatile int mode=1,objects=65536;
    private volatile boolean benchmarkRequested;
    private volatile boolean stopRequested;
    private boolean sustainedRunning;
    private volatile PendingSustained sustainedRequest;
    private final ProbeMonitor monitor=new ProbeMonitor();
    private final StartupState startup=new StartupState(System.nanoTime());
    private int legacyProgram,advancedProgram;
    private int[] legacyBindings,advancedBindings;
    private boolean advancedReady;
    private long startupFence;
    private static final class PendingSustained {
        final int count;final DeviceInfo info;final BenchmarkPlan plan;
        PendingSustained(int count,DeviceInfo info,BenchmarkPlan plan){this.count=count;this.info=info;this.plan=plan;}
    }
    private int width=1,height=1,program,compute,vbo,vao,gpuVbo,hashVbo,hashCount;
    private int targetWidth=512,targetHeight=512,computeParamsAt,computeDetailsAt,computeCachedAt;
    private long hashSetupNs;
    private int paramsAt,detailsAt,modeAt,cameraAt,viewportAt;
    private final int[] framebuffers=new int[2],textures=new int[2];
    private final int[] gpuSlots=new int[16];
    private ByteBuffer denseBytes;
    private IntBuffer denseInts;
    private String renderer="Sin identificar",vendor="",version="";
    private boolean hardware,fatal,software,emulated;
    private long animationStart,liveStart,lastLive;
    private int liveFrames;
    private Bench bench;
    private String vertexSource,fragmentSource,computeSource;
    private static final int MAX_OBJECTS=262144,SIDE=512,SSBO=0x90D2;
    private static final int SEED=0x00c0511a;
    public SceneRenderer(AssetManager assets,File cache,Listener listener){this.assets=assets;this.cache=cache;this.listener=listener;}
    public int mode(){return mode;}
    public void mode(int value){if(value<0||value>=BenchmarkPlan.ROUTES)throw new IllegalArgumentException("Metodo desconocido");mode=value;liveStart=0;liveFrames=0;}
    public void objects(int value){if(value<1||value>MAX_OBJECTS)throw new IllegalArgumentException("Fuera de la carga de este instrumento");objects=value;liveStart=0;liveFrames=0;}
    public void compare(){benchmarkRequested=true;}
    public ProbeMonitor.Snapshot probe(){return monitor.snapshot(System.nanoTime());}
    public StartupState.Snapshot startup(){return startup.snapshot(System.nanoTime());}
    private void startupStage(String stage,String label){startup.stage(stage,label,System.nanoTime());Log.i("GPU-VIRTUAL",stage+": "+label);}
    /** No hace llamadas GL desde la UI: el siguiente onDrawFrame inicia el ensayo. */
    public void requestSustained(int count,DeviceInfo info,BenchmarkPlan plan){
        need(count>=1&&count<=MAX_OBJECTS,"Carga fuera de la demo");
        stopRequested=false;monitor.queue(count,System.nanoTime());sustainedRequest=new PendingSustained(count,info,plan);
    }
    public void requestStop(){stopRequested=true;monitor.requestStop();}
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
    private int compileGraphics(String vertex,String fragment,String prefix)throws Exception {
        int v=0,f=0;
        try {
            startupStage(prefix+"_VERTEX","Compilando vertex shader "+prefix);v=shader(GLES30.GL_VERTEX_SHADER,vertex);
            startupStage(prefix+"_FRAGMENT","Compilando fragment shader "+prefix);f=shader(GLES30.GL_FRAGMENT_SHADER,fragment);
            startupStage(prefix+"_LINK","Enlazando programa "+prefix);int vv=v,ff=f;v=f=0;return link(vv,ff);
        }finally{if(v!=0)GLES30.glDeleteShader(v);if(f!=0)GLES30.glDeleteShader(f);}
    }
    private int[] bindings(int id) {
        int[] result={GLES30.glGetUniformLocation(id,"parameters"),GLES30.glGetUniformLocation(id,"details[0]"),
            GLES30.glGetUniformLocation(id,"compactMode"),GLES30.glGetUniformLocation(id,"camera"),GLES30.glGetUniformLocation(id,"viewportSize")};
        for(int at:result)need(at>=0,"Faltan parametros del programa "+id);return result;
    }
    private void selectGraphics(int id,int[] at){program=id;paramsAt=at[0];detailsAt=at[1];modeAt=at[2];cameraAt=at[3];viewportAt=at[4];}
    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config) {
        try {
            if(bench!=null)bench.fail(new IllegalStateException("Se recreo el contexto grafico"));
            startup.reset(System.nanoTime());fatal=false;advancedReady=false;startupFence=0;mode=1;
            program=compute=legacyProgram=advancedProgram=vbo=gpuVbo=hashVbo=vao=hashCount=0;
            legacyBindings=advancedBindings=null;denseBytes=null;denseInts=null;hashSetupNs=0;
            Arrays.fill(gpuSlots,0);Arrays.fill(framebuffers,0);Arrays.fill(textures,0);
            startupStage("DRIVER","Identificando GPU y version OpenGL ES");
            renderer=GLES30.glGetString(GLES30.GL_RENDERER);vendor=GLES30.glGetString(GLES30.GL_VENDOR);version=GLES30.glGetString(GLES30.GL_VERSION);
            int[] major=new int[1],minor=new int[1];GLES30.glGetIntegerv(0x821B,major,0);GLES30.glGetIntegerv(0x821C,minor,0);
            need(major[0]>3||(major[0]==3&&minor[0]>=1),"Esta prueba requiere OpenGL ES 3.1");
            String identity=(renderer+" "+vendor).toLowerCase(Locale.ROOT);
            String device=(Build.FINGERPRINT+" "+Build.HARDWARE+" "+Build.MODEL).toLowerCase(Locale.ROOT);
            software=has(identity,"llvmpipe","softpipe","swiftshader","lavapipe","software rasterizer");
            emulated=has(device,"ranchu","goldfish","emulator","sdk_gphone","google_sdk","vbox86");
            hardware=!software&&!emulated&&has(identity,"mali","adreno","powervr","nvidia","tegra","xclipse","vivante","qualcomm","arm");
            need(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN,"El perfil requiere orden de bytes little-endian");
            startupStage("LEGACY_ASSETS","Leyendo los shaders C06 verificados en el telefono");
            String legacyVertex="#version 310 es\n"+asset("startup-scene.glsl")+"\n"+asset("startup-vertex.glsl");
            String fragment=asset("fragment.glsl");
            legacyProgram=compileGraphics(legacyVertex,fragment,"C06");
            startupStage("LEGACY_BINDINGS","Preparando parametros del visor compatible");legacyBindings=bindings(legacyProgram);selectGraphics(legacyProgram,legacyBindings);
            startupStage("LEGACY_VAO","Preparando geometria del visor");
            int[] ids=new int[1];GLES30.glGenVertexArrays(1,ids,0);vao=ids[0];GLES30.glBindVertexArray(vao);check();
            animationStart=System.nanoTime();liveStart=0;liveFrames=0;lastLive=0;
            startupStage("WAIT_DRAW","Esperando primer cuadro compatible");
        }catch(Throwable error){fatal=true;sustainedRequest=null;startup.fail(error.toString(),true,System.nanoTime());monitor.finish("ERROR",error.toString(),System.nanoTime());Log.e("GPU-VIRTUAL","Startup failed",error);listener.failure("No se pudo abrir la escena:\n"+error);}
    }
    private void releaseAdvanced() {
        if(advancedProgram!=0)GLES30.glDeleteProgram(advancedProgram);if(compute!=0)GLES30.glDeleteProgram(compute);
        int[] buffers={vbo,gpuVbo,hashVbo};GLES30.glDeleteBuffers(buffers.length,buffers,0);GLES30.glDeleteBuffers(gpuSlots.length,gpuSlots,0);
        GLES30.glDeleteFramebuffers(2,framebuffers,0);GLES30.glDeleteTextures(2,textures,0);
        advancedProgram=compute=vbo=gpuVbo=hashVbo=hashCount=0;advancedReady=false;denseBytes=null;denseInts=null;
        Arrays.fill(gpuSlots,0);Arrays.fill(framebuffers,0);Arrays.fill(textures,0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glBindVertexArray(vao);
        for(int i=0;i<16&&GLES30.glGetError()!=0;i++){}
        if(legacyProgram!=0&&legacyBindings!=null)selectGraphics(legacyProgram,legacyBindings);
    }
    private void ensureAdvanced()throws Exception {
        if(advancedReady)return;startup.beginAdvanced(System.nanoTime());
        try {
            startupStage("ADVANCED_ASSETS","Leyendo los shaders de los cuatro metodos");
            String common=asset("scene.glsl");
            vertexSource="#version 310 es\n"+common+"\n"+asset("vertex.glsl");fragmentSource=asset("fragment.glsl");computeSource="#version 310 es\n"+common+"\n"+asset("compute.glsl");
            advancedProgram=compileGraphics(vertexSource,fragmentSource,"C08");
            startupStage("ADVANCED_COMPUTE","Compilando compute para datos GPU y cache");compute=link(shader(GLES31.GL_COMPUTE_SHADER,computeSource));
            startupStage("ADVANCED_BINDINGS","Preparando parametros de los cuatro metodos");
            computeParamsAt=GLES30.glGetUniformLocation(compute,"parameters");computeDetailsAt=GLES30.glGetUniformLocation(compute,"details[0]");computeCachedAt=GLES30.glGetUniformLocation(compute,"cachedMode");
            need(computeParamsAt>=0&&computeDetailsAt>=0&&computeCachedAt>=0,"Faltan parametros compute");advancedBindings=bindings(advancedProgram);
            startupStage("ADVANCED_BUFFERS","Reservando buffers de comparacion");
            int[] ids=new int[1];GLES30.glGenBuffers(1,ids,0);vbo=ids[0];GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,MAX_OBJECTS*16,null,GLES30.GL_STREAM_DRAW);
            denseBytes=ByteBuffer.allocateDirect(MAX_OBJECTS*16).order(ByteOrder.LITTLE_ENDIAN);denseInts=denseBytes.asIntBuffer();
            GLES30.glGenBuffers(1,ids,0);gpuVbo=ids[0];GLES30.glBindBuffer(SSBO,gpuVbo);GLES30.glBufferData(SSBO,MAX_OBJECTS*16,null,GLES30.GL_DYNAMIC_DRAW);
            GLES30.glGenBuffers(gpuSlots.length,gpuSlots,0);
            GLES30.glGenBuffers(1,ids,0);hashVbo=ids[0];GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,hashVbo);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,MAX_OBJECTS*4,null,GLES30.GL_STATIC_DRAW);
            startupStage("ADVANCED_CACHE","Preparando cache estable");prepareHashCache(objects);
            startupStage("ADVANCED_TARGETS","Preparando imagenes fuera de pantalla");
            GLES30.glGenFramebuffers(2,framebuffers,0);GLES30.glGenTextures(2,textures,0);ensureTargets(SIDE,SIDE);
            selectGraphics(advancedProgram,advancedBindings);advancedReady=true;startup.advancedReady(System.nanoTime());check();
        }catch(Throwable error){releaseAdvanced();mode=1;startup.fail(error.toString(),false,System.nanoTime());Log.e("GPU-VIRTUAL","Advanced initialization failed",error);throw new IllegalStateException("Fallo en funciones nuevas; se conserva el visor C06: "+error,error);}
    }
    private void ensureTargets(int w,int h) {
            targetWidth=w;targetHeight=h;
            for(int i=0;i<2;i++) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[i]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffers[i]);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[i],0);
                need(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE,"Framebuffer incompleto");
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);check();
    }
    private void prepareHashCache(int n) {
        if(hashCount>=n)return;long start=System.nanoTime();ByteBuffer bytes=ByteBuffer.allocateDirect(n*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int id=0;id<n;id++)bytes.putInt(SceneState.mix(id^SEED));bytes.flip();
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,hashVbo);GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,n*4,bytes);
        hashCount=n;hashSetupNs+=System.nanoTime()-start;check();
    }
    private void prepareGpuSlots(int n,int depth) {
        GLES31.glMemoryBarrier(0x200);GLES30.glFinish();
        for(int i=0;i<gpuSlots.length;i++){GLES30.glBindBuffer(SSBO,gpuSlots[i]);GLES30.glBufferData(SSBO,i<depth?n*16:0,null,GLES30.GL_DYNAMIC_DRAW);}
        check();
    }
    private void dispatchValues(SceneState scene,int buffer,boolean cached){dispatchValues(scene,buffer,cached,false);}
    private void dispatchValues(SceneState scene,int buffer,boolean cached,boolean priorBatchCompleted) {
        // ES 3.1 section 7.11: finish earlier vertex fetches before overwriting their SSBO storage.
        // Timed ring slots are only reused after every fence in their prior batch signaled.
        if(!priorBatchCompleted)GLES31.glMemoryBarrier(0x2000);
        GLES31.glUseProgram(compute);GLES31.glBindBufferBase(SSBO,0,buffer);GLES31.glBindBufferBase(SSBO,1,hashVbo);
        GLES31.glUniform4uiv(computeParamsAt,1,scene.parameters(),0);GLES31.glUniform4uiv(computeDetailsAt,4,scene.details,0);GLES31.glUniform1i(computeCachedAt,cached?1:0);
        GLES31.glDispatchCompute((scene.count+63)/64,1,1);GLES31.glMemoryBarrier(0x1);
    }
    @Override public void onSurfaceChanged(GL10 unused,int w,int h){width=Math.max(1,w);height=Math.max(1,h);}
    private static final class Sample {
        final double preparationMs,submissionFinishMs,totalMs;
        final SceneState scene;
        Sample(long begin,long prepared,long done,SceneState scene){preparationMs=(prepared-begin)/1e6;submissionFinishMs=(done-prepared)/1e6;totalMs=(done-begin)/1e6;this.scene=scene;}
        JSONObject json()throws Exception{return new JSONObject().put("prepare_ms",preparationMs).put("submit_and_finish_ms",submissionFinishMs).put("complete_frame_ms",totalMs);}
    }
    private Sample draw(int route,int n,int tick,int target,int w,int h,boolean finish) {
        return draw(route,n,tick,target,w,h,finish,gpuVbo,false);
    }
    private Sample draw(int route,int n,int tick,int target,int w,int h,boolean finish,int stateBuffer,boolean priorBatchCompleted) {
        long begin=System.nanoTime();SceneState scene=new SceneState(n,tick,SEED);
        if(route==0){scene.materialize(denseInts);denseBytes.position(0);denseBytes.limit(n*16);}
        if(route==2)prepareHashCache(n);
        long prepared=System.nanoTime();
        if(route==3)dispatchValues(scene,stateBuffer,false,priorBatchCompleted);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,w,h);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA,GLES30.GL_ONE);
        GLES30.glClearColor(0.007f,0.012f,0.035f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);GLES30.glBindVertexArray(vao);
        GLES30.glUniform1i(modeAt,route);GLES30.glUniform4uiv(paramsAt,1,scene.parameters(),0);
        GLES30.glDisableVertexAttribArray(1);GLES30.glVertexAttribI4ui(1,0,0,0,0);
        if(route==0||route==3) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,route==0?vbo:stateBuffer);if(route==0)GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,n*16,denseBytes);
            GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribIPointer(0,4,GLES30.GL_UNSIGNED_INT,16,0);GLES30.glVertexAttribDivisor(0,1);
        } else {
            GLES30.glDisableVertexAttribArray(0);GLES30.glVertexAttribI4ui(0,0,0,0,0);
            GLES30.glUniform4uiv(detailsAt,4,scene.details,0);
            if(route==2){GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,hashVbo);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribIPointer(1,1,GLES30.GL_UNSIGNED_INT,4,0);GLES30.glVertexAttribDivisor(1,1);}
        }
        double angle=tick*0.003,tilt=0.23+Math.sin(tick*0.001)*0.18;
        GLES30.glUniform4f(cameraAt,(float)Math.cos(angle),(float)Math.sin(angle),(float)Math.cos(tilt),(float)Math.sin(tilt));
        GLES30.glUniform2f(viewportAt,w,h);GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES,0,6,n);
        if(finish)GLES30.glFinish();
        long done=System.nanoTime();check();return new Sample(begin,prepared,done,scene);
    }
    @Override public void onDrawFrame(GL10 unused) {
        if(fatal){
            if(sustainedRequest!=null||benchmarkRequested){sustainedRequest=null;benchmarkRequested=false;monitor.finish("ERROR","La escena no tiene contexto listo",System.nanoTime());listener.failure("La escena no tiene contexto listo para medir.");}
            return;
        }
        try {
            if(startupFence!=0){
                int status=GLES30.glClientWaitSync(startupFence,0,0);
                if(status==GLES30.GL_ALREADY_SIGNALED||status==GLES30.GL_CONDITION_SATISFIED){GLES30.glDeleteSync(startupFence);startupFence=0;startup.confirmed(System.nanoTime());}
                else need(status==GLES30.GL_TIMEOUT_EXPIRED,"Fallo al confirmar el primer cuadro");
            }
            boolean ready=startup().ready;
            PendingSustained pending=sustainedRequest;
            if(ready&&pending!=null){sustainedRequest=null;sustained(pending.count,pending.info,pending.plan);return;}
            if(ready&&benchmarkRequested&&bench==null){benchmarkRequested=false;bench=new Bench();}
            if(bench!=null){bench.step();return;}
            if(ready&&mode!=1)ensureAdvanced();
            long now=System.nanoTime();int tick=(int)(((now-animationStart)*60)/1_000_000_000L);
            if(!startup().submitted)startupStage("FIRST_DRAW","Dibujando el primer cuadro C06");
            draw(mode,objects,tick,0,width,height,false);
            if(!startup().submitted){startupFence=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0);need(startupFence!=0,"No se pudo crear la confirmacion inicial");GLES30.glFlush();startup.submitted(System.nanoTime());}
            if(liveStart==0){liveStart=now;liveFrames=0;}liveFrames++;
            if(startup().ready&&now-lastLive>=1_000_000_000L) {
                double fps=(now>liveStart&&liveFrames>1)?(liveFrames-1)*1e9/(now-liveStart):0;
                long bytes=mode==0?16L*objects+16:mode==3?96:80;
                listener.live(String.format(Locale.ROOT,"%s · %,d luces\n%s · %.1f FPS visor · %,d B enviados/cuadro%s",renderer,objects,BenchmarkPlan.name(mode),fps,bytes,mode==2?" · cache "+(objects*4L)+" B":""));
                lastLive=now;liveStart=now;liveFrames=1;
            }
        }catch(Throwable error){
            if(bench!=null)bench.fail(new IllegalStateException(error));
            else if(legacyProgram!=0&&startup().ready){releaseAdvanced();mode=1;startup.fail(error.toString(),false,System.nanoTime());listener.failure("Visor compatible C06 activo. No se pudo abrir la ruta nueva:\n"+error);}
            else{fatal=true;startup.fail(error.toString(),true,System.nanoTime());listener.failure("Error de dibujo:\n"+error);}
            Log.e("GPU-VIRTUAL","Draw failed",error);
        }
    }
    private byte[] pixels(int framebuffer) {
        ByteBuffer out=ByteBuffer.allocateDirect(targetWidth*targetHeight*4);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffer);
        GLES30.glReadPixels(0,0,targetWidth,targetHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,out);check();
        byte[] bytes=new byte[out.capacity()];out.position(0);out.get(bytes);return bytes;
    }
    private byte[] computeValues(SceneState scene){return computeValues(scene,false);}
    private byte[] readBuffer(int buffer,int bytes) {
        GLES31.glMemoryBarrier(0x200);GLES30.glFinish();GLES30.glBindBuffer(SSBO,buffer);
        Buffer mapped=GLES30.glMapBufferRange(SSBO,0,bytes,GLES30.GL_MAP_READ_BIT);
        need(mapped instanceof ByteBuffer,"No se pudo leer el buffer GPU");byte[] result=new byte[bytes];((ByteBuffer)mapped).position(0);((ByteBuffer)mapped).get(result);
        need(GLES30.glUnmapBuffer(SSBO),"Buffer invalido");check();return result;
    }
    private byte[] computeValues(SceneState scene,boolean cached) {
        int[] ids=new int[1];GLES30.glGenBuffers(1,ids,0);int id=ids[0];
        try {
            GLES31.glBindBuffer(SSBO,id);GLES31.glBufferData(SSBO,scene.count*16,null,GLES30.GL_STREAM_DRAW);
            dispatchValues(scene,id,cached);return readBuffer(id,scene.count*16);
        }finally{GLES30.glDeleteBuffers(1,ids,0);}
    }
    private static double percentile(double[] values,double q){double[] copy=values.clone();Arrays.sort(copy);double at=q*(copy.length-1);int low=(int)Math.floor(at),high=(int)Math.ceil(at);return copy[low]+(copy[high]-copy[low])*(at-low);}
    private static JSONObject stats(double[] values)throws Exception {
        double sum=0;for(double v:values)sum+=v;
        return new JSONObject().put("median_ms",percentile(values,0.5)).put("p95_ms",percentile(values,0.95)).put("mean_ms",sum/values.length);
    }
    public interface DeviceInfo { JSONObject read()throws Exception; }
    /** Arranca desde onDrawFrame con contexto y superficie listos; no presenta en su bucle. */
    private void sustained(int n,DeviceInfo deviceInfo,BenchmarkPlan plan) {
        if(sustainedRunning)return;
        sustainedRunning=true;
        try {
            monitor.phase("STARTING",-1,-1,System.nanoTime());
            need(!fatal&&program!=0&&android.opengl.EGL14.eglGetCurrentContext()!=android.opengl.EGL14.EGL_NO_CONTEXT,"Espera a que aparezca la escena antes de medir");need(bench==null,"Ya hay una prueba activa");
            new Sustained(n,deviceInfo,plan).run();
        } catch(Exception error){monitor.finish("ERROR",error.toString(),System.nanoTime());listener.failure("No se pudo completar la prueba sostenida: "+error);}
        finally{sustainedRunning=false;liveStart=0;liveFrames=0;}
    }
    private JSONObject monitorJson(ProbeMonitor.Snapshot s)throws Exception {
        return new JSONObject().put("active",s.active).put("stop_requested",s.stopRequested).put("stage",s.stage).put("operation",s.operation)
            .put("count",s.count).put("route",s.route).put("block_zero_based",s.block).put("elapsed_ns",s.elapsedNs)
            .put("operation_elapsed_ns",s.operationNs).put("since_confirmation_ns",s.sinceConfirmationNs).put("waiting_for_confirmation",s.waiting)
            .put("frames_submitted",s.submitted).put("frames_confirmed_including_warmup",s.confirmed).put("frames_confirmed_during_measurement",s.measured)
            .put("latest_window_fps",s.windowFps<0?JSONObject.NULL:s.windowFps).put("message",s.message);
    }
    public JSONObject diagnostic()throws Exception {
        return new JSONObject().put("format","GPU-VIRTUAL-C08R-diagnostic-v1").put("source_commit",BuildInfo.SOURCE_SHA)
            .put("completed_benchmark_result",false).put("hardware_sustained_test_passed",false).put("device",Build.MODEL)
            .put("monitor",monitorJson(probe())).put("startup",startupJson()).put("gl_renderer",renderer).put("gl_vendor",vendor).put("gl_version",version)
            .put("scope","UI snapshot only; elapsed timer is not evidence of GPU progress. Frames increment only after signaled GLsync.");
    }
    private JSONObject startupJson()throws Exception {
        StartupState.Snapshot s=startup();JSONArray events=new JSONArray();
        for(StartupState.Event e:startup.events())events.put(new JSONObject().put("stage",e.stage).put("label",e.label).put("elapsed_ns",e.elapsedNs));
        return new JSONObject().put("stage",s.stage).put("label",s.label).put("error",s.error).put("preview_ready",s.ready).put("first_frame_submitted",s.submitted)
            .put("loading_advanced",s.loadingAdvanced).put("advanced_ready",s.advancedReady).put("advanced_failed",s.advancedFailed).put("fatal",s.fatal)
            .put("elapsed_ns",s.elapsedNs).put("stage_elapsed_ns",s.phaseNs).put("waiting",s.waiting).put("events",events);
    }
    private void continuing(){if(stopRequested)throw new CancellationException("Prueba interrumpida");}
    private int finishBatch(int route,int n,long firstFrame,int batchSize) {
        long[] fences=new long[batchSize];int submitted=0,finished=0;
        try {
            for(int j=0;j<batchSize;j++) {
                continuing();int tick=1024+(int)((firstFrame+j)%64)*8;
                monitor.operation("PREPARE_AND_SUBMIT",System.nanoTime());
                draw(route,n,tick,framebuffers[route&1],targetWidth,targetHeight,false,route==3?gpuSlots[j]:gpuVbo,route==3);
                fences[j]=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                need(fences[j]!=0,"No se pudo crear la confirmacion GPU");submitted++;monitor.submitted();
            }
            monitor.operation("FLUSH",System.nanoTime());
            GLES30.glFlush();
            for(int j=0;j<submitted;j++) {
                monitor.operation("WAIT_FENCE",System.nanoTime());
                long waitStart=System.nanoTime();
                while(true) {
                    continuing();
                    int result=GLES30.glClientWaitSync(fences[j],0,1_000_000L);
                    if(result==GLES30.GL_ALREADY_SIGNALED||result==GLES30.GL_CONDITION_SATISFIED){finished++;monitor.confirmed(System.nanoTime());break;}
                    need(result==GLES30.GL_TIMEOUT_EXPIRED,"Fallo al confirmar un cuadro GPU");
                    need(System.nanoTime()-waitStart<30_000_000_000L,"La GPU no completo el lote dentro del plazo de diagnostico");
                }
            }
            check();return finished;
        } finally {monitor.operation("RELEASE_FENCES",System.nanoTime());for(long fence:fences)if(fence!=0)GLES30.glDeleteSync(fence);}
    }
    private final class Sustained {
        final int n;
        final DeviceInfo deviceInfo;
        final BenchmarkPlan plan;
        final String runId=Long.toString(System.currentTimeMillis());
        final File archive;
        final ZipOutputStream zip;
        final JSONObject report=new JSONObject();
        final JSONArray blocks=new JSONArray(),windows=new JSONArray(),captures=new JSONArray();
        final SustainedStats totals=new SustainedStats(BenchmarkPlan.ROUTES);
        final long[] frameIndex=new long[BenchmarkPlan.ROUTES];
        int validatedPairs;
        long verifiedWords;
        long ringVerifiedWords;
        boolean powerFree=true;
        Sustained(int n,DeviceInfo deviceInfo,BenchmarkPlan plan)throws Exception {
            this.n=n;this.deviceInfo=deviceInfo;this.plan=plan;
            need(n>=1&&n<=MAX_OBJECTS,"Carga fuera del instrumento");
            archive=new File(cache,"GPU-VIRTUAL-C08R-"+n+"-"+plan.width+"x"+plan.height+"-B"+plan.pipeline+"-"+runId+".zip");
            zip=new ZipOutputStream(new FileOutputStream(archive));zip.setLevel(1);
        }
        void put(String name,byte[] value)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();}
        JSONObject device()throws Exception {
            JSONObject value=deviceInfo.read();
            if(!value.has("power_save_mode")||value.optBoolean("power_save_mode",true))powerFree=false;
            return value;
        }
        void validate(String prefix,int tick)throws Exception {
            monitor.phase(prefix.equals("before")?"VALIDATING_BEFORE":"VALIDATING_AFTER",-1,-1,System.nanoTime());
            monitor.operation("VERIFY_IMAGES_AND_STATE",System.nanoTime());continuing();
            Sample dense=draw(0,n,tick,framebuffers[0],plan.width,plan.height,true);
            byte[] image=pixels(framebuffers[0]);put(prefix+"-dense.rgba",image);put(prefix+".xscene",dense.scene.packet());
            dense.scene.materialize(denseInts);denseBytes.position(0);denseBytes.limit(n*16);
            byte[] expected=new byte[n*16];denseBytes.get(expected);
            JSONArray states=new JSONArray();
            for(int route=1;route<BenchmarkPlan.ROUTES;route++) {
                continuing();draw(route,n,tick,framebuffers[route&1],plan.width,plan.height,true);
                byte[] actualImage=pixels(framebuffers[route&1]);put(prefix+"-route"+route+".rgba",actualImage);
                need(Arrays.equals(image,actualImage),"Imagen distinta: "+BenchmarkPlan.name(route));validatedPairs++;
                byte[] state=route==3?readBuffer(gpuVbo,n*16):computeValues(dense.scene,route==2);
                put(prefix+"-route"+route+"-gpu-state.u32",state);
                need(Arrays.equals(expected,state),"Estado distinto: "+BenchmarkPlan.name(route));verifiedWords+=n*4L;
                states.put(new JSONObject().put("route",route).put("state_sha256",sha(state)));
            }
            captures.put(new JSONObject().put("prefix",prefix).put("tick",tick).put("n",n).put("image_sha256",sha(image)).put("states",states));
        }
        void warm(int route)throws Exception {
            monitor.phase("WARMUP",route,-1,System.nanoTime());monitor.operation("WAIT_PREVIOUS",System.nanoTime());
            continuing();listener.progress(BenchmarkPlan.name(route)+" · calentamiento 2 s");
            GLES30.glFinish();long begin=System.nanoTime(),frames=0;
            do{frames+=finishBatch(route,n,frames,plan.pipeline);}while(System.nanoTime()-begin<2_000_000_000L);
        }
        void validateTimedRing()throws Exception {
            monitor.phase("VALIDATING_RING",3,-1,System.nanoTime());monitor.operation("VERIFY_IMAGES_AND_STATE",System.nanoTime());
            JSONArray slots=new JSONArray();
            for(int slot=0;slot<plan.pipeline;slot++) {
                continuing();int tick=1024+(int)((frameIndex[3]-plan.pipeline+slot)%64)*8;
                SceneState scene=new SceneState(n,tick,SEED);scene.materialize(denseInts);denseBytes.position(0);denseBytes.limit(n*16);
                byte[] expected=new byte[n*16];denseBytes.get(expected);byte[] actual=readBuffer(gpuSlots[slot],n*16);
                need(Arrays.equals(expected,actual),"Buffer del lote GPU distinto: "+slot);ringVerifiedWords+=n*4L;
                slots.put(new JSONObject().put("slot",slot).put("tick",tick).put("state_sha256",sha(actual)));
                if(slot==plan.pipeline-1){put("gpu-ring-last.xscene",scene.packet());put("gpu-ring-last.u32",actual);}
            }
            report.put("timed_gpu_ring_final_slots",slots);
        }
        void window(int phase,int route,long frames,long elapsed,boolean full)throws Exception {
            windows.put(new JSONObject().put("block",phase).put("route",route).put("round",plan.round(phase)).put("frames_completed",frames)
                .put("elapsed_ns",elapsed).put("fps",frames*(1e9/elapsed)).put("full_second_window",full));
        }
        void measure(int phase,int route)throws Exception {
            monitor.phase("MEASURING",route,phase,System.nanoTime());monitor.operation("WAIT_PREVIOUS",System.nanoTime());
            continuing();GLES30.glFinish();JSONObject before=device();
            long start=System.nanoTime(),last=start,windowFrames=0,frames=0;
            while(System.nanoTime()-start<BenchmarkPlan.BLOCK_NS) {
                int completed=finishBatch(route,n,frameIndex[route],plan.pipeline);frameIndex[route]+=completed;
                frames+=completed;windowFrames+=completed;long now=System.nanoTime();
                if(now-last>=1_000_000_000L) {
                    double rate=windowFrames*(1e9/(now-last));window(phase,route,windowFrames,now-last,true);monitor.window(rate);
                    listener.progress(String.format(Locale.ROOT,"%s · ronda %d / 2 · bloque %d / %d\n%.1f FPS calculados · %d cuadros terminados\n%s · sin presentar cuadros",BenchmarkPlan.name(route),plan.round(phase)+1,phase+1,BenchmarkPlan.BLOCKS,rate,frames,plan.label()));
                    last=now;windowFrames=0;
                }
            }
            long end=System.nanoTime(),elapsed=end-start;
            if(end>last)window(phase,route,windowFrames,end-last,false);
            continuing();totals.add(route,frames,elapsed);
            blocks.put(new JSONObject().put("block",phase).put("round",plan.round(phase)).put("route",route).put("frames_completed",frames).put("elapsed_ns",elapsed)
                .put("fps",frames*(1e9/elapsed)).put("device_before",before).put("device_after",device()));
        }
        JSONObject routeReport(int route)throws Exception {
            JSONArray rounds=new JSONArray();
            for(int round=0;round<2;round++) {
                long frames=0,ns=0;
                for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);if(b.getInt("route")==route&&b.getInt("round")==round){frames+=b.getLong("frames_completed");ns+=b.getLong("elapsed_ns");}}
                rounds.put(new JSONObject().put("round",round).put("frames_completed",frames).put("measured_ns",ns).put("fps",ns==0?0:frames*(1e9/ns)));
            }
            return new JSONObject().put("route",route).put("name",BenchmarkPlan.name(route)).put("frames_completed",totals.frames(route)).put("measured_ns",totals.nanos(route))
                .put("sustained_fps",totals.fps(route)).put("best_block_fps",totals.bestBlockFps(route)).put("last_three_blocks_fps",totals.lastBlocksFps(route,3)).put("rounds",rounds);
        }
        void run()throws Exception {
            long wallStart=System.nanoTime();boolean success=false;
            try {
                report.put("format","GPU-VIRTUAL-C08-graphics-campaign-v1").put("run_id",runId).put("source_commit",BuildInfo.SOURCE_SHA).put("hardware_sustained_test_passed",false);
                report.put("device",Build.MODEL).put("fingerprint",Build.FINGERPRINT).put("android_api",Build.VERSION.SDK_INT);
                report.put("gl_renderer",renderer).put("gl_vendor",vendor).put("gl_version",version);
                report.put("hardware_driver_identified",hardware).put("software_renderer_detected",software).put("emulator_detected",emulated);
                report.put("n",n).put("width",plan.width).put("height",plan.height).put("scene_seed",SEED).put("pipeline_frames",plan.pipeline);
                JSONObject initial=device();report.put("device_start",initial);
                need(initial.has("power_save_mode")&&!initial.getBoolean("power_save_mode"),"Desactiva el ahorro de energia antes de medir este perfil");
                continuing();ensureAdvanced();continuing();report.put("build_variant","C08R");
                monitor.operation("PREPARE_TARGETS_AND_CACHE",System.nanoTime());
                ensureTargets(plan.width,plan.height);prepareGpuSlots(n,plan.pipeline);long cacheStart=System.nanoTime();prepareHashCache(n);
                report.put("cache_prepare_before_test_ns",System.nanoTime()-cacheStart).put("cache_setup_ns_since_context_creation",hashSetupNs);
                report.put("blocks",blocks).put("one_second_windows",windows).put("captures",captures);
                report.put("measurement","Confirmed frames / actual measured wall time, including CPU preparation, compute if used, API submission, telemetry and fence waits");
                report.put("presentation_inside_measurement",false).put("screen_fps_used_in_calculation",false).put("sleep_or_target_fps",false);
                report.put("completion","One GLsync after each frame, count only after signaled; selected pipeline depth is fixed for all four routes");
                report.put("schedule","Two mirrored rounds of four-route Williams rows; 32 >=4 s blocks, eight per route, >=32 s per route; each quartet contains every route");
                report.put("phase_sequence","tick=1024+8*(route_frame_index modulo 64), same cyclic states per route, independent of display cadence");
                report.put("warmup_seconds_per_route",2).put("pss_kib_start",Debug.getPss());
                report.put("authoritative_scene_packet_bytes",84).put("cache_validity","mixWord(id XOR seed), independent of tick and exceptions; invalidation on context or seed change (seed fixed in this profile)");
                report.put("cache_active_prefix_bytes",4L*n).put("cache_reserved_gpu_capacity_bytes",4L*MAX_OBJECTS);
                report.put("dense_gpu_buffers_reserved_bytes",32L*MAX_OBJECTS).put("dense_cpu_direct_buffer_capacity_bytes",16L*MAX_OBJECTS);
                report.put("gpu_materialization_ring_reserved_bytes",16L*n*plan.pipeline).put("gpu_materialization_ring_slots",plan.pipeline);
                report.put("gpu_materialization_ring_reuse","One distinct output buffer per frame in the batch; reuse only after all prior batch fences signal. No intra-batch read/write alias.");
                report.put("all_comparison_buffers_retained_for_every_route",true);
                report.put("scene_api_byte_basis","Per-frame scene arrays only; excludes mode, camera, viewport, constant attributes, API overhead and initial cache uploads");
                JSONArray definitions=new JSONArray();
                String[] descriptions={"CPU materializes 4 uint32 per light and uploads each frame","GPU rules directly in the vertex shader, no per-light state buffer","Same rules with a derived, persistent 1 uint32 hash cache per identity; no dense state per frame","Compute GPU generates full state into distinct ring slots, vertex barrier then instanced draw; no CPU expansion, per-light upload or intra-batch state-buffer reuse"};
                for(int route=0;route<4;route++)definitions.put(new JSONObject().put("route",route).put("name",BenchmarkPlan.name(route)).put("description",descriptions[route])
                    .put("scene_api_bytes_per_frame",route==0?16L*n+16:route==3?96:80).put("logical_state_bytes_written_by_compute_per_frame",route==3?16L*n:0));
                report.put("route_definitions",definitions).put("byte_metrics_are_physical_traffic_or_total_vram",false);
                report.put("progress_instrumentation","500 ms independent UI snapshots; submitted and signaled-fence counters; UI clock alone is not GPU progress, instrumentation included");
                report.put("scope","Own specialized scene at selected resolution and load. Tests the compact route against both CPU and GPU materialization. No arbitrary shader runtime, world-record claim, energy measurement or automatic game integration; full state materialization remains explicit.");
                put("vertex.glsl",vertexSource.getBytes(StandardCharsets.UTF_8));put("fragment.glsl",fragmentSource.getBytes(StandardCharsets.UTF_8));put("compute.glsl",computeSource.getBytes(StandardCharsets.UTF_8));
                report.put("vertex_sha256",sha(vertexSource.getBytes(StandardCharsets.UTF_8))).put("fragment_sha256",sha(fragmentSource.getBytes(StandardCharsets.UTF_8))).put("compute_sha256",sha(computeSource.getBytes(StandardCharsets.UTF_8)));
                byte[] hashBytes=readBuffer(hashVbo,n*4);IntBuffer hashes=ByteBuffer.wrap(hashBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                for(int id=0;id<n;id++)need(hashes.get()==SceneState.mix(id^SEED),"Cache GPU distinta a la referencia");
                put("hash-cache.u32",hashBytes);report.put("hash_cache_words_checked",n).put("hash_cache_sha256",sha(hashBytes));
                validate("before",1024);for(int route=0;route<4;route++)warm(route);
                for(int phase=0;phase<BenchmarkPlan.BLOCKS;phase++)measure(phase,plan.route(phase));
                validateTimedRing();
                validate("after",1376);
                need(validatedPairs==6&&verifiedWords==n*24L&&ringVerifiedWords==n*4L*plan.pipeline&&blocks.length()==BenchmarkPlan.BLOCKS,"Cobertura de campana incompleta");
                for(int route=0;route<4;route++)need(totals.nanos(route)>=32_000_000_000L,"Tiempo de metodo incompleto");
                continuing();success=true;report.put("status",hardware?"PASSED_ON_IDENTIFIED_HARDWARE_DRIVER":"SOFTWARE_OR_UNIDENTIFIED_DRIVER");
                report.put("hardware_sustained_test_passed",hardware);
            }catch(Exception error) {
                report.put("status",error instanceof CancellationException?"CANCELLED":"NOT_VERIFIED").put("error",error.toString());
            }finally {
                monitor.phase("SAVING",-1,-1,System.nanoTime());
                report.put("verified_image_pairs",validatedPairs).put("gpu_state_words_checked",verifiedWords).put("timed_ring_state_words_checked",ringVerifiedWords).put("pss_kib_end",Debug.getPss()).put("device_end",device());
                report.put("power_save_off_in_all_recorded_snapshots",powerFree).put("performance_comparison_eligible",success&&powerFree&&hardware);
                report.put("total_wall_ns_including_validation_and_warmup",System.nanoTime()-wallStart);
                JSONArray routes=new JSONArray();for(int route=0;route<4;route++)routes.put(routeReport(route));report.put("routes",routes);
                int bestCompact=totals.fps(2)>totals.fps(1)?2:1,fastest=0;
                for(int route=1;route<4;route++)if(totals.fps(route)>totals.fps(fastest))fastest=route;
                report.put("fastest_route_in_this_run",fastest).put("best_compact_route_in_this_run",bestCompact);
                report.put("best_compact_gain_vs_cpu_percent",totals.fps(0)==0?JSONObject.NULL:(totals.fps(bestCompact)/totals.fps(0)-1)*100);
                report.put("best_compact_gain_vs_gpu_materialization_percent",totals.fps(3)==0?JSONObject.NULL:(totals.fps(bestCompact)/totals.fps(3)-1)*100);
                report.put("winner_selection_scope","Within this one campaign; not an independent validation of a universal best path");
                report.put("monitor_before_save",monitorJson(probe()));JSONArray events=new JSONArray();
                report.put("startup",startupJson());
                for(ProbeMonitor.Event e:monitor.events())events.put(new JSONObject().put("stage",e.stage).put("route",e.route).put("block_zero_based",e.block).put("elapsed_ns",e.elapsedNs));report.put("monitor_events",events);
                put("report.json",report.toString(2).getBytes(StandardCharsets.UTF_8));zip.close();
                GLES30.glFlush();GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
                monitor.finish(success?"DONE":report.optString("status","ERROR"),report.optString("error",""),System.nanoTime());
                StringBuilder summary=new StringBuilder(success&&hardware?"CAMPANA COMPLETA":"RESULTADO PARCIAL O SIN VERIFICAR");
                for(int route=0;route<4;route++)summary.append(String.format(Locale.ROOT,"\n%s: %.2f FPS",BenchmarkPlan.name(route),totals.fps(route)));
                summary.append("\n").append(n).append(" luces · ").append(plan.label()).append("\n32 bloques · 2 rondas · fuera de pantalla");
                if(success&&powerFree){mode=bestCompact;summary.append("\nMejor compacta aplicada al visor: ").append(BenchmarkPlan.name(bestCompact));}
                if(!powerFree)summary.append("\nAhorro activo o sin dato en algun control: comparar condiciones.");
                if(!success)summary.append("\n").append(report.optString("error","Incompleto"));
                listener.result(archive,report,summary.toString());
            }
        }
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
            ensureAdvanced();
            ensureTargets(SIDE,SIDE);
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
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,framebuffers[mode&1]);GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,0);
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
            StringBuilder text=new StringBuilder("PRUEBA CORTA · LATENCIA EN MS\n");
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

