#!/usr/bin/env bash
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-11-03 on the tree before the commit that added it: it named
# six lines in four boards -- the Au DDR3 exerciser's program script, the
# fallbacks in the Au's two program scripts, the Wukong's BRAM and SDRAM
# exerciser bitstreams, and the A-E115FB exerciser's .sof -- and exited 1.
# Also proved red on a planted `$(CURDIR)/vivado/build/x.bit`, and green on a
# planted `../../build/x/vivado/x.bit`.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
# ---------------------------------------------------------------------------
# REGRESSION TEST: a recipe that loads an FPGA loads what the build wrote.
#
# Every flow's BUILD step moved out of the board directories into
# build/<config>/ on 2026-08-29 -- one of the commits is titled "no build
# product is read from the source tree any more" (062ce68). Six steps that
# load a bitstream did not move: they still named it by a path in the board
# directory, where nothing has written since. Nothing failed until someone next
# tried to load the board, and then it failed on a missing file -- or, had an
# old build been left there, loaded a stale bitstream without complaint.
#
# Found 2026-11-03 through the Au DDR3 exerciser's `make program`, whose script
# looked in fpga/alchitry-au-ddr3-test/vivado/build/. The first run of this
# guard found the same in the Wukong's `make program` and `program-flash` --
# the board's default flow, converted 2026-08-29 with its bitstream path left
# behind -- and in its SDRAM exerciser, the A-E115FB's DDR2 exerciser and the
# Au's flash programmer.
#
# THE RULE. In the fpga/ flows' code (comments stripped), a path to a
# configuration file -- .bit .rbf .sof .jic .pof -- starts at a directory the
# flow computes ($(CFG_DIR), $(ABS_BUILD), $::env(JOP_BIT_FILE), ...) or at
# build/ through ../ . A path that starts with a literal directory name
# (vivado/build/, output_files/) or at $(CURDIR) is in the board directory,
# i.e. the source tree. A bare file name (JOP_BIT=X.bit, which the build
# script places itself) is not a path and is not checked.
#
# WHAT IT CANNOT SEE: a variable that is itself a directory in the source tree.
# The bring-up jigs (fpga/bringup/) do that on purpose: they build into a
# build/ beside their sources, from no board data.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

BAD=0
n=0
# Untracked files too: a new board's Makefile must not pass until it is added.
while IFS= read -r f; do
  [ -f "$f" ] || continue
  n=$(( n + 1 ))
  # sh, tcl, Makefile and .mk all comment with #
  hits=$(sed -e 's/#.*//' "$f" \
    | command grep -noE '[^[:space:]=]*/[^[:space:]=]*\.(bit|rbf|sof|jic|pof)\b' \
    | while IFS= read -r h; do
        path=${h#*:}
        first=${path%%/*}                          # what the path starts at
        case "$first" in
          *'$(CURDIR)'*) ;;                        # the board directory
          *'$'*) continue ;;                       # a computed directory
        esac
        [[ "$path" =~ ^(\.\./)+build/ ]] && continue
        echo "$h"
      done)
  if [ -n "$hits" ]; then
    echo "FAIL: $f names a bitstream in the source tree:" >&2
    printf '%s\n' "$hits" | sed 's/^/    line /' >&2
    BAD=1
  fi
done < <(git ls-files --cached --others --exclude-standard -- \
           'fpga/*Makefile' 'fpga/*.mk' 'fpga/*.tcl' 'fpga/*.sh' | sort -u)

if [ "$n" -lt 30 ]; then
  echo "FAIL: read only $n flow files under fpga/ -- the file list is broken" >&2
  exit 1
fi
if [ "$BAD" -ne 0 ]; then
  cat >&2 <<'EOF'

Nothing writes a bitstream into a board directory any more: every flow builds
under build/<config>/. Name the file where the build target puts it -- through
the config directory the flow computes, or a JOP_BIT_FILE its caller passes --
so that `make program` loads the build that `make bitstream` just made.
EOF
  exit 1
fi
echo "  $n flow files under fpga/, every bitstream they load is in the build tree"
