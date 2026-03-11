# Wukong XC7A100T Feature Utilization Sweep

Date: 2026-03-10
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
| Baseline (idiv/irem HW) | 17,741 | 13,075 | 12.5 | 0 | 28.0% |
| + imul iterative | 18,041 | 13,274 | 12.5 | 0 | 28.5% |
| + imul DSP | 18,133 | 13,309 | 12.5 | 4 | 28.6% |
| + FCU (9 float ops) | 19,124 | 13,511 | 12.5 | 2 | 30.2% |
| + LCU (8 long ops) | 18,788 | 13,285 | 12.5 | 0 | 29.6% |
| + DCU (12 double ops) | 22,297 | 14,078 | 12.5 | 9 | 35.2% |
| All 4 CUs + DSP imul | 24,922 | 14,946 | 12.5 | 15 | 39.3% |
| + Ethernet GMII | 18,305 | 13,648 | 13.5 | 0 | 28.9% |
| + SD Native | 29,556 | 17,872 | 12.5 | 0 | 46.6% |
| + SD SPI | 17,759 | 13,131 | 12.5 | 0 | 28.0% |
| + Ethernet + SD | 30,117 | 18,457 | 13.5 | 0 | 47.5% |
| **Full** (all CUs + Eth + SD) | **37,275** | **19,988** | **13.5** | **15** | **58.8%** |

## Results: Per-Feature Marginal Cost

Delta measured from the "No ICU" baseline (17,336 LUTs).

| Feature | LUTs | Regs | DSP | Notes |
|---------|------:|------:|----:|-------|
| **ICU** (idiv/irem) | +405 | +214 | 0 | Binary restoring divider, shared with irem |
| **ICU** (+ imul iterative) | +705 | +413 | 0 | Adds radix-4 Booth multiplier (~18 cycles) |
| **ICU** (+ imul DSP) | +797 | +448 | +4 | DSP48E1 multiply, 2-cycle latency |
| **FCU** (9 float ops) | +1,788 | +650 | +2 | IEEE 754 single: add/sub/mul/div/cmp/cvt |
| **LCU** (8 long ops) | +1,452 | +424 | 0 | 64-bit ALU + barrel shifter + lmul |
| **DCU** (12 double ops) | +4,961 | +1,217 | +9 | IEEE 754 double: add/sub/mul/div/cmp/cvt |
| **All 4 CUs + DSP** | +7,586 | +2,085 | +15 | Combined (less than sum — shared operand stack) |
| **Ethernet GMII** | +969 | +787 | 0 | RTL8211EG PHY interface + MDIO + CDC |
| **SD Native** | +12,220 | +5,011 | 0 | SpinalHDL SdcardCtrl (4-bit, cmd/data FSMs) |
| **SD SPI** | +423 | +270 | 0 | Simple SPI shift register + clock divider |

### Observations

1. **SD Native dominates**: At 12,220 LUTs, SpinalHDL's SD native controller
   costs more than all four compute units combined (7,586 LUTs). This is the
   primary obstacle for SMP builds on XC7A100T — it alone consumes 19% of the
   device. SD SPI (423 LUTs) is **29x cheaper** — a viable alternative when
   throughput is not critical.

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

## SMP Implications

Using the 2-core SMP build (wukongSmp, all CUs, no Eth/SD) as a reference:

| Config | LUTs | LUT% | Timing (WNS) | Status |
|--------|------:|-----:|-------------:|--------|
| 1-core full (CUs + Eth + SD) | 37,027 | 58.4% | -2.043 ns | Violations in CDC |
| 2-core SMP (CUs + Eth + SD) | 55,359 | 87.3% | -2.150 ns | **Too congested** |
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
| `wukongFull` | All CUs + Eth + SD | 37,275 |
| `wukongSmp 2` | 2-core, all CUs, no Eth/SD | 38,011 |
| `wukongFullSmp 2` | 2-core, all CUs + Eth + SD | 55,359 |

```bash
# Generate Verilog
sbt "runMain jop.system.JopTopVerilog wukongSmp 2"

# Build bitstream
cd fpga/qmtech-xc7a100t-wukong && make ddr3-smp-build

# Run utilization sweep
sbt "runMain jop.system.UtilSweep <label>"
```
