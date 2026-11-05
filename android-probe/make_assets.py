#!/usr/bin/env python3
"""Vectores C02 y shaders ES propios para ejecutar en el dispositivo fisico."""
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'experiments/c02'))
from observe import make_cases
from filters import encode_column, encode_state, words

OUT = ROOT / 'android-probe/build/assets'
OUT.mkdir(parents=True, exist_ok=True)


def es(source):
    return source.replace('#version 430 core', '#version 310 es\nprecision highp float;\nprecision highp int;')


def float_passes(size):
    header = f'''#version 310 es
precision highp float;
precision highp int;
layout(local_size_x={size}) in;
layout(std430,binding=0) readonly buffer Input {{ uint source[]; }};
layout(std430,binding=1) writeonly buffer Trace {{ uint trace[]; }};
layout(std430,binding=2) buffer Output {{ uint result[]; }};
layout(std430,binding=3) buffer Intermediate {{ uint first[]; }};
uniform uint count;
void mark(uint phase,uint id,uint ab,uint bb,uint cb,uint p,uint q) {{
 uint at=(phase*count+id)*8u;
 trace[at]=id;trace[at+1u]=ab;trace[at+2u]=bb;trace[at+3u]=cb;
 trace[at+4u]=p;trace[at+5u]=q;trace[at+6u]=(p==q)?1u:0u;
 trace[at+7u]=result[id];
}}
'''
    first = '''void main(){
 uint id=gl_GlobalInvocationID.x;if(id>=count)return;
 uint ab=source[id*3u],bb=source[id*3u+1u],cb=source[id*3u+2u];
 float a=uintBitsToFloat(ab),b=uintBitsToFloat(bb),c=uintBitsToFloat(cb);
 mark(0u,id,ab,bb,cb,0u,0u);
 first[id*2u]=floatBitsToUint(a+b);
 first[id*2u+1u]=floatBitsToUint(b+c);
}'''
    second = '''void main(){
 uint id=gl_GlobalInvocationID.x;if(id>=count)return;
 uint ab=source[id*3u],bb=source[id*3u+1u],cb=source[id*3u+2u];
 float a=uintBitsToFloat(ab),c=uintBitsToFloat(cb);
 float left=uintBitsToFloat(first[id*2u])+c;
 float right=a+uintBitsToFloat(first[id*2u+1u]);
 uint p=floatBitsToUint(left),q=floatBitsToUint(right);
 result[id]=p;mark(1u,id,ab,bb,cb,p,q);
}'''
    return [header + first, header + second]


inventory = {'format': 'GPU-VIRTUAL-C03-device-probe-v1', 'cases': [],
             'scope': 'C02 source checkpoints, mobile OpenGL ES compute; float additions split across two dispatches'}
fixture = bytearray()
columns = []
states = []
for n, group in [(1024, 64), (257, 16)]:
    for item in make_cases(n, group):
        key = str(n) + '-' + item['name']
        folder = OUT / key
        folder.mkdir(exist_ok=True)
        expected, output = item['reference']
        (folder / 'input.u32').write_bytes(words(item['source']))
        (folder / 'trace.u32').write_bytes(words([v for phase in expected for row in phase for v in row]))
        (folder / 'output.u32').write_bytes(words(output))
        programs = float_passes(group) if item['kind'] == 'float' else [es(item['shader'])]
        shader_paths = []
        for i, program in enumerate(programs):
            filename = f'program-{i}.glsl'
            (folder / filename).write_text(program)
            shader_paths.append(key + '/' + filename)
        inventory['cases'].append({'key': key, 'name': item['name'], 'kind': item['kind'],
                                   'n': item['n'], 'local_size': group, 'points': len(expected),
                                   'names': item['names'], 'programs': shader_paths})
        for point, phase in enumerate(expected):
            encoded_state,_ = encode_state(item['names'],phase,point)
            states.append((point,item['names'],phase,encoded_state))
            for at in range(len(item['names'])):
                values = [row[at] for row in phase]
                encoded = encode_column(values)
                columns.append((values, encoded))
fixture.extend(struct.pack('>I', len(columns)))
for values, encoded in columns:
    fixture.extend(struct.pack('>I', len(values)))
    for value in values:
        fixture.extend(struct.pack('>I', value))
    fixture.extend(struct.pack('>I', len(encoded)))
    fixture.extend(encoded)
fixture.extend(struct.pack('>I',len(states)))
for point,names,rows,encoded in states:
    fixture.extend(struct.pack('>III',point,len(rows),len(names)))
    for name in names:
        data=name.encode();fixture.extend(struct.pack('>I',len(data)));fixture.extend(data)
    for row in rows:
        for value in row:fixture.extend(struct.pack('>I',value))
    fixture.extend(struct.pack('>I',len(encoded)));fixture.extend(encoded)
(OUT / 'inventory.json').write_text(json.dumps(inventory, separators=(',', ':')))
(ROOT / 'android-probe/build/filter-fixtures.dat').write_bytes(fixture)
print(json.dumps({'cases': len(inventory['cases']), 'filter_columns': len(columns), 'filter_states':len(states), 'assets': str(OUT)}))
