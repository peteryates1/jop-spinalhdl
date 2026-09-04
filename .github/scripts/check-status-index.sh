#!/usr/bin/env bash
#
# DISCIPLINE: docs/testing-discipline.md — "assert the invariant, not the tally".
# PROVED RED 2026-09-04 by a "1. 1. **[#140]" renumbering — the entry COUNT could not catch it, because 1 is a legitimate value.
# If you change this guard, re-prove it: a guard that cannot fail is worse
# than none, because it gets quoted as evidence.
#
# REGRESSION TEST: docs/current-status.md's cross-references must resolve.
#
# That file is the project's backlog and the first thing read each session. It
# carries three indexes -- a priority list, a summary, and the item sections
# themselves -- and on 2026-08-30 all three disagreed with each other and with
# the tree:
#
#   * anchors existed for items 1-59 only, so 36 numbers were LINKED BUT
#     UNANCHORED, from docs/ and README.md alike. GitHub's fallback slug is the
#     full heading text, so none of those links resolved.
#   * a pasted block defined <a id="item-46"> and <a id="item-47"> a SECOND
#     time. Renderers bind the first, so the duplicate Item 46 -- which
#     contradicted the bound one -- was unreachable.
#   * `see [item 60a](#item-60)` named an item that does not exist.
#
# None of that is visible while writing: a broken anchor renders as ordinary
# text and a duplicate renders as a heading. Only a reader following the link
# finds out.
#
# Usage: .github/scripts/check-status-index.sh
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2
f=docs/current-status.md
[ -f "$f" ] || { echo "  SKIP ($f not present)"; exit 0; }
fail=0

# 1. Every item section must have an anchor.
# Sorted as STRINGS, because comm compares lexicographically and a numeric
# sort makes it emit "input is not in sorted order" and give wrong answers.
# [0-9]+[a-z]? because item 78b exists -- and went unanchored and unlinked for
# days precisely because a plain-integer pattern could not see it.
sections=$(grep -oE '^### Item [0-9]+[a-z]?' "$f" | grep -oE '[0-9]+[a-z]?$' | sort -u)
anchors=$(grep -oE '<a id="item-[0-9]+[a-z]?"' "$f" | grep -oE '[0-9]+[a-z]?' | sort -u)
missing=$(comm -23 <(echo "$sections") <(echo "$anchors") | sort -n | tr '\n' ' ')
if [ -n "${missing// /}" ]; then
  echo "  FAIL item sections with no anchor: $missing"
  fail=1
fi

# 2. No anchor may be defined twice -- renderers bind the first and the second
#    becomes unreachable, which is how a contradiction hid in plain sight.
dupes=$(grep -oE '<a id="item-[0-9]+"' "$f" | sort | uniq -d | grep -oE '[0-9]+' | tr '\n' ' ')
if [ -n "${dupes// /}" ]; then
  echo "  FAIL anchors defined more than once: $dupes"
  fail=1
fi

# 3. Every #item-N link in the repo must resolve to an anchor that exists.
linked=$(grep -rhoE '#item-[0-9]+' --include='*.md' . | grep -oE '[0-9]+' | sort -u)
dangling=$(comm -23 <(echo "$linked") <(echo "$anchors") | sort -n | tr '\n' ' ')
if [ -n "${dangling// /}" ]; then
  echo "  FAIL links to items with no anchor: $dangling"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  cat <<'EOF'

A cross-reference in the backlog does not resolve. Anchors are <a id="item-N">
immediately before the corresponding `### Item N` heading; a link that has no
anchor silently renders as plain text on GitHub.
EOF
  exit 1
fi

# 4. Section 1 declares itself the ground truth for what is open, so nothing it
#    lists may have a struck (closed) heading, and nothing open may be missing
#    from it. The first half is what actually rotted: items 9, 10, 50, 57, 59,
#    60 and 61 sat in the priority list for days after closing.
# 4b. THE LIST'S OWN NUMBERING MUST BE 1..N, CONTIGUOUS AND UNIQUE.
#
#    Because a COUNT is not evidence. On 2026-09-03 a botched renumbering turned
#    68 entries into "1. 1. **[#136]..." -- the doubled prefix failed the
#    extraction regex, so the guard reported `priority list: 1 entries` and
#    exited 0. That was caught only because a human knew it should be ~68.
#
#    But 1 IS a legitimate value: one open item would be excellent news, not a
#    defect. A count only means something to a reader who already has an
#    expectation, which is exactly the guarantee a check should not depend on.
#    The numbering, by contrast, is checkable with no expectation at all --
#    1..N with no gaps and no repeats is either true or it is not. That is what
#    would have caught the botched edit on its own. Status item 111.
seq_raw=$(sed -n '/^## 1\. Outstanding now/,/^## 2\./p' "$f" \
          | grep -oE '^[0-9]+\. \*\*\[#' | grep -oE '^[0-9]+')
#    Only the FIRST mismatch is reported: one bad entry makes every later one
#    disagree too, and 67 cascade lines bury the one that matters.
expected=1
first_bad=""
for n in $seq_raw; do
  if [ "$n" -ne "$expected" ]; then first_bad="$n (expected $expected)"; break; fi
  expected=$((expected + 1))
done
if [ -n "$first_bad" ]; then
  echo "  FAIL priority list numbering is not 1..N contiguous: first bad entry $first_bad"
  echo "       a renumbering went wrong; the entry COUNT alone would not show this"
  exit 1
fi

listed=$(sed -n '/^## 1\. Outstanding now/,/^## 2\./p' "$f" \
         | grep -oE '^[0-9]+\. \*\*\[#[0-9]+\]' \
         | grep -oE '#[0-9]+' | tr -d '#' | sort -u)
stale=""
for n in $listed; do
  h=$(grep -m1 "^### Item $n " "$f")
  case "$h" in *'~~'*) stale="$stale $n";; esac
done
if [ -n "${stale// /}" ]; then
  echo "  FAIL closed items still in the priority list:$stale"
  echo "       section 1 says it is the ground truth for what is OPEN"
  exit 1
fi

# 5. INERT BY DESIGN. The navigation table used to say "57 entries" against an
#    actual 65, having been correct when written. The fix was not to check the
#    number harder -- it was to DELETE it: the entries are listed immediately
#    below, so restating how many can only ever be redundant or wrong.
#
#    This check remains for the case where someone reinstates a count, because
#    that is a likely and easy thing to do. It does nothing while none is
#    present. Prefer no number. See docs/testing-discipline.md.
nav_n=$(grep -oE '\[§1 Outstanding now\][^|]*— [0-9]+ entries' "$f" | grep -oE '[0-9]+ entries' | grep -oE '[0-9]+')
real_n=$(echo "$listed" | wc -w)
if [ -n "$nav_n" ] && [ "$nav_n" != "$real_n" ]; then
  echo "  FAIL the 'how to read' table says $nav_n entries; section 1 has $real_n"
  exit 1
fi

echo "  status index: $(echo "$sections" | wc -w) items, all anchored, no duplicates, all links resolve"
echo "  priority list: $(echo "$listed" | wc -w) entries, none closed"

# ---------------------------------------------------------------------------
# Journals split out under docs/status/ must stay reachable and complete.
#
# Item 116: current-status.md was 491 KB / 9,671 lines and could not be read --
# the 2026-08-30 review had to be told not to open it whole. The 18 largest
# journals moved to docs/status/item-<N>.md, leaving a summary and a link.
#
# The anchors stay in current-status.md, so every existing `#item-N` reference
# in the repo still resolves; that is checked above. What is checked here is
# that the split did not strand anything: a journal nothing links to is lost,
# and a link with no journal is worse.
# ---------------------------------------------------------------------------
if [ -d docs/status ]; then
  jfail=0
  for j in docs/status/item-*.md; do
    [ -e "$j" ] || continue
    b=$(basename "$j")
    grep -qF "status/$b" "$f" || { echo "  FAIL $j is not linked from $f"; jfail=1; }
  done
  while IFS= read -r target; do
    [ -f "docs/$target" ] || { echo "  FAIL $f links to docs/$target, which does not exist"; jfail=1; }
  done < <(grep -oE 'status/item-[0-9a-z]+\.md' "$f" | sort -u)
  [ "$jfail" -ne 0 ] && exit 1
  echo "  journals: $(ls docs/status/item-*.md 2>/dev/null | wc -l) split out, all linked, all present"
fi
