#!/usr/bin/env python3
"""Alternating whole-output CPU trials against the immutable C11 source tree."""
import argparse
import hashlib
import json
import os
import platform
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evidence/current/c12"
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument("--baseline-root", type=Path, required=True)
parser.add_argument("--rounds", type=int, default=5)
args = parser.parse_args()
baseline = args.baseline_root.resolve()
assert 3 <= args.rounds <= 9
tree = subprocess.check_output(["git", "-C", str(baseline), "rev-parse", "HEAD^{tree}"], text=True).strip()
assert tree == "f721b5c2da6268ec81bca30e7b7b65e31c9e68ff", "baseline differs from verified C11"
compiler = os.environ.get("CXX", "g++")
source = ROOT / "tests/relaciones_benchmark.cpp"
bins = {}
for name, include, flags in (("pinned-C11", baseline / "include", []), ("C12", ROOT / "include", ["-DXGPU_RELATIONS=1"])):
    binary = ROOT / "build" / ("relation-bench-" + name)
    subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                    *flags, "-I" + str(include), str(source), "-o", str(binary)], check=True, timeout=60)
    bins[name] = binary


def trial(name, workload, count):
    p = subprocess.run([str(bins[name]), workload, str(count)], text=True, capture_output=True, timeout=60)
    if p.returncode:
        raise AssertionError(p.stderr)
    row = json.loads(p.stdout)
    assert row["complete_output_checked"]
    return row


records, summaries = [], []
for workload in ("periodic", "gather", "uniform"):
    for count in (4096, 65536):
        # Compile/startup warmups are deliberately separate from measured trials.
        trial("pinned-C11", workload, count)
        trial("C12", workload, count)
        rows = {name: [] for name in bins}
        for round_number in range(args.rounds):
            order = ("pinned-C11", "C12") if round_number % 2 == 0 else ("C12", "pinned-C11")
            for name in order:
                row = trial(name, workload, count)
                row["round"] = round_number
                rows[name].append(row)
                records.append(row)
        assert len({r["output_hash"] for r in rows["pinned-C11"] + rows["C12"]}) == 1
        summary = {"workload": workload, "n": count, "rounds": args.rounds}
        for name, group in rows.items():
            summary[name] = {key: statistics.median(r[key] for r in group) for key in (
                "prepare_ms", "execute_ms", "materialize_ms", "total_to_output_ms", "checkpoint_ms",
                "checkpoint_bytes", "scalar_lane_evaluations", "native_peak_rss_kib")}
        summary["speedup_to_full_output"] = summary["pinned-C11"]["total_to_output_ms"] / summary["C12"]["total_to_output_ms"]
        summaries.append(summary)

result = {"scope": "host CPU, full-output interpreter workloads; not game/display FPS",
          "baseline_commit": "567b97a5ccd59f4a25a4df351bd6e48d0e0f8665", "baseline_tree": tree,
          "host": platform.platform(), "compiler": subprocess.check_output([compiler, "--version"], text=True).splitlines()[0],
          "benchmark_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
          "records": records, "summaries": summaries}
(OUT / "measurements.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps({"summaries": summaries}))
