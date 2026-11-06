#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-11-06 at f261717, before the sims were converted: it named
#   the nine files that ran DoAll on a rule of their own (JopJvmTestsBramSim,
#   -LmulSwSim, -McFallbackSim, -McFcmpSim, -SmpBramSim, JopGenJvmTestsBramSim,
#   JopStackCacheSim, JopTopDcuBramSim, JopTopJvmTestsBramSim) and passed the
#   two that judge (JopConfigBramSim, JopDcuCacheSim). And it named a probe
#   file that loads DoAll and mentions SimVerdict.doAll only in a comment.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: every simulation that runs DoAll judges it by
# jop.sim.SimVerdict.doAll.
#
# DoAll announces `DoAll: N tests`, then prints `<name> ok` or `<name> failed!`
# per test, and has no summary line. A sim that judges it any other way gets it
# wrong in the direction that passes. Items 176 and 177 counted eleven DoAll
# sims: most printed what they saw and exited 0; one passed on any `ok` with no
# `failed!`; and CI's all-compute-units sim stopped at a count written into it,
# 59, nine tests short of the suite. SimVerdict.doAll is hw_verify.py's rule
# with the count taken from DoAll, and SimVerdictTest holds it to hw_verify's
# own cases.
#
# THE CHECK. A test source that loads DoAll -- the literal "DoAll" outside a
# comment -- must call SimVerdict.doAll outside a comment. It is per FILE, so a
# file with several objects passes if one of them judges: JopStackCacheSim.scala
# holds JopStackCacheWaveSim, a waveform capture that stops part-way through
# DoAll on purpose, beside JopJvmTestsStackCacheBramSim, which judges.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.."

offenders=""
n=0
while IFS= read -r f; do
  body=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$f")
  grep -qE '"DoAll"' <<<"$body" || continue
  n=$(( n + 1 ))
  grep -qE 'SimVerdict\.doAll' <<<"$body" && continue
  offenders="$offenders $f"
done < <(grep -rl '"DoAll"' spinalhdl/src/test/scala --include='*.scala')

if [ -n "$offenders" ]; then
  echo "  FAIL simulation(s) that run DoAll without judging it by SimVerdict.doAll:"
  for f in $offenders; do echo "         $f"; done
  echo "       DoAll's verdict is its own count (jop.sim.SimVerdict.doAll): print"
  echo "       PASS or FAIL from what it returns, and exit 1 on a FAIL."
  exit 1
fi
[ "$n" -gt 0 ] || { echo "  FAIL no simulation loads DoAll -- this guard is looking in the wrong place"; exit 1; }
echo "  every simulation that runs DoAll judges it by SimVerdict.doAll ($n files)"
