#!/usr/bin/env python3
"""C10 production shader verification. Host software execution is not Mali FPS."""
import ctypes as C
import hashlib,json,math,struct,subprocess,time
from pathlib import Path
from verify_surface import Driver,U,I,P,F,S,scalar,sha,png,dot,add,sub,mul,norm,cross
from surface2_sources import ROOT,sources

def tangent_uncertainty(body,eye,right,up,forward,w,h,x,y):
    # Forward-error witness for a float32 ray/silhouette boundary. This is
    # separate from ordinary exact ID checks, and never treated as exact float64.
    ray=norm(add(forward,add(mul(right,((x+.5)/w*2-1)*eye[3]*w/h),mul(up,((y+.5)/h*2-1)*eye[3]))))
    if body[15]>1.5:return False
    origin=sub(eye[:3],body[:3])
    if body[15]>.5:
        c,t=math.cos(body[11]),math.sin(body[11]);a=body[8:11]
        o=((c*origin[0]-t*origin[2])/a[0],origin[1]/a[1],(t*origin[0]+c*origin[2])/a[2])
        d=((c*ray[0]-t*ray[2])/a[0],ray[1]/a[1],(t*ray[0]+c*ray[2])/a[2])
        closest=sub(o,mul(d,dot(o,d)/dot(d,d)));gap=abs(math.sqrt(dot(closest,closest))-1)
        return gap<=8*2**-23*max(1,math.sqrt(dot(o,o)))
    perpendicular=cross(origin,ray);gap=abs(math.sqrt(dot(perpendicular,perpendicular))-body[3])
    return gap<=8*2**-23*max(1,math.sqrt(dot(origin,origin)),body[3])

class ListDriver(Driver):
    def __init__(self):
        super().__init__();self.legacy=dict(self.programs);self.programs={}
        self.cache_valid=False;self.reuse_hits=0;self.full_captures=0
        def compile(stage,source):
            s=self.f('glCreateShader',U,[U])(stage);p=C.c_char_p(source.encode());self.f('glShaderSource',None,[U,I,P,P])(s,1,C.byref(p),None);self.f('glCompileShader',None,[U])(s)
            ok=I();self.f('glGetShaderiv',None,[U,U,P])(s,0x8b81,C.byref(ok))
            if not ok.value:
                log=C.create_string_buffer(8192);self.f('glGetShaderInfoLog',None,[U,I,P,P])(s,len(log),None,log);raise RuntimeError(log.value.decode())
            return s
        def link(*shaders):
            p=self.f('glCreateProgram',U,[])()
            for s in shaders:self.f('glAttachShader',None,[U,U])(p,s)
            self.f('glLinkProgram',None,[U])(p);ok=I();self.f('glGetProgramiv',None,[U,U,P])(p,0x8b82,C.byref(ok));assert ok.value
            for s in shaders:self.f('glDeleteShader',None,[U])(s)
            return p
        code=sources()
        for key in ['clear','count','scan','groups','finalize','fill','restore','capture','query']:self.programs[key]=link(compile(0x91b9,code['surface2-'+key+'.comp']))
        self.programs['present']=link(compile(0x8b31,code['surface2.vert']),compile(0x8b30,code['surface2.frag']))
        n=U();self.f('glGenBuffers',None,[I,P])(1,C.byref(n));self.builder=n.value
    def map_step(self,name,count):
        Driver.uniforms(self,name)
        self.f('glBindBufferBase',None,[U,U,U])(0x90d2,1,self.builder);self.f('glBindBufferBase',None,[U,U,U])(0x90d2,2,self.ids[1])
        if count:self.f('glDispatchCompute',None,[U,U,U])(count,1,1)
        self.f('glMemoryBarrier',None,[U])(0x2000)
    def layout(self,fixture,w,h):
        raw=fixture.read_bytes();self.n,preset,stride,fmt=struct.unpack_from('<4I',raw);assert stride==96 and fmt==1
        self.eye=struct.unpack_from('<4f',raw,16);self.right=struct.unpack_from('<3f',raw,32);self.up=struct.unpack_from('<3f',raw,44);self.forward=struct.unpack_from('<3f',raw,56)
        self.body=raw[72:];self.bodies=[struct.unpack_from('<24f',self.body,i*96) for i in range(self.n)]
        self.w,self.h=w,h;self.tiles=((w+7)//8)*((h+7)//8);self.words=max(1,(self.n+31)//32);groups=(self.tiles+127)//128
        builder_words=4+4*self.tiles+4*self.n+4*groups;self.builder_bytes=builder_words*4
        self.upload(0,self.body)
        self.f('glBindBuffer',None,[U,U])(0x90d2,self.builder);self.f('glBufferData',None,[U,S,P,U])(0x90d2,self.builder_bytes,None,0x88e8)
        for name,amount in [('clear',(builder_words+63)//64),('count',(self.n+63)//64),('scan',groups),('groups',1)]:self.map_step(name,amount)
        self.complete();self.f('glMemoryBarrier',None,[U])(0x200);self.f('glBindBuffer',None,[U,U])(0x90d2,self.builder);header=C.create_string_buffer(16);self.f('glGetBufferSubData',None,[U,S,S,P])(0x90d2,0,16,header)
        pages,refs,base,check=struct.unpack('<4I',header.raw);assert refs==check and base==4+4*self.tiles and pages<=self.tiles
        self.pages=pages;self.references=refs;self.metadata_size=4*(base+refs);self.allocate(1,self.metadata_size)
        for name,amount in [('finalize',(self.tiles+63)//64),('fill',(self.n+63)//64),('restore',(self.tiles+63)//64)]:self.map_step(name,amount)
        self.complete();self.metadata=struct.unpack('<'+'I'*(self.metadata_size//4),self.read(1,self.metadata_size));self.cache_valid=False
        total=0;assigned=[]
        for tile in range(self.tiles):
            page,first,count=self.metadata[4+3*tile:7+3*tile];assert first==total;total+=count
            ids=self.metadata[base+first:base+first+count];assert len(set(ids))==count and all(v<self.n for v in ids)
            if page:assigned.append(page);assert self.metadata[4+3*self.tiles+page-1]==tile
            else:assert count==0
        assert total==refs and sorted(assigned)==list(range(1,pages+1))
        self.f('glBindTexture',None,[U,U])(0xde1,self.texture)
        for key in [0x2801,0x2800]:self.f('glTexParameteri',None,[U,U,I])(0xde1,key,0x2600)
        self.f('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0xde1,0,0x8058,w,h,0,0x1908,0x1401,None)
        self.f('glBindFramebuffer',None,[U,U])(0x8d40,self.target);self.f('glFramebufferTexture2D',None,[U,U,U,U,I])(0x8d40,0x8ce0,0xde1,self.texture,0)
        assert self.f('glCheckFramebufferStatus',U,[U])(0x8d40)==0x8cd5;self.gl.check_error()
        return preset
    def reserve(self,route):
        self.complete();self.allocate(2,384*(self.tiles if route==0 else self.pages));self.cache_valid=False
    def render(self,route,tick=41,depth=False):
        self.f('glMemoryBarrier',None,[U])(0x2000);at=self.uniforms('capture',route,tick,depth)
        reuse=route==2 and self.cache_valid;self.f('glUniform1i',None,[I,I])(at('reuseVisibility'),int(reuse))
        if route==0:self.f('glDispatchCompute',None,[U,U,U])((self.w+7)//8,(self.h+7)//8,1)
        elif self.pages:
            gx=min(self.pages,16384);self.f('glDispatchCompute',None,[U,U,U])(gx,(self.pages+gx-1)//gx,1)
        if reuse:self.reuse_hits+=1
        else:self.full_captures+=1
        if route==2:self.cache_valid=True
        self.f('glMemoryBarrier',None,[U])(0x2000);self.f('glBindFramebuffer',None,[U,U])(0x8d40,self.target);self.f('glViewport',None,[I,I,I,I])(0,0,self.w,self.h)
        for cap in [0xbe2,0xb71,0xb44,0xbd0,0xc11]:self.f('glDisable',None,[U])(cap)
        self.uniforms('present',route,tick,depth);self.f('glBindVertexArray',None,[U])(self.vao);self.f('glDrawArrays',None,[U,I,I])(4,0,3);self.complete();self.gl.check_error()
        out=C.create_string_buffer(self.w*self.h*4);self.f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,self.w,self.h,0x1908,0x1401,out);return out.raw
    def canonical(self,route):
        data=memoryview(self.read(2,max(4,384*(self.tiles if route==0 else self.pages)))).cast('I');out=[]
        for y in range(self.h):
            for x in range(self.w):
                tile=y//8*((self.w+7)//8)+x//8;page=self.metadata[4+3*tile];lane=y%8*8+x%8
                if route!=0 and page==0:ident=65535;rgba=0
                else:
                    base=(tile if route==0 else page-1)*96;ident=(data[base+64+lane//2]>>((lane&1)*16))&65535;rgba=data[base+lane]
                if ident==65535:v=5+y*18//self.h;rgba=0xff000000|((v+9)<<16)|((v+3)<<8)|v
                assert ident==65535 or ident<self.n;out.append((ident,rgba))
        return out

def run():
    out=ROOT/'build/surface2';out.mkdir(parents=True,exist_ok=True)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(out),str(ROOT/'src/x/gpu/visual/SurfaceScene.java'),str(ROOT/'host/SurfaceFixture.java')],check=True)
    subprocess.run(['java','-cp',str(out),'SurfaceFixture',str(out)],check=True)
    gl=ListDriver();cases=[];checked=0;scalar_count=0;legacy_cases=0;max_channel=0;max_depth=0;boundary_changes=0
    profiles=[(n,p,128,72,'default') for p in range(5) for n in [8,64,4096]]
    profiles += [(4096,0,1280,720,'default'),(64,2,1280,720,'default'),(64,4,640,360,'default'),(64,0,127,73,'default'),(64,0,128,72,'orbit'),(64,2,128,72,'inside'),(16384,0,320,180,'default'),(65535,0,320,180,'default')]
    for n,p,w,h,pose in profiles:
        fixture=out/(f'surface-{n}-{p}.bin' if pose=='default' else f'surface-{pose}.bin')
        reference_image=None;reference_state=None
        if n<=4096 and pose=='default':
            current=gl.programs;gl.programs=gl.legacy;Driver.layout(gl,fixture,w,h);Driver.reserve(gl,0)
            reference_image=Driver.render(gl,0);reference_state=Driver.canonical(gl,0);gl.programs=current
        gl.layout(fixture,w,h);images=[];states=[];queries=[];depths=[]
        for route in range(3):
            gl.reserve(route);images.append(gl.render(route));states.append(gl.canonical(route));queries.append(gl.query(route));depths.append(gl.render(route,depth=True))
            gl.render(route,83);gl.render(route,41);assert gl.canonical(route)==states[route],('cache/pool reuse',n,p,route)
        assert images[0]==images[1]==images[2],('color routes',n,p,w,h)
        assert states[0]==states[1]==states[2],('ID/RGBA routes',n,p,w,h)
        assert depths[0]==depths[1]==depths[2],('depth routes',n,p,w,h)
        assert queries[0]==queries[1]==queries[2],('queries',n,p,w,h)
        if reference_image is not None:
            # Stable intersections improve C09's cancellation-prone float32 math.
            # Every changed nearest ID must agree with the independent oracle.
            changed_ids=0;changed_colors=0
            for index,(old,new) in enumerate(zip(reference_state,states[0])):
                if old[0]!=new[0]:
                    expected,_,_=scalar(gl.bodies,gl.eye,gl.right,gl.up,gl.forward,w,h,index%w,index//w,gl.light)
                    if new[0]!=expected:
                        candidates=[v for v in (new[0],expected) if v!=65535]
                        assert any(tangent_uncertainty(gl.bodies[v],gl.eye,gl.right,gl.up,gl.forward,w,h,index%w,index//w) for v in candidates),('precision regression ID outside float32 silhouette uncertainty',n,p,index,old[0],new[0],expected)
                        boundary_changes+=1
                    changed_ids+=1
                changed_colors+=old[1]!=new[1]
            legacy_cases+=1
        limit=128 if n<=64 else 32 if n<=4096 else 8
        for q in queries[0][:limit]:
            x,y,ident,rgba,depth,*_=q;expected,color,d=scalar(gl.bodies,gl.eye,gl.right,gl.up,gl.forward,w,h,x,y,gl.light)
            assert ident==expected,('scalar ID',n,p,x,y,ident,expected)
            channel=max(abs(((rgba>>s)&255)-((color>>s)&255)) for s in (0,8,16,24));max_channel=max(max_channel,channel);assert channel<=2,('scalar color',n,p,x,y,channel)
            if expected!=65535:
                actual=struct.unpack('<f',struct.pack('<I',depth))[0];err=abs(actual-d)/max(1,abs(d));max_depth=max(max_depth,err);assert err<3e-4,('scalar depth',n,p,err)
            scalar_count+=1
        visible=sum(i!=65535 for i,c in states[0]);checked+=w*h
        if (n,p,w,h)==(64,4,640,360):png(ROOT/'build/surface2-preview.png',images[2],w,h)
        row={'objects':gl.n,'preset':p,'camera':pose,'width':w,'height':h,'active_pages':gl.pages,'references':gl.references,'resident_map_bytes':gl.metadata_size,'builder_bytes':gl.builder_bytes,
             'c09_mask_map_bytes':4*(1+gl.tiles+gl.tiles*gl.words),'compact_pool_bytes':max(4,384*gl.pages),'dense_pool_bytes':384*gl.tiles,'visible_pixels':visible,
             'three_routes_exact':True,'c09_compared':reference_image is not None,'c09_changed_ids':changed_ids if reference_image is not None else None,'c09_changed_colors':changed_colors if reference_image is not None else None,'color_sha256':sha(images[0])}
        cases.append(row);print(json.dumps(row),flush=True)
    # Rebuild the map while retaining an existing pixel pool: stale IDs must
    # never survive a camera change. The allocation itself is not the invalidator.
    motion_checks=0
    for pose in ['default','orbit','default','orbit']:
        fixture=out/('surface-64-0.bin' if pose=='default' else 'surface-orbit.bin')
        gl.layout(fixture,128,72)
        if motion_checks==0:gl.allocate(2,384*gl.tiles)
        cached=gl.render(2,83);cached_query=gl.query(2,83)
        dense=gl.render(0,83);dense_query=gl.query(0,83)
        assert cached==dense and cached_query==dense_query,('camera invalidation with retained pool',pose)
        motion_checks+=1
    report={'format':'GPU-VIRTUAL-C10-host-validation-v1','backend':gl.gl.identity,'hardware_gpu_executed':False,'shader_count':len(sources()),'shader_sha256':{n:sha(v.encode()) for n,v in sources().items()},
            'cases':cases,'image_pairs':len(cases)*4,'canonical_pixels':checked,'legacy_compared_cases':legacy_cases,'legacy_changed_id_float32_boundary_cases':boundary_changes,'scalar_queries':scalar_count,'max_scalar_color_channel_error':max_channel,
            'max_scalar_depth_relative_error':max_depth,'cached_frames_executed':gl.reuse_hits,'full_visibility_captures':gl.full_captures,'completed_fences':gl.fences,'camera_rebuild_retained_pool_checks':motion_checks,
            'scope':'C10 shader programs on llvmpipe CPU, no physical GPU FPS. Three exact GPU routes, C09 precision changes counted; changed nearest IDs checked against the scalar oracle with separately counted float32 tangent-boundary uncertainty, exact CSR allocation and candidate IDs, cache reuse and invalidation between camera/scene changes. Float64 independent scalar tolerance as C09.'}
    (ROOT/'build/surface2-host-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='cases'}));gl.gl.close()
if __name__=='__main__':run()
