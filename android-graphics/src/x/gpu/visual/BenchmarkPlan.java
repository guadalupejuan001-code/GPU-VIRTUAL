package x.gpu.visual;

/** Finite workloads of this instrument, not claimed limits of the representation. */
public final class BenchmarkPlan {
    public static final int ROUTES=4,BLOCKS=32;
    public static final long BLOCK_NS=4_000_000_000L;
    public final int width,height,pipeline;
    private final int[] order=new int[BLOCKS];
    public BenchmarkPlan(int width,int height,int pipeline) {
        if(!((width==512&&height==512)||(width==1280&&height==720))||(pipeline!=8&&pipeline!=16))throw new IllegalArgumentException("Perfil desconocido");
        this.width=width;this.height=height;this.pipeline=pipeline;
        int[] first={0,1,3,2,1,2,0,3,2,3,1,0,3,0,2,1};
        for(int i=0;i<16;i++){order[i]=first[i];order[31-i]=first[i];}
    }
    public int route(int block){return order[block];}
    public int round(int block){return block/16;}
    public static String name(int route) {
        switch(route){case 0:return "Datos CPU";case 1:return "Reglas";case 2:return "Reglas + cache";case 3:return "Datos GPU";default:throw new IllegalArgumentException("Metodo desconocido");}
    }
    public String label(){return width+" x "+height+" · lote "+pipeline;}
}
