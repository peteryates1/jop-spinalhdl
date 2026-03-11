# Small FPGA Utilization Analysis

Date: 2026-03-11
Tool: Quartus Prime 25.1std Lite Edition (full compile: map + fit)

## Target Boards

| Board | FPGA | LEs | M9K | Memory bits | SDRAM |
|-------|------|----:|----:|------------:|-------|
| Arrow MAX1000 | 10M08SAE144C8G (MAX10) | 8,064 | 42 | 387,072 | IS42S16160G (32 MB) |
| Generic EP4CE6 | EP4CE6E22C8 (Cyclone IV E) | 6,272 | 30 | 276,480 | W9864G6JT (8 MB) |

## Configuration

Both use `smallFpgaMemConfig` (defined in `JopConfig.scala`):

| Parameter | Value | Notes |
|-----------|-------|-------|
| `useOcache` | false | No object cache (saves ~850 LEs) |
| `useAcache` | false | No array cache (saves ~1,050 LEs) |
| `memoryStyle` | AlteraLpm (auto) | LPM megafunction BlackBoxes for BRAM inference |
| `useSyncRam` | true (auto) | Synchronous RAM reads |
| Compute units | idiv/irem HW, LCU, DCU | Default (no FCU, no DSP imul) |
| Boot mode | Serial | UART boot |

## Results Summary

| Target | LEs | LE% | Memory bits | M9Ks | Status |
|--------|----:|----:|------------:|-----:|--------|
| MAX1000 | 4,765 | 59% | 49,408 | 8 | **Fits** (41% headroom) |
| EP4CE6 | 4,688 | 75% | 49,408 | 8 | **Fits** (25% headroom) |
| jopmin MAX1000 (reference) | 7,613 | 94% | 328,704 | — | Fits (6% headroom) |

SpinalHDL JOP is 37% smaller than jopmin in LEs, using far less memory bits (49K vs 329K)
because jopmin has a 256KB method cache vs our 16KB bytecode RAM.

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
