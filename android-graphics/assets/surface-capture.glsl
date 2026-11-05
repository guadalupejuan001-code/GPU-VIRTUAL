layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=2) writeonly buffer SurfacePixels { uint pixels[]; };
shared uint ids[64];
void main(){
    uvec2 tile=gl_WorkGroupID.xy;uint tileId=tile.y*tilesX()+tile.x,page=meta[1u+tileId];
    if(surfaceRoute==1&&page==0u)return; // Uniform across this workgroup.
    uvec2 p=tile*8u+gl_LocalInvocationID.xy;uint lane=gl_LocalInvocationID.y*8u+gl_LocalInvocationID.x;
    uvec2 value=uvec2(NO_SURFACE,0u);
    if(all(lessThan(p,frameInfo.xy))){float d;value=tracePixel(p,d);}
    ids[lane]=value.x;
    uint slot=surfaceRoute==0?tileId:page-1u,base=slot*PAGE_WORDS;
    pixels[base+lane]=(surfaceRoute==1&&value.x==NO_SURFACE)?0u:value.y;
    barrier();
    if((lane&1u)==0u)pixels[base+64u+lane/2u]=ids[lane]|(ids[lane+1u]<<16u);
}
