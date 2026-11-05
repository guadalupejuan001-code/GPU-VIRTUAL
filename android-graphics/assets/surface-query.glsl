layout(local_size_x=64) in;
layout(std430,binding=3) writeonly buffer SurfaceQueries { uint queryWords[]; };
uniform uint queryCount;
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=queryCount)return;
    uvec2 p=uvec2((i*7919u+17u)%frameInfo.x,(i*3467u+7u)%frameInfo.y);
    uvec2 stored=storedPixel(p);float referenceDepth;uvec2 reference=tracePixel(p,referenceDepth);
    float d=uintBitsToFloat(0x7f800000u);
    if(stored.x!=NO_SURFACE){Body body=bodies[stored.x];vec3 ray=rayFor(p);d=intersectBody(body,ray);}
    uint at=i*8u;queryWords[at]=p.x;queryWords[at+1u]=p.y;
    queryWords[at+2u]=stored.x;queryWords[at+3u]=stored.y;queryWords[at+4u]=floatBitsToUint(d);
    queryWords[at+5u]=reference.x;queryWords[at+6u]=reference.y;queryWords[at+7u]=floatBitsToUint(referenceDepth);
}
