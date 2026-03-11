# Small FPGA Utilization Analysis

Date: 2026-03-11
Tool: Quartus Prime 25.1std Lite Edition (full compile: map + fit)

## Target Boards

| Board | FPGA | LEs | M9K | Memory bits | SDRAM |
|-------|------|----:|----:|------------:|-------|
| Arrow MAX1000 | 10M08SAE144C8G (MAX10) | 8,064 | 42 | 387,072 | W9864G6JT-6 (8 MB) |
| Generic EP4CE6 | EP4CE6E22C8 (Cyclone IV E) | 6,272 | 30 | 276,480 | W9864G6JT (8 MB) |

## Configuration

Both use `MemoryStyle.AlteraLpm` (auto-derived from Altera manufacturer). Cache
configuration differs per target based on available LEs.

| Parameter | MAX1000 | EP4CE6 | Notes |
|-----------|---------|--------|-------|
| `useOcache` | true | false | O$ adds ~850 LEs |
| `useAcache` | true | false | A$ adds ~1,050 LEs |
| `memoryStyle` | AlteraLpm | AlteraLpm | LPM megafunction BlackBoxes for BRAM inference |
| `useSyncRam` | true (auto) | true (auto) | Synchronous RAM reads |
| Compute units | idiv/irem HW, LCU, DCU | same | Default (no FCU, no DSP imul) |
| Boot mode | Serial | Serial | UART boot |

EP4CE6 uses `noCacheMemConfig` (no O$/A$) because 6K LEs is too small for caches.
MAX1000 uses default `JopMemoryConfig` (O$/A$ enabled) — fits at 87%.

## Results Summary

| Target | LEs | LE% | Memory bits | M9Ks | Status |
|--------|----:|----:|------------:|-----:|--------|
| MAX1000 (caches on) | 7,054 | 87% | 59,648 | 10 | **Fits** (13% headroom) |
| EP4CE6 (no caches) | 4,688 | 75% | 49,408 | 8 | **Fits** (25% headroom) |
| jopmin MAX1000 (reference) | 7,613 | 94% | 328,704 | — | Fits (6% headroom) |

SpinalHDL JOP on MAX1000 is 7% smaller than jopmin with O$/A$ caches that jopmin lacks.
EP4CE6 without caches uses far fewer LEs (75%) with room for additional features.

## Per-Module Breakdown (MAX1000)

| Module | LEs | M9K bits | Notes |
|--------|----:|--------:|-------|
| **JopPipeline** | **2,108** | **49,152** | |
| -- FetchStage | 107 | 24,576 | Microcode ROM (2048x12) in 3 M9Ks via lpm_rom |
| -- DecodeStage | 96 | 0 | |
| -- BytecodeFetchStage | 441 | 16,384 | JBC RAM (1024x16) in 2 M9Ks |
| ---- JumpTable | 242 | 0 | 256x11 readAsync ROM still in logic LEs |
| -- StackStage | 852 | 8,192 | Stack RAM (256x32) in 1 M9K via lpm_ram_dp |
| ---- Shift (barrel shifter) | 195 | 0 | |
| -- ComputeUnitTop | 621 | 0 | |
| ---- LongComputeUnit | 466 | 0 | 64-bit integer ops |
| ---- DoubleComputeUnit | 8 | 0 | Stub (no FCU) |
| **BmbMemoryController** | **1,623** | **0** | No O$/A$ caches |
| -- MethodCache | 606 | 0 | Tag-only (16-block FIFO) |
| **BmbSdramCtrl32** | **593** | **0** | Altera triple-port controller |
| **UART** | 247 | 256 | TX/RX FIFOs in 2 M9Ks |
| **BmbSys** | 213 | 0 | Timer, counter, watchdog |
| **Top-level glue** | 43 | 0 | PLL, reset, BMB interconnect |

## Optimization History

| Step | MAX1000 LEs | EP4CE6 LEs | Change |
|------|------------:|------------:|--------|
| Original (readAsync, no LPM) | 23,437 (291%) | 9,542 (152%) | Baseline — doesn't fit |
| + AlteraLpm BRAM (lpm_rom/lpm_ram_dp) | 6,847 (85%) | 6,351 (101%) | -71% / -33% |
| + Disable O$/A$ caches | 4,765 (59%) | 4,688 (75%) | -30% / -26% |

## Root Causes of Original Bloat

1. **MAX10 MIF limitation**: Quartus inference engine does not support `$readmemb` / MIF
   for MAX10. ROM and RAM with initialization data are implemented in logic LEs instead of
   M9K block RAM. Fix: `lpm_rom` / `lpm_ram_dp` megafunction BlackBoxes with .mif files.

2. **MAX10 synchronous ROM only**: `LPM_ADDRESS_CONTROL = "UNREGISTERED"` creates async ROM,
   which MAX10 doesn't support. Fix: `REGISTERED` address (LPM registers internally).

3. **MAX10 ERAM configuration mode**: `INTERNAL_FLASH_UPDATE_MODE "SINGLE IMAGE WITH ERAM"`
   required in QSF for M9K initialization via .mif on MAX10.

4. **Object/array caches**: ~1,900 LEs for O$ (16-entry, 8 fields) + A$ (16-entry, 4 elements).
   Disabled for small FPGAs. Performance impact: getfield/iaload/iastore go through full
   SDRAM handle dereference on every access (~3 SDRAM cycles) instead of 1-cycle cache hit.

## Performance Impact of Disabled Caches

Disabling the object cache (O$) and array cache (A$) affects only heap object and array
access patterns. Stack operations, method calls, ALU, and control flow are unaffected.

### Object Cache (O$) — getfield / putfield

| | With O$ (16-entry, 8 fields) | Without O$ |
|---|---|---|
| **getfield (hit)** | 1 cycle (combinational hit, stays in IDLE) | N/A |
| **getfield (miss or no cache)** | HANDLE_READ → HANDLE_WAIT → HANDLE_CALC → HANDLE_DATA_CMD → HANDLE_DATA_WAIT → IDLE = ~20-30 cycles | Always full path: ~20-30 cycles |
| **putfield** | Always full path (write-through on hit) | Same |

The O$ eliminates the SDRAM handle dereference on repeated reads of the same object's
fields. Typical hit rates depend on the access pattern — tight loops over object fields
benefit most. First access to any object is always a miss.

### Array Cache (A$) — iaload / iastore

| | With A$ (16-entry, 4 elements/line) | Without A$ |
|---|---|---|
| **iaload (hit)** | 1 cycle (combinational hit, stays in IDLE) | N/A |
| **iaload (miss or no cache)** | Full handle deref + 4-element line fill (~30-50 cycles) | Full handle deref per access (~20-30 cycles) |
| **iastore** | Full handle deref (write-through on hit) | Same |

The A$ is most valuable for sequential array iteration — after one miss fills a 4-element
cache line, the next 3 accesses are 1-cycle hits. Without A$, every `iaload` pays the
full SDRAM cost.

### Unaffected Operations

These operations have identical performance with or without O$/A$:

- **Stack operations** (push, pop, dup, swap) — register + on-chip RAM, no SDRAM
- **Local variable access** (load, store) — on-chip stack RAM
- **ALU / shifts / comparisons** — combinational or compute unit, no memory
- **Method calls / returns** (invoke, return) — method cache (M$) is always present
- **Bytecode fetch** — bytecode RAM is always present
- **getstatic / putstatic** — no caching (always SDRAM), same with or without O$/A$
- **I/O operations** — direct I/O bus, never cached

### Practical Impact

For jopmin-class workloads (HelloWorld, simple control applications), the impact is
negligible — jopmin never had these caches. For object-heavy or array-heavy Java code
(e.g., iterating over arrays, frequent field access in inner loops), expect ~2-3x slowdown
on those specific operations. Overall application impact depends on the fraction of time
spent in getfield/iaload vs other operations.

## Remaining Optimization Opportunities

| Opportunity | Est. savings | Notes |
|-------------|-------------|-------|
| JumpTable → LPM ROM | ~240 LEs | 256x11 readAsync ROM currently in logic |
| LongComputeUnit disable | ~466 LEs | If 64-bit integer ops not needed |
| MethodCache shrink | ~200 LEs | Fewer tag entries (8 vs 16) |
| Barrel shifter → iterative | ~150 LEs | Slower shifts but smaller |

With JumpTable in LPM and no LCU, the design would be ~4,050 LEs (50% of MAX1000, 65% of EP4CE6).

## Key Files

| File | Description |
|------|-------------|
| `jop/config/JopConfig.scala` | `smallFpgaMemConfig`, `max1000Sdram`, `ep4ce6Sdram` presets |
| `jop/config/JopCoreConfig.scala` | `memoryStyle` and `useSyncRam` config fields |
| `jop/memory/AlteraLpmBlackBox.scala` | `AlteraLpmRom` and `AlteraLpmRam` BlackBox wrappers |
| `fpga/ip/altera_lpm/arom.vhd` | lpm_rom wrapper (REGISTERED addr, UNREGISTERED output) |
| `fpga/ip/altera_lpm/aram.vhd` | lpm_ram_dp wrapper (all REGISTERED, internal delays, inverted wrclock) |
| `fpga/max1000/jop_max1000.qsf` | MAX1000 Quartus project (ERAM mode, VHDL includes) |
| `fpga/generic-ep4ce6/jop_ep4ce6.qsf` | EP4CE6 Quartus project |
