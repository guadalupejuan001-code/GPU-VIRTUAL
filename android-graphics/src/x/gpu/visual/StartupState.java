package x.gpu.visual;

import java.util.ArrayList;
import java.util.List;

/** UI-readable startup state. Readiness requires a real first-frame fence. */
public final class StartupState {
    public static final class Event {
        public final String stage,label;public final long elapsedNs;
        Event(String stage,String label,long elapsed){this.stage=stage;this.label=label;elapsedNs=elapsed;}
    }
    public static final class Snapshot {
        public final String stage,label,error;
        public final boolean ready,submitted,loadingAdvanced,advancedReady,advancedFailed,fatal,waiting;
        public final long elapsedNs,phaseNs;
        Snapshot(String stage,String label,String error,boolean ready,boolean submitted,boolean loading,boolean advanced,boolean failed,boolean fatal,long elapsed,long phase){
            this.stage=stage;this.label=label;this.error=error;this.ready=ready;this.submitted=submitted;loadingAdvanced=loading;advancedReady=advanced;advancedFailed=failed;this.fatal=fatal;elapsedNs=elapsed;phaseNs=phase;
            waiting=!fatal&&(!ready||loading)&&phase>=10_000_000_000L;
        }
    }
    private long start,phaseStart;
    private String stage,label,error="";
    private boolean ready,submitted,loading,advanced,failed,fatal;
    private final List<Event> events=new ArrayList<>();
    public StartupState(long now){reset(now);stage("WAIT_SURFACE","Esperando superficie grafica",now);}
    public synchronized void reset(long now){start=phaseStart=now;ready=submitted=loading=advanced=failed=fatal=false;error="";events.clear();stage("CONTEXT","Identificando el controlador",now);}
    public synchronized void stage(String stage,String label,long now){this.stage=stage;this.label=label;phaseStart=now;events.add(new Event(stage,label,Math.max(0,now-start)));}
    public synchronized void submitted(long now){if(submitted)throw new IllegalStateException("Primer cuadro ya enviado");submitted=true;stage("WAIT_FIRST_FRAME","Esperando confirmacion del primer cuadro",now);}
    public synchronized void confirmed(long now){if(!submitted||fatal)throw new IllegalStateException("Primer cuadro sin envio valido");ready=true;stage("READY","Escena lista · reglas compatibles C06",now);}
    public synchronized void beginAdvanced(long now){if(!ready||failed)throw new IllegalStateException("Funciones nuevas no disponibles; consulta Diagnostico");loading=true;stage("ADVANCED_SOURCE","Preparando los metodos nuevos",now);}
    public synchronized void advancedReady(long now){if(!loading)throw new IllegalStateException("Inicializacion no iniciada");loading=false;advanced=true;stage("ADVANCED_READY","Cuatro metodos disponibles",now);}
    public synchronized void fail(String error,boolean fatal,long now){this.error=error;this.fatal=fatal;loading=false;advanced=false;if(fatal)ready=false;else failed=true;stage(fatal?"ERROR":"FALLBACK",fatal?"Fallo al abrir la escena":"Visor compatible activo; fallo de funciones nuevas",now);}
    public synchronized Snapshot snapshot(long now){return new Snapshot(stage,label,error,ready,submitted,loading,advanced,failed,fatal,Math.max(0,now-start),Math.max(0,now-phaseStart));}
    public synchronized List<Event> events(){return new ArrayList<>(events);}
}
