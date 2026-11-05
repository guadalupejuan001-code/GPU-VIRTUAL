package x.gpu.visual;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.os.BatteryManager;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity implements SceneRenderer.Listener {
    private GLSurfaceView surface;
    private SceneRenderer renderer;
    private TextView metrics,summary,probeText;
    private LinearLayout probePanel;
    private ProgressBar probeBar;
    private Button mode,amount,compare,save,sustained,resolution,pipeline;
    private File archive,exportFile;
    private int selected=2;
    private boolean sustainedActive,resumed,exportPending,workBusy;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){@Override public void run(){if(resumed){showStartup();showProbe();ui.postDelayed(this,500);}}};
    private final int[] counts={4096,16384,65536,262144};
    private boolean hd;
    private int batch=8;
    private BenchmarkPlan plan(){return new BenchmarkPlan(hd?1280:512,hd?720:512,batch);}
    private boolean powerSave(){PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);return power==null||power.isPowerSaveMode();}
    private void energySettings(){try{startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));}catch(Exception error){startActivity(new Intent(Settings.ACTION_SETTINGS));}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(7,12,24));
        root.setPadding(dp(14),dp(10),dp(14),dp(10));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(10)+insets.getSystemWindowInsetBottom());return insets;});
        root.addView(label("GPU VIRTUAL  /  C08R2",12,Color.rgb(119,217,231)));
        TextView title=label("Graficos compactos",27,Color.WHITE);title.setPadding(0,dp(3),0,dp(5));root.addView(title);
        TextView info=label("Cuatro metodos, la misma escena. Dos rondas de FPS sostenidos fuera de pantalla.",14,Color.rgb(171,183,203));root.addView(info);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,0,0);surface.setPreserveEGLContextOnPause(true);
        renderer=new SceneRenderer(getAssets(),getCacheDir(),this);surface.setRenderer(renderer);
        FrameLayout viewport=new FrameLayout(this);viewport.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        probePanel=new LinearLayout(this);probePanel.setOrientation(LinearLayout.VERTICAL);probePanel.setGravity(Gravity.CENTER);
        probePanel.setPadding(dp(16),dp(8),dp(16),dp(8));probePanel.setBackgroundColor(Color.rgb(6,15,27));
        TextView probeTitle=label("MEDICION ACTIVA\nFUERA DE PANTALLA",16,Color.rgb(119,217,231));probeTitle.setGravity(Gravity.CENTER);probePanel.addView(probeTitle);
        probeBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);probeBar.setIndeterminate(false);probeBar.setMax(BenchmarkPlan.BLOCKS);
        probePanel.addView(probeBar,new LinearLayout.LayoutParams(-1,dp(18)));
        probeText=label("Esperando el contexto grafico…",15,Color.WHITE);probeText.setGravity(Gravity.CENTER);probePanel.addView(probeText);
        viewport.addView(probePanel,new FrameLayout.LayoutParams(-1,-1));probePanel.setVisibility(View.GONE);
        // Fixed positive viewport: large system fonts must not collapse SurfaceView to zero height.
        int viewportHeight=Math.min(dp(260),Math.max(dp(160),getResources().getDisplayMetrics().heightPixels/3));
        LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(-1,viewportHeight);image.topMargin=dp(10);image.bottomMargin=dp(8);root.addView(viewport,image);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        ScrollView controlsScroll=new ScrollView(this);controlsScroll.addView(panel,new FrameLayout.LayoutParams(-1,-2));root.addView(controlsScroll,new LinearLayout.LayoutParams(-1,0,1));
        metrics=label("Esperando superficie grafica…",13,Color.rgb(130,221,196));panel.addView(metrics);
        LinearLayout controls=new LinearLayout(this);
        mode=new Button(this);mode.setText(BenchmarkPlan.name(1));controls.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));
        amount=new Button(this);amount.setText("65.536 luces");controls.addView(amount,new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(controls);
        LinearLayout profiles=new LinearLayout(this);
        resolution=new Button(this);resolution.setText("512 x 512");profiles.addView(resolution,new LinearLayout.LayoutParams(0,dp(48),1));
        pipeline=new Button(this);pipeline.setText("Lote: 8 cuadros");profiles.addView(pipeline,new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(profiles);
        sustained=new Button(this);sustained.setText("Medir 4 metodos · ~2,5 min");panel.addView(sustained,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout actions=new LinearLayout(this);compare=new Button(this);compare.setText("Latencia (2 rutas)");actions.addView(compare,new LinearLayout.LayoutParams(0,dp(48),1));
        save=new Button(this);save.setText("Copiar diagnostico");actions.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(actions);
        summary=label("Primero se abre el visor con los shaders C06. Los cuatro metodos se preparan al pedirlos. Si una etapa tarda, Copiar diagnostico funciona sin esperar al hilo grafico. Para medir, desactiva ahorro y deja 65.536 luces, 512 x 512, lote 8.",12,Color.rgb(171,183,203));summary.setTextIsSelectable(true);panel.addView(summary);
        setContentView(root);root.requestApplyInsets();
        updateControls();
        mode.setOnClickListener(v->{int next=(renderer.mode()+1)%BenchmarkPlan.ROUTES;surface.queueEvent(()->renderer.mode(next));mode.setText(BenchmarkPlan.name(next));});
        resolution.setOnClickListener(v->{hd=!hd;resolution.setText(hd?"HD · 1280 x 720":"512 x 512");});
        pipeline.setOnClickListener(v->{batch=batch==8?16:8;pipeline.setText("Lote: "+batch+" cuadros");});
        amount.setOnClickListener(v->{selected=(selected+1)%counts.length;int count=counts[selected];surface.queueEvent(()->renderer.objects(count));amount.setText(String.format(java.util.Locale.ROOT,"%,d luces",count));});
        compare.setOnClickListener(v->{archive=null;busy(true,false);renderer.compare();summary.setText("PRUEBA CORTA · comparacion de latencia en ms…");});
        sustained.setOnClickListener(v->{
            if(sustainedActive){renderer.requestStop();sustained.setEnabled(false);summary.setText("Deteniendo: esperando que retorne la operacion del controlador. Se guardara lo parcial.");return;}
            if(powerSave()){
                new AlertDialog.Builder(this).setTitle("Repetir sin ahorro de energia").setMessage("Android indica ahorro activado o no confirma su estado. Desactivalo en los ajustes y vuelve a pulsar Medir. La app no cambia las frecuencias del chip.")
                    .setPositiveButton("Abrir ajustes",(dialog,which)->energySettings()).setNegativeButton("Volver",null).show();return;
            }
            int count=counts[selected];Display display=getWindowManager().getDefaultDisplay();
            archive=null;busy(true,true);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            renderer.requestSustained(count,()->deviceSnapshot(display),plan());surface.requestRender();showProbe();
            metrics.setText("Medicion sostenida · sin presentar cada cuadro\nEl cronometro de UI no confirma progreso GPU.");
            summary.setText("Arrancando desde un cuadro con contexto grafico listo. La animacion volvera al terminar.");
        });
        save.setOnClickListener(v->{StartupState.Snapshot s=renderer.startup();if(workBusy||!s.ready||s.loadingAdvanced||archive==null){copyDiagnostic();return;}exportFile=archive;exportPending=true;tryExport();});
        save.setOnLongClickListener(v->{copyDiagnostic();return true;});
    }
    private void busy(boolean busy,boolean longTest) {
        workBusy=busy;sustainedActive=busy&&longTest;
        sustained.setText(sustainedActive?"Detener prueba":"Medir 4 metodos · ~2,5 min");
        probePanel.setVisibility(sustainedActive?View.VISIBLE:View.GONE);
        updateControls();
    }
    private void updateControls(){
        StartupState.Snapshot s=renderer.startup();boolean idle=s.ready&&!s.fatal&&!s.loadingAdvanced&&!workBusy;
        mode.setEnabled(idle&&!s.advancedFailed);amount.setEnabled(idle);resolution.setEnabled(idle&&!s.advancedFailed);pipeline.setEnabled(idle&&!s.advancedFailed);
        compare.setEnabled(idle&&!s.advancedFailed);sustained.setEnabled(sustainedActive?!renderer.probe().stopRequested:idle&&!s.advancedFailed);
        mode.setText(BenchmarkPlan.name(renderer.mode()));save.setEnabled(true);
        save.setText(!workBusy&&s.ready&&!s.loadingAdvanced&&archive!=null?"Guardar prueba":"Copiar diagnostico");
    }
    private void showStartup(){
        StartupState.Snapshot s=renderer.startup();updateControls();
        if(s.fatal){metrics.setText(s.label+"\n"+s.error);return;}
        if(!s.ready||s.loadingAdvanced){
            metrics.setText(String.format(Locale.ROOT,"%s\n%s · %.1f s en esta etapa%s",s.label,s.stage,s.phaseNs/1e9,s.waiting?"\nSigue esperando: copia el diagnostico.":""));
        }
    }
    private void copyDiagnostic(){
        try {
            JSONObject snapshot=renderer.diagnostic().put("surface_view_width",surface.getWidth()).put("surface_view_height",surface.getHeight())
                .put("surface_view_attached",surface.isAttachedToWindow()).put("window_has_focus",surface.hasWindowFocus())
                .put("requested_width",plan().width).put("requested_height",plan().height).put("requested_pipeline",batch).put("selected_objects",counts[selected]);
            String text=snapshot.toString(2);File file=new File(getCacheDir(),"GPU-VIRTUAL-C08R2-DIAGNOSTICO.json");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(text.getBytes(StandardCharsets.UTF_8));}
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard==null)throw new IllegalStateException("Portapapeles no disponible");
            clipboard.setPrimaryClip(ClipData.newPlainText("GPU VIRTUAL C08R2",text));Toast.makeText(this,"Diagnostico copiado. Pegalo en el chat.",Toast.LENGTH_LONG).show();
        }catch(Exception error){Toast.makeText(this,"No se pudo copiar: "+error,Toast.LENGTH_LONG).show();}
    }
    private String phase(ProbeMonitor.Snapshot s){
        switch(s.stage){
            case "QUEUED":return "Esperando inicio del hilo grafico";
            case "STARTING":return "Abriendo el ensayo";
            case "VALIDATING_BEFORE":return "Verificando imagen y datos iniciales";
            case "VALIDATING_AFTER":return "Verificando imagen y datos finales";
            case "VALIDATING_RING":return "Verificando los buffers del lote GPU";
            case "WARMUP":return "Calentamiento · "+BenchmarkPlan.name(s.route);
            case "MEASURING":return BenchmarkPlan.name(s.route)+" · bloque "+(s.block+1)+" / "+BenchmarkPlan.BLOCKS;
            case "SAVING":return "Guardando la prueba";
            default:return s.stage;
        }
    }
    private String operation(String value){
        switch(value){
            case "PREPARE_AND_SUBMIT":return "preparar / enviar dibujo";
            case "WAIT_FENCE":return "esperar confirmacion GPU";
            case "WAIT_PREVIOUS":return "finalizar trabajo anterior";
            case "FLUSH":return "enviar lote al controlador";
            case "RELEASE_FENCES":return "liberar confirmaciones";
            case "VERIFY_IMAGES_AND_STATE":return "leer y comparar imagen / datos";
            case "PREPARE_TARGETS_AND_CACHE":return "preparar resolucion y cache";
            default:return value;
        }
    }
    private void showProbe(){
        if(!sustainedActive)return;ProbeMonitor.Snapshot s=renderer.probe();long tenths=s.elapsedNs/100_000_000L;
        probeBar.setProgress(s.stage.equals("MEASURING")?s.block:s.stage.equals("VALIDATING_AFTER")||s.stage.equals("DONE")?BenchmarkPlan.BLOCKS:0);
        String fps=s.windowFps<0?"FPS: esperando ventana medida":String.format(Locale.ROOT,"Ultima ventana: %.1f FPS",s.windowFps);
        String state=s.stopRequested?"Detencion solicitada · esperando al controlador":s.waiting?"Sin nuevas confirmaciones por 5 s o mas":
            s.stage.equals("QUEUED")&&s.elapsedNs>=5_000_000_000L?"El hilo grafico aun no inicio la medicion":"";
        probeText.setText(String.format(Locale.ROOT,"%02d:%02d.%d transcurridos\n%s\n%,d cuadros confirmados\n%,d durante medicion\n%s\nOperacion: %s\n%s",
            tenths/600,(tenths/10)%60,tenths%10,phase(s),s.confirmed,s.measured,fps,operation(s.operation),state));
    }
    private void tryExport(){
        if(!exportPending||!resumed||sustainedActive||exportFile==null)return;exportPending=false;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(exportFile.getName().endsWith(".json")?"application/json":"application/zip");i.putExtra(Intent.EXTRA_TITLE,exportFile.getName());startActivityForResult(i,51);
    }
    private JSONObject deviceSnapshot(Display display) {
        JSONObject result=new JSONObject();
        try {
            result.put("reported_refresh_hz",display.getRefreshRate());Display.Mode active=display.getMode();
            result.put("mode_id",active.getModeId());result.put("mode_refresh_hz",active.getRefreshRate());
            result.put("mode_width",active.getPhysicalWidth());result.put("mode_height",active.getPhysicalHeight());
            JSONArray modes=new JSONArray();for(Display.Mode m:display.getSupportedModes())modes.put(new JSONObject().put("id",m.getModeId()).put("hz",m.getRefreshRate()).put("width",m.getPhysicalWidth()).put("height",m.getPhysicalHeight()));
            result.put("api_reported_display_modes",modes);
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(power!=null){result.put("power_save_mode",power.isPowerSaveMode());if(Build.VERSION.SDK_INT>=29)result.put("thermal_status",power.getCurrentThermalStatus());}
            Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){result.put("battery_level",battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)).put("battery_scale",battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1))
                .put("battery_temperature_deci_c",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)).put("plugged",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0));}
        }catch(Exception error){try{result.put("diagnostic_error",error.toString());}catch(Exception ignored){}}
        return result;
    }
    @Override public void live(String text){runOnUiThread(()->{if(!sustainedActive)metrics.setText(text+String.format(java.util.Locale.ROOT,"\n%.1f Hz informados · ahorro %s",getWindowManager().getDefaultDisplay().getRefreshRate(),powerSave()?"ACTIVO":"desactivado"));});}
    @Override public void progress(String text){runOnUiThread(()->{if(!exportPending)summary.setText(text);});}
    @Override public void result(File file,JSONObject report,String text){runOnUiThread(()->{archive=file;summary.setText(text);mode.setText(BenchmarkPlan.name(renderer.mode()));busy(false,false);surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);tryExport();});}
    @Override public void failure(String text){runOnUiThread(()->{metrics.setText(text);summary.setText(text);busy(false,false);surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);tryExport();});}
    @Override protected void onResume(){super.onResume();resumed=true;if(surface!=null)surface.onResume();ui.removeCallbacks(heartbeat);ui.post(heartbeat);tryExport();}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(heartbeat);if(surface!=null){renderer.requestStop();surface.queueEvent(()->renderer.cancel());surface.onPause();}super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==51&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportFile!=null) {
            try(FileInputStream in=new FileInputStream(exportFile);OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null)throw new IllegalStateException("Destino no disponible");byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
                Toast.makeText(this,"Prueba guardada",Toast.LENGTH_LONG).show();
            }catch(Exception error){Toast.makeText(this,error.toString(),Toast.LENGTH_LONG).show();}
        }
        if(request==51)exportFile=null;
    }
}

