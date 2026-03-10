# Compute Unit Timing Benchmark — Artix-7 XC7A100T @ 100 MHz

Date: 2026-03-10

## Methodology

Each compute unit (ICU, FCU, LCU, DCU) was wrapped in a standalone benchmark
component with registered inputs and outputs to isolate the unit's internal
critical path from I/O timing. Vivado 2025.2 non-project flow: synth_design +
opt_design + place_design + route_design targeting xc7a100tfgg676-2 (speed
grade -2) with a 10 ns clock constraint.

Source: `spinalhdl/src/main/scala/jop/core/ComputeUnitBench.scala`
TCL: `fpga/qmtech-xc7a100t-wukong/vivado/tcl/bench_cu.tcl`

All units benchmarked with full feature sets enabled (all operations active).

## Results Summary (after pipelining)

| Unit | Post-Synth WNS | Post-Route WNS | Fmax   | LUTs  | FFs | Status |
|------|----------------|----------------|--------|-------|-----|--------|
| ICU  | +5.473 ns      | +2.952 ns      | 142 MHz|   569 | 571 | PASS   |
| FCU  | +2.584 ns      | +0.592 ns      | 106 MHz| 1,379 | 545 | PASS   |
| LCU  | +3.947 ns      | +3.229 ns      | 151 MHz| 1,895 | 879 | PASS   |
| DCU  | +0.238 ns      | +0.505 ns      | 105 MHz| 4,281 |1,212| PASS   |

No DSP48E1 usage in ICU/FCU/LCU. DCU uses 9 DSP48E1 slices for 52-bit multiply.

### Before pipelining (baseline)

| Unit | Post-Synth WNS | Post-Route WNS | Status       |
|------|----------------|----------------|--------------|
| FCU  | +1.472 ns      | +0.959 ns      | PASS (tight) |
| DCU  | -3.127 ns      | -2.622 ns      | **FAIL**     |

## Per-Unit Analysis

### IntegerComputeUnit (ICU) — 32-bit imul/idiv/irem

Very comfortable at 100 MHz with 2.95 ns positive slack. Radix-4 multiply
(16 iterations, ~18 cycles) and binary restoring division (32 iterations,
~36 cycles) are well-bounded by their FSM structure. The critical path is
within the multiply accumulation logic.

Could run at ~142 MHz. Smallest footprint at 569 LUTs — lightweight enough
that per-core instantiation has negligible area impact even at high core counts.

### FloatComputeUnit (FCU) — 32-bit IEEE 754 single-precision

Operations: fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg.

**Before pipelining**: 0.96 ns slack. Critical path was `bMant_reg` → 2x DSP48E1
(24x24 multiply) → product(47) conditional → resExp CE logic (8.79 ns, 5 logic
levels). The DSP cascade consumed ~4.67 ns, leaving 3.5 ns for normalize + resExp.

**After pipelining**: MUL_STEP2 split into MUL_STEP2 (register product) + MUL_NORM
(normalize from registered product). Post-synth improved from +1.47 to +2.58 ns.
Post-route: +0.59 ns (the multiply path is no longer critical; a different path
now dominates, likely in the add/normalize chain).

### LongComputeUnit (LCU) — 64-bit integer arithmetic

Excellent timing with 3.23 ns slack despite 64-bit data paths. The combinational
operations (ladd, lsub, lcmp, lshl, lshr, lushr) complete in a single FSM cycle
but their carry/shift chains are shorter than the clock period. Iterative
operations (lmul ~34 cycles, ldiv/lrem ~68 cycles) are FSM-gated with no long
combinational chains.

Larger LUT count (1,895) is expected given 64-bit registers and barrel shifter.
Could run at ~151 MHz.

### DoubleComputeUnit (DCU) — 64-bit IEEE 754 double-precision

Operations: dadd, dsub, dmul, ddiv, i2d, d2i, l2d, d2l, f2d, d2f, dcmpl, dcmpg.

**Before pipelining**: Failed by 2.62 ns. Two critical paths:

1. **MUL_STEP2** (22 logic levels, 12.6 ns): `aMant_reg` → 3x DSP48E1 cascade
   (52-bit multiply) → 14x CARRY4 (normalize) → sticky. The DSP cascade alone
   consumed 6.2 ns, leaving only 3.8 ns for the 52-bit carry chain.

2. **L2D_EXEC** (44 logic levels, 12.0 ns): `opaReg_reg` → 15x CARRY4 (64-bit
   negate) → CLZ → barrel shift → 17x CARRY4 (sticky OR reduction). The entire
   long-to-double conversion was computed combinationally in one cycle.

**After pipelining** (three fixes):
- MUL_STEP2 → MUL_STEP2 (register product) + MUL_NORM (normalize). Adds +1 cycle
  to dmul only.
- L2D_EXEC → L2D_EXEC (negate + CLZ, register absVal/lz) + L2D_SHIFT (barrel
  shift + sticky). Adds +1 cycle to l2d only.
- ADD_EXEC → ADD_EXEC (speculative compute A-B, B-A, A+B in parallel, register
  results) + ADD_SELECT (MUX-only selection from registered results). Breaks the
  57-bit compare → 57-bit subtract critical path. Adds +1 cycle to dadd/dsub only.

Post-synth improved from -3.13 to +0.24 ns. Post-route: +0.51 ns. The new
critical path is the mantissa alignment barrel shifter in ADD_ALIGN
(bExp → addMantB shift → sticky, 22 logic levels). In a full design this will
need monitoring.

## Pipelining Applied

### FCU: MUL_STEP2 → MUL_STEP2 + MUL_NORM

Split the 24x24 multiply from the normalize/sticky computation. The registered
`mulProdHi` (48 bits) serves as the pipeline boundary. MUL_NORM reads the
registered product and computes resMant/sticky/resExp adjustment.

Latency impact: fmul adds +1 cycle. Other ops (fadd, fsub, fdiv, i2f, f2i,
fcmpl, fcmpg) are unaffected.

### DCU: MUL_STEP2 → MUL_STEP2 + MUL_NORM

Same pattern as FCU but with 106-bit product register. The 53x53 DSP cascade
is isolated from the 55-bit normalize carry chain.

Latency impact: dmul adds +1 cycle.

### DCU: L2D_EXEC → L2D_EXEC + L2D_SHIFT

The 64-bit negate + CLZ (computed in L2D_EXEC) is registered in `l2dAbsVal`
and `l2dLz`. L2D_SHIFT uses these registered values for the barrel shift and
sticky bit computation.

Latency impact: l2d adds +1 cycle.

### DCU: ADD_EXEC → ADD_EXEC + ADD_SELECT

The 57-bit compare (A >= B) fed into a 57-bit subtract (A - B or B - A) in the
same cycle, creating a long combinational chain. Split into:
- ADD_EXEC: speculatively compute all three results (A-B, B-A, A+B) and the
  compare (A >= B), register them.
- ADD_SELECT: MUX-only selection from registered speculative computations.

Latency impact: dadd/dsub add +1 cycle.

### ICU and LCU — No changes needed

Both have >2.9 ns slack, sufficient to absorb routing degradation in a full
design.

## Vivado Reports

Detailed timing and utilization reports are in:
```
fpga/qmtech-xc7a100t-wukong/vivado/build/cu_bench/
├── IntegerCuBench/
│   ├── timing_synth.rpt
│   ├── timing_route.rpt
│   ├── timing_paths.rpt
│   ├── utilization.rpt
│   └── utilization_route.rpt
├── FloatCuBench/
│   └── ...
├── LongCuBench/
│   └── ...
└── DoubleCuBench/
    └── ...
```

## Reproduction

```bash
# Generate benchmark Verilog
cd /path/to/jop-spinalhdl
sbt "runMain jop.core.ComputeUnitBenchVerilog"

# Run Vivado benchmarks (all 4 units, ~5 minutes)
cd fpga/qmtech-xc7a100t-wukong
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
vivado -mode batch -source vivado/tcl/bench_cu.tcl
```
