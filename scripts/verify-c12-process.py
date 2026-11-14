#!/usr/bin/env python3
"""Cross-process C12 continuation, complete output bytes and C11 wire compatibility."""
import argparse
import hashlib
import json
import os
import struct
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evidence/current/c12/process"
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument("--baseline-root", type=Path, required=True)
args = parser.parse_args()
baseline = args.baseline_root.resolve()
assert subprocess.check_output(["git", "-C", str(baseline), "rev-parse", "HEAD^{tree}"], text=True).strip() == "f721b5c2da6268ec81bca30e7b7b65e31c9e68ff"
legacy_bin = ROOT / "build/pinned-c11-cli"
subprocess.run([os.environ.get("CXX", "g++"), "-std=c++17", "-O2", "-I" + str(baseline / "include"),
                str(baseline / "src/ejecucion_main.cpp"), "-o", str(legacy_bin)], check=True, timeout=60)
binary = ROOT / "build/gpu_c12"


def invoke(executable, *arguments):
    r = subprocess.run([str(executable), *map(str, arguments)], cwd=ROOT, text=True, capture_output=True, timeout=60)
    if r.returncode:
        raise AssertionError(r.stderr)
    return json.loads(r.stdout)


records = []
for name in ("relaciones-compartidas", "flotantes-ordenados"):
    source = ROOT / "examples" / (name + ".xgpu")
    complete, output = OUT / (name + ".xgs"), OUT / (name + ".bin")
    final = invoke(binary, "run", source, complete)
    assert final["profile"] == "XGPU-C12" and final["finished"]
    invoke(binary, "export", complete, 0, output)
    values = list(struct.unpack("<65536I", output.read_bytes()))
    if name == "relaciones-compartidas":
        expected = [(((i % 64) ^ 63) + 3) ** 2 for i in range(65536)]
    else:
        expected = [struct.unpack("<I", struct.pack("<f", float((i + 1) * 2)))[0] for i in range(65536)]
    assert values == expected, "full exported output differs from closed-form reference"
    assert int(invoke(binary, "sum-u32", complete, 0)["sum_u64"]) == sum(expected)
    query = invoke(binary, "sum-u32", complete, 0, 61, 997)
    assert int(query["sum_u64"]) == sum(expected[61:1058])
    hashes = []
    for stop in (0, 1, 3, 5, 8, 10, final["steps"]):
        partial = OUT / f"{name}-step-{stop:03d}.xgs"
        resumed = OUT / (name + "-resumed.xgs")
        report = invoke(binary, "advance", source, stop, partial)
        before = partial.read_bytes()
        assert invoke(binary, "inspect", partial) == report
        assert partial.read_bytes() == before
        assert invoke(binary, "resume", partial, resumed) == final
        assert resumed.read_bytes() == complete.read_bytes()
        hashes.append({"step": stop, "sha256": hashlib.sha256(before).hexdigest()})
    records.append({"program": name, "checkpoints": hashes, "output_words_verified": 65536,
                    "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest()})

reversible = OUT / "reversible.xi"
reversible.write_text("begin\nx 128\ncx 0 64\ncx 64 128\nend\n")
inputs = [0, 1, 1 << 64, (1 << 128) | 3]
observed = invoke(binary, "reversible-xi", 129, reversible, *(f"{value:x}" for value in inputs))
expected = []
for value in inputs:
    value ^= 1 << 128
    value ^= (value & 1) << 64
    value ^= ((value >> 64) & 1) << 128
    expected.append(f"{value:033x}")
assert observed["outputs_hex"] == expected

# Old snapshots must continue with identical old counters, aliases and wire bytes.
legacy_checks = 0
for name in ("estado-divergente", "atomicas", "primer-calculo"):
    source = baseline / "examples" / (name + ".xgpu")
    for stop in (0, 1, 5, 12, 14):
        old, expected, actual = OUT / "legacy-old.xgs", OUT / "legacy-expected.xgs", OUT / "legacy-actual.xgs"
        invoke(legacy_bin, "advance", source, stop, old)
        invoke(legacy_bin, "resume", old, expected)
        invoke(binary, "resume", old, actual)
        assert expected.read_bytes() == actual.read_bytes(), "C11 checkpoint continuation changed"
        legacy_checks += 1

single = OUT / "legacy-single.xgpu"
single.write_text("xgpu 2\ndominio 1\ngrupo 1\ncompartida 0\nglobal 1\nindice r0\nconstante r1 31\ndesplaza_izq r2 r0 r1\nsalida r2\nfin\n")
for stop in (0, 1, 2, 3):
    old, expected, actual = OUT / "legacy-old.xgs", OUT / "legacy-expected.xgs", OUT / "legacy-actual.xgs"
    invoke(legacy_bin, "advance", single, stop, old)
    invoke(legacy_bin, "resume", old, expected)
    invoke(binary, "resume", old, actual)
    assert expected.read_bytes() == actual.read_bytes()
    legacy_checks += 1

result = {"profile": "XGPU-C12-process", "programs": records, "legacy_c11_future_byte_checks": legacy_checks,
          "hardware_gpu_executed": False, "failures": 0}
(OUT.parent / "process.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({"profile": result["profile"], "checkpoints": sum(len(r["checkpoints"]) for r in records),
                  "full_exported_words_verified": 131072, "legacy_c11_future_byte_checks": legacy_checks, "failures": 0}))
