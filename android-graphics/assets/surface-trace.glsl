// Requires a readonly TileMap declaration in its caller.
uvec2 tracePixel(uvec2 p,out float depth){
    uint tile=(p.y/8u)*tilesX()+p.x/8u,id=NO_SURFACE;
    depth=uintBitsToFloat(0x7f800000u);vec3 ray=rayFor(p);
    uint base=1u+tileCount()+tile*frameInfo.w;
    for(uint w=0u;w<frameInfo.w;++w){
        uint bits=meta[base+w];
        while(bits!=0u){
            uint bit=uint(findLSB(bits)),candidate=w*32u+bit;bits&=bits-1u;
            Body body=bodies[candidate];float d=intersectBody(body,ray);
            if(d<depth){depth=d;id=candidate;}
        }
    }
    uint rgba=id==NO_SURFACE?backgroundFor(p):shadeBody(id,ray,depth);
    return uvec2(id,rgba);
}
