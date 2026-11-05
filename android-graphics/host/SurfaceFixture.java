import x.gpu.visual.SurfaceScene;
import java.nio.file.*;
import java.nio.*;
public class SurfaceFixture {
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        for(int preset=0;preset<5;preset++)for(int n:new int[]{8,64,256,4096}){
            SurfaceScene scene=new SurfaceScene(n,preset,.17f,-.09f);
            write(scene,root.resolve("surface-"+n+"-"+preset+".bin"));
        }
        for(int n:new int[]{16384,65535})write(new SurfaceScene(n,0,.17f,-.09f),root.resolve("surface-"+n+"-0.bin"));
        write(new SurfaceScene(64,0,1.2f,.6f),root.resolve("surface-orbit.bin"));
        SurfaceScene inside=new SurfaceScene(64,2,0,0);inside.eye[0]=0;inside.eye[1]=0;inside.eye[2]=0;
        write(inside,root.resolve("surface-inside.bin"));
        System.out.println("Surface inputs: 24 Java fixtures, including terrain, 65535 objects, camera orbit and inside geometry");
    }
    private static void write(SurfaceScene scene,Path path)throws Exception{
            ByteBuffer bytes=ByteBuffer.allocate(72+scene.bodies.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(scene.count).putInt(scene.preset).putInt(SurfaceScene.BODY_BYTES).putInt(1);
            for(float v:scene.eye)bytes.putFloat(v);
            for(float[] row:new float[][]{scene.right,scene.up,scene.forward})for(float v:row)bytes.putFloat(v);
            bytes.putFloat(0);bytes.put(scene.bodies.duplicate());
            Files.write(path,bytes.array());
    }
}
