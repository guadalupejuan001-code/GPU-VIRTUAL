package x.gpu.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private TextView status;
    private Button run,save;
    private File archive;
    private Thread worker;
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setFitsSystemWindows(true);
        root.setPadding(dp(20),dp(20),dp(20),dp(20));root.setBackgroundColor(Color.rgb(12,18,24));
        TextView title=new TextView(this);title.setText("GPU VIRTUAL\nPrueba en el dispositivo");title.setTextColor(Color.WHITE);title.setTextSize(25);root.addView(title);
        TextView info=new TextView(this);info.setText("Identifica la GPU, ejecuta las pruebas y guarda las capturas para revisar sus resultados.");info.setTextColor(Color.LTGRAY);info.setTextSize(16);info.setPadding(0,dp(15),0,dp(15));root.addView(info);
        run=new Button(this);run.setText("Ejecutar prueba en GPU");root.addView(run);
        save=new Button(this);save.setText("Guardar resultado");save.setEnabled(false);root.addView(save);
        ScrollView scroll=new ScrollView(this);status=new TextView(this);status.setTextColor(Color.rgb(164,221,199));status.setTextSize(16);status.setPadding(0,dp(18),0,dp(18));status.setText("Lista para identificar el dispositivo.");scroll.addView(status);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        run.setOnClickListener(v->start());
        save.setOnClickListener(v->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/zip");intent.putExtra(Intent.EXTRA_TITLE,"GPU-FISICA-C03-RESULTADO.zip");startActivityForResult(intent,41);});
    }
    private void text(String message){runOnUiThread(()->status.setText(message));}
    private void start() {
        run.setEnabled(false);save.setEnabled(false);archive=new File(getCacheDir(),"GPU-FISICA-C03-RESULTADO.zip");
        worker=new Thread(()->{
            try {
                JSONObject report=new GpuProbe(getAssets(),this::text).run(archive);
                boolean passed=report.optBoolean("hardware_gpu_test_passed",false);
                String message=(passed?"PRUEBA SUPERADA":"PRUEBA NO VERIFICADA")+"\n\n"+report.optString("model","")+"\nGPU: "+report.optString("gl_renderer","Sin identificar")+"\n\nValores verificados: "+report.optLong("checked_words",0)+"\nCasos completados: "+report.getJSONArray("cases").length();
                if(!passed)message+="\n\n"+report.optString("error","No se ha confirmado una GPU fisica.");
                message+="\n\nGuarda el resultado para compartir el informe y las capturas.";text(message);
                runOnUiThread(()->{run.setEnabled(true);save.setEnabled(archive.isFile());});
            } catch(Exception error) {text("No se pudo completar la prueba:\n"+error);runOnUiThread(()->run.setEnabled(true));}
        },"gpu-physical-probe");worker.start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null&&archive!=null) {
            try(FileInputStream input=new FileInputStream(archive);OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null)throw new IllegalStateException("No se pudo abrir el destino");
                byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)out.write(buffer,0,n);
                Toast.makeText(this,"Resultado guardado",Toast.LENGTH_LONG).show();
            } catch(Exception error){Toast.makeText(this,error.toString(),Toast.LENGTH_LONG).show();}
        }
    }
    @Override protected void onDestroy(){if(worker!=null)worker.interrupt();super.onDestroy();}
}
