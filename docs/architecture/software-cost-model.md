# What operations cost on JOP — measured

Per-operation costs for software running *on* JOP, measured on hardware rather
than reasoned about. Written because three separate cost estimates during the GC
work were wrong by 3x or more, in both directions, and there was nowhere to look
them up.

All figures are 100 MHz (EP4CGX150 SDR unless noted). They are **not** portable
to a different clock or memory system — see the caveats at the end.

| operation | cost | how it was measured |
|---|---:|---|
| **method call** (`invokestatic`, few args) | **~142 cycles** | one extra delegation per push cost 102 ms over 72020 pushes (`9b9a690`) |
| **static field read** (`getstatic`) | **~22 cycles** | hoisting 3 statics out of `push` saved 48 ms over 72020 pushes (`9b9a690`) |
| **monitor enter+exit pair** | **~58 cycles** | hoisting a per-handle monitor out of `compactAndSweep` bought 1.6% of 1311 ms over 36024 handles |
| **`imul` as microcode** | **~775 cycles** | 32-iteration shift-add loop; removing one from `push` halved the mark phase (`4cb4ba5`) |
| **scattered handle access** (read or write, one 256-bit line) | **~65 cycles** | minor sweep: 1346-1567 ns for 3 accesses in one line |
| **merge-sort element, per pass** | **~2 µs / 200 cycles** | 3-4 scattered handle accesses plus loop overhead (`63a9fa0`) |

## The two that overturn received wisdom here

**A method call costs about 6.5 static reads.** The established optimisation
pattern in this codebase has been "hoist statics into locals", which cut the
minor GC sweep 27% and was applied on the reasoning that statics live in main
memory. That is true, but a `getstatic` is ~22 cycles, not the ~65 of a scattered
main-memory access — statics are evidently served far better than a random
handle read. Meanwhile every helper method extracted "for clarity" in a
per-object loop costs ~142 cycles. **In a hot loop, prefer one fat method over
three clean ones**, and be sceptical of refactorings that add a call per element.

**`imul` is not free.** `HANDLE_SIZE * n` where `HANDLE_SIZE` is 8 compiles to
an `imul` — javac does not strength-reduce — and `imul` defaults to Microcode on
any preset that does not ask for an ICU multiplier. That is ~775 cycles for a
shift. Write `<< 3`. This cost half the major GC's mark phase for years, in a
file that already wrote the same product as a shift twenty lines earlier.

## Estimating accuracy, historically

Recorded because it is the actual lesson:

| estimate | reality | error |
|---|---|---|
| "six statics are ~60% of `push`'s cost" | ~11% | **3x too high** |
| "the sweep is not memory-bound" | it is; removing accesses helped | wrong sign |
| "the root scan is the pause floor at 3.88 ms" | that timer bundled two scans; the named one was 0.647 ms | **6x too high** |
| "monitors are the major-GC problem" | 1.6% | wrong |
| "the merge sort dominates compact" | 87% of compact | **right** |

Four of five wrong, and the one that was right had been sitting unverified for
days because two earlier guesses about the same pause had also been wrong. The
pattern is not that estimates are bad — it is that **they were never cheap to
check and so never were**. Every figure in the table above cost one two-minute
hardware cycle.

## What is not measurable today

**There are no cache hit/miss counters anywhere in the RTL.** `IO_PERFCNT` is a
constant with nothing behind it. So "how many misses did this phase take" cannot
be answered directly, and every miss-count figure in the GC docs is inferred
from timing. A small counter block on `LruCacheCore` — hits, misses, stall
cycles, readable over I/O — would make that a measurement instead of an
inference. Weigh against the XC7A100T closing at +0.001 ns, which makes adding
logic to the cache path there risky; the EP4CGX150 has margin.

## A structural trap worth knowing

**`JVM.java`'s method order IS the bytecode dispatch table.** JOPizer emits
"pointer to first non Object method struct of class JVM" and handlers are
indexed by position, so adding *any* method to that class — even a private
helper at the end — shifts every bytecode after it. The symptom is a bogus
`bytecode NNN not implemented` at boot, nowhere near the change.

Put helpers in `JVMHelp` or another class. `JVMHelp.arrayCastOk` is there for
exactly this reason.

## Caveats

- **Clock and memory system.** Scattered-access cost tracks memory latency, not
  clock: the minor GC root scan is 2.2 / 4.7 / 8.5 ms across SDR / DDR3 / DDR2
  with two of those boards at the same 100 MHz.
- **Some costs are configuration-dependent.** `imul` is ~775 cycles as microcode
  and a few as an ICU operation. Check the preset's `bytecodes` map before
  assuming.
- **These are averages over a real workload**, not microbenchmarks. The method
  call figure comes from a push loop with its arguments already on the stack.

## How to measure something new

`Native.rd(Const.IO_US_CNT)` is a free-running microsecond counter. The pattern
that worked repeatedly:

1. Wrap the suspect region, accumulate into a **local**, store to a static once
   at the end. A static increment per iteration is a main-memory access and will
   dominate what it is counting.
2. Prefer counters that are analytically redundant — if a bottom-up merge sort
   relinks every element once per pass, count passes, not steps.
3. Put it behind a `static final boolean` so javac folds it away for production
   (`GC_TIMING`, `GC_SORT_TRACE`, `GC_MARK_TRACE`).
4. Time both ends of something that should differ if your theory is right. Pass 1
   versus pass N is what proved the sort was *not* a locality problem.
