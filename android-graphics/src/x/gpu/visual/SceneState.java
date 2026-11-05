package x.gpu.visual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Estado autoritativo del perfil C05: reglas enteras y cuatro cambios locales. */
public final class SceneState {
    public static final int DETAIL_COUNT=4, PACKET_BYTES=84;
    public final int count,tick,seed;
    public final int[] details;
    public SceneState(int count,int tick,int seed) {
        if(count<1)throw new IllegalArgumentException("Dominio vacio");
        this.count=count;this.tick=tick;this.seed=seed;details=new int[16];
        int base=Integer.remainderUnsigned(tick*17,count);
        for(int k=0;k<4;k++) {
            details[4*k]=(int)(((long)base+(long)(count/4)*k)%count);
            details[4*k+1]=51+13*k;details[4*k+2]=87+7*k;
            details[4*k+3]=0x00f0b060^(k*0x00132517);
        }
    }
    private SceneState(int count,int tick,int seed,int[] details) {
        if(count<1||details.length!=16)throw new IllegalArgumentException("Escena invalida");
        for(int k=0;k<4;k++)if(details[4*k]<0||details[4*k]>=count)throw new IllegalArgumentException("Detalle fuera del dominio");
        this.count=count;this.tick=tick;this.seed=seed;this.details=details;
    }
    public static int mix(int x) {
        x^=x>>>16;x*=0x7feb352d;x^=x>>>15;x*=0x846ca68b;x^=x>>>16;return x;
    }
    private static int tri(int x){int p=x&511;return p<256?p:511-p;}
    private void write(int id,IntBuffer out) {
        int h=mix(id^seed);
        int x=((id&63)*16+(tri(tick+(h&255))>>>2))&1023;
        int y=(((id>>>6)&63)*16+(tri(tick*3+((h>>>8)&255))>>>2))&1023;
        int z=(((id>>>12)&31)*32+tri(tick+((h>>>16)&255))*4)&1023;
        int color=(h&0x00ffffff)|((tick+id)<<24);
        for(int k=0;k<4;k++)if(details[4*k]==id) {
            x=(x+details[4*k+1])&1023;y=(y+details[4*k+2])&1023;color^=details[4*k+3];
        }
        out.put(x).put(y).put(z).put(color);
    }
    public int[] value(int id) {
        if(id<0||id>=count)throw new IndexOutOfBoundsException("Identidad fuera del dominio");
        IntBuffer out=IntBuffer.allocate(4);write(id,out);return out.array();
    }
    public void materialize(IntBuffer out) {
        if(out.capacity()/4<count)throw new IllegalArgumentException("Buffer insuficiente");
        out.clear();for(int i=0;i<count;i++)write(i,out);out.flip();
    }
    public int[] parameters(){return new int[]{count,tick,seed,DETAIL_COUNT};}
    public byte[] packet() {
        ByteBuffer b=ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x31435358);for(int v:parameters())b.putInt(v);for(int v:details)b.putInt(v);return b.array();
    }
    public static SceneState fromPacket(byte[] data) {
        if(data.length!=PACKET_BYTES)throw new IllegalArgumentException("Tamano XSC1 incorrecto");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt()!=0x31435358)throw new IllegalArgumentException("Cabecera XSC1 incorrecta");
        int n=b.getInt(),tick=b.getInt(),seed=b.getInt();
        if(b.getInt()!=4)throw new IllegalArgumentException("Perfil de detalles no admitido");
        int[] details=new int[16];for(int i=0;i<16;i++)details[i]=b.getInt();
        return new SceneState(n,tick,seed,details);
    }
}
