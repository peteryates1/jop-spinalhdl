#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "verify against the artefact".
# PROVED RED 2026-11-02 on fpga/colorlight-i5/Makefile as a605433 left it
# (named: "unit-info"), and on fpga/colorlight-db-i5/Makefile with its
# `.DEFAULT_GOAL := all` deleted (named: "unit-info").
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: where a board Makefile's header says a bare `make` builds
# the board, it does.
#
# make's default goal is the first rule it reads, included files and all.
# fpga/colorlight-i5/Makefile documents `make  build the bitstream`, but its
# first rule built the Java app; since 2026-11-01 the first rule it reads is
# fpga/unit.mk's `unit-info` (status item 173, step 3), so a bare `make`
# printed "unit: none" and exited 0 with no bitstream built -- found on
# 2026-11-02 when it did exactly that (item 175). colorlight-db-i5 says
# `.DEFAULT_GOAL := all` and was never affected.
#
# The subjects are the Makefiles that DOCUMENT a bare make (a `#   make   <what
# it builds>` header line). The Quartus and Vivado flows document `make all`
# (README.md); what their bare `make` runs is not a promise, so it is not
# checked here.
#
# The answer comes from make itself, after every include. A target starting
# with '.' is never a default-goal candidate, so the probe rule cannot
# change the answer it reports.
#
# Usage: .github/scripts/check-board-default-goal.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

fail=0
n=0
for mk in $(git ls-files 'fpga/*Makefile'); do
  command grep -q -E '^#[[:space:]]+make[[:space:]]{2,}[^ ]' "$mk" || continue
  n=$((n + 1))
  out=$(make -C "$(dirname "$mk")" -s --no-print-directory \
        --eval '.jop-default-goal: ; @echo "$(.DEFAULT_GOAL)"' .jop-default-goal 2>&1)
  goal=$(tail -1 <<<"$out")
  if [ "$goal" != "all" ]; then
    printf '  FAIL %-44s documents a bare make, which runs "%s", not all\n' "$mk" "${goal:-<nothing>}"
    fail=1
  fi
done

if [ "$n" -eq 0 ]; then
  echo "  FAIL no Makefile under fpga/ documents a bare make -- the subject search is broken"
  exit 1
fi
if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

A bare `make` in that directory does not do what its header says: an included
file's rule, or a rule above `all`, became the default goal. Say
`.DEFAULT_GOAL := all` in the board Makefile (see fpga/colorlight-db-i5).
EOF
  exit 1
fi
echo "  a bare make builds the board in all $n flows that document one"
