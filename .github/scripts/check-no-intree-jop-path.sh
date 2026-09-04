#!/usr/bin/env bash
# Two invariants for the Java build tree.
#
# 1. NO SIM MAY HARDCODE AN IN-TREE .jop PATH. `java/apps/<X>/<Y>.jop` is a
#    SOURCE path that build products were written into. A sim reading it gets
#    "whichever preset was built last", because Const.java -- I/O addresses,
#    SUPPORT_FLOAT, the method-size limit -- is per configuration and was
#    generated into a shared source tree. The sim's harness and the image it
#    loads then come from two unrelated places and agree only by coincidence of
#    defaults. Resolve through jop.utils.SimApp instead, which keys both on one
#    preset name.
#
# 2. THE BUILD TREE IS NOT OPT-IN. `BUILDTREE ?= 0` made the correct layout a
#    flag and the source-tree layout the default, so anything not explicitly
#    converted silently kept writing build products into the source tree. Two
#    layouts is what makes that failure silent; there must be exactly one.
set -uo pipefail
cd "$(dirname "$0")/../.."

BAD=0

# --- 1. no in-tree .jop literals in Scala -----------------------------------
while IFS= read -r f; do
  hits=$(sed -e 's://.*::' -e 's:^[[:space:]]*\*.*::' "$f" \
         | command grep -n '"java/apps/[^"]*\.jop"' || true)
  if [ -n "$hits" ]; then
    echo "FAIL: $f hardcodes an in-tree .jop path:" >&2
    printf '%s\n' "$hits" | sed 's/^/    /' >&2
    BAD=1
  fi
done < <(git ls-files -- '*.scala')

# --- 1b. no in-tree .jop literals in the Java makefiles ----------------------
# Missed on the first pass: `sim-smallest` and `sim-small` invoked JopSim on
# apps/<X>/HelloWorld.jop, a source path, and nothing flagged them because the
# Scala check above only reads .scala. A recipe naming a path that no longer
# exists fails loudly rather than silently, but only when someone runs it --
# and nothing in CI does.
while IFS= read -r f; do
  hits=$(sed -e 's:#.*::' "$f" | command grep -nE '(^|[^/A-Za-z])apps/[A-Za-z0-9_]+/[A-Za-z0-9_]+\.jop' || true)
  if [ -n "$hits" ]; then
    echo "FAIL: $f names an in-tree .jop path:" >&2
    printf '%s\n' "$hits" | sed 's/^/    /' >&2
    BAD=1
  fi
done < <(git ls-files -- 'java/Makefile' 'java/*/Makefile' 'java/*.mk')

# --- 1c. the tools jars are not built into the source tree -------------------
# jopizer.jar embeds Const.class -- METHOD_MAX_SIZE is derived from the
# preset's method cache -- so it is a PER-CONFIGURATION artefact. Built into a
# single java/tools/dist it went stale exactly the way the shared Const.java
# did: make compares the jar against the CURRENT config's Const.java, which is
# older than the jar the PREVIOUS config left behind, so switching preset
# rebuilt nothing and linked with the other configuration's linker.
# Reproduced 2026-09-04: build A (2 compile steps), build B (0), and B shipped
# A's Const.class.
if command grep -qE '^DIST_DIR[[:space:]]*:?=[[:space:]]*dist[[:space:]]*$' java/tools/Makefile; then
  echo "FAIL: java/tools/Makefile builds into the source tree (DIST_DIR := dist)" >&2
  echo "      config-dependent tools belong in build/<config>/java/tools," >&2
  echo "      config-independent ones in build/java/tools" >&2
  BAD=1
fi

# --- 2. config.mk has no legacy branch --------------------------------------
if command grep -qE '^BUILDTREE[[:space:]]*\?=[[:space:]]*0' java/config.mk; then
  echo "FAIL: java/config.mk still defaults BUILDTREE to 0" >&2
  BAD=1
fi
if command grep -qE '^ifeq \(\$\(BUILDTREE\),1\)' java/config.mk; then
  echo "FAIL: java/config.mk still branches on BUILDTREE; there must be one layout" >&2
  BAD=1
fi

[ "$BAD" -ne 0 ] && exit 1
echo "OK: one Java build layout, no in-tree .jop paths"
