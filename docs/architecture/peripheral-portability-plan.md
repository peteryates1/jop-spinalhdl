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

## Plan

### 2a — lift the remaining board facts into config
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

2a is pure data and blocks the rest. 2b is the only real design work and is
worth isolating so its risk does not contaminate the mechanical steps. 2c is
cheap once both land. 2d is irreversible, so it goes last and only after
hardware verification.

## What this is worth beyond the six exercisers

The same machinery covers the non-JOP designs already in the tree — blink
projects, UART loopbacks, SDRAM exercisers across every board — which is a
larger constituency than the flash and SD tops that prompted it.
