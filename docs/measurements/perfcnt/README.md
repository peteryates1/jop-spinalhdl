# Raw `IO_PERFCNT` captures

Hardware measurements from `java/apps/JbeBench/DoAppPerf.jop`. These are kept
because they **cannot be re-derived without the board** — and one of them
(DDR2, on the A-E115FB) cannot be simulated at all, since `Ddr2BlackBox` is
Altera ALTMEMPHY vendor IP with no model.

Each file is the UART transcript of one run: eleven 32-bit counters read back
per benchmark, plus the download log that produced it.

Filenames are `<date>-<board>-<notes>.txt`.

## Reading them

`stall` is the total; the categories below it sum to `stall`. The sum should
overshoot by ~200 cycles and no more — `DoAppPerf.dump()` snapshots all eleven
counters before printing precisely so that reading them does not measure the
printing. An overshoot of 0.1 % or more means the snapshot was lost and the
capture is biased; see item 50.

## `2026-08-19-wukong-dual-mismatched-*`

**Superseded — do not quote these for the SDR-vs-DDR3 comparison.** Both halves
of `wukongDualIndependent` ran simultaneously and correctly, but the two halves
had *different cores*: the DDR3 half had `useDspMul = true, bytecodes = "*" ->
"hw"` while the SDR half took the defaults (`imul` a ~35-cycle microcode
shift-add, `idiv`/`irem` ~1300-cycle Java calls). That is a compute difference
inside a memory experiment, so the per-cycle throughput comparison between the
two halves is contaminated.

Retained because the *stall-category shares* are unaffected — they are
memory-side ratios — and the DDR3 half reproduces the previously recorded
Wukong DDR3 row exactly, which is useful evidence that the measurement itself
is repeatable.

## `2026-08-19-wukong-dual-matched-nocounters-*`

Both halves at 100 MHz with identical cores, but built before
`hasPerfCounters` moved into the preset — so every counter reads 0 and only the
`<bench> <n> 1/s` throughput lines are usable. Kept because those lines ARE
valid and give the matched-clock A/B directly (DDR3 +3.9 / +4.6 / +3.5 % on
Kfl / UdpIp / Lift), and because comparing them against the counter-enabled
run that follows shows enabling the counters does not perturb throughput.

This is the failure mode DoAppPerf's header warns about: a bitstream without
the counters does not fail the build, it fails silently at measurement time an
hour of synthesis later. `perfcnt_report.py` now raises on it rather than
printing a table of zeroes.

## `2026-08-19-wukong-dual-matched-*` (and `-repeat-`)

**The good ones.** Both halves at 100 MHz with identical cores and counters
enabled, run simultaneously from one bitstream. `-repeat-` is an independent
second run taken after a reprogram, and establishes the noise floor: the DDR3
half is bit-identical, the SDR half moves under 0.1 %.

    ./fpga/scripts/perfcnt_report.py <file> ...
    ./fpga/scripts/perfcnt_report.py --compare <sdr>.txt 100 <ddr3>.txt 100

Repeat runs need a REPROGRAM, not a UART reset: `hasRuntimeReset` is
`!isMultiSystem`, so the dual preset is the one config the runtime reset does
not cover.
