layout(local_size_x=128) in;
layout(std430,binding=1) buffer Builder {uint meta[];};
shared uint refs[128];shared uint occupied[128];
void main(){
    uint lane=gl_LocalInvocationID.x,tile=gl_GlobalInvocationID.x;
    uint count=tile<tileCount()?meta[recordAt(tile)+2u]:0u;
    refs[lane]=count;occupied[lane]=count==0u?0u:1u;memoryBarrierShared();barrier();
    for(uint distance=1u;distance<128u;distance*=2u){
        uint a=lane>=distance?refs[lane-distance]:0u,b=lane>=distance?occupied[lane-distance]:0u;
        barrier();refs[lane]+=a;occupied[lane]+=b;memoryBarrierShared();barrier();
    }
    if(tile<tileCount()){meta[recordAt(tile)]=occupied[lane]-(count==0u?0u:1u);meta[recordAt(tile)+1u]=refs[lane]-count;}
    if(lane==127u){uint at=groupsAt()+gl_WorkGroupID.x*4u;meta[at]=refs[lane];meta[at+1u]=occupied[lane];}
}
