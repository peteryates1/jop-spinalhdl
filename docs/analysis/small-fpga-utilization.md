# FPGA Utilization Analysis

Date: 2026-03-11
Tools: Quartus Prime 25.1std Lite Edition, Vivado 2025.2

## Cross-Platform Summary

All builds: serial boot, UART only, no HW math (imul=Microcode, idiv/irem/float=Java).

### Xilinx Artix-7 (Vivado, 6-input LUTs)

| Target | Cores | Memory | O$/A$ | LUTs | / Available | Util% | Status |
|--------|:-----:|--------|:-----:|-----:|------------:|------:|--------|
| AU (XC7A35T) | 1 | DDR3 | on | 15,884 | 20,800 | 76% | Fits |
| AU (XC7A35T) | 1 | DDR3 | off | 14,806 | 20,800 | 71% | Fits |
| AU (XC7A35T) | 2 | DDR3 | on | 21,065 | 20,800 | 101% | **Over by 265** |
| AU (XC7A35T) | 2 | DDR3 | off | 17,714 | 20,800 | 85% | Fits |
| Wukong (XC7A100T) | 1 | SDR | off | 3,385 | 63,400 | 5% | Fits easily |

### Altera (Quartus, 4-input LEs)

| Target | Cores | Memory | O$/A$ | LEs | / Available | Util% | Status |
|--------|:-----:|--------|:-----:|----:|------------:|------:|--------|
| MAX1000 (MAX10 8K) | 1 | SDR | on | 7,054 | 8,064 | 87% | Fits |
| EP4CE6 (Cyc IV 6K) | 1 | SDR | off | 4,688 | 6,272 | 75% | Fits |
| jopmin MAX1000 (ref) | 1 | SDR | — | 7,613 | 8,064 | 94% | Fits |

### Key Cost Breakdown (Xilinx LUTs)

| Component | LUTs | How measured |
|-----------|-----:|--------------|
| **JOP core + SDR controller** | **~3,385** | Wukong SDR build (no caches) |
| Second JOP core (SMP) | ~2,900 | 17,714 − 14,806 |
| O$/A$ caches | ~1,078 | 15,884 − 14,806 |
| DDR3 overhead (MIG + L1 + bridge) | ~11,400 | 14,806 − 3,385 |
| MIG IP alone | ~4,736 | MIG synth report |
| L1 cache + bridge | ~6,700 | DDR3 overhead − MIG |

### Feasibility for New Targets

| Target | Memory | Estimated LUTs | Available | Util% | Notes |
|--------|--------|---------------:|----------:|------:|-------|
| **XC7A15T** | SDR 8/32 MB | ~3,400 | 10,400 | 33% | Room for 2 cores + caches + HW math |
| **XC7A35T 2-core** | DDR3 | 17,714 | 20,800 | 85% | Requires O$/A$ disabled |
| XC7A35T 2-core + caches | DDR3 | 21,065 | 20,800 | 101% | Does not fit |

---

## Altera Detailed Analysis

### Target Boards

| Board | FPGA | LEs | M9K | Memory bits | SDRAM |
|-------|------|----:|----:|------------:|-------|
| Arrow MAX1000 | 10M08SAE144C8G (MAX10) | 8,064 | 42 | 387,072 | W9864G6JT-6 (8 MB) |
| Generic EP4CE6 | EP4CE6E22C8 (Cyclone IV E) | 6,272 | 30 | 276,480 | W9864G6JT (8 MB) |

### Configuration

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

### Per-Module Breakdown (MAX1000)

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

### Optimization History

| Step | MAX1000 LEs | EP4CE6 LEs | Change |
|------|------------:|------------:|--------|
| Original (readAsync, no LPM) | 23,437 (291%) | 9,542 (152%) | Baseline — doesn't fit |
| + AlteraLpm BRAM (lpm_rom/lpm_ram_dp) | 6,847 (85%) | 6,351 (101%) | -71% / -33% |
| + Disable O$/A$ caches | 4,765 (59%) | 4,688 (75%) | -30% / -26% |

### Root Causes of Original Bloat

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

---

## Xilinx Detailed Analysis

### Target Boards

| Board | FPGA | LUTs (6-input) | BRAM tiles | Memory |
|-------|------|----------------:|-----------:|--------|
| Alchitry Au V2 | XC7A35T-1FTG256C | 20,800 | 50 | MT41K128M16JT DDR3 (256 MB) |
| Wukong | XC7A100T-1FGG676C | 63,400 | 135 | MT41K128M16JT DDR3 + W9825G6JH6 SDR (32 MB) |

### BRAM Usage

| Config | RAMB36 | RAMB18 | Total tiles |
|--------|-------:|-------:|------------:|
| AU 1-core DDR3 | 11 | 7 | 14.5 / 50 (29%) |
| Wukong SDR | 1 | 5 | 3.5 / 135 (3%) |

### DDR3 vs SDR Cost

The DDR3 memory path adds ~11,400 LUTs of fixed overhead:

- **MIG 7 Series IP**: ~4,736 LUTs (PHY + controller + calibration)
- **L1 cache (LruCacheCore)**: 512-set cache bridge between BMB and MIG
- **BmbCacheBridge + CacheToMigAdapter**: Protocol translation

The SDR path uses SpinalHDL's `SdramCtrl` directly — no IP, no L1 cache,
much simpler. This is why the SDR build (3,385 LUTs) is so much smaller
than DDR3 (14,806 LUTs) despite identical JOP core logic.

### Dual-Core SMP on XC7A35T

Dual-core DDR3 with O$/A$ caches enabled (21,065 LUTs) exceeds the XC7A35T by
265 LUTs. Disabling caches brings it to 17,714 LUTs (85%), leaving 15% headroom.

The second core adds ~2,900 LUTs: one additional JopPipeline + BmbMemoryController,
plus a BMB arbiter for shared DDR3 access.

---

## Performance Impact of Disabled Caches

Disabling the object cache (O$) and array cache (A$) affects only heap object and array
access patterns. Stack operations, method calls, ALU, and control flow are unaffected.

### Object Cache (O$) — getfield / putfield

| | With O$ (16-entry, 8 fields) | Without O$ |
|---|---|---|
| **getfield (hit)** | 1 cycle (combinational hit, stays in IDLE) | N/A |
| **getfield (miss or no cache)** | HANDLE_READ → HANDLE_WAIT → HANDLE_CALC → HANDLE_DATA_CMD → HANDLE_DATA_WAIT → IDLE = ~20-30 cycles | Always full path: ~20-30 cycles |
| **putfield** | Always full path (write-through on hit) | Same |

### Array Cache (A$) — iaload / iastore

| | With A$ (16-entry, 4 elements/line) | Without A$ |
|---|---|---|
| **iaload (hit)** | 1 cycle (combinational hit, stays in IDLE) | N/A |
| **iaload (miss or no cache)** | Full handle deref + 4-element line fill (~30-50 cycles) | Full handle deref per access (~20-30 cycles) |
| **iastore** | Full handle deref (write-through on hit) | Same |

### Unaffected Operations

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

---

## Remaining Optimization Opportunities

| Opportunity | Est. savings (Altera LEs) | Notes |
|-------------|--------------------------|-------|
| JumpTable → LPM ROM | ~240 LEs | 256x11 readAsync ROM currently in logic |
| LongComputeUnit disable | ~466 LEs | If 64-bit integer ops not needed |
| MethodCache shrink | ~200 LEs | Fewer tag entries (8 vs 16) |
| Barrel shifter → iterative | ~150 LEs | Slower shifts but smaller |

## Key Files

| File | Description |
|------|-------------|
| `jop/config/JopConfig.scala` | `auMinimal`, `max1000Sdram`, `ep4ce6Sdram` presets |
| `jop/config/JopCoreConfig.scala` | `memoryStyle`, `useSyncRam`, per-bytecode config |
| `jop/memory/JopMemoryConfig.scala` | `useOcache`, `useAcache` flags |
| `jop/memory/AlteraLpmBlackBox.scala` | `AlteraLpmRom` and `AlteraLpmRam` BlackBox wrappers |
| `fpga/ip/altera_lpm/arom.vhd` | lpm_rom wrapper (REGISTERED addr, UNREGISTERED output) |
| `fpga/ip/altera_lpm/aram.vhd` | lpm_ram_dp wrapper (all REGISTERED, internal delays, inverted wrclock) |
| `fpga/max1000/jop_max1000.qsf` | MAX1000 Quartus project (ERAM mode, VHDL includes) |
| `fpga/generic-ep4ce6/jop_ep4ce6.qsf` | EP4CE6 Quartus project |
| `fpga/alchitry-au/Makefile` | AU Vivado build (single-core, SMP, flash) |
| `fpga/qmtech-xc7a100t-wukong/Makefile` | Wukong Vivado build (DDR3, SDR, SMP) |
