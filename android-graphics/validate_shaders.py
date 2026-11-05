#!/usr/bin/env python3
"""Independent language-compiler check of the exact own shaders packed into Android."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
from surface_sources import sources as surface_sources
from surface2_sources import sources as surface2_sources

ROOT=Path(__file__).resolve().parent

def run():
    compiler=shutil.which('glslangValidator')
    if not compiler:raise SystemExit('Install glslang-tools for this optional host compiler check')
    out=ROOT/'build/shader-compiler';out.mkdir(parents=True,exist_ok=True)
    text=lambda name:(ROOT/'assets'/name).read_text()
    common='#version 310 es\n'+text('scene.glsl')+'\n'
    startup='#version 310 es\n'+text('startup-scene.glsl')+'\n'
    shaders={'startup.vert':startup+text('startup-vertex.glsl'),'scene.vert':common+text('vertex.glsl'),
             'scene.frag':text('fragment.glsl'),'scene.comp':common+text('compute.glsl')}
    shaders.update(surface_sources())
    shaders.update(surface2_sources())
    rows=[]
    for name,source in shaders.items():
        path=out/name;path.write_text(source)
        result=subprocess.run([compiler,'-S',path.suffix[1:],str(path)],capture_output=True,text=True)
        if result.returncode:raise RuntimeError(name+'\n'+result.stdout+result.stderr)
        rows.append({'name':name,'sha256':hashlib.sha256(source.encode()).hexdigest(),'compiled':True})
    version=subprocess.run([compiler,'--version'],capture_output=True,text=True,check=True).stdout.strip()
    report={'compiler':version,'shaders':rows,'shader_count':len(rows),'all_compiled':True,
            'target':'GLSL ES 3.10 from production source','hardware_gpu_executed':False,
            'scope':'Language compiler check; not the Mali driver and not proof of device execution'}
    (ROOT/'build/shader-compiler-validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))

if __name__=='__main__':run()
