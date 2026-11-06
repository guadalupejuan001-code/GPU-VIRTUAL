#!/usr/bin/env python3
"""Check the official GPU inventory and, in CI, the branch's C12 ancestry."""
import argparse
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument("--head", help="HEAD or the exact commit SHA whose ancestry must be checked")
args = parser.parse_args()
root = args.repository.resolve()
baseline = json.loads((root / "GPU_BASELINE.json").read_text())
assert baseline["schema"] == "gpu-virtual-baseline-v1", "unknown baseline schema"
version = (root / "VERSION").read_text().strip()
current = re.fullmatch(r"C(\d+)(?:\.\d+)*", version)
origin = re.fullmatch(r"C(\d+)", baseline["baseline_version"])
assert current and origin and int(current[1]) >= int(origin[1]), "GPU version predates the official baseline"
ancestor = baseline["required_ancestor"]
assert re.fullmatch(r"[0-9a-f]{40}", ancestor), "invalid required ancestor"
for name in baseline["required_paths"]:
    assert (root / name).is_file(), f"baseline component missing: {name}"
if args.head:
    assert args.head == "HEAD" or re.fullmatch(r"[0-9a-f]{40}", args.head), "invalid head SHA"
    result = subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", ancestor, args.head],
                            text=True, capture_output=True, check=False)
    if result.returncode:
        raise SystemExit("La rama debe descender de la base C12; actualizar desde main con historial completo. "
                         + result.stderr.strip())
print(json.dumps({"version": version, "baseline": baseline["baseline_version"],
                  "required_ancestor": ancestor, "head": args.head,
                  "lineage_checked": bool(args.head), "passed": True}))
