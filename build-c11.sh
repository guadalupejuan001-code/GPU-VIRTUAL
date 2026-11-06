#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current/c11
compiler="${CXX:-g++}"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude \
  src/ejecucion_main.cpp -o build/gpu_c11
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude -Itests \
  tests/ejecucion_pruebas.cpp -o build/ejecucion_pruebas
./build/ejecucion_pruebas | tee evidence/current/c11/correctness.json
python3 scripts/verify-c11-process.py
