#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build evidence/current/c13
compiler="${CXX:-g++}"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror -Iinclude \
  tests/graphics_page_residency.cpp -o build/graphics_page_residency
./build/graphics_page_residency | tee evidence/current/c13/residency.json
