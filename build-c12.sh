#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current/c12
compiler="${CXX:-g++}"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude \
  src/ejecucion_main.cpp -o build/gpu_c12
"$compiler" -std=c++17 -O2 -ffp-contract=off -Wall -Wextra -Wpedantic -Werror -Iinclude -Itests \
  tests/relaciones_pruebas.cpp -o build/relaciones_pruebas
./build/relaciones_pruebas | tee evidence/current/c12/correctness.json
