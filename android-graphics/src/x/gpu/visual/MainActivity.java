package x.gpu.visual;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity implements SceneRenderer.Listener {
    private GLSurfaceView surface;
    private SceneRenderer renderer;
    private TextView metrics,summary;
    private Button mode,amount,compare,save;
    private File archive;
    private int selected=1;
    private final int[] counts={4096,16384,65536};
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(7,12,24));
        root.setPadding(dp(14),dp(10),dp(14),dp(10));
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(10)+insets.getSystemWindowInsetBottom());return insets;});
        root.addView(label("GPU VIRTUAL  /  C05",12,Color.rgb(119,217,231)));
        TextView title=label("Campo de luz",27,Color.WHITE);title.setPadding(0,dp(3),0,dp(5));root.addView(title);
        TextView info=label("Una escena. Dos representaciones. Cambia de ruta y compara sus tiempos.",14,Color.rgb(171,183,203));root.addView(info);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,0,0);surface.setPreserveEGLContextOnPause(true);
        renderer=new SceneRenderer(getAssets(),getCacheDir(),this);surface.setRenderer(renderer);
        LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(-1,0,1);image.topMargin=dp(10);image.bottomMargin=dp(8);root.addView(surface,image);
        metrics=label("Abriendo la escena…",13,Color.rgb(130,221,196));root.addView(metrics);
        LinearLayout controls=new LinearLayout(this);
        mode=new Button(this);mode.setText("Ruta: compacta");controls.addView(mode,new LinearLayout.LayoutParams(0,dp(48),1));
        amount=new Button(this);amount.setText("16.384 luces");controls.addView(amount,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(controls);
        LinearLayout actions=new LinearLayout(this);compare=new Button(this);compare.setText("Comparar ahora");actions.addView(compare,new LinearLayout.LayoutParams(0,dp(48),1));
        save=new Button(this);save.setText("Guardar prueba");save.setEnabled(false);actions.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        ScrollView scroll=new ScrollView(this);summary=label("Los FPS app muestran la cadencia de dibujo. La comparacion mide cuadros terminados a 512 × 512; puede favorecer a cualquiera de las dos rutas.",12,Color.rgb(171,183,203));scroll.addView(summary);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(95)));
        setContentView(root);root.requestApplyInsets();
        mode.setOnClickListener(v->{int next=1-renderer.mode();surface.queueEvent(()->renderer.mode(next));mode.setText(next==1?"Ruta: compacta":"Ruta: completa");});
        amount.setOnClickListener(v->{selected=(selected+1)%counts.length;int count=counts[selected];surface.queueEvent(()->renderer.objects(count));amount.setText(String.format(java.util.Locale.ROOT,"%,d luces",count));});
        compare.setOnClickListener(v->{mode.setEnabled(false);amount.setEnabled(false);compare.setEnabled(false);save.setEnabled(false);renderer.compare();summary.setText("Preparando la comparacion…");});
        save.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"GPU-VIRTUAL-C05-RESULTADO.zip");startActivityForResult(i,51);});
    }
    @Override public void live(String text){runOnUiThread(()->metrics.setText(text));}
    @Override public void progress(String text){runOnUiThread(()->summary.setText(text));}
    @Override public void result(File file,JSONObject report,String text){archive=file;runOnUiThread(()->{summary.setText(text);mode.setEnabled(true);amount.setEnabled(true);compare.setEnabled(true);save.setEnabled(file.isFile());});}
    @Override protected void onResume(){super.onResume();if(surface!=null)surface.onResume();}
    @Override protected void onPause(){if(surface!=null){surface.queueEvent(()->renderer.cancel());surface.onPause();}super.onPause();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==51&&result==RESULT_OK&&data!=null&&data.getData()!=null&&archive!=null) {
            try(FileInputStream in=new FileInputStream(archive);OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null)throw new IllegalStateException("Destino no disponible");byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);
                Toast.makeText(this,"Prueba guardada",Toast.LENGTH_LONG).show();
            }catch(Exception error){Toast.makeText(this,error.toString(),Toast.LENGTH_LONG).show();}
        }
    }
}
