package x.gpu.virtual;
import java.nio.*;
import java.util.*;

public final class CompactTriangleStreamTest {
    static int checks;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    static byte[] source(int faces){
        ByteBuffer b=ByteBuffer.allocate(2064+faces*60).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'X','F','G','4'}).putShort((short)3840).putShort((short)2160).putInt(4).putInt(faces);
        Random r=new Random(20261106);
        for(int f=0;f<faces;f++){
            b.position(2064+f*60);
            for(int v=0;v<3;v++)b.putInt(r.nextInt(200000001)-100000000).putInt(r.nextInt(200000001)-100000000).putInt(1+r.nextInt(1000000));
            for(int v=0;v<6;v++)b.putShort((short)r.nextInt(65536));
            for(int v=0;v<3;v++)b.putShort((short)r.nextInt(65281));
            b.put((byte)(f%64)).put((byte)8).put((byte)(f%8)).put((byte)4).putShort((short)0);
        }return b.array();
    }
    static byte[] bytes(CompactTriangleStream stream){ByteBuffer b=stream.view(0,stream.usedBytes());byte[] r=new byte[b.remaining()];b.get(r);return r;}
    static byte[] apply(byte[] resident,CompactTriangleStream stream){
        resident=Arrays.copyOf(resident,stream.capacityBytes());
        for(int i=0;i<stream.uploadCount();i++)stream.view(stream.uploadOffset(i),stream.uploadLength(i)).get(resident,stream.uploadOffset(i),stream.uploadLength(i));
        check(Arrays.equals(bytes(stream),Arrays.copyOf(resident,stream.usedBytes())),"upload ranges reconstruct exact GPU bytes");return resident;
    }
    public static void main(String[] args){
        CompactTriangleStream s=new CompactTriangleStream();int n=1600;
        byte[] source=source(n),category=new byte[n],pass=new byte[n];Arrays.fill(category,(byte)11);
        s.update(source,category,pass);check(s.usedBytes()==n*72,"24 bytes per vertex");
        byte[] resident=apply(new byte[0],s);
        ByteBuffer packed=s.view(0,s.usedBytes()).order(ByteOrder.nativeOrder());
        ByteBuffer reference=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        for(int f=0;f<n;f++)for(int v=0;v<3;v++){
            int a=2064+f*60,d=(f*3+v)*24;
            check(packed.getInt(d)==reference.getInt(a+v*12)&&packed.getInt(d+4)==reference.getInt(a+v*12+4),"unquantized signed screen coordinates");
            check((packed.getInt(d+8)&1048575)==reference.getInt(a+v*12+8)&&(packed.getInt(d+8)>>>20)==11,"exact z and flat category");
            check(packed.getInt(d+12)==reference.getInt(a+36+v*4),"signed UV words exact");
            check(packed.getInt(d+16)==Float.floatToRawIntBits((reference.getShort(a+48+v*2)&65535)/65280f),"binary32 light exact");
            check(packed.getInt(d+20)==reference.getInt(a+54),"material bytes exact");
        }
        s.update(source.clone(),category,pass);check(s.uploadCount()==0&&s.uploadedBytes==0,"equal content reuses GPU copy across source objects");
        source[2064+923*60+4]^=1;s.update(source,category,pass);
        check(s.uploadedBytes==256*24&&s.uploadCount()==1,"one changed vertex touches one bounded page");resident=apply(resident,s);
        s.invalidateGpuCopy();s.update(source,category,pass);check(s.uploadedBytes==s.usedBytes(),"context loss reuploads all live data");resident=apply(resident,s);
        int cap=s.capacityBytes();byte[] small=source(4);s.update(small,new byte[4],new byte[4]);resident=apply(resident,s);
        check(s.capacityBytes()==cap&&s.usedBytes()==4*72,"shrinking keeps bounded capacity and exact draw count");
        s.update(source,category,pass);resident=apply(resident,s);
        byte[] max=source(8192);s.update(max,new byte[8192],new byte[8192]);check(s.capacityBytes()==8192*72,"maximum bounded allocation");apply(new byte[0],s);
        boolean rejected=false;try{s.update(source(8193),new byte[8193],new byte[8193]);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"face budget rejection");
        s.update(source(0),new byte[0],new byte[0]);check(s.usedBytes()==0&&s.uploadCount()==0,"empty stream has no stale draws");
        System.out.println("GPU_VIRTUAL_COMPACT_TRIANGLES_PASS checks="+checks);
    }
}
