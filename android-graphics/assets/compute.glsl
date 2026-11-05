layout(local_size_x=64) in;
layout(std430,binding=0) writeonly buffer Values { uvec4 values[]; };
layout(std430,binding=1) readonly buffer HashValues { uint hashes[]; };
uniform int cachedMode;
void main() {
    uint id=gl_GlobalInvocationID.x;
    if(id>=parameters.x)return;
    if(cachedMode==1) {
        // Load a plain value before the function call; preserve readonly on buffer storage.
        uint hashValue=hashes[id];
        values[id]=sceneValueFromHash(id,hashValue);
    } else {
        values[id]=sceneValue(id);
    }
}
