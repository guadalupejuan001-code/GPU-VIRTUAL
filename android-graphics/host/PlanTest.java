import x.gpu.visual.BenchmarkPlan;
import x.gpu.visual.SustainedStats;

public final class PlanTest {
    private static int checks;
    private static void need(boolean ok){checks++;if(!ok)throw new AssertionError("Plan check "+checks);}
    public static void main(String[] args) {
        for(int[] size:new int[][]{{512,512},{1280,720}})for(int pipeline:new int[]{8,16}){
            BenchmarkPlan p=new BenchmarkPlan(size[0],size[1],pipeline);
            int[] counts=new int[4],positions=new int[4];
            for(int i=0;i<32;i++){int r=p.route(i);counts[r]++;positions[r]+=i;need(r==p.route(31-i));}
            for(int r=0;r<4;r++)need(counts[r]==8&&positions[r]==124&&counts[r]*BenchmarkPlan.BLOCK_NS==32_000_000_000L);
            for(int i=0;i<32;i+=4){int mask=0;for(int j=0;j<4;j++)mask|=1<<p.route(i+j);need(mask==15);}
            need(p.round(15)==0&&p.round(16)==1);
        }
        SustainedStats s=new SustainedStats(4);s.add(2,200,1_000_000_000L);s.add(3,300,2_000_000_000L);
        need(s.fps(2)==200&&s.fps(3)==150&&s.frames(0)==0);
        boolean rejected=false;try{new BenchmarkPlan(1920,1080,8);}catch(IllegalArgumentException expected){rejected=true;}need(rejected);
        rejected=false;try{s.add(4,1,1);}catch(IllegalArgumentException expected){rejected=true;}need(rejected);
        System.out.println("{\"plan_checks\":"+checks+",\"four_routes_balanced\":true,\"two_mirrored_rounds\":true,\"hardware_gpu_executed\":false}");
    }
}
