precision highp float;
precision highp int;
// GPU adaptation of X's EtherSurfaceBuffer / EtherRenderSpatial (Mundo X).
// C10 contract: opaque sphere, ellipsoid and triangle; float32 intersections,
// exact uint16 surface IDs and RGBA8. No promise of float64 CPU bit identity.
struct Body { vec4 centerRadius; vec4 material; vec4 shape; vec4 a; vec4 b; vec4 c; };
layout(std430,binding=0) readonly buffer SceneBodies { Body bodies[]; };
uniform uvec4 frameInfo; // width, height, number of bodies, mask words per tile
uniform vec4 eyeFov;
uniform vec3 cameraRight;
uniform vec3 cameraUp;
uniform vec3 cameraForward;
uniform vec3 lightDirection;
uniform int surfaceRoute; // 0: dense ID+RGBA, 1: compact tile pages
uniform int depthView;
const uint NO_SURFACE=65535u;
const uint PAGE_WORDS=96u; // 64 RGBA words + 32 pairs of uint16 IDs
uint tilesX(){ return (frameInfo.x+7u)/8u; }
uint tileCount(){ return tilesX()*((frameInfo.y+7u)/8u); }
vec3 rayFor(uvec2 p){
    vec2 ndc=(vec2(p)+vec2(0.5))/vec2(frameInfo.xy)*2.0-1.0;
    return normalize(cameraForward+cameraRight*(ndc.x*eyeFov.w*float(frameInfo.x)/float(frameInfo.y))+cameraUp*(ndc.y*eyeFov.w));
}
uint backgroundFor(uvec2 p){
    uint v=5u+(p.y*18u)/frameInfo.y;
    return (255u<<24u)|((v+9u)<<16u)|((v+3u)<<8u)|v;
}
float intersectBody(Body b,vec3 ray){
    if(b.a.w>1.5){
        vec3 ab=b.b.xyz-b.a.xyz,ac=b.c.xyz-b.a.xyz;
        vec3 p=cross(ray,ac);float det=dot(ab,p);
        if(abs(det)<=1e-7*length(ab)*length(ac))return uintBitsToFloat(0x7f800000u);
        float inv=1.0/det;vec3 t=eyeFov.xyz-b.a.xyz;
        float u=dot(t,p)*inv;if(u<0.0||u>1.0)return uintBitsToFloat(0x7f800000u);
        vec3 q=cross(t,ab);float v=dot(ray,q)*inv;
        if(v<0.0||u+v>1.0)return uintBitsToFloat(0x7f800000u);
        float d=dot(ac,q)*inv;return d>1e-5?d:uintBitsToFloat(0x7f800000u);
    }
    if(b.a.w>0.5){
        float c=cos(b.shape.w),s=sin(b.shape.w);vec3 p=eyeFov.xyz-b.centerRadius.xyz;
        vec3 o=vec3(c*p.x-s*p.z,p.y,s*p.x+c*p.z)/b.shape.xyz;
        vec3 d=vec3(c*ray.x-s*ray.z,ray.y,s*ray.x+c*ray.z)/b.shape.xyz;
        // Stable equivalent: avoid subtracting two large, nearly equal squares.
        vec3 perpendicular=cross(o,d);float a=dot(d,d),h=dot(o,d),disc=a-dot(perpendicular,perpendicular);
        if(disc<0.0)return uintBitsToFloat(0x7f800000u);
        float root=sqrt(disc),t=(-h-root)/a;if(t<=1e-5)t=(-h+root)/a;
        return t>1e-5?t:uintBitsToFloat(0x7f800000u);
    }
    vec3 offset=b.centerRadius.xyz-eyeFov.xyz;float along=dot(offset,ray);
    vec3 perpendicular=cross(offset,ray);
    float rayLength2=dot(ray,ray);
    float h2=rayLength2*b.centerRadius.w*b.centerRadius.w-dot(perpendicular,perpendicular);
    if(h2<0.0)return uintBitsToFloat(0x7f800000u);
    float root=sqrt(h2),t=(along-root)/rayLength2;if(t<=1e-5)t=(along+root)/rayLength2;
    return t>1e-5?t:uintBitsToFloat(0x7f800000u);
}
vec3 normalFor(Body b,vec3 ray,float depth){
    if(b.a.w>1.5){
        vec3 n=normalize(cross(b.b.xyz-b.a.xyz,b.c.xyz-b.a.xyz));
        return dot(n,eyeFov.xyz-b.a.xyz)<0.0?-n:n;
    }
    vec3 p=eyeFov.xyz+ray*depth-b.centerRadius.xyz;
    if(b.a.w<0.5)return normalize(p);
    float c=cos(b.shape.w),s=sin(b.shape.w);
    float x=(c*p.x-s*p.z)/(b.shape.x*b.shape.x),z=(s*p.x+c*p.z)/(b.shape.z*b.shape.z);
    return normalize(vec3(c*x+s*z,p.y/(b.shape.y*b.shape.y),-s*x+c*z));
}
uint shadeBody(uint id,vec3 ray,float depth){
    Body body=bodies[id]; // Plain local values preserve readonly buffer access.
    vec3 normal=normalFor(body,ray,depth);
    float l=0.12+0.88*max(0.0,dot(normal,lightDirection));
    return packUnorm4x8(vec4(clamp(body.material.rgb*(l*body.material.w),0.0,1.0),1.0));
}
