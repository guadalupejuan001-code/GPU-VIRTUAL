#!/usr/bin/env python3
"""Ejecuta shaders reales mediante llvmpipe CPU; no declara GPU fisica."""
import ctypes as C
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import zlib

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'experiments/c02'))
from gl_capture import Capture
from make_fixtures import details,value

U,I,P,F,SIZE=C.c_uint,C.c_int,C.c_void_p,C.c_float,C.c_ssize_t
sha=lambda data:hashlib.sha256(data).hexdigest()


def run():
    gl=Capture(software=True)
    def f(name,result,args):return gl.gl(name,result,args)
    def compile(stage,source):
        s=f('glCreateShader',U,[U])(stage);data=C.c_char_p(source.encode())
        f('glShaderSource',None,[U,I,C.POINTER(C.c_char_p),P])(s,1,C.byref(data),None)
        f('glCompileShader',None,[U])(s);ok=I();f('glGetShaderiv',None,[U,U,C.POINTER(I)])(s,0x8B81,C.byref(ok))
        if not ok.value:
            log=C.create_string_buffer(8192);f('glGetShaderInfoLog',None,[U,I,P,P])(s,len(log),None,log);raise RuntimeError(log.value.decode())
        return s
    def link(*shaders):
        p=f('glCreateProgram',U,[])()
        for s in shaders:f('glAttachShader',None,[U,U])(p,s)
        f('glLinkProgram',None,[U])(p);ok=I();f('glGetProgramiv',None,[U,U,C.POINTER(I)])(p,0x8B82,C.byref(ok))
        for s in shaders:f('glDeleteShader',None,[U])(s)
        if not ok.value:
            log=C.create_string_buffer(8192);f('glGetProgramInfoLog',None,[U,I,P,P])(p,len(log),None,log);raise RuntimeError(log.value.decode())
        return p
    common=(ROOT/'assets/scene.glsl').read_text()
    vertex='#version 310 es\n'+common+'\n'+(ROOT/'assets/vertex.glsl').read_text()
    fragment=(ROOT/'assets/fragment.glsl').read_text()
    compute='#version 310 es\n'+common+'\nlayout(local_size_x=64) in;\nlayout(std430,binding=0) writeonly buffer Values { uvec4 values[]; };\nvoid main(){uint id=gl_GlobalInvocationID.x;if(id<parameters.x)values[id]=sceneValue(id);}\n'
    render=link(compile(0x8B31,vertex),compile(0x8B30,fragment));calculate=link(compile(0x91B9,compute))
    location=lambda p,name:f('glGetUniformLocation',I,[U,C.c_char_p])(p,name.encode())
    def parameters(p,n,tick,seed):
        f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location(p,'parameters'),1,(U*4)(n,tick,seed,4))
        values=[word for row in details(n,tick) for word in row]
        f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location(p,'details[0]'),4,(U*16)(*values))
    ids=(U*1)();f('glGenVertexArrays',None,[I,C.POINTER(U)])(1,ids);vao=ids[0];f('glBindVertexArray',None,[U])(vao)
    f('glGenBuffers',None,[I,C.POINTER(U)])(1,ids);buffer=ids[0]
    f('glGenTextures',None,[I,C.POINTER(U)])(1,ids);texture=ids[0]
    f('glBindTexture',None,[U,U])(0x0DE1,texture)
    f('glTexParameteri',None,[U,U,I])(0x0DE1,0x2801,0x2600);f('glTexParameteri',None,[U,U,I])(0x0DE1,0x2800,0x2600)
    side=384
    f('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0x0DE1,0,0x8058,side,side,0,0x1908,0x1401,None)
    f('glGenFramebuffers',None,[I,C.POINTER(U)])(1,ids);target=ids[0];f('glBindFramebuffer',None,[U,U])(0x8D40,target)
    f('glFramebufferTexture2D',None,[U,U,U,U,I])(0x8D40,0x8CE0,0x0DE1,texture,0)
    assert f('glCheckFramebufferStatus',U,[U])(0x8D40)==0x8CD5
    cases=[];checked=0;preview=None
    for n in [257,4096,16384,65536]:
        for tick in [0,511]:
            seed=0xc0511a;flat=[word for i in range(n) for word in value(n,tick,seed,i)]
            dense=struct.pack('<'+'I'*len(flat),*flat)
            f('glUseProgram',None,[U])(calculate);parameters(calculate,n,tick,seed)
            f('glBindBuffer',None,[U,U])(0x90D2,buffer);f('glBufferData',None,[U,SIZE,P,U])(0x90D2,len(dense),None,0x88E0)
            f('glBindBufferBase',None,[U,U,U])(0x90D2,0,buffer)
            f('glDispatchCompute',None,[U,U,U])((n+63)//64,1,1);f('glMemoryBarrier',None,[U])(0x2000|0x200);f('glFinish',None,[])()
            generated=C.create_string_buffer(len(dense));f('glGetBufferSubData',None,[U,SIZE,SIZE,P])(0x90D2,0,len(dense),generated)
            assert generated.raw==dense,(n,tick,'compute state');checked+=len(flat)
            f('glBindBuffer',None,[U,U])(0x8892,buffer);payload=C.create_string_buffer(dense)
            f('glBufferData',None,[U,SIZE,P,U])(0x8892,len(dense),payload,0x88E0)
            images=[]
            for route in [0,1]:
                f('glBindFramebuffer',None,[U,U])(0x8D40,target);f('glViewport',None,[I,I,I,I])(0,0,side,side)
                for cap in [0x0B71,0x0B44,0x0BD0,0x0C11]:f('glDisable',None,[U])(cap)
                f('glEnable',None,[U])(0x0BE2);f('glBlendFunc',None,[U,U])(0x0302,1)
                f('glClearColor',None,[F,F,F,F])(0.007,0.012,0.035,1);f('glClear',None,[U])(0x4000)
                f('glUseProgram',None,[U])(render);parameters(render,n,tick,seed)
                f('glUniform1i',None,[I,I])(location(render,'compactMode'),route)
                a=tick*.003;t=.23+math.sin(tick*.001)*.18
                f('glUniform4f',None,[I,F,F,F,F])(location(render,'camera'),math.cos(a),math.sin(a),math.cos(t),math.sin(t))
                f('glUniform2f',None,[I,F,F])(location(render,'viewportSize'),side,side)
                if route==0:
                    f('glEnableVertexAttribArray',None,[U])(0);f('glVertexAttribIPointer',None,[U,I,U,I,P])(0,4,0x1405,16,None);f('glVertexAttribDivisor',None,[U,U])(0,1)
                else:
                    f('glDisableVertexAttribArray',None,[U])(0);f('glVertexAttribI4ui',None,[U,U,U,U,U])(0,0,0,0,0)
                f('glDrawArraysInstanced',None,[U,I,I,I])(4,0,6,n);f('glFinish',None,[])()
                pixels=C.create_string_buffer(side*side*4);f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,side,side,0x1908,0x1401,pixels)
                images.append(pixels.raw);gl.check_error()
            assert images[0]==images[1],(n,tick,'framebuffer')
            cases.append({'n':n,'tick':tick,'pixel_exact':True,'sha256':sha(images[0])})
            if n==16384 and tick==511:preview=images[0]
    out=ROOT/'build';out.mkdir(exist_ok=True)
    if preview:
        def chunk(tag,payload):return struct.pack('>I',len(payload))+tag+payload+struct.pack('>I',zlib.crc32(tag+payload)&0xffffffff)
        scan=b''.join(b'\0'+preview[y*side*4:(y+1)*side*4] for y in range(side-1,-1,-1))
        png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',side,side,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(scan))+chunk(b'IEND',b'')
        (out/'host-preview.png').write_bytes(png)
    report={'backend':gl.identity,'hardware_gpu_executed':False,'independent_scene_words':checked,
            'image_pairs':len(cases),'compared_pixels':side*side*len(cases),'cases':cases,
            'scope':'Software driver execution of production GLSL, independent Python values, both render routes; no Android runtime execution'}
    (out/'graphics-host-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report));gl.close()

if __name__=='__main__':run()
