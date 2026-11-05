uvec2 tracePixel(uvec2 p,out float depth){
    uint tile=(p.y/8u)*tilesX()+p.x/8u,at=recordAt(tile),count=meta[at+2u],id=NO_SURFACE;
    depth=uintBitsToFloat(0x7f800000u);
    // Shared fast path: the dense reference also avoids empty candidate scans.
    if(count==0u)return uvec2(NO_SURFACE,backgroundFor(p));
    uint first=meta[2]+meta[at+1u];vec3 ray=rayFor(p);
    for(uint i=0u;i<count;++i){
        uint candidate=meta[first+i];Body body=bodies[candidate];float d=intersectBody(body,ray);
        if(d<depth||(d==depth&&id!=NO_SURFACE&&candidate<id)){depth=d;id=candidate;}
    }
    return uvec2(id,id==NO_SURFACE?backgroundFor(p):shadeBody(id,ray,depth));
}
