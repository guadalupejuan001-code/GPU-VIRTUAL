import x.gpu.visual.StartupState;

public final class StartupTest {
    private static int checks;
    private static void need(boolean ok){checks++;if(!ok)throw new AssertionError("Startup check "+checks);}
    public static void main(String[] args) {
        StartupState s=new StartupState(0);StartupState.Snapshot p=s.snapshot(1);
        need(!p.ready&&!p.submitted&&!p.advancedReady&&p.error.isEmpty());
        boolean rejected=false;try{s.beginAdvanced(1);}catch(IllegalStateException expected){rejected=true;}need(rejected);
        s.stage("VERTEX","Compilando shader compatible",2);p=s.snapshot(12_000_000_002L);
        need(p.waiting&&!p.ready&&p.phaseNs==12_000_000_000L);
        s.submitted(13_000_000_000L);p=s.snapshot(20_000_000_000L);need(p.submitted&&!p.ready);
        s.confirmed(21_000_000_000L);need(s.snapshot(22_000_000_000L).ready&&!s.snapshot(22_000_000_000L).waiting);
        s.beginAdvanced(23_000_000_000L);p=s.snapshot(34_000_000_000L);need(p.ready&&p.loadingAdvanced&&p.waiting);
        s.fail("Compiler log",false,35_000_000_000L);p=s.snapshot(36_000_000_000L);
        need(p.ready&&!p.fatal&&p.advancedFailed&&!p.loadingAdvanced&&p.error.equals("Compiler log"));
        rejected=false;try{s.beginAdvanced(37_000_000_000L);}catch(IllegalStateException expected){rejected=true;}need(rejected);
        need(s.events().size()>=6);
        s.reset(40_000_000_000L);p=s.snapshot(41_000_000_000L);need(!p.ready&&!p.advancedFailed&&!p.submitted&&p.error.isEmpty());
        s.submitted(42_000_000_000L);s.confirmed(43_000_000_000L);s.beginAdvanced(44_000_000_000L);s.advancedReady(45_000_000_000L);
        p=s.snapshot(46_000_000_000L);need(p.ready&&p.advancedReady&&!p.loadingAdvanced);
        s.reset(47_000_000_000L);s.fail("GL context error",true,48_000_000_000L);p=s.snapshot(90_000_000_000L);
        need(p.fatal&&!p.ready&&!p.waiting&&p.error.equals("GL context error"));
        rejected=false;try{s.confirmed(91_000_000_000L);}catch(IllegalStateException expected){rejected=true;}need(rejected);
        System.out.println("{\"startup_checks\":"+checks+",\"ready_only_after_frame_confirmation\":true,\"fallback_preserves_preview\":true,\"hardware_gpu_executed\":false}");
    }
}
