import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Arrays;
import x.gpu.probe.FilterCodec;

public final class FilterTest {
    public static void main(String[] args)throws Exception {
        DataInputStream in=new DataInputStream(new FileInputStream(args[0]));
        int count=in.readInt();long checked=0;
        for(int j=0;j<count;j++) {
            int n=in.readInt();int[] values=new int[n];for(int i=0;i<n;i++)values[i]=in.readInt();
            int size=in.readInt();byte[] expected=new byte[size];in.readFully(expected);
            byte[] actual=FilterCodec.encode(values);
            if(!Arrays.equals(actual,expected))throw new AssertionError("Encoding distinto en columna "+j);
            if(!Arrays.equals(FilterCodec.decode(actual),values))throw new AssertionError("Valores distintos");
            checked+=n;
        }
        int states=in.readInt();
        for(int j=0;j<states;j++) {
            int point=in.readInt(),n=in.readInt(),cols=in.readInt();String[] names=new String[cols];
            for(int i=0;i<cols;i++){byte[] name=new byte[in.readInt()];in.readFully(name);names[i]=new String(name,"UTF-8");}
            int[] flat=new int[n*cols];for(int i=0;i<flat.length;i++)flat[i]=in.readInt();
            byte[] expected=new byte[in.readInt()];in.readFully(expected);
            byte[] actual=FilterCodec.encodeState(point,names,flat,n,0);
            if(!Arrays.equals(actual,expected))throw new AssertionError("Estado distinto en "+j);
        }
        if(in.read()!=-1)throw new AssertionError("Datos sobrantes");in.close();
        System.out.println("{\"java_python_exact_columns\":"+count+",\"java_python_exact_states\":"+states+",\"checked_words\":"+checked+",\"hardware_gpu_executed\":false}");
    }
}
