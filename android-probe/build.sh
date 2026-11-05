#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "Falta un Android SDK configurado" >&2
  exit 2
fi
android_jar="$sdk_root/platforms/android-35/android.jar"
android_tools="$sdk_root/build-tools/35.0.0"
test -f "$android_jar"
test -x "$android_tools/aapt2"
mkdir -p build/gen/x/gpu/probe build/classes build/dex build/host build/dist
python3 make_assets.py
java com.sun.tools.javac.Main -d build/host src/x/gpu/probe/FilterCodec.java host/FilterTest.java
java -cp build/host FilterTest build/filter-fixtures.dat | tee build/dist/host-validation.json
source_sha="${GITHUB_SHA:-local-uncommitted}"
python3 - "$source_sha" <<'PY'
from pathlib import Path
import sys,json
p=Path('build/gen/x/gpu/probe/BuildInfo.java')
p.write_text('package x.gpu.probe; public final class BuildInfo { public static final String SOURCE_SHA='+json.dumps(sys.argv[1])+'; }\n')
PY
"$android_tools/aapt2" link --manifest AndroidManifest.xml -I "$android_jar" \
  --java build/gen -A build/assets --min-sdk-version 26 --target-sdk-version 35 \
  -o build/base.apk
python3 - <<'PY'
from pathlib import Path
paths=sorted(Path('src').rglob('*.java'))+sorted(Path('build/gen').rglob('*.java'))
Path('build/sources.list').write_text('\n'.join(str(p) for p in paths)+'\n')
PY
java com.sun.tools.javac.Main -encoding UTF-8 --release 8 -classpath "$android_jar" \
  -d build/classes @build/sources.list
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
if [[ ! -f build/probe-debug.keystore ]]; then
  keytool -genkeypair -noprompt -keystore build/probe-debug.keystore -storepass android \
    -keypass android -alias probe -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=GPU VIRTUAL Test,O=GPU VIRTUAL,C=US"
fi
apk="build/dist/GPU-VIRTUAL-C03-PRUEBA-FISICA.apk"
"$android_tools/apksigner" sign --ks build/probe-debug.keystore --ks-key-alias probe \
  --ks-pass pass:android --key-pass pass:android --out "$apk" build/aligned.apk
"$android_tools/apksigner" verify --verbose "$apk" | tee build/dist/signature-validation.txt
"$android_tools/aapt2" dump badging "$apk" > build/dist/package-validation.txt
python3 - <<'PY'
from pathlib import Path
import hashlib,json,zipfile
p=Path('build/dist/GPU-VIRTUAL-C03-PRUEBA-FISICA.apk')
with zipfile.ZipFile(p) as z:
 assert z.testzip() is None
 assert 'classes.dex' in z.namelist()
 inventory=json.loads(z.read('assets/inventory.json'))
 result={'apk':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),
         'cases':len(inventory['cases']),'signature_verified':True,'physical_gpu_executed':False,
         'requires_device_run':True,'java_python_filters':json.loads(Path('build/dist/host-validation.json').read_text())}
 Path('build/dist/validation.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result))
PY
