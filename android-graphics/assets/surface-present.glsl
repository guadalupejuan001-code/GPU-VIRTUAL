layout(location=0) out vec4 color;
void main(){
    uvec2 p=uvec2(gl_FragCoord.xy);uvec2 value=storedPixel(p);
    if(depthView!=0&&value.x!=NO_SURFACE){
        Body body=bodies[value.x];vec3 ray=rayFor(p);float d=intersectBody(body,ray);
        float v=1.0/(1.0+0.15*d);color=vec4(v,v*v,1.0-v,1.0);
    }else color=unpackUnorm4x8(value.y);
}
