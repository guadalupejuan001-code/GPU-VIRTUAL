package x.gpu.visual;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.Locale;

/** Device-facing C09 instrument; all image generation happens on the GL thread. */
public final class SurfaceActivity extends Activity implements SurfaceRenderer.Listener {
    private GLSurfaceView surface;private SurfaceRenderer renderer;
    private TextView metrics,summary,progress;
    private Button route,amount,preset,resolution,depth,measure,save;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final int[] counts={64,256,1024,4096};private int selected,scenePreset;
    private boolean hd=true,depthMode,resumed;private float touchX,touchY;
    private File resultFile,exportFile;
    private final Runnable heartbeat=new Runnable(){public void run(){if(!resumed)return;boolean busy=renderer.busy();progress.setVisibility(busy||!renderer.ready()?View.VISIBLE:View.GONE);progress.setText(renderer.progress());controls();ui.postDelayed(this,400);}};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(4));return v;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setTextSize(12);return b;}
    private void row(LinearLayout panel,Button a,Button b){LinearLayout line=new LinearLayout(this);line.addView(a,new LinearLayout.LayoutParams(0,dp(52),1));line.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));panel.addView(line);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(10),dp(14),dp(10));root.setBackgroundColor(Color.rgb(6,12,24));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(10)+insets.getSystemWindowInsetBottom());return insets;});
        root.addView(label("GPU VIRTUAL  /  C09",12,Color.rgb(113,222,211)));root.addView(label("Superficies en GPU",27,Color.WHITE));
        root.addView(label("Tu buffer de superficies. Gira la camara y compara color, profundidad y memoria.",14,Color.rgb(171,185,205)));
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,0,0);surface.setPreserveEGLContextOnPause(true);
        renderer=new SurfaceRenderer(this,this,this::device);surface.setRenderer(renderer);
        int height=Math.min(dp(260),Math.max(dp(170),getResources().getDisplayMetrics().heightPixels/3));root.addView(surface,new LinearLayout.LayoutParams(-1,height));
        surface.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=event.getX();touchY=event.getY();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float x=event.getX(),y=event.getY();if(!renderer.busy())renderer.rotate((touchX-x)*.006f,(y-touchY)*.006f);touchX=x;touchY=y;return true;}
            return true;
        });
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(this);scroll.addView(panel);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        metrics=label("Abriendo el contexto grafico…",12,Color.rgb(132,225,195));panel.addView(metrics);
        progress=label("Esperando GPU",13,Color.WHITE);panel.addView(progress);
        route=button("Buffer: compacto GPU");amount=button("64 objetos");row(panel,route,amount);
        preset=button("Escena: dispersa");resolution=button("HD · 1280 x 720");row(panel,preset,resolution);
        depth=button("Ver profundidad");save=button("Copiar diagnostico");row(panel,depth,save);
        measure=button("Verificar y medir · ~30 s");panel.addView(measure,new LinearLayout.LayoutParams(-1,dp(52)));
        Button legacy=button("Abrir pruebas de luces C08");panel.addView(legacy,new LinearLayout.LayoutParams(-1,dp(48)));
        summary=label("Esferas, elipsoides y triangulos opacos. Fondo por regla y profundidad reconstruida. La GPU crea el mapa y la imagen. La escena ocupada permite comprobar el coste cuando compactar no ahorra. Mantiene precision float32; aun no compone transparencias.",12,Color.rgb(171,185,205));summary.setTextIsSelectable(true);panel.addView(summary);
        setContentView(root);root.requestApplyInsets();
        route.setOnClickListener(v->{int r=1-renderer.route();renderer.route(r);route.setText(r==1?"Buffer: compacto GPU":"Buffer: denso GPU");resultFile=null;});
        amount.setOnClickListener(v->{selected=(selected+1)%counts.length;amount.setText(String.format(Locale.ROOT,"%,d objetos",counts[selected]));configure();});
        preset.setOnClickListener(v->{scenePreset=(scenePreset+1)%4;preset.setText("Escena: "+new String[]{"dispersa","superpuesta","ocupada","vacia"}[scenePreset]);configure();});
        resolution.setOnClickListener(v->{hd=!hd;resolution.setText(hd?"HD · 1280 x 720":"640 x 360");configure();});
        depth.setOnClickListener(v->{depthMode=!depthMode;renderer.depth(depthMode);depth.setText(depthMode?"Ver color":"Ver profundidad");});
        measure.setOnClickListener(v->{if(renderer.busy()){renderer.stop();return;}resultFile=null;renderer.requestTest();summary.setText("Verificando ambas representaciones. La medicion terminara cuadros fuera de pantalla; el visor volvera al finalizar.");});
        save.setOnClickListener(v->{if(resultFile!=null&&!renderer.busy()){exportFile=resultFile;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,resultFile.getName());startActivityForResult(i,91);}else copy();});
        save.setOnLongClickListener(v->{copy();return true;});legacy.setOnClickListener(v->{if(!renderer.busy())startActivity(new Intent(this,MainActivity.class));});controls();
    }
    private void configure(){resultFile=null;renderer.configure(counts[selected],scenePreset,hd);}
    private void controls(){boolean enabled=renderer.ready()&&!renderer.busy()&&!renderer.failed();for(Button b:new Button[]{route,amount,preset,resolution,depth})b.setEnabled(enabled);measure.setEnabled(renderer.ready()&&!renderer.failed());measure.setText(renderer.busy()?"Detener y guardar parcial":"Verificar y medir · ~30 s");save.setText(resultFile!=null&&!renderer.busy()?"Guardar prueba":"Copiar diagnostico");}
    private void copy(){try{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cb==null)throw new IllegalStateException("Portapapeles no disponible");cb.setPrimaryClip(ClipData.newPlainText("GPU VIRTUAL C09",renderer.diagnostic().toString(2)));Toast.makeText(this,"Diagnostico copiado",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}
    private JSONObject device(){JSONObject out=new JSONObject();try{PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);if(power!=null){out.put("power_save_mode",power.isPowerSaveMode());if(Build.VERSION.SDK_INT>=29)out.put("thermal_status",power.getCurrentThermalStatus());}
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(b!=null)out.put("battery_temperature_deci_c",b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)).put("battery_level",b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)).put("plugged",b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0));
    }catch(Exception e){try{out.put("error",e.toString());}catch(Exception ignored){}}return out;}
    @Override public void status(String text){runOnUiThread(()->metrics.setText(text));}
    @Override public void result(File file,String text){runOnUiThread(()->{resultFile=file;summary.setText(text);controls();});}
    @Override public void failure(String text){runOnUiThread(()->{metrics.setText(text);summary.setText("Copia el diagnostico para revisar el fallo del controlador.");controls();});}
    @Override protected void onResume(){super.onResume();resumed=true;if(surface!=null)surface.onResume();ui.removeCallbacks(heartbeat);ui.post(heartbeat);}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(heartbeat);if(renderer!=null)renderer.stop();if(surface!=null)surface.onPause();super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==91&&result==RESULT_OK&&data!=null&&data.getData()!=null&&exportFile!=null){try(InputStream in=new FileInputStream(exportFile);OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Sin destino");byte[] bytes=new byte[16384];for(int n;(n=in.read(bytes))!=-1;)out.write(bytes,0,n);Toast.makeText(this,"Prueba guardada",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}}if(request==91)exportFile=null;}
}
