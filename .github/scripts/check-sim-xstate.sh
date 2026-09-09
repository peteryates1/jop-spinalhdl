#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-09-09 on five cases: a bare `SimConfig`; a file naming
# JopSimDefaults only in a COMMENT; a file calling JopSimDefaults.workspace but
# applying no flag; and, as must-not-fire controls, JopSimDefaults.config and a
# direct `--x-initial 0`. The middle two were live defects in this guard: it
# exempted 14 real offenders, and a `grep -q` behind `set -o pipefail` reported
# a FALSE POSITIVE on BytecodeFetchStageTest, which uses simWave correctly.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: every Verilator simulation must start its registers at ZERO.
#
# Verilator gives a register with no reset a RANDOM initial value drawn from
# the simulation seed; this design has ~405 such registers. An FPGA does not do
# this -- it powers up at zero -- so a seed-dependent failure is a simulator
# artefact, not a hardware bug. `--x-initial 0` removes the entire class, and
# `jop.utils.JopSimDefaults` is where that flag is applied.
#
# X-state has already been the SINGLE root cause of three separate CI flakes:
# items 29, 30 and 32. Each was closed by routing ONE sim through the defence.
# The class was never closed, because 64 sims still built their own config from
# a bare `SimConfig` and took Verilator's randomisation.
#
# It bit again on 2026-09-07: the scheduled run of 44fd48e failed
# `readme-walkthrough` step 7 with
#
#     Per-core WD toggles: C0=0 C1=0
#     FAIL: Did not see 'NCoreHelloWorld' from core 0
#
# -- neither core toggled its watchdog, so nothing ran at all -- and the same
# job passed on the next push. `JopSmpNCoreHelloWorldSim` lives in
# JopSmpBramSim.scala, which used a bare `SimConfig`.
#
# WHY A STRUCTURAL GUARD AND NOT A REPLAY. A seed names an initial state only
# relative to a FIXED netlist and a FIXED Verilator build; CI runs 5.020 and a
# Debian workstation runs 5.032, so feeding CI's seed to a local run reproduces
# nothing and a clean local sweep proves nothing. Catching a probabilistic
# failure is not a test. Asserting that no sim can be built without the defence
# is deterministic, runs in a second, and cannot pass for the wrong reason.
#
# ESCAPE HATCH is unchanged: JOP_SIM_XINIT=random restores randomisation when
# you WANT to hunt missing resets.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.."

fail=0

# A sim is "unprotected" if it builds a config from a bare `SimConfig` without
# routing it through JopSimDefaults (directly, or via TestVectorUtils.simWave,
# which applies JopSimDefaults.xInitial). Files that never compile a model are
# not sims -- helpers and config case classes match the name but build nothing.
offenders=""
while IFS= read -r f; do
  grep -qE '\.compile|\.doSim' "$f" || continue
  # NON-COMMENT LINES ONLY. This was a plain `grep -qE ... "$f"`, so a file was
  # exempted by MENTIONING JopSimDefaults in a COMMENT. MemoryOpTest.scala
  # built a bare SimConfig and was skipped by the comment explaining why it
  # could not use JopSimDefaults -- the guard printed "every Verilator
  # simulation starts its registers at zero" while that file randomised.
  # A guard whose exemption matches prose exempts whatever talks about it.
  # THE DEFENCE, NOT A MENTION OF IT. Three ways to get it: JopSimDefaults's
  # `config`/`xInitial`, TestVectorUtils.simWave (which calls xInitial), or the
  # flag applied directly -- the only option for a file in src/main, since
  # JopSimDefaults lives in src/test.
  #
  # Two earlier spellings of this test were both wrong:
  #   * a plain `grep -qE 'JopSimDefaults|simWave' "$f"` exempted a file for
  #     MENTIONING the name IN A COMMENT -- MemoryOpTest.scala was skipped by
  #     the comment explaining why it could not use JopSimDefaults;
  #   * matching the bare name `JopSimDefaults` exempts a file that only calls
  #     `JopSimDefaults.workspace`, which sets the output directory and applies
  #     no flag at all. SdNativeTest.scala did exactly that.
  # Match the defence itself.
  #
  # NO PIPE INTO `grep -q`. Under `set -o pipefail`, `-q` exits at the first
  # match, the upstream grep takes SIGPIPE, and the PIPELINE reports non-zero --
  # so the exemption never fired and BytecodeFetchStageTest.scala, which uses
  # simWave correctly, was reported as an offender. A false positive still looks
  # like a working guard: the planted-offender red test passed throughout.
  body=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$f")
  grep -qE 'JopSimDefaults\.(config|xInitial)|simWave\(|--x-initial 0' <<<"$body" && continue
  # a bare `SimConfig` token, not `MainMemorySimConfig` or a local `simConfig` val
  grep -qE '(^|[^A-Za-z0-9_.])SimConfig([^A-Za-z0-9_]|$)' "$f" || continue
  offenders="$offenders $f"
  fail=1
done < <(grep -rl 'SimConfig' spinalhdl/src --include='*.scala')

if [ "$fail" -ne 0 ]; then
  echo "  FAIL these simulations build a Verilator model without the X-state defence:"
  for f in $offenders; do echo "        $f"; done
  echo "       Verilator randomises every unreset register from the seed, so each"
  echo "       of these can fail for a reason that does not exist in hardware."
  echo "       Route the config through jop.utils.JopSimDefaults:"
  echo "           import jop.utils.JopSimDefaults"
  echo "           JopSimDefaults.config.compile(...)"
  echo "       or, for a unit test, TestVectorUtils.simWave(SimConfig)."
  exit 1
fi

echo "  every Verilator simulation starts its registers at zero"
