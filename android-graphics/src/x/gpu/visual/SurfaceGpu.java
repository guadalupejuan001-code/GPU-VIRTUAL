package x.gpu.visual;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLES31;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** GPU surface observer. CPU never computes surface pixels or depth. */
public final class SurfaceGpu {
    static final int SSBO=0x90d2,STORAGE_BARRIER=0x2000,BUFFER_BARRIER=0x200;
    public interface Stages {void stage(String message);}
    private final AssetManager assets;
    private final Stages stages;
    private final Map<Integer,Map<String,Integer>> locations=new HashMap<>();
    private final int[] buffers=new int[4];
    private int mapProgram,captureProgram,presentProgram,queryProgram,vao,texture,target;
    public int width,height,tiles,maskWords,pages,route=-1;
    public long pixelBytes,metadataBytes,sceneBytes,layoutNs;
    public SurfaceScene scene;
    public final String renderer,vendor,version;
    public final Map<String,String> shaderSources=new LinkedHashMap<>();
    public SurfaceGpu(AssetManager assets,Stages stages)throws Exception{
        this.assets=assets;this.stages=stages;
        renderer=GLES30.glGetString(GLES30.GL_RENDERER);vendor=GLES30.glGetString(GLES30.GL_VENDOR);version=GLES30.glGetString(GLES30.GL_VERSION);
        int[] max=new int[1];GLES30.glGetIntegerv(0x90db,max,0);need(max[0]>=4,"El driver no ofrece cuatro bloques SSBO compute");
        String common="#version 310 es\n"+asset("surface-common.glsl")+"\n";
        String ro="layout(std430,binding=1) readonly buffer TileMap { uint meta[]; };\n";
        String trace=asset("surface-trace.glsl")+"\n",storage=asset("surface-storage.glsl")+"\n";
        shaderSources.put("surface-map.comp",common+asset("surface-map.glsl"));
        shaderSources.put("surface-capture.comp",common+ro+trace+asset("surface-capture.glsl"));
        shaderSources.put("surface-query.comp",common+ro+trace+storage+asset("surface-query.glsl"));
        shaderSources.put("surface.vert",asset("surface-vertex.glsl"));
        shaderSources.put("surface.frag",common+ro+storage+asset("surface-present.glsl"));
        stages.stage("Compilando mapa de superficies GPU");mapProgram=program(shader(GLES31.GL_COMPUTE_SHADER,shaderSources.get("surface-map.comp")));
        stages.stage("Compilando captura compacta GPU");captureProgram=program(shader(GLES31.GL_COMPUTE_SHADER,shaderSources.get("surface-capture.comp")));
        stages.stage("Compilando presentacion directa GPU");presentProgram=program(shader(GLES30.GL_VERTEX_SHADER,shaderSources.get("surface.vert")),shader(GLES30.GL_FRAGMENT_SHADER,shaderSources.get("surface.frag")));
        stages.stage("Compilando consultas de profundidad GPU");queryProgram=program(shader(GLES31.GL_COMPUTE_SHADER,shaderSources.get("surface-query.comp")));
        GLES30.glGenBuffers(4,buffers,0);for(int b:buffers){GLES30.glBindBuffer(SSBO,b);GLES30.glBufferData(SSBO,4,null,GLES30.GL_DYNAMIC_DRAW);}
        int[] id=new int[1];GLES30.glGenVertexArrays(1,id,0);vao=id[0];GLES30.glGenTextures(1,id,0);texture=id[0];GLES30.glGenFramebuffers(1,id,0);target=id[0];check();
    }
    private String asset(String name)throws Exception{
        try(InputStream in=assets.open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] data=new byte[8192];for(int n;(n=in.read(data))!=-1;)out.write(data,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static void need(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    static void check(){int error=GLES30.glGetError();need(error==0,"Error GL 0x"+Integer.toHexString(error));}
    private static int shader(int type,String text){
        int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,text);GLES30.glCompileShader(s);int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String error=GLES30.glGetShaderInfoLog(s);GLES30.glDeleteShader(s);throw new IllegalStateException(error);}return s;
    }
    private static int program(int... shaders){
        int p=GLES30.glCreateProgram();for(int s:shaders)GLES30.glAttachShader(p,s);GLES30.glLinkProgram(p);int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
        for(int s:shaders)GLES30.glDeleteShader(s);if(ok[0]==0){String error=GLES30.glGetProgramInfoLog(p);GLES30.glDeleteProgram(p);throw new IllegalStateException(error);}return p;
    }
    private int at(int p,String name){Map<String,Integer> values=locations.get(p);if(values==null){values=new HashMap<>();locations.put(p,values);}Integer n=values.get(name);if(n==null){n=GLES30.glGetUniformLocation(p,name);values.put(name,n);}return n;}
    private void bind(){for(int i=0;i<buffers.length;i++)GLES31.glBindBufferBase(SSBO,i,buffers[i]);}
    private void uniforms(int p,int tick,boolean depth){
        GLES30.glUseProgram(p);bind();
        GLES30.glUniform4ui(at(p,"frameInfo"),width,height,scene.count,maskWords);
        GLES30.glUniform4fv(at(p,"eyeFov"),1,scene.eye,0);
        GLES30.glUniform3fv(at(p,"cameraRight"),1,scene.right,0);GLES30.glUniform3fv(at(p,"cameraUp"),1,scene.up,0);GLES30.glUniform3fv(at(p,"cameraForward"),1,scene.forward,0);
        float x=.37f+.3f*(float)Math.sin(tick*.017),y=.74f,z=.56f;float length=(float)Math.sqrt(x*x+y*y+z*z);
        GLES30.glUniform3f(at(p,"lightDirection"),x/length,y/length,z/length);
        GLES30.glUniform1i(at(p,"surfaceRoute"),route);GLES30.glUniform1i(at(p,"depthView"),depth?1:0);
    }
    public void layout(SurfaceScene next,int w,int h)throws Exception{
        stages.stage("GPU: proyectando objetos y asignando paginas");long start=System.nanoTime();GLES30.glFinish();
        scene=next;width=w;height=h;tiles=((w+7)/8)*((h+7)/8);maskWords=Math.max(1,(next.count+31)/32);
        sceneBytes=Math.max(4,next.count*SurfaceScene.BODY_BYTES);metadataBytes=4L*(1+tiles+tiles*maskWords);
        need(metadataBytes<=Integer.MAX_VALUE,"Mapa demasiado grande");
        GLES30.glBindBuffer(SSBO,buffers[0]);GLES30.glBufferData(SSBO,(int)sceneBytes,next.bodies.duplicate(),GLES30.GL_DYNAMIC_DRAW);
        GLES30.glBindBuffer(SSBO,buffers[1]);GLES30.glBufferData(SSBO,(int)metadataBytes,null,GLES30.GL_DYNAMIC_DRAW);
        ByteBuffer zero=ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);zero.putInt(0).flip();GLES30.glBufferSubData(SSBO,0,4,zero);
        uniforms(mapProgram,0,false);GLES31.glDispatchCompute((tiles+63)/64,1,1);GLES31.glMemoryBarrier(STORAGE_BARRIER|BUFFER_BARRIER);complete(()->false);
        pages=ByteBuffer.wrap(read(1,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();need(pages>=0&&pages<=tiles,"Contador de paginas GPU invalido");
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0);
        need(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE,"Salida GPU incompleta");
        int selected=route<0?1:route;route=-1;reserve(selected);layoutNs=System.nanoTime()-start;check();
    }
    public void reserve(int selected){
        need(selected==0||selected==1,"Ruta invalida");if(route==selected)return;
        // The comparison owns ONE pixel pool; release/reallocate on route switch.
        GLES30.glFinish();route=selected;pixelBytes=Math.max(4L,(selected==0?tiles:pages)*384L);
        need(pixelBytes<=Integer.MAX_VALUE,"Buffer demasiado grande");GLES30.glBindBuffer(SSBO,buffers[2]);GLES30.glBufferData(SSBO,(int)pixelBytes,null,GLES30.GL_DYNAMIC_DRAW);check();
    }
    public void render(int tick,boolean depth){
        // Finish earlier fragment SSBO reads before overwriting the same pool.
        GLES31.glMemoryBarrier(STORAGE_BARRIER);uniforms(captureProgram,tick,depth);
        GLES31.glDispatchCompute((width+7)/8,(height+7)/8,1);GLES31.glMemoryBarrier(STORAGE_BARRIER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,width,height);
        for(int cap:new int[]{GLES30.GL_BLEND,GLES30.GL_DEPTH_TEST,GLES30.GL_CULL_FACE,GLES30.GL_DITHER,GLES30.GL_SCISSOR_TEST})GLES30.glDisable(cap);
        uniforms(presentProgram,tick,depth);GLES30.glBindVertexArray(vao);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
    }
    public void present(int w,int h){
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,target);GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,0);
        GLES30.glClearColor(.015f,.025f,.045f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        float scale=Math.min(w/(float)width,h/(float)height);int dw=Math.max(1,(int)(width*scale)),dh=Math.max(1,(int)(height*scale)),x=(w-dw)/2,y=(h-dh)/2;
        GLES30.glBlitFramebuffer(0,0,width,height,x,y,x+dw,y+dh,GLES30.GL_COLOR_BUFFER_BIT,GLES30.GL_LINEAR);
    }
    public long complete(BooleanSupplier stop){
        long sync=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0);need(sync!=0,"No se pudo crear GLsync");GLES30.glFlush();long start=System.nanoTime();
        try{for(;;){int result=GLES30.glClientWaitSync(sync,0,1_000_000L);if(result==GLES30.GL_ALREADY_SIGNALED||result==GLES30.GL_CONDITION_SATISFIED)return System.nanoTime();
            need(result==GLES30.GL_TIMEOUT_EXPIRED,"Fallo GLsync");need(System.nanoTime()-start<10_000_000_000L,"El driver no confirmo el cuadro en 10 segundos");
            if(stop.getAsBoolean())throw new java.util.concurrent.CancellationException("Detencion solicitada");}}
        finally{GLES30.glDeleteSync(sync);}
    }
    public byte[] read(int slot,int length){
        GLES31.glMemoryBarrier(BUFFER_BARRIER);GLES30.glBindBuffer(SSBO,buffers[slot]);
        Buffer mapped=GLES30.glMapBufferRange(SSBO,0,length,GLES30.GL_MAP_READ_BIT);need(mapped instanceof ByteBuffer,"No se pudo leer el buffer GPU");
        byte[] out=new byte[length];try{((ByteBuffer)mapped).get(out);}finally{need(GLES30.glUnmapBuffer(SSBO),"Lectura de buffer invalidada");}check();return out;
    }
    public byte[] image(){ByteBuffer bytes=ByteBuffer.allocateDirect(width*height*4);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,bytes);byte[] out=new byte[bytes.capacity()];bytes.position(0);bytes.get(out);check();return out;}
    public byte[] queries(int tick)throws Exception{
        GLES30.glBindBuffer(SSBO,buffers[3]);GLES30.glBufferData(SSBO,256*32,null,GLES30.GL_DYNAMIC_DRAW);
        uniforms(queryProgram,tick,false);GLES30.glUniform1ui(at(queryProgram,"queryCount"),256);GLES31.glDispatchCompute(4,1,1);GLES31.glMemoryBarrier(BUFFER_BARRIER|STORAGE_BARRIER);complete(()->false);
        byte[] out=read(3,256*32);GLES30.glBindBuffer(SSBO,buffers[3]);GLES30.glBufferData(SSBO,4,null,GLES30.GL_DYNAMIC_DRAW);return out;
    }
    public long reservedSsboBytes(){return sceneBytes+metadataBytes+pixelBytes+4;}
    public byte[] sceneBytes(){ByteBuffer bytes=scene.bodies.duplicate();byte[] out=new byte[bytes.remaining()];bytes.get(out);return out;}
    public void release(){GLES30.glFinish();GLES30.glDeleteBuffers(4,buffers,0);GLES30.glDeleteFramebuffers(1,new int[]{target},0);GLES30.glDeleteTextures(1,new int[]{texture},0);GLES30.glDeleteVertexArrays(1,new int[]{vao},0);for(int p:new int[]{mapProgram,captureProgram,presentProgram,queryProgram})if(p!=0)GLES30.glDeleteProgram(p);}
}
