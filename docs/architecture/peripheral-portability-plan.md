# Phase 2 — portable peripheral designs

**Status: planned, not started. Written 2026-08-25.**

Phase 1 (`79f1925`) made the constraint generators take a `BoardDesign` instead
of a `JopConfig`. That was the smaller half. This is the rest: making the
peripheral designs — SD card, config flash, SPI — usable on any board and any
FPGA family, so their hand-written `.qsf` files can be retired.

## What already exists

Considerably more than it looks from the outside.

| piece | state |
|---|---|
| `DeviceType` for the peripherals | **done** — `sdspi`, `sdnative`, `cfgflash`, plus `uart`, `ethernet`, `vgadma`, `vgatext` |
| `verilogPins` for those types | **done** — e.g. `cfgflash` → `cf_dclk`/`cf_ncs`/`cf_asdo`/`cf_data0` |
| `SD_CARD` pins in board data | **done on 3 boards** — Wukong, `qmtech-fpga-db-v4` (the EP4CGX150's carrier), `qmtech-fpga-db-v5` (the XC7A100T's) |
| `ConfigFlash` I/O device | **done and family-agnostic** — drives four wires, knows nothing about the part |
| `StartupE2` blackbox | **done** — the Xilinx config-flash escape |
| `PinResolver` | **already generic** — takes a `SystemAssembly`, nothing else |
| `BoardDesign` | **done** (phase 1) |

The SD card genuinely is "a simple plug in": it is ordinary I/O on both
families, so it needs no vendor abstraction at all — only pins. Note the DB
boards are shared between the EP4CGX150 and the XC7A100T, so the *same*
`SD_CARD` device already sits behind two different FPGA families. That is the
proof the approach works, and it is already in the tree.

## The three gaps

**1. No board declares its config flash.** Zero `BoardDevice` entries for
EPCS/EPCQ/N25Q/W25Q/GD25/S25FL. The pins exist only inside hand-written project
files — on the EP4CGX150, `flash_dclk`=F6, `flash_ncs`=D5, `flash_asdo`=E6,
`flash_data0`=D6.

**2. The vendor difference is handled by duplicating the TOP, not by
abstracting the pad.** On Altera the config-flash wires are ordinary top-level
ports. On Xilinx 7-series CCLK is not a normal I/O and must go through
`STARTUPE2`. The response was a second copy of the design:

```
FlashProgrammerTop.scala       292 lines   Altera    UartCtrl ...
FlashProgrammerDdr3Top.scala   280 lines   Xilinx    UartCtrl ... + StartupE2
```

The Xilinx file even references `FlashProgrammerTop`. Same UART machinery, same
protocol, ~12 lines of difference — for one pad escape. `ConfigFlash` itself is
already family-agnostic; it is only the *pins leaving the die* that differ.

**3. The tops have no board association.** `ConfigFlashExerciserTop`'s entire
configuration is a hardcoded `targetDirectory`. This is what phase 1 made
fixable but did not fix.

There is also a fourth, smaller item: Quartus needs three
`RESERVE_*_AFTER_CONFIGURATION` globals when the config-flash pins are reused as
regular I/O. Those are Quartus **project settings**, not pins, and have no
Xilinx analogue — so they belong in `QuartusProject`, keyed off "this design
uses `cfgflash`", not in `Board`.

## Two tracks, not one sequence

**The first version of this plan had a false dependency in it**: it ordered
2a → 2b → 2c as though the data lift blocked everything. It does not. The gaps
are per-PERIPHERAL, and the SD designs need none of them.

Proven, not assumed — `BoardDesignTest` resolves SD pins on all three boards
today, with no new board data and no vendor abstraction:

```
wukong (Xilinx)             sdspi    -> 5 pins   sd_spi_clk=L4    sd_spi_mosi=J8 ...
wukong (Xilinx)             sdnative -> 7 pins   sd_clk=L4        sd_cmd=J8 ...
ep4cgx150+DBv4 (Altera)     sdspi    -> 5 pins   sd_spi_clk=PIN_B21 ...
ep4cgx150+DBv4 (Altera)     sdnative -> 7 pins   sd_clk=PIN_B21 ...
xc7a100t+DBv5 (Xilinx)      sdspi    -> 5 pins   sd_spi_clk=A3 ...
xc7a100t+DBv5 (Xilinx)      sdnative -> 7 pins   sd_clk=A3 ...
```

So:

| track | needs | blocked by |
|---|---|---|
| **SD** (`sdspi`, `sdnative`) | a `BoardDesign` per top; a parameterised PLL to leave Altera | pins: **nothing** |
| **config flash / SPI** | board data (2a) AND the pad abstraction (2b) | both |

### The real portability blocker is the CLOCK, not the peripheral

Found while starting the SD track, and it reframes the whole phase. Six of these
tops hardcode a vendor PLL primitive:

```
ConfigFlashExerciserTop   DramPll()          SdramExerciserTop   DramPll()
SdNativeExerciserTop      DramPll()          FlashProgrammerTop  DramPll()
SdSpiExerciserTop         DramPll()          Ddr3ExerciserTop    ClkWizBlackBox
```

`DramPll()` is an Altera altpll blackbox. So `SdSpiExerciserTop` cannot build on
the Wukong today — not because of anything to do with SD, whose pins resolve
there fine, but because its clock source is nailed to one vendor.

**The abstraction for this already exists and these tops simply do not use it.**
`Board.pllType` carries a `PllType` per board, `Pll.create(board, memType,
inputClock)` returns a `PllResult`, and `JopTop` has used it all along. For a
design with no DRAM the non-SDR branch returns exactly what an exerciser needs:
a system clock and a locked signal.

That collapses phase 2 to ONE pattern with three instances:

| vendor primitive | abstraction | work |
|---|---|---|
| PLL | `PllType` — **exists**, unused by these tops | mechanical: call `Pll.create` |
| config-flash pad | none — Altera direct ports vs Xilinx `STARTUPE2` | **build it** (2b) |
| SD | none needed — ordinary I/O both families | nothing |

Take vendor primitives from the `Board`, not from a hardcoded call. That is the
whole of it.

One consequence to watch: each board's PLL produces its own frequency, and these
tops declare `FixedFrequency(80 MHz)` to match the Altera one. Moving a design to
another board changes its clock, so the declared frequency has to come from the
config too rather than being restated in the top.

Start the SD track immediately. It delivers the portability claim on real
hardware across two FPGA families while the flash track's design question is
still open, and it de-risks 2c before the harder peripheral needs it.

## Plan

### 2a — lift the remaining board facts into config
**Config flash only.** Nothing here blocks the SD track.
Pure data, no design work, unblocks everything else.

- `BoardDevice` for each board's config-flash part, with its four pins.
- `SD_CARD` for the A-E115FB (and any other board that has one).
- `RESERVE_*_AFTER_CONFIGURATION` emitted by `QuartusProject` when the design
  declares a `cfgflash` device.

*Verification:* generate the constraints for an existing hand-written exerciser
project and diff against the `.qsf`, the same way `LpfGenerator` was proven
against the i5's hand-written `.lpf` — content-identical or explain every line.

### 2b — one vendor abstraction: the config-flash pad
The only genuine design work here.

A narrow interface for "how these four wires reach the flash on this family",
with two implementations — Altera direct-to-port, Xilinx via `StartupE2` —
selected from `FpgaFamily`. Then ONE flash design serves both, and
`FlashProgrammerDdr3Top` is deleted rather than maintained.

*Risk to check first:* the two tops must be diffed properly before promising a
merge. 292 vs 280 lines and a shared `UartCtrl` says duplication, but a
functional divergence hiding in there would change the shape of this step.

*Verification:* the merged design must reproduce both existing bitstreams'
behaviour on hardware — Altera on the EP4CGX150, Xilinx on the Wukong or DB V5.

### 2c — a `BoardDesign` per peripheral top
Mechanical once 2a and 2b land: assembly, entity name, device list. Each top
then gets generated constraints, an output tree under `build/<config>/`, and
step 5 (`hw_verify.py`) for free.

Do the SD exercisers first — no vendor abstraction needed, three boards already
carry the pins, so they exercise the portability claim immediately and on real
hardware.

### 2d — retire
- Delete the hand-written `.qsf` for every converted flow.
- Delete the per-family duplicate tops.
- Decide the fate of the three tracked projects with **no build target at all**
  — `jop_smp_bram`, `probe_defaults`, `uart_test`. These look like retirement,
  not conversion.
- Retire hand-written constraint files from `ConstraintDriftTest` deliberately:
  `wukong_jop_sdram.xdc` and `wukong_ddr3_base.xdc` are its ORACLES, and
  deleting one as cleanup silently stops the ratchet checking anything.

## Why this order

Within the FLASH track, 2a is pure data and blocks the rest, and 2b is the only
real design work — worth isolating so its risk does not contaminate the
mechanical steps. 2d is irreversible, so it goes last and only after hardware
verification.

The SD track runs in parallel and starts at 2c. Treating the two as one
sequence was the mistake in the first draft: it would have made a data lift for
config flash block work that does not depend on it.

## What this is worth beyond the six exercisers

The same machinery covers the non-JOP designs already in the tree — blink
projects, UART loopbacks, SDRAM exercisers across every board — which is a
larger constituency than the flash and SD tops that prompted it.

## The PLL: generate the IP with the vendor's own tool

**Raised 2026-08-25.** Today there are NINE PLL blackboxes for TWO vendor
primitives:

| primitive | wrappers | differ only by |
|---|---|---|
| Altera `altpll` | `DramPll` (4 outputs), `Max1000Pll` (2), `Ep4ce6Pll` (2), `EthPll` (1, and no `areset`) | output count, generated-file name |
| Xilinx `clk_wiz` | `ClkWizBlackBox`, `WukongClkWizBlackBox`, `SdramExerciserClkWiz` | port set, instance name |
| Lattice `EHXPLLL` | `I5Pll` | — |
| Altera Cyclone V | `Cyc5000Pll` | different megafunction |

`DramPll` is named for its first job -- clocking the SDRAM -- but `c1` is the
SYSTEM clock, so a design with no DRAM instantiates something called `DramPll`.
And the settings are only half parameterised: `DramPllGen` does not generate the
VHDL, it reads a **441-line tracked, hand-written**
`fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd` and text-substitutes the multiply and
divide. The SHAPE -- how many outputs, at what phases -- is frozen in that file
and mirrored in the Scala blackbox, which is why each new shape needed a new
pair.

Three bugs already traced to this: the Wukong BRAM branch ties the clock
wizard's reset ASSERTED, a `set_clock_groups` was discarded because the netlist
said `dramPll` where the constraint said `pll`, and a generated config under
`build/` still reaches back into `fpga/` for that template.

### The tools are already here, and all three work

| family | generator | status |
|---|---|---|
| Lattice ECP5 | `ecppll` (`/usr/bin`) | **proven** — regenerating `pll_jop_i5.v` from `--clkin 25 --clkout0 40 --clkout1 40 --phase1 315` is BIT-IDENTICAL to the tracked file |
| Xilinx | `create_ip` in Vivado | **already the practice** — `create_clk_wiz.tcl` does exactly this |
| Altera | `ip-generate` with `altpll` (`quartus/sopc_builder/bin`) | **generates** — `altpll` is in the catalog and produced a wrapper; the parameter NAMES still need to be the correct altpll set |

The i5's PLL is already reproducible from three numbers, and its own header
records the command. It is a generator invocation written down as a file.

### Shape

```scala
case class PllOutput(role: PllRole, mhz: Int, phaseDeg: Int = 0)
case class PllSpec(inputMhz: Int, outputs: Seq[PllOutput])

trait PllVendor {                       // one per family, not per board
  def generateIp(spec: PllSpec, moduleName: String, outDir: String): Unit
  def blackBox(spec: PllSpec, moduleName: String): PllResult
}
```

`Board` carries a `PllSpec`; the vendor follows from `FpgaFamily`. All nine
blackboxes collapse, and both hand-written IP files (`dram_pll.vhd`,
`pll_jop_i5.v`) are deleted rather than maintained.

**What it buys beyond tidiness:** the reset polarity is decided once instead of
per branch; the instance name comes from the same spec `TimingConstraints`
reads, so the two cannot disagree; and `build/` stops depending on `fpga/`.

**The real risk, and it is not the refactor.** Generating IP means the
`generate` step needs a VENDOR TOOL, where today it is pure sbt. CI has no
vendor tools at all. So either the generated IP is cached and checked against
the spec, or CI cannot elaborate those designs -- and "CI cannot build it" is
how the microcode flash variant went 16 days stale. Decide this BEFORE writing
the generators, not after.
