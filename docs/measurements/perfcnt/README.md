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
