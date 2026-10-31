#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-09-11 against Board.scala at 39611af, which is the tree as it
# stood when the CYC5000 and the Alchitry Au could not be programmed. It named
# both defects: "alchitry-au-v2 — consoleAlias 'alchitry' but NO probeAlias" and
# "cyc5000 — probe 'cyc5000' is kind arrow-usb-blaster, tool is quartus".
# Also proved red on a typo'd alias ('cyc5OOO' -> not in the registry) and on
# the no-parse tripwire (renaming the Board( constructor).
#
# Running the red proof is what found this guard's OWN bug: the first version
# was tab-separated, bash collapsed the Alchitry's empty probeAlias field, and
# the guard flagged the right board with the wrong reason. Reading a green run
# would never have shown that.
# EXTENDED 2026-10-28 (status item 174): board INSTANCES (jop.setup.Instances)
# now carry the unit facts of the boards they cover, so the guard reads them
# too. Moving the two Atari-800 units there would otherwise have dropped them
# from this guard silently -- 9 boards checked became 7, and nothing failed.
# PROVED RED the same day on an instance alias missing from the registry and
# on the instances no-parse tripwire (BOARD_SCALA / INSTANCES / REGISTRY can
# point at copies for exactly that).
# NARROWED 2026-10-31 (status item 173): every unit's facts moved to
# Instances.scala and Board lost the fields, so the Board.scala parse went. A
# board design that grew them back would be checked by nobody -- hence the
# tripwire below, which fails on any of them declared in Board.scala.
# PROVED RED the same day four ways: an alias added back to a board design, an
# instance alias missing from the registry, a CYC5000 unit with no loader (the
# Quartus fallback), and the instances no-parse tripwire.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: a board that is physically attached must be programmable,
# and the tool it declares must be one that can actually drive its probe.
#
# TWO DEFECTS, ONE CLASS (2026-09-11, items 156/157). Both boards had been
# attached for weeks and neither could be programmed:
#
#   Alchitry Au V2  had consoleAlias but no probeAlias, so hw_verify refused it
#                   before doing anything: "board 'alchitry-au-v2' has no
#                   PROBE_ALIAS in its config". It was the ONE attached board
#                   that could not be hardware-verified at all -- which is why
#                   item 153's 197 unclocked pins survived undetected. Nothing
#                   notices a board that is never tested.
#
#   CYC5000         named no loaderBoard, so HwVerifyDescriptor computed
#                   PROGRAM_TOOL=quartus (the fallback when neither loaderCable
#                   nor loaderBoard is set). Quartus never enumerates this
#                   board's Arrow blaster on 18.1 or 25.1 -- "Error (213013):
#                   Programming hardware cable not detected".
#
# WHY A GUARD AND NOT TWO FIXES. Board.scala's tool choice is a FALLBACK: a
# board that declares neither loader field silently becomes a quartus board. A
# fallback that is wrong for a given probe cannot announce itself, so the cost
# lands on whoever next tries to use the board -- and the failure they see is
# never "the config is wrong". The CYC5000's read as "Read ID failed / SPI
# flash write failed", i.e. dead hardware. It was not.
#
# jtag_probe_map is the registry of what is PHYSICALLY attached and what kind
# of probe each board has. This guard is the join between that physical truth
# and Board.scala's declarations.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

BOARD_SCALA=${BOARD_SCALA:-spinalhdl/src/main/scala/jop/config/Board.scala}
INSTANCES=${INSTANCES:-spinalhdl/src/main/scala/jop/setup/Instances.scala}
REGISTRY=${REGISTRY:-fpga/scripts/jtag_probe_map}

for f in "$BOARD_SCALA" "$INSTANCES" "$REGISTRY"; do
  [ -r "$f" ] || { echo "  FAIL cannot read $f"; exit 2; }
done

# --- probe registry: alias -> kind ----------------------------------------
# The registry's BOARDS heredoc is `alias|serial|kind|description`. Read it as
# data rather than sourcing the script, which would run its argument parsing.
kind_of() {
  command sed -n '/^BOARDS=\$(cat <</,/^EOF$/p' "$REGISTRY" |
    command grep -E "^$1\|" | cut -d'|' -f3
}

# Which tool can drive which kind. `usb-blaster` is the odd one out: the
# openFPGALoader busdev patch covers dirtyJtag only, so its usb-blaster driver
# still grabs the first vid:pid match and Quartus is the correct tool there.
# Everything else -- FTDI parts, CMSIS-DAP, RP2040 dirtyJtag -- Quartus cannot
# see at all.
tool_for_kind() {
  case "$1" in
    usb-blaster) echo quartus ;;
    *)           echo openfpgaloader ;;
  esac
}

fail=0
problems=""
checked=0

instances=0

note() { problems="$problems        $1"$'\n'; fail=1; }

# --- walk the Board( ... ) blocks -----------------------------------------
# Each board is `  def Name = Board(` down to the next such line. awk emits one
# record per board: name|probeAlias|consoleAlias|loader
#
# NOT tab-separated. Bash treats tab as IFS WHITESPACE, so `read` collapses a
# run of them into one delimiter and an empty middle field shifts everything
# left. That is not hypothetical: with tabs, the Alchitry's empty probeAlias
# made `read` slide its consoleAlias into $probe, and this guard reported the
# right board for the wrong reason -- a tool mismatch rather than the missing
# probeAlias that was actually there.
while IFS='|' read -r kind bname probe console loader; do
  [ -n "$bname" ] || continue

  # A board with a serial console is a board someone talks to, i.e. one that is
  # attached and meant to be run. It must also be programmable.
  if [ -n "$console" ] && [ -z "$probe" ]; then
    note "$bname — consoleAlias '$console' but NO probeAlias; hw_verify refuses this board outright, so it can never be hardware-verified"
    continue
  fi

  [ -n "$probe" ] || continue
  checked=$(( checked + 1 ))
  instances=$(( instances + 1 ))

  kind=$(kind_of "$probe")
  if [ -z "$kind" ]; then
    note "$bname — probeAlias '$probe' is not in $REGISTRY; jtag_probe_map cannot resolve it to a serial"
    continue
  fi

  want=$(tool_for_kind "$kind")
  # HwVerifyDescriptor.scala: loaderCable or loaderBoard => openfpgaloader,
  # otherwise quartus. Mirror that rule here rather than guessing.
  if [ -n "$loader" ]; then have=openfpgaloader; else have=quartus; fi

  if [ "$want" != "$have" ]; then
    if [ "$want" = openfpgaloader ]; then
      note "$bname — probe '$probe' is kind $kind, tool is quartus; quartus cannot enumerate it. Declare loaderBoard or loaderCable."
    else
      note "$bname — probe '$probe' is kind $kind, tool is openfpgaloader; the busdev patch does not cover usb-blaster, so it takes whichever probe enumerated first. Drop loaderBoard/loaderCable and use quartus."
    fi
  fi
done < <({
# Board instances: `  val x = Instance(` blocks, `name = ".."` at four spaces,
# the UnitAccess fields at six.
awk '
  /^  val [A-Za-z0-9_]+ = Instance\(/ {
    if (name != "") print name "|" probe "|" console "|" loader
    name = "?"; probe = ""; console = ""; loader = ""
    next
  }
  name == "" { next }
  /^    name[[:space:]]*=/          { if (match($0, /"[^"]*"/)) name = substr($0, RSTART+1, RLENGTH-2) }
  /^      probeAlias[[:space:]]*=/  { if (match($0, /"[^"]*"/)) probe = substr($0, RSTART+1, RLENGTH-2) }
  /^      consoleAlias[[:space:]]*=/{ if (match($0, /"[^"]*"/)) console = substr($0, RSTART+1, RLENGTH-2) }
  /^      loaderBoard[[:space:]]*=/ { if (match($0, /"[^"]*"/)) loader = substr($0, RSTART+1, RLENGTH-2) }
  /^      loaderCable[[:space:]]*=/ { if (match($0, /"[^"]*"/)) loader = substr($0, RSTART+1, RLENGTH-2) }
  END { if (name != "") print name "|" probe "|" console "|" loader }
' "$INSTANCES" | command sed 's/^/instance|/'
})

# Unit facts belong to instances (UnitFacts.scala defines their type), and
# no board design may carry one, nor may Board declare the fields.
leaked=$(command grep -nE '(probeAlias|consoleAlias|loaderCable|loaderBoard)[[:space:]]*(=|:)' "$BOARD_SCALA")
if [ -n "$leaked" ]; then
  echo "  FAIL $BOARD_SCALA carries unit facts again -- they belong to an instance in $INSTANCES:"
  printf '        %s\n' "$leaked"
  exit 1
fi
if [ "$instances" -eq 0 ]; then
  echo "  FAIL parsed no board instances with a probeAlias out of $INSTANCES"
  echo "       The instances awk block stopped matching. It is asserting nothing."
  exit 1
fi

if [ "$fail" -ne 0 ]; then
  echo "  FAIL board probe/tool declarations do not match the probe registry:"
  printf '%s' "$problems"
  echo "       $REGISTRY records what is physically attached and what kind of"
  echo "       probe each board has. An instance's tool choice is a FALLBACK --"
  echo "       a unit naming neither loaderCable nor loaderBoard silently"
  echo "       becomes a quartus unit -- so a mismatch surfaces as a hardware"
  echo "       fault, not a config error. The CYC5000's read as a dead board."
  exit 1
fi

echo "  $instances board instances with a probe, all reachable and on a tool that can drive it"
