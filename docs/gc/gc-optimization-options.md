# GC Optimization Options for Large Memory

> **STATUS: ANALYSIS / FUTURE WORK.**
> This document analyses GC performance on large memories (64 MB–1 GB) and
> describes four optimization options, plus hardware accelerators. The
> primary motivation is supporting a 1 GB DDR2 SODIMM (64-bit bus) on the
> A-E115FB V2 (EP4CE115) board and DDR3 on the Wukong XC7A100T.
>
> Current GC implementation: `java/runtime/src/jop/com/jopdesign/sys/GC.java`  
> Background: [Mark-Compact GC Design](gc-mark-compact-design.md),
> [Incremental GC Analysis](incremental-gc-analysis.md)

---

## 1. Current GC Algorithm

JOP uses an **incremental mark-compact** collector. Key properties:

- **Handle indirection**: Every object is accessed through an 8-word handle.
  Compaction only rewrites `OFF_PTR` in handles — no heap pointer scan needed.
- **MAX_HANDLES = 65536**: The handle table is bounded regardless of heap size.
  Sweeping 65536 handles costs ~6 ms at 100 MHz.
- **Mark phase**: O(live_set) — traverses only reachable objects via gray list.
- **Compact phase**: O(live_set) — slides live objects to low addresses in
  handle-address order; dead handles go to free list.
- **Free-space zeroing**: O(heap_size − live_set) — **the dominant cost**.

### 1.1 The Zeroing Bottleneck

After every GC cycle (both full STW and incremental finish), the free region is
zeroed word-by-word (`GC.java:869–871`, `GC.java:725–727`):

```java
for (int i = copyPtr; i < allocPtr; ++i) {
    Native.wrMem(0, i);   // one SDRAM write per word, ~6 cycles each
}
```

JOP's single-word SDRAM writes (activate + write + precharge) achieve roughly
**64 MB/s** throughput at 100 MHz with realistic SDRAM timing. With a typical
live set of ~1 MB:

| Heap size | Free space to zero | STW pause  |
|-----------|-------------------|------------|
| 8 MB      | ~7 MB             | ~110 ms    |
| 32 MB     | ~31 MB            | ~490 ms    |
| 64 MB     | ~63 MB            | ~980 ms    |
| 256 MB    | ~255 MB           | ~4.0 s     |
| 1 GB      | ~1023 MB          | ~16.0 s    |

Mark + compact are negligible by comparison (≪ 50 ms even for large live sets).

**Note on incremental GC**: The `gcIncrement()` path decomposes mark and compact
into bounded slices (`MARK_STEP=20`, `COMPACT_STEP=10`), but `finishCycle()`
still zeroes all free space in one shot. The worst-case STW pause is the full
zeroing cost regardless of the incremental path.

---

## 2. Option 1: Heap Cap

**Effort**: trivial — one line in `JopTop.scala`  
**Runtime changes**: none

Cap the active heap at 32–64 MB regardless of the physical memory size. The
existing `min(cc.memConfig.mainMemSize, md.sizeBytes)` fix in `JopTop.scala`
(see README TODO) makes this configurable per preset.

### Performance

| Cap | GC pause | Free DRAM wasted |
|-----|----------|-----------------|
| 32 MB  | ~490 ms  | ~224 MB of 256 MB DDR3 |
| 64 MB  | ~980 ms  | ~192 MB of 256 MB DDR3 |
| 32 MB  | ~490 ms  | ~968 MB of 1 GB DDR2  |

**Verdict**: Practical and immediate. Suitable when total allocation is modest.
Pause of ~490 ms at 32 MB is tolerable for non-real-time applications. Wastes
most of the available DRAM — acceptable if the use case doesn't need it.

---

## 3. Option 2: Region-Based GC

**Effort**: medium — partition heap, per-cycle compact one region  
**Runtime changes**: `GC.java` heap region logic, handle-to-region mapping

Divide the heap into fixed regions (e.g., 32 MB each). Each GC cycle compacts
one region and zeroes only that region's free space.

### How it works

- Handle table records which region each object lives in (one extra bit, or
  derived from `OFF_PTR`).
- Mark phase remains **global** — you must trace from all roots to know which
  objects in the target region are live. Cannot be region-scoped without a
  remembered set.
- Compact phase selects the region with the highest garbage fraction, compacts
  it in place, and zeroes its free tail.

### Performance

| Target region | Zone zeroed | Pause per cycle | Full-heap coverage |
|---------------|------------|-----------------|-------------------|
| 32 MB / 8 zones | ~30 MB  | ~490 ms         | 8 cycles           |
| 32 MB / 32 zones | ~30 MB | ~490 ms         | 32 cycles          |

Peak pause is the same as the heap cap option, but total heap can be arbitrarily
large. GC amortises across more mutator time because only 1/N of the heap is
collected per cycle.

**Limitation**: Mark phase overhead scales with live set (still O(live_set)),
not with region size. The real win is that only one region is zeroed per cycle.

**Verdict**: Reduces average GC overhead and unlocks large heaps without a heap
cap. Moderate complexity. Best combined with HW accelerator 1 (zero-fill DMA)
to cut the per-region pause.

---

## 4. Option 3: Parallel GC (SMP)

**Effort**: high — SMP coordination, work-stealing gray list, parallel zero-fill  
**Runtime changes**: `GC.java` parallel phases, halt protocol change

Use halted cores to perform GC work instead of spinning. Currently
`IO_GC_HALT` freezes other cores entirely. Parallel GC changes the protocol:
core 0 orchestrates; other cores execute assigned GC work while "halted" to
the mutator.

### Parallel decomposition

| Phase | Parallelisable? | Speedup (4 cores) |
|-------|----------------|-------------------|
| Root scan (STW) | Partial — each core scans its own stack | ~4× |
| Mark | Yes — work-steal from shared gray list | ~3–4× |
| Compact | No — sliding compaction is sequential | 1× |
| Zero-fill | Yes — partition free space, each core zeroes its slice | ~4× |

### Performance (4 cores, 256 MB heap)

Sequential: ~4.0 s. With parallel zero-fill: ~1.0 s. With parallel mark: ~0.8 s.

### Implementation notes

- The gray list already uses `GC.mutex` for push/pop — compatible with
  parallel pop (work-stealing).
- Compact cannot be parallelised without significant restructuring (objects
  must move in address order to avoid overwriting uncopied data — proven by
  the `sortListByAddress` invariant).
- Parallel zero-fill requires assigning disjoint address ranges to cores and
  a barrier before `Native.invalidate()`.
- Halt protocol change: `IO_GC_HALT` must distinguish "freeze" (normal op)
  from "GC worker" mode. Needs microcode + `CmpSync` changes.

**Verdict**: Meaningful speedup for multi-core systems. Large implementation
effort. Best combined with Option 4 (generational) to make major GC rare.

---

## 5. Option 4: Generational GC

**Effort**: very high — nursery allocator, minor GC, promotion, remembered set  
**Runtime changes**: `GC.java` major rewrite; write barrier already present

The dominant insight: most objects die young. A **nursery** (young generation)
is collected frequently at low cost; long-lived objects are promoted to a
**tenure space** collected rarely.

### Write barrier: already in GC.java

`GC.java:1203–1281` implements `writeBarrier()`. It is currently a
**snapshot-at-beginning barrier** for the incremental concurrent collector
(marks the old value gray on a store). This must be replaced/extended with a
**generational remembered set barrier**: when a tenured object stores a
reference to a nursery object, record the tenured object's card in a card table.

### Memory layout

```
heapStart
+------------------+  <- nursery base
|   Nursery        |  ~4–16 MB (fast allocation, frequent collection)
+------------------+  <- nurseryTop / tenureBase
|   Tenure space   |  remaining heap (infrequent collection)
+------------------+  <- allocPtr
```

Allocation: nursery uses a simple bump pointer. When nursery is full, trigger
minor GC. Survivors (objects reachable from roots or from remembered set) are
promoted (copied) to tenure space. Tenure uses the existing mark-compact allocator.

### Minor GC cost (nursery only)

With nursery = 8 MB and ~10% survival rate (800 KB survivors):

| Step | Cost |
|------|------|
| Root scan | ~5 ms (STW) |
| Mark nursery reachable | ~10 ms (O(nursery live set)) |
| Copy survivors to tenure | ~12 ms (800 KB × 6 cycles) |
| Zero nursery | ~125 ms (7.2 MB free × 6 cycles) |
| **Total minor GC** | **~150 ms** |

Minor GC is triggered every time the nursery fills (~every 100–500k allocations
depending on object size). Major GC is triggered only when tenure space
approaches full — typically 10–50× less frequent than minor GC.

With a 4 MB nursery, minor GC pause drops to ~75 ms.

### Major GC cost (tenure space)

Major GC on tenure space is the existing mark-compact cycle. With a 256 MB
tenure space and 50 MB live set:

| Step | Cost |
|------|------|
| Mark | ~50 ms (O(live_set)) |
| Compact | ~50 ms (50 MB × 6 cycles) |
| Zero free | ~3.2 s (206 MB free) |

Major GC remains expensive for large heaps without HW zero-fill assist (see
Section 6). But it is rare — if the tenure space is 256 MB and the live set
grows at 1 MB/minor-GC, a major GC fires every ~200 minor GCs (~30 seconds of
application time).

### Implementation steps

1. **Nursery allocator**: Add `nurseryAllocPtr` / `nurseryTop` pointers.
   `newObject()` and `newArray()` allocate from nursery by default.
2. **Minor GC**: On nursery full, scan roots + card table → mark reachable
   nursery objects → copy survivors to tenure via bump pointer → zero nursery.
3. **Card table**: 1 bit per 64-byte (16-word) card = 4 KB for 256 MB heap.
   Store in BRAM. Dirty cards scanned during minor GC root scan.
4. **Write barrier change**: Replace snapshot-at-beginning push with card-table
   dirty-bit set. New `writeBarrier()` body: if dest object is in tenure and
   stored value is in nursery, set `cardTable[card(dest)] = 1`.
5. **Promotion**: During minor GC, copy survivors to tenure bump pointer;
   update handle `OFF_PTR` to new tenure address.
6. **Major GC**: Existing mark-compact on tenure space. Triggered by tenure
   free space threshold (e.g., < 25%).

### Interdependency with SMP

For SMP (Option 3 + Option 4 combined): minor GC still requires STW for root
scan, but other cores zero the nursery in parallel while core 0 copies survivors.
This brings minor GC pause to ~40–60 ms on 4 cores with an 8 MB nursery.

**Verdict**: The right long-term solution for large-heap (256 MB+) operation.
Reduces typical pause from seconds to ~150 ms minor / rare major. High
implementation effort but the `writeBarrier()` infrastructure already exists.
Recommended as the primary GC improvement target.

---

## 6. Hardware Accelerators

All four software options benefit from hardware assistance targeting the dominant
cost: bulk memory operations (zero-fill, object copy).

### 6.1 Bulk Zero-Fill DMA (highest impact)

**Target**: replaces the `for (i = copyPtr; i < allocPtr) wrMem(0, i)` loop.  
**Location**: extend `BmbMemoryController` with a `ZERO` state.

JOP already has a `memCopy` hardware state machine (the `COPY` states in
`BmbMemoryController`). A zero-fill variant:

- New I/O registers: `IO_ZERO_START` (word address), `IO_ZERO_END` (word address)
- Writing `IO_ZERO_START` launches the DMA; reading returns `rdy` (0=busy, 1=done)
- State machine issues burst SDRAM writes (BL=4 or BL=8) with `wrData = 0`
- Burst writes bypass the JOP pipeline overhead (~6 cycles/word) and drive the
  SDRAM at near-peak bandwidth

**Burst write throughput** (SDR SDRAM, BL=4, 80 MHz):
`4 words × 80 MHz / (activate + burst + precharge ≈ 8 cycles) = ~40 MW/s = ~160 MB/s`

vs. current single-word: ~16 MW/s = ~64 MB/s → **~2.5× speedup**.

With pipelined activate/precharge (open-page policy), burst write throughput
approaches the SDRAM peak: 80 MHz × 16-bit × 2 = ~320 MB/s.

| Heap size | Zeroing (SW) | Zeroing (HW DMA) | Speedup |
|-----------|-------------|-----------------|---------|
| 32 MB     | 490 ms      | ~195 ms         | 2.5×    |
| 256 MB    | 4.0 s       | ~1.6 s          | 2.5×    |
| 1 GB      | 16 s        | ~6.4 s          | 2.5×    |

Combined with Option 4 (generational), the nursery zero-fill cost drops from
~125 ms to ~50 ms for an 8 MB nursery.

**Implementation**: ~150 lines of Scala in `BmbMemoryController.scala`. The
`COPY` state machine is the template. Key differences: no source address, write
`0` directly, drive burst write commands.

### 6.2 Object Copy DMA (speeds compact phase)

The compact inner loop (`GC.java:604–606`) copies each live object word-by-word.
This is the same operation `memCopy` hardware already handles.

Current compact flow (software):
```java
for (int i = 0; i < size; ++i) {
    Native.wrMem(Native.rdMem(oldAddr + i), compactPtr + i);
}
```

With HW copy DMA, compact issues `(src, dst, wordCount)` to hardware I/O
registers and moves on to the next handle while the DMA runs. The JOP pipeline
stalls on the I/O `rdy` check only when it needs the next handle's pointer.

**Impact**: compact phase is currently O(live_set) but with ~12 cycles/word
overhead (read + write). DMA burst copy achieves ~4 cycles/word → ~3× speedup
on compact. Since compact is not the dominant cost for large heaps (zeroing is),
this matters more for small heaps where compact takes a comparable share of
total GC time.

**Implementation**: The existing `memCopy` state machine (`MCOPY` states) may
already cover this. Verify that `Const.IO_MEMCOPY_*` I/O addresses are
accessible from GC.java and that the state machine handles overlapping addresses
correctly (compact always moves forward so `dst ≤ src` — safe for forward copy).

### 6.3 Write Barrier Hardware Assist (for Option 4)

For generational GC, every `putfield` and `aastore` that stores a reference
needs to check whether the destination is in tenure and the stored value is in
nursery, and dirty the corresponding card.

Currently `writeBarrier()` is called from `JVM.java` in software (~15–20 cycles
overhead per barrier-relevant store). A hardware assist:

- On every SDRAM write where `destAddr` falls in the tenure range, the memory
  controller sets bit `(destAddr − tenureBase) >> 4` in a BRAM card table.
- No software overhead — the card dirty happens at the same time as the write.
- GC reads the card table from BRAM during minor GC root scan (single-cycle
  BRAM reads, far cheaper than chasing a software remembered set).

**Implementation**: ~50 lines in `BmbMemoryController.scala`. BRAM card table:
1 bit per 16-word (64-byte) card = 4 KB for 256 MB heap → fits in one 36Kb
BRAM. Tenure base/top configured via I/O registers.

**Impact**: Eliminates software write barrier cost entirely. Roughly 10–20 cycle
saving per store to a tenured reference array. Critical for applications with
high pointer-mutation rates (e.g., graph algorithms).

---

## 7. Recommended Path

### Near-term: Heap Cap + Zero-Fill DMA

1. **Heap cap** (`JopTop.scala` one-liner): cap DDR3 at 32–64 MB. Pause ~500 ms.
   Zero code changes in the Java runtime.
2. **Zero-fill DMA** (`BmbMemoryController`): ~150 lines of Scala. Cuts pause to
   ~200 ms at 32 MB heap. No Java runtime changes.

This combination is feasible in a single sprint and gives usable DDR2/DDR3 GC
without touching `GC.java`.

### Long-term: Generational GC (Option 4)

Option 4 is the right target for large-memory (256 MB – 1 GB) operation:

- Minor GC pause: ~150 ms (8 MB nursery), ~75 ms (4 MB nursery)
- Major GC: rare (~30 s interval with 1 MB/s allocation rate)
- With zero-fill DMA: minor GC ~60 ms, major GC zeroing 2.5× faster
- With SMP parallel GC: minor GC ~40 ms on 4 cores

Recommended implementation order for Option 4:
1. Add `nurseryAllocPtr` / `nurseryTop` to `GC.init()` and `newObject()`/`newArray()`
2. Implement `minorGc()`: root scan + card table scan → mark nursery reachable →
   copy survivors to tenure → zero nursery
3. Change `writeBarrier()` from snapshot-at-beginning to card-table dirty bit
4. Hook `minorGc()` into `gc_alloc()` (try minor before full STW)
5. Add card-table BRAM + HW write barrier assist in memory controller
6. Validate with `JopSmallGcBramSim` and hardware DoAll.jop runs

---

## 8. Complexity / Risk Summary

| Option | Java runtime | SpinalHDL | Microcode | Risk |
|--------|-------------|-----------|-----------|------|
| 1. Heap cap | None | 1 line | None | Very low |
| 2. Region-based | Medium | None | None | Medium |
| 3. Parallel GC | High | Low (halt protocol) | Low | High |
| 4. Generational | Very high | Low (card table) | None | Very high |
| HW: Zero-fill DMA | None (I/O call) | Medium | None | Low |
| HW: Object copy DMA | Tiny (use existing memCopy I/O) | None | None | Very low |
| HW: Write barrier assist | None (card table in HW) | Low | None | Low |
