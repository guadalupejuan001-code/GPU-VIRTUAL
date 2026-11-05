layout(local_size_x=64) in;
layout(std430,binding=1) buffer TileMap { uint meta[]; };
// Counter, one page index per tile, then conservative body bitmasks per tile.
bool projectsInto(Body b,uvec2 tile){
    vec3 v=b.centerRadius.xyz-eyeFov.xyz;float r=b.centerRadius.w;
    float z=dot(v,cameraForward);if(z+r<=1e-5)return false;
    if(z<=r+1e-5)return true;
    float x=dot(v,cameraRight),y=dot(v,cameraUp),zn=z-r,zf=z+r;
    vec4 xx=vec4((x-r)/zn,(x-r)/zf,(x+r)/zn,(x+r)/zf)/(eyeFov.w*float(frameInfo.x)/float(frameInfo.y));
    vec4 yy=vec4((y-r)/zn,(y-r)/zf,(y+r)/zn,(y+r)/zf)/eyeFov.w;
    vec2 low=(vec2(min(min(xx.x,xx.y),min(xx.z,xx.w)),min(min(yy.x,yy.y),min(yy.z,yy.w)))*0.5+0.5)*vec2(frameInfo.xy)-2.0;
    vec2 high=(vec2(max(max(xx.x,xx.y),max(xx.z,xx.w)),max(max(yy.x,yy.y),max(yy.z,yy.w)))*0.5+0.5)*vec2(frameInfo.xy)+2.0;
    vec2 first=vec2(tile*8u),last=first+vec2(7.0);
    return all(greaterThanEqual(last,low))&&all(lessThanEqual(first,high));
}
void main(){
    uint tile=gl_GlobalInvocationID.x;if(tile>=tileCount())return;
    uvec2 xy=uvec2(tile%tilesX(),tile/tilesX());bool occupied=false;
    uint base=1u+tileCount()+tile*frameInfo.w;
    for(uint w=0u;w<frameInfo.w;++w){
        uint bits=0u;
        for(uint b=0u;b<32u;++b){
            uint id=w*32u+b;if(id>=frameInfo.z)break;
            Body body=bodies[id];if(projectsInto(body,xy))bits|=1u<<b;
        }
        meta[base+w]=bits;occupied=occupied||bits!=0u;
    }
    meta[1u+tile]=occupied?atomicAdd(meta[0],1u)+1u:0u;
}
