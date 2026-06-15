#!/usr/bin/env python3
# Diff-coverage gate: do the changed executable production lines in this range
# meet the coverage floor?
#
# Wraps `diff-cover` (pip; reads the filtered jacocoTestReport.xml and computes
# the git diff itself) and adds the one thing diff-cover lacks - a tiny-diff
# exemption. A change with fewer than --min-lines executable changed lines is
# too small to hold to a percentage (a single untested line should not fail
# CI), so it passes with a note. Above that size, the changed lines must be
# >= --fail-under percent covered or the gate fails and names the gaps.
#
# Scope is whatever the JaCoCo report contains. The report is ALREADY filtered
# (Compose UI under es/jjrh/bikeradar/ui/** and the dev services are excluded -
# see coverageExcludes in app/build.gradle.kts), so new UI is not gated here;
# Roborazzi goldens cover it. The report must exist first: run
# `:app:jacocoTestReport` before this script.
#
# Base ref:
#   - contributor PR -> the PR base SHA (gate the PR's own changed lines;
#     diff-cover diffs three-dot from the merge-base by default).
#   - push to main   -> github.event.before (gate the pushed batch).
#   - local pre-push -> origin/main (gate the unpushed stack).
# An empty / all-zero / unreachable base (first push, force-push, shallow
# clone) SKIPS the gate rather than failing on a CI-history artefact - deepen
# the checkout (fetch-depth: 0) to make the base reachable.
#
# Usage:
#   diff-coverage-gate.py --base <ref> [--report PATH] [--src-roots DIR]
#                         [--fail-under 85] [--min-lines 10]
#
# Exit: 0 = pass (or skipped/exempt), 1 = coverage below floor, 2 = setup error.

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

ZERO_SHA = "0" * 40


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def base_reachable(base):
    return run(["git", "rev-parse", "--verify", "--quiet", f"{base}^{{commit}}"]).returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="base git ref/SHA to diff against")
    ap.add_argument(
        "--report",
        default="app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
    )
    ap.add_argument("--src-roots", default="app/src/main/java")
    ap.add_argument("--fail-under", type=float, default=85.0)
    ap.add_argument("--min-lines", type=int, default=10)
    args = ap.parse_args()

    base = (args.base or "").strip()
    if not base or set(base) == {"0"} or base == ZERO_SHA:
        print(f"diff-coverage: no usable base ref ('{base}') - skipping (first push / force-push).")
        return 0
    if not base_reachable(base):
        print(
            f"diff-coverage: base '{base}' not in local history - skipping. "
            "Deepen the checkout (fetch-depth: 0) to enforce the gate.",
        )
        return 0
    if not os.path.exists(args.report):
        print(f"diff-coverage: report not found at {args.report} - run :app:jacocoTestReport first.", file=sys.stderr)
        return 2
    if not shutil.which("diff-cover"):
        print("diff-coverage: diff-cover not installed - `pip install diff-cover`.", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as td:
        json_path = os.path.join(td, "diff-cover.json")
        dc = run(
            [
                "diff-cover", args.report,
                "--compare-branch", base,
                "--src-roots", args.src_roots,
                "--format", f"json:{json_path}",
            ],
        )
        if not os.path.exists(json_path) or os.path.getsize(json_path) == 0:
            print("diff-coverage: diff-cover produced no report.", file=sys.stderr)
            print(dc.stdout, dc.stderr, file=sys.stderr)
            return 2
        with open(json_path) as fh:
            data = json.load(fh)

        total = data.get("total_num_lines", 0)
        pct = data.get("total_percent_covered", 100)
        violations = data.get("total_num_violations", 0)
        print(f"diff-coverage vs {base}: {total - violations}/{total} changed lines covered = {pct}% (floor {args.fail_under:.0f}%)")

        if total < args.min_lines:
            print(f"diff-coverage: only {total} executable changed lines (< {args.min_lines}) - too small to enforce; PASS.")
            return 0
        if pct < args.fail_under:
            print(f"diff-coverage: FAIL - changed lines {pct}% < floor {args.fail_under:.0f}%. Uncovered:")
            for path, stat in sorted(data.get("src_stats", {}).items()):
                vlines = stat.get("violation_lines", [])
                if vlines:
                    print(f"  {path}: lines {', '.join(map(str, vlines))}")
            return 1
        print("diff-coverage: PASS.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
