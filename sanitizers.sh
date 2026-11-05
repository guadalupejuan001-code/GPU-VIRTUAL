#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current
compiler="${CXX:-g++}"
asan_options="${ASAN_OPTIONS:-detect_leaks=1:halt_on_error=1}"
ubsan_options="${UBSAN_OPTIONS:-halt_on_error=1}"
"$compiler" -std=c++17 -O1 -g -Wall -Wextra -Wpedantic -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer -Iinclude -Itests \
  tests/pruebas.cpp -o build/pruebas_san
ASAN_OPTIONS="$asan_options" UBSAN_OPTIONS="$ubsan_options" \
  ./build/pruebas_san evidence/current/checkpoint-sanitizer.xgs \
  > evidence/current/sanitizers-checks.json
python3 - "$asan_options" <<'PY'
import json
from pathlib import Path
import sys
directory=Path('evidence/current')
checks=json.loads((directory/'sanitizers-checks.json').read_text())
leaks='detect_leaks=0' not in sys.argv[1].split(':')
result={'address_sanitizer':'passed','undefined_behavior_sanitizer':'passed',
        'leak_sanitizer':'passed' if leaks else 'disabled_in_traced_runtime',
        'leak_detection_enabled':leaks,'checks':checks}
(directory/'sanitizers.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
PY
