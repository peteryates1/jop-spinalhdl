#!/usr/bin/env bash
#
# DISCIPLINE: docs/testing-discipline.md — "assert the invariant, not the tally".
# PROVED RED 2026-09-04 by removing a job's `sbt/setup-sbt` step — it names the job.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
#
# REGRESSION TEST: a CI job that runs sbt must also install it.
#
# The GitHub runner image has NO sbt. Every job here pairs
#   - uses: sbt/setup-sbt@v1
# with a `Cache SBT` step, and the two look like one idiom -- which is exactly
# how they come apart. The card-table-tests job was written by copying the cache
# step and not the setup step, and failed on 2026-09-03 with
#
#   /bin/sh: 1: sbt: not found
#   make: *** [Makefile:64: runtime/.../Const.java] Error 127
#
# at the first `make -C java runtime`, because that shells out to sbt to
# generate Const.java. The failure is a long way from the cause: the error names
# a Makefile rule and a Java source file, not a missing action in a workflow.
#
# Caching without installing is the dangerous half of the pair, so this checks
# the direction that matters: anything that RUNS sbt must SET UP sbt.
#
# Usage: .github/scripts/check-workflow-sbt-setup.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

for wf in .github/workflows/*.yml; do
  [ -e "$wf" ] || continue
  python3 - "$wf" <<'PY' || exit 1
import sys
try:
    import yaml
except ImportError:
    # Loud, not silent: a guard that quietly does nothing is worse than none.
    print("  SKIP (PyYAML not installed — this check did NOT run)")
    sys.exit(0)

wf = sys.argv[1]
with open(wf) as fh:
    doc = yaml.safe_load(fh)

jobs = (doc or {}).get("jobs") or {}
bad, checked = [], 0
for name, job in jobs.items():
    steps = job.get("steps") or []
    runs_sbt = any("sbt" in str(s.get("run", "")) for s in steps)
    if not runs_sbt:
        continue
    checked += 1
    if not any(str(s.get("uses", "")).startswith("sbt/setup-sbt") for s in steps):
        bad.append(name)

if bad:
    print(f"  FAIL {wf}: job(s) run sbt without sbt/setup-sbt: {', '.join(bad)}")
    print("       the runner image has no sbt; add `uses: sbt/setup-sbt@v1`")
    sys.exit(1)

# A COUNT, not a verdict: "0 jobs checked" would mean the detection broke and
# this guard had silently stopped guarding anything. See status item 111.
print(f"  {wf}: {checked} job(s) run sbt, all install it")
PY
done
