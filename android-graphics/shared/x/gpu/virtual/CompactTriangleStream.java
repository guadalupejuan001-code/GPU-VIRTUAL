package x.gpu.virtual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Exact XFG4-to-GPU transport. The producer owns geometry and pass semantics.
 * Positions/UVs stay integer; the original binary32 light scalar is retained.
 * z <= 1,000,000 leaves twelve bits for the flat category without quantization.
 * No pixel, world equation, visibility guess or driver readback is used here.
 */
public final class CompactTriangleStream {
    public static final int STRIDE=24, MAX_FACES=8192, PAGE_VERTICES=256;
    private static final int HEADER=2064, FACE=60, PAGE_BYTES=STRIDE*PAGE_VERTICES;
    private ByteBuffer data=ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
    private int[] order=new int[0], offsets=new int[0], lengths=new int[0];
    private boolean[] dirty=new boolean[0];
    private boolean invalid=true;
    private byte[] packet;
    private int ranges, usedBytes;
    public final int[] starts=new int[4], counts=new int[4];
    public long uploadedBytes;

    private static int i32(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|(b[at+3]<<24);}
    private static int u16(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8);}
    private static void need(boolean ok,String why){if(!ok)throw new IllegalArgumentException("GPU-VIRTUAL XFG4: "+why);}
    private void reserve(int faces){
        if(faces<=order.length)return;
        int capacity=256;while(capacity<faces)capacity*=2;
        order=new int[capacity];data=ByteBuffer.allocateDirect(capacity*3*STRIDE).order(ByteOrder.nativeOrder());
        int pages=(data.capacity()+PAGE_BYTES-1)/PAGE_BYTES;
        dirty=new boolean[pages];offsets=new int[pages];lengths=new int[pages];invalid=true;
    }
    private int depth(int face){int at=HEADER+face*FACE;return i32(packet,at+8)+i32(packet,at+20)+i32(packet,at+32);}
    // Preserve the original adapter's ordering, including equal-depth alpha faces.
    private void sort(int low,int high){
        int l=low,r=high,pivot=depth(order[(low+high)>>>1]);
        while(l<=r){while(depth(order[l])>pivot)l++;while(depth(order[r])<pivot)r--;
            if(l<=r){int t=order[l];order[l++]=order[r];order[r--]=t;}}
        if(low<r)sort(low,r);if(l<high)sort(l,high);
    }
    private void word(int at,int value){
        if(data.getInt(at)!=value){data.putInt(at,value);dirty[at/PAGE_BYTES]=true;}
    }
    /** Call after a GL context loss or any GPU buffer reallocation. */
    public void invalidateGpuCopy(){invalid=true;}

    /** Pass -1 omits a metadata face. Pass 2 keeps the exact back-to-front sort. */
    public void update(byte[] source,byte[] categories,byte[] passes){
        need(source!=null&&source.length>=HEADER,"truncated source");
        int faces=i32(source,12);
        need(source[0]=='X'&&source[1]=='F'&&source[2]=='G'&&source[3]=='4'&&
             u16(source,4)==3840&&u16(source,6)==2160&&i32(source,8)==4,"source revision");
        need(faces>=0&&faces<=MAX_FACES&&source.length==HEADER+faces*FACE,"face budget or length");
        need(categories!=null&&passes!=null&&categories.length>=faces&&passes.length>=faces,"classification length");
        reserve(faces);packet=source;Arrays.fill(counts,0);Arrays.fill(dirty,false);
        for(int i=0;i<faces;i++){
            int p=passes[i],c=categories[i]&255;need(p>=-1&&p<4&&c<16,"classification");
            int at=HEADER+i*FACE;
            for(int v=0;v<3;v++){int z=i32(source,at+v*12+8);need(z>0&&z<=1_000_000,"z exceeds exact packed domain");}
            if(p>=0)counts[p]++;
        }
        int n=0;int[] cursor=new int[4];
        for(int p=0;p<4;p++){starts[p]=n;cursor[p]=n;n+=counts[p];}
        for(int i=0;i<faces;i++)if(passes[i]>=0)order[cursor[passes[i]]++]=i;
        if(counts[2]>1)sort(starts[2],starts[2]+counts[2]-1);
        for(int f=0;f<n;f++){
            int face=order[f],at=HEADER+face*FACE,c=categories[face]&255,material=i32(source,at+54);
            for(int v=0;v<3;v++){
                int dst=(f*3+v)*STRIDE,z=i32(source,at+v*12+8);
                word(dst,i32(source,at+v*12));word(dst+4,i32(source,at+v*12+4));
                word(dst+8,z|(c<<20));word(dst+12,i32(source,at+36+v*4));
                word(dst+16,Float.floatToRawIntBits(u16(source,at+48+v*2)/65280f));
                word(dst+20,material);
            }
        }
        for(int p=0;p<4;p++){starts[p]*=3;counts[p]*=3;}
        usedBytes=n*3*STRIDE;ranges=0;uploadedBytes=0;
        for(int page=0;page*PAGE_BYTES<usedBytes;page++)if(invalid||dirty[page]){
            int start=page*PAGE_BYTES,length=Math.min(PAGE_BYTES,usedBytes-start);
            if(ranges>0&&offsets[ranges-1]+lengths[ranges-1]==start)lengths[ranges-1]+=length;
            else {offsets[ranges]=start;lengths[ranges++]=length;}
            uploadedBytes+=length;
        }
        invalid=false;packet=null;
    }
    public int capacityBytes(){return data.capacity();}
    public int usedBytes(){return usedBytes;}
    public int uploadCount(){return ranges;}
    public int uploadOffset(int range){return offsets[range];}
    public int uploadLength(int range){return lengths[range];}
    public ByteBuffer view(int offset,int length){
        if(offset<0||length<0||offset+length>usedBytes)throw new IndexOutOfBoundsException();
        ByteBuffer view=data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        view.position(offset);view.limit(offset+length);return view;
    }
}
