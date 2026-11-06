#!/usr/bin/env python3
"""Production CLI, separate OS processes and persisted complete checkpoints."""
import hashlib
import json
import subprocess
import resource
import signal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evidence/current/c11"
OUT.mkdir(parents=True, exist_ok=True)
BIN = ROOT / "build/gpu_c11"


def invoke(*args, expected=0):
    result = subprocess.run([str(BIN), *map(str, args)], cwd=ROOT, text=True,
                            capture_output=True, timeout=60)
    if result.returncode != expected:
        raise AssertionError(f"CLI {args}: {result.returncode}: {result.stderr}")
    return json.loads(result.stdout) if result.stdout else None


records = []
for name in ("estado-compartido", "estado-divergente", "atomicas", "primer-calculo"):
    source = ROOT / "examples" / (name + ".xgpu")
    complete = OUT / (name + "-complete.xgs")
    expected = invoke("run", source, complete)
    assert expected["finished"]
    assert invoke("inspect", complete) == expected
    stops = list(range(expected["steps"] + 1)) if name == "estado-divergente" else [0, 1, 7, 12, 14]
    waiting_points = []
    checkpoints = []
    for stop in stops:
        partial = OUT / (name + "-partial.xgs")
        resumed = OUT / (name + "-resumed.xgs")
        saved = invoke("advance", source, stop, partial)
        if any(job["waiting_groups"] for job in saved["jobs"]):
            waiting_points.append(stop)
        persisted = partial.read_bytes()
        retained = OUT / f"{name}-step-{stop:03d}.xgs"
        retained.write_bytes(persisted)
        checkpoints.append({"step": stop, "file": retained.name,
                            "sha256": hashlib.sha256(persisted).hexdigest(),
                            "complete_snapshot_bytes": len(persisted)})
        assert invoke("inspect", partial) == saved
        assert partial.read_bytes() == persisted, "inspection modified checkpoint"
        assert invoke("resume", partial, resumed) == expected
        assert resumed.read_bytes() == complete.read_bytes()
        # Replacing the input checkpoint atomically must also work.
        assert invoke("resume", partial, partial) == expected
        assert partial.read_bytes() == complete.read_bytes()
    if name == "estado-divergente":
        assert waiting_points, "no persisted partial barrier was tested"
    records.append({"program": source.name, "stopping_points": stops,
                    "waiting_group_stopping_points": waiting_points,
                    "checkpoints": checkpoints,
                    "final_sha256": hashlib.sha256(complete.read_bytes()).hexdigest(),
                    "complete_snapshot_bytes": complete.stat().st_size,
                    "final_steps": expected["steps"]})

# A failed open/publish must not damage a prior valid checkpoint.
old = OUT / "estado-compartido-complete.xgs"
before = old.read_bytes()
invoke("advance", ROOT / "examples/estado-compartido.xgpu", 1,
       OUT / "absent-directory/checkpoint.xgs", expected=1)
assert old.read_bytes() == before


def restrict_checkpoint_write():
    signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
    resource.setrlimit(resource.RLIMIT_FSIZE, (64, 64))


failed = subprocess.run([str(BIN), "run", str(ROOT / "examples/estado-compartido.xgpu"), str(old)],
                        cwd=ROOT, text=True, capture_output=True, timeout=60,
                        preexec_fn=restrict_checkpoint_write)
assert failed.returncode == 1, failed.stderr
assert old.read_bytes() == before, "failed write destroyed the last valid checkpoint"
assert not list(OUT.glob("*.tmp.*")), "temporary checkpoint leaked"

result = {"profile": "XGPU-C11-process", "host": "CPU", "programs": records,
          "separate_process_resume": True, "atomic_same_path_replace": True,
          "failed_write_preserves_previous_checkpoint": True,
          "hardware_gpu_executed": False, "failures": 0}
(OUT / "process.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
