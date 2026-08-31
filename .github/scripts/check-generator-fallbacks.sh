#!/usr/bin/env bash
#
# REGRESSION TEST: a generator main must not fall back to a named board.
#
# Five constraint generators resolved a missing preset argument to a hard-coded
# board name -- ep4cgx150Serial, wukongSdram, colorlightI5Sdram. Each then wrote
# a WELL-FORMED constraint file for the wrong board into the path --write names,
# and the build succeeded. A comment in XdcGenerator records that this already
# fired once, via an argument-index bug that shifted the preset out of args(0).
#
# QsfGeneratorMain was worse: it never called resolvePreset at all, so any
# preset name passed was discarded and EP4CGX150 pins were emitted regardless.
#
# The failure mode is silence. Nothing crashes, no tool complains, and the wrong
# pins reach a real board -- which is the same shape as the .sdc that was never
# regenerated. A missing preset must stop the build.
#
# WHY A GREP AND NOT A BEHAVIOURAL TEST. Invoking six sbt mains costs minutes
# and needs a full compile; this needs neither and runs in CI on every push. It
# asserts the SHAPE of the argument handling, which is what actually regressed.
# The behavioural half is covered by the mains' own `sys.error` paths.
#
# Usage: .github/scripts/check-generator-fallbacks.sh
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
fail=0

# SCAN EVERY MAIN THAT WRITES A GENERATED ARTEFACT, not just jop/generate.
# This globbed only jop/generate and therefore printed "no generator falls back
# to a named board" while JopTopVerilog.scala:334 -- the main that writes both
# the Verilog AND the build summary -- carried exactly the forbidden pattern in
# jop/system. A guard whose claim is wider than its glob is worse than no guard,
# because it is quoted as evidence. Found by the B4 boundary review, item 110.
gen_dirs="spinalhdl/src/main/scala/jop/generate spinalhdl/src/main/scala/jop/system"

# 1. No `.getOrElse("<something that looks like a preset>")` when resolving the
#    preset argument. Matches a quoted identifier, which is what a board name
#    is; a getOrElse to "" or to a computed value is not this defect.
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  echo "  FAIL $hit"
  echo "       a missing preset must fail, not resolve to a named board"
  fail=1
done < <(grep -nE '(preset|presetArgs|args)[A-Za-z]*\.headOption\.getOrElse\("[A-Za-z][A-Za-z0-9]*"\)' \
           $(for d in $gen_dirs; do echo $d/*.scala; done) 2>/dev/null)

# 2. Every *Main that takes a preset must actually resolve one. A main that
#    assigns a JopConfig preset directly is ignoring its arguments.
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  echo "  FAIL $hit"
  echo "       assigns a preset directly — the argument is being discarded"
  fail=1
# UtilSweep and AlteraUtilSweep are EXEMPT and must stay so: their argument is a
# sweep LABEL ("baseline", "no_icu", ...), not a preset, so hardcoding the base
# config is correct rather than a discarded argument. Exempting by name with a
# reason beats loosening the pattern, which would only misfire somewhere else.
done < <(grep -nE 'val +(base|config|cfg) += +JopConfig\.[a-zA-Z0-9]+ *$' \
           $(for d in $gen_dirs; do echo $d/*.scala; done) 2>/dev/null \
         | grep -vE '(UtilSweep|AlteraUtilSweep)\.scala:')

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

A generator resolves a missing or ignored preset to a named board. It will
write a valid constraint file for the WRONG board into the path --write names,
and the build will succeed.

Use sys.error to refuse, or call resolvePreset on the argument.
EOF
  exit 1
fi

echo "  no generator falls back to a named board"
