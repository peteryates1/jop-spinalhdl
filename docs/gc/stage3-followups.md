# GC Stage 3 — history and open follow-ups

Things surfaced while measuring and fixing the generational collector: what was
done and why, plus the items still open. None of them block the current state:
the pause bound holds on all four boards it has been measured on, and the boards
in the handshake table pass DoAll 66/66 along with the GC stress, pause and
multi-array tests.

**Item 1 is the only one still open**, and it is the largest number left in the
collector by an order of magnitude. Items 2-5 are done; they are kept because
each records a wrong turn worth not repeating.

> **Pause numbers live in [`../current-status.md`](../current-status.md) §3, not
> here.** This file kept its own copies through Stage 3 and they drifted out of
> date within two days, so the tables below have been cut back to the *deltas*
> each change produced. For the current per-board figures and the tuning
> constants actually compiled in, read current-status; if the two disagree,
> current-status wins.

Context: `gc-generational-implementation-plan.md`, `stage2-generational-design.md`,
and `copy-phase-redesign.md` for the successor to items 3 and 5.
Commits: `1916415` (measure), `8a8e154` (young list), `5e0a3a0` (256 MB full GC),
`78cc968` (multianewarray / zero-size), `dfe7f46` (constants retuned),
`42a52aa` (tenure-bounded card scan), `4a0b446` (512-word cards).

---

## 1. Major GC worst case — SPLIT MEASURED, sort confirmed — **OPEN**

**2026-08-06, XC7A100T DDR3.** `gcMajTCompact` now splits into sort / slide /
copy, which settles the standing hypothesis and kills a second one.

| live objs | pause | mark | compact | **sort** | slide | copy | live words |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 6000 | 452.6 | 199.5 | 252.2 | **186.8** | 65.4 | 33.9 | 48244 |
| 18000 | 1134.5 | 486.3 | 647.3 | **554.6** | 92.8 | 9.4 | 72244 |
| 36000 | **2214.9** | 915.8 | 1298.2 | **1127.6** | 170.6 | 9.5 | 108244 |

- **The merge sort is the single largest term: 1127.6 ms of the 1298.2 ms
  compact phase, and 51% of the whole pause.** The hypothesis recorded below was
  right.
- **The object data copy is 9.5 ms — 0.4%.** That is the important negative
  result: a hardware block-copy engine (the zero-fill DMA is the obvious
  precedent, 110.7x on DDR3) would take 9.5 ms off a 2215 ms pause. The live set
  is only 108k words; a major GC here moves almost no data and spends its time
  chasing pointers through the handle table. **Do not build copy acceleration
  for this.**
- Copy is 33.9 ms on the first collection and ~9.5 ms after, because once the
  heap is compacted only newly allocated objects move.

**A real defect found while measuring, now fixed.** `push()` and `pushYoung()`
screened every candidate root with `ref >= mem_start + handle_cnt*HANDLE_SIZE`:
three static reads (statics live in main memory) plus an **`imul` bytecode**.
`HANDLE_SIZE` is 8, but javac emits `imul` rather than strength-reducing, and
`imul` defaults to **Microcode** — a ~775-cycle shift-add loop — on any preset
that does not ask for an ICU multiplier, which includes `xc7a100tDbSerial`
(it sets only `idiv`/`irem` to hw). Line 429 of `GC.java` had already written
the identical product as `<< 3` for exactly this reason. Precomputing
`handleEnd` once at init:

| | before | after |
|---|---:|---:|
| mark @36k | 915.8 ms | **422.2 ms** (-54%) |
| major pause @36k | 2214.9 ms | **1720.8 ms** (-22%) |
| minor pause | 12.523 ms | **11.757 ms** |
| minor stack+static root scan | — | 0.235 ms |

`corrupt 0`, `MAJOR OK`, retained 64/64 on both `GcMajorPauseTest` and
`GcPauseTest`. Compact is unchanged, as expected — the fix only touches `push`.

**Where the remaining 1720 ms goes @36k live**: sort **1127 ms (65%)**, mark
422 ms (25%), slide 171 ms (10%), copy 9.6 ms (0.6%).

**An anomaly worth resolving before promising a speedup factor**: sort cost per
handle is *flat* at ~30 µs across n = 6024..36024 (31.0, 30.0, 30.8, 30.0, 29.6,
31.3). A bottom-up merge sort makes ceil(log2 n) passes — 13 to 16 over that
range — so per-handle cost should rise ~23%. It does not. So "n x passes x
constant" is not the right cost model, and the pass count should be measured
before assuming a linear-time sort buys 4x or 10x. Two hypotheses about this
pause were already wrong; this document has a poor record with unmeasured cost
models.

**Why a desktop JVM does not have this problem**: it is not software versus
hardware, it is the data structure. HotSpot has no handle table — object
references are direct pointers, marking uses a side bitmap with good locality,
and compaction computes forwarding addresses over regions. JOP's handle
indirection is what forces a full address sort of every live object on every
major collection, and a handle is exactly one 256-bit cache line, so no walk of
the handle table ever gets spatial reuse. That is the thing to attack, and it is
the same root cause as the minor GC's copy phase.

---

### Original entry (measurements above supersede the numbers here)

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

**Check the copy phase first, though**: `compactAndSweep` walks `useList` the
same way the copy phase walks `youngList`, and the copy phase's problem turned
out to be placement — the handle table is far larger than the cache and a handle
is exactly one cache line. This constant may have the same cause, in which case
one redesign fixes both. See [`copy-phase-redesign.md`](copy-phase-redesign.md)
and the *Coupling* section of current-status.

Why it matters: 2.2 s is fatal for anything real-time, and the minor pause is now
bounded at 14.1 ms worst across four boards. A major GC currently fires only on
tenure exhaustion, so it is rare — but "rare and unbounded" is exactly the
property RT systems cannot have.

## 2. `addInterruptHandler` under GC — DONE (IntHandlerGcTest)

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

## 3. Sweep cost — reduced 27% (SDR) / 22% (DDR3), closed

The sweep is O(handles in the nursery), and at the time was the term that
dominated. Two changes, both aimed at the number of memory accesses the common
(dead-handle) path makes:

1. **Hoist the list heads into locals.** `freeList`/`useList` are static fields,
   and JOP keeps statics in main memory, so `Native.wrMem(freeList, ...)` was a
   `getstatic` (read) and `freeList = ref` a `putstatic` (write). The dead path
   was making **six** memory accesses per handle, not four.
2. **Splice runs instead of relinking each handle.** Dead handles are already
   chained in `youngList` order, so whole runs can be spliced onto `freeList`;
   a run only has to be closed where a survivor interrupts it. ~67 writes
   instead of 33k.

| sweep ns/handle | baseline | + hoist statics | + splice runs |
|---|---|---|---|
| EP4CGX150 SDR | 1854 | 1441 | **1346** |
| XC7A100T DDR3 | 1940 | 1727 | **1506** |

Note the two boards responded differently: hoisting statics helped SDR far more
(-22% vs -11%, it has no L2 to cache them), while run-splicing helped DDR3 more
(-13% vs -6.6%). An earlier guess that the sweep was *not* memory-bound — based
on the two boards having near-identical per-handle costs — was wrong; removing
accesses clearly helps.

(The later three-board `GcPauseTest` run measured DDR3 at 1567 ns/handle rather
than 1506 — a different run, not a regression. The cross-board figures in
current-status §3 are the ones the tuning constants were derived from.)

**Remaining per dead handle: 2 reads (`OFF_NEXT`, `OFF_SPACE`) + 1 write
(`OFF_PTR = 0`).** All three look irreducible without changing the handle layout:
the two reads are traversal and the survivor test, and the write is what stops a
conservative root resurrecting a freed handle (`push()` rejects `OFF_PTR == 0`).
Diminishing returns set in — removing a write bought only 6.6% on SDR — which
suggests a fixed per-iteration cost now dominates (~1350 ns ≈ 135 cycles for
three accesses plus loop overhead).

**Superseded — do not plan from this item.** The three directions guessed at
here (fewer iterations, fold `OFF_NEXT` and the survivor mark into one word,
segregate young handles contiguously) were the right instinct, but the sweep is
no longer where the time is. Once the constants, the tenure-bounded card scan
and finer cards had landed, the **copy phase** was 79-82% of the minor pause on
every board and had not moved at any point during Stage 3.

The developed version of the last two bullets — a dense array of refs for the
traversal and a bitmap for the survivor mark, taking ~6400 scattered line
fetches down to ~825 sequential ones — is
[`copy-phase-redesign.md`](copy-phase-redesign.md), with a four-stage plan and
the constraints that must not break. Go there, not here.

## 4. The nursery zero — DONE (removed)

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

## 5. Pause bound — DONE (young-object cap)

The original plan was to size the nursery in bytes. That cannot bound the pause:
the sweep is O(young **handles**), and a nursery full of small objects holds far
more handles than the same space full of large ones. So the cap is on the young
object COUNT, which bounds the sweep regardless of object size:

```java
MAX_YOUNG_OBJECTS = (MINOR_TARGET_US - MINOR_FIXED_US) * 1000 / SWEEP_NS_PER_HANDLE
```

`allocGen` collects when the nursery fills **or** the count is reached,
whichever comes first. Set `MINOR_TARGET_US = 0` to disable.

**The model's shape held; its constants did not.** `fixed + swept x per-handle`
predicted all three measured boards to within 0.01 ms — but the constants were
derived from the DDR3 board alone and it met the target by luck, its fixed cost
over budget and its per-handle cost under, the two errors cancelling. On DDR2
both errors pointed the same way and the bound broke by 27%. Retuned to the
slowest board in `dfe7f46`: `SWEEP_NS_PER_HANDLE` 1600 -> **1750**,
`MINOR_FIXED_US` 4500 -> **8800**, `MAX_YOUNG_OBJECTS` 9687 -> **6400**.
Current per-board pauses are in current-status §3 item 2.

**Cost**: more collections, so the fixed root-scan cost is paid more often. That
is the real-time trade — bounded pause for lower throughput. Anyone who wants
throughput over latency should raise `MINOR_TARGET_US`. Small-heap boards are
unaffected by a cap change: they sweep fewer handles than the cap because the
nursery binds first.

**Caveat, and it has already bitten once**: these are measured constants for
specific hardware. A different clock, memory system or core count invalidates
them, and the bound then **silently becomes wrong rather than failing loudly**.
Re-measure with `GcPauseTest` when the hardware changes.

**Correcting the note that stood here**: this item used to say "root scan is now
the floor at 3.88 ms — targeting much below ~10 ms means attacking that next."
That was wrong, and acting on it would have wasted a day. `gcTRoots` bundled two
different scans under one timer; splitting it (`42a52aa`) showed the stack and
static scan was **0.647 ms — 3% of the pause** — while the dirty-card walk was
**7.671 ms, 38%**. The optimisation this paragraph recommended targeted the 3%.

The card walk was nearly all waste: it scanned the whole tenure span, but tenure
is two used regions with a large free gap (compacted data grows up from
`heapStart` to `copyPtr`, promotions grow down from `tenureTop` to `allocPtr`).
On the 1 GB board the scanned span was **99.98% free**. Scanning only the two
used regions, then halving card size (`4a0b446`, budget 16 -> 64 KB, cards 2048
-> 512 words, no RTL change — `cardShift` is derived and read at runtime via
`IO_CARD_SHIFT`), took the A-E115FB card scan 7.671 -> 5.122 -> **1.931 ms** and
the whole pause 25.376 -> **14.143 ms**, 44% off.

Measure before optimising is the lesson, and it is the second time in this
document that a confident guess about where the pause went was wrong.

## 6. Smaller items

- **`checkcast` is not implemented for array types.** `(int[]) someObject`
  throws an uncaught exception. Cost me two debugging cycles writing tests;
  worth either implementing or documenting in the programmer's guide.
- **`f_multianewarray` only supports 2 dimensions** — `dim != 2` prints
  "dimensions not supported" and calls `noim()`. Pre-existing, and now tracked
  as **item 23 in current-status** (investigate and assess generalising it).
  Noted here because the `78cc968` defect was in exactly the per-level type
  assignment that generalising has to get right at every level, not just two.
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
