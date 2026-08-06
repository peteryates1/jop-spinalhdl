# Major GC — evacuation instead of sliding

**Status: design note, nothing implemented.** Sibling of
[`copy-phase-redesign.md`](copy-phase-redesign.md); the two share one root cause
(every GC phase walks the handle table with no spatial locality, because a
handle is exactly one 256-bit cache line) and should probably be merged once
both have been through hardware.

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

## Open question that gates the choice

Sort cost per handle is **flat at ~30 µs** from n=6024 to n=36024, while a
bottom-up merge sort makes `ceil(log2 n)` = 13 -> 16 passes over that range.
Per-handle cost should rise ~23%; it does not. **The obvious cost model is
therefore wrong**, and until that is resolved neither "a linear sort buys 4x"
nor "evacuation buys 65%" can be stated honestly — the second only follows if
the sort's cost really is the sort.

Cheapest resolution: instrument the existing `sortListByAddress` to report its
actual pass count and per-pass time. Ten lines, one two-minute hardware cycle.
**Do this before choosing between the options above.**

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
