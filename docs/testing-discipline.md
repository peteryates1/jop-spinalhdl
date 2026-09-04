# Testing discipline — what a check has to prove

Written 2026-09-04, from [status item 111](current-status.md#item-111) and the
seven separate incidents behind it.

**This is not a proposal to test the tests.** A guard for every guard never
bottoms out and costs more than it catches. Tests are proved capable of failing
**when they are written** — run them red against the unfixed code — and re-proved
when something makes them suspect. That is a judgement, and it should stay one.

What follows is about a narrower failure: **a check whose output cannot
distinguish "I looked and found nothing wrong" from "I did not look."**

---

## 0. The cheapest invariant is not restating the fact at all

Before writing a check, ask whether the thing being checked should exist.

The navigation table in `current-status.md` said *"57 entries"* against an actual
65 — correct when written, silently wrong for weeks. The first fix was a guard
asserting the number matched. The better fix, and the one taken, was to delete
the number: **the entries are listed directly below it, so restating how many
can only ever be redundant or wrong.** A derived value copied into prose is a
second source of truth with no mechanism keeping it honest.

Check what cannot be derived. Delete what can.

## 1. Assert a structural invariant, not a tally

A count is evidence only to a reader who already knows what to expect.

`check-status-index.sh` once reported `priority list: 0 entries` and passed —
its regex matched nothing. Later a botched renumbering produced
`1. 1. **[#136]`, the regex failed again, and it reported `1 entries`. Nobody
could call that wrong from the output alone: **1 is a legitimate value** — one
open item would be excellent news.

The fix was not a bigger count. It was an invariant needing no expectation:
*the list is numbered 1..N, contiguous, no repeats.* True or false with nothing
to compare against. Report only the first mismatch — one bad entry makes every
later one disagree, and the cascade buries the useful line.

## 2. Never accept an exit status as evidence

Four checks in one day reported success without executing:

- `sbt -J-Xmx4g` and `sbt --no-colors` are CI-only flags the local sbt rejects.
  A pre-push gate announced *"rc=0, zero NO DRIVER"* for three simulations that
  never started — `NO DRIVER` was zero because nothing had elaborated.
- `cmd | tail` reports **tail's** status. Use `${PIPESTATUS[0]}`.
- `quartus_pgm` is not on the interactive PATH (the Makefile supplies it), so
  five retries against a dead JTAG chain printed five blanks that read as five
  clean detections.
- `sbt testOnly <filter matching nothing>` exits **0**: `No tests to run`.
  Measured, not assumed. A package rename would silently drop every test.

Assert on content: a test tally, a distinctive phrase, a line count. And pick a
phrase the *unfixed* tree cannot emit — `check-console-baud.sh` first asserted a
non-zero exit, which the broken tree also produced, then grepped for "baud",
which matched the very error message saying the target did not exist.

## 3. Verify against the artefact, not the build

A build that exits 0 has not said your change took effect.

`make -C java/apps/<X>` does not rebuild JOPizer
([item 140](current-status.md#item-140)), so a linker change compiles into
nothing while every timestamp looks fresh. It was caught only because the result
was *impossible* — a test passed that cannot pass with the defect present. The
image disagreed with its own source:

```
$ command grep -c 'OFF_TYPE: IS_OBJ' DoAll.jop     -> 374   (stale jar)
$ command grep -n 'STR_OBJ_LEN =' StringInfo.java  -> 2+2+1 (the source)
```

Read the emitted `.jop`, the elaborated Verilog under `build/<preset>/rtl/`, the
generated constraint file.

**And test the DEPENDENCY, not just the feature.** On 2026-09-04 the linker's
method-size limit was made to come from the generated `Const.java`. The feature
was verified — the limit derived correctly, the override lowered it, an
excessive value was refused — and all of that passed while the dependency was
missing: `jopizer.jar`'s prerequisites did not include `Const.java`, and javac
INLINES a `static final int`, so the limit was baked into the jar with no
runtime lookup to save it. A single build regenerates `Const` and rebuilds the
tools in the same invocation, so every feature test passed. **Only switching
between two values exposed it** — set the limit to 2048, rebuild, and the jar
still compared against the 4092-byte limit. When a generated input decides an
artefact's content, change it twice and check the artefact both times. Note also that this shell's `grep` is a ugrep wrapper
that silently returns nothing for `--include=<glob>` with a `.` root — use
`command grep` for any "appears nowhere" claim
([item 135](current-status.md#item-135)).

## 4. Record red-before-green where you do it

The discipline is human; only its written trace is durable. "Verified red
against the unfixed code" in a commit message survives. Doing it silently does
not, and six months on nobody can tell which guards were ever seen failing.

---

## The current state

All six guards in `make check-build` have been proved red by injecting the
defect each exists to catch (2026-09-04):

| guard | injected defect |
|---|---|
| `check-status-index.sh` | `1. 1. **[#140]` renumbering |
| `check-workflow-sbt-setup.sh` | removed a job's `setup-sbt` step |
| `check-docs-structure.sh` | unclosed markdown fence |
| `check-generator-fallbacks.sh` | `getOrElse("ep4cgx150Serial")` |
| `check-generated-deps.sh` | dropped `.sdc: $(GEN_STAMP)` |
| `check-console-baud.sh` | changed the guard's own message |

Two CI jobs assert a suite actually ran (`formal-verification`,
`simulation-tests`); the sim jobs already did. When adding a job that runs
tests, copy the whole idiom — `card-table-tests` broke because half of one was
copied, taking `Cache SBT` without `setup-sbt`.
