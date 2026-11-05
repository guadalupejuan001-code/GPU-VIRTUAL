"""Render the exact, own C06 preview before any C08 compute/state buffers exist."""
import ctypes as C
import hashlib
import math
from pathlib import Path
import time
from make_fixtures import details

U,I,P,F=C.c_uint,C.c_int,C.c_void_p,C.c_float
ROOT=Path(__file__).resolve().parent

def verify_startup(gl,compile,link):
    f=lambda name,result,args:gl.gl(name,result,args)
    common=(ROOT/'assets/startup-scene.glsl').read_bytes()
    vertex=b'#version 310 es\n'+common+b'\n'+(ROOT/'assets/startup-vertex.glsl').read_bytes()
    fragment=(ROOT/'assets/fragment.glsl').read_bytes()
    assert hashlib.sha256(vertex).hexdigest()=='c1ebba4a800a87630acdd5acc7b5c2af00e55c189551d3e2698befadcaac6ead'
    assert hashlib.sha256(fragment).hexdigest()=='0967196e6db86bd63ad38a1994f4cd86be50c61a26c859ee4aacd95e75fc2c22'
    program=link(compile(0x8B31,vertex.decode()),compile(0x8B30,fragment.decode()))
    ids=(U*1)();f('glGenVertexArrays',None,[I,C.POINTER(U)])(1,ids);vao=ids[0];f('glBindVertexArray',None,[U])(vao)
    f('glGenTextures',None,[I,C.POINTER(U)])(1,ids);texture=ids[0];f('glBindTexture',None,[U,U])(0x0DE1,texture)
    for key in [0x2801,0x2800]:f('glTexParameteri',None,[U,U,I])(0x0DE1,key,0x2600)
    w=h=512;n=65536;tick=1024;seed=0xc0511a
    f('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0x0DE1,0,0x8058,w,h,0,0x1908,0x1401,None)
    f('glGenFramebuffers',None,[I,C.POINTER(U)])(1,ids);target=ids[0];f('glBindFramebuffer',None,[U,U])(0x8D40,target)
    f('glFramebufferTexture2D',None,[U,U,U,U,I])(0x8D40,0x8CE0,0x0DE1,texture,0)
    assert f('glCheckFramebufferStatus',U,[U])(0x8D40)==0x8CD5
    f('glViewport',None,[I,I,I,I])(0,0,w,h)
    for cap in [0x0B71,0x0B44,0x0BD0,0x0C11]:f('glDisable',None,[U])(cap)
    f('glEnable',None,[U])(0x0BE2);f('glBlendFunc',None,[U,U])(0x0302,1)
    f('glClearColor',None,[F,F,F,F])(0.007,0.012,0.035,1);f('glClear',None,[U])(0x4000)
    f('glUseProgram',None,[U])(program)
    location=lambda name:f('glGetUniformLocation',I,[U,C.c_char_p])(program,name.encode())
    f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location('parameters'),1,(U*4)(n,tick,seed,4))
    flat=[word for row in details(n,tick) for word in row]
    f('glUniform4uiv',None,[I,I,C.POINTER(U)])(location('details[0]'),4,(U*16)(*flat))
    f('glUniform1i',None,[I,I])(location('compactMode'),1)
    angle=tick*.003;tilt=.23+math.sin(tick*.001)*.18
    f('glUniform4f',None,[I,F,F,F,F])(location('camera'),math.cos(angle),math.sin(angle),math.cos(tilt),math.sin(tilt))
    f('glUniform2f',None,[I,F,F])(location('viewportSize'),w,h)
    for index in [0,1]:f('glDisableVertexAttribArray',None,[U])(index);f('glVertexAttribI4ui',None,[U,U,U,U,U])(index,0,0,0,0)
    f('glDrawArraysInstanced',None,[U,I,I,I])(4,0,6,n)
    fence=f('glFenceSync',P,[U,U])(0x9117,0);assert fence;f('glFlush',None,[])()
    end=time.monotonic()+30
    while True:
        status=f('glClientWaitSync',U,[P,U,C.c_uint64])(fence,0,1_000_000)
        if status in (0x911A,0x911C):break
        assert status==0x911B and time.monotonic()<end,'First-frame fence did not signal'
    f('glDeleteSync',None,[P])(fence)
    pixels=C.create_string_buffer(w*h*4);f('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,w,h,0x1908,0x1401,pixels)
    assert len(set(pixels.raw[0::4]))>1;gl.check_error()
    f('glBindFramebuffer',None,[U,U])(0x8D40,0);f('glUseProgram',None,[U])(0);f('glDeleteProgram',None,[U])(program)
    f('glDeleteFramebuffers',None,[I,C.POINTER(U)])(1,(U*1)(target));f('glDeleteTextures',None,[I,C.POINTER(U)])(1,(U*1)(texture));f('glDeleteVertexArrays',None,[I,C.POINTER(U)])(1,(U*1)(vao))
    return pixels.raw,{'c06_shader_bytes_exact':True,'first_frame_fence_confirmed':True,'per_light_buffer_objects_required':0,'compute_programs_required':0,'width':w,'height':h,'image_sha256':hashlib.sha256(pixels.raw).hexdigest()}
