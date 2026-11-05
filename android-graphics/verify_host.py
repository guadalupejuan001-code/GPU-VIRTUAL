#!/usr/bin/env python3
"""Execute production shaders through llvmpipe CPU, never call it physical GPU evidence."""
import ctypes as C
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import time
import zlib

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'experiments/c02'))
from gl_capture import Capture
from make_fixtures import details,value,mix
from verify_startup import verify_startup
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
    startup_image,startup_report=verify_startup(gl,compile,link)
    common=(ROOT/'assets/scene.glsl').read_text()
    vertex='#version 310 es\n'+common+'\n'+(ROOT/'assets/vertex.glsl').read_text()
    fragment=(ROOT/'assets/fragment.glsl').read_text()
    compute='#version 310 es\n'+common+'\n'+(ROOT/'assets/compute.glsl').read_text()
    render=link(compile(0x8B31,vertex),compile(0x8B30,fragment));calculate=link(compile(0x91B9,compute))
    location=lambda p,name:f('glGetUniformLocation',I,[U,C.c_char_p])(p,name.encode())
    def parameters(p,n,tick,seed):
        f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location(p,'parameters'),1,(U*4)(n,tick,seed,4))
        flat=[word for row in details(n,tick) for word in row]
        f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location(p,'details[0]'),4,(U*16)(*flat))
    ids=(U*1)();f('glGenVertexArrays',None,[I,C.POINTER(U)])(1,ids);f('glBindVertexArray',None,[U])(ids[0])
    f('glGenBuffers',None,[I,C.POINTER(U)])(1,ids);buffer=ids[0]
    f('glGenBuffers',None,[I,C.POINTER(U)])(1,ids);hash_buffer=ids[0]
    f('glGenTextures',None,[I,C.POINTER(U)])(1,ids);texture=ids[0]
    f('glBindTexture',None,[U,U])(0x0DE1,texture)
    for key in [0x2801,0x2800]:f('glTexParameteri',None,[U,U,I])(0x0DE1,key,0x2600)
    f('glGenFramebuffers',None,[I,C.POINTER(U)])(1,ids);target=ids[0]
    def target_size(w,h):
        f('glBindTexture',None,[U,U])(0x0DE1,texture)
        f('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0x0DE1,0,0x8058,w,h,0,0x1908,0x1401,None)
        f('glBindFramebuffer',None,[U,U])(0x8D40,target)
        f('glFramebufferTexture2D',None,[U,U,U,U,I])(0x8D40,0x8CE0,0x0DE1,texture,0)
        assert f('glCheckFramebufferStatus',U,[U])(0x8D40)==0x8CD5
    def generated(n,tick,seed,cached,prior_batch_completed=False):
        if not prior_batch_completed:f('glMemoryBarrier',None,[U])(0x2000)
        f('glUseProgram',None,[U])(calculate);parameters(calculate,n,tick,seed)
        f('glUniform1i',None,[I,I])(location(calculate,'cachedMode'),int(cached))
        f('glBindBufferBase',None,[U,U,U])(0x90D2,0,buffer);f('glBindBufferBase',None,[U,U,U])(0x90D2,1,hash_buffer)
        f('glDispatchCompute',None,[U,U,U])((n+63)//64,1,1)
        f('glMemoryBarrier',None,[U])(1)
    def read_state(size):
        f('glMemoryBarrier',None,[U])(0x200);f('glFinish',None,[])()
        f('glBindBuffer',None,[U,U])(0x90D2,buffer)
        result=C.create_string_buffer(size);f('glGetBufferSubData',None,[U,SIZE,SIZE,P])(0x90D2,0,size,result);return result.raw
    cases=[];checked=0;hash_checked=0;preview=None;frame_fences=0
    def completed(sync):
        nonlocal frame_fences
        deadline=time.monotonic()+30
        while True:
            status=f('glClientWaitSync',U,[P,U,C.c_uint64])(sync,0,1_000_000)
            if status in (0x911A,0x911C):frame_fences+=1;return
            assert status==0x911B and time.monotonic()<deadline,'Fence no confirmada'
    profiles=[(n,t,384,384) for n in [257,4096,16384,65536] for t in [0,511]]
    profiles += [(n,t,w,h) for n in [65536,262144] for t,w,h in [(1024,512,512),(1376,1280,720)]]
    for n,tick,w,h in profiles:
        seed=0xc0511a;flat=[word for i in range(n) for word in value(n,tick,seed,i)]
        dense=struct.pack('<'+'I'*len(flat),*flat)
        hashes=struct.pack('<'+'I'*n,*(mix(i^seed) for i in range(n)))
        f('glBindBuffer',None,[U,U])(0x8892,hash_buffer)
        f('glBufferData',None,[U,SIZE,P,U])(0x8892,len(hashes),C.create_string_buffer(hashes),0x88E4)
        cached=C.create_string_buffer(len(hashes));f('glGetBufferSubData',None,[U,SIZE,SIZE,P])(0x8892,0,len(hashes),cached)
        assert cached.raw==hashes;hash_checked+=n
        f('glBindBuffer',None,[U,U])(0x90D2,buffer);f('glBufferData',None,[U,SIZE,P,U])(0x90D2,len(dense),None,0x88E0)
        for use_cache in [False,True]:
            generated(n,tick,seed,use_cache);assert read_state(len(dense))==dense,(n,tick,use_cache,'compute state');checked+=len(flat)
        target_size(w,h);images=[]
        for route in range(4):
            if route==0:
                f('glBindBuffer',None,[U,U])(0x8892,buffer)
                f('glBufferSubData',None,[U,SIZE,SIZE,P])(0x8892,0,len(dense),C.create_string_buffer(dense))
            if route==3:generated(n,tick,seed,False)
            f('glBindFramebuffer',None,[U,U])(0x8D40,target);f('glViewport',None,[I,I,I,I])(0,0,w,h)
            for cap in [0x0B71,0x0B44,0x0BD0,0x0C11]:f('glDisable',None,[U])(cap)
            f('glEnable',None,[U])(0x0BE2);f('glBlendFunc',None,[U,U])(0x0302,1)
            f('glClearColor',None,[F,F,F,F])(0.007,0.012,0.035,1);f('glClear',None,[U])(0x4000)
            f('glUseProgram',None,[U])(render);parameters(render,n,tick,seed)
            f('glUniform1i',None,[I,I])(location(render,'compactMode'),route)
            angle=tick*.003;tilt=.23+math.sin(tick*.001)*.18
            f('glUniform4f',None,[I,F,F,F,F])(location(render,'camera'),math.cos(angle),math.sin(angle),math.cos(tilt),math.sin(tilt))
            f('glUniform2f',None,[I,F,F])(location(render,'viewportSize'),w,h)
            for index in [0,1]:f('glDisableVertexAttribArray',None,[U])(index);f('glVertexAttribI4ui',None,[U,U,U,U,U])(index,0,0,0,0)
            if route in [0,3]:
                f('glBindBuffer',None,[U,U])(0x8892,buffer);f('glEnableVertexAttribArray',None,[U])(0)
                f('glVertexAttribIPointer',None,[U,I,U,I,P])(0,4,0x1405,16,None);f('glVertexAttribDivisor',None,[U,U])(0,1)
            if route==2:
                f('glBindBuffer',None,[U,U])(0x8892,hash_buffer);f('glEnableVertexAttribArray',None,[U])(1)
                f('glVertexAttribIPointer',None,[U,I,U,I,P])(1,1,0x1405,4,None);f('glVertexAttribDivisor',None,[U,U])(1,1)
            f('glDrawArraysInstanced',None,[U,I,I,I])(4,0,6,n)
            sync=f('glFenceSync',P,[U,U])(0x9117,0);assert sync
            f('glFlush',None,[])();completed(sync);f('glDeleteSync',None,[P])(sync)
            pixels=C.create_string_buffer(w*h*4);f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,w,h,0x1908,0x1401,pixels)
            images.append(pixels.raw)
            if route==3:assert read_state(len(dense))==dense;checked+=len(flat)
            gl.check_error()
        assert all(image==images[0] for image in images[1:]),(n,tick,w,h,'framebuffer')
        if (n,tick,w,h)==(65536,1024,512,512):assert images[0]==startup_image;startup_report['matches_four_route_scene']=True
        cases.append({'n':n,'tick':tick,'width':w,'height':h,'routes':4,'pixel_exact':True,'sha256':sha(images[0])})
        if n==65536 and w==1280:preview=(images[0],w,h)
    # Two batches reuse distinct state buffers only after every prior batch fence signals.
    base_buffer=buffer;f('glEnableVertexAttribArray',None,[U])(0);f('glVertexAttribDivisor',None,[U,U])(0,1)
    f('glDisableVertexAttribArray',None,[U])(1);f('glVertexAttribI4ui',None,[U,U,U,U,U])(1,0,0,0,0)
    pipeline_image_checks=0
    for depth in [8,16]:
        ring=(U*depth)();f('glGenBuffers',None,[I,C.POINTER(U)])(depth,ring)
        for slot in ring:
            f('glBindBuffer',None,[U,U])(0x90D2,slot);f('glBufferData',None,[U,SIZE,P,U])(0x90D2,n*16,None,0x88E8)
        for batch in range(2):
            pending=[]
            for step in range(depth):
                buffer=ring[step];tick=1024+(batch*depth+step)*8;generated(n,tick,seed,False,True);f('glUseProgram',None,[U])(render)
                parameters(render,n,tick,seed);f('glUniform1i',None,[I,I])(location(render,'compactMode'),3)
                f('glBindBuffer',None,[U,U])(0x8892,buffer);f('glVertexAttribIPointer',None,[U,I,U,I,P])(0,4,0x1405,16,None)
                f('glClear',None,[U])(0x4000);f('glDrawArraysInstanced',None,[U,I,I,I])(4,0,6,n)
                sync=f('glFenceSync',P,[U,U])(0x9117,0);assert sync;pending.append(sync)
            f('glFlush',None,[])()
            for sync in pending:completed(sync);f('glDeleteSync',None,[P])(sync)
        flat=[word for i in range(n) for word in value(n,tick,seed,i)]
        expected=struct.pack('<'+'I'*len(flat),*flat);assert read_state(len(expected))==expected;checked+=len(flat)
        generated_image=C.create_string_buffer(w*h*4);f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,w,h,0x1908,0x1401,generated_image)
        f('glUseProgram',None,[U])(render);parameters(render,n,tick,seed);f('glUniform1i',None,[I,I])(location(render,'compactMode'),1)
        f('glClear',None,[U])(0x4000);f('glDrawArraysInstanced',None,[U,I,I,I])(4,0,6,n);f('glFinish',None,[])()
        reference_image=C.create_string_buffer(w*h*4);f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,w,h,0x1908,0x1401,reference_image)
        assert generated_image.raw==reference_image.raw;pipeline_image_checks+=1
        f('glDeleteBuffers',None,[I,C.POINTER(U)])(depth,ring);buffer=base_buffer
    gl.check_error();assert frame_fences==len(cases)*4+48
    out=ROOT/'build';out.mkdir(exist_ok=True)
    if preview:
        pixels,w,h=preview
        def chunk(tag,payload):return struct.pack('>I',len(payload))+tag+payload+struct.pack('>I',zlib.crc32(tag+payload)&0xffffffff)
        scan=b''.join(b'\0'+pixels[y*w*4:(y+1)*w*4] for y in range(h-1,-1,-1))
        png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(scan))+chunk(b'IEND',b'')
        (out/'host-preview.png').write_bytes(png)
    assert startup_report['matches_four_route_scene']
    report={'backend':gl.identity,'hardware_gpu_executed':False,'independent_scene_words':checked,'hash_cache_words':hash_checked,'startup':startup_report,
            'image_cases':len(cases),'equivalent_route_pairs':len(cases)*3+pipeline_image_checks+1,'pipeline_final_image_checks':pipeline_image_checks,'compared_pixels':sum(c['width']*c['height']*3 for c in cases)+pipeline_image_checks*w*h+512*512,'cases':cases,
            'completed_frame_fences':frame_fences+1,'pipelined_frames_confirmed':48,'pipeline_depths':[8,16],'gpu_ring_batches_per_depth':2,'window_swaps':0,
            'scope':'Production C08 GLSL on CPU software driver; independent Python state and hash reference; four render routes at square/HD resolutions. No physical GPU performance result.'}
    (out/'graphics-host-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report));gl.close()

if __name__=='__main__':run()

