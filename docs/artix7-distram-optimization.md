# Artix-7 Distributed RAM Optimization

## Problem

On Xilinx Artix-7, the stack cache bank RAMs consume **1,584 LUTs** as distributed RAM
(out of 1,892 total dist RAM LUTs). This pushes LUT utilization to **81%** on the XC7A35T,
which is the binding constraint for adding more cores.

The root cause is `readAsync` on the bank RAMs. Xilinx BRAM (RAMB36E1/RAMB18E1) only
supports synchronous reads. When SpinalHDL sees `readAsync`, it emits
`(* ram_style = "distributed" *)`, forcing Vivado to implement the memory using LUT RAM
(RAMD64E primitives) instead of block RAM.

**This is a Xilinx-specific problem.** Altera M9K (Cyclone IV) and M10K (Cyclone V) natively
support asynchronous reads on block RAM (`OUTDATA_REG: UNREGISTERED`), so `readAsync` on
Altera infers block RAM with zero LUT cost.

## Current Utilization (XC7A35T, stack cache enabled)

From Vivado placed utilization report (2026-02-26):

| Resource | Used | Available | Util% |
|---|---|---|---|
| Slice LUTs | 16,863 | 20,800 | **81%** |
| — LUT as Logic | 14,954 | 20,800 | 72% |
| — LUT as Distributed RAM | 1,892 | 9,600 | 20% |
| Block RAM tiles | 12.5 | 50 | 25% |
| Timing (WNS) | +0.103 ns | — | Met |

## Distributed RAM Breakdown

From Vivado synthesis log (`runme.log`, Final Mapping Report):

| Memory | Module | Size | Primitives | Est. LUTs | Notes |
|---|---|---|---|---|---|
| `_zz_2_reg` | stackStg bank 0 | 256 x 32 | RAM64M x 132 | ~528 | **Target** |
| `_zz_3_reg` | stackStg bank 1 | 256 x 32 | RAM64M x 132 | ~528 | **Target** |
| `_zz_4_reg` | stackStg bank 2 | 256 x 32 | RAM64M x 132 | ~528 | **Target** |
| `_zz_1_reg` | stackStg scratch | 64 x 32 | RAM64M x 22 | ~88 | Also async |
| UART FIFOs | bmbUart tx/rx | 16 x 8 | RAM32M x 4 | ~8 | Fine |
| Cache FIFOs | cachei_3 cmd/rsp | 4 x 129/173 | RAM32M x 51 | ~102 | Fine |
| Bridge FIFOs | bmbBridge/adapter | 2-4 x 39-173 | RAM32M x 58 | ~116 | Fine |

The 3 stack cache banks dominate: **~1,584 LUTs** (84% of all distributed RAM).

All marked "User Attribute" — SpinalHDL's `readAsync` forces `(* ram_style = "distributed" *)`.

## Read Ports per Bank RAM

Each bank RAM currently has multiple `readAsync` calls in `StackStage.scala`:

1. **Pipeline read** (line 438): `bankRams(i).readAsync(bankRdPhysAddr(i))` — main data path,
   every cycle
2. **DMA spill read** (line 522): `bankRams(i).readAsync(dmaRdAddr)` — only during rotation
   (pipeline stalled)
3. **VP+0 debug readback** (line 716): `bankRams(i).readAsync(vp0BankPhys(i))` — debug only

Plus the scratch RAM (64 x 32) has its own async reads (lines 426, 515, 721).

## Why `readAsync`?

The JOP stack stage reads `ramDout` in the same cycle the address is registered:

```
Cycle C-1: ramRdaddrReg latched (from selRda decode)
Cycle C:   ramDout = readAsync(ramRdaddrReg)  ← combinational, same cycle
           A/B registers updated using ramDout
```

This is a zero-latency read — the address is a register output, and the data is available
combinationally in the same cycle. BRAM would add 1 cycle of read latency.

## Proposed Fix: Convert to `readSync`

### Port Reduction

Ports 1 (pipeline) and 2 (DMA) are mutually exclusive (DMA only runs during rotation stall).
They can share a single read port with an address MUX:

```scala
val bankRdAddr = Mux(rotBusy, dmaRdAddr, bankRdPhysAddr(i))
val bankRdData = bankRams(i).readSync(bankRdAddr)
```

Port 3 (VP+0 debug) should be removed or converted to use the same shared port (it's
debug-only and can tolerate stale data or be sampled during idle).

This gives: 1 write port + 1 read port = true dual-port BRAM. Fits in RAMB18E1.

### Pipeline Retiming

With `readSync`, data arrives 1 cycle later:

```
Cycle C-1: ramRdaddrReg latched
Cycle C:   readSync issued (address presented to BRAM)
Cycle C+1: bankRdData valid  ← 1 cycle later than current
```

The A/B register update logic that consumes `ramDout` needs to be retimed to account for
this. This affects the `selLmux` path where `ramDout` feeds the A register.

**Key constraint**: The address computation (`bankRdPhysAddr = ramRdaddrReg - bankBaseVAddr`)
is combinational from register outputs, so it's available at the start of the cycle — fine
for BRAM address setup. The retiming is only on the data output side.

### Resource Impact

| | Before | After | Delta |
|---|---|---|---|
| LUT (distributed RAM) | 1,892 | ~308 | **-1,584** |
| LUT (total) | 16,863 | ~15,279 | **-1,584** |
| LUT utilization | 81% | **~73%** | -8% |
| BRAM tiles | 12.5 | ~14.0 | +1.5 |
| BRAM utilization | 25% | ~28% | +3% |

Each 256x32 bank = 1 RAMB18E1 (0.5 tiles). 3 banks = 1.5 tiles.

### SMP Core Count Impact

From [artix7-core-estimates.md](artix7-core-estimates.md), per-core cost is ~4,400 LUT.
Saving 1,584 LUT frees room for roughly **1/3 of an additional core** on XC7A35T.

More importantly, it drops base utilization enough that the 2-core SMP build would go from
92% → ~84% LUT, giving much more timing closure headroom. On larger Artix-7 devices, the
per-core savings compound (each core's stack cache banks would use BRAM instead of LUT).

## Cross-Platform Impact

| Platform | Current behavior | After fix |
|---|---|---|
| **Xilinx Artix-7** | Distributed RAM (LUTs) | **Block RAM** — saves ~1,584 LUT/core |
| **Altera Cyclone IV** | M9K block RAM (async read native) | M9K block RAM (sync read) — no LUT change, adds 1-cycle read latency |
| **Altera Cyclone V** | M10K block RAM (async read native) | M10K block RAM (sync read) — no LUT change, adds 1-cycle read latency |

On Altera, the change moves from async M9K/M10K reads to synchronous — same block RAM
resource usage, but adds 1-cycle latency that the current design doesn't have. The pipeline
retiming would apply universally.

**Option A — Universal `readSync`**: One pipeline timing model everywhere. Simpler code.
Altera takes the latency hit (no resource impact, slight performance cost from extra cycle).

**Option B — Conditional `readAsync`/`readSync`**: Config flag (e.g., `useSyncBankRam`)
selects async on Altera, sync on Xilinx. Two code paths, more complexity, but optimal for
both platforms.

Recommendation: **Option A** (universal `readSync`). The 1-cycle latency cost on Altera is
negligible for the stack cache path (bank reads are not in the critical inner loop — they're
dominated by DMA spill/fill latency). The code simplicity benefit outweighs the micro-optimization.

## Implementation Checklist

- [ ] Remove VP+0 debug `readAsync` port (or convert to shared port)
- [ ] MUX pipeline and DMA read addresses into single port per bank
- [ ] Convert `bankRams(i).readAsync(...)` → `bankRams(i).readSync(...)`
- [ ] Convert `scratchRam.readAsync(...)` → `scratchRam.readSync(...)` (saves ~88 LUTs)
- [ ] Retime `ramDout` consumers in stack stage (1-cycle shift on bank/scratch data path)
- [ ] Verify all 57 JVM tests pass (BRAM sim)
- [ ] Verify DDR3 build utilization improvement
- [ ] Update `artix7-core-estimates.md` with revised per-core LUT cost
