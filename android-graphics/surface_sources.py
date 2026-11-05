"""Assembly of the exact C09 shader assets used by SurfaceGpu.java."""
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def sources():
    read=lambda name:(ROOT/'assets'/name).read_text()
    common='#version 310 es\n'+read('surface-common.glsl')+'\n'
    ro='layout(std430,binding=1) readonly buffer TileMap { uint meta[]; };\n'
    trace=read('surface-trace.glsl')+'\n'
    storage=read('surface-storage.glsl')+'\n'
    return {'surface-map.comp':common+read('surface-map.glsl'),
            'surface-capture.comp':common+ro+trace+read('surface-capture.glsl'),
            'surface-query.comp':common+ro+trace+storage+read('surface-query.glsl'),
            'surface.vert':read('surface-vertex.glsl'),
            'surface.frag':common+ro+storage+read('surface-present.glsl')}
