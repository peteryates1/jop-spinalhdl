#!/usr/bin/env bash
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
