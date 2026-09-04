#!/usr/bin/env bash
# Nothing may READ OR WRITE `spinalhdl/generated` any more.
#
# WHY THIS IS A GUARD AND NOT A COMMENT. Generated RTL used to land in the
# source tree, and the build-tree move (`build/<config>/rtl`) was made OPT-IN so
# ten boards could convert one at a time. That is the defect: the correct path
# needed a flag, the legacy path was the default, and anything not explicitly
# converted silently kept writing into the source tree. It hid a flow that could
# not build from a clean clone at all -- it only ever worked because someone had
# once run the generator by hand, leaving an artefact behind. The same shape,
# one tree over, cost item 60 in the Java build.
#
# PROSE IS ALLOWED, CODE IS NOT. Several comments record the conversion and are
# worth keeping ("CONVERTED 2026-08-29. This flow generated into ..."), so this
# strips comments and checks what is left. A path a flow actually resolves is a
# code line; a path in a sentence is not.
set -uo pipefail
cd "$(dirname "$0")/../.."

BAD=0
while IFS= read -r f; do
  case "$f" in
    docs/*|*.md) continue ;;
    .github/scripts/check-no-legacy-rtl-path.sh) continue ;;
  esac
  [ -f "$f" ] || continue
  case "$f" in
    *.scala)
      # Drop // line comments and block-comment bodies (` * ` and `/* `).
      stripped=$(sed -e 's://.*::' -e 's:^[[:space:]]*\*.*::' -e 's:/\*.*::' "$f") ;;
    *)
      # sh, tcl, Makefile, .mk, .yml, .xdc all comment with #
      stripped=$(sed -e 's:#.*::' "$f") ;;
  esac
  hits=$(printf '%s\n' "$stripped" | command grep -n "spinalhdl/generated" || true)
  if [ -n "$hits" ]; then
    echo "FAIL: $f references the legacy RTL path in code:" >&2
    printf '%s\n' "$hits" | sed 's/^/    /' >&2
    BAD=1
  fi
done < <(git ls-files -- '*.scala' '*.sh' '*.tcl' '*.mk' '*.yml' '*.xdc' 'Makefile' '*/Makefile' '.gitignore')

if [ "$BAD" -ne 0 ]; then
  echo "" >&2
  echo "Generated RTL belongs under build/<config>/rtl (BuildLayout.rtlDir) or," >&2
  echo "for standalone component tops, build/standalone/<Top> (standaloneDir)." >&2
  exit 1
fi
echo "OK: no code path references spinalhdl/generated"
