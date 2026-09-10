#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-09-10 by stripping the PROVED RED line from
# check-console-baud.sh; this guard named that file and exited 1.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: every guard must record which practice it follows and the
# defect it was demonstrated against.
#
# Item 111 established the practice and asserted the class: "every guard in
# .github/scripts/ carries a DISCIPLINE: header naming which practice it
# follows and a PROVED RED line recording the defect it was demonstrated
# against, and one that carries no such line is visibly untrusted."
#
# Nothing enforced it. By 2026-09-10, FOUR OF TEN carried neither -- and all
# four were written AFTER item 111 closed, so the practice decayed on its first
# use. Worse, the six original guards ARE stamped, so spot-checking any one of
# them confirmed the class while the newest guards -- including the guard added
# for the item 146 X-state class failure -- were entirely unproved.
#
# WHY THIS MATTERS MORE THAN BOOKKEEPING. Writing the PROVED RED line is what
# forces you to actually run the guard against a defect. Doing that on
# 2026-09-10 found that check-no-intree-jop-path.sh could not see
# `java/apps/X/Y.jop` -- the exact path it exists to reject -- and that
# check-sim-xstate.sh reported a false positive from `grep -q` under
# `set -o pipefail`. Neither would have surfaced from reading the scripts.
#
# A guard that has never been seen to fail is a comment that runs.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

fail=0
missing=""
n=0

for f in .github/scripts/*.sh; do
  # this guard is itself in scope -- it carries both markers above
  n=$(( n + 1 ))
  d=$(command grep -c 'DISCIPLINE:' "$f" || true)
  r=$(command grep -c 'PROVED RED' "$f" || true)
  if [ "$d" -eq 0 ] || [ "$r" -eq 0 ]; then
    lack=""
    [ "$d" -eq 0 ] && lack="DISCIPLINE:"
    [ "$r" -eq 0 ] && lack="$lack${lack:+ and }PROVED RED"
    missing="$missing        $(basename "$f") — no $lack"$'\n'
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "  FAIL guard(s) with no recorded discipline or red proof:"
  printf '%s' "$missing"
  echo "       Item 111's rule: a guard carries a DISCIPLINE: header naming the"
  echo "       practice it follows, and a PROVED RED line recording the defect it"
  echo "       was demonstrated against. Write the line by actually running the"
  echo "       guard against a defect -- that is the part that finds things."
  exit 1
fi

echo "  $n guards, all carrying DISCIPLINE: and PROVED RED"
