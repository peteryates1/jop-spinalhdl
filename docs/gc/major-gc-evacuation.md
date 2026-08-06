# Major GC — evacuation instead of sliding

**Status: IMPLEMENTED and hardware-validated (EP4CGX150, 2026-08-06).**

| @36k live | before | after |
|---|---:|---:|
| **major pause** | 1849.2 ms | **865.6 ms** (-53%) |
| sort | 1084.9 ms | **0.006 ms** (`passes 0` — never runs) |
| compact | 1306.1 ms | 309.4 ms |
| mark | 542.5 ms | 555.6 ms (+13, the `liveWords` accumulation) |
| copy | 10.1 ms | 86.5 ms (every object moves now, not just displaced ones) |

`GcPauseTest`'s explicit `GC.gc()` went **161 -> 12.4 ms**. Minor GC untouched:
sweep 1344 ns/handle against 1346 before.

Validated: DoAll 66/66, `GcPauseTest` MAJOR OK / retained 64/64 / born-bad 0,
`MultiArrayGcTest` and `IntHandlerGcTest` OK, `GcStressTest` 320k+ rounds with
0 errors and an unchanged 0.42 bytes/round promotion slope.

Cumulative for the day: **2214.9 -> 865.6 ms, 2.56x**, from two static hoists
and this.

The trade is visible in the copy row: sliding left objects in place when they
were already positioned, so after the first collection almost nothing moved.
Evacuation ping-pongs the region and moves everything every time — 76 ms more
copying to remove 1085 ms of sorting.

**Correction to an earlier draft of this note**, which said this and
[`copy-phase-redesign.md`](copy-phase-redesign.md) share one root cause — no
spatial locality on the handle table. The pass-count measurement below
undermines that, and the two problems should not be planned as one:

| | minor GC copy phase | major GC sort |
|---|---|---|
| evidence | 132 cycles/handle at 75 MHz vs 162 at 100 MHz — scales with **memory latency**, not clock | pass 1 costs the same as pass N; within 4% across SDR and DDR3+L2 |
| diagnosis | **locality**: ~6400 scattered line fetches to find ~66 survivors | **algorithmic**: `ceil(log2 n)` passes, each doing the same uniform work |
| fix | dense side arrays (copy-phase-redesign) | remove the passes (this note) |

They smell alike because both walk the handle table. They are not the same
problem, and evacuation does nothing for the minor pause.

**What would help both** is de-interleaving the handle: `OFF_PTR` must stay at
offset 0 (the hardware's `stgf` indirection depends on it), but the GC's own
bookkeeping — `OFF_NEXT`, `OFF_SPACE`, `OFF_GREY` — could live in dense arrays
indexed by handle number, eight per cache line instead of eight lines. That is
copy-phase-redesign's plan and it composes with evacuation rather than competing.
Consistent with the measurements, but not itself measured.

## This is not a new mechanism — the minor GC already does it

`copyAndSweepYoung` promotes survivors exactly this way today:

```java
int dst = allocPtr - size;
allocPtr = dst;
for (int i = 0; i < size; ++i) Native.wrMem(Native.rdMem(ptr+i), dst+i);
Native.wrMem(dst, ref+OFF_PTR);      // one word relocates the object
```

Fresh destination, list order, no sort — and `sortListByAddress` is called from
only two places, both major-GC paths (`compactAndSweep` and `prepareCompact`).
So the proposal is to make the major GC do what the minor GC has been doing on
four boards through the whole GC stress suite, not to invent something. That is
the strongest argument for this over a linear-time sort: the relocation
mechanism is already proven in-tree.

**It does not change the minor GC's algorithm.** It does touch the minor GC's
*correctness*, through the shared layout invariants — see below.

Measurements: [`../current-status.md`](../current-status.md) §3 item 3.
Not repeated here.

---

## The problem, in one line

At 36k live objects the address sort in `compactAndSweep` is **65% of a 1721 ms
major GC pause**, and the object data copy is **0.6%**.

## Why the sort exists at all

This is the part worth understanding before proposing anything, because it is
architectural rather than accidental.

**JOP objects have no header.** A reference is a handle address; the object's
size and type live in the handle (`getObjectSize` goes handle -> `OFF_MTAB_ALEN`
-> mtab -> class struct), and the data at `OFF_PTR` is bare words. So **the heap
cannot be parsed linearly** — given an address in the middle of the heap there
is no way to know where the object there starts or ends.

HotSpot walks its heap in address order for free, by pointer increment, because
every object is self-describing. `compactAndSweep` cannot, so it reconstructs
that same address order at O(n log n) by sorting a linked list of handles. The
sort is not a naive implementation; it is a workaround for a missing header.

Adding headers would remove the need, at one word per object plus changes to
every allocation path and to the hardware indirection. That is not the cheapest
route.

## What a modern JVM actually does

Sliding compaction is the *old* algorithm — HotSpot's serial mark-compact and
ParallelOld. G1, Shenandoah and ZGC all **evacuate**: live objects are copied
out of a source region into fresh space, in **any order**, because source and
destination are disjoint. Nothing is sorted, because nothing needs to be.

**JOP is better suited to evacuation than HotSpot is**, and this is the key
observation:

| | HotSpot | JOP |
|---|---|---|
| moving one object costs | find and rewrite **every reference** to it (the "adjust pointers" phase), or a forwarding pointer plus a read barrier on every access forever | **one word** — the handle's `OFF_PTR` |

JOP already pays for the handle indirection on every single field access —
`getfield` is `stgf`, "let the HW do the work". Relocation being nearly free is
the *benefit* that indirection buys. The current major GC does not collect it:
it sorts 36k objects as though references were direct pointers, then slides.

## Design

1. **Mark** — unchanged.
2. **Evacuate** — walk `useList` in whatever order it happens to be in. For each
   marked handle: copy `size` words from `OFF_PTR` to the destination bump
   pointer, write the new address into `OFF_PTR`, relink onto the new use list.
   Unmarked handles go to the free list exactly as now.
3. **Publish** — the evacuated region becomes the live tenure extent; the old
   extent becomes free.

No sort. No second pass. Same O(live) data movement as today (which measured
9.5 ms, so it is not the cost).

### The constraint, and the fallback

Evacuation needs a destination that does not overlap any *not-yet-copied*
source object. The simple form needs **free >= live**. Live is 108k words
against a 256 MB heap, so this is not a close call on the large boards — but it
is not guaranteed, and the failure mode if it is assumed and false is heap
corruption, not an exception.

Two fallbacks, in preference order:

- **Region-granular ordering** — HotSpot's "summary phase". Bucket handles by
  address region in one O(n) pass, then process regions in index order with the
  destination trailing the source. Ordering ~hundreds of regions instead of 36k
  objects; the bucket pass is the same work as the first pass of a radix sort.
  This is the general answer and it also covers the free < live case.
- **Keep the existing sort+slide** as a last resort when neither holds.

### Invariants that must not break

- `carveNursery` and the generational layout assume compacted data grows up
  from `heapStart` to `copyPtr` and promotions grow down from `tenureTop` to
  `allocPtr`. Evacuating to a different extent changes both.
- The tenure-bounded card scan (`42a52aa`) scans exactly `[heapStart, copyPtr)`
  and `[allocPtr, tenureTop)`. If the live extent moves, that scan must move
  with it or dirty cards are missed — silently, and the symptom is premature
  collection.
- `gc()` already re-carves the nursery and clears cards after compaction; that
  ordering still has to hold.

This is the area that produced the `multianewarray` premature-collection defect,
which is why `MultiArrayGcTest` and `IntHandlerGcTest` exist. Both must pass,
and neither is a formality — the `multianewarray` failure signal was a crash
during churn, not a FAIL line.

## The gating question — RESOLVED (EP4CGX150, 2026-08-06)

`GC_SORT_TRACE` reports pass count and the cost of the first and last pass.

| n | passes | ceil(log2 n) | pass 1 | pass N | sort total | **µs / element / pass** |
|---:|---:|---:|---:|---:|---:|---:|
| 6024 | 13 | 13 | 15.448 | 15.196 | 179.810 | 2.296 |
| 18024 | 15 | 15 | 39.471 | 35.169 | 533.396 | 1.973 |
| 36024 | 16 | 16 | 75.553 | 67.325 | 1086.513 | 1.885 |

Three things settle it:

1. **Pass count is exactly `ceil(log2 n)`**, as the algorithm says it must be.
2. **First pass costs the same as last pass** (75.6 vs 67.3 at n=36024). Early
   passes merge short runs in list order; late passes merge long runs already in
   address order. If locality were the story these would differ sharply. They do
   not, so **the sort is not a locality story** — it is simply doing the work
   `n x log n` times.
3. The earlier "flat ~30 µs per handle" anomaly was **two effects cancelling**:
   passes rose 23% (13 -> 16) while cost per element-pass fell 18% (2.296 ->
   1.885). The `n x passes x constant` model was right after all; per-*handle*
   was the wrong thing to plot. Recording that because the wrong conclusion —
   "the obvious cost model is wrong" — was drawn from it and could have sent
   someone hunting a phantom.

**Cross-board**: the XC7A100T DDR3 gives 1.956 µs/element/pass against the
EP4CGX150 SDR's 1.885 — within 4%, on completely different memory systems (DDR3
plus a 32 KB L2 versus bare SDR). The minor *sweep* differs 16% between the same
two boards. So the sort is not memory-latency-bound in the way the sweep is, and
no amount of cache or memory improvement will help it. **Only removing passes
will.**

## The mark phase — measured too (EP4CGX150, 2026-08-06)

Mark is 591 ms at 36k live and becomes **~71% of the pause** once evacuation
removes the sort, so it was instrumented before starting that work.
`GC_MARK_TRACE` splits it into popping a gray object and pushing its children.

| n | mark | push | push % | pop+header | µs/pop | µs/push |
|---:|---:|---:|---:|---:|---:|---:|
| 6024 | 164.9 | 142.5 | 86% | 22.4 | 3.72 | 3.39 |
| 18024 | 335.4 | 269.0 | 80% | 66.4 | 3.68 | 4.98 |
| 36024 | 591.1 | 458.8 | **78%** | 132.4 | 3.67 | 6.37 |

**`push()` is 78-86% of the mark phase.** Popping a gray object and reading its
header is flat at 3.7 µs and is not the problem.

At ~6.4 µs — **640 cycles** — per call, what `push()` actually does per
reference is:

- read `mem_start` and `handleEnd` (two statics, so two main-memory reads)
  before it does anything else
- take `mutex` — a third static read plus a monitor pair
- read `toSpace` (fourth static)
- read `grayList` and write it back (fifth and sixth)
- three `rdMem` on the handle itself, all in one cache line

So roughly **six main-memory static accesses per pushed reference**, against
three accesses that do actual work. This is the same defect class as the `imul`
in the same method: JOP keeps statics in main memory, and this is the hottest
loop in the collector.

### Applied, and it calibrated something more useful than the win

Hoisting `mem_start`/`handleEnd`/`toSpace` into locals at the three loop call
sites (`mark`, `getStaticRoots`, `getStackRoots`) and passing them to a
`pushFast`: **mark 591.1 -> 542.5 ms (-8.2%)**, pause 1897 -> 1849 ms. Validated
on EP4CGX150 — DoAll 66/66, MultiArray and IntHandler OK, GcStressTest 320k+
rounds with the identical 0.42 bytes/round slope.

**The first attempt was a 22% regression**, and that is the valuable part. It
kept the logic in one place by having `pushFast` delegate to a `pushInto` that
took the hoisted values — one extra method call per push. Mark went 591 -> 693
ms. Collapsing the delegation and inlining the body gave the -8.2% above.

Two costs fall out, and they invert the guidance this codebase has been working
from:

| | cost | note |
|---|---:|---|
| JOP method call | **~142 cycles** | 102 ms / 72020 pushes, from the regression |
| main-memory static read | **~22 cycles** | 48 ms / 72020 / 3, from the fix |

**A method call costs about 6.5 static reads.** The established pattern here has
been "hoist statics" — it cut the minor sweep 27% — and the estimate going into
this change was that six statics were ~60% of `push`'s 637 cycles. They were
~11%. The estimate was 3x too high, and the *call* was the bigger term all along.

What remains: `push` is 570 cycles, of which ~142 is its own invoke. Inlining it
into `mark`'s two loops would take ~102 ms off a 542 ms mark (19%), but it
duplicates GC logic into the most safety-critical loop in the collector for 5.5%
of the pause. **Not worth it while the sort is still 1085 ms** — evacuation
removes that entirely and is the better use of the same risk budget.

Caveat on reading the µs/push column: it rises with n partly because the
`Node[36000]` root array is a fixed size across every row, so at small n most of
its slots are null and return from `push()` immediately. The 78% share is the
solid number; the per-call cost is an average over a changing mix.

### What that is worth

Sort is `n x ceil(log2 n) x ~2 µs`. At 36k live that is 16 passes.

- **Linear sort** (radix, 1-2 passes): ~8-16x on the sort, 1086 -> ~70-140 ms.
- **Evacuation** (no sort at all): the whole 1086 ms, **58% of the 1883 ms
  pause** on this board.

Both are now honest numbers rather than estimates, because the cost model has
been validated on two boards with different memory.

## How this gets validated

There is **no RTL in this change** — it is entirely `GC.java`. The existing
vehicles cover it:

| stage | vehicle | what it establishes |
|---|---|---|
| correctness, real microcode, small heap | `JopGenGcBramSim`, `JopSmallGcBramSim` | the algorithm works against the real memory controller and cache |
| randomised churn | `GcStressTest` (537k rounds) | no premature collection under mixed allocation |
| structural traps | `MultiArrayGcTest`, `IntHandlerGcTest` | reference arrays and interrupt handlers survive relocation |
| regression | `DoAll` 66/66 | nothing else broke |
| **timing** | `GcMajorPauseTest`, `GcPauseTest` on hardware | the only place the 36k-object / 256 MB numbers exist |

Timing cannot come from simulation: one 2.2 s collection is ~220M cycles in a
cycle-accurate DDR3 sim, and the BRAM sims have a 512 KB heap — too small for
the live sets that make the constant visible. The hardware loop (build,
program, download, six-point measurement) is about two minutes, which is faster
than most simulation anyway.

## The instrument that is actually missing

Three cost models for this pause have now been wrong, and the reason is the
same each time: **there is no visibility into the memory system.** Hardware
tells us how long a phase took; nothing tells us how many cache misses it took.
There are no hit/miss/stall counters anywhere in the RTL — `IO_PERFCNT` is a
constant with nothing behind it.

A small counter block on `LruCacheCore` (hits, misses, stall cycles, readable
via an I/O address) would answer this question and every future one directly,
on real hardware at full scale, permanently. It is the one piece of hardware
this work would actually benefit from — as opposed to the block-copy engine,
which the measurements ruled out.

Cost to weigh against that: it is an RTL change and therefore a Vivado cycle,
and the XC7A100T currently closes at **+0.001 ns** (outstanding item 8). Adding
logic to the cache path there is a real risk of manufacturing a timing failure.
The EP4CGX150 has margin and would be the place to try it first.
