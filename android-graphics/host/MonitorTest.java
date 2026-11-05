import x.gpu.visual.ProbeMonitor;

public final class MonitorTest {
    private static int checks;
    private static void need(boolean ok){checks++;if(!ok)throw new AssertionError("Monitor check "+checks);}
    public static void main(String[] args) {
        ProbeMonitor m=new ProbeMonitor();m.queue(65536,0);
        ProbeMonitor.Snapshot s=m.snapshot(2_000_000_000L);
        need(s.active&&s.stage.equals("QUEUED")&&s.elapsedNs==2_000_000_000L);
        need(s.confirmed==0&&s.measured==0&&s.windowFps<0);
        m.phase("WARMUP",0,-1,2_000_000_000L);m.submitted();
        s=m.snapshot(8_000_000_000L);need(s.waiting&&s.confirmed==0&&s.submitted==1);
        m.confirmed(8_000_000_001L);need(m.snapshot(8_000_000_002L).measured==0);
        m.phase("MEASURING",1,0,9_000_000_000L);
        for(int i=0;i<8;i++){m.submitted();m.confirmed(9_000_000_001L+i);}
        m.window(123.5);s=m.snapshot(10_000_000_000L);
        need(s.measured==8&&s.confirmed==9&&s.windowFps==123.5&&!s.waiting);
        m.requestStop();need(m.snapshot(11_000_000_000L).active&&m.snapshot(11_000_000_000L).stopRequested);
        m.finish("CANCELLED","Interrumpido",12_000_000_000L);
        s=m.snapshot(100_000_000_000L);need(!s.active&&s.stage.equals("CANCELLED")&&s.elapsedNs==12_000_000_000L);
        need(s.message.equals("Interrumpido")&&s.measured==8&&s.windowFps<0);
        need(m.events().size()==4);
        m.queue(4096,101_000_000_000L);s=m.snapshot(102_000_000_000L);
        need(s.confirmed==0&&!s.stopRequested&&m.events().size()==1);
        boolean rejected=false;try{m.confirmed(103_000_000_000L);}catch(IllegalStateException expected){rejected=true;}need(rejected);
        System.out.println("{\"monitor_checks\":"+checks+",\"heartbeat_independent_of_gpu\":true,\"submitted_frames_not_counted_as_complete\":true,\"hardware_gpu_executed\":false}");
    }
}
