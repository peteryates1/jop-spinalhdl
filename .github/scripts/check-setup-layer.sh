#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-11-01 (status item 173, step 1) against the tree as it
# stood, with the setup files written but the tools not yet switched to them:
# it named jtag_probe_map and usb_serial_map (their heredoc registries),
# colorlight-i5/Makefile (a hardcoded by-id fallback) and cyc5000-sdram/Makefile
# (a serial in a comment). Also red on the no-parse tripwire (JOP_SETUP at an
# empty directory).
# EXTENDED 2026-11-01 (item 173, step 2): units too. PROVED RED on the tree
# with setup/units.toml written but jop/setup/Instances.scala still defining
# the eight units (it named Instances.scala's quoted unit names), and on a
# unit's MAC pasted into a board Makefile.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: the generic and hardware layers carry no SETUP facts.
#
# Status item 173: JOP is three layers -- generic, hardware support, and one
# lab's setup -- and dependencies point only downward, so the setup can move
# to a repository of its own and another lab can supply theirs. The setup is
# the TOML in setup/ (fpga/scripts/jop_setup.py reads it). Before 2026-11-01
# this lab's probe and console serials lived in the generic tools themselves,
# as heredoc tables inside jtag_probe_map and usb_serial_map, and leaked from
# there into board Makefiles.
#
# The checks:
#   - no serial number the setup lists appears in any file outside setup/ and
#     the records (docs/, *.md). Serials shorter than 8 characters are
#     skipped: the Alchitry's FT2232H reports 000000, which names nothing.
#   - no unit is DEFINED outside setup/: none of its MACs appears there, and
#     its name appears in no source as a whole string literal ("jop-x-1").
#     A name in a comment or a usage example is a reference, not a
#     definition, and stays legal. The scope leaves out spinalhdl/src/test:
#     tests that pin this lab's units (InstanceTest) are the setup's
#     acceptance tests, and would move with it.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

SETUP_TOOL=fpga/scripts/jop_setup.py

rows=$(python3 "$SETUP_TOOL" table probes) || { echo "  FAIL $SETUP_TOOL could not read the probes"; exit 1; }
crows=$(python3 "$SETUP_TOOL" table consoles) || { echo "  FAIL $SETUP_TOOL could not read the consoles"; exit 1; }

serials=$( { cut -d'|' -f2 <<<"$rows"; cut -d'|' -f4 <<<"$crows"; } |
           awk 'length($0) >= 8' | sort -u)
n=$(command grep -c . <<<"$serials" || true)
if [ "$n" -eq 0 ]; then
  echo "  FAIL read no serials from the setup ($(python3 "$SETUP_TOOL" where 2>&1))"
  echo "       This guard is asserting nothing."
  exit 1
fi

leaks=""
while read -r s; do
  hits=$(git grep --untracked -n -i -F -e "$s" -- . ':!setup/' ':!docs/' ':!*.md' 2>/dev/null)
  [ -n "$hits" ] && leaks="$leaks$(command sed "s/^/        [$s] /" <<<"$hits")"$'\n'
done <<<"$serials"

if [ -n "$leaks" ]; then
  echo "  FAIL setup facts outside the setup layer -- these serials belong in setup/*.toml:"
  printf '%s' "$leaks" | cut -c1-200
  echo "       A tool or Makefile resolves an ALIAS through jtag_probe_map or"
  echo "       usb_serial_map (they read setup/ via fpga/scripts/jop_setup.py)."
  echo "       A serial written anywhere else is this lab's fact in a layer every"
  echo "       other lab shares, and it goes stale when a probe moves."
  exit 1
fi

urows=$(python3 "$SETUP_TOOL" table units) || { echo "  FAIL $SETUP_TOOL could not read the units"; exit 1; }
names=$(cut -d'|' -f1 <<<"$urows" | command grep .)
macs=$(cut -d'|' -f3 <<<"$urows" | tr ',' '\n' | command grep .)
u=$(command grep -c . <<<"$names" || true)
if [ "$u" -eq 0 ]; then
  echo "  FAIL read no units from the setup -- this check is asserting nothing"
  exit 1
fi
defs=""
while read -r name; do
  hits=$(git grep --untracked -n -F -e "\"$name\"" -- . ':!setup/' ':!docs/' ':!*.md' ':!spinalhdl/src/test/' 2>/dev/null)
  [ -n "$hits" ] && defs="$defs$(command sed "s/^/        [$name] /" <<<"$hits")"$'\n'
done <<<"$names"
while read -r mac; do
  [ -n "$mac" ] || continue
  hits=$(git grep --untracked -n -i -F -e "$mac" -- . ':!setup/' ':!docs/' ':!*.md' ':!spinalhdl/src/test/' 2>/dev/null)
  [ -n "$hits" ] && defs="$defs$(command sed "s/^/        [$mac] /" <<<"$hits")"$'\n'
done <<<"$macs"
if [ -n "$defs" ]; then
  echo "  FAIL units defined outside the setup layer -- they belong in setup/units.toml:"
  printf '%s' "$defs" | cut -c1-200
  echo "       Code reaches a unit through jop.setup.Instances (Scala) or"
  echo "       fpga/scripts/jop_setup.py, which read setup/units.toml."
  exit 1
fi

echo "  $n setup serials and $u units, none outside setup/"
