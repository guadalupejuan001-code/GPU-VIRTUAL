layout(local_size_x=64) in;
layout(std430,binding=2) buffer Resident {uint finalMeta[];};
void main(){uint tile=gl_GlobalInvocationID.x;if(tile<tileCount()){uint at=recordAt(tile);finalMeta[at+1u]-=finalMeta[at+2u];}}
