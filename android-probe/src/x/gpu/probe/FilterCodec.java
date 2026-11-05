package x.gpu.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Implementacion propia del formato de columna C02; probada contra fixtures Python. */
public final class FilterCodec {
    private FilterCodec() {}
    private static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void u8(int v) { out.write(v & 255); }
        void u16(int v) { u8(v);u8(v >>> 8); }
        void u32(int v) { for (int j=0;j<4;j++) u8(v >>> (8*j)); }
        void raw(byte[] bytes) { out.write(bytes,0,bytes.length); }
        byte[] bytes() { return out.toByteArray(); }
    }
    private static int common(int[] values) {
        Map<Integer,Integer> counts = new LinkedHashMap<Integer,Integer>();
        for (int value : values) counts.put(value, counts.containsKey(value) ? counts.get(value)+1 : 1);
        int best=0, count=-1;
        for (Map.Entry<Integer,Integer> entry : counts.entrySet())
            if (entry.getValue()>count) { best=entry.getKey();count=entry.getValue(); }
        return best;
    }
    private static int period(int[] v) {
        if (v.length==0) return 0;
        int[] p=new int[v.length];
        for(int i=1;i<v.length;i++) {
            int j=p[i-1];
            while(j>0 && v[i]!=v[j]) j=p[j-1];
            if(v[i]==v[j]) j++;
            p[i]=j;
        }
        return v.length-p[v.length-1];
    }
    private static Writer begin(int tag,int n) { Writer w=new Writer();w.u8(tag);w.u32(n);return w; }
    private static byte[] shorter(byte[] a,byte[] b) {
        return b.length<a.length || (b.length==a.length && (b[0]&255)<(a[0]&255)) ? b : a;
    }
    public static byte[] encode(int[] v) {
        int n=v.length;if(n==0)throw new IllegalArgumentException("Campo vacio");
        Writer w=begin(0,n);for(int x:v)w.u32(x);byte[] best=w.bytes();
        boolean constant=true,affine=true;
        int step=n>1?v[1]-v[0]:0;
        for(int i=0;i<n;i++) {constant &= v[i]==v[0];affine &= v[i]==v[0]+i*step;}
        if(constant){w=begin(1,n);w.u32(v[0]);best=shorter(best,w.bytes());}
        if(affine){w=begin(2,n);w.u32(v[0]);w.u32(step);best=shorter(best,w.bytes());}
        int p=period(v);w=begin(3,n);w.u32(p);for(int i=0;i<p;i++)w.u32(v[i]);best=shorter(best,w.bytes());
        int slope=0;
        if(n>1) {
            int[] d=new int[n-1];for(int i=1;i<n;i++)d[i-1]=v[i]-v[i-1];
            p=period(d);w=begin(4,n);w.u32(v[0]);w.u32(p);for(int i=0;i<p;i++)w.u32(d[i]);best=shorter(best,w.bytes());
            slope=common(d);
        }
        int[] bases=new int[n];for(int i=0;i<n;i++)bases[i]=v[i]-i*slope;
        int base=common(bases),details=0;
        for(int i=0;i<n;i++)if(v[i]!=base+i*slope)details++;
        w=begin(5,n);w.u32(base);w.u32(slope);w.u32(details);
        for(int i=0;i<n;i++)if(v[i]!=base+i*slope){w.u32(i);w.u32(v[i]);}
        best=shorter(best,w.bytes());
        if(!Arrays.equals(decode(best),v))throw new IllegalStateException("Filtro con perdida");
        return best;
    }
    public static int[] decode(byte[] data) {
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(b.remaining()<5)throw new IllegalArgumentException("Columna truncada");
        int tag=b.get()&255,n=b.getInt();
        if(n<=0)throw new IllegalArgumentException("Dominio invalido");
        int[] out=new int[n];
        if(tag==0){for(int i=0;i<n;i++)out[i]=b.getInt();}
        else if(tag==1){int value=b.getInt();Arrays.fill(out,value);}
        else if(tag==2){int base=b.getInt(),step=b.getInt();for(int i=0;i<n;i++)out[i]=base+i*step;}
        else if(tag==3){int p=b.getInt();if(p<1||p>n)throw new IllegalArgumentException("Periodo invalido");int[] pattern=new int[p];for(int i=0;i<p;i++)pattern[i]=b.getInt();for(int i=0;i<n;i++)out[i]=pattern[i%p];}
        else if(tag==4){int base=b.getInt(),p=b.getInt();if(p<1||p>=n)throw new IllegalArgumentException("Diferencias invalidas");int[] pattern=new int[p];for(int i=0;i<p;i++)pattern[i]=b.getInt();out[0]=base;for(int i=1;i<n;i++)out[i]=out[i-1]+pattern[(i-1)%p];}
        else if(tag==5){int base=b.getInt(),step=b.getInt(),k=b.getInt();if(k<0||k>n)throw new IllegalArgumentException("Detalles invalidos");for(int i=0;i<n;i++)out[i]=base+i*step;int previous=-1;for(int j=0;j<k;j++){int at=b.getInt(),value=b.getInt();if(at<=previous||at>=n)throw new IllegalArgumentException("Indice invalido");out[at]=value;previous=at;}}
        else throw new IllegalArgumentException("Filtro desconocido");
        if(b.hasRemaining())throw new IllegalArgumentException("Bytes sobrantes");
        return out;
    }
    public static byte[] encodeState(int point,String[] names,int[] flat,int n,int offset) {
        int columns=names.length;
        if(n<=0 || columns==0 || offset<0 || offset+n*columns>flat.length)
            throw new IllegalArgumentException("Matriz invalida");
        Writer w=new Writer();w.raw(new byte[]{'T','F','S','2'});w.u32(point);w.u32(n);w.u16(columns);
        for(int at=0;at<columns;at++) {
            byte[] label=names[at].getBytes(StandardCharsets.UTF_8);
            if(label.length>255)throw new IllegalArgumentException("Nombre largo");
            int[] values=new int[n];for(int i=0;i<n;i++)values[i]=flat[offset+i*columns+at];
            byte[] payload=encode(values);
            w.u8(label.length);w.raw(label);w.u32(payload.length);w.raw(payload);
        }
        return w.bytes();
    }
}
