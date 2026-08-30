# Measurement presets

Seven presets exist that **no board Makefile builds**. They are not dead code
and should not be deleted: each is a measurement vehicle — half of an A/B pair,
or a probe — and each produced a recorded result that is cited elsewhere in the
documentation.

They were driven by hand, so the invocation lived only in the commit message
that reported the result. This page is that invocation, written down.

## Why they have no Makefile target

A board flow builds the configuration the board is *for*. A measurement vehicle
exists to be built once, compared against its partner, and set aside — wiring
each into a board Makefile would add seven targets that are wrong to run by
default and confusing to find.

What was actually missing is the record of how to reproduce a published number.
Checked 2026-08-30: none of the seven has ever been referenced by a Makefile or
CI job, at any point in the history.

## The presets

| preset | signature | what it measured |
|---|---|---|
| `wukongDdr3Smp` | `(n, mig = Ddr3_400)` | DDR3 generational GC at 4 cores — `SMPGC OK`, 5 of 6 runs, post-route WNS +0.081 / WHS +0.065 |
| `wukongDdr3SmpMshr` | `(n, mshr = 4, mig = Ddr3_400)` | the DDR3 MSHR A/B. Differs from the row above in `l2MshrCount` and nothing else |
| `ae115fbDdr2SmpMshr` | `(n, mshr = 4)` | the DDR2 MSHR A/B — 682 → 1613 kacc/s at eight cores |
| `ep4cgx150NoCache` | — | the cache A/B: whether the caches earn their area. They do |
| `ep4cgx150BramSmp` | `(n, clkMhz = 50)` | 4-core BRAM build, isolating a stall to the SDRAM path |
| `wukongAuMatch` | — | the `ScaleL2` probe — L2 **size** is worth up to 33 % |
| `wukongDualSmp` | `(n = 2, mhz = 100)` | dual-cluster SMP. Resolved by an inline `case` arm in `JopTopVerilog`, not a `def` in `JopConfig` |

## Driving one

Generate the RTL, then hand the result to whichever board flow matches the
target device:

```bash
# RTL + constraints for a measurement config
sbt "runMain jop.system.JopTopVerilog wukongDdr3SmpMshr 8 4 buildtree"

# then build it with the board's own flow, pointing CFG at the same invocation
make -C fpga/qmtech-xc7a100t-wukong ddr3-smp-bitstream CFG="wukongDdr3SmpMshr 8 4"
```

Output lands in `build/<config>/`, where `<config>` is the sanitised invocation
— `build/wukongDdr3SmpMshr-8-4/` for the example above. `make print-cfg-dir
CFG="..."` prints it, and is the only thing that should be used to compute it;
reconstructing the name by hand is how two copies of a naming rule come to
disagree.

## The pairing rule

An A/B result means nothing unless the two arms differ in exactly one field.
This has already gone wrong once: a comparison preset carried its own confound
because the two halves had different core counts. Diff both arms field by field
before trusting any number that comes out of them.

`wukongDdr3SmpMshr` is derived from `wukongDdr3Smp`, **not** from `wukongSmp` —
which is a different preset with a different memory profile, and getting that
wrong cost a detour when the pair was first built.
