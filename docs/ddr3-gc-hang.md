# DDR3 GC Hang — Investigation Notes

**STATUS: OPEN (deprioritized)** — Data corruption ruled out. Hang is at pipeline/state machine level. QMTECH EP4CGX150 SDR SDRAM is now the primary platform (single-core GC stable, SMP 2-core verified on hardware).

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

- **LruCacheCore**: 4-way set-associative, write-back cache (4KB data, 256 sets).
  Handles eviction (dirty writeback) and refill (line fetch from DDR3).
  Debug output: state (IDLE, ISSUE_EVICT, WAIT_EVICT_RSP, ISSUE_REFILL, WAIT_REFILL_RSP).

- **CacheToMigAdapter**: Converts cache memory commands to MIG app interface signals.
  Captures MIG read data on rspFifo backpressure (one-cycle pulse protection).
  Debug output: state (IDLE, ISSUE_WRITE, ISSUE_READ, WAIT_READ).

- **MigBlackBox**: Xilinx MIG 7 Series IP core. Handles DDR3 PHY, calibration, refresh.

### Timing

- WNS = +0.431ns at 100 MHz (tight but met)
- WHS = +0.048ns (hold margin is tight)
- Critical path: MIG reset fanout (96% routing delay)

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

## Remaining Hypotheses

### A. Pipeline/MemoryController State Machine Deadlock

The JOP pipeline + BmbMemoryController may have a state where the FSM gets stuck,
leaving `memBusy` asserted permanently. This would only happen under specific GC
access patterns that don't occur in simpler apps.

**Why plausible**: All simulations pass (the logic is functionally correct), but
real DDR3 has variable latency (refresh stalls, bank conflicts) that could expose
a timing-dependent race condition in the state machine.

**Next step**: Capture DiagUart output to identify which state the FSM is stuck in.

### B. Variable DDR3 Latency Exposing Pipeline Bug

Real DDR3 has latency spikes from refresh cycles (~7.8μs periodic), bank conflicts,
and page misses. The cache absorbs most of this, but cache misses expose the full
DDR3 latency (~30-50 cycles vs ~10 cycles in sim). A pipeline assumption about
worst-case latency could be violated.

**Why plausible**: The latency sweep test only goes to +5 extra cycles. DDR3 can
add 20-40 extra cycles on a cache miss during a refresh.

**Next step**: Increase CacheToBramAdapter latency in sim to 50+ cycles and re-test.

### C. Clock Domain Crossing Issue

Signals crossing between board clock domain and MIG ui_clk domain may have
metastability issues. BufferCC (2-FF synchronizer) is used, but if a multi-bit
signal is sampled during transition, corrupted values could cause incorrect behavior.

**Why less likely**: Single-bit signals (memBusy, txd) use BufferCC correctly.
Multi-bit signals (memState, pc, jpc) are only used for LED display and DiagUart
(monitoring), not control flow.

### D. I/O Subsystem Interaction

BmbSys timer/counter registers interact with the pipeline during GC. If a timer
interrupt fires at a critical moment during GC's synchronized sections (monitorenter/
monitorexit), it could corrupt state. On BRAM/SDRAM FPGAs interrupts work fine,
but DDR3 latency changes the timing relationship.

**Why less likely**: The GC app doesn't use interrupts (intMask=0). Timer fires but
is masked.

### E. Write-Back Cache Eviction During Critical Sequence

The write-back cache may evict a dirty line during a multi-step memory controller
operation (e.g., mid-getfield or mid-memCopy). If the eviction delays the expected
response timing, the state machine could deadlock.

**Why plausible**: This wouldn't happen in simulation (CacheToBramAdapter has fixed
latency) or the exerciser (simple sequential patterns). Only JOP's specific access
patterns during GC might trigger this exact cache eviction timing.

## Key Observations

1. **Deterministic hang point**: Always R12, never earlier — the first GC trigger
2. **Passes in all simulations**: Including xsim with real MIG RTL — functional logic is correct
3. **Passes with real DDR3 data patterns**: Trace replayer proves data correctness
4. **Only the full JOP hangs**: Exerciser and trace replayer use same DDR3 path and work
5. **Other FPGAs work**: BRAM and SDR SDRAM backends with same JopCore run GC indefinitely
6. **Tight timing**: WNS=+0.431ns, WHS=+0.048ns — functional but not much margin

## Files

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | DDR3 FPGA top-level |
| `spinalhdl/src/main/scala/jop/system/DiagUart.scala` | Hang detection UART |
| `spinalhdl/src/main/scala/jop/ddr3/BmbCacheBridge.scala` | BMB to cache bridge |
| `spinalhdl/src/main/scala/jop/ddr3/LruCacheCore.scala` | Write-back cache |
| `spinalhdl/src/main/scala/jop/ddr3/CacheToMigAdapter.scala` | Cache to MIG adapter |
| `spinalhdl/src/main/scala/jop/ddr3/MigBlackBox.scala` | MIG IP wrapper |
| `spinalhdl/src/main/scala/jop/system/Ddr3GcExerciserTop.scala` | GC pattern exerciser |
| `spinalhdl/src/main/scala/jop/system/Ddr3TraceReplayerTop.scala` | BMB trace replayer |
| `spinalhdl/src/test/scala/jop/system/JopGcTraceCaptureSim.scala` | Trace capture sim |
| `verification/vivado-ddr3/` | xsim with real MIG RTL |
| `fpga/alchitry-au/vivado/tcl/` | Vivado project/build/program scripts |

## Build Commands

```bash
# Main JOP DDR3 FPGA
cd fpga/alchitry-au && make generate bitstream

# GC pattern exerciser
cd fpga/alchitry-au && make generate-exerciser bitstream-exerciser program-exerciser

# BMB trace replayer
sbt "Test / runMain jop.system.JopGcTraceCaptureSim"  # Phase 1: capture
cd fpga/alchitry-au && make generate-replayer bitstream-replayer program-replayer

# Vivado xsim
sbt "Test / runMain jop.system.JopDdr3SimGen"
cd verification/vivado-ddr3 && make compile elaborate sim

# Monitor serial output
make monitor  # 1 Mbaud on /dev/ttyUSB2
```
