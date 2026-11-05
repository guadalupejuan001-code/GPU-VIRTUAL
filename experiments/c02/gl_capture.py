"""Captura variables de shaders propios mediante el driver OpenGL del sistema.

Es una herramienta de referencia externa a C01; no contiene otro motor GPU.
Los puntos de captura son puntos del codigo fuente, no instrucciones ISA ni
contadores de programa fisicos. Solo usa ctypes de la biblioteca estandar.
"""
import ctypes as C
import ctypes.util
import glob
import os
import struct
import time


class Capture:
    def __init__(self, software=False):
        self.software_requested = software
        if software:
            os.environ['LIBGL_ALWAYS_SOFTWARE'] = '1'
        os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
        path = ctypes.util.find_library('EGL')
        if not path:
            raise RuntimeError('No hay biblioteca EGL del sistema')
        self.egl = C.CDLL(path)
        self.display = None
        self.context = None
        self.functions = {}
        try:
            self._setup()
        except Exception:
            self.close()
            raise

    def egl_fn(self, name, result, args):
        function = getattr(self.egl, name)
        function.restype = result
        function.argtypes = args
        return function

    def gl(self, name, result, args):
        if name not in self.functions:
            address = self.get_proc(name.encode())
            if not address:
                raise RuntimeError('Funcion no disponible: ' + name)
            self.functions[name] = C.CFUNCTYPE(result, *args)(address)
        return self.functions[name]

    def _setup(self):
        P, I, U = C.c_void_p, C.c_int, C.c_uint
        self.get_proc = self.egl_fn('eglGetProcAddress', P, [C.c_char_p])
        display = self.egl_fn('eglGetDisplay', P, [P])(None)
        major, minor = I(), I()
        if not self.egl_fn('eglInitialize', U, [P, C.POINTER(I), C.POINTER(I)])(display, C.byref(major), C.byref(minor)):
            raise RuntimeError('No se pudo inicializar EGL; no se sustituye por C01')
        self.display = display
        if not self.egl_fn('eglBindAPI', U, [U])(0x30A2):
            raise RuntimeError('No se pudo seleccionar OpenGL')
        config, count = P(), I()
        attrs = (I * 7)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3038)
        choose = self.egl_fn('eglChooseConfig', U, [P, C.POINTER(I), C.POINTER(P), I, C.POINTER(I)])
        if not choose(display, attrs, C.byref(config), 1, C.byref(count)) or count.value == 0:
            raise RuntimeError('No hay configuracion EGL compatible')
        context_attrs = (I * 7)(0x3098, 4, 0x30FB, 3, 0x30FD, 1, 0x3038)
        context = self.egl_fn('eglCreateContext', P, [P, P, P, C.POINTER(I)])(display, config, None, context_attrs)
        if not context:
            raise RuntimeError('No hay contexto OpenGL 4.3')
        self.context = context
        if not self.egl_fn('eglMakeCurrent', U, [P, P, P, P])(display, None, None, context):
            raise RuntimeError('No se pudo activar el contexto OpenGL')
        get_string = self.gl('glGetString', C.c_char_p, [U])
        self.identity = {
            key: get_string(enum).decode()
            for key, enum in [('vendor', 0x1F00), ('renderer', 0x1F01), ('version', 0x1F02), ('glsl', 0x8B8C)]
        }
        software_names = ('llvmpipe', 'softpipe', 'swiftshader', 'software rasterizer')
        known_software = any(name in self.identity['renderer'].lower() for name in software_names)
        self.identity.update({
            'backend_kind': 'software' if known_software else 'driver_not_classified',
            'physical_gpu_verified': False,
            'device_nodes': glob.glob('/dev/dri/render*') + glob.glob('/dev/nvidia*') + glob.glob('/dev/kgsl*'),
            'egl_version': f'{major.value}.{minor.value}',
            'software_requested': self.software_requested,
        })
        get_int = self.gl('glGetIntegerv', None, [U, C.POINTER(I)])
        self.limits = {}
        for name, enum in [('max_workgroup_invocations', 0x90EB), ('max_ssbo_bytes', 0x90DE)]:
            value = I()
            get_int(enum, C.byref(value))
            self.limits[name] = value.value
        self.check_error()

    def check_error(self):
        error = self.gl('glGetError', C.c_uint, [])()
        if error:
            raise RuntimeError(f'Error OpenGL: 0x{error:04x}')

    def compile(self, source):
        U, I, P = C.c_uint, C.c_int, C.c_void_p
        shader = self.gl('glCreateShader', U, [U])(0x91B9)
        encoded = source.encode()
        pointer = C.c_char_p(encoded)
        self.gl('glShaderSource', None, [U, I, C.POINTER(C.c_char_p), C.POINTER(I)])(shader, 1, C.byref(pointer), None)
        self.gl('glCompileShader', None, [U])(shader)
        get_shader = self.gl('glGetShaderiv', None, [U, U, C.POINTER(I)])
        ok = I()
        get_shader(shader, 0x8B81, C.byref(ok))
        if not ok.value:
            log = C.create_string_buffer(16384)
            self.gl('glGetShaderInfoLog', None, [U, I, C.POINTER(I), P])(shader, len(log), None, log)
            self.gl('glDeleteShader', None, [U])(shader)
            raise RuntimeError('Compilacion GLSL: ' + log.value.decode())
        program = self.gl('glCreateProgram', U, [])()
        self.gl('glAttachShader', None, [U, U])(program, shader)
        self.gl('glLinkProgram', None, [U])(program)
        self.gl('glDeleteShader', None, [U])(shader)
        self.gl('glGetProgramiv', None, [U, U, C.POINTER(I)])(program, 0x8B82, C.byref(ok))
        if not ok.value:
            log = C.create_string_buffer(16384)
            self.gl('glGetProgramInfoLog', None, [U, I, C.POINTER(I), P])(program, len(log), None, log)
            self.gl('glDeleteProgram', None, [U])(program)
            raise RuntimeError('Enlace GLSL: ' + log.value.decode())
        return program

    def execute(self, source, input_words, n, phases, columns, local_size):
        if n < 1 or local_size < 1 or local_size > self.limits['max_workgroup_invocations']:
            raise ValueError('Dominio o grupo no admitido por este driver')
        sizes = [len(input_words) * 4, n * phases * columns * 4, n * 4]
        if max(sizes) > self.limits['max_ssbo_bytes']:
            raise ValueError('Captura mayor que el buffer admitido por el driver')
        U, I, P, SIZE = C.c_uint, C.c_int, C.c_void_p, C.c_ssize_t
        program = self.compile(source)
        ids = (U * 3)()
        self.gl('glGenBuffers', None, [I, C.POINTER(U)])(3, ids)
        try:
            # Los centinelas permiten detectar capturas o salidas sin escribir.
            data = [struct.pack('<' + 'I' * len(input_words), *input_words), b'\xa5' * sizes[1], b'\x5a' * sizes[2]]
            for slot, content in enumerate(data):
                self.gl('glBindBuffer', None, [U, U])(0x90D2, ids[slot])
                payload = C.create_string_buffer(content)
                self.gl('glBufferData', None, [U, SIZE, P, U])(0x90D2, len(content), payload, 0x88E8)
                self.gl('glBindBufferBase', None, [U, U, U])(0x90D2, slot, ids[slot])
            self.gl('glUseProgram', None, [U])(program)
            location = self.gl('glGetUniformLocation', I, [U, C.c_char_p])(program, b'count')
            if location < 0:
                raise RuntimeError('El shader no expone el dominio')
            self.gl('glUniform1ui', None, [I, U])(location, n)
            begin = time.perf_counter()
            self.gl('glDispatchCompute', None, [U, U, U])((n + local_size - 1) // local_size, 1, 1)
            self.gl('glMemoryBarrier', None, [U])(0x2000 | 0x200)
            self.gl('glFinish', None, [])()
            dispatch_ms = (time.perf_counter() - begin) * 1000
            readback = []
            for slot in [1, 2]:
                self.gl('glBindBuffer', None, [U, U])(0x90D2, ids[slot])
                target = C.create_string_buffer(sizes[slot])
                self.gl('glGetBufferSubData', None, [U, SIZE, SIZE, P])(0x90D2, 0, sizes[slot], target)
                readback.append(target.raw)
            self.check_error()
            return readback[0], readback[1], dispatch_ms
        finally:
            self.gl('glDeleteBuffers', None, [I, C.POINTER(U)])(3, ids)
            self.gl('glDeleteProgram', None, [U])(program)

    def close(self):
        if self.display:
            self.egl_fn('eglMakeCurrent', C.c_uint, [C.c_void_p] * 4)(self.display, None, None, None)
            if self.context:
                self.egl_fn('eglDestroyContext', C.c_uint, [C.c_void_p, C.c_void_p])(self.display, self.context)
            self.egl_fn('eglTerminate', C.c_uint, [C.c_void_p])(self.display)
            self.context = self.display = None


def header(local_size):
    return f'''#version 430 core
layout(local_size_x={local_size}) in;
layout(std430,binding=0) readonly buffer Input {{ uint source[]; }};
layout(std430,binding=1) writeonly buffer Trace {{ uint trace[]; }};
layout(std430,binding=2) buffer Output {{ uint result[]; }};
uniform uint count;
'''


def integer_shader(local_size):
    return header(local_size) + '''
void mark(uint phase,uint id,uint route,uint a,uint b,uint c,uint d,uint address) {
    uint p=(phase*count+id)*8u;
    trace[p]=id; trace[p+1u]=route; trace[p+2u]=a; trace[p+3u]=b;
    trace[p+4u]=c; trace[p+5u]=d; trace[p+6u]=address; trace[p+7u]=result[id];
}
void main() {
    uint id=gl_GlobalInvocationID.x;
    if(id>=count) return;
    uint a=source[id], b=0u, c=0u, d=0u, route=0u, address=id;
    mark(0u,id,route,a,b,c,d,address);
    b=a*3u+5u;
    mark(1u,id,route,a,b,c,d,address);
    route=a&1u;
    if(route==1u) c=b+11u; else c=b-7u;
    mark(2u,id,route,a,b,c,d,address);
    address=(id*5u+3u)%count;
    d=source[address];
    mark(3u,id,route,a,b,c,d,address);
    c=(c^d)+(id&7u)*17u;
    mark(4u,id,route,a,b,c,d,address);
    result[id]=c;
    mark(5u,id,route,a,b,c,d,address);
}
'''


def shared_shader(local_size):
    return header(local_size) + f'''
shared uint scratch[{local_size}];
shared uint total;
void mark(uint phase,uint id,uint a,uint neighbor,uint sum,uint value) {{
    if(id>=count) return;
    uint p=(phase*count+id)*6u;
    trace[p]=id; trace[p+1u]=a; trace[p+2u]=neighbor;
    trace[p+3u]=sum; trace[p+4u]=value; trace[p+5u]=result[id];
}}
void main() {{
    uint id=gl_GlobalInvocationID.x, lid=gl_LocalInvocationID.x;
    uint a=id<count ? source[id] : 0u;
    mark(0u,id,a,0u,0u,0u);
    scratch[lid]=a;
    if(lid==0u) total=0u;
    barrier();
    uint neighbor=scratch[(lid+1u)%{local_size}u];
    mark(1u,id,a,neighbor,0u,0u);
    atomicAdd(total,a);
    barrier();
    uint sum=total, value=neighbor+sum;
    if(id<count) result[id]=value;
    mark(2u,id,a,neighbor,sum,value);
}}
'''


def float_shader(local_size):
    return header(local_size) + '''
void mark(uint phase,uint id,uint ab,uint bb,uint cb,uint p,uint q) {
    uint at=(phase*count+id)*8u;
    trace[at]=id; trace[at+1u]=ab; trace[at+2u]=bb; trace[at+3u]=cb;
    trace[at+4u]=p; trace[at+5u]=q; trace[at+6u]=(p==q)?1u:0u;
    trace[at+7u]=result[id];
}
void main() {
    uint id=gl_GlobalInvocationID.x;
    if(id>=count) return;
    uint ab=source[id*3u], bb=source[id*3u+1u], cb=source[id*3u+2u];
    precise float a=uintBitsToFloat(ab), b=uintBitsToFloat(bb), c=uintBitsToFloat(cb);
    mark(0u,id,ab,bb,cb,0u,0u);
    precise float left_first=a+b;
    precise float left=left_first+c;
    precise float right_first=b+c;
    precise float right=a+right_first;
    uint p=floatBitsToUint(left), q=floatBitsToUint(right);
    result[id]=p;
    mark(1u,id,ab,bb,cb,p,q);
}
'''
