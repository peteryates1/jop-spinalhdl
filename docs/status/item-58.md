# Item 58 — `source` inside an XDC is silently ignored by Vivado — four shared constraint files have never been applied

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 58](../current-status.md#item-58).

---

> **Partially closed 2026-08-31.** The SDR SDRAM IOB packing named below is now
> applied: the Wukong's SMP SDR flow was reading a tracked `wukong_jop_sdram.xdc`
> that lacked `set_property IOB TRUE` on `sdram_DQ`/`sdram_DQM`, while the
> generated file carries both. Pointing that flow at the generated constraints
> applied them for the first time. The Ethernet GMII half of this item is
> untouched.

**Found 2026-08-23 by switching one board to generated constraints and running
the control.** `wukongSdram` was built twice from the SAME Verilog, differing
only in which XDC was read. The results were not identical:

| | generated XDC | hand-written XDC |
|---|---|---|
| Slice LUTs | 5,979 | 5,967 |
| **Slice Regs** | **5,574** | **5,608** |
| WNS | +0.414 ns | +0.404 ns |

Vivado is deterministic for fixed inputs, so a 34-register difference had to
come from the constraints. It did:

```
CRITICAL WARNING: [Designutils 20-1307] Command 'source' is not supported in
the xdc constraint file. [wukong_jop_sdram.xdc:122]
```

**`source` does not work inside a file read by `read_xdc`.** Every shared
constraint file included that way has been silently absent from every build:

| file | includes | consequence |
|---|---|---|
| `wukong_jop_sdram.xdc:122` | `sdram_sdr.xdc` | **SDR SDRAM IOB packing never applied** |
| `wukong_sdram.xdc:132` | `sdram_sdr.xdc` | same, SDRAM exerciser |
| `wukong_ddr3.xdc:68` | `rtl8211eg_gmii.xdc` | **Ethernet GMII constraints never applied** |
| `wukong_ddr3.xdc:5` | `wukong_ddr3_base.xdc` | harmless — the build TCL reads it directly |

The DDR3 SMP build logs **ten** of these critical warnings. IOB mentions in the
build log: 26 with the generated file, 2 with the hand-written one.

**What it cost.** `sdram_sdr.xdc` exists to "place data and DQM registers in I/O
blocks for deterministic timing" on the SDRAM interface. That has never
happened, so every SDR build has had its DQ/DQM registers placed in the fabric
wherever the placer liked — working (these builds pass `DoAll` on hardware) but
with I/O timing that is neither deterministic nor as good as intended. The
Ethernet case is worse in principle: GMII constraints simply absent on any DDR3
build carrying the PHY.

**Already fixed for one board.** `XdcGenerator` now INLINES the two IOB
properties instead of emitting a `source` line, so `wukongSdram` — the first
board on generated constraints — is the only Wukong build where that packing has
ever taken effect. The register delta above is that fix landing.

**FIXED 2026-08-23, and the fix had a second trap in it.** All three moved to
`read_xdc` in the build TCL. `wukongFull` (the only DDR3 preset carrying the
PHY, so the only one that can test it) now reports **zero** `Designutils
20-1307`.

But applying constraints that were never applied is not a no-op, and ORDER
matters:

| `wukongFull` DDR3 | 20-1307 | `e_rxc` resolved | timing |
|---|---|---|---|
| dead `source` — before | 2 | never created | "MET" — nothing was analysed |
| GMII read AFTER `wukong_ddr3.xdc` | 0 | **no** | **VIOLATED -1.228 ns** |
| **GMII read BEFORE it** | 0 | yes | **MET +0.349 ns** |

`rtl8211eg_gmii.xdc` does `create_clock -name e_rxc`, and `wukong_ddr3.xdc`
references `[get_clocks e_rxc]` in `set_clock_groups -asynchronous`. Read the
wrong way round, that matched nothing —

```
WARNING: [Vivado 12-627] No clocks matched 'e_rxc'. [wukong_ddr3.xdc:83]
```

— so the asynchronous exclusion silently did not apply and genuinely-async RX
crossings were analysed as real paths. The shared file says so in its own
header: *"After sourcing, add e_rxc to your project's set_clock_groups."*

**The design meets GMII timing.** The violation was constraint ordering, not
the hardware. Worth stating because the intermediate result looked exactly like
"the Ethernet path has always been broken", and acting on that would have been
expensive.

**Still unresolved, pre-existing:** `clk_pll_i` and `clk_125_ddr3_clk` also
match nothing at read time — they are MIG and clock-wizard clocks that do not
exist until the IP is synthesised. Vivado says it defers those
(`[Project 1-498] ... will be read post-synthesis`), and the MET result implies
they do bind, but that has not been verified directly. Any DDR3 timing number
rests on it.

#### The same disease in Quartus, found 2026-08-24 — a dead `set_clock_groups`

Not `source` this time (that works in an SDC, which is Tcl the timing analyser
executes — item 58 is Vivado-specific). A hand-copied NAME:

```
Warning (332049): Ignored set_clock_groups at jop_sdram.sdc(23): Argument -group
with value pll|altpll_component|auto_generated|pll1|clk[1] ... could not match
any element of the following types: ( clk )
```

`jop_sdram.sdc` declares the DRAM PLL's outputs asynchronous to the Ethernet
PLL and the PHY RX clock. It names the instance `pll|...`; the design
instantiates it as `dramPll|...`. **Every group is discarded**, so the
asynchronous declaration has never applied on this board.

Harmless where it was found — a single-core SDR build has +9.714 ns and no
Ethernet — but it is inert on every build that reads this file, and the effect
of a missing async exclusion is pessimism or a false violation, which is exactly
what the Wukong GMII ordering bug produced.

The instance name is chosen in Scala (`JopTop`) and was copied into the SDC by
hand, where nothing rechecks it. Same shape as the CH340N routing and the
SignalTap virtual pins: an assertion about the design, written once, never
verified again.

**The general lesson, and why this was invisible.** A CRITICAL WARNING in a
30-minute log is not a failure: the build completes, the bitstream works, and
the missing constraints only show up as timing that is quietly worse than the
constraint file claims. **The bug was found by comparing a generated artefact
against a hand-written one and refusing to explain away a 34-register
difference** — not by reading the log, which had been saying so on every build
for as long as the file has existed.
