package x.gpu.visual;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tasas a partir de cuadros confirmados y tiempo real, sin usar FPS de pantalla. */
public final class SustainedStats {
    public static final class Block {
        public final int route;
        public final long frames,nanos;
        Block(int route,long frames,long nanos){this.route=route;this.frames=frames;this.nanos=nanos;}
        public double fps(){return frames*(1e9/nanos);}
    }
    private final long[] frames,nanos;
    private final List<Block> blocks=new ArrayList<>();
    public SustainedStats(){this(2);}
    public SustainedStats(int routes){if(routes<1)throw new IllegalArgumentException("Sin metodos");frames=new long[routes];nanos=new long[routes];}
    private void route(int route){if(route<0||route>=frames.length)throw new IllegalArgumentException("Metodo desconocido");}
    public void add(int route,long frames,long nanos) {
        route(route);
        if(frames<1||nanos<1)throw new IllegalArgumentException("Bloque sin cuadros terminados o tiempo");
        long newFrames=Math.addExact(this.frames[route],frames),newNanos=Math.addExact(this.nanos[route],nanos);
        this.frames[route]=newFrames;this.nanos[route]=newNanos;blocks.add(new Block(route,frames,nanos));
    }
    public long frames(int route){route(route);return frames[route];}
    public long nanos(int route){route(route);return nanos[route];}
    public double fps(int route){route(route);return nanos[route]==0?0:frames[route]*(1e9/nanos[route]);}
    public double bestBlockFps(int route){route(route);double best=0;for(Block b:blocks)if(b.route==route)best=Math.max(best,b.fps());return best;}
    public double lastBlocksFps(int route,int count) {
        route(route);if(count<1)throw new IllegalArgumentException("Cantidad invalida");
        long frames=0,nanos=0;int found=0;
        for(int i=blocks.size()-1;i>=0&&found<count;i--){Block b=blocks.get(i);if(b.route==route){frames+=b.frames;nanos+=b.nanos;found++;}}
        return nanos==0?0:frames*(1e9/nanos);
    }
    public List<Block> blocks(){return Collections.unmodifiableList(blocks);}
}

