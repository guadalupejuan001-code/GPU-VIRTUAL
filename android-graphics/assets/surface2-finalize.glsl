layout(local_size_x=64) in;
layout(std430,binding=1) readonly buffer Builder {uint meta[];};
layout(std430,binding=2) writeonly buffer Resident {uint finalMeta[];};
void main(){
    uint tile=gl_GlobalInvocationID.x;if(tile>=tileCount())return;
    uint at=recordAt(tile),group=groupsAt()+(tile/128u)*4u,count=meta[at+2u];
    uint page=count==0u?0u:meta[at]+meta[group+3u]+1u;
    finalMeta[at]=page;finalMeta[at+1u]=meta[at+1u]+meta[group+2u];finalMeta[at+2u]=count;
    if(page!=0u)finalMeta[reverseAt()+page-1u]=tile;
    if(tile==0u)for(uint i=0u;i<4u;++i)finalMeta[i]=meta[i];
}
