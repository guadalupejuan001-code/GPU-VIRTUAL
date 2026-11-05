layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=2) buffer SurfacePixels {uint pixels[];};
uniform int reuseVisibility;
shared uint ids[64];
void main(){
    uint tileId,page;
    if(surfaceRoute==0){tileId=gl_WorkGroupID.y*tilesX()+gl_WorkGroupID.x;page=meta[recordAt(tileId)];}
    else{
        page=gl_WorkGroupID.y*gl_NumWorkGroups.x+gl_WorkGroupID.x+1u;if(page>meta[0])return;
        tileId=meta[reverseAt()+page-1u];
    }
    uvec2 tile=uvec2(tileId%tilesX(),tileId/tilesX()),p=tile*8u+gl_LocalInvocationID.xy;
    uint lane=gl_LocalInvocationID.y*8u+gl_LocalInvocationID.x,base=(surfaceRoute==0?tileId:page-1u)*PAGE_WORDS;
    uvec2 value=uvec2(NO_SURFACE,0u);
    if(all(lessThan(p,frameInfo.xy))){
        if(reuseVisibility!=0){
            uint packedIds=pixels[base+64u+lane/2u];uint id=(packedIds>>((lane&1u)*16u))&65535u;
            value.x=id;
            if(id!=NO_SURFACE){Body body=bodies[id];vec3 ray=rayFor(p);float d=intersectBody(body,ray);value.y=shadeBody(id,ray,d);}
        }else{float d;value=tracePixel(p,d);}
    }
    pixels[base+lane]=(surfaceRoute!=0&&value.x==NO_SURFACE)?0u:value.y;
    // On a cache hit IDs are read-only; their validity tracks geometry/camera.
    if(reuseVisibility==0){ids[lane]=value.x;memoryBarrierShared();barrier();if((lane&1u)==0u)pixels[base+64u+lane/2u]=ids[lane]|(ids[lane+1u]<<16u);}
}
