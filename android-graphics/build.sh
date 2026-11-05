#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then echo "Falta el Android SDK" >&2; exit 2; fi
android_jar="$sdk_root/platforms/android-35/android.jar"
android_tools="$sdk_root/build-tools/35.0.0"
test -f "$android_jar"
mkdir -p build/gen/x/gpu/visual build/classes build/dex build/host build/dist
python3 make_fixtures.py
java com.sun.tools.javac.Main -d build/host src/x/gpu/visual/SceneState.java src/x/gpu/visual/SustainedStats.java src/x/gpu/visual/ProbeMonitor.java src/x/gpu/visual/BenchmarkPlan.java src/x/gpu/visual/StartupState.java host/SceneTest.java host/SustainedTest.java host/MonitorTest.java host/PlanTest.java host/StartupTest.java
java -cp build/host SceneTest build/scene-fixtures.dat | tee build/dist/scene-host-validation.json
java -cp build/host SustainedTest | tee build/dist/sustained-host-validation.json
java -cp build/host MonitorTest | tee build/dist/monitor-host-validation.json
java -cp build/host PlanTest | tee build/dist/plan-host-validation.json
java -cp build/host StartupTest | tee build/dist/startup-host-validation.json
source_sha="${GITHUB_SHA:-local-uncommitted}"
python3 - "$source_sha" <<'PY'
from pathlib import Path
import sys,json
Path('build/gen/x/gpu/visual/BuildInfo.java').write_text('package x.gpu.visual; public final class BuildInfo { public static final String SOURCE_SHA='+json.dumps(sys.argv[1])+'; }\n')
PY
"$android_tools/aapt2" link --manifest AndroidManifest.xml -I "$android_jar" \
  --java build/gen -A assets --min-sdk-version 26 --target-sdk-version 35 -o build/base.apk
python3 - <<'PY'
from pathlib import Path
Path('build/sources.list').write_text('\n'.join(str(p) for p in sorted(Path('src').rglob('*.java'))+sorted(Path('build/gen').rglob('*.java')))+'\n')
PY
java com.sun.tools.javac.Main -encoding UTF-8 --release 8 -classpath "$android_jar" -d build/classes @build/sources.list
shopt -s globstar nullglob
class_files=(build/classes/**/*.class)
"$android_tools/d8" --lib "$android_jar" --min-api 26 --output build/dex "${class_files[@]}"
python3 - <<'PY'
from pathlib import Path
import shutil,zipfile
shutil.copyfile('build/base.apk','build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(Path('build/dex').glob('*.dex')):z.write(p,p.name)
PY
"$android_tools/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/scene-debug.keystore ]]; then
  keytool -genkeypair -noprompt -keystore build/scene-debug.keystore -storepass android \
    -keypass android -alias scene -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=GPU VIRTUAL Scene Test,O=GPU VIRTUAL,C=US"
fi
apk="build/dist/GPU-VIRTUAL-C10-PERSISTENTE-GPU.apk"
"$android_tools/apksigner" sign --ks build/scene-debug.keystore --ks-key-alias scene \
  --ks-pass pass:android --key-pass pass:android --out "$apk" build/aligned.apk
"$android_tools/apksigner" verify --verbose "$apk" > build/dist/signature-validation.txt
"$android_tools/aapt2" dump badging "$apk" > build/dist/package-validation.txt
python3 - <<'PY'
from pathlib import Path
import json,hashlib,zipfile,shutil
p=Path('build/dist/GPU-VIRTUAL-C10-PERSISTENTE-GPU.apk')
with zipfile.ZipFile(p) as z:
 assert z.testzip() is None and 'classes.dex' in z.namelist()
 for asset in Path('assets').glob('*.glsl'):assert z.read('assets/'+asset.name)==asset.read_bytes()
if Path('build/graphics-host-validation.json').exists():shutil.copyfile('build/graphics-host-validation.json','build/dist/graphics-host-validation.json')
if Path('build/host-preview.png').exists():shutil.copyfile('build/host-preview.png','build/dist/host-preview.png')
if Path('build/shader-compiler-validation.json').exists():shutil.copyfile('build/shader-compiler-validation.json','build/dist/shader-compiler-validation.json')
if Path('build/surface-host-validation.json').exists():shutil.copyfile('build/surface-host-validation.json','build/dist/surface-host-validation.json')
if Path('build/surface-preview.png').exists():shutil.copyfile('build/surface-preview.png','build/dist/surface-preview.png')
if Path('build/surface2-host-validation.json').exists():shutil.copyfile('build/surface2-host-validation.json','build/dist/surface2-host-validation.json')
if Path('build/surface2-preview.png').exists():shutil.copyfile('build/surface2-preview.png','build/dist/surface2-preview.png')
report={'apk':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
        'signature_verified':True,'hardware_gpu_executed':False,'android_device_run_required':True,
        'scene_host':json.loads(Path('build/dist/scene-host-validation.json').read_text()),
        'sustained_host':json.loads(Path('build/dist/sustained-host-validation.json').read_text()),
        'monitor_host':json.loads(Path('build/dist/monitor-host-validation.json').read_text()),
        'plan_host':json.loads(Path('build/dist/plan-host-validation.json').read_text()),
        'startup_host':json.loads(Path('build/dist/startup-host-validation.json').read_text()),
        'shader_compiler_host':json.loads(Path('build/shader-compiler-validation.json').read_text()) if Path('build/shader-compiler-validation.json').exists() else None,
        'surface_host':json.loads(Path('build/surface-host-validation.json').read_text()) if Path('build/surface-host-validation.json').exists() else None,
        'surface2_host':json.loads(Path('build/surface2-host-validation.json').read_text()) if Path('build/surface2-host-validation.json').exists() else None,
        'profile':'C10 compact CSR candidate lists, reusable ID visibility, stable intersections, terrain, 65535 objects and camera rebuild cost. Three GPU routes; one pixel pool; C09/C08 shader evidence preserved. Device execution required.'}
Path('build/dist/validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
PY

