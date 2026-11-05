package x.gpu.visual;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
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
    private Button mode,amount,compare,save,sustained;
    private File archive,exportFile;
    private int selected=2;
    private boolean sustainedActive,resumed,exportPending;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){@Override public void run(){if(resumed){showProbe();ui.postDelayed(this,500);}}};
    private final int[] counts={4096,16384,65536};
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(7,12,24));
        root.setPadding(dp(14),dp(10),dp(14),dp(10));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(10)+insets.getSystemWindowInsetBottom());return insets;});
        root.addView(label("GPU VIRTUAL  /  C07",12,Color.rgb(119,217,231)));
        TextView title=label("FPS sostenidos",27,Color.WHITE);title.setPadding(0,dp(3),0,dp(5));root.addView(title);
        TextView info=label("Mide cuadros terminados fuera de pantalla. Datos por luz frente a reglas + cambios.",14,Color.rgb(171,183,203));root.addView(info);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,0,0);surface.setPreserveEGLContextOnPause(true);
        renderer=new SceneRenderer(getAssets(),getCacheDir(),this);surface.setRenderer(renderer);
        FrameLayout viewport=new FrameLayout(this);viewport.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        probePanel=new LinearLayout(this);probePanel.setOrientation(LinearLayout.VERTICAL);probePanel.setGravity(Gravity.CENTER);
        probePanel.setPadding(dp(16),dp(8),dp(16),dp(8));probePanel.setBackgroundColor(Color.rgb(6,15,27));
        TextView probeTitle=label("MEDICION ACTIVA\nFUERA DE PANTALLA",16,Color.rgb(119,217,231));probeTitle.setGravity(Gravity.CENTER);probePanel.addView(probeTitle);
        probeBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);probeBar.setIndeterminate(false);probeBar.setMax(12);
        probePanel.addView(probeBar,new LinearLayout.LayoutParams(-1,dp(18)));
        probeText=label("Esperando el contexto grafico…",15,Color.WHITE);probeText.setGravity(Gravity.CENTER);probePanel.addView(probeText);
        viewport.addView(probePanel,new FrameLayout.LayoutParams(-1,-1));probePanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(-1,0,1);image.topMargin=dp(10);image.bottomMargin=dp(8);root.addView(viewport,image);
        metrics=label("Abriendo la escena…",13,Color.rgb(130,221,196));root.addView(metrics);
        LinearLayout controls=new LinearLayout(this);
        mode=new Button(this);mode.setText("Metodo: reglas");controls.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));
        amount=new Button(this);amount.setText("65.536 luces");controls.addView(amount,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(controls);
        sustained=new Button(this);sustained.setText("Medir FPS · 60 segundos");root.addView(sustained,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout actions=new LinearLayout(this);compare=new Button(this);compare.setText("Comparar ms");actions.addView(compare,new LinearLayout.LayoutParams(0,dp(48),1));
        save=new Button(this);save.setText("Guardar prueba");save.setEnabled(false);actions.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        ScrollView scroll=new ScrollView(this);summary=label("Elige la carga y pulsa Medir FPS. Veras tiempo, etapa y cuadros confirmados. Cada metodo recibe 30 s medidos, mas calentamiento y verificacion. Guardar diagnostico detiene el ensayo.",12,Color.rgb(171,183,203));scroll.addView(summary);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(116)));
        setContentView(root);root.requestApplyInsets();
        mode.setOnClickListener(v->{int next=1-renderer.mode();surface.queueEvent(()->renderer.mode(next));mode.setText(next==1?"Metodo: reglas":"Metodo: datos");});
        amount.setOnClickListener(v->{selected=(selected+1)%counts.length;int count=counts[selected];surface.queueEvent(()->renderer.objects(count));amount.setText(String.format(java.util.Locale.ROOT,"%,d luces",count));});
        compare.setOnClickListener(v->{archive=null;busy(true,false);renderer.compare();summary.setText("PRUEBA CORTA · comparacion de latencia en ms…");});
        sustained.setOnClickListener(v->{
            if(sustainedActive){renderer.requestStop();sustained.setEnabled(false);summary.setText("Deteniendo: esperando que retorne la operacion del controlador. Se guardara lo parcial.");return;}
            int count=counts[selected];Display display=getWindowManager().getDefaultDisplay();
            archive=null;busy(true,true);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            renderer.requestSustained(count,()->deviceSnapshot(display));surface.requestRender();showProbe();
            metrics.setText("Medicion sostenida · sin presentar cada cuadro\nEl cronometro de UI no confirma progreso GPU.");
            summary.setText("Arrancando desde un cuadro con contexto grafico listo. La animacion volvera al terminar.");
        });
        save.setOnClickListener(v->{
            try {
                if(sustainedActive){
                    JSONObject snapshot=renderer.diagnostic().put("device_snapshot",deviceSnapshot(getWindowManager().getDefaultDisplay()));
                    exportFile=new File(getCacheDir(),"GPU-VIRTUAL-C07-DIAGNOSTICO.json");
                    try(FileOutputStream out=new FileOutputStream(exportFile)){out.write(snapshot.toString(2).getBytes(StandardCharsets.UTF_8));}
                    renderer.requestStop();sustained.setEnabled(false);save.setEnabled(false);
                    summary.setText("Diagnostico preparado. Deteniendo antes de abrir el selector; esperando al controlador.");
                }else exportFile=archive;
                exportPending=exportFile!=null;tryExport();
            }catch(Exception error){Toast.makeText(this,"No se pudo preparar el archivo: "+error,Toast.LENGTH_LONG).show();}
        });
    }
    private void busy(boolean busy,boolean longTest) {
        sustainedActive=busy&&longTest;mode.setEnabled(!busy);amount.setEnabled(!busy);compare.setEnabled(!busy);
        sustained.setEnabled(!busy||longTest);sustained.setText(sustainedActive?"Detener prueba":"Medir FPS · 60 segundos");
        probePanel.setVisibility(sustainedActive?View.VISIBLE:View.GONE);
        save.setText(sustainedActive?"Guardar diagnostico":"Guardar prueba");
        save.setEnabled(sustainedActive||(!busy&&archive!=null&&archive.isFile()));
    }
    private String phase(ProbeMonitor.Snapshot s){
        switch(s.stage){
            case "QUEUED":return "Esperando inicio del hilo grafico";
            case "STARTING":return "Abriendo el ensayo";
            case "VALIDATING_BEFORE":return "Verificando imagen y datos iniciales";
            case "VALIDATING_AFTER":return "Verificando imagen y datos finales";
            case "WARMUP":return "Calentamiento · "+(s.route==0?"datos":"reglas");
            case "MEASURING":return (s.route==0?"Datos":"Reglas")+" · bloque "+(s.block+1)+" / 12";
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
            default:return value;
        }
    }
    private void showProbe(){
        if(!sustainedActive)return;ProbeMonitor.Snapshot s=renderer.probe();long tenths=s.elapsedNs/100_000_000L;
        probeBar.setProgress(s.stage.equals("MEASURING")?s.block:s.stage.equals("VALIDATING_AFTER")||s.stage.equals("DONE")?12:0);
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
        }catch(Exception error){try{result.put("diagnostic_error",error.toString());}catch(Exception ignored){}}
        return result;
    }
    @Override public void live(String text){runOnUiThread(()->{if(!sustainedActive)metrics.setText(text+String.format(java.util.Locale.ROOT,"\nPantalla: %.1f Hz informados por Android",getWindowManager().getDefaultDisplay().getRefreshRate()));});}
    @Override public void progress(String text){runOnUiThread(()->{if(!exportPending)summary.setText(text);});}
    @Override public void result(File file,JSONObject report,String text){runOnUiThread(()->{archive=file;summary.setText(text);busy(false,false);surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);tryExport();});}
    @Override public void failure(String text){runOnUiThread(()->{summary.setText(text);busy(false,false);surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);tryExport();});}
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

