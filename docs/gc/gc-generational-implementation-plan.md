# Generational GC — Implementation Plan (Option 4)

> **STATUS: PLAN / IN PROGRESS.**
> Staged implementation plan for [Option 4: Generational GC](gc-optimization-options.md#5-option-4-generational-gc),
> chosen as the best deterministic GC for real-time (bounded minor-GC pause =
> fixed nursery size; constant-time HW write barrier; rare, schedulable major GC).
>
> Precursor complete: reliable full-speed (2 Mbaud) `.jop` upload + run on the
> QMTECH XC7A100T + DB_FPGA V5 board over DDR3, verified with DoAll 66/66. This
> lets us measure GC behaviour on real 256 MB DDR3 — the regime the BRAM sim
> cannot reach.

---

## 1. Findings from the current code (`GC.java`, `BmbMemoryController.scala`)

Three things the code confirms/refines vs. the options doc:

1. **Handles never move — only object data does.** `newObject`/`newArray` split
   each object into a fixed *handle* (from `freeList`) plus *data* (`allocPtr -= size`,
   grows down). Compaction/promotion rewrites the handle's `OFF_PTR`; the handle
   table itself is fixed (`MAX_HANDLES = 65536`). **The nursery is therefore a
   data-region change only** — survivors' data copies nursery→tenure and we
   rewrite `OFF_PTR`. Simpler than a classic copying nursery.

2. **The existing write barrier needs real surgery, not a tweak.**
   `writeBarrier(handle, index)` (`GC.java:1203-1281`) is a *pre-write,
   old-value* snapshot-at-beginning barrier — it never sees the *new* value being
   stored. A generational remembered-set barrier needs the new value (to detect a
   tenured→nursery pointer). Rather than change the barrier signature and the
   JVM.java/microcode call convention, we do **HW card-marking** (see Stage 1):
   the memory controller dirties a card on any write into the tenure range. Zero
   software cost, constant-time (RT-friendly).

3. **Minor GC is zeroing-bound**, so HW zero-fill is a prerequisite, not an
   add-on. Doc estimate: ~75–125 ms just to zero a 4–8 MB nursery in software.
   A HW zero-fill DMA both speeds this ~2.5× **and makes it deterministic**
   (fixed burst timing vs. a variable software loop) — the actual RT win.
   `BmbMemoryController` already has a GC copy state machine (`cp0-cpstop`,
   the "GC copy states"), which is the template for a `ZERO` state.

---

## 2. Staged plan

Ordered to de-risk: start with the self-contained HW foundation that improves
*every* GC path and is directly measurable on the hardware upload loop, before
the high-risk `GC.java` generational rewrite.

| Stage | What | Risk | Depends on |
|---|---|---|---|
| **0. HW Zero-Fill DMA** | `ZERO` state in `BmbMemoryController` (template: `cp0-cpstop`) + `IO_ZERO_START`/`IO_ZERO_END` I/O regs; replace the two SW zero loops (`GC.java:725,869`) with a DMA call (SW fallback retained) | Low | — |
| **1. HW card-marking barrier** | Memory controller dirties a BRAM card table (1 bit / 16-word card) on writes into the tenure range; GC reads it during minor root scan | Low–Med | — |
| **2. Nursery + `minorGc()`** | Bump-pointer nursery in `newObject`/`newArray`; minor GC = root scan + card scan → copy survivors to tenure → HW-zero nursery | High | 0, 1 |
| **3. Tune & validate** | Per-preset nursery sizing; major-GC trigger threshold; validate bounded ~75 ms minor pause on real 256 MB DDR3 | Med | 2 |

---

## 3. Stage 0 detail — HW Zero-Fill DMA

**Goal:** replace `for (i=copyPtr; i<allocPtr) Native.wrMem(0,i)` (`GC.java:725,869`,
and later the nursery zero) with a hardware burst-write DMA. Deterministic timing,
~2.5× throughput.

### HDL (`spinalhdl/src/main/scala/jop/memory/BmbMemoryController.scala`)
- Add `ZERO_RUN` / `ZERO_WAIT` states modelled on the existing GC copy states.
- I/O-mapped registers (new `Const.IO_ZERO_*`):
  - `IO_ZERO_START` (word address) — writing it latches start.
  - `IO_ZERO_END` (word address) — writing it launches the DMA.
  - read `IO_ZERO_STATUS` — 0 = busy, 1 = done (`rdy`).
- State machine issues BMB/SDRAM writes of `0` across `[start, end)`, using the
  burst path where available (BL=4/8). Pipeline stalls only on the `rdy` poll.

### Runtime (`GC.java` + `Const.java`)
- Add `Native`/`Const` hooks for the new I/O regs.
- New helper `zeroMem(from, to)`: launch DMA, poll `rdy`. Keep the SW loop behind
  a `USE_HW_ZERO` flag as fallback / for boards without the DMA.
- Swap the two zero loops (`finishCycle` ~725, `gc` ~869) to `zeroMem`.

### Test (uses the new upload/run loop)
1. Sim first: `JopSmallGcBramSim` / a targeted BRAM sim — correctness (region
   reads back zero, no over/underrun at bounds).
2. Hardware microbenchmark: a `.jop` that times zeroing N MB on DDR3, SW vs DMA
   — confirm ~2.5× and record deterministic timing.
3. Regression: DoAll 66/66 on hardware still passes with `USE_HW_ZERO` on.

**Scope:** ~150 lines Scala + small `GC.java`/`Const.java` changes. No GC
algorithm change — safe stepping stone.

---

## 4. Stages 1–3 outline (expand when reached)

- **Stage 1 (card table):** BRAM card table sized 1 bit / 16-word card (4 KB for
  256 MB). Memory controller sets `card[(addr−tenureBase)>>4]` on tenure-range
  writes. `tenureBase`/`tenureTop` via I/O regs. GC clears cards after scanning.
- **Stage 2 (nursery + minorGc):** `nurseryBase/nurseryTop/nurseryAllocPtr` in
  `init`; allocate data from nursery; on full → `minorGc()`: scan stack/static
  roots + dirty cards → mark nursery-reachable → copy survivors to tenure bump
  pointer, rewrite `OFF_PTR` → HW-zero nursery → clear cards. Major GC = existing
  mark-compact on tenure, triggered by tenure-free threshold.
- **Stage 3:** tune nursery size (4 MB ⇒ ~75 ms bound), validate on hardware.

---

## 5. References
- [GC Optimization Options](gc-optimization-options.md) — full analysis, HW accelerators §6, RT compatibility §8.
- [Mark-Compact GC Design](gc-mark-compact-design.md)
- `java/runtime/src/jop/com/jopdesign/sys/GC.java`
- `spinalhdl/src/main/scala/jop/memory/BmbMemoryController.scala`
