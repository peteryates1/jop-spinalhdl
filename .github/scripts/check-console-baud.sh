#!/usr/bin/env bash
#
# REGRESSION TEST: an unknown baud must STOP the console targets, not fall back
# to a constant.
#
# fpga/console.mk derives BAUD from the build summary with
#   BAUD ?= $(shell grep -h 'UART baud' ... 2>/dev/null | ...)
# which yields EMPTY whenever the summary is missing or carries no UART line --
# and StandaloneBuild omits that line by design for a design with no UART. The
# empty variable then expands to nothing, the positional argument vanishes, and
# download.py supplies its own 2000000 default. On a 1 Mbaud design that prints
# garbage, which is indistinguishable from a board that never booted.
#
# console.mk's own header says it exists to stop exactly this: twelve board
# Makefiles each carried their own BAUD_RATE constant and two boards refused to
# download in one session because a constant disagreed with the config. The
# constant was not eliminated -- it moved into download.py, where it is
# invisible to anyone reading the Makefile.
#
# WHY THIS MATCHES A DISTINCTIVE PHRASE AND NOT JUST THE EXIT CODE, OR THE WORD
# "baud". `make require-baud` against the unfixed tree also exits non-zero, with
#   No rule to make target 'require-baud'
# -- which contains the word "baud", so both an exit-code test AND a naive
# `grep -i baud` pass for the wrong reason before the fix exists. Observed while
# writing this. The assertion is on a phrase only the guard itself can emit.
#
# Usage: .github/scripts/check-console-baud.sh
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
fail=0

# The phrase the guard must emit. Deliberately not a substring of any make
# diagnostic, and not of the target name.
GUARD_PHRASE='baud rate is not known'

# console.mk is included by quartus.mk and vivado.mk as well as directly, so
# every board folded onto either shared flow has console targets. Listing only
# the direct includers tested 5 boards and missed the rest.
boards=$(grep -lE 'include \.\./(console|quartus|vivado)\.mk' fpga/*/Makefile \
         | sed 's|fpga/||;s|/Makefile||')

for board in $boards; do
  printf '  %-30s ' "$board"

  # An empty BAUD must stop the flow, and say why.
  out=$(make -C "fpga/$board" require-baud BAUD= 2>&1)
  rc=$?

  if [ "$rc" -eq 0 ]; then
    echo "FAIL — an empty BAUD was accepted"
    fail=1
    continue
  fi
  if ! grep -qF "$GUARD_PHRASE" <<<"$out"; then
    echo "FAIL — refused, but not by the baud guard:"
    sed 's/^/      /' <<<"$out" | head -3
    fail=1
    continue
  fi

  # A known baud must pass, or the guard is just breaking the console.
  if ! make -C "fpga/$board" require-baud BAUD=115200 >/dev/null 2>&1; then
    echo "FAIL — a valid BAUD was rejected"
    fail=1
    continue
  fi

  echo "ok"
done

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

A console target accepts an unknown baud. It will reach download.py with the
argument missing, and download.py will silently substitute 2000000 -- which
reads as a dead board rather than as a misconfiguration.

The baud comes from the build. If the build did not state one, stop.
EOF
  exit 1
fi

echo "  all console flows refuse an unknown baud"

# COVERAGE, STATED RATHER THAN ASSUMED. A board with its own download target
# does not import the guard, so a green run above says nothing about it. Report
# those instead of letting the pass imply a completeness it does not have.
uncovered=""
for mk in fpga/*/Makefile; do
  board=$(basename "$(dirname "$mk")")
  grep -qE 'include \.\./(console|quartus|vivado)\.mk' "$mk" && continue
  grep -qE '^(download|monitor|reset|redownload):' "$mk" && uncovered="$uncovered $board"
done
if [ -n "$uncovered" ]; then
  echo "  note: own console, NOT covered by this guard:$uncovered"
  echo "        (colorlight-i5 hardcodes BAUD := 1000000 — status item 70)"
fi
