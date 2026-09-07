#!/usr/bin/env bash
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
  grep -qE 'JopSimDefaults|simWave' "$f" && continue
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
