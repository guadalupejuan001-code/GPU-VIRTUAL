package x.gpu.visual;

import java.util.ArrayList;
import java.util.List;

/** Telemetria legible desde UI sin esperar al hilo GL ni fabricar cuadros/FPS. */
public final class ProbeMonitor {
    public static final class Snapshot {
        public final boolean active,stopRequested,waiting;
        public final String stage,operation,message;
        public final int count,route,block;
        public final long elapsedNs,operationNs,sinceConfirmationNs,submitted,confirmed,measured;
        public final double windowFps;
        Snapshot(boolean active,boolean stop,boolean waiting,String stage,String operation,String message,int count,int route,int block,
                 long elapsed,long operationNs,long since,long submitted,long confirmed,long measured,double fps) {
            this.active=active;stopRequested=stop;this.waiting=waiting;this.stage=stage;this.operation=operation;this.message=message;
            this.count=count;this.route=route;this.block=block;elapsedNs=elapsed;this.operationNs=operationNs;sinceConfirmationNs=since;
            this.submitted=submitted;this.confirmed=confirmed;this.measured=measured;windowFps=fps;
        }
    }
    public static final class Event {
        public final String stage;
        public final int route,block;
        public final long elapsedNs;
        Event(String stage,int route,int block,long elapsed){this.stage=stage;this.route=route;this.block=block;elapsedNs=elapsed;}
    }
    private boolean active,stop;
    private String stage="IDLE",operation="",message="";
    private int count,route=-1,block=-1;
    private long start,end,phaseStart,operationStart,lastConfirmation,submitted,confirmed,measured;
    private double fps=-1;
    private final List<Event> events=new ArrayList<>();
    public synchronized void queue(int count,long now) {
        if(count<1)throw new IllegalArgumentException("Carga vacia");
        this.count=count;active=true;stop=false;start=end=operationStart=lastConfirmation=now;
        submitted=confirmed=measured=0;fps=-1;route=block=-1;message="";events.clear();phase("QUEUED",-1,-1,now);
    }
    public synchronized void phase(String stage,int route,int block,long now) {
        this.stage=stage;this.route=route;this.block=block;operation=stage;phaseStart=operationStart=now;fps=-1;
        events.add(new Event(stage,route,block,Math.max(0,now-start)));
    }
    public synchronized void operation(String name,long now){if(!operation.equals(name)){operation=name;operationStart=now;}}
    public synchronized void submitted(){if(active)submitted++;}
    public synchronized void confirmed(long now) {
        if(!active||confirmed>=submitted)throw new IllegalStateException("Confirmacion sin envio");
        confirmed++;if(stage.equals("MEASURING"))measured++;lastConfirmation=now;
    }
    public synchronized void window(double value) {
        if(!stage.equals("MEASURING")||!Double.isFinite(value)||value<0)throw new IllegalStateException("Ventana invalida");
        fps=value;
    }
    public synchronized void requestStop(){stop=true;}
    public synchronized void finish(String status,String message,long now){active=false;end=now;this.message=message==null?"":message;phase(status,route,block,now);}
    public synchronized List<Event> events(){return new ArrayList<>(events);}
    public synchronized Snapshot snapshot(long now) {
        long at=active?now:end;
        long since=Math.max(0,at-lastConfirmation);
        boolean waiting=active&&(stage.equals("WARMUP")||stage.equals("MEASURING"))&&at-Math.max(lastConfirmation,phaseStart)>=5_000_000_000L;
        return new Snapshot(active,stop,waiting,stage,operation,message,count,route,block,Math.max(0,at-start),Math.max(0,at-operationStart),since,submitted,confirmed,measured,fps);
    }
}
