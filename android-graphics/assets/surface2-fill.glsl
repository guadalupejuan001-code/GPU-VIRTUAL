layout(local_size_x=64) in;
layout(std430,binding=1) readonly buffer Builder {uint meta[];};
layout(std430,binding=2) buffer Resident {uint finalMeta[];};
void main(){
    uint id=gl_GlobalInvocationID.x;if(id>=frameInfo.z)return;
    uint at=boundsAt()+id*4u;uvec4 b=uvec4(meta[at],meta[at+1u],meta[at+2u],meta[at+3u]);
    if(b.x>b.z||b.y>b.w)return;
    for(uint y=b.y;y<=b.w;++y)for(uint x=b.x;x<=b.z;++x){
        uint cursor=atomicAdd(finalMeta[recordAt(y*tilesX()+x)+1u],1u);finalMeta[residentWords()+cursor]=id;
    }
}
