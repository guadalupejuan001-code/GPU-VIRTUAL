layout(local_size_x=64) in;
layout(std430,binding=1) buffer Builder {uint meta[];};
void main(){uint i=gl_GlobalInvocationID.x;if(i<builderWords())meta[i]=0u;}
