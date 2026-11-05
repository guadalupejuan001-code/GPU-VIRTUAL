layout(std430,binding=2) readonly buffer SurfacePixels { uint pixels[]; };
uvec2 storedPixel(uvec2 p){
    uint tile=(p.y/8u)*tilesX()+p.x/8u,page=meta[1u+tile];
    if(surfaceRoute==1&&page==0u)return uvec2(NO_SURFACE,backgroundFor(p));
    uint base=(surfaceRoute==0?tile:page-1u)*PAGE_WORDS,lane=(p.y%8u)*8u+p.x%8u;
    uint ids=pixels[base+64u+lane/2u],id=(ids>>((lane&1u)*16u))&65535u;
    return uvec2(id,id==NO_SURFACE?backgroundFor(p):pixels[base+lane]);
}
