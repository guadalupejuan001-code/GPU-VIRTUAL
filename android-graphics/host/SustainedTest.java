import x.gpu.visual.SustainedStats;

public final class SustainedTest {
    private static int checks;
    private static void equal(double actual,double expected){checks++;if(Math.abs(actual-expected)>1e-9)throw new AssertionError(actual+" != "+expected);}
    private static void reject(Runnable operation){checks++;try{operation.run();}catch(IllegalArgumentException|ArithmeticException expected){return;}throw new AssertionError("Bloque invalido aceptado");}
    public static void main(String[] args) {
        SustainedStats s=new SustainedStats();equal(s.fps(0),0);equal(s.fps(1),0);
        s.add(0,60,1_000_000_000L);s.add(1,240,2_000_000_000L);s.add(0,60,2_000_000_000L);
        equal(s.fps(0),40);equal(s.fps(1),120);equal(s.bestBlockFps(0),60);equal(s.lastBlocksFps(0,1),30);
        equal(s.lastBlocksFps(0,2),40);equal(s.frames(0),120);equal(s.nanos(0),3_000_000_000L);
        // La tasa sostenida es cuadros/suma de tiempos, no la media 45 de 60 y 30.
        for(int i=0;i<12;i++)s.add(i%2,120+i,5_000_000_000L+i);
        long frames=0,nanos=0;for(SustainedStats.Block b:s.blocks())if(b.route==1){frames+=b.frames;nanos+=b.nanos;}
        equal(s.fps(1),frames*(1e9/nanos));equal(s.blocks().size(),15);
        reject(()->s.add(2,1,1));reject(()->s.add(0,0,1));reject(()->s.add(1,1,0));reject(()->s.lastBlocksFps(0,0));
        long before=s.frames(0);reject(()->s.add(0,Long.MAX_VALUE,1));equal(s.frames(0),before);
        SustainedStats fast=new SustainedStats();fast.add(1,9000,1_000_000_000L);equal(fast.fps(1),9000);
        System.out.println("{\"sustained_stats_checks\":"+checks+",\"weighted_rates_exact\":true,\"fps_clamped_to_screen\":false,\"hardware_gpu_executed\":false}");
    }
}
