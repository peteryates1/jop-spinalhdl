# SDR SDRAM GC Hang — Investigation Notes

**STATUS: RESOLVED** — Root cause was SpinalHDL's `SdramCtrl` DQ bus I/O timing.
Replaced with Altera `altera_sdram_tri_controller` BlackBox which uses
`FAST_INPUT_REGISTER=ON` / `FAST_OUTPUT_REGISTER=ON` to place I/O flip-flops in
dedicated I/O cells. GC now runs 2000+ rounds on QMTECH and 9800+ rounds on CYC5000.

## Symptom
GC-enabled app (Small/HelloWorld.java) hung at R12 on SDR SDRAM FPGAs when using
SpinalHDL's `SdramCtrl`. R12 is the first round where free memory reaches 0 and
`gc_alloc()` triggers a full GC cycle. Serial boot + "Hello World!" (Smallest app,
no GC) worked fine on both boards.

```
R0 f=14268
R1 f=12988
...
R11 f=188
R12            <-- hangs here, no free memory value printed
```

## Affected Platforms
- **QMTECH EP4CGX150** (Cyclone IV GX, W9825G6JH6, CAS=3) — hangs at R12
- **CYC5000 TEI0050** (Cyclone V E, W9864G6JT, CAS=2) — hangs at R12

## Unaffected Platforms
- **Alchitry Au V2** (Artix-7, DDR3 via write-back cache + MIG) — 100,000+ GC rounds at 100 MHz
- **QMTECH BRAM** (on-chip RAM only, no SDRAM) — 98,000+ GC rounds
- **SpinalHDL BRAM simulation** — unlimited GC rounds
- **SpinalHDL SDRAM simulation** (Scala SdramModel) — unlimited GC rounds
- **Questa SDRAM simulation** (Verilog sdram_model.v) — 100+ GC rounds, 5M cycles, 0 errors

## Hypotheses Eliminated

### 1. FPGA Internal Timing Violations — DISPROVEN
- **Observation**: CYC5000 WNS=-1.150ns at 100 MHz, QMTECH WNS=-0.297ns at 100 MHz
- **Test**: Reduced CYC5000 clock from 100 MHz to 80 MHz
- **Result**: WNS improved to +1.081ns (worst slow corner), all corners pass — GC still hangs at R12
- **Conclusion**: Internal FPGA timing is not the cause

### 2. Object Cache Bug — DISPROVEN
- **Test**: Disabled ocache (`useOcache = false`) on both boards
- **Result**: GC still hangs at R12
- **Conclusion**: Not an ocache issue

### 3. Burst Reads Bug — DISPROVEN
- **Test**: Disabled burst reads (`burstLen = 0`) on both boards
- **Result**: GC still hangs at R12
- **Conclusion**: Not a burst read issue

### 4. BmbSdramCtrl32 Protocol Violation — DISPROVEN
- **Test**: Questa simulation with behavioral SDRAM model checking tRP, tRCD, tRAS, tRC, tWR, tRFC
- **Result**: 5M cycles, 122K reads, 89K writes, 0 timing violations
- **Conclusion**: SDRAM protocol timing is correct

### 5. BmbSdramCtrl32 Logic Bug (in simulation) — DISPROVEN
- **Test**: Questa simulation runs GC through 100+ rounds with identical Verilog netlist
- **Result**: All reads return correct data, GC completes successfully
- **Conclusion**: The generated Verilog logic is functionally correct in simulation

## Remaining Hypotheses

### A. Unconstrained SDRAM I/O Timing
The SDC files only contain:
```
create_clock -period ... -name clk_in [get_ports clk_in]
derive_pll_clocks
derive_clock_uncertainty
```
No `set_input_delay` or `set_output_delay` for SDRAM pins. Quartus warns "Design is
not fully constrained for setup/hold requirements." The fitter may place SDRAM I/O
paths with insufficient margin. The -2.5ns PLL phase shift compensates somewhat, but
without constraints Quartus doesn't know the SDRAM chip's tAC/tOH/tDS/tDH requirements.

**Why this is plausible**: Internal paths meet timing at 80 MHz, but the SDRAM DQ
input path (chip output -> PCB trace -> FPGA input register) is completely unanalyzed.
The STA slack numbers only reflect internal register-to-register paths.

### B. SDRAM DQ Bus Contention / Turnaround
Real SDRAM has bus turnaround requirements. When switching from a read (SDRAM drives DQ)
to a write (FPGA drives DQ), there must be a dead cycle to avoid bus contention.
BmbSdramCtrl32 may not properly enforce this. The behavioral Questa model doesn't
model DQ bus contention (it's a register-transfer model, not a physical bus model).

**Why this is plausible**: GC performs intensive interleaved read/write patterns
(mark phase reads handles, sweep phase reads/writes free list, copy phase reads+writes).
Simpler apps (Hello World) may never trigger back-to-back read->write or write->read
patterns that expose bus contention.

### C. SDRAM Refresh Collision During Long Operations
GC operations like memCopy perform many back-to-back SDRAM accesses. If a refresh
request arrives mid-operation and isn't properly handled, it could corrupt data.
SdramCtrl should handle this, but the interaction with BmbSdramCtrl32's multi-cycle
operations (2 SDRAM ops per 32-bit BMB word) may create an edge case.

### D. BmbSdramCtrl32 State Machine Race Condition
A subtle timing dependency that works in simulation but fails on real hardware due to
metastability, clock domain crossing, or FPGA-specific register initialization.
Less likely since the design is single-clock-domain.

## Key Observations
1. **Deterministic hang point**: Always R12, never earlier, never later. This suggests
   a reproducible trigger, not a random timing failure.
2. **Two different SDRAM chips, same hang**: W9825G6JH6 (CAS=3) and W9864G6JT (CAS=2)
   both fail at R12. The bug is not device-specific.
3. **DDR3 works**: The DDR3 path uses a completely different memory subsystem
   (BmbCacheBridge + LruCacheCore + MIG), bypassing BmbSdramCtrl32 entirely.
4. **BRAM works**: Eliminates JopCore/BmbMemoryController bugs — only the SDRAM
   backend path is affected.
5. **80 MHz doesn't help**: Rules out internal FPGA timing as the cause.

## Files
- `spinalhdl/src/main/scala/jop/memory/BmbSdramCtrl32.scala` — 32-bit BMB to 16-bit SDRAM bridge
- `spinalhdl/spinal/lib/memory/sdram/sdr/SdramCtrl.scala` — SpinalHDL's SDR SDRAM controller
- `verification/questa-sdram/` — Questa simulation with Verilog SDRAM model
- `verification/questa-sdram/sdram_model.v` — Behavioral SDRAM model with timing assertions

## Resolution
Replaced SpinalHDL's `SdramCtrl` with Altera's `altera_sdram_tri_controller` as a
BlackBox wrapper (`AlteraSdramBlackBox` + `AlteraSdramAdapter`). The Altera controller
has `FAST_INPUT_REGISTER=ON` / `FAST_OUTPUT_REGISTER=ON` synthesis attributes that
place SDRAM DQ input and output flip-flops in dedicated FPGA I/O cells, ensuring
proper setup/hold timing at the chip interface.

The fix is controlled by `BmbSdramCtrl32(useAlteraCtrl=true)`. Setting it to `false`
reverts to SpinalHDL's `SdramCtrl` (the broken path). Both paths coexist.

### Files Added
- `fpga/ip/altera_sdram_tri_controller/altera_sdram_tri_controller.v` — Altera IP (patched line 850 for separate DQ signals)
- `fpga/ip/altera_sdram_tri_controller/efifo_module.v` — Altera IP dependency
- `spinalhdl/src/main/scala/jop/memory/AlteraSdramBlackBox.scala` — BlackBox wrapper
- `spinalhdl/src/main/scala/jop/memory/AlteraSdramAdapter.scala` — SdramCtrlBus adapter with context FIFOs

### Root Cause Analysis
The most likely hypothesis was **A. Unconstrained SDRAM I/O Timing** — SpinalHDL's
`SdramCtrl` generates generic RTL with no I/O register placement hints. The fitter
placed DQ flip-flops in fabric rather than dedicated I/O cells, causing setup/hold
violations on the SDRAM interface that were invisible to internal STA.
