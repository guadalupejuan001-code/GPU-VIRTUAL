#!/usr/bin/env python3
"""Orquesta medidas; el nucleo de calculo esta en C++, no en Python."""
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess

ROOT = Path(__file__).resolve().parent
EXE = ROOT / "build/gpu_c01"


def run(*args):
    return json.loads(subprocess.check_output([str(EXE), *map(str, args)], text=True))


def main():
    records = []
    for n in [4096, 65536, 1048576]:
        # Alternar orden para no atribuir siempre el calentamiento al mismo backend.
        for repeat in range(5):
            order = ["compact", "dense"] if repeat % 2 == 0 else ["dense", "compact"]
            pair = {}
            for backend in order:
                row = run("benchmark", backend, n)
                row["repeat"] = repeat
                records.append(row)
                pair[backend] = row
            if pair["compact"]["fnv1a64_output"] != pair["dense"]["fnv1a64_output"]:
                raise RuntimeError("La salida completa difiere entre backends")
    summary = []
    for n in [4096, 65536, 1048576]:
        result = {"n": n, "host": "CPU", "same_output_hash_all_repeats": True}
        for backend in ["compact", "dense"]:
            rows = [r for r in records if r["n"] == n and r["backend"] == backend]
            result[backend] = {
                key: statistics.median(row[key] for row in rows)
                for key in ["prepare_ms", "execute_ms", "materialize_ms", "total_to_output_ms", "native_peak_rss_kib", "peak_process_rss_kib"]
            }
            result[backend]["output_bytes"] = rows[0]["output_bytes"]
            result[backend]["complete_snapshot_bytes"] = rows[0]["complete_snapshot_bytes"]
        result["dense_to_compact_total_ratio"] = result["dense"]["total_to_output_ms"] / result["compact"]["total_to_output_ms"]
        summary.append(result)
    demo = run("run", ROOT / "examples/primer-calculo.xgpu")
    resumed = run("resume", ROOT / "evidence/current/checkpoint-demo.xgs")
    if demo != resumed:
        raise RuntimeError("El checkpoint persistido no reanuda a la misma salida/estado")
    (ROOT / "evidence/current/demo.json").write_text(json.dumps(demo, indent=2) + "\n")
    sources = {}
    for name in ["include/estado_gpu.hpp", "include/programa.hpp", "src/main.cpp", "tests/pruebas.cpp", "tests/referencia_densa.hpp"]:
        sources[name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    compiler = subprocess.check_output([os.environ.get("CXX", "g++"), "--version"], text=True).splitlines()[0]
    evidence = {
        "profile": "XGPU-C01",
        "platform": platform.platform(),
        "compiler": compiler,
        "compiler_flags": "-std=c++17 -O2 -Wall -Wextra -Wpedantic -Werror",
        "repeats_per_backend_and_size": 5,
        "reference": "Dense CPU interpreter written for this experiment; not a physical GPU benchmark",
        "scope": "prepare + execute + full materialized output; common typed program creation, parsing, snapshot serialization and final hash excluded from timed total",
        "memory_scope": "native_peak_rss_kib is Linux /proc/self/status VmHWM for the native image, including output and snapshot generation; raw peak_process_rss_kib is ru_maxrss and may include a launcher high-water floor; complete_snapshot_bytes is serialized logical state, not RSS",
        "limitations": ["uint32 arithmetic only", "prefix selection only", "one ordered dispatch", "no hardware GPU execution", "not integrated into Ether/XI", "no global minimum or universal acceleration claim"],
        "correctness": json.loads((ROOT / "evidence/current/correctness.json").read_text()),
        "persistent_checkpoint_resume_equal": True,
        "demo": demo,
        "source_sha256": sources,
        "summary": summary,
        "raw": records,
    }
    sanitizer = ROOT / "evidence/current/sanitizers.json"
    if sanitizer.exists():
        evidence["sanitizers"] = json.loads(sanitizer.read_text())
    (ROOT / "evidence/current/measurements.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps({"demo": demo, "summary": summary, "checkpoint_resume_equal": True}, indent=2))


if __name__ == "__main__":
    main()
