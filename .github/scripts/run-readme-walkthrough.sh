#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-09-09 by pointing step 4 at a sim object that does not exist;
# the runner printed `!!! step 4 FAILED` and exited 1 rather than running on.
# A command fails when it exits non-zero, and a SIMULATION also fails without a
# `PASS:` line. That half PROVED RED 2026-11-05 on item 176's unfixed tree, fed
# step 4b alone through README=: the old JopTopBramSim exited 0 at `GC init...`
# and the runner said `!!! step 4 FAILED, no PASS line`.
#
# Run the README's own Getting Started commands. THE README IS THE TEST.
#
# WHY THIS SHAPE. The obvious way to put the walk-through under CI is to list
# the commands in the workflow. That creates two lists that drift apart, which
# is the same defect this project keeps finding in itself: a constant that
# outlives the thing it was copied from. Here there is ONE list, in README.md,
# and CI executes it. Edit a command in the README and CI runs the edited
# command; add a step and CI runs it; break a step and the build fails.
#
# It parses the fenced bash block under "### Build and Run Simulation", which
# is numbered "# N." per step. Steps are selected by number so the long one can
# live in a different job:
#
#   run-readme-walkthrough.sh 1-8     every push, ~13 minutes
#
# Ranges still work, and step 8 is still separable -- but CI no longer splits
# it. It ran as a schedule-only `readme-walkthrough-long` job until 2026-09-09,
# which is precisely how item 147 hid for three days: no push ever ran it.
#
# Step 8 (JopSmpBramSim) runs until a garbage collection actually happens.
# It used to do that on a 128 KB heap: ~54M cycles, 25 min unloaded and 48 min
# contended, against a 90-minute job wall. Item 137 removed ~190 lines of dead
# code, which left MORE free heap, which meant MORE rounds before exhaustion --
# and the job then exceeded its wall three nights running. A cleanup made a
# test slower. The heap is 64 KB now (~2.7M cycles, under a minute) and the sim
# carries a cycle budget, so drift fails fast with a message instead of a bare
# "The operation was canceled". Soak with JOP_SMP_GC_HEAP=131072. Item 147.
set -uo pipefail

RANGE="${1:-1-7}"
README="${README:-README.md}"
lo="${RANGE%%-*}"; hi="${RANGE##*-}"

[ -f "$README" ] || { echo "no $README here (run from the repo root)" >&2; exit 2; }

# Extract the fenced block under the Getting Started heading.
block=$(awk '
  /^### Build and Run Simulation/ { insec=1; next }
  insec && /^### /                { exit }
  insec && /^```/                 { infence = !infence; next }
  insec && infence                { print }
' "$README")

[ -n "$block" ] || { echo "could not find the walk-through block in $README" >&2; exit 2; }

# Split into steps on the "# N." markers, keeping only those in range.
step=0; script=""; ran=0; failed=0
simlog=$(mktemp); trap 'rm -f "$simlog"' EXIT   # one simulation's output at a time
while IFS= read -r line; do
  if [[ "$line" =~ ^#[[:space:]]*([0-9]+)[a-z]?\. ]]; then
    step="${BASH_REMATCH[1]}"
  fi
  [[ "$line" =~ ^[[:space:]]*# ]] && continue      # comments are documentation
  [[ -z "${line// }" ]] && continue
  if [ "$step" -ge "$lo" ] && [ "$step" -le "$hi" ]; then
    # EXPLICIT CHECK PER COMMAND, not `set -e`. In `cd java && make x && cd ..`
    # a failure of `make` is EXEMPT from -e, because -e ignores any command in
    # an && list except the last. The first version of this ran on past a failed
    # step 3 and printed step 4 before reporting failure, which is exactly the
    # confusing log a CI job must not produce.
    script+="echo '>>> [step $step] $line'"$'\n'
    if [[ "$line" == *"runMain jop.system."* || "$line" == *"jopsim-"* ]]; then
      # A SIMULATION IS JUDGED BY ITS VERDICT, not only its exit status. The
      # sims are Apps, and three of the six exited 0 whatever the UART said,
      # so this job was green while step 4b never got past GC.init (item 176).
      # Each now prints `PASS: ...`, or `FAIL: ...` and exits 1; one that exits
      # 0 with no PASS line fails here, which is what a sim with no verdict at
      # all does. The pipe runs the line in a subshell, so a `cd` in it would
      # not last into the next step; no simulation line has one. JopSim's run
      # (step 3b, `make -C java jopsim-hello`) is judged the same way: it once
      # ran to its budget and exited 0 having printed nothing (item 154).
      script+="if ! { $line ; } 2>&1 | tee '$simlog'; then echo \"!!! step $step FAILED: $line\"; exit 1; fi"$'\n'
      script+="if ! grep -q 'PASS: ' '$simlog'; then echo \"!!! step $step FAILED, no PASS line: $line\"; exit 1; fi"$'\n'
    else
      script+="if ! { $line ; }; then echo \"!!! step $step FAILED: $line\"; exit 1; fi"$'\n'
    fi
    ran=$((ran + 1))
  fi
done <<< "$block"

[ "$ran" -gt 0 ] || { echo "no commands matched steps $RANGE" >&2; exit 2; }

echo "=== README walk-through, steps $RANGE: $ran commands ==="
# One shell for the lot: the steps `cd` between directories and rely on it.
# no -e: each command is checked explicitly above
bash -uo pipefail -c "$script" || failed=1

if [ "$failed" -ne 0 ]; then
  echo "=== README walk-through FAILED (steps $RANGE) ==="
  echo "The README documents a command that does not work. Fix the command or"
  echo "fix the README -- they are the same file now."
  exit 1
fi
echo "=== README walk-through OK (steps $RANGE) ==="
