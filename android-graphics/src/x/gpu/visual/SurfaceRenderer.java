package x.gpu.visual;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Debug;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.zip.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class SurfaceRenderer implements GLSurfaceView.Renderer {
    public interface Listener {void status(String text);void result(File file,String summary);void failure(String text);}
    private static final class Config {
        final int n,preset,width,height;final float yaw,pitch;
        Config(int n,int preset,int w,int h,float yaw,float pitch){this.n=n;this.preset=preset;this.width=w;this.height=h;this.yaw=yaw;this.pitch=pitch;}
    }
    private final Context context;private final Listener listener;private final Supplier<JSONObject> device;
    private volatile Config wanted=new Config(64,0,1280,720,.17f,-.09f);
    private Config applied;
    private SurfaceGpu gpu;
    private volatile int selectedRoute=1;
    private volatile boolean depth,wantsTest,stop,ready,failed,busy;
    private volatile String stage="Esperando contexto GPU",identity="Sin identificar",error="",memory="";
    private volatile long confirmed,measured,started,firstFrameNs;
    private int screenWidth=1,screenHeight=1,tick;private long liveAt;private int liveFrames;
    public SurfaceRenderer(Context context,Listener listener,Supplier<JSONObject> device){this.context=context.getApplicationContext();this.listener=listener;this.device=device;}
    public void configure(int n,int preset,boolean hd){Config c=wanted;wanted=new Config(n,preset,hd?1280:640,hd?720:360,c.yaw,c.pitch);}
    public void rotate(float dx,float dy){Config c=wanted;wanted=new Config(c.n,c.preset,c.width,c.height,c.yaw+dx,Math.max(-1.25f,Math.min(1.25f,c.pitch+dy)));}
    public void route(int route){selectedRoute=route;}public int route(){return selectedRoute;}
    public void depth(boolean value){depth=value;}
    public boolean ready(){return ready;}public boolean busy(){return busy;}public boolean failed(){return failed;}
    public void requestTest(){if(!busy&&ready){stop=false;wantsTest=true;}}
    public void stop(){stop=true;wantsTest=false;}
    private void stage(String message){stage=message;listener.status(message);}
    public String progress(){return stage+(busy?String.format(Locale.ROOT,"\n%.1f s · %,d cuadros GPU confirmados\n%,d cuadros medidos",(System.nanoTime()-started)/1e9,confirmed,measured):"");}
    public JSONObject diagnostic(){
        JSONObject out=new JSONObject();try{Config c=wanted;out.put("format","GPU-VIRTUAL-C09-diagnostic-v1").put("source_commit",BuildInfo.SOURCE_SHA)
            .put("stage",stage).put("error",error).put("ready",ready).put("failed",failed).put("busy",busy).put("stop_requested",stop)
            .put("confirmed_frames",confirmed).put("measured_frames",measured).put("first_frame_ns",firstFrameNs)
            .put("renderer",identity).put("selected_route",selectedRoute).put("objects",c.n).put("preset",c.preset).put("width",c.width).put("height",c.height)
            .put("memory",memory).put("device",device.get()).put("scope","CPU snapshot; counters increase only after a signaled GLsync during the test. No CPU pixel renderer.");
        }catch(Exception ignored){}return out;
    }
    @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config){
        ready=false;failed=false;applied=null;firstFrameNs=0;long begin=System.nanoTime();
        try{stage("Identificando GPU");gpu=new SurfaceGpu(context.getAssets(),this::stage);identity=gpu.renderer;
            apply();gpu.render(41,depth);gpu.complete(()->false);confirmed++;firstFrameNs=System.nanoTime()-begin;ready=true;stage("Escena GPU lista · arrastra para girar la camara");
        }catch(Throwable e){fail(e);}
    }
    private void apply()throws Exception{
        Config c=wanted;if(c!=applied){gpu.layout(new SurfaceScene(c.n,c.preset,c.yaw,c.pitch),c.width,c.height);applied=c;}
        gpu.reserve(selectedRoute);memory=String.format(Locale.ROOT,"Paginas: %,d / %,d · pixel pool: %,d B\nEscena + indices: %,d B · salida RGBA: %,d B",gpu.pages,gpu.tiles,gpu.pixelBytes,gpu.sceneBytes+gpu.metadataBytes,gpu.width*(long)gpu.height*4);
    }
    private void fail(Throwable e){failed=true;ready=false;busy=false;error=e.toString();stage="Fallo GPU: "+error;listener.failure(stage);}
    @Override public void onSurfaceChanged(GL10 ignored,int w,int h){screenWidth=Math.max(1,w);screenHeight=Math.max(1,h);}
    @Override public void onDrawFrame(GL10 ignored){
        if(failed||gpu==null)return;
        try{
            apply();if(wantsTest){wantsTest=false;campaign();apply();}
            gpu.render(41+(tick++%64),depth);gpu.present(screenWidth,screenHeight);SurfaceGpu.check();
            long now=System.nanoTime();if(liveAt==0)liveAt=now;liveFrames++;
            if(now-liveAt>=1_000_000_000L){listener.status(String.format(Locale.ROOT,"%s · %d objetos · %d x %d\n%s · %.1f FPS visor\n%s",gpu.renderer,gpu.scene.count,gpu.width,gpu.height,selectedRoute==1?"Superficies GPU compactas":"Superficies GPU densas",liveFrames*1e9/(now-liveAt),memory));liveAt=now;liveFrames=0;}
        }catch(Throwable e){if(e instanceof CancellationException){stage("Detenido");busy=false;}else fail(e);}
    }
    private void continuing(){if(stop)throw new CancellationException("Detencion solicitada");}
    private long frame(int tick,boolean depth){continuing();gpu.render(tick,depth);long done=gpu.complete(()->stop);confirmed++;return done;}
    private static String hash(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static byte[] text(JSONObject json){return json.toString().getBytes(StandardCharsets.UTF_8);}
    private static void put(ZipOutputStream zip,String name,byte[] data)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    private static int word(byte[] data,int index){int p=index*4;SurfaceGpu.need(p>=0&&p+4<=data.length,"Indice de captura fuera del buffer");return (data[p]&255)|((data[p+1]&255)<<8)|((data[p+2]&255)<<16)|(data[p+3]<<24);}
    private long value(byte[] pool,byte[] map,int route,int x,int y){
        int tilesX=(gpu.width+7)/8,tile=(y/8)*tilesX+x/8,page=word(map,1+tile),lane=(y%8)*8+x%8;
        if(route==1&&page==0)return (65535L<<32)|(SurfaceScene.background(x,y,gpu.width,gpu.height)&0xffffffffL);
        int base=(route==0?tile:page-1)*96,id=(word(pool,base+64+lane/2)>>>((lane&1)*16))&65535;
        SurfaceGpu.need(id==65535||id<gpu.scene.count,"Identificador de superficie invalido");
        return ((long)id<<32)|((id==65535?SurfaceScene.background(x,y,gpu.width,gpu.height):word(pool,base+lane))&0xffffffffL);
    }
    private JSONObject validate(ZipOutputStream zip,String prefix,int tick)throws Exception{
        stage("Verificando "+prefix+": color, ID y profundidad en GPU");
        byte[][] images=new byte[2][],depthImages=new byte[2][],states=new byte[2][],queries=new byte[2][];
        byte[] map=gpu.read(1,(int)gpu.metadataBytes);put(zip,prefix+"-map.u32",map);
        JSONObject sizes=new JSONObject();
        for(int r=0;r<2;r++){
            continuing();gpu.reserve(r);frame(tick,false);images[r]=gpu.image();states[r]=gpu.read(2,(int)gpu.pixelBytes);queries[r]=gpu.queries(tick);
            put(zip,prefix+"-route"+r+".rgba",images[r]);put(zip,prefix+"-route"+r+"-surfaces.u32",states[r]);put(zip,prefix+"-route"+r+"-queries.u32",queries[r]);
            sizes.put("route"+r,new JSONObject().put("surface_pool_reserved_bytes",gpu.pixelBytes).put("total_requested_ssbo_bytes",gpu.reservedSsboBytes()).put("pss_kib_after_readback",Debug.getPss()));
            frame(tick,true);depthImages[r]=gpu.image();put(zip,prefix+"-route"+r+"-depth.rgba",depthImages[r]);
        }
        SurfaceGpu.need(Arrays.equals(images[0],images[1]),"Las imagenes densas y compactas difieren");
        SurfaceGpu.need(Arrays.equals(depthImages[0],depthImages[1]),"La profundidad presentada difiere");
        SurfaceGpu.need(Arrays.equals(queries[0],queries[1]),"Las consultas de superficie difieren");
        for(int i=0;i<256;i++){
            int at=i*8;SurfaceGpu.need(word(queries[0],at+2)==word(queries[0],at+5),"La consulta no recupero el ID cercano");
            SurfaceGpu.need(word(queries[0],at+3)==word(queries[0],at+6),"La consulta no recupero el RGBA");
            float a=Float.intBitsToFloat(word(queries[0],at+4)),b=Float.intBitsToFloat(word(queries[0],at+7));
            SurfaceGpu.need((Float.isInfinite(a)&&Float.isInfinite(b))||Math.abs(a-b)<=Math.max(1e-4f,Math.abs(b)*2e-5f),"La profundidad reconstruida difiere");
        }
        long visible=0;for(int y=0;y<gpu.height;y++)for(int x=0;x<gpu.width;x++){
            long a=value(states[0],map,0,x,y),b=value(states[1],map,1,x,y);if(a!=b)throw new IllegalStateException("Estado de superficie distinto en "+x+","+y);if((a>>>32)!=65535)visible++;
        }
        return new JSONObject().put("tick",tick).put("image_pairs",2).put("images_byte_exact",true).put("ids_and_rgba_exact",true)
            .put("pixels_checked",(long)gpu.width*gpu.height).put("visible_pixels",visible).put("query_count",256).put("depth_relative_tolerance",2e-5)
            .put("color_sha256",hash(images[0])).put("depth_image_sha256",hash(depthImages[0])).put("reservations",sizes);
    }
    private static boolean off(JSONObject d){return d.has("power_save_mode")&&!d.optBoolean("power_save_mode",true);}
    private void campaign()throws Exception{
        busy=true;started=System.nanoTime();confirmed=0;measured=0;stop=false;
        Config c=applied;File file=new File(context.getCacheDir(),"GPU-VIRTUAL-C09-"+gpu.scene.count+"-"+gpu.width+"x"+gpu.height+"-"+System.currentTimeMillis()+".zip");
        JSONObject report=new JSONObject();JSONArray blocks=new JSONArray(),checks=new JSONArray();long[] frames={0,0},time={0,0};boolean success=false,energy=off(device.get());
        String driver=(gpu.renderer+" "+gpu.vendor).toLowerCase(Locale.ROOT),system=(Build.FINGERPRINT+" "+Build.HARDWARE+" "+Build.MODEL).toLowerCase(Locale.ROOT);
        boolean hardware=!driver.contains("llvmpipe")&&!driver.contains("softpipe")&&!driver.contains("swiftshader")&&!driver.contains("software")&&!driver.contains("angle")&&!system.contains("generic")&&!system.contains("emulator")&&!system.contains("ranchu")&&!system.contains("goldfish");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
            report.put("format","GPU-VIRTUAL-C09-surface-v1").put("source_commit",BuildInfo.SOURCE_SHA).put("status","NOT_VERIFIED")
                .put("device",Build.MODEL).put("gl_renderer",gpu.renderer).put("gl_vendor",gpu.vendor).put("gl_version",gpu.version)
                .put("width",gpu.width).put("height",gpu.height).put("objects",gpu.scene.count).put("preset",c.preset).put("tile_size",8).put("page_words",96)
                .put("tile_count",gpu.tiles).put("active_pages",gpu.pages).put("mask_words_per_tile",gpu.maskWords).put("scene_bytes",gpu.sceneBytes).put("metadata_bytes",gpu.metadataBytes)
                .put("layout_prepare_ns",gpu.layoutNs).put("output_rgba_bytes",4L*gpu.width*gpu.height).put("device_start",device.get())
                .put("camera_eye_fov",new JSONArray(cast(gpu.scene.eye))).put("camera_right",new JSONArray(cast(gpu.scene.right))).put("camera_up",new JSONArray(cast(gpu.scene.up))).put("camera_forward",new JSONArray(cast(gpu.scene.forward)))
                .put("contract","Opaque sphere, ellipsoid and triangle; float32 intersections, uint16 ID, RGBA8; not a bit-exact replacement of the CPU float64 engine. Background implicit; depth reconstructed from stored ID.")
                .put("allocation","One pixel pool; route switching reallocates after prior work completes. No dense comparison pool retained in compact mode. 8x8 conservative pages may include empty pixels.")
                .put("memory_scope","Requested SSBO bytes and final RGBA texture are accounted separately. PSS is measured process memory, not total GPU allocation or physical bus traffic.")
                .put("measurement_scope","Completed serial frames outside the display: capture, barriers and presentation shader into RGBA8 FBO, then signaled GLsync. One frame in flight; not maximum pipelined throughput. Scene/map preparation and route allocations excluded and reported separately.")
                .put("window_swaps_during_measurement",0).put("frames_in_flight",1).put("blocks",blocks).put("validation",checks);
            put(zip,"scene.f32",gpu.sceneBytes());for(Map.Entry<String,String> source:gpu.shaderSources.entrySet())put(zip,"shaders/"+source.getKey(),source.getValue().getBytes(StandardCharsets.UTF_8));
            checks.put(validate(zip,"before",41));
            for(int r=0;r<2;r++){gpu.reserve(r);stage("Calentando "+name(r));long start=System.nanoTime();int warm=0;while(System.nanoTime()-start<500_000_000L)frame(41+(warm++%64),false);}
            int[] order={0,1,1,0,0,1,1,0};
            for(int i=0;i<order.length;i++){
                continuing();int r=order[i];gpu.reserve(r);JSONObject before=device.get();energy&=off(before);long begin=System.nanoTime(),end=begin;int count=0;
                while(end-begin<3_000_000_000L){end=frame(41+(count%64),false);count++;measured++;stage=String.format(Locale.ROOT,"Bloque %d / 8 · %s\n%.1f FPS confirmados en serie",i+1,name(r),count*1e9/(end-begin));}
                JSONObject after=device.get();energy&=off(after);frames[r]+=count;time[r]+=end-begin;
                blocks.put(new JSONObject().put("block",i).put("route",r).put("frames",count).put("elapsed_ns",end-begin).put("fps",count*1e9/(end-begin))
                    .put("pixel_pool_reserved_bytes",gpu.pixelBytes).put("total_requested_ssbo_bytes",gpu.reservedSsboBytes()).put("pss_kib",Debug.getPss()).put("device_before",before).put("device_after",after));
            }
            checks.put(validate(zip,"after",83));success=true;report.put("status","COMPLETE");
        }catch(Throwable e){report.put("error",e.toString()).put("status",e instanceof CancellationException?"CANCELLED":"NOT_VERIFIED");}
        finally{
            JSONObject end=device.get();energy&=off(end);JSONArray results=new JSONArray();for(int r=0;r<2;r++)results.put(new JSONObject().put("route",r).put("name",name(r)).put("frames",frames[r]).put("elapsed_ns",time[r]).put("fps",time[r]>0?frames[r]*1e9/time[r]:0));
            report.put("results",results).put("confirmed_frames",confirmed).put("measured_frames",measured).put("total_elapsed_ns",System.nanoTime()-started).put("device_end",end)
                .put("completed_benchmark_result",success).put("hardware_identity_eligible",hardware).put("hardware_surface_test_passed",success&&hardware)
                .put("power_save_off_in_all_samples",energy).put("performance_comparison_eligible",success&&hardware&&energy).put("pss_kib_end",Debug.getPss());
            // Report is written even after cancellation or a failed validation.
            appendReport(file,report);busy=false;wantsTest=false;liveAt=0;gpu.reserve(selectedRoute);
            String summary=(success?"VERIFICACION GPU COMPLETA":"PRUEBA PARCIAL: "+report.optString("error"))+String.format(Locale.ROOT,"\nDenso: %.2f FPS · compacto: %.2f FPS\nCuadros confirmados en serie, sin pantalla.",time[0]>0?frames[0]*1e9/time[0]:0,time[1]>0?frames[1]*1e9/time[1]:0);
            if(success)summary+="\nColor e ID exactos; profundidad reconstruida.";
            if(!energy)summary+="\nAhorro de energia activo o sin confirmar: repetir para comparar rendimiento.";
            stage=success?"Prueba completa":"Prueba parcial";listener.result(file,summary);
        }
    }
    private static void appendReport(File file,JSONObject report)throws Exception{
        // ZipOutputStream closed above: copy entries and add a complete report atomically.
        File next=new File(file.getParentFile(),file.getName()+".pending");
        try(ZipInputStream in=new ZipInputStream(new FileInputStream(file));ZipOutputStream out=new ZipOutputStream(new FileOutputStream(next))){
            byte[] bytes=new byte[16384];for(ZipEntry e;(e=in.getNextEntry())!=null;){out.putNextEntry(new ZipEntry(e.getName()));for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);out.closeEntry();}
            put(out,"report.json",report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        SurfaceGpu.need(next.renameTo(file),"No se pudo guardar el informe final");
    }
    private static List<Double> cast(float[] values){List<Double> out=new ArrayList<>();for(float v:values)out.add((double)v);return out;}
    public static String name(int route){return route==0?"Denso GPU":"Compacto GPU";}
}
