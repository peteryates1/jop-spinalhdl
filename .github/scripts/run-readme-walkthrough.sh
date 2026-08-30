#!/usr/bin/env bash
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
#   run-readme-walkthrough.sh 1-7     every push, ~6 minutes
#   run-readme-walkthrough.sh 8       nightly, ~25-50 minutes
#
# Step 8 (JopSmpBramSim) runs until a garbage collection actually happens,
# ~54M cycles. Measured at 25 min unloaded and 48 min on a contended machine.
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
while IFS= read -r line; do
  if [[ "$line" =~ ^#[[:space:]]*([0-9]+)[a-z]?\. ]]; then
    step="${BASH_REMATCH[1]}"
  fi
  [[ "$line" =~ ^[[:space:]]*# ]] && continue      # comments are documentation
  [[ -z "${line// }" ]] && continue
  if [ "$step" -ge "$lo" ] && [ "$step" -le "$hi" ]; then
    script+="echo '>>> [step $step] $line'"$'\n'"$line"$'\n'
    ran=$((ran + 1))
  fi
done <<< "$block"

[ "$ran" -gt 0 ] || { echo "no commands matched steps $RANGE" >&2; exit 2; }

echo "=== README walk-through, steps $RANGE: $ran commands ==="
# One shell for the lot: the steps `cd` between directories and rely on it.
bash -euo pipefail -c "$script" || failed=1

if [ "$failed" -ne 0 ]; then
  echo "=== README walk-through FAILED (steps $RANGE) ==="
  echo "The README documents a command that does not work. Fix the command or"
  echo "fix the README -- they are the same file now."
  exit 1
fi
echo "=== README walk-through OK (steps $RANGE) ==="
