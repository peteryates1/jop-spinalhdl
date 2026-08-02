# GC Stage 3 — open follow-ups

Things surfaced while measuring and fixing the generational collector that are
*not* yet done. Recorded here so they are not lost; none of them block the
current state (both boards pass DoAll 66/66, the GC stress test, and the pause
and multi-array tests).

Context: `gc-generational-implementation-plan.md`, `stage2-generational-design.md`.
Commits: `1916415` (measure), `8a8e154` (young list), `5e0a3a0` (256 MB full GC),
`78cc968` (multianewarray / zero-size).

---

## 1. `addInterruptHandler` under GC — DONE (IntHandlerGcTest)

`JVMHelp.ih = new Runnable[cpus][NUM_INTERRUPTS]` (`JVMHelp.java:177`) is the
allocation that exposed the `multianewarray` defect fixed in `78cc968`: its
inner arrays were typed `IS_OBJ`, so the collector never traced them and any
registered handler was invisible to it — collectable while still installed.

`java/apps/Small/src/test/IntHandlerGcTest.java` now covers it: it registers a
handler whose ONLY reference is `ih[core][INT_NR]` (created in its own frame),
fires it in software via `IO_SWINT`, runs many minor GCs plus a full
mark-compact, then fires again and checks both that it still runs and that its
own field survived intact — proving the object was traced and relocated, not
merely that something callable was there.

Correcting an earlier note here: the boot-time slots are **not** null.
`JVMHelp.init()` fills every slot with a `DummyHandler`, which stays rooted via
the static field `JVMHelp.dh` regardless of how `ih` is traced. That is why
nothing crashed before — the exposure was only ever a handler with no other
reference.

Verified on **both boards**: EP4CGX150 (13 minor GCs + a full GC) and XC7A100T
DDR3 (2 minor GCs + a full GC), handler runs before and after, tag `0x5A5A`
intact. With the `multianewarray` fix reverted the run **dies during the churn
phase** and never reaches "done" — so the failure signal is a crash rather than
a clean FAIL line, and absence of `IntHandlerGcTest done` is the check.

Still open: whether any *other* runtime structure is reachable only through a
reference array built by `multianewarray`. `JVMHelp.ih` is the one we know of.

## 2. Sweep cost — reduced 27% (SDR) / 22% (DDR3), partly done

The sweep is O(handles in the nursery) and still dominates. Two changes so far,
both aimed at the number of memory accesses the common (dead-handle) path makes:

1. **Hoist the list heads into locals.** `freeList`/`useList` are static fields,
   and JOP keeps statics in main memory, so `Native.wrMem(freeList, ...)` was a
   `getstatic` (read) and `freeList = ref` a `putstatic` (write). The dead path
   was making **six** memory accesses per handle, not four.
2. **Splice runs instead of relinking each handle.** Dead handles are already
   chained in `youngList` order, so whole runs can be spliced onto `freeList`;
   a run only has to be closed where a survivor interrupts it. ~67 writes
   instead of 33k.

| | sweep ns/handle | worst pause |
|---|---|---|
| EP4CGX150 SDR | 1854 → 1441 → **1346** | 15.11 → **11.93 ms** |
| XC7A100T DDR3 | 1940 → 1727 → **1506** | 69.15 → **53.89 ms** |

Note the two boards responded differently: hoisting statics helped SDR far more
(-22% vs -11%, it has no L2 to cache them), while run-splicing helped DDR3 more
(-13% vs -6.6%). An earlier guess that the sweep was *not* memory-bound — based
on the two boards having near-identical per-handle costs — was wrong; removing
accesses clearly helps.

**Remaining per dead handle: 2 reads (`OFF_NEXT`, `OFF_SPACE`) + 1 write
(`OFF_PTR = 0`).** All three look irreducible without changing the handle layout:
the two reads are traversal and the survivor test, and the write is what stops a
conservative root resurrecting a freed handle (`push()` rejects `OFF_PTR == 0`).
Diminishing returns set in — removing a write bought only 6.6% on SDR — which
suggests a fixed per-iteration cost now dominates (~1350 ns ≈ 135 cycles for
three accesses plus loop overhead).

Further gains need a different approach, in rough order of promise:
- **Fewer iterations, not cheaper ones** — the sweep is O(objects allocated), so
  this is where nursery sizing (item 4) and the pause bound actually meet.
- **Handle layout**: put `OFF_NEXT` and the survivor mark in one word so the
  traversal needs a single read.
- **Segregate young handles** into a contiguous block so the walk is sequential.

## 3. The nursery zero — DONE (removed)

`minorGc` no longer calls `zeroMem(nurseryBase, nurseryTop)`. It was redundant
for the same reason the post-compaction bulk zero was (`5e0a3a0`): once
survivors are copied out the whole nursery is garbage, `allocGen` zeroes each
object's data before handing it out, and free memory is never scanned — only
live objects and roots.

Measured worst-case minor pause:

| board | before | after |
|---|---|---|
| EP4CGX150 SDR | 20.20 ms | **15.11 ms** (-25%) |
| XC7A100T DDR3 | 73.80 ms | **69.15 ms** (-6.3%) |

The SDR board gains more because the zero was a larger share there (5.10 ms of
20.2 vs 5.43 of 73.8). `zero` now reports 0.000 ms on both.

Note the incremental collector's own `zeroMem(copyPtr, allocPtr)` in
`finishCycle` is untouched — a different path, only used when `concurrentGc` is
enabled.

## 4. Pause bound — DONE (young-object cap)

The original plan was to size the nursery in bytes. That cannot bound the pause:
the sweep is O(young **handles**), and a nursery full of small objects holds far
more handles than the same space full of large ones. So the cap is on the young
object COUNT, which bounds the sweep regardless of object size:

```java
MAX_YOUNG_OBJECTS = (MINOR_TARGET_US - MINOR_FIXED_US) * 1000 / SWEEP_NS_PER_HANDLE
```

Derived, not tuned, from the hardware measurements above:
`MINOR_TARGET_US = 20000`, `SWEEP_NS_PER_HANDLE = 1600` (slowest board, DDR3
1506, rounded up), `MINOR_FIXED_US = 4500` (roots + mark + cards). That gives
9687 objects. `allocGen` collects when the nursery fills **or** the count is
reached, whichever comes first. Set `MINOR_TARGET_US = 0` to disable.

| board | worst pause | handles swept | note |
|---|---|---|---|
| XC7A100T DDR3 | **19.26 ms** (target 20) | 9687 = the cap | cap binds; 42 GCs vs 12 |
| EP4CGX150 SDR | 11.93 ms | 6168 | nursery fills first, cap never binds |

The model predicts 4.5 + 9687 × 1.6 µs = 20.0 ms against 19.26 ms measured, and
worst 19.257 vs mean 19.217 ms is a 0.2% spread — the bound is tight and
deterministic, which is the property we were actually after.

**Cost**: 3.5x more collections on DDR3, so the fixed ~4 ms of root scanning is
paid 3.5x as often. That is the real-time trade — bounded pause for lower
throughput. Anyone who wants throughput over latency should raise
`MINOR_TARGET_US`.

**Caveat**: `SWEEP_NS_PER_HANDLE` and `MINOR_FIXED_US` are measured constants for
*these two boards at 100 MHz*. A different clock, memory system or core count
invalidates them, and the bound silently becomes wrong rather than failing
loudly. Re-measure with `GcPauseTest` when the hardware changes — the A-E115FB
DDR2 board will need this.

**Root scan is now the floor**: at 3.88 ms it is 20% of the DDR3 pause and does
not shrink with the cap. Targeting much below ~10 ms means attacking that next.

## 5. Major GC worst case — MEASURED, and it is bad

`GcMajorPauseTest` forces full collections at increasing live-set sizes.
Previously nothing measured this at all: the timing lived in `majorGc()`, so a
direct `GC.gc()` was not counted, which is why `GcPauseTest` always reported
`major GCs 0`. Timing now lives inside `gc()` itself, with a mark/compact split
and a live-handle census, so every path is measured.

XC7A100T DDR3:

| live objs | pause | mark | compact | live handles |
|---|---|---|---|---|
| 6000 | 460 ms | 199 ms | 257 ms | 6024 |
| 18000 | 1150 ms | 486 ms | 660 ms | 18024 |
| 36000 | **2231 ms** | 916 ms | 1311 ms | 36024 |

**O(live) is confirmed** — the pause is linear in live handles, so `5e0a3a0` did
what it set out to do. But the constant is **~25 µs/handle for mark and
~36 µs/handle for compact**, against **1.3-1.5 µs/handle** for the minor sweep.
Both phases do roughly the same kind of work (walk handles, touch a few words),
so a 20-25x gap is not explained by what the code appears to do.

Even a nearly-empty heap is expensive: `GcPauseTest`'s explicit `GC.gc()` calls
now report 161 ms (SDR) and 99.6 ms (DDR3) with only ~4300 promoted handles live.

Tried and rejected: `compactAndSweep` took the monitor and touched the
`useList`/`freeList` statics **per handle**. Hoisting both out of the loop (kept,
it is correct and strictly safer than removing the lock) bought only **1.6%**, so
monitors are ~1 µs each and are not the problem.

**Leading hypothesis, not yet confirmed**: the merge sort inside
`compactAndSweep`. Sorting 36024 handles is ~545k merge steps, each doing about
three scattered handle accesses. At ~2.4 µs per step that is ~1.31 s — which is
exactly the measured compact time. It is O(n log n) over a range where log n only
moves 12.5→15.1, so it would look near-linear in this data. If that is right the
fix is a bucket/radix sort by address (O(n), and handle addresses are dense and
bounded), not a faster comparison sort.

To confirm before optimising: time `sortUseListByAddress()` separately from the
rest of `compactAndSweep`. Do that first — two hypotheses about this pause have
already been wrong.

Why it matters: 2.2 s is fatal for anything real-time, and the minor pause is now
bounded at 19 ms. A major GC currently fires only on tenure exhaustion, so it is
rare — but "rare and unbounded" is exactly the property RT systems cannot have.

## 6. Smaller items

- **`checkcast` is not implemented for array types.** `(int[]) someObject`
  throws an uncaught exception. Cost me two debugging cycles writing tests;
  worth either implementing or documenting in the programmer's guide.
- **`f_multianewarray` only supports 2 dimensions** — `dim != 2` prints
  "dimensions not supported" and calls `noim()`. Pre-existing.
- **An intermittent startup fault on the XC7A100T** — now seen **twice**, so no
  longer a one-off. Before `main`, an `Uncaught exception` that re-faults inside
  its own handler, printing endlessly. Both times an identical
  reprogram-and-retry ran clean, and both times the download itself verified OK
  (checksum good), so it is not a corrupted image. Unexplained. If it becomes
  frequent it is worth catching with the SWD probe or by trapping the first
  exception's type before the handler re-enters.
- **`GC_META_CHECK`** (in `GC.java`, off, compile-time folded) validates handle
  metadata at creation. It is what localised the `multianewarray` defect —
  turn it on if another mis-typed allocation ever shows up.
