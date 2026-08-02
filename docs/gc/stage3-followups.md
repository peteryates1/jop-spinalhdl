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

Verified on EP4CGX150: passes with the fix (13 minor GCs + a full GC, handler
runs, tag intact); with the `multianewarray` fix reverted the run **dies during
the churn phase** and never reaches "done". So the failure signal is a crash
rather than a clean FAIL line — absence of `IntHandlerGcTest done` is the check.

Still open: whether any *other* runtime structure is reachable only through a
reference array built by `multianewarray`. `JVMHelp.ih` is the one we know of.

## 2. Sweep cost dominates the minor pause — ~1.9 µs/handle

Measured worst-case minor pause is 20.2 ms (EP4CGX150 SDR) and 73.8 ms
(XC7A100T DDR3). The split on DDR3:

| phase | time | share |
|-------|------|-------|
| roots | 3.81 ms | 5% |
| mark  | 0.21 ms | 0% |
| **copy/sweep** | **64.4 ms** | **87%** |
| zero  | 5.43 ms | 7% |
| cards | 0.00 ms | 0% |

The sweep is O(handles in the nursery) at ~1850 ns (SDR) / ~1946 ns (DDR3) per
handle — six word accesses to a 32-byte handle costing ~190 cycles, which
suggests the handle area is missing cache badly. Worth attacking before any
nursery sizing: the constant sets the pause, and reducing it costs no
throughput, whereas shrinking the nursery does.

Ideas not yet tried: pack the fields the sweep touches into one cache line;
prefetch the next handle; segregate young handles into a contiguous block so
the walk is sequential.

## 3. The nursery zero is probably redundant (~7% of the pause)

`minorGc` calls `zeroMem(nurseryBase, nurseryTop)` — 5.43 ms on DDR3, 5.10 ms on
SDR. This is likely unnecessary for the same reason the post-compaction bulk
zero was (removed in `5e0a3a0`): every allocation path already zeroes its own
data before handing the object out (`allocGen`, both `newObject` branches,
`newArray`), and free memory is never scanned.

Not removed yet only because it was one change too many at the time. Should be
cheap to verify and worth ~7% of the pause.

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
- **A one-off startup fault on the XC7A100T**, once, during GC init: an
  `Uncaught exception` that re-faulted in its own handler. An identical
  reprogram-and-retry ran clean and it has not recurred across many runs since.
  Unexplained; noted in case it reappears.
- **`GC_META_CHECK`** (in `GC.java`, off, compile-time folded) validates handle
  metadata at creation. It is what localised the `multianewarray` defect —
  turn it on if another mis-typed allocation ever shows up.
