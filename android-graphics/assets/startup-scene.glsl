precision highp float;
precision highp int;
uniform uvec4 parameters;
uniform uvec4 details[4];
uint mixWord(uint x) {
    x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;x^=x>>16u;return x;
}
uint triangle(uint x){uint p=x&511u;return p<256u?p:511u-p;}
uvec4 sceneValue(uint id) {
    uint t=parameters.y,h=mixWord(id^parameters.z);
    uint x=((id&63u)*16u+(triangle(t+(h&255u))>>2u))&1023u;
    uint y=(((id>>6u)&63u)*16u+(triangle(t*3u+((h>>8u)&255u))>>2u))&1023u;
    uint z=(((id>>12u)&31u)*32u+triangle(t+((h>>16u)&255u))*4u)&1023u;
    uint color=(h&0x00ffffffu)|((t+id)<<24u);
    for(int k=0;k<4;k++)if(uint(k)<parameters.w&&details[k].x==id) {
        x=(x+details[k].y)&1023u;y=(y+details[k].z)&1023u;color^=details[k].w;
    }
    return uvec4(x,y,z,color);
}
