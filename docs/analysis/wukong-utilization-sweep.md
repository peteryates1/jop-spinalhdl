# Wukong XC7A100T Feature Utilization Sweep

Date: 2026-03-15 (updated — SD Native FIFO fix)
Platform: QMTECH Wukong V3, Xilinx XC7A100T-2FGG676 (63,400 LUTs, 126,800 FFs, 135 BRAM, 240 DSP)
Tool: Vivado 2025.2, non-project flow, synth_design only (post-synthesis estimates)
Memory: DDR3 via MIG 7 Series (all variants)

## Methodology

Each variant starts from the `wukongDdr3` base preset (DDR3 + UART, no compute
units, no Ethernet/SD) and enables one feature at a time. All variants share
the same MIG IP, ClkWiz, BmbCacheBridge (32KB L2), and base JOP core
infrastructure. Synthesis-only utilization is reported (post-synth LUTs may
differ slightly from post-implementation due to optimization during P&R).

Source: `spinalhdl/src/main/scala/jop/system/UtilSweep.scala`
TCL: `fpga/qmtech-xc7a100t-wukong/vivado/tcl/synth_only.tcl`
Reports: `fpga/qmtech-xc7a100t-wukong/vivado/build/util_sweep/*/util.rpt`

## Results: Absolute Utilization

| Config | LUTs | Regs | BRAM | DSP | LUT% |
|--------|------:|------:|-----:|----:|-----:|
| No ICU (no HW integer) | 17,336 | 12,861 | 12.5 | 0 | 27.3% |
| No A$ (idiv/irem HW, no array cache) | 17,235 | 12,150 | 12 | 0 | 27.2% |
| Baseline (idiv/irem HW) | 17,741 | 13,075 | 12.5 | 0 | 28.0% |
| + imul iterative | 18,041 | 13,274 | 12.5 | 0 | 28.5% |
| + imul DSP | 18,133 | 13,309 | 12.5 | 4 | 28.6% |
| + FCU (9 float ops) | 19,124 | 13,511 | 12.5 | 2 | 30.2% |
| + LCU (8 long ops) | 18,788 | 13,285 | 12.5 | 0 | 29.6% |
| + DCU (12 double ops) | 22,297 | 14,078 | 12.5 | 9 | 35.2% |
| All 4 CUs + DSP imul | 24,922 | 14,946 | 12.5 | 15 | 39.3% |
| + Ethernet GMII | 18,305 | 13,648 | 13.5 | 0 | 28.9% |
| + SD Native | 17,828 | 14,459 | 16 | 0 | 28.1% |
| + SD SPI | 17,759 | 13,131 | 12.5 | 0 | 28.0% |
| + Ethernet + SD | ~18,800 | ~15,050 | 17 | 0 | ~29.6% |
| **Full** (all CUs + Eth + SD) | **~26,500** | **~16,000** | **14** | **15** | **~41.8%** |

## Results: Per-Feature Marginal Cost

Delta measured from the "No ICU" baseline (17,336 LUTs).

| Feature | LUTs | Regs | BRAM | DSP | Notes |
|---------|------:|------:|-----:|----:|-------|
| **ICU** (idiv/irem) | +405 | +214 | 0 | 0 | Binary restoring divider, shared with irem |
| **ICU** (+ imul iterative) | +705 | +413 | 0 | 0 | Adds radix-4 Booth multiplier (~18 cycles) |
| **ICU** (+ imul DSP) | +797 | +448 | 0 | +4 | DSP48E1 multiply, 2-cycle latency |
| **FCU** (9 float ops) | +1,788 | +650 | 0 | +2 | IEEE 754 single: add/sub/mul/div/cmp/cvt |
| **LCU** (8 long ops) | +1,452 | +424 | 0 | 0 | 64-bit ALU + barrel shifter + lmul |
| **DCU** (12 double ops) | +4,961 | +1,217 | 0 | +9 | IEEE 754 double: add/sub/mul/div/cmp/cvt |
| **All 4 CUs + DSP** | +7,586 | +2,085 | 0 | +15 | Combined (less than sum — shared operand stack) |
| **Ethernet GMII** | +969 | +787 | +1 | 0 | RTL8211EG PHY interface + MDIO + CDC |
| **SD Native** | +661 | +596 | +0.5 | 0 | jop.io.SdNative (4-bit, cmd/data FSMs, 128×32 FIFO in BRAM) |
| **SD SPI** | +423 | +270 | 0 | 0 | Simple SPI shift register + clock divider |
| **Array cache** | +506 | +925 | 0.5 | 0 | 16-entry FA, 4 elements/line (delta: baseline - no_acache) |

### Observations

1. **SD Native is now cheap**: After fixing a FIFO BRAM inference bug (see
   below), SD Native costs only 661 LUTs — comparable to SD SPI (423 LUTs).
   SD Native is no longer an obstacle for SMP builds.

2. **DCU is the most expensive CU**: Double-precision IEEE 754 at 4,961 LUTs
   is ~3.5x the cost of FCU (single-precision). The 52-bit mantissa multiplier
   uses 9 DSP48E1 slices, and the wider datapath (64-bit operands, 11-bit
   exponent) drives up LUT count.

3. **Ethernet is cheap**: The GMII PHY interface adds only 969 LUTs — mostly
   CDC synchronizers and the MDIO management controller. The PHY itself (RTL8211EG)
   handles all the heavy lifting.

4. **ICU is negligible**: Even with all three integer operations (imul/idiv/irem),
   the cost is only 705 LUTs. The DSP variant trades 92 extra LUTs for 4 DSP
   slices and reduces multiply latency from ~18 to 2 cycles.

5. **Combined CU overhead is subadditive**: Sum of individual CU costs =
   405 + 1,788 + 1,452 + 4,961 = 8,606 LUTs. Actual combined = 7,586 LUTs.
   Savings of ~1,020 LUTs come from the shared ComputeUnitTop operand stack
   and result MUX.

## SD Native FIFO Fix (2026-03-15)

The original sweep reported SD Native at +12,220 LUTs. Root cause: SpinalHDL's
`Mem.write()` was called twice in different `when/elsewhen` branches (push+pop
path and push-only path), both writing the same address and data. SpinalHDL
emitted two separate Verilog `always` blocks, creating a 3-port memory (2 write
+ 1 read). BRAM only supports 2 ports, so Vivado implemented the 128×32 FIFO
entirely in flip-flops — 4,096 FFs for storage plus ~11,000 LUTs of mux logic.

Fix: hoist the single `.write()` call outside the when chain, gated only by
`fifoPushValid`. Commit `2e9912d`.

Vivado-validated result (XC7A100T, 1-core DDR3, baseline = 17,167 LUTs):

| Metric | Before fix | After fix |
|--------|----------:|----------:|
| SdNative LUTs | 11,663 | 609 |
| SdNative FFs | 4,797 | 590 |
| SdNative BRAM | 0 | 0.5 |
| Total (with SD) | 28,816 | 17,828 |
| SD delta | +11,649 | +661 |

**Note:** Rows marked with `~` in the tables above are estimates based on
subtracting the 11,000 LUT savings from the original measurements. A full
sweep re-run will provide exact numbers.

## SMP Implications

Using the 2-core SMP build (wukongSmp, all CUs, no Eth/SD) as a reference:

| Config | LUTs | LUT% | Timing (WNS) | Status |
|--------|------:|-----:|-------------:|--------|
| 1-core full (CUs + Eth + SD) | ~26,500 | ~41.8% | TBD | Expect comfortable |
| 2-core SMP (CUs + Eth + SD) | ~44,400 | ~70.0% | TBD | Should be feasible (was 87.3% before fix) |
| 2-core SMP (CUs, no Eth/SD) | 38,011 | 59.9% | +0.080 ns | **Timing met** |

Per additional core (with all CUs, no Eth/SD): approximately **7,200 LUTs**.

Estimated max core counts at 85% LUT budget (53,890 LUTs), DDR3 + all CUs:

| Cores | Est. LUTs | LUT% | Feasibility |
|------:|----------:|-----:|-------------|
| 1 | 24,922 | 39.3% | Comfortable |
| 2 | 38,011 | 59.9% | Verified on HW |
| 3 | ~45,200 | 71.3% | Likely feasible |
| 4 | ~52,400 | 82.6% | Tight but possible |
| 5 | ~59,600 | 94.0% | Probably too tight |

Without compute units (baseline core only): per-core cost drops to ~4,400 LUTs,
allowing up to 9 cores at 85% budget (consistent with artix7-core-estimates.md).

## Comparison with Standalone Benchmarks

The per-feature costs measured here in a full system context closely match the
standalone ComputeUnitBench results from compute-unit-timing-benchmark.md:

| Unit | Standalone LUTs | In-system delta | Ratio |
|------|----------------:|----------------:|------:|
| ICU | 569 | 705 | 1.24x |
| FCU | 1,379 | 1,788 | 1.30x |
| LCU | 1,895 | 1,452 | 0.77x |
| DCU | 4,281 | 4,961 | 1.16x |

The ~20% overhead for ICU/FCU/DCU reflects the additional glue logic:
ComputeUnitTop wrapper, operand stack, result MUX, pipeline integration, and
microcode ROM entries for the sthw/wait/ldop handlers. LCU measures smaller
in-system due to logic sharing with the common operand stack infrastructure.

## Presets and Build Commands

| Preset | Description | Est. LUTs |
|--------|-------------|----------:|
| `wukongDdr3` | Bare DDR3 (idiv/irem HW) | 17,741 |
| `wukongFull` | All CUs + Eth + SD | ~26,500 |
| `wukongSmp 2` | 2-core, all CUs, no Eth/SD | 38,011 |
| `wukongFullSmp 2` | 2-core, all CUs + Eth + SD | ~44,400 |

```bash
# Generate Verilog
sbt "runMain jop.system.JopTopVerilog wukongSmp 2"

# Build bitstream
cd fpga/qmtech-xc7a100t-wukong && make ddr3-smp-build

# Run utilization sweep
sbt "runMain jop.system.UtilSweep <label>"
```
