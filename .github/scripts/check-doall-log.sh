#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-11-05 on JopDcuCacheSim's log at 457c426, which announced
#   `DoAll: 68 tests` and then printed its own "PASS: All 59 tests pass":
#   `1 of 68 tests printed ok`. And on the baseline's log cut after test 59
#   (59 of 68), and cut at test 34 then printed twice as the sim does (34 of
#   68) -- a raw count passed that one with 68. Green on the baseline's and
#   the microcode-fallback sim's whole logs at the same commit: 68 of 68.
# ---------------------------------------------------------------------------
# DoAll's verdict, read out of a JVM-suite simulation's log. Usage:
#
#   check-doall-log.sh sim.log [name]
#
# CI's three JVM-suite jobs are App sims: two exit 0 whatever DoAll printed,
# so this is their verdict. It is a script rather than a workflow step so that
# it can be run, and proved, against a real log on a workstation.
#
# THE RULE IS hw_verify.py's (judge, judge_count), so a sim and a board pass
# the same suite: DoAll prints `DoAll: N tests` before it starts, then
# `<name> ok` or `<name> failed!` per test, and no summary line. So: the
# announcement, at least N `ok` lines, no `failed!`, no uncaught exception.
# hw_verify counts `fail` as a word anywhere; a sim log also carries sbt's,
# SpinalHDL's and Verilator's output, so this keeps to DoAll's own markers.
#
# WHAT IT REPLACED (item 177). The step this came from failed on a `failed!`
# line or on no results at all, and otherwise passed. So a suite that stopped
# part-way passed, and one did: JopDcuCacheSim, the all-compute-units job,
# stopped at a count of 59 written into it, and DoAll has run 68 tests since
# well before -- its last nine never ran in that configuration on any push.
# ---------------------------------------------------------------------------
set -uo pipefail

log=${1:?usage: check-doall-log.sh sim.log [name]}
name=${2:-DoAll}
[ -f "$log" ] || { echo "::error::$name: no log at $log"; exit 2; }

announced=$(grep -oE 'DoAll: [0-9]+ tests' "$log" | head -1 | grep -oE '[0-9]+' || true)
# DISTINCT lines. A sim may print the suite twice -- live, and again as its
# whole UART output at the end (JopJvmTestsBramSim does) -- so a run that
# stopped half-way shows N raw `ok` lines. DoAll's test names are distinct.
oks=$(grep -E '[A-Za-z0-9_)] ok[[:space:]]*$' "$log" | sort -u | wc -l)
fails=$(grep -c 'failed!' "$log" || true)
crashed=$(grep -c 'Uncaught exception' "$log" || true)
echo "announced=${announced:-none} ok=$oks failed=$fails uncaught=$crashed"

# The seed is what makes these sims reproducible: item 29 was pinned down only
# by re-running with the seed from the failing job. -?: seeds are signed 32-bit
# and negative ones are common; without the sign this prints a different seed,
# and replaying it reproduces the wrong run (the 2026-08-15 baseline failure
# was seed -748081925).
grep -oE 'with seed -?[0-9]+' "$log" | head -1 || true

if [ -z "$announced" ]; then
  echo "::error::$name: DoAll never announced its test count -- the suite did not start"
  tail -40 "$log"
  exit 1
fi
if [ "$fails" -ne 0 ] || [ "$crashed" -ne 0 ]; then
  echo "::error::$name: $fails test(s) failed, $crashed uncaught exception(s)"
  grep -E 'failed!|Uncaught exception' "$log"
  exit 1
fi
if [ "$oks" -lt "$announced" ]; then
  echo "::error::$name: $oks of $announced tests printed ok -- the suite did not complete"
  tail -40 "$log"
  exit 1
fi
echo "DoAll: $oks ok of $announced tests"
