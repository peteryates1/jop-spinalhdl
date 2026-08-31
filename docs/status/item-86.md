# Item 86 — Build port, phase 3a: the Vivado flow, once

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 86](../current-status.md#item-86).

---

The Vivado side never had a shared flow. `vivado.mk` existed but was included by
NO board, so the Make layer was untested -- and the real duplication was never
in Make anyway: it was **56 Tcl scripts, 2,495 lines**, four boards each
carrying its own copy of the same five families.

Three shared scripts now under `fpga/scripts/`, all environment-driven the way
`JOP_CFG_DIR` already reached the non-project builds:

| script | replaces |
|---|---|
| `vivado_create_project.tcl` | 9 create-project scripts, 356 lines |
| `vivado_build_project.tcl` | 8 project-mode builds, 236 lines |
| `vivado_build_nonproject.tcl` | 7 non-project builds, 429 lines |

**What was normalised and what was NOT.** Four of the eight project-mode builds
disabled incremental synthesis and four did not:

```tcl
set_property AUTO_INCREMENTAL_CHECKPOINT 0 [get_runs synth_1]
```

Incremental synthesis reuses a previous checkpoint when Vivado judges the design
close enough -- exactly what you do not want when the question is "does this
still fit and still meet timing", because the answer can come back from a stale
checkpoint. That divergence was drift, so it is now uniform. The **impl
directives were left as parameters**, because they are a real per-design choice:
the SDRAM exerciser runs bare `opt/place/route` with margin to spare, while the
JOP builds need `Explore` / `ExtraTimingOpt` / `AggressiveExplore` to close.
Flattening those into one "standard" flow would have silently re-tuned every
build. Same reasoning gave `JOP_IP_GEN_TARGET` its own flag rather than
dropping or universalising the one board's `generate_target all`.

Two things could not be fields. `JOP_XDC` is an **ordered** list because XDC
order is load-bearing (item 58: read `wukong_ddr3.xdc` before
`rtl8211eg_gmii.xdc` and its `[get_clocks e_rxc]` matches nothing, the
asynchronous exclusion silently does not apply, and the build reports a
violation it should not). And `JOP_POST_SYNTH_TCL` sources a file after
synthesis for the dual-cluster build's `set_max_delay` / `set_clock_groups`,
which can only be applied to a synthesised netlist -- real per-design Tcl, and
pretending otherwise would have meant leaving that build unconverted.

**Equivalence was PROVEN, not asserted.** The converted DB_FPGA DDR3 build came
out at 12,872 LUTs against the on-disk baseline's 22,547 -- a 43 % drop. A Tcl
change cannot alter a LUT count, so that number was either RTL evolution or
evidence the script was not equivalent, and the difference is not something to
settle by reasoning. The original script was restored from git and re-run **on
byte-identical RTL**:

| | control (original) | shared script |
|---|---|---|
| Slice LUTs | 12,872 (20.30 %) | 12,872 (20.30 %) |
| Slice Regs | 11,505 (9.07 %) | 11,505 (9.07 %) |
| Block RAM | 22 (16.30 %) | 22 (16.30 %) |
| Timing | MET, WNS +0.242, WHS +0.052 | MET, WNS +0.242, WHS +0.052 |

The only differing line is the build timestamp.

**So the 43 % is real, and nobody knew.** The DB_FPGA DDR3 build has gone from
35.56 % to 20.30 % of the part and from WNS +0.010 ns to **+0.242 ns** since
2026-08-18, purely from RTL work done for other reasons. Block RAM rose 19.5 ->
22 while LUTs halved, which is the signature of memories moving out of
distributed RAM. Checked before celebrating: all four compute units and every
cache module are still present in the generated Verilog -- this project has lost
10 JVM tests before to CU instantiation being skipped, and a large area drop is
worth ruling that out rather than assuming a win. This is
[[fpga-validation-decays-silently]] inverted: the stale record was **pessimistic**,
and a board nobody had rebuilt in eight days was carrying 15 points of
utilisation headroom that no plan knew about.

**Converted so far:** DB_FPGA V5 (9 tcl -> 2), `alchitry-au-ddr3-test` (3 -> 1).
Verified by cold build: `uart_txgen`, `uart_loopback`, and DDR3 against control.
The Wukong's orphaned `program_bitstream.tcl` also went -- unreferenced by its
Makefile, which uses openFPGALoader, and it selected `[lindex $hw_targets 0]`,
the same wrong-board hazard in Vivado form. **Both Alchitry boards still have
their own copies of that hazard**; neither is attached, so they are recorded
rather than changed.

**The Wukong followed: 16 Tcl scripts -> 6** (four IP generators plus the two
the analysis docs cite). Six non-project builds, one project-mode build and two
create-project scripts gone.

`create_ddr3_project.tcl` was **worse than vestigial**. The DDR3 flow is
non-project, so `ddr3-build` never opened the project it created -- and it read
only `wukong_ddr3.xdc`, missing the GMII and base constraints the real build
uses. Anyone opening that project in the GUI was looking at a
differently-constrained design than the one that ships. `ddr3-build` also gained
a dependency on `ddr3-generate`; it had none, so building without a separate
generate step used whatever RTL happened to be lying around.

The dual-cluster build's post-synthesis constraints moved to
`wukong_dual_post_synth.tcl` verbatim -- that escape hatch is what let the
seventh build share the flow instead of keeping its own 83-line copy.

**Verified against a one-day-old baseline, and it reproduced it exactly:**

| | baseline 2026-08-25 | shared script |
|---|---|---|
| Slice LUTs | 12,448 (19.63 %) | 12,448 (19.63 %) |
| Slice Regs | 11,370 (8.97 %) | 11,370 (8.97 %) |
| Block RAM | 22 (16.30 %) | 22 (16.30 %) |
| Timing | MET, WNS +0.642, WHS +0.035 | MET, WNS +0.642, WHS +0.035 |

No control build was needed here: the baseline was fresh enough that RTL had not
drifted, and the two Wukong-specific risks are both self-announcing -- a
mis-ordered GMII constraint changes the timing result, and a missing DRC waiver
fails `write_bitstream`.

**Remaining:** phase 3b, the Wukong's Makefile onto `vivado.mk`, where that file
finally gets exercised. It is not only tidiness: this board still carries
`UART_BAUD := 1000000` and `DDR3_UART_BAUD := 2000000` as constants, the exact
item 70 hazard, and `console.mk` derives the rate from the build instead. Also
unconverted: the 9 IP-generation scripts, genuinely per-IP and possibly not
worth merging.
