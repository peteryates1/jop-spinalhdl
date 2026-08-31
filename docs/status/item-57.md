# Item 57 — ~~The XDC/QSF generators exist and NOTHING USES THEM~~ — DONE

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 57](../current-status.md#item-57).

---

> **Closed 2026-08-31.** Every board build now reads generated constraints. The
> Wukong and i5 Makefiles invoke `XdcGeneratorMain` / `LpfGeneratorMain`, the
> EP4CGX150 takes a generated `pins.tcl`, `quartus.mk` generates the `.sdc` and
> the project Tcl, and the Wukong's SMP SDR flow — the last one reading a
> tracked file — was converted the same day.
>
> The tracked `.xdc`/`.qsf` files that remain are no longer INPUTS. They are the
> oracles `ConstraintDriftTest` checks the generators against, and deleting them
> as "unused" would remove the only thing that would notice the generator
> drifting.

**Raised 2026-08-23**, after a summary sent the wrong console port and cost an
hour. `jop.generate.XdcGenerator` and `jop.generate.QsfGenerator` both exist,
both take a `JopConfig`, and both resolve pins through `PinResolver`. Neither
is invoked by any Makefile or TCL under `fpga/` — `XdcGeneratorMain` prints to
stdout and stops there. **Every board build reads hand-maintained
constraints.**

So the config is not the source of truth for pins, and the two drift:

| | says |
|---|---|
| `wukongFull` preset | `devicePart = Some("CH340N")`, assembly `SystemAssembly.wukong` |
| `wukong_ddr3_base.xdc` (what the build reads) | UART on **J11 -> Pico uart0** (A4/A5); the CH340N at E3/F3 is hardwired and "cannot be tapped" |

The XDC is right and the config is wrong, and the generated summary faithfully
reported the wrong one. [Item 52](#item-52) is the same disease pointing at the
Java tools; this is the constraints half.

**Adopting the generator today would emit the wrong pins**, so this is not
"wire it up". `SystemAssembly.wukong` has no J11 device at all — only
`wukongWithJ11Uart` carries `Board.J11UartAdapter`, and it is described as the
DUAL-subsystem assembly, though single-system DDR3 builds use J11 too. The
assembly data has to be corrected before generation can be trusted.

Note the J11 choice is a HOST-side decision, not electrical: the XDC explains
that a second `1a86:7523` bridge is indistinguishable from the A-E115FB's, so
J11 gives a Pico CDC with a real serial number. That reasoning lives only in an
XDC comment and is invisible to the config.

**Scope, honestly.** Pin constraints are generable. Some of what is in these
files is not: `wukong_ddr3.xdc` carries hand-tuned timing exceptions (a
`ui_clk` -> `sys_clk` UART crossing, with a comment explaining it stayed
invisible while the clocks were exactly equal). The realistic split is
**generate the pins, keep hand-written timing exceptions in a separate
file** — which also makes it obvious which constraints are derived and which
are judgement.

**On templating (jmustache or similar): not recommended.** The existing
generators build strings in plain Scala from typed `PinResolver` output, and
that is the right shape — the output is structured data (pin -> property), not
prose with holes, so a template would stringify early and lose the typing that
catches a bad pin at elaboration. It would also add a dependency and a second
artefact to keep in sync. The problem here is adoption and wrong assembly data,
not the rendering mechanism.

**Order:** (1) fix the assembly so `wukongFull`'s UART resolves to J11,
(2) diff generated XDC against the hand-written one per board until they agree,
(3) switch one board's build to the generated file, (4) roll out. Step 2 is the
real work and is a pure comparison — no build risk until step 3.

#### Step 2 done for the Wukong, 2026-08-23 — the gap is TWO PINS

Ran `XdcGeneratorMain` per preset and compared pin-by-pin against the files the
builds actually read. This is far better than the item assumed.

**`wukongSdram` vs `wukong_jop_sdram.xdc`: PIN-IDENTICAL.** 45 pins each, no
mismatched assignment, nothing missing on either side — all 16 SDRAM data pins,
address, control, clock, reset, UART and LEDs. **That board is adoptable
today.**

**`wukongDdr3` vs `wukong_ddr3_base.xdc`: identical except the UART.**

| port | generated | hand-written |
|---|---|---|
| `clk` + `create_clock` | M21, 20.000 ns | same |
| `resetn` | H7 | same |
| **`ser_txd`** | **E3** | **A5** |
| **`ser_rxd`** | **F3** | **A4** |
| `led[0]`, `led[1]` | G21, G20 | same |
| CFGBVS / CONFIG_VOLTAGE / COMPRESS | same |

The whole DDR3 gap is the two UART pins — E3/F3 is the CH340N, A5/A4 is J11 ->
Pico uart0. So step 1 is not merely a prerequisite, it is *the entire remaining
difference* for this board.

**The non-pin content splits exactly as predicted.** Generated already:
`create_clock`, `CFGBVS`, `CONFIG_VOLTAGE`, `BITSTREAM.GENERAL.COMPRESS`. Not
generable and must stay hand-written: the `set_clock_groups -asynchronous`
timing exceptions in `wukong_ddr3.xdc`. One real gap: the generator emits
`# source <path-to>/fpga/constraints/sdram_sdr.xdc` as a COMMENT, so a generated
file used as-is would lose the SDR IOB packing — it needs to emit a real
`source` line, and the relative path differs per board directory.

#### Step 2 for Quartus, same day — one MISSING pin, and it is the reset button

`QsfGeneratorMain` against `fpga/qmtech-ep4cgx150-sdram/jop_sdram.qsf`:

- **44 shared ports, ZERO assignment conflicts** — including `clk_in` on
  PIN_B14 and the whole SDRAM bus. The generator gets the Altera port names
  right, which is not obvious: the RTL port is `clk_in`, not `clk`.
- **`reset_n` (PIN_AD24, the SW1 reset button) is not emitted at all.**
  `QsfGenerator` has **no reset handling whatsoever** — its only "reset" match
  is a comment — while `XdcGenerator` has a section calling
  `PinResolver.resetFpgaPin`. This is an omission, not a decision. Adopting the
  generated file as-is would leave the reset button unassigned for Quartus to
  place wherever it likes.
- **37 generated-only pins**: the Ethernet and SD daughter-board pins that
  `QsfGeneratorMain` adds unconditionally "for pin reservation". Reasonable for
  the DB build, but it means the output is not a drop-in for a preset without
  those ports.

**The reset fix is not a copy-paste from the Xilinx side.** `XdcGenerator` emits
the port as `resetn`; the Altera top level calls it `reset_n`. The name is
family-specific, which is exactly the sort of detail that makes "just wire the
generators up" the wrong instinct.

**Overall after step 2:** two boards compared, and between them the generated
constraints are wrong in **three pins total** — two UART on the Wukong DDR3 and
one reset on the EP4CGX150 — with everything else, including every memory-bus
pin on both, already identical. The generators are much closer to usable than
this item assumed when it was filed.

**Not yet checked:** the A-E115FB `.qsf`, and the non-Wukong Vivado boards.
