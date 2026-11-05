layout(local_size_x=64) in;
layout(std430,binding=0) writeonly buffer Values { uvec4 values[]; };
layout(std430,binding=1) readonly buffer HashValues { uint hashes[]; };
uniform int cachedMode;
void main(){uint id=gl_GlobalInvocationID.x;if(id<parameters.x)values[id]=cachedMode==1?sceneValueFromHash(id,hashes[id]):sceneValue(id);}
