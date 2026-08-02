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

## 4. Nursery sizing (the original Stage 3 goal)

Only meaningful once (2) and (3) settle, since they change the constant. The
model that now holds:

    pause ≈ (objects in nursery) × ~1.9 µs + (nursery bytes ÷ fill rate) + ~3 ms roots

Note this is **object count**, not nursery bytes — the opposite of what
`gc-optimization-options.md` assumed. That document's ~75 ms estimate is
retired: it predicted zeroing would be ~83% of the pause; measured it is 5-16%.

## 5. `GC.gc()` is O(live) now but still unbounded in the mutator's view

`5e0a3a0` made a major GC O(live) rather than O(heap), and replaced the O(n²)
handle sort with a merge sort. It is no longer a hang, but it is still a
stop-the-world pause with no bound published anywhere. `GcPauseTest` reports
`gcMajorCount`/`gcMajorMax`; nothing yet drives enough promotion on the 256 MB
board to make a major GC fire naturally, so the worst case is unmeasured.

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
