#!/usr/bin/env python3
"""Actual C09 production shaders on CPU software GL, with a scalar oracle.

This is correctness evidence, never physical Mali execution or FPS evidence.
"""
import ctypes as C
import hashlib,json,math,struct,subprocess,sys,time,zlib
from pathlib import Path
from surface_sources import ROOT,sources
sys.path.insert(0,str(ROOT.parent/'experiments/c02'))
from gl_capture import Capture
U,I,P,F,S=C.c_uint,C.c_int,C.c_void_p,C.c_float,C.c_ssize_t
sha=lambda data:hashlib.sha256(data).hexdigest()

def dot(a,b):return sum(x*y for x,y in zip(a,b))
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def add(a,b):return tuple(x+y for x,y in zip(a,b))
def mul(a,b):return tuple(x*b for x in a)
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def norm(a):return mul(a,1/math.sqrt(dot(a,a)))

def scalar(bodies,eye,right,up,forward,w,h,x,y,light):
    ray=norm(add(forward,add(mul(right,((x+.5)/w*2-1)*eye[3]*w/h),mul(up,((y+.5)/h*2-1)*eye[3]))))
    best=math.inf;selected=65535;normal=None;color=None
    for index,b in enumerate(bodies):
        center=b[:3];kind=b[15];p=sub(eye[:3],center)
        if kind>1.5:
            ab=sub(b[16:19],b[12:15]);ac=sub(b[20:23],b[12:15]);q=cross(ray,ac);det=dot(ab,q)
            if abs(det)<=1e-7*math.sqrt(dot(ab,ab)*dot(ac,ac)):continue
            t=sub(eye[:3],b[12:15]);u=dot(t,q)/det
            if not 0<=u<=1:continue
            r=cross(t,ab);v=dot(ray,r)/det
            if v<0 or u+v>1:continue
            depth=dot(ac,r)/det
        elif kind>.5:
            c,s=math.cos(b[11]),math.sin(b[11]);axes=b[8:11]
            o=((c*p[0]-s*p[2])/axes[0],p[1]/axes[1],(s*p[0]+c*p[2])/axes[2])
            d=((c*ray[0]-s*ray[2])/axes[0],ray[1]/axes[1],(s*ray[0]+c*ray[2])/axes[2])
            a=dot(d,d);q=dot(o,d);disc=q*q-a*(dot(o,o)-1)
            if disc<0:continue
            root=math.sqrt(disc);depth=(-q-root)/a
            if depth<=1e-5:depth=(-q+root)/a
        else:
            offset=sub(center,eye[:3]);along=dot(offset,ray);disc=b[3]*b[3]-(dot(offset,offset)-along*along)
            if disc<0:continue
            root=math.sqrt(disc);depth=along-root
            if depth<=1e-5:depth=along+root
        if not 1e-5<depth<best:continue
        best=depth;selected=index;color=b[4:7];gain=b[7]
        if kind>1.5:
            normal=norm(cross(sub(b[16:19],b[12:15]),sub(b[20:23],b[12:15])))
            if dot(normal,sub(eye[:3],b[12:15]))<0:normal=mul(normal,-1)
        else:
            point=sub(add(eye[:3],mul(ray,depth)),center)
            if kind<.5:normal=norm(point)
            else:
                c,s=math.cos(b[11]),math.sin(b[11]);a=b[8:11]
                xx=(c*point[0]-s*point[2])/(a[0]*a[0]);zz=(s*point[0]+c*point[2])/(a[2]*a[2])
                normal=norm((c*xx+s*zz,point[1]/(a[1]*a[1]),-s*xx+c*zz))
    if selected==65535:
        v=5+y*18//h;return selected,0xff000000|((v+9)<<16)|((v+3)<<8)|v,best
    brightness=.12+.88*max(0,dot(normal,light));rgb=[int(min(1,max(0,v*brightness*gain))*255+.5) for v in color]
    return selected,0xff000000|(rgb[2]<<16)|(rgb[1]<<8)|rgb[0],best

class Driver:
    def __init__(self):
        self.gl=Capture(software=True);self.f=self.gl.gl;self.programs={};self.current=None
        def compile(stage,text):
            s=self.f('glCreateShader',U,[U])(stage);data=C.c_char_p(text.encode());self.f('glShaderSource',None,[U,I,P,P])(s,1,C.byref(data),None);self.f('glCompileShader',None,[U])(s)
            ok=I();self.f('glGetShaderiv',None,[U,U,P])(s,0x8b81,C.byref(ok))
            if not ok.value:
                log=C.create_string_buffer(8192);self.f('glGetShaderInfoLog',None,[U,I,P,P])(s,len(log),None,log);raise RuntimeError(log.value.decode())
            return s
        def link(*shaders):
            p=self.f('glCreateProgram',U,[])()
            for s in shaders:self.f('glAttachShader',None,[U,U])(p,s)
            self.f('glLinkProgram',None,[U])(p);ok=I();self.f('glGetProgramiv',None,[U,U,P])(p,0x8b82,C.byref(ok))
            assert ok.value,'Program link failed'
            for s in shaders:self.f('glDeleteShader',None,[U])(s)
            return p
        code=sources()
        for name in ['map','capture','query']:self.programs[name]=link(compile(0x91b9,code['surface-'+name+'.comp']))
        self.programs['present']=link(compile(0x8b31,code['surface.vert']),compile(0x8b30,code['surface.frag']))
        self.ids=(U*4)();self.f('glGenBuffers',None,[I,P])(4,self.ids)
        for b in self.ids:self.f('glBindBuffer',None,[U,U])(0x90d2,b);self.f('glBufferData',None,[U,S,P,U])(0x90d2,4,None,0x88e8)
        n=U();self.f('glGenVertexArrays',None,[I,P])(1,C.byref(n));self.vao=n.value
        self.f('glGenTextures',None,[I,P])(1,C.byref(n));self.texture=n.value
        self.f('glGenFramebuffers',None,[I,P])(1,C.byref(n));self.target=n.value
        self.fences=0
    def upload(self,slot,data):
        self.f('glBindBuffer',None,[U,U])(0x90d2,self.ids[slot]);self.f('glBufferData',None,[U,S,P,U])(0x90d2,len(data),C.create_string_buffer(data),0x88e8)
    def allocate(self,slot,size):
        self.f('glBindBuffer',None,[U,U])(0x90d2,self.ids[slot]);self.f('glBufferData',None,[U,S,P,U])(0x90d2,max(4,size),None,0x88e8)
    def read(self,slot,size):
        self.f('glMemoryBarrier',None,[U])(0x200);self.f('glBindBuffer',None,[U,U])(0x90d2,self.ids[slot]);out=C.create_string_buffer(size)
        self.f('glGetBufferSubData',None,[U,S,S,P])(0x90d2,0,size,out);return out.raw
    def uniforms(self,name,route=1,tick=41,depth=False):
        p=self.programs[name];self.f('glUseProgram',None,[U])(p)
        at=lambda key:self.f('glGetUniformLocation',I,[U,C.c_char_p])(p,key.encode())
        for i,b in enumerate(self.ids):self.f('glBindBufferBase',None,[U,U,U])(0x90d2,i,b)
        self.f('glUniform4ui',None,[I,U,U,U,U])(at('frameInfo'),self.w,self.h,self.n,self.words)
        self.f('glUniform4fv',None,[I,I,P])(at('eyeFov'),1,(F*4)(*self.eye))
        for key,data in [('cameraRight',self.right),('cameraUp',self.up),('cameraForward',self.forward)]:self.f('glUniform3fv',None,[I,I,P])(at(key),1,(F*3)(*data))
        x=F(.37+F(.3*F(math.sin(tick*.017)).value).value).value;y=F(.74).value;z=F(.56).value;l=F(math.sqrt(F(F(x*x).value+F(y*y).value+F(z*z).value).value)).value
        self.light=tuple(F(v/l).value for v in (x,y,z));self.f('glUniform3fv',None,[I,I,P])(at('lightDirection'),1,(F*3)(*self.light))
        self.f('glUniform1i',None,[I,I])(at('surfaceRoute'),route);self.f('glUniform1i',None,[I,I])(at('depthView'),int(depth))
        return at
    def complete(self):
        sync=self.f('glFenceSync',P,[U,U])(0x9117,0);assert sync;self.f('glFlush',None,[])();limit=time.monotonic()+30
        try:
            while True:
                status=self.f('glClientWaitSync',U,[P,U,C.c_uint64])(sync,0,1_000_000)
                if status in (0x911a,0x911c):self.fences+=1;return
                assert status==0x911b and time.monotonic()<limit,'Surface fence failed'
        finally:self.f('glDeleteSync',None,[P])(sync)
    def layout(self,fixture,w,h):
        raw=fixture.read_bytes();self.n,preset,stride,fmt=struct.unpack_from('<4I',raw);assert stride==96 and fmt==1
        self.eye=struct.unpack_from('<4f',raw,16);self.right=struct.unpack_from('<3f',raw,32);self.up=struct.unpack_from('<3f',raw,44);self.forward=struct.unpack_from('<3f',raw,56)
        body=raw[72:];assert len(body)==max(4,96*self.n);self.bodies=[struct.unpack_from('<24f',body,i*96) for i in range(self.n)]
        self.w,self.h=w,h;self.tiles=((w+7)//8)*((h+7)//8);self.words=max(1,(self.n+31)//32);self.metadata_size=4*(1+self.tiles+self.tiles*self.words)
        self.upload(0,body);self.upload(1,bytes(self.metadata_size));self.uniforms('map')
        self.f('glDispatchCompute',None,[U,U,U])((self.tiles+63)//64,1,1);self.f('glMemoryBarrier',None,[U])(0x2200);self.complete()
        self.metadata=struct.unpack('<'+'I'*(self.metadata_size//4),self.read(1,self.metadata_size));self.pages=self.metadata[0]
        assigned=[v for v in self.metadata[1:1+self.tiles] if v];assert sorted(assigned)==list(range(1,self.pages+1))
        self.f('glBindTexture',None,[U,U])(0xde1,self.texture)
        for key in [0x2801,0x2800]:self.f('glTexParameteri',None,[U,U,I])(0xde1,key,0x2600)
        self.f('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0xde1,0,0x8058,w,h,0,0x1908,0x1401,None)
        self.f('glBindFramebuffer',None,[U,U])(0x8d40,self.target);self.f('glFramebufferTexture2D',None,[U,U,U,U,I])(0x8d40,0x8ce0,0xde1,self.texture,0)
        assert self.f('glCheckFramebufferStatus',U,[U])(0x8d40)==0x8cd5
        self.gl.check_error();return preset
    def reserve(self,route):self.complete();self.allocate(2,384*(self.tiles if route==0 else self.pages))
    def render(self,route,tick=41,depth=False):
        self.f('glMemoryBarrier',None,[U])(0x2000);self.uniforms('capture',route,tick,depth)
        self.f('glDispatchCompute',None,[U,U,U])((self.w+7)//8,(self.h+7)//8,1);self.f('glMemoryBarrier',None,[U])(0x2000)
        self.f('glBindFramebuffer',None,[U,U])(0x8d40,self.target);self.f('glViewport',None,[I,I,I,I])(0,0,self.w,self.h)
        for cap in [0xbe2,0xb71,0xb44,0xbd0,0xc11]:self.f('glDisable',None,[U])(cap)
        self.uniforms('present',route,tick,depth);self.f('glBindVertexArray',None,[U])(self.vao);self.f('glDrawArrays',None,[U,I,I])(4,0,3);self.complete();self.gl.check_error()
        out=C.create_string_buffer(self.w*self.h*4);self.f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,self.w,self.h,0x1908,0x1401,out)
        return out.raw
    def query(self,route,tick=41):
        self.allocate(3,8192);at=self.uniforms('query',route,tick);self.f('glUniform1ui',None,[I,U])(at('queryCount'),256)
        self.f('glDispatchCompute',None,[U,U,U])(4,1,1);self.f('glMemoryBarrier',None,[U])(0x2200);self.complete();data=self.read(3,8192);self.allocate(3,4)
        return list(struct.iter_unpack('<8I',data))
    def canonical(self,route):
        data=struct.unpack('<'+'I'*(max(4,384*(self.tiles if route==0 else self.pages))//4),self.read(2,max(4,384*(self.tiles if route==0 else self.pages))))
        out=[]
        for y in range(self.h):
            for x in range(self.w):
                tile=y//8*((self.w+7)//8)+x//8;page=self.metadata[1+tile];lane=y%8*8+x%8
                if route==1 and page==0:ident=65535;rgba=0
                else:
                    base=(tile if route==0 else page-1)*96;ident=(data[base+64+lane//2]>>((lane&1)*16))&65535;rgba=data[base+lane]
                if ident==65535:v=5+y*18//self.h;rgba=0xff000000|((v+9)<<16)|((v+3)<<8)|v
                assert ident==65535 or ident<self.n
                out.append((ident,rgba))
        return out

def png(path,image,w,h):
    def chunk(tag,data):return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data)&0xffffffff)
    scan=b''.join(b'\0'+image[y*w*4:(y+1)*w*4] for y in range(h-1,-1,-1))
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(scan))+chunk(b'IEND',b''))

def run():
    out=ROOT/'build/surface';out.mkdir(parents=True,exist_ok=True)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',str(out),str(ROOT/'src/x/gpu/visual/SurfaceScene.java'),str(ROOT/'host/SurfaceFixture.java')],check=True)
    subprocess.run(['java','-cp',str(out),'SurfaceFixture',str(out)],check=True)
    gl=Driver();cases=[];pixels=0;oracle_checks=0;max_depth_error=0.;max_channel_error=0
    profiles=[(n,p,128,72,'default') for p in range(4) for n in [8,64,256,4096]]
    profiles += [(64,0,1280,720,'default'),(64,2,1280,720,'default'),(64,0,127,73,'default'),(64,0,128,72,'orbit'),(64,2,128,72,'inside')]
    for n,p,w,h,pose in profiles:
        fixture=out/(f'surface-{n}-{p}.bin' if pose=='default' else f'surface-{pose}.bin')
        gl.layout(fixture,w,h);images=[];states=[];depths=[];queries=[]
        for route in [0,1]:
            gl.reserve(route);images.append(gl.render(route));states.append(gl.canonical(route));queries.append(gl.query(route));depths.append(gl.render(route,depth=True))
            # Reusing the same pool with fragment reads followed by SSBO writes.
            gl.render(route,83);gl.render(route,41)
            assert gl.canonical(route)==states[route],('pool reuse',n,p,w,h)
        assert images[0]==images[1],('RGBA image',n,p,w,h)
        assert depths[0]==depths[1],('depth image',n,p,w,h)
        assert states[0]==states[1],('ID and RGBA',n,p,w,h)
        assert queries[0]==queries[1],('query state',n,p,w,h)
        for q in queries[0]:
            x,y,ident,rgba,depth,refid,refcolor,refdepth=q
            assert ident==refid and rgba==refcolor,('stored query',n,p,q)
            a,b=struct.unpack('<2f',struct.pack('<2I',depth,refdepth))
            assert a==b or abs(a-b)<=max(1e-4,abs(b)*2e-5)
        # Independent exhaustive CPU observer ignores the GPU masks/page lists.
        limit=256 if n<=64 else 64 if n<=256 else 32
        for q in queries[0][:limit]:
            x,y,ident,rgba,depth,*_=q
            expected,color,d=scalar(gl.bodies,gl.eye,gl.right,gl.up,gl.forward,w,h,x,y,gl.light)
            assert ident==expected,('scalar ID',n,p,x,y,ident,expected)
            if expected!=65535:
                actual=struct.unpack('<f',struct.pack('<I',depth))[0];err=abs(actual-d)/max(1,abs(d));max_depth_error=max(max_depth_error,err)
                assert err<3e-4,('scalar depth',n,p,actual,d,err)
            channel=max(abs(((rgba>>s)&255)-((color>>s)&255)) for s in (0,8,16,24));max_channel_error=max(max_channel_error,channel)
            assert channel<=2,('scalar RGBA',n,p,x,y,hex(rgba),hex(color),channel)
            oracle_checks+=1
        if n==8:
            for y in range(h):
                for x in range(w):
                    ident,rgba=states[0][y*w+x]
                    expected,color,d=scalar(gl.bodies,gl.eye,gl.right,gl.up,gl.forward,w,h,x,y,gl.light)
                    assert ident==expected,('exhaustive scalar ID',p,x,y,ident,expected)
                    channel=max(abs(((rgba>>s)&255)-((color>>s)&255)) for s in (0,8,16,24));max_channel_error=max(max_channel_error,channel)
                    assert channel<=2,('exhaustive scalar RGBA',p,x,y,hex(rgba),hex(color))
                    oracle_checks+=1
        occupied=sum(ident!=65535 for ident,rgba in states[0]);assert occupied==0 if p==3 else occupied>0
        if p==3:assert gl.pages==0
        if p==2:assert occupied==w*h and gl.pages==gl.tiles
        if p==0 and pose=='default':assert gl.pages<gl.tiles
        if (n,p,w,h)==(64,0,1280,720):png(ROOT/'build/surface-preview.png',images[1],w,h)
        pixels+=w*h
        cases.append({'objects':gl.n,'preset':p,'camera':pose,'width':w,'height':h,'tiles':gl.tiles,'active_pages':gl.pages,'visible_pixels':occupied,
                      'dense_surface_pool_bytes':384*gl.tiles,'compact_surface_pool_bytes':max(4,384*gl.pages),'map_bytes':gl.metadata_size,
                      'rgba_byte_exact':True,'depth_image_byte_exact':True,'id_rgba_state_exact':True,'image_sha256':sha(images[0])})
        print(json.dumps(cases[-1]),flush=True)
    report={'format':'GPU-VIRTUAL-C09-host-validation-v1','backend':gl.gl.identity,'hardware_gpu_executed':False,'shader_count':5,
            'shader_sha256':{name:sha(text.encode()) for name,text in sources().items()},'cases':cases,'image_pairs':len(cases)*2,'canonical_pixels':pixels,
            'query_pairs':len(cases)*256,'independent_scalar_queries':oracle_checks,'max_scalar_depth_relative_error':max_depth_error,
            'max_scalar_color_channel_error':max_channel_error,'completed_fences':gl.fences,'window_swaps':0,
            'scope':'Actual C09 GPU programs executed by llvmpipe CPU; independent exhaustive float64 oracle checks geometry against Java float32 input. GPU dense/compact images and ID+RGBA exact. CPU/GPU depth tolerance 3e-4 and color tolerance 2/255. No physical GPU FPS or total memory saving claim.'}
    (ROOT/'build/surface-host-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='cases'}))
    gl.gl.close()
if __name__=='__main__':run()
