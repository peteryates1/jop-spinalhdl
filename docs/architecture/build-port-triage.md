# Build port — flow triage

**Compiled 2026-08-25.** Every tracked FPGA flow, sorted into what should happen
to it. Written because two flows in one session turned out not to be conversion
candidates at all — `jop_dbfpga` was generating the wrong RTL, and
`WukongUartLoopback` is deliberately hand-written — and both were only caught by
reading rather than building.

Three outcomes:

| | meaning |
|---|---|
| **CONVERT** | a JopConfig preset or a SpinalHDL top drives it; it joins `build/<config>/` and the five-step loop |
| **INDEPENDENT** | a bring-up jig, deliberately not using the config system. Kept, isolated, and marked so nobody converts it |
| **RETIRE** | nothing drives it, or what drives it no longer exists |

## Why bring-up jigs stay independent

A jig that shares machinery with the thing under test is compromised as a
diagnostic. If `JopTop` will not elaborate, or the constraint generators are
broken, you still want a way to prove the board and the cable are alive.
`WukongUartLoopback.v` already says so in its own header — "a test fixture, not
part of any design, and three assigns do not warrant a generator entry".

The risk is not that they are hand-written; it is that they look like ordinary
flows. Each gets its own directory under `fpga/<board>/bringup/<jig>/` with a
README stating the intent, so the category is structural rather than something
rediscovered by reading Verilog.

## CONVERT — a preset already produces the entity

| flow | entity | preset | state |
|---|---|---|---|
| `ep4cgx150-sdram/jop_sdram` | `JopSdramTop` | `ep4cgx150Serial` | **done** |
| `ep4cgx150-sdram/jop_smp_sdram` | `JopSmpSdramTop` | `ep4cgx150Smp(n)` | **done** |
| `ep4cgx150-sdram` mc-fallback | `JopSdramTop` | `ep4cgx150McFallback` | **done** |
| `ep4cgx150-sdram/jop_dbfpga` | `JopSdramTop` | `ep4cgx150DbFull` | **done** |
| `ep4cgx150-sdram` dbfpga-vgadma | `JopSdramTop` | `ep4cgx150DbVgaDma` | **done** |
| `ep4cgx150-sdram-test` | `SdramExerciserTop` | (BoardDesign) | **done** |
| `colorlight-i5` bram + sdram | `JopBramSerialI5Top`, `JopSdramI5Top` | `colorlightI5Bram/Sdram` | **done** |
| `wukong` jop_sdram | `JopSdramWukongTop` | `wukongSdram` | **done** |
| `ep4cgx150-sdram/jop_smp8_test` | `JopSmpSdramTop` | `ep4cgx150Smp 8` | to do |
| `ep4cgx150-bram/jop_bram` | `JopBramTop` | `ep4cgx150Bram` | to do |
| `ep4cgx150-bram-serial/jop_bram_serial` | `JopBramSerialTop` | `ep4cgx150BramSerial` | to do |
| `cyc5000-sdram/jop_cyc5000` | `JopCyc5000Top` | `cyc5000Serial` | to do |
| `cyc5000-sdram/jop_smp_cyc5000` | `JopSmpCyc5000Top` | `cyc5000Smp(n)` | to do |

| `max1000/jop_max1000` | `JopMax1000SdramTop` | `max1000Sdram` | to do — **no hardware** |
| `a-e115fb-ddr2/jop_ddr2` | `JopDdr2Ae115fbTop` | `ae115fbDdr2` | **BLOCKED — board data incomplete, see below** |
| `a-e115fb-ddr2/jop_ddr2_smp` | `JopSmpDdr2Ae115fbTop` | `ae115fbDdr2Smp(n)` | **BLOCKED — same** |
| `alchitry-au` ddr3 | `JopDdr3Top` | `auSerial` | to do |
| `dbfpga-v5` ddr3 | `JopDdr3Top` | `xc7a100tDbSerial` / `Full` | to do |
| `wukong` ddr3 | `JopDdr3WukongTop` | `wukongDdr3` / `wukongFull` | **done — DoAll 66/66 on `wukongDdr3`** |
| `wukong` ddr3_smp | `JopSmpDdr3WukongTop` | `wukongSmp(n)` | **done — 4 cores, SMPGC OK on hardware** |
| `wukong` jop_sdram_smp | `JopSmpSdramWukongTop` | `wukongSdrSmp(n)` | **done — builds; timing violated, as it always has** |
| `wukong` dual | `JopDualWukongTop` | `wukongDualIndependent` | **done — builds; timing violated, unchanged WNS** |

**The Wukong board directory is complete.** All six flows converted; four
hardware-verified or build-verified against their known-good figures.

| flow | LUTs | timing | hardware |
|---|---|---|---|
| `wukongSdram` | 5,979 | +0.414 | DoAll 66/66 |
| `wukongDdr3` | 12,448 | +0.642 | **DoAll 66/66** |
| `wukongFull` | 20,514 | +0.349 | blocked by [item 69](#item-69) |
| `wukongSmp 4` | 43,414 | +0.176 | **`cores 4, publishers 3` → SMPGC OK** |
| `wukongSdrSmp 2` | 14,331 | **−2.465** | not run — see below |
| `wukongDualIndependent` | 18,806 | **−0.364** | not run — see below |

Neither violated build was run on hardware, on this document's own rule: *a
passing `DoAll` on a violated bitstream proves nothing.* Both violations are
pre-existing — the recorded 8-core SDR SMP missed by −6.281 ns, and the whole
SDR family misses at 100 MHz (`wukongSdrAllCu` −0.061, `wukongSdrFull` −0.774).

**One number worth a second look.** The dual build came out at 18,806 LUTs where
the Aug-19 record says 29,412 — a 36 % difference from a byte-identical
configuration (same 1+1 cores, clocks, memory, bytecodes, devices), and with the
SAME WNS of −0.364 ns. The new figure is the self-consistent one: `wukongDdr3`
(12,448) plus `wukongSdram` (5,979) is 18,427, which the dual build should
approximate and does. What made the older build 11,000 LUTs larger is not
recorded in its summary and has not been established.

## CONVERT — a SpinalHDL top, needs a `BoardDesign`

| flow | top | blocked by |
|---|---|---|
| `ep4cgx150-sdram` sd-spi-exerciser | `SdSpiExerciserTop` | **done** |
| `ep4cgx150-sdram` sd-native-exerciser | `SdNativeExerciserTop` | **done** |
| `ep4cgx150-sdram/uart_test` | `UartTestTop` | nothing |
| `wukong` sdram exerciser | `SdramExerciserWukongTop` | nothing |
| `a-e115fb-ddr2/ddr2_exerciser` | `Ddr2ExerciserTop` | nothing |
| `alchitry-au-ddr3-test` | `Ddr3ExerciserTop` | nothing |
| `ep4cgx150-sdram/config_flash_exerciser` | `ConfigFlashExerciserTop` | **config-flash pad abstraction** |
| `ep4cgx150-sdram/flash_programmer` | `FlashProgrammerTop` | **config-flash pad abstraction** |
| `alchitry-au` spi diagnostic | `SpiDiagnosticTop` | **config-flash pad abstraction** |
| `alchitry-au` flash programmer (DDR3) | `FlashProgrammerDdr3Top` | **pad abstraction; merges with `FlashProgrammerTop`** |

## Blocked on board data — the A-E115FB

Attempted 2026-08-25 and **reverted**, because converting it left the board
unable to build. Two things must be fixed first, and neither is a generator
problem:

1. **Its 118-pin DDR2 interface is not in board data.** `Board.AE115FB` declares
   about two pin mappings; the DDR2 set lives only in the hand-written
   `jop_ddr2.qsf` (and `ddr2_pins.qsf`). The generated project therefore emitted
   6 pins where the design needs 118.
2. **Its pins lack the `PIN_` prefix.** Every other Altera board stores
   `PIN_E22`; this one stores `H5`. Quartus rejects the bare form —
   *"Can't place node ser_txd — illegal location assignment H5"* — so the
   generator faithfully emitted what the board declared and the tool refused it.

The generator was not at fault either time, which is why the fix is board data
rather than code. Worth doing when the board's console is reconnected so the
result can actually be verified; converting a board that cannot be tested and
cannot be built is the worst of both.

Two generator gaps DID come out of the attempt and were kept: `Board.extraIpFiles`
now infers the Quartus assignment from the file extension (`.qip` -> QIP_FILE),
and Ethernet-only IP moved to `ethIpFiles` so a UART-only build does not list a
PHY PLL it never instantiates.

## INDEPENDENT — bring-up jigs, keep and isolate

**DONE 2026-08-25.** All three now live under `fpga/bringup/<jig>/`, one
directory each, self-contained, with a README stating why they are not converted.

| jig | what it is |
|---|---|
| `bringup/a-e115fb-uart-loopback` | `uart_loopback.sv`, RX wired to TX |
| `bringup/wukong-uart-loopback` | `WukongUartLoopback.v`, three assigns — **rebuilt standalone to prove the move**, `LOOPBACK BITSTREAM DONE` |
| `bringup/ep4cgx150-eth-ref` | third-party Ethernet reference: `ethernet_test.v`, `crc.v`, `ipsend.v`, `udp.v`, `iprecieve.v`, `ram.v`, `pll_125.v` |

A single top-level `fpga/bringup/` rather than one per board: the point is that
the CATEGORY is unmissable, and a jig buried in a board directory is exactly
what gets converted on a tidy-up.

## RETIRE — nothing drives it

**DONE 2026-08-25** — 7 files removed.

| flow | why |
|---|---|
| `generic-ep4ce6` | **retired 2026-08-25** — an experiment to see whether JOP fitted a 6 K-LE part, not a board in use. The `ep4ce6Sdram` preset and `Board.GenericEP4CE6` were LEFT: they cost nothing, are exercisable in simulation, and record the fit experiment. Say if they should go too |
| `wukong` blink | **`WukongBlink` has no Scala source and no git history of one.** `create_blink_project.tcl` reads RTL that has never been in this repo |
| `ep4cgx150-sdram/probe_defaults` | no build target; a JTAG-probe helper project |
| `ep4cgx150-sdram/jop_smp_bram` | entity `JopSmpBramSerialTop`; **no preset produces that name** and there is no build target |

## Decide, do not convert

| flow | question |
|---|---|
| `ep4cgx150-sdram/jop_flash_boot` | entity is `JopSdramTop` but flash boot needs its own microcode variant; is flash boot still wanted on this board? |
| `alchitry-au` flash / replayer / spi-diag projects | the Au is slated to stay; are its four extra projects still used? |

## Counts

23 CONVERT (9 done), 10 top-based CONVERT (3 done, 4 pad-blocked), 3 INDEPENDENT,
3 RETIRE, 2 to decide. **72 hand-written constraint/project files tracked today.**
