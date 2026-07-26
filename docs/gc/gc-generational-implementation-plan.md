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
| **0. HW Zero-Fill DMA ✅ DONE** | `ZERO` state in `BmbMemoryController` (template: `cp0-cpstop`) + `IO_ZERO_START`/`IO_ZERO_END` I/O regs; replace the two SW zero loops (`GC.java:725,869`) with a DMA call (SW fallback retained) | Low | — |
| **1. HW card-marking barrier** | Memory controller dirties a BRAM card table (1 bit / 16-word card) on writes into the tenure range; GC reads it during minor root scan | Low–Med | — |
| **2. Nursery + `minorGc()`** | Bump-pointer nursery in `newObject`/`newArray`; minor GC = root scan + card scan → copy survivors to tenure → HW-zero nursery | High | 0, 1 |
| **3. Tune & validate** | Per-preset nursery sizing; major-GC trigger threshold; validate bounded ~75 ms minor pause on real 256 MB DDR3 | Med | 2 |

---

## 3. Stage 0 detail — HW Zero-Fill DMA

**Goal:** replace `for (i=copyPtr; i<allocPtr) Native.wrMem(0,i)` (`GC.java:725,869`,
and later the nursery zero) with a hardware burst-write DMA. Deterministic timing,
~2.5× throughput.

### Chosen trigger design — controller-owned I/O registers, blocking

Grounded in the code: the memory controller sees *every* I/O write via its
`addrIsIo` path (before forwarding to external slaves) and owns the BMB master.
The auto I/O allocator packs devices *downward from 0xED*, so fixed addresses
just below the boot region are free. Three trigger options were considered —
(A) controller-owned I/O regs, (B) Sys registers + a `syncOut` wire to the
controller (like `gcHalt`), (C) a new microcode primitive like `stcp`. **A is
chosen**: least invasive, no microcode, no Sys wiring, no new pipeline op.

Blocking (not `rdy`-polled) for the first cut: GC zeroing is already stop-the-
world, so the pipeline can stall in `ZERO_RUN` until done. The win is throughput
+ deterministic burst timing, not concurrency. Async/`rdy` is a later option.

### HDL (`BmbMemoryController.scala` + `JopMemoryConfig.scala` + `IoAddressAllocator.scala`)
- Reserve two fixed I/O sub-addresses `ZERO_START = 0xEC`, `ZERO_END = 0xED`
  (`JopMemoryConfig`); `markRange` them in `IoAddressAllocator.allocate()` so no
  auto device collides.
- Add `ZERO_RUN` state + `zeroCur`/`zeroEnd` regs. In the IDLE I/O-write path:
  write to `ZERO_START` latches `zeroCur`; write to `ZERO_END` latches `zeroEnd`
  and enters `ZERO_RUN`. `ZERO_RUN` drives BMB `WRITE 0` to `(zeroCur<<2)`; on
  `fire`, `zeroCur += 1`; when `zeroCur === zeroEnd` → `IDLE`. (Burst BL=4/8 is a
  later throughput optimization; a resident word loop already removes the
  ~6 cycle/word pipeline round-trip.)

### Runtime (`GC.java` + `Const.java` + `ConstGenerator.scala`)
- Add `Const.IO_ZERO_START`/`IO_ZERO_END` (generated to match the sub-addresses).
- `zeroMem(from, to)`: `Native.wr(from, IO_ZERO_START); Native.wr(to, IO_ZERO_END)`
  (the second write blocks until done). Behind a `USE_HW_ZERO` flag; SW loop kept
  as fallback for boards without the DMA.
- Swap the two zero loops (`finishCycle` ~725, `gc` ~869) to `zeroMem`.

### Test (uses the new upload/run loop)
1. Sim first: `JopSmallGcBramSim` / a targeted BRAM sim — correctness (region
   reads back zero, no over/underrun at bounds).
2. Hardware microbenchmark: a `.jop` that times zeroing N MB on DDR3, SW vs DMA
   — confirm ~2.5× and record deterministic timing.
3. Regression: DoAll 66/66 on hardware still passes with `USE_HW_ZERO` on.

**Scope:** ~150 lines Scala + small `GC.java`/`Const.java` changes. No GC
algorithm change — safe stepping stone.

### Stage 0 results (hardware, XC7A100T + DB_FPGA V5, DDR3, 2026-07-25)

Cost: **+66 LUTs, +37 FF**, 0 BRAM/DSP (16585 vs 16519 LUTs). Timing MET
(WNS +0.272 ns). Validated via `java/apps/Small/src/test/ZeroBench.java`
(`make -C java/apps/Small APP_NAME=ZeroBench`), zeroing a 4 MB int[] backing
store both ways on the same bitstream:

| | Time (4 MB) | Correctness |
|---|---|---|
| SW word loop | 604 ms | — |
| **HW zero DMA** | **194 ms** | `nonzeroAfterHW=0` (region reads back all-zero) |

**3.11× speedup** (beats the doc's projected 2.5×), and DoAll 66/66 still
passes with GC routing through the DMA — no regression.

**Finding — throughput is bound by the word-granular L2, not write-allocate.**
Both paths go through the 32 KB L2 (`BmbCacheBridge` → `LruCacheCore`). The L2
already *skips* read-allocate on full-word writes
(`pendingNeedRefill := !(write && compReqIsFullLineWrite)`), so refill is not the
cost. The cost is that the L2 line is **one word (32-bit)**: each zeroed word is a
per-word cache-state round-trip, and dirty-line writeback drives the 128-bit MIG
at ¼ utilization (32 useful bits per transaction). Net ~21 MB/s. The 3.11× comes
purely from removing the per-word JOP pipeline round-trip. Real throughput needs
wide (128-bit / burst) writes to the MIG — see Stage 0.5.

---

## 4. Stage 0.5 — fast, portable fill (per-backend mechanism)

**Constraint (user):** must work across swappable backends — BRAM / SDR / DDR2 /
DDR3 — wired via `MemoryControllerFactory` (sealed trait: `BramMemCtrl`,
`SdrMemCtrl`, `Ddr3MemCtrl`). So the fill is a **capability each backend module
provides**, exposed through a common interface at the BMB level; the
`BmbMemoryController` drives it and falls back to the Stage-0 word loop for
backends that don't implement it.

**Principle (user):** the controller *always* delegates fill to the backend; each
backend fills at its **native full memory speed, whatever the technology.** No
controller-side fallback loop — every backend implements the mechanism (a trivial
loop is fine where that is already full speed).

### Interface
- Common sideband `MemFill` bundle threaded alongside `io.bmb` (controller =
  master, backend = slave): `valid` (start pulse), `start`/`end` word addresses,
  `value`, `busy` (backend asserts while filling).
- `BmbMemoryController`: a write to `ZERO_END` drives `io.fill` and stalls on
  `fill.busy`. The Stage-0 `ZERO_RUN` word loop is removed — the mechanism now
  lives in each backend.
- Threaded controller → `JopCore` → cluster → top → backend, parallel to `io.bmb`.

### Status (SDR slice — sim-validated)
- SDR fill FSM + `MemFill` + controller `FILL_REQ`/`FILL_WAIT` + threading:
  implemented, unit-tested, integration-tested.
- **Bug found + fixed — inverted/empty range hang.** The old software zero loop
  `for (i=from; i<to; ++i)` silently does nothing when `from >= to`, which
  `GC.finishCycle` produces (sets `copyPtr=compactDst` without resetting
  `allocPtr`, so the free region can be crossed). The HW fill and Stage-0
  `ZERO_RUN` looped `while (cur != end)` and **wrapped around → hang**. Fixed with
  a controller guard (`newEnd <= zeroCur` ⇒ IDLE, covers both fill and the DDR3
  `ZERO_RUN` path) plus a defensive `total16=0` in the SDR backend.
- Direct end-to-end proof: `FillTest` HW-zeros a 512-word buffer over a valid
  range; `JopSdramFillSim` confirms `FILL OK` + a positive-range `fillBusy` pulse.
- **Throughput — already at full SDR bandwidth.** Measured (`FillTest` N=8192 via
  `JopSdramFillSim`): **2.04 cyc/32-bit-word = 1.02 cyc/16-bit-write ≈ 98% of peak
  SDR write bandwidth (~196 MB/s @ 100 MHz)**. No page-mode work needed:
  `SdramCtrlNoCke` already keeps the row open for sequential same-row writes, and
  the fill FSM streams writes back-to-back (advances on `cmd.fire`, not on the
  response — no per-write round-trip). ~4× the software loop (~8 cyc/word). The
  earlier "slow" reading was the hung inverted-range run, not a real fill.

### Build order (confirmed) + test rig
1. **`MemFill` interface** + `BmbMemoryController` driving it.
2. **BRAM fill** (word/line loop into the on-chip `Mem`) and **SDR fill** (SDR
   burst zero writes in `BmbSdramCtrl32`; no write-back L2 in this path).
3. Validate on **EP4CGX150 + FPGA-DB V4** (primary SDR board, now connected):
   Altera USB-Blaster (`09fb:6001`) for programming, CP210x UART (`10c4:ea60`,
   from DB V4) for serial. Quartus flow: `fpga/qmtech-ep4cgx150-sdram`.
4. **DDR3 fill** — separate follow-up (below).

### DDR3 mechanism (later) — option B **and** A
Chosen: **128-bit L2 line + invalidate-line + block-zero at full MIG speed.**
- **(B)** Widen `LruCacheCore` line to 128 bits (matches `app_wdf_data`): general
  DDR3 speedup (spatial locality) and makes full-line zeroing a single MIG beat.
- **(A)** Fill = invalidate the L2 lines in `[start,end)` (writeback dirty
  live-object lines, drop clean ones) + block-write 0 straight to the MIG in
  128-bit bursts. Needs a new L2 invalidate/flush capability (none today).
- Open: full-L2 flush (simplest, OK during STW GC) vs range invalidate; MIG
  arbitration (fill vs cache — safe during STW since cache is idle).

**Effort:** interface + SDR/BRAM is moderate; DDR3 (B+A) is the substantial part,
deferred. Justified because minor-GC nursery zeroing (Stage 2) rides on fill
throughput for its bounded pause.

---

## 5. Stages 1–3 outline (expand when reached)

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
