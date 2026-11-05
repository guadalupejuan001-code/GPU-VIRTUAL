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
    private final int[] buffers=new int[5];
    private final Map<String,Integer> mapPrograms=new LinkedHashMap<>();
    private boolean cacheValid;private int blockLimit;
    private int captureProgram,presentProgram,queryProgram,vao,texture,target;
    public int width,height,tiles,maskWords,pages,references,route=-1;
    public long pixelBytes,metadataBytes,sceneBytes,builderBytes,metadataLogicalBytes,pixelLogicalBytes,layoutNs;
    public long layoutCount,layoutTotalNs,cacheHits,visibilityCaptures,sceneUploads;
    public SurfaceScene scene;
    public final String renderer,vendor,version;
    public final Map<String,String> shaderSources=new LinkedHashMap<>();
    public SurfaceGpu(AssetManager assets,Stages stages)throws Exception{
        this.assets=assets;this.stages=stages;
        renderer=GLES30.glGetString(GLES30.GL_RENDERER);vendor=GLES30.glGetString(GLES30.GL_VENDOR);version=GLES30.glGetString(GLES30.GL_VERSION);
        int[] max=new int[1];GLES30.glGetIntegerv(0x90db,max,0);need(max[0]>=4,"El driver no ofrece cuatro bloques SSBO compute");
        GLES30.glGetIntegerv(0x90de,max,0);blockLimit=max[0];
        String common="#version 310 es\n"+asset("surface2-common.glsl")+"\n"+asset("surface2-layout.glsl")+"\n";
        String ro="layout(std430,binding=1) readonly buffer TileMap { uint meta[]; };\n";
        String trace=asset("surface2-trace.glsl")+"\n",storage=asset("surface2-storage.glsl")+"\n";
        for(String name:new String[]{"clear","count","scan","groups","finalize","fill","restore"}){
            String key="surface2-"+name+".comp",source=common+asset("surface2-"+name+".glsl");shaderSources.put(key,source);
            stages.stage("Compilando indices GPU: "+name);mapPrograms.put(name,program(shader(GLES31.GL_COMPUTE_SHADER,source)));
        }
        shaderSources.put("surface2-capture.comp",common+ro+trace+asset("surface2-capture.glsl"));
        shaderSources.put("surface2-query.comp",common+ro+trace+storage+asset("surface-query.glsl"));
        shaderSources.put("surface2.vert",asset("surface-vertex.glsl"));shaderSources.put("surface2.frag",common+ro+storage+asset("surface-present.glsl"));
        stages.stage("Compilando visibilidad persistente GPU");captureProgram=program(shader(GLES31.GL_COMPUTE_SHADER,shaderSources.get("surface2-capture.comp")));
        presentProgram=program(shader(GLES30.GL_VERTEX_SHADER,shaderSources.get("surface2.vert")),shader(GLES30.GL_FRAGMENT_SHADER,shaderSources.get("surface2.frag")));
        queryProgram=program(shader(GLES31.GL_COMPUTE_SHADER,shaderSources.get("surface2-query.comp")));
        GLES30.glGenBuffers(5,buffers,0);for(int buffer:buffers){GLES30.glBindBuffer(SSBO,buffer);GLES30.glBufferData(SSBO,4,null,GLES30.GL_DYNAMIC_DRAW);}
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
    private void bind(){for(int i=0;i<4;i++)GLES31.glBindBufferBase(SSBO,i,buffers[i]);}
    private void uniforms(int p,int tick,boolean depth){
        GLES30.glUseProgram(p);bind();
        GLES30.glUniform4ui(at(p,"frameInfo"),width,height,scene.count,maskWords);
        GLES30.glUniform4fv(at(p,"eyeFov"),1,scene.eye,0);
        GLES30.glUniform3fv(at(p,"cameraRight"),1,scene.right,0);GLES30.glUniform3fv(at(p,"cameraUp"),1,scene.up,0);GLES30.glUniform3fv(at(p,"cameraForward"),1,scene.forward,0);
        float x=.37f+.3f*(float)Math.sin(tick*.017),y=.74f,z=.56f;float length=(float)Math.sqrt(x*x+y*y+z*z);
        GLES30.glUniform3f(at(p,"lightDirection"),x/length,y/length,z/length);
        GLES30.glUniform1i(at(p,"surfaceRoute"),route);GLES30.glUniform1i(at(p,"depthView"),depth?1:0);
    }
    private void mapStep(String name,int count){
        int p=mapPrograms.get(name);uniforms(p,0,false);GLES31.glBindBufferBase(SSBO,1,buffers[4]);GLES31.glBindBufferBase(SSBO,2,buffers[1]);
        if(count>0)GLES31.glDispatchCompute(count,1,1);GLES31.glMemoryBarrier(STORAGE_BARRIER);
    }
    private void allocate(int slot,long bytes){need(bytes>0&&bytes<=Integer.MAX_VALUE&&bytes<=blockLimit,"Reserva SSBO supera el limite del driver: "+bytes);GLES30.glBindBuffer(SSBO,buffers[slot]);GLES30.glBufferData(SSBO,(int)bytes,null,GLES30.GL_DYNAMIC_DRAW);}
    public void layout(SurfaceScene next,int w,int h)throws Exception{
        if(layoutCount==0)stages.stage("GPU: construyendo listas compactas de candidatos");long start=System.nanoTime();GLES30.glFinish();
        boolean geometry=scene!=next,size=width!=w||height!=h,profile=geometry||size;
        scene=next;width=w;height=h;tiles=((w+7)/8)*((h+7)/8);maskWords=Math.max(1,(next.count+31)/32);
        if(geometry){sceneBytes=Math.max(4,next.count*SurfaceScene.BODY_BYTES);need(sceneBytes<=blockLimit,"Escena demasiado grande");GLES30.glBindBuffer(SSBO,buffers[0]);GLES30.glBufferData(SSBO,(int)sceneBytes,next.bodies.duplicate(),GLES30.GL_DYNAMIC_DRAW);sceneUploads++;}
        int groups=(tiles+127)/128,builderWords=4+4*tiles+4*next.count+4*groups;long needed=4L*builderWords;
        if(profile||builderBytes<needed){builderBytes=needed;allocate(4,builderBytes);}
        mapStep("clear",(builderWords+63)/64);mapStep("count",(next.count+63)/64);mapStep("scan",groups);mapStep("groups",1);
        GLES31.glMemoryBarrier(BUFFER_BARRIER);complete(()->false);
        IntBuffer header=ByteBuffer.wrap(read(4,16)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();pages=header.get(0);references=header.get(1);
        need(pages>=0&&pages<=tiles&&references>=0&&references==header.get(3)&&header.get(2)==4+4*tiles,"Prefijos GPU invalidos");
        metadataLogicalBytes=4L*(header.get(2)+(long)references);
        if(profile||metadataBytes<metadataLogicalBytes){metadataBytes=profile?metadataLogicalBytes:Math.max(metadataLogicalBytes,metadataBytes+metadataBytes/8);metadataBytes=Math.min(metadataBytes,blockLimit);need(metadataBytes>=metadataLogicalBytes,"Lista de candidatos supera el SSBO del driver");allocate(1,metadataBytes);}
        mapStep("finalize",(tiles+63)/64);mapStep("fill",(next.count+63)/64);mapStep("restore",(tiles+63)/64);complete(()->false);
        if(size){
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0);
            need(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE,"Salida GPU incompleta");
        }
        int selected=route<0?2:route;if(profile)route=-1;cacheValid=false;reserve(selected);layoutNs=System.nanoTime()-start;layoutTotalNs+=layoutNs;layoutCount++;check();
    }
    public void reserve(int selected){
        need(selected>=0&&selected<3,"Ruta invalida");long needed=Math.max(4L,(selected==0?tiles:pages)*384L);pixelLogicalBytes=needed;
        if(route!=selected||pixelBytes<needed){
            GLES30.glFinish();boolean changed=route!=selected;route=selected;long growth=changed?needed:Math.max(needed,((pixelBytes+pixelBytes/8+383)/384)*384);
            pixelBytes=Math.min(tiles*384L,Math.max(4,growth));allocate(2,pixelBytes);cacheValid=false;
        }
        route=selected;
    }
    public void render(int tick,boolean depth){
        GLES31.glMemoryBarrier(STORAGE_BARRIER);uniforms(captureProgram,tick,depth);boolean reuse=route==2&&cacheValid;
        GLES30.glUniform1i(at(captureProgram,"reuseVisibility"),reuse?1:0);
        if(route==0)GLES31.glDispatchCompute((width+7)/8,(height+7)/8,1);
        else if(pages>0){int gx=Math.min(pages,16384);GLES31.glDispatchCompute(gx,(pages+gx-1)/gx,1);}
        if(reuse)cacheHits++;else visibilityCaptures++;if(route==2)cacheValid=true;
        GLES31.glMemoryBarrier(STORAGE_BARRIER);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target);GLES30.glViewport(0,0,width,height);
        GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
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
    public long reservedSsboBytes(){return sceneBytes+metadataBytes+builderBytes+pixelBytes+4;}
    public byte[] sceneBytes(){ByteBuffer bytes=scene.bodies.duplicate();byte[] out=new byte[bytes.remaining()];bytes.get(out);return out;}
    public void release(){GLES30.glFinish();GLES30.glDeleteBuffers(5,buffers,0);GLES30.glDeleteFramebuffers(1,new int[]{target},0);GLES30.glDeleteTextures(1,new int[]{texture},0);GLES30.glDeleteVertexArrays(1,new int[]{vao},0);for(int p:new int[]{captureProgram,presentProgram,queryProgram})if(p!=0)GLES30.glDeleteProgram(p);for(int p:mapPrograms.values())GLES30.glDeleteProgram(p);}
}
