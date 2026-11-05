layout(local_size_x=64) in;
layout(std430,binding=1) buffer Builder {uint meta[];};
bool projectBounds(Body body,out uvec4 bounds){
    vec3 v=body.centerRadius.xyz-eyeFov.xyz;float r=body.centerRadius.w;
    float z=dot(v,cameraForward);if(z+r<=1e-5)return false;
    uvec2 last=uvec2(tilesX()-1u,(frameInfo.y+7u)/8u-1u);
    if(z<=r+1e-5){bounds=uvec4(0u,0u,last);return true;}
    float x=dot(v,cameraRight),y=dot(v,cameraUp),zn=z-r,zf=z+r;
    vec4 xx=vec4((x-r)/zn,(x-r)/zf,(x+r)/zn,(x+r)/zf)/(eyeFov.w*float(frameInfo.x)/float(frameInfo.y));
    vec4 yy=vec4((y-r)/zn,(y-r)/zf,(y+r)/zn,(y+r)/zf)/eyeFov.w;
    vec2 low=(vec2(min(min(xx.x,xx.y),min(xx.z,xx.w)),min(min(yy.x,yy.y),min(yy.z,yy.w)))*0.5+0.5)*vec2(frameInfo.xy)-2.0;
    vec2 high=(vec2(max(max(xx.x,xx.y),max(xx.z,xx.w)),max(max(yy.x,yy.y),max(yy.z,yy.w)))*0.5+0.5)*vec2(frameInfo.xy)+2.0;
    if(any(lessThan(high,vec2(0.0)))||any(greaterThan(low,vec2(last*8u+7u))))return false;
    // Equivalent to C09's tileLast>=low && tileFirst<=high, not floor(low/8).
    ivec2 first=ivec2(clamp(ceil((low-7.0)/8.0),vec2(0.0),vec2(last)));
    ivec2 until=ivec2(clamp(floor(high/8.0),vec2(0.0),vec2(last)));
    if(any(greaterThan(first,until)))return false;
    bounds=uvec4(uvec2(first),uvec2(until));return true;
}
void main(){
    uint id=gl_GlobalInvocationID.x;if(id>=frameInfo.z)return;
    Body body=bodies[id];uvec4 b;uint at=boundsAt()+id*4u;
    if(!projectBounds(body,b)){meta[at]=1u;meta[at+1u]=1u;meta[at+2u]=0u;meta[at+3u]=0u;return;}
    meta[at]=b.x;meta[at+1u]=b.y;meta[at+2u]=b.z;meta[at+3u]=b.w;
    uint refs=(b.z-b.x+1u)*(b.w-b.y+1u);atomicAdd(meta[1],refs);
    for(uint y=b.y;y<=b.w;++y)for(uint x=b.x;x<=b.z;++x)atomicAdd(meta[recordAt(y*tilesX()+x)+2u],1u);
}
