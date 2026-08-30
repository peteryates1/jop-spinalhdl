#!/usr/bin/env bash
#
# REGRESSION TEST: Markdown code fences must balance.
#
# A ``` marker opens a block; the next one closes it. An ODD count leaves the
# file ending inside a block, and every fence after the stray one has its role
# inverted -- prose renders as code and code renders as prose, silently, for the
# rest of the document. Nothing in the source looks wrong.
#
# This has now happened twice. README.md shipped with an unclosed fence and
# reached github.com, where a shell comment rendered as an H1 and "<config>" was
# eaten as an HTML tag. docs/architecture/configuration-driven-design.md carried
# a stray DUPLICATE CLOSING fence at line 573 that inverted 2,300 lines of a
# 2,874-line file, unnoticed for however long it had been there.
#
# THE H1 SCAN IS THE SYMPTOM, THE PARITY IS THE CAUSE. A "# " line outside a
# fence is usually a shell comment that has escaped its block, which is what a
# reader actually notices. It is reported second because on a file with a fence
# fault it fires many times as a consequence -- fix the parity and the H1s go.
#
# Usage: .github/scripts/check-docs-structure.sh
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
fail=0
checked=0

while IFS= read -r f; do
  checked=$((checked + 1))
  n=$(grep -c '^ *```' "$f")
  if [ $((n % 2)) -ne 0 ]; then
    echo "  FAIL $f"
    echo "       $n fences — odd, so the file ends inside a code block."
    # Point at the CAUSE, not the first consequence. A duplicated closing fence
    # leaves two markers on adjacent lines, and that is the actual stray. The
    # first mispaired fence (a close carrying an info string) is reported only
    # as a fallback, because on a file that slipped earlier it sits well
    # downstream of the real fault -- here it named 618 for a stray at 573.
    awk '
      /^ *```/ { if (NR == prev + 1) { printf "       adjacent fences at lines %d and %d — likely a duplicated closing marker\n", prev, NR; found=1; exit } prev = NR }
      END { if (!found) print "       no adjacent pair; scan for a block that is never closed" }' "$f"
    awk '
      /^ *```/ {
        if (!inb) { inb=1; open=NR; next }
        inb=0
        if ($0 !~ /^ *```[ \t]*$/)
          { printf "       first mispaired fence: line %d (%s), opened at %d\n", NR, $0, open; exit }
      }' "$f"
    fail=1
  fi
done < <(find . -name '*.md' -not -path './build/*' -not -path './.git/*' | sort)

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

An unbalanced fence inverts every code block below it for the rest of the file.
Find the stray marker -- usually a duplicated closing fence -- and delete it.
EOF
  exit 1
fi

echo "  $checked markdown files, all fences balanced"

# Consequence check, reported separately so it cannot be mistaken for a cause.
strays=0
while IFS= read -r f; do
  out=$(awk '
    /^ *```/ { inb = !inb; next }
    !inb && /^# / { printf "%s:%d: %s\n", FILENAME, FNR, substr($0,1,60) }' "$f")
  if [ -n "$out" ]; then
    # An H1 on line 1 is a legitimate document title.
    body=$(awk -F: '$2 != 1' <<<"$out")
    if [ -n "$body" ]; then
      echo "  note: '# ' outside a fence (shell comment rendering as a heading?)"
      sed 's/^/        /' <<<"$body" | head -5
      strays=$((strays + 1))
    fi
  fi
done < <(find . -name '*.md' -not -path './build/*' -not -path './.git/*' | sort)
[ "$strays" -eq 0 ] && echo "  no stray H1s outside fences"
exit 0
