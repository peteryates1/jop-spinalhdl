# DDR3 GC Hang — Investigation Notes

**STATUS: RESOLVED (2026-02-23)** — Root cause was extreme cache thrashing from the 256-byte direct-mapped cache during GC. Fixed by rewriting LruCacheCore as a 4-way set-associative 16KB BRAM-based cache. Hardware verified: 67K+ GC rounds on real DDR3 with zero errors.

## Symptom

GC-enabled app (Small/HelloWorld.java) hangs at R12 on the DDR3 FPGA (Alchitry Au V2,
Artix-7 XC7A35T + DDR3) when the first GC cycle is triggered. Serial boot + "Hello
World!" (Smallest app, no GC) works fine.

```
R0 f=14268
R1 f=12988
...
R11 f=188
R12            <-- hangs here, no free memory value printed
```

R12 is the first round where free memory reaches 0 and `gc_alloc()` triggers a full
garbage collection cycle involving handle scanning, object compaction, and memCopy.

## Platform

- **Board**: Alchitry Au V2 (Xilinx Artix-7 XC7A35T-2, 100 MHz)
- **Memory**: DDR3 via Xilinx MIG 7 Series IP
- **Top-level**: `JopDdr3Top` (`spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala`)
- **Memory path**: JopCore.bmb → BmbCacheBridge → LruCacheCore (write-back) → CacheToMigAdapter → MIG → DDR3
- **Config**: `JopMemoryConfig(addressWidth=26, burstLen=0)` — all BMB transactions are single-word

## Working Platforms (same JopCore + BmbMemoryController)

- **QMTECH BRAM FPGA**: 98,000+ GC rounds — proves JopCore logic is correct
- **QMTECH SDR SDRAM FPGA**: 2,000+ GC rounds (with Altera SDRAM controller)
- **CYC5000 SDR SDRAM FPGA**: 9,800+ GC rounds (with Altera SDRAM controller)
- **SpinalHDL BRAM simulation**: Unlimited GC rounds
- **SpinalHDL SDRAM simulation**: Unlimited GC rounds
- **SpinalHDL Cache simulation** (10-cycle latency): Unlimited GC rounds
- **Vivado xsim** (real MIG RTL + DDR3 behavioral model): 2+ GC rounds (2M cycles)

## DDR3 Memory Path Architecture

```
JopCore ──BMB──► BmbCacheBridge ──► LruCacheCore ──► CacheToMigAdapter ──► MIG ──► DDR3
                     │                    │                   │
                 Mask invert         Write-back          MIG protocol
                 Burst decompose     4-way LRU           Read capture
                                     Evict+refill        rspFifo protect
```

### Components (`jop.ddr3` package)

- **BmbCacheBridge**: Translates single BMB transactions to cache frontend requests.
  Inverts mask convention (BMB: 1=write, cache: 1=keep). Decomposes burst BMB reads
  into sequential cache lookups.

- **LruCacheCore**: 4-way set-associative, write-back cache (16KB, 256 sets, 128-bit lines).
  BRAM-based data/tag/dirty arrays, register-based valid/LRU. PLRU replacement.
  6-state FSM: IDLE → CHECK_HIT → (hit: IDLE | miss: ISSUE_EVICT → WAIT_EVICT_RSP
  → ISSUE_REFILL → WAIT_REFILL_RSP → IDLE). 2-cycle hit path (BRAM readSync latency).

- **CacheToMigAdapter**: Converts cache memory commands to MIG app interface signals.
  Captures MIG read data on rspFifo backpressure (one-cycle pulse protection).
  Debug output: state (IDLE, ISSUE_WRITE, ISSUE_READ, WAIT_READ).

- **MigBlackBox**: Xilinx MIG 7 Series IP core. Handles DDR3 PHY, calibration, refresh.

### Timing & Utilization (XC7A35T-2)

| Config | LUT | FF | BRAM | WNS | WHS |
|--------|-----|-----|------|-----|-----|
| 1-core DDR3 (16KB cache) | 12,021 (57.8%) | 10,279 (24.7%) | 12.5 (25%) | +0.115 ns | +0.025 ns |
| 2-core DDR3 SMP (16KB cache) | 16,454 (79.1%) | 13,215 (31.8%) | 15 (30%) | +0.228 ns | +0.043 ns |
| 2-core DDR3 SMP (32KB cache) | 19,069 (91.7%) | 15,049 (36.2%) | 15 (30%) | +0.197 ns | +0.047 ns |

- Critical path (1-core): MIG `ui_clk_sync_rst` reset fanout to 5180 FFs (96% routing delay)
- Per additional core: ~4,400 LUT, ~2,900 FF, ~2.5 BRAM
- 16KB→32KB cache upgrade: +2,615 LUT (valid/LRU register arrays double), BRAM unchanged (Vivado maps wider tag/dirty as distributed RAM)

## Hang Detection Infrastructure

`JopDdr3Top` includes a hang detector that monitors `memBusy`:

1. Board-domain counter increments while `memBusy` stays True
2. After ~167ms (2^24 cycles at 100 MHz), `hangDetected` asserts
3. LEDs switch to show latched `memState` (BmbMemoryController FSM encoding)
4. **DiagUart** takes over `usb_tx` and sends state dump every ~200ms:
   ```
   HANG ms=XX pc=XXX jpc=XXXX cs=X as=X
   ```
   - `ms`: BmbMemoryController state (5 bits, see decode table in DiagUart.scala)
   - `pc`: Microcode PC (11 bits)
   - `jpc`: Java bytecode PC (12 bits)
   - `cs`: LruCacheCore state (3 bits)
   - `as`: CacheToMigAdapter state (3 bits)

### memState Decode Table

| Value | State | Description |
|-------|-------|-------------|
| 0x00 | IDLE | Ready for commands |
| 0x01 | READ_WAIT | Waiting for BMB read response |
| 0x02 | WRITE_WAIT | Waiting for BMB write response |
| 0x03 | IAST_WAIT | iastore pop delay (1 cycle) |
| 0x04 | HANDLE_READ | Read handle word |
| 0x05 | HANDLE_WAIT | Wait for handle read response |
| 0x06 | HANDLE_CALC | Calculate data pointer + field offset |
| 0x07 | HANDLE_ACCESS | Issue field read/write or I/O route |
| 0x08 | HANDLE_DATA_WAIT | Wait for field access response |
| 0x09 | HANDLE_BOUND_READ | Read array length for bounds check |
| 0x0A | HANDLE_BOUND_WAIT | Wait for bounds check response |
| 0x0B | NP_EXC | Null pointer exception |
| 0x0C | AB_EXC | Array bounds exception |
| 0x0D | BC_CACHE_CHECK | Method cache tag lookup |
| 0x0E | BC_FILL_R1 | Bytecode fill: first read |
| 0x0F | BC_FILL_LOOP | Bytecode fill: pipelined loop |
| 0x10 | BC_FILL_CMD | Bytecode fill: issue command |
| 0x11 | CP_SETUP | memCopy: read src/dest from stack |
| 0x12 | CP_READ | memCopy: read source word |
| 0x13 | CP_READ_WAIT | memCopy: wait for read response |
| 0x14 | CP_WRITE | memCopy: write to destination |
| 0x15 | CP_STOP | memCopy: stop bit handling |
| 0x16 | GS_READ | getfield via state machine |
| 0x17 | PS_WRITE | putfield via state machine |
| 0x18 | LAST | Terminal state |

---

## Investigation Timeline

### Test 1: DDR3 Basic Exerciser — PASS

Walking-1s, address patterns, and random data tests through the full DDR3 path.
All pass. Confirms basic DDR3 read/write correctness.

### Test 2: Vivado xsim with Real MIG RTL — PASS

Full JopDdr3Top compiled with real MIG RTL and Xilinx DDR3 behavioral model (ddr3_model.sv).
Ran for 2M simulation cycles. Completed 2+ GC rounds successfully.

- **Directory**: `verification/vivado-ddr3/`
- **Generator**: `sbt "Test / runMain jop.system.JopDdr3SimGen"`
- **Build**: `cd verification/vivado-ddr3 && make compile elaborate`
- **Run**: `make sim` (batch, ~4 hours) or `make sim_gui` (waveforms)
- **Output**: `mig_trace.csv` — MIG transactions with JOP execution context

**Conclusion**: MIG RTL + DDR3 behavioral model produces correct results.
The hang does not reproduce in gate-level simulation.

### Test 3: DDR3 GC Pattern Exerciser — PASS (780+ loops)

Custom exerciser replaying GC-like access patterns through the same DDR3 path
(BmbCacheBridge → LruCacheCore → CacheToMigAdapter → MIG → DDR3) but WITHOUT
the JOP pipeline. Five phases:

| Phase | Pattern | Purpose |
|-------|---------|---------|
| P1:FILL | Sequential write (24K words) | Initialize memory |
| P2:SCAN | LFSR-addressed reads (2048) | Cache thrashing |
| P3:COPY | Read+write interleave (4K words) | memCopy stress |
| P4:RMW | Scattered read-modify-write (2048) | Dirty evictions |
| P5:VFY | Sequential verify (4K words) | Data integrity |

All 5 phases pass for 780+ consecutive loops on real FPGA hardware.

- **File**: `spinalhdl/src/main/scala/jop/system/Ddr3GcExerciserTop.scala`
- **Build**: `cd fpga/alchitry-au && make generate-exerciser project-exerciser bitstream-exerciser`
- **Monitor**: `make program-exerciser && make monitor`

**Conclusion**: Cache→MIG→DDR3 data path is correct for GC-like patterns.
The hang is NOT in the cache/adapter/MIG path in isolation.

### Test 4: BMB Trace Replayer — PASS (16,384 entries)

Captured exact BMB transactions from a GC simulation (Small/HelloWorld.jop running
through first GC cycle and beyond) and replayed them through real DDR3 hardware.
Every read response was compared against the simulation's expected value.

#### Architecture

```
Phase 1 (Sim):   JopCore → [capture BMB cmd/rsp] → trace file + mem init file
Phase 2 (FPGA):  [BRAM] → Replay FSM → BMB → BmbCacheBridge → LruCacheCore
                                                 → CacheToMigAdapter → MIG → DDR3
                 Compare each read response against expected data from trace
```

#### Trace Coverage

28,243 total BMB transactions captured. First 16,384 replayed (BRAM budget limit).
The trace covers the complete GC phase:

| Phase | Entries | Characteristic |
|-------|---------|----------------|
| VM bootstrap | ~256 | 98% reads (ROM loading) |
| Class loading | ~7,400 | 71% writes (method cache fills) |
| GC handle scan | ~512 | 91% reads (pointer chasing) |
| GC compaction | ~2,800 | 89% reads (field traversal) |
| GC copy | ~5,400 | 80% reads (memCopy interleave) |

**Result**: PASS N=4000 — all 16,384 entries match expected values.

#### Files

- `spinalhdl/src/test/scala/jop/system/JopGcTraceCaptureSim.scala` — Trace capture harness + sim
- `spinalhdl/src/main/scala/jop/system/Ddr3TraceReplayerTop.scala` — FPGA replayer
- `spinalhdl/src/main/scala/jop/utils/TraceFileLoader.scala` — Hex file loaders
- `spinalhdl/generated/gc_mem_init.hex` — .jop memory image (8295 words)
- `spinalhdl/generated/gc_bmb_trace.hex` — BMB trace (28243 entries)

#### Build & Run

```bash
# Phase 1: Capture trace from simulation
sbt "Test / runMain jop.system.JopGcTraceCaptureSim"

# Phase 2: Build and program FPGA replayer
cd fpga/alchitry-au
make generate-replayer bitstream-replayer program-replayer

# Monitor (result repeats every ~670ms)
make monitor
```

#### FPGA Resource Usage

- LUTs: 39% (8120/20800)
- BRAM: 97% (48.5/50) — initMem 9 + traceMem 32 + cache 3 + MIG 2 + FIFOs 2
- WNS: +1.170ns, WHS: +0.056ns

#### Diagnostic Boot UART

The replayer includes a board-domain boot UART (independent of MIG calibration):
```
BOOT          ← FPGA configured, board clock running
CAL OK        ← MIG calibration complete
TRACE REPLAY  ← Main state machine started
F:0800...     ← Fill progress (every 2048 words)
T:0400...     ← Replay progress (every 1024 entries)
PASS N=4000   ← Result (repeats every ~670ms)
```

If only "BOOT" appears → MIG calibration failed.
If "BOOT" + "CAL OK" but no "TRACE REPLAY" → MIG domain state machine stuck.

#### BRAM Lessons Learned

- SpinalHDL `readAsync` on large `Mem` → Vivado maps to LUT-ROM with massive MUX trees
  (95% LUT usage, 6000+ F7 muxes for 128KB memory)
- Two `readAsync` ports on same `Mem` → Vivado duplicates the entire memory (2× BRAM)
- `readSync` maps cleanly to BRAM: one port per BRAM primitive, synchronous read
- All output emits within ~200ms of programming; result repeating solves late serial open

**Conclusion**: DDR3 data path returns correct data for the exact GC access patterns.
The GC hang is NOT caused by data corruption.

---

## Resolution

### Root Cause: Cache Thrashing Under GC

The original LruCacheCore was a **256-byte direct-mapped cache** (16 sets × 1 way,
register-based). During garbage collection, the GC algorithm walks the object graph
across many different memory addresses. With only 16 cache lines, virtually every
access was a miss, causing back-to-back dirty evictions and refills to DDR3.

Under real DDR3 latency (variable due to refresh cycles, bank conflicts, page misses),
this extreme eviction pressure caused the system to hang. The exerciser and trace
replayer passed because they don't generate the same sustained miss pattern that the
full JOP pipeline does during GC — the exerciser uses sequential/LFSR patterns, and
the trace replayer succeeds because it replays at the DDR3's pace rather than the
pipeline's pace.

### Fix: 4-Way Set-Associative 16KB BRAM Cache (commit 70ea953)

Rewrote `LruCacheCore` from scratch:

| Property | Before | After |
|----------|--------|-------|
| Organization | Direct-mapped (1 way) | 4-way set-associative |
| Sets | 16 | 256 |
| Total size | 256 bytes | 16 KB |
| Storage | Registers | BRAM (data/tag/dirty) + registers (valid/LRU) |
| Replacement | N/A (1 way) | Pseudo-LRU (tree-based) |
| Hit latency | 1 cycle | 2 cycles (BRAM readSync) |
| FSM states | 5 | 6 (added CHECK_HIT for BRAM latency) |
| GC rounds on DDR3 | Hung at R12 | 67,000+ (no errors) |

Key design decisions:
- **BRAM arrays**: Per-way data Mem, packed tag/dirty Mems (all ways in one word)
- **Register arrays**: Valid bits and PLRU bits (need combinational read for hit check)
- **Parameterized**: wayCount=1/2/4 all supported, setCount configurable
- **Single write port per BRAM**: Address/data/enable muxed across FSM states

### Verification

| Test | Config | Result |
|------|--------|--------|
| Unit test (LruCacheCoreUnitSim) | 2-way, 4 sets | 7/7 pass |
| Integration test (LruCacheCoreTest) | 4-way, 256 sets | 6/6 pass |
| GC sim (JopSmallGcCacheSim) | 4-way, 256 sets | R14+ in 882K cycles |
| High-latency sim (20-60 cycles) | 4-way, 256 sets | R14+ in 1.02M cycles |
| Serial boot sim | 4-way, 256 sets | R14+ |
| MIG behavioral model sim | 4-way, 256 sets | R14+ in 917K cycles |
| **FPGA hardware (DDR3)** | **4-way, 256 sets** | **67K+ rounds, 5 min, zero errors** |
| SMP DDR3 sim (2 cores) | 4-way, 256 sets, burstLen=4 | PASS at 335K cycles |
| **SMP FPGA hardware (2-core DDR3)** | **4-way, 256 sets, burstLen=4** | **NCoreHelloWorld running, both cores verified** |

### Why Simulations Passed With the Old Cache

All simulations used fixed or low-variance latency backends (CacheToBramAdapter with
~10 cycle latency). Under fixed latency, even a 256-byte cache works — the pipeline
simply stalls longer on misses but never deadlocks. Real DDR3 has:
- **Refresh stalls**: ~7.8μs periodic (every 64ms / 8192 rows), blocking all access
- **Bank conflicts**: 20-40 extra cycles when accessing different rows in same bank
- **Page misses**: Additional latency for row activate + precharge

The combination of constant cache thrashing (every access a miss) plus variable DDR3
latency created conditions that couldn't occur in simulation.

---

## Hypotheses Eliminated

### 1. DDR3 Data Corruption — DISPROVEN

- **Evidence**: BMB trace replayer passes all 16,384 entries covering the full GC cycle
- **Evidence**: GC pattern exerciser passes 780+ loops
- **Evidence**: xsim with real MIG RTL passes 2+ GC rounds
- **Conclusion**: The DDR3 subsystem (cache + MIG + DDR3 chip) returns correct data

### 2. Cache→MIG Protocol Bug — DISPROVEN

- **Evidence**: Exerciser runs 780+ loops of intensive R/W patterns through same path
- **Evidence**: xsim passes with functional simulation of MIG RTL
- **Conclusion**: LruCacheCore and CacheToMigAdapter work correctly

### 3. DDR3 Timing / Calibration Failure — DISPROVEN

- **Evidence**: Trace replayer at 97% BRAM shows MIG calibration succeeds ("CAL OK")
- **Evidence**: xsim passes, basic exerciser passes, trace replayer passes
- **Evidence**: Timing met (WNS=+0.431ns, WHS=+0.048ns)
- **Conclusion**: DDR3 PHY and calibration work correctly

### 4. Memory Content Initialization Error — DISPROVEN

- **Evidence**: Trace replayer pre-fills DDR3 with .jop data, then replays exact sim
  transactions — all reads match
- **Conclusion**: DDR3 contents are correct after initialization

## Former Hypotheses (all resolved by cache rewrite)

The following hypotheses were considered during investigation. The cache rewrite
resolved the issue, confirming that the root cause was cache thrashing (closest
to hypothesis E) rather than a pipeline bug or CDC issue.

- **A. Pipeline/MemoryController deadlock** — Not a pipeline bug; pipeline works
  correctly with adequate cache.
- **B. Variable DDR3 latency exposing pipeline bug** — Partially correct: variable
  DDR3 latency was a factor, but only because the tiny cache exposed every access
  to DDR3. With 16KB cache, hit rate is high enough that DDR3 latency spikes are
  absorbed.
- **C. Clock domain crossing issue** — Not the cause; same CDC with new cache works.
- **D. I/O subsystem interaction** — Not the cause.
- **E. Write-back cache eviction during critical sequence** — Closest to root cause.
  The 256-byte cache caused constant evictions during GC, and the interaction between
  back-to-back evictions and variable DDR3 latency caused the hang.

## Key Observations

1. **Deterministic hang point**: Always R12, never earlier — the first GC trigger
2. **Passes in all simulations**: Including xsim with real MIG RTL — functional logic is correct
3. **Passes with real DDR3 data patterns**: Trace replayer proves data correctness
4. **Only the full JOP hangs**: Exerciser and trace replayer use same DDR3 path and work
5. **Other FPGAs work**: BRAM and SDR SDRAM backends with same JopCore run GC indefinitely
6. **Tight timing**: WNS=+0.115ns, WHS=+0.025ns (was +0.431/+0.048 with old cache)
7. **16KB cache fixes it**: 67K+ GC rounds on real DDR3 — cache hit rate eliminates thrashing

## Files

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | DDR3 FPGA top-level |
| `spinalhdl/src/main/scala/jop/system/DiagUart.scala` | Hang detection UART |
| `spinalhdl/src/main/scala/jop/ddr3/BmbCacheBridge.scala` | BMB to cache bridge |
| `spinalhdl/src/main/scala/jop/ddr3/LruCacheCore.scala` | Write-back cache |
| `spinalhdl/src/main/scala/jop/ddr3/CacheToMigAdapter.scala` | Cache to MIG adapter |
| `spinalhdl/src/main/scala/jop/ddr3/CacheConfig.scala` | Cache geometry config |
| `spinalhdl/src/main/scala/jop/ddr3/MigBlackBox.scala` | MIG IP wrapper |
| `spinalhdl/src/main/scala/jop/system/Ddr3GcExerciserTop.scala` | GC pattern exerciser |
| `spinalhdl/src/main/scala/jop/system/Ddr3TraceReplayerTop.scala` | BMB trace replayer |
| `spinalhdl/src/test/scala/jop/ddr3/LruCacheCoreUnitSim.scala` | Cache unit test (7 tests) |
| `spinalhdl/src/test/scala/jop/system/LruCacheCoreTest.scala` | Cache integration test |
| `spinalhdl/src/test/scala/jop/system/JopSmallGcHighLatencySim.scala` | High-latency GC sim |
| `spinalhdl/src/test/scala/jop/system/JopDdr3SerialBootSim.scala` | Serial boot GC sim |
| `spinalhdl/src/test/scala/jop/system/JopGcTraceCaptureSim.scala` | Trace capture sim |
| `spinalhdl/src/test/scala/jop/system/JopSmpDdr3Sim.scala` | SMP DDR3 simulation test harness |
| `spinalhdl/src/test/scala/jop/formal/LruCacheCoreFormal.scala` | Formal verification |
| `verification/vivado-ddr3/` | xsim with real MIG RTL |
| `fpga/alchitry-au/vivado/tcl/` | Vivado project/build/program scripts |

## Build Commands

```bash
# Main JOP DDR3 FPGA (single-core)
cd fpga/alchitry-au && make generate bitstream

# SMP DDR3 FPGA (dual-core)
cd fpga/alchitry-au && make generate-smp project-smp bitstream-smp

# Program + download + monitor (SMP)
cd fpga/alchitry-au && make program-smp
# Wait ~5s for MIG calibration, then:
python3 ../scripts/download.py -e ../../java/apps/Small/NCoreHelloWorld.jop /dev/ttyUSB1 1000000
# Or use: make run-smp

# SMP DDR3 simulation
sbt "Test/runMain jop.system.JopSmpDdr3NCoreHelloWorldSim"

# GC pattern exerciser
cd fpga/alchitry-au && make generate-exerciser bitstream-exerciser program-exerciser

# BMB trace replayer
sbt "Test / runMain jop.system.JopGcTraceCaptureSim"  # Phase 1: capture
cd fpga/alchitry-au && make generate-replayer bitstream-replayer program-replayer

# Vivado xsim
sbt "Test / runMain jop.system.JopDdr3SimGen"
cd verification/vivado-ddr3 && make compile elaborate sim

# Monitor serial output
make monitor  # 1 Mbaud on /dev/ttyUSB1
```

---

## Full 256MB DDR3 Addressing (2026-02-27)

The MT41K128M16JT is 256MB, but JOP initially only addressed 64MB due to
`addressWidth = 26` (24-bit physical word address = 64MB). The MIG interface
already supports 28-bit byte addresses — the limitation was purely in JOP's
configuration.

### Changes

| File | Change |
|------|--------|
| `JopMemoryConfig.scala` | Relaxed `addressWidth` constraint from `<= 26` to `<= 28` |
| `JopDdr3Top.scala` | `addressWidth = 28`, `mainMemSize = 256MB` |
| `BmbCacheBridge.scala` | Truncate BMB address to `cacheAddrWidth` bits (top 2 type bits always 00 for memory) |
| `StackStage.scala` | Added `wordAddrWidth` parameter to `StackCacheConfig`, made `extByteAddr` and `dmaExtAddr` port widths parametric |
| `JopPipeline.scala` | Parametric `dmaExtAddr` port width |
| `JopCore.scala` | Pass `wordAddrWidth = addressWidth - 2` to StackCacheConfig |
| `GC.java` | `MAX_HANDLES = 65536` cap (prevents O(N) sweep explosion on large memories) |

### Address Flow

```
JOP pipeline → BmbMemoryController → BMB bus → BmbCacheBridge → LruCacheCore → CacheToMigAdapter → MIG
  28-bit word     (aoutAddr<<2).resized   30-bit byte    addr(27:0)→28-bit   28-bit cache    28-bit MIG
  [27:26]=type                            [29:28]=00     strips type bits     matches MIG     app_addr
```

### GC MAX_HANDLES

With 256MB, the uncapped formula `handle_cnt = full_heap_size >> 4` creates 4.2M
handles (128MB of handle table, ~335ms sweep per GC cycle at 100MHz). This caused
the GC to hang after ~104 rounds on FPGA — the mutator couldn't make progress between
GC increments. Adding `MAX_HANDLES = 65536` caps the handle table at 2MB with ~6ms
sweep time, leaving 254MB for heap.

### Verification

| Test | Result |
|------|--------|
| `sbt compile` | PASS |
| `sbt "runMain jop.system.JopDdr3TopVerilog"` | PASS |
| `JopSmallGcBramSim` | PASS (81+ rounds, 3 full GC cycles) |
| `JopSmallGcCacheSim` | PASS (uses addressWidth=26, unchanged) |
| FPGA build (Vivado) | PASS |
| FPGA 256MB DDR3 GC | PASS — 1,870+ GC rounds, stable |
