#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current/c11
compiler="${CXX:-g++}"
asan_options="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1}"
ubsan_options="${UBSAN_OPTIONS:-halt_on_error=1}"
"$compiler" -std=c++17 -O1 -g -Wall -Wextra -Wpedantic -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer -Iinclude -Itests \
  tests/ejecucion_pruebas.cpp -o build/ejecucion_pruebas_san
ASAN_OPTIONS="$asan_options" UBSAN_OPTIONS="$ubsan_options" \
  ./build/ejecucion_pruebas_san > evidence/current/c11/sanitizers-checks.json
python3 - "$asan_options" <<'PY'
import json
from pathlib import Path
import sys
root=Path('evidence/current/c11')
result={'address_sanitizer':'passed','undefined_behavior_sanitizer':'passed',
        'leak_detection_enabled':'detect_leaks=0' not in sys.argv[1].split(':'),
        'checks':json.loads((root/'sanitizers-checks.json').read_text())}
(root/'sanitizers.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
PY
