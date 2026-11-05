#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current
compiler="${CXX:-g++}"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude -Itests src/main.cpp -o build/gpu_c01
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude -Itests tests/pruebas.cpp -o build/pruebas
./build/pruebas evidence/current/checkpoint-demo.xgs | tee evidence/current/correctness.json
./build/gpu_c01 run examples/primer-calculo.xgpu evidence/current/resultado-demo.xgs
