layout(location=0) in uvec4 denseValue;
uniform int compactMode;
uniform vec4 camera;
uniform vec2 viewportSize;
out vec2 pointLocal;
out vec3 pointColor;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(-1,1),vec2(-1,1),vec2(1,-1),vec2(1,1));
void main() {
    uint id=uint(gl_InstanceID);
    uvec4 raw=compactMode==1?sceneValue(id):denseValue;
    vec3 p=(vec3(raw.xyz)-vec3(512.0))/512.0;
    float rx=camera.x*p.x+camera.y*p.z;
    float rz=-camera.y*p.x+camera.x*p.z;
    float ry=camera.z*p.y-camera.w*rz;
    float depth=3.4+camera.w*p.y+camera.z*rz;
    vec2 center=vec2(rx*viewportSize.y/viewportSize.x,ry)*2.2/depth;
    pointLocal=corners[gl_VertexID];
    float shimmer=float(raw.w>>24u)/255.0;
    float radius=(1.5+shimmer*1.1)*2.8/depth;
    vec2 offset=pointLocal*radius*2.0/viewportSize;
    gl_Position=vec4(center+offset,0.0,1.0);
    vec3 cool=vec3(0.16,0.58,1.0),warm=vec3(1.0,0.22,0.58);
    float tint=float(raw.w&255u)/255.0;
    float density=inversesqrt(max(1.0,float(parameters.x)/4096.0));
    pointColor=mix(cool,warm,tint)*density*(0.8+shimmer*0.4);
}
