#!/usr/bin/env bash
#
# DISCIPLINE: docs/testing-discipline.md — "verify against the artefact".
# PROVED RED 2026-09-04 by dropping `$(REV).sdc: $(GEN_STAMP)` from fpga/quartus.mk — it named three boards.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
#
# REGRESSION TEST: a config change must reach the GENERATED CONSTRAINTS, not
# just the RTL.
#
# fpga/quartus.mk wrote the .sdc and setup_proj.tcl from rules with NO
# PREREQUISITES, so they were build-once artefacts: written on the first build
# and never regenerated, whatever changed underneath. $(GEN_STAMP) depends on
# $(SCALA_SRC), so the RTL re-elaborated on any Scala change -- but the two
# generators producing the PINS, the device string, TOP_LEVEL_ENTITY, the I/O
# standards and the CLOCK PERIOD did not. Changing a pin in Board.scala and
# running `make build` therefore SUCCEEDED, fitting new RTL against the previous
# run's assignments, and quartus_sta reported timing met against the wrong
# constraint. Nothing in the flow noticed.
#
# WHY THIS ASSERTS THE RULE DATABASE AND NOT AN OBSERVED REBUILD. The obvious
# test -- touch a Scala file, run `make -n`, look for the generators -- passes
# for the WRONG REASON on a cold tree, where every target is out of date and
# everything runs regardless. It also passes on a warm tree whose .jop is
# missing, because $(GEN_STAMP) is then permanently stale and cascades. Both
# were observed while writing this. `make -p` reports the dependency edge
# itself, which is the thing that actually has to hold.
#
# Usage: .github/scripts/check-generated-deps.sh
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
fail=0

# Every board that folds onto the shared Quartus flow.
boards=$(grep -ln 'include ../quartus.mk' fpga/*/Makefile | sed 's|fpga/||;s|/Makefile||')

for board in $boards; do
  printf '  %-30s ' "$board"

  db=$(make -C "fpga/$board" -p -n 2>/dev/null)
  if [ -z "$db" ]; then
    echo "SKIP (make could not read this board)"
    continue
  fi

  # Which branch of quartus.mk is live? A board driving a standalone
  # BoardDesign sets GEN_MAKES_PROJECT=yes and its generator emits the .sdc and
  # the project Tcl in one pass, so those two rules do not exist -- there the
  # edge that matters is .qsf -> .generated.
  sdc_rule=$(grep -E '^[^ |#]*\.sdc:' <<<"$db" | head -1)
  tcl_rule=$(grep -E '^[^ |#]*setup_proj\.tcl:' <<<"$db" | head -1)

  problems=""
  if [ -n "$sdc_rule" ] || [ -n "$tcl_rule" ]; then
    for rule in "$sdc_rule" "$tcl_rule"; do
      [ -z "$rule" ] && continue
      target=${rule%%:*}
      prereqs=${rule#*:}
      # Whitespace-only prerequisites means the rule fires once and never again.
      if [ -z "${prereqs// /}" ]; then
        problems="$problems $(basename "$target")"
      fi
    done
  else
    qsf_rule=$(grep -E '^[^ |#]*\.qsf:' <<<"$db" | head -1)
    if [ -n "$qsf_rule" ]; then
      prereqs=${qsf_rule#*:}
      [ -z "${prereqs// /}" ] && problems=" $(basename "${qsf_rule%%:*}")"
    fi
  fi

  if [ -n "$problems" ]; then
    echo "FAIL — generated with no prerequisites:$problems"
    fail=1
  else
    echo "ok"
  fi
done

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

A generated project or constraint file has no prerequisites, so it is written
once and never regenerated. A later config change will reach the RTL and not
the constraints, and the build will succeed while being wrong.

Give the rule the same dependency $(GEN_STAMP) carries.
EOF
  exit 1
fi

echo "  all Quartus flows regenerate their constraints"

# ---------------------------------------------------------------------------
# No board may shadow a rule from a shared include.
#
# fpga/qmtech-xc7a100t-wukong/Makefile redefined `generate:`, which
# fpga/vivado.mk also defines. Make keeps the LAST recipe and accumulates the
# prerequisites of both, so `make ddr3-smp-generate` re-entered with
# CFG="wukongSmp 4", satisfied the stamp for that config, and then ran the
# board's own recipe -- hardcoded to $(BRAM_CFG) -- elaborating wukongBram on
# top. Make had been printing "overriding recipe for target" on every build of
# that board for as long as it existed, and nobody read it.
#
# A warning that is normal is a warning nobody reads, so this makes it fatal.
# ---------------------------------------------------------------------------
shadow_fail=0
for mk in fpga/*/Makefile; do
  board=$(basename "$(dirname "$mk")")
  warns=$(make -C "fpga/$board" -n -p 2>&1 >/dev/null \
          | grep -E 'warning: (overriding|ignoring old) recipe' | head -4)
  if [ -n "$warns" ]; then
    echo "  FAIL $board shadows a shared rule:"
    sed 's/^/       /' <<<"$warns"
    shadow_fail=1
  fi
done

if [ "$shadow_fail" -ne 0 ]; then
  cat <<'EOF'

A board Makefile redefines a target that a shared include also defines. Make
keeps the last recipe but the prerequisites of BOTH, so the surviving recipe can
run against a config it was not written for -- silently, since it succeeds.

Rename the board-local target, or make it honour $(CFG) so the shared rule can
stand on its own.
EOF
  exit 1
fi

echo "  no board shadows a shared rule"

# ---------------------------------------------------------------------------
# A generated fragment must be sourced from where it is written.
#
# MigProfile.emit writes the clk_wiz frequency to
#   build/ip/<board>/generated/ddr3_clocks.tcl
# while create_ddr3_clk_wiz.tcl sourced
#   <script dir>/../ip/generated/ddr3_clocks.tcl
# i.e. fpga/<board>/vivado/ip/generated/ -- a directory that has never existed.
# The `if {[file exists $_gen]}` guard was therefore always false and the
# literal `set ddr3_clkwiz_mhz 100.000` always won.
#
# Harmless on the default preset, which IS 100 MHz. Live for the three presets
# that can carry a non-default MigProfile (wukongDdr3Smp, wukongDdr3SmpMshr,
# wukongDualIndependentSmp): the clk_wiz would be built at 100 MHz while MIG was
# tuned for another sys_clk, and the memory clock lands off target WHILE STILL
# BUILDING CLEANLY -- the exact hazard the script's own comment describes.
# ---------------------------------------------------------------------------
tcl=fpga/qmtech-xc7a100t-wukong/vivado/tcl/create_ddr3_clk_wiz.tcl
mk=fpga/qmtech-xc7a100t-wukong/Makefile
if [ -f "$tcl" ]; then
  if ! grep -q 'JOP_MIG_TCL' "$tcl"; then
    echo "  FAIL $tcl does not take the fragment path from the environment"
    echo "       it must not guess where MigProfile.emit wrote ddr3_clocks.tcl"
    exit 1
  fi
  if ! grep -q 'JOP_MIG_TCL' "$mk"; then
    echo "  FAIL $mk does not set JOP_MIG_TCL for create_ddr3_clk_wiz.tcl"
    exit 1
  fi
  echo "  the MIG clk_wiz fragment is sourced from where it is written"
fi

# ---------------------------------------------------------------------------
# Const.java must depend on everything that decides an I/O address.
#
# Status item 120, from the B2 boundary review. `java/Makefile` listed
# ConstGenerator.scala, JopConfig.scala and JopCoreConfig.scala -- and none of
# the files that actually assign addresses. The RTL regenerates from Scala on
# every build; Const.java did not, so moving a device or changing an addrBits
# left the hardware in one place and the constants in another.
#
# That is the same defect as the .sdc above, one directory over: a generated
# artefact whose prerequisites omit an input that determines its content. Its
# failure is TROUBLESHOOTING.md's first entry -- reads and writes land on the
# wrong device and the board looks dead.
# ---------------------------------------------------------------------------
const_rule=$(make -C java -p -n 2>/dev/null \
             | grep -E '^[^ |#]*runtime/src/jop/com/jopdesign/sys/Const\.java:' | head -1)
if [ -n "$const_rule" ]; then
  missing=""
  for f in IoAddressAllocator.scala DeviceTypes.scala DeviceInstance.scala JopMemoryConfig.scala; do
    grep -qF "$f" <<<"$const_rule" || missing="$missing $f"
  done
  if [ -n "$missing" ]; then
    echo "  FAIL Const.java does not depend on:$missing"
    echo "       these decide I/O addresses; without them it goes silently stale"
    exit 1
  fi
  echo "  Const.java depends on everything that decides an I/O address"
fi

# ---------------------------------------------------------------------------
# 2. THE APP'S .jop MUST DEPEND ON THE TOOL THAT PRODUCES IT — status item 140.
#
# `make -C java/apps/<X>` ran JOPizer from whatever tools/dist/jopizer.jar
# happened to be on disk. A change under java/tools/src compiled into nothing:
# timestamps fresh, exit 0, and the .jop emitted by the OLD linker. It cost two
# wrong conclusions during item 136, and was caught only because a result was
# impossible.
#
# ASSERTED WITHOUT RUNNING MAKE. The obvious check -- `make -pn jop | grep
# tools-fresh` -- reads the real rule database, but `-pn` still executes
# sub-makes, so it recurses into java/tools, emits a 200-line dump and gives an
# ORDER-DEPENDENT answer: it reported PerfTest as failing on one run and passing
# on the next, with the edge present both times. A guard that is flaky is worse
# than none. This checks the three facts that produce the edge instead, which is
# deterministic and needs no build.
# ---------------------------------------------------------------------------
echo
printf '  %-30s ' "java/config.mk"
if grep -qE '^jop: *tools-fresh' java/config.mk && grep -qE '^tools-fresh:' java/config.mk; then
  echo "declares jop: tools-fresh"
else
  echo "FAIL — java/config.mk no longer declares 'jop: tools-fresh'"
  echo "       every app links with a jar nothing rebuilds"
  fail=1
fi

for mk in java/apps/*/Makefile; do
  app=$(basename "$(dirname "$mk")")
  printf '  %-30s ' "$app"
  if ! grep -qE '^include .*config\.mk' "$mk"; then
    echo "FAIL — does not include config.mk, so gets no tools dependency"; fail=1; continue
  fi
  if ! grep -qE '^jop:' "$mk"; then
    echo "FAIL — no 'jop' target for config.mk's prerequisite to attach to"; fail=1; continue
  fi
  echo "ok"
done

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

An app links with a jar nothing rebuilt. A change to JOPizer or PreLinker will
compile into nothing, the build will exit 0, and the .jop will be produced by
the previous linker.

java/config.mk declares `jop: tools-fresh` for every app that includes it.
EOF
  exit 1
fi
