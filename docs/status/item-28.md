# Item 28 — ~~`DoAll` dies at `CollectionTest` on the Wukong — FIXED~~

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 28](../current-status.md#item-28).

---

~~**`DoAll` dies at `CollectionTest` on the Wukong**~~ — **FIXED
2026-08-08. Three real hardware defects, `wukongFull` now DoAll 66/66** with
every compute unit in hardware (was 59/66 with a crash).

| # | defect | symptom |
|---|---|---|
| 1 | **FCU compare**: a lone zero operand fell through to the exponent compare | `0.75f <= 0` TRUE |
| 2 | **DCU compare**: identical defect in the sibling unit | same, for double |
| 3 | **DCU divider**: dropped its last quotient bit | `Math.sqrt(9.0)` = 3.345 |

**1 and 2 — the compare.** `unpackFloat`/`unpackDouble` flush zero to
`exp := 0`, which is the *unbiased* exponent of 1.0. Only both-operands-zero
was special-cased, so a lone zero was compared as if it were ~1.0 and every
magnitude below 1.0 came out "less than zero". `HashMap`'s constructor is
`if (loadFactor <= 0 …) throw`, so with an FCU present **every `HashMap`
construction threw** on the default `0.75f` — and the throw concatenates a
float into its message, so control vanished into float-to-string. That is
why `CollectionTest`, which contains no float at all, died: silently
standalone, as `bytecode 255 not implemented` under `DoAll`. Fixed by
deciding on the sign of the non-zero operand.

**3 — the divider.** `DIV_ITER` read `val q = divQuotient` at the final
count; that is a register, so it returned the pre-update value and lost the
last quotient bit. `resMant`'s leading 1 landed at bit 53 instead of 54 and
`ROUND` read a zero as the hidden bit — packing `1.1010…` for `1.0101…`, so
`1.0/3.0` gave 0.416667. Only quotients with dividend < divisor are
affected, which is why `div_normal` (7/2, 12/4) never caught it.

**Why it stayed latent for months.** Every one of these hides behind the
values the tests happened to use: `fcmp_zeros` only compared zero *with*
zero, all other compare cases use 1.0/2.0 where exponent ≥ 0 gives the right
answer, and both divide cases are exact with dividend > divisor. The FCU was
signed off at "52/52 BRAM JVM tests" on a suite predating `CollectionTest`.

Guards added, each **verified to fail on the unfixed RTL** rather than
merely passing: `fcmp_one_operand_zero`, `dcmp_one_operand_zero`,
`div_inexact` (both units). 145/145 in `jop.core`.

On-target reproducers kept: `FcuBugTest` (the exact operations `HashMap`
performs, integers only) and `MathBugTest` (`MathTest`'s 21 checks reported
individually, because `MathTest` chains them with `&&` and reports only
"failed!"). `OneTest` runs a single `TestCase` from a cold start.

**A fourth suspicion was wrong and is worth recording**: the FCU divider has
the same `val q = divQuotient` shape, so it looked like the same bug. Patching
it broke `7.0/2.0`, which had been correct. Reverted — its iteration
structure differs and it never had the defect. `div_inexact` passes there
unmodified and now stands as proof.

**All six DDR3 Wukong presets re-verified against the final RTL**
(2026-08-08), rather than leaving intermediate-state results lying around —
`wukongDdr3AllCu`'s only previous record was a *failure* from before any fix:

| preset | LUTs | WNS | DoAll |
|---|---:|---:|---|
| `wukongDdr3` (baseline) | 17515 | +0.360 | **66/66** |
| `wukongDdr3DspMul` | 17850 | +0.263 | **66/66** |
| `wukongDdr3Lcu` | 18474 | +0.207 | **66/66** |
| `wukongDdr3Fcu` | 18870 | +0.029 | **66/66** |
| `wukongNoDcu` | 20161 | +0.033 | **66/66** |
| `wukongDdr3AllCu` | 24497 | +0.008 | **66/66** |
| `wukongFull` | 25624 | +0.121 | **66/66** |

`wukongDdr3Lcu` passing means the **LCU is clean** — the three defects were
confined to the FCU and DCU.

`wukongDdr3Fcu` was added on 2026-08-08: it had **never been built or run**,
despite being the preset that isolates the FCU (`wukongDdr3 + float -> hw`)
and therefore the most direct check on the compare fix in item 28. It was
missed because the sweep was assembled from the presets that already had
bitstreams, so the one preset with no history was the one that got skipped. Builds were staggered against tests, so each
Vivado run overlapped the previous bitstream's ~4-minute DoAll; bitstreams
are stashed per preset because every build writes the same output path and
would otherwise clobber the one under test.

**The SDR-on-Artix trio now runs too** (2026-08-08) — first time JOP has
used the Wukong's SDRAM. All three pass `DoAll` 66/66, but two do not close
timing at 100 MHz:

| preset | LUTs | WNS | DoAll |
|---|---:|---:|---|
| `wukongSdram` | 4963 | +0.318 | **66/66** |
| `wukongSdrAllCu` | 11801 | **-0.061** | 66/66 *(timing violated)* |
| `wukongSdrFull` | 13232 | **-0.774** | 66/66 *(timing violated)* |

A passing `DoAll` on a violated bitstream proves nothing — it can misbehave
arbitrarily and the failure would be intermittent. Both need a seed sweep or
a lower clock before they mean anything. Note the all-CU configs sit on the
edge on both memories: DDR3 AllCu closed at **+0.008 ns**.

Two build-flow defects were fixed to get here, neither about the design:

- **`clk_wiz_0` is generated by BOTH flows into the same IP directory.**
  `create_sdram_clk_wiz.tcl` and `create_ddr3_clk_wiz.tcl` emit the same
  module name, and only the SDR one has the phase-shifted `CLKOUT2` that
  `JopSdramWukongTop` wires to `sdram_clk`. Whichever flow ran last owns the
  IP, so switching without regenerating fails synthesis with *"named port
  connection 'clk_100_shift' does not exist"*. The SDR build now regenerates
  its own clk_wiz first.

  **FIXED PROPERLY 2026-08-17 — the clock wizards are named for their
  FUNCTION**: `sdr_clk`, `ddr3_clk`, `bram_clk`. Regenerating first was a
  workaround that cost ~105 s on every flow switch and still left one live
  IP; the three variants now occupy three directories and coexist, so
  switching memory type regenerates nothing. It was in fact a THREE-way
  collision — the BRAM flow emitted `clk_wiz_0` too, with only `clk_100`.
  `Board.scala` derives the instance name from `memType` rather than from
  the index in `systems`, so the dual build no longer depends on SDR
  happening to sit at index 1. Two things fell out: `create_sdram_clk_wiz_1.tcl`
  was config-identical to `create_sdram_clk_wiz.tcl` (the dual's SDR clock
  was raised 80→100 MHz and the two were never collapsed) and is deleted;
  `build_sdram_exerciser_80mhz.tcl` is deleted too — its 80 MHz wizard no
  longer existed, so pointing it at `sdr_clk` would have silently built a
  100 MHz bitstream from RTL named `_80mhz`. It is recoverable from `6b31502`
  if the 80 MHz exerciser is ever wanted again; rebuilding it needs a
  `sdr_clk_80` variant of `create_sdram_clk_wiz.tcl`. Its hand-modified
  `SdramExerciserWukongTop_80mhz.v` was never tracked (it lived in the
  gitignored `spinalhdl/generated/`), so that part is gone for good — it was
  a hand-edit of generated output, which is why it should never have been the
  only copy of anything.

  Note the derived clock names in XDC follow the **IP module** name, not the
  RTL instance name (which is `clkWizBlackBox`) — hence `clk_125_clk_wiz_0`
  became `clk_125_ddr3_clk`, and the dual's `clk_100_clk_wiz_1` became
  `clk_100_sdr_clk`. Getting this wrong is silent: `set_clock_groups` on a
  clock that matches nothing simply does not constrain, which is how the
  −2.037 ns CDC regression hid in the first place.
- **`wukongSdrFull` could not generate a bitstream at all.** Ethernet and SD
  pin constraints existed only in `wukong_ddr3.xdc`, so 32 of 77 ports had no
  LOC/IOSTANDARD and `write_bitstream` refused (DRC NSTD-1 / UCIO-1). Moved
  to `wukong_peripherals.xdc` and read by the SDR flow. `wukong_ddr3.xdc`
  keeps its copy — removing constraints that six verified DDR3 configs
  depend on was not worth the risk — so the two are **duplicated and must be
  kept in sync**; collapsing that is a follow-up.

**The dual, SMP and BRAM presets now run too** (2026-08-08) — every Wukong
preset that can be built has been on hardware:

| preset | WNS | test |
|---|---:|---|
| `wukongDualIndependent` | -0.365 | **both clusters `DoAll` 66/66 concurrently** |
| `wukongBram` | — | Hello World from the built-in BRAM image |
| `wukongSmpMinimal 2` | +0.500 | `SmpCacheTest` PASS |
| `wukongSmp 2` | +0.318 | `SmpCacheTest` PASS + `DoAll` 66/66 |
| `wukongFullSmp 2` | +0.285 | `SmpCacheTest` PASS + `DoAll` 66/66 |

`SmpCacheTest` is the meaningful SMP test — `NCoreHelloWorld` only prints
from `cpuID==0`, so it cannot distinguish a working second core from a dead
one. Its `.jop` had never been built; `make -C java/apps/SmpCacheTest`.

The dual needed its SDR cluster moved from 80 to 100 MHz — see
`docs/architecture/dual-subsystem-design.md`, "Phase 2 Resolved", which also
records what that was *not* (IOB packing, the `set_max_delay` violation, the
`sdram_clk` phase shift), each disproved by measurement.

`wukongBram` could not generate a bitstream at all: `wukong_jop_bram.xdc`
constrained a port named `clk_in`, but the generated top's ports are `clk`
and `resetn`. The stale name matched nothing, so both reached implementation
unconstrained and DRC refused (NSTD-1 / UCIO-1) — the same failure mode as
`wukongSdrFull` above, from a different cause. This is now the third build
killed by unconstrained ports; a pre-implementation check that every top-level
port has a LOC would have caught all three.

**Two presets cannot be tested, and should be fixed or deleted:**

- **`wukongDual`** — differs from `wukongDualIndependent` only by
  `interconnect = Some(InterconnectConfig(...))` and
  `monitors = Seq(WatchdogConfig(...))`, and **neither field is read by any
  RTL** (Phase 3 message queues are still "Future"). It also has no `case` in
  `JopTopVerilog`, so it cannot be generated. Building it would produce the
  same hardware as `wukongDualIndependent` under a name implying otherwise.
- **`wukongDualSmp` is misleadingly named** — its `case` maps to
  `wukongDualIndependentSmp`, the *no*-interconnect variant. Neither name
  reaches the interconnect design.
- **`wukongBramFull`** and **`auMinimal`** — no `case` in `JopTopVerilog`, so
  unreachable. Unlike `wukongDual` these look like plain omissions rather
  than unimplemented features.

**Every other preset has now been on hardware, or has no board attached.**
Sweeping `JopConfig`'s definitions against `JopTopVerilog`'s cases is the
cheap way to find this class of gap — it is what surfaced the three
unreachable presets above. Note that all 40 presets *are* covered at config
level by `JumpTableResolutionTest`, including the unreachable ones, so a
green test suite does not imply a preset can be generated, let alone run.
The presets with no board attached are `auSerial`, `max1000Sdram` and
`ep4ce6Sdram` (`minimum` and `simulation` are not board targets).

### Compute units and bytecode implementation

**Implementation coverage, measured 2026-08-05.** Every configurable bytecode
crossed with every implementation it may legally take, against the configs that
some passing DoAll simulation actually selects:

| implementation | covered | gaps |
|---|---|---|
| **Java** | **32 / 32** | none — the default-config sims select every Java handler |
| **Hardware** | **32 / 32** | none — `JopDcuCacheSim` runs `"*" -> "hw"` |
| **Microcode** | **12 / 12** | none — the four dead float handlers were deleted and `lmul` was a config defect (item 22) |

So every implementation that exists is now selected by a passing simulation,
with no gaps in any of the three columns.

**The caveat that matters**: this measures which handlers a config *selects*,
not which the workload *executes*. DoAll passing with `lushr = mc` proves the
build is sound; it does not prove DoAll contains an `lushr`. Closing that would
need per-bytecode execution counters in the simulation — worth doing before
trusting the table as true coverage rather than as a configuration matrix.
