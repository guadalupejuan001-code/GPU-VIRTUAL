import x.gpu.visual.SceneState;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.IntBuffer;
import java.util.Arrays;

public final class SceneTest {
    public static void main(String[] args)throws Exception {
        long values=0;int cases;
        try(DataInputStream in=new DataInputStream(new FileInputStream(args[0]))) {
            cases=in.readInt();
            for(int k=0;k<cases;k++) {
                int n=in.readInt(),tick=in.readInt(),seed=in.readInt();
                SceneState scene=new SceneState(n,tick,seed);
                byte[] packet=scene.packet();SceneState loaded=SceneState.fromPacket(packet);
                if(!Arrays.equals(packet,loaded.packet()))throw new AssertionError("Packet distinto");
                int length=in.readInt();
                for(int j=0;j<length;j++) {
                    int id=in.readInt();int[] actual=loaded.value(id);
                    for(int v:actual){if(v!=in.readInt())throw new AssertionError("Referencia distinta en caso "+k+", id "+id);values++;}
                }
                if(n<=4096) {
                    IntBuffer material=IntBuffer.allocate(n*4);loaded.materialize(material);
                    for(int id=0;id<n;id++)for(int value:loaded.value(id))if(material.get()!=value)throw new AssertionError("Expansion distinta");
                }
            }
            if(in.read()!=-1)throw new AssertionError("Datos sobrantes");
        }
        try{SceneState.fromPacket(new byte[84]);throw new AssertionError("Cabecera aceptada");}catch(IllegalArgumentException expected){}
        System.out.println("{\"scene_cases\":"+cases+",\"independent_words_checked\":"+values+",\"packet_roundtrip\":true,\"hardware_gpu_executed\":false}");
    }
}
