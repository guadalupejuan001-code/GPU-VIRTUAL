package x.gpu.visual;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Authoritative input only; no CPU ray tracing, pixel generation or visibility. */
public final class SurfaceScene {
    public static final int BODY_BYTES=96;
    public final int count,preset;
    public final ByteBuffer bodies;
    public final float[] eye=new float[4],right=new float[3],up=new float[3],forward=new float[3];
    public SurfaceScene(int count,int preset,float yaw,float pitch){
        if(count<0||count>4096||preset<0||preset>3)throw new IllegalArgumentException("Perfil de superficies invalido");
        this.count=preset==3?0:count;this.preset=preset;
        bodies=ByteBuffer.allocateDirect(Math.max(4,this.count*BODY_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        int side=Math.max(1,(int)Math.ceil(Math.sqrt(Math.max(1,this.count))));
        for(int i=0;i<this.count;i++){
            float x=((i%side+.5f)/side-.5f)*8.4f,y=((i/side+.5f)/side-.5f)*5.6f,z=-.15f*(i%5);
            float radius=(preset==1?5.6f:1.4f)/side;
            int kind=i%3;
            if(preset==2&&i==0){x=0;y=0;z=0;radius=7.9f;kind=0;}
            put(x,y,z,radius);
            put(.22f+.7f*((i*37)%101)/100f,.22f+.7f*((i*61+23)%101)/100f,.22f+.7f*((i*17+67)%101)/100f,1);
            put(radius,radius*.65f,radius*.8f,i*.37f);
            put(x-radius*.75f,y-radius*.55f,z,kind);
            put(x+radius*.8f,y-radius*.55f,z+radius*.2f,0);
            put(x,y+radius*.85f,z-radius*.2f,0);
        }
        bodies.position(0);bodies.limit(Math.max(4,this.count*BODY_BYTES));
        float cy=(float)Math.cos(yaw),sy=(float)Math.sin(yaw),cp=(float)Math.cos(pitch),sp=(float)Math.sin(pitch);
        eye[0]=sy*cp*8.5f;eye[1]=sp*8.5f;eye[2]=cy*cp*8.5f;eye[3]=(float)Math.tan(Math.toRadians(47)*.5);
        right[0]=cy;right[1]=0;right[2]=-sy;
        up[0]=-sy*sp;up[1]=cp;up[2]=-cy*sp;
        forward[0]=-sy*cp;forward[1]=-sp;forward[2]=-cy*cp;
    }
    private void put(float a,float b,float c,float d){bodies.putFloat(a).putFloat(b).putFloat(c).putFloat(d);}
    public static int background(int x,int y,int width,int height){int v=5+y*18/height;return 0xff000000|((v+9)<<16)|((v+3)<<8)|v;}
}
