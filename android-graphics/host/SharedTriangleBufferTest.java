import java.nio.*;
import java.util.*;
import x.gpu.virtual.CompactTriangleStream;

public final class SharedTriangleBufferTest {
    static int checks;
    static void need(boolean ok){checks++;if(!ok)throw new AssertionError("shared source contract");}
    static byte[] scene(){
        ByteBuffer b=ByteBuffer.allocate(2064+4*60).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'X','F','G','4'}).putShort((short)3840).putShort((short)2160).putInt(4).putInt(4);
        for(int i=16;i<2064;i++)b.put(i,(byte)(i&15));
        for(int face=0;face<4;face++){
            int at=2064+face*60;
            for(int v=0;v<3;v++){
                b.putInt(at+v*12,100+face*23+v*7).putInt(at+v*12+4,-200+face*11+v*13).putInt(at+v*12+8,face==0?1300:3000);
                b.putShort(at+36+v*4,(short)(v*91-100)).putShort(at+38+v*4,(short)(v*73+17));
                b.putShort(at+48+v*2,(short)(1234+v*9786));
            }
            b.put(at+54,(byte)(24+face)).put(at+55,(byte)3).put(at+56,(byte)2).put(at+57,(byte)7);
        }
        return b.array();
    }
    static ByteBuffer direct(byte[] b){ByteBuffer view=ByteBuffer.allocateDirect(b.length);view.put(b);view.flip();return view.asReadOnlyBuffer();}
    public static void main(String[] args){
        byte[] bytes=scene(),categories={1,3,3,10},passes={1,2,2,-1},original=bytes.clone();
        CompactTriangleStream array=new CompactTriangleStream(),shared=new CompactTriangleStream();
        ByteBuffer view=direct(bytes);array.update(bytes,categories,passes);shared.update(view,categories,passes);
        need(Arrays.equals(array.starts,shared.starts)&&Arrays.equals(array.counts,shared.counts));
        need(array.view(0,array.usedBytes()).equals(shared.view(0,shared.usedBytes())));
        need(view.position()==0&&view.isReadOnly()&&Arrays.equals(bytes,original));
        shared.update(view,categories,passes);need(shared.uploadedBytes==0);
        shared.invalidateGpuCopy();shared.update(view,categories,passes);need(shared.uploadedBytes==shared.usedBytes());
        byte[] changed=bytes.clone();ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(2064,7382);
        array.update(changed,categories,passes);shared.update(direct(changed),categories,passes);
        need(array.view(0,array.usedBytes()).equals(shared.view(0,shared.usedBytes())));
        need(Arrays.equals(original,bytes)&&view.getInt(2064)==ByteBuffer.wrap(bytes).getInt(2064));
        try{shared.update((ByteBuffer)null,categories,passes);throw new AssertionError();}catch(IllegalArgumentException correct){checks++;}
        view.position(1);try{shared.update(view,categories,passes);throw new AssertionError();}catch(IllegalArgumentException correct){checks++;}
        System.out.println("{\"shared_triangle_buffer\":\"PASS\",\"checks\":"+checks+",\"vertex_stride\":24,\"byte_exact\":true,\"physical_gpu\":false}");
    }
}
