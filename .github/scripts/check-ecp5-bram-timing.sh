#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "test the DEPENDENCY, not just the feature".
# PROVED RED 2026-11-02: see the record at the end of this header.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: every ECP5 board flow synthesises with the block RAM's
# clock-to-out (status item 175).
#
# yosys's DP16KD model has no timing, so ABC9 deepened the i5's RAM-fed fetch
# path to match unrelated logic: the same fetch RTL routed at 41.61 MHz
# without Ethernet and 50.72 MHz with it. fpga/ecp5_dp16kd.v supplies the
# delay and fpga/ecp5.mk applies it. Each way of losing that still
# synthesises, routes and passes at 40 MHz -- only slower -- so no build
# would notice:
#
#   1. a board flow runs its own synth_ecp5 again (a copied rule, a new board);
#   2. the netlist rule loses $(ECP5_DP16KD) as a prerequisite, so a new delay
#      never re-synthesises -- asserted on `make -p`, the edge itself, not on
#      an observed rebuild;
#   3. the model drifts from the installed yosys's DP16KD. A parameter the
#      mapping sets that the copy lacks fails synthesis outright; a changed
#      default or a missing arc does not.
#
# And one that hides any failure at all: a flow that pipes nextpnr into tee
# gets tee's exit status, so a failed place and route goes on to pack
# whatever placement is already on disk -- seen 2026-11-02 in
# fpga/colorlight-i5, where only a missing .config stopped it.
#
# The subjects are every Makefile under fpga/ that places for the ECP5 (calls
# nextpnr-ecp5), not the ones that include ecp5.mk: a guard that enumerates
# its subjects by the fix decides what it is allowed to find. Bring-up designs
# are declared below.
#
# PROVED RED 2026-11-02, each named and exit 1:
#   - the tree before the fix: both i5 flows, for running their own
#     synth_ecp5 AND for a netlist that did not depend on the model;
#   - colorlight-db-i5's netlist rule without $(ECP5_DP16KD) (only that);
#   - fpga/ecp5_dp16kd.v with DOB17's arc deleted; with REGMODE_A's default
#     changed to "OUTREG" (the diff against yosys); with DOA3 at 2400 ps;
#   - fpga/colorlight-i5's nextpnr rule as it was, `2>&1 | tee nextpnr.log`.
#
# Usage: .github/scripts/check-ecp5-bram-timing.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

fail=0
MODEL=fpga/ecp5_dp16kd.v

# Bring-up designs are not JOP builds: each is the record of a bring-up, and
# the timing its README reports was measured with yosys's stock model.
# Re-synthesising them would change those records, not fix a flow. A new
# one is declared here, which is the moment to ask whether it should be.
declared="fpga/bringup/colorlight-db-i5-eth/Makefile fpga/bringup/colorlight-i5-idle/Makefile"

subjects=$(git ls-files 'fpga/*Makefile' | while read -r f; do
  command grep -q 'nextpnr-ecp5' "$f" && echo "$f"; done)
if [ -z "$subjects" ]; then
  echo "  FAIL no Makefile under fpga/ calls nextpnr-ecp5 -- the subject search is broken"
  exit 1
fi

# --- 1 and 2: each flow's netlist rule, from make's own database ------------
for mk in $subjects; do
  case " $declared " in
    *" $mk "*) printf '  %-44s declared (bring-up)\n' "$mk"; continue ;;
  esac
  printf '  %-44s ' "$mk"
  db=$(make -C "$(dirname "$mk")" -p -n 2>/dev/null)
  if [ -z "$db" ]; then
    echo "FAIL (make could not read it)"; fail=1; continue
  fi
  # Every .json rule whose recipe synthesises: rule line, comments, recipe,
  # then a blank line.
  rules=$(awk '
    /^[^ #\t][^:]*\.json:/ { blk = $0; inb = 1; next }
    inb && /^$/ { if (blk ~ /yosys|synth_ecp5|ecp5_synth/) print blk "\n@@end-of-rule@@"; inb = 0; next }
    inb { blk = blk "\n" $0 }' <<<"$db")
  n=$(command grep -c "@@end-of-rule@@" <<<"$rules")
  if [ "$n" -ne 1 ]; then
    echo "FAIL -- $n synthesis rules in make's database, expected 1"; fail=1; continue
  fi
  head=$(head -1 <<<"$rules")
  recipe=$(sed -n '/recipe to execute/,$p' <<<"$rules")
  problems=""
  if command grep -q 'synth_ecp5' <<<"$recipe" || ! command grep -q 'ecp5_synth' <<<"$recipe"; then
    problems="$problems runs its own synth_ecp5, not \$(call ecp5_synth) from fpga/ecp5.mk;"
  fi
  if ! command grep -qE "[ :]$(pwd)/$MODEL( |\$)" <<<"$head"; then
    problems="$problems the netlist does not depend on $MODEL;"
  fi
  # A failed place and route must fail the build. Piped into tee, nextpnr's
  # status is tee's, and the next rule packs whatever placement is on disk.
  pnr=$(awk '
    /^[^ #\t][^:]*:/ { blk = $0; inb = 1; next }
    inb && /^$/ { if (blk ~ /nextpnr-ecp5/) print blk; inb = 0; next }
    inb { blk = blk "\n" $0 }' <<<"$db")
  if [ -z "$pnr" ]; then
    problems="$problems no rule runs nextpnr-ecp5;"
  elif command grep -qE '\|[[:space:]]*tee' <<<"$pnr"; then
    problems="$problems pipes nextpnr into tee, which hides its exit status;"
  fi
  if [ -n "$problems" ]; then
    echo "FAIL --$problems"; fail=1
  else
    echo "ok"
  fi
done

# The one recipe they share: the model replaces yosys's DP16KD after
# synth_ecp5's `begin` (which reads the stock library) and before anything else.
order=$(command grep -n -E 'synth_ecp5 -top \$\(1\) -run :coarse|read_verilog -lib -specify -overwrite \$\(ECP5_DP16KD\)|synth_ecp5 -top \$\(1\) -run coarse:' fpga/ecp5.mk | cut -d: -f1 | xargs)
if [ "$(wc -w <<<"$order")" -ne 3 ] || [ "$order" != "$(xargs -n1 <<<"$order" | sort -n | xargs)" ]; then
  echo "  FAIL fpga/ecp5.mk: ecp5_synth must run synth_ecp5 -run :coarse, read the model, then synth_ecp5 -run coarse: (lines: ${order:-none})"
  fail=1
fi

# --- 3: the model ------------------------------------------------------------
# One arc per data output, from that port's own clock, all one positive value.
arcs=$(command grep -E '^\s*\(posedge CLK[AB] => \(DO[AB][0-9]+ : DI[AB][0-9]+\)\) = [0-9]+;' "$MODEL")
expected=$(for p in A B; do for i in $(seq 0 17); do echo "CLK$p DO$p$i"; done; done | sort)
got=$(sed -E 's/.*posedge (CLK[AB]) => \((DO[AB][0-9]+) .*/\1 \2/' <<<"$arcs" | sort)
values=$(sed -E 's/.*\) = ([0-9]+);/\1/' <<<"$arcs" | sort -u)
if [ "$got" != "$expected" ]; then
  echo "  FAIL $MODEL: needs exactly one arc per DOA0..17/DOB0..17 from CLKA/CLKB;"
  diff <(echo "$expected") <(echo "$got") | sed 's/^/         /' | head -6
  fail=1
elif [ "$(wc -l <<<"$values")" -ne 1 ] || [ "$values" -le 0 ]; then
  echo "  FAIL $MODEL: the arcs must share one positive delay, found: $(tr '\n' ' ' <<<"$values")"
  fail=1
else
  echo "  $MODEL: 36 arcs at $values ps"
fi

# Byte for byte yosys's own declaration, around the specify block.
yosys_bin=$(command -v yosys || true)
if [ -z "$yosys_bin" ]; then
  echo "  SKIP the model against yosys's DP16KD: no yosys on this host (only hosts that synthesise need it)"
else
  share=$(dirname "$(readlink -f "$yosys_bin")")/../share/yosys/ecp5
  theirs=$(awk '
    /^\(\* blackbox \*\)$/ { hold = $0; next }
    /^module DP16KD\(/ && hold != "" { p = 1; print hold }
    { hold = "" }
    p { print }
    p && /^endmodule/ { exit }' "$share/cells_sim.v")
  ours=$(awk '
    /^\(\* blackbox \*\)$/ { p = 1 }
    p && /^\tspecify$/ { s = 1 }
    p && !s { print }
    s && /^\tendspecify$/ { s = 0 }
    p && /^endmodule/ { exit }' "$MODEL")
  if [ -z "$theirs" ]; then
    echo "  FAIL no DP16KD declaration found in $share/cells_sim.v"; fail=1
  elif [ "$ours" != "$theirs" ]; then
    echo "  FAIL $MODEL has drifted from $share/cells_sim.v's DP16KD:"
    diff <(echo "$theirs") <(echo "$ours") | sed 's/^/         /' | head -8
    fail=1
  else
    echo "  $MODEL: yosys's own DP16KD declaration ($share)"
  fi
  # The delay is the unregistered RAM's: yosys must still only map to that.
  modes=$(command grep -o -E 'REGMODE_[AB]\("[A-Z]+"\)' "$share/brams_map.v" | sed -E 's/.*"([A-Z]+)".*/\1/' | sort -u)
  if [ "$modes" != "NOREG" ]; then
    echo "  FAIL $share/brams_map.v maps DP16KD with REGMODE $(tr '\n' ' ' <<<"$modes")-- the arcs assume NOREG only"
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

An ECP5 flow can synthesise without the block RAM's clock-to-out, and ABC9
then deepens the RAM-fed fetch path to match whatever else is slowest --
the build still passes at 40 MHz, just with less margin than it should
have (status item 175). Synthesise through $(call ecp5_synth) from
fpga/ecp5.mk with $(ECP5_DP16KD) as a prerequisite; re-copy the model from
the installed yosys if it has drifted.
EOF
  exit 1
fi
echo "  every ECP5 board flow synthesises with the block RAM's clock-to-out"
