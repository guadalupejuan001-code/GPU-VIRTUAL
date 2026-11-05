"""C10 exact source assembly. C09 sources remain reproducible and unchanged."""
from surface_sources import ROOT
def sources():
    read=lambda name:(ROOT/'assets'/name).read_text()
    common='#version 310 es\n'+read('surface2-common.glsl')+'\n'+read('surface2-layout.glsl')+'\n'
    ro='layout(std430,binding=1) readonly buffer TileMap { uint meta[]; };\n'
    trace=read('surface2-trace.glsl')+'\n';storage=read('surface2-storage.glsl')+'\n'
    out={'surface2-'+name+'.comp':common+read('surface2-'+name+'.glsl') for name in ['clear','count','scan','groups','finalize','fill','restore']}
    out.update({'surface2-capture.comp':common+ro+trace+read('surface2-capture.glsl'),
                'surface2-query.comp':common+ro+trace+storage+read('surface-query.glsl'),
                'surface2.vert':read('surface-vertex.glsl'),
                'surface2.frag':common+ro+storage+read('surface-present.glsl')})
    return out
