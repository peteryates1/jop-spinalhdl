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
| `generic-ep4ce6/jop_ep4ce6` | `JopEp4ce6SdramTop` | `ep4ce6Sdram` | to do |
| `max1000/jop_max1000` | `JopMax1000SdramTop` | `max1000Sdram` | to do — **no hardware** |
| `a-e115fb-ddr2/jop_ddr2` | `JopDdr2Ae115fbTop` | `ae115fbDdr2` | to do |
| `a-e115fb-ddr2/jop_ddr2_smp` | `JopSmpDdr2Ae115fbTop` | `ae115fbDdr2Smp(n)` | to do |
| `alchitry-au` ddr3 | `JopDdr3Top` | `auSerial` | to do |
| `dbfpga-v5` ddr3 | `JopDdr3Top` | `xc7a100tDbSerial` / `Full` | to do |
| `wukong` ddr3 | `JopDdr3WukongTop` | `wukongDdr3` / `wukongFull` | to do |
| `wukong` ddr3_smp | `JopSmpDdr3WukongTop` | `wukongSmp(n)` | to do |
| `wukong` jop_sdram_smp | `JopSmpSdramWukongTop` | `wukongSdrSmp(n)` | to do |
| `wukong` dual | `JopDualWukongTop` | `wukongDualIndependent` | to do |

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

## INDEPENDENT — bring-up jigs, keep and isolate

| jig | what it is | move to |
|---|---|---|
| `A-E115FB/uart-loopback` | `uart_loopback.sv`, RX wired to TX | already isolated; add a README |
| `wukong` uart loopback | `rtl/WukongUartLoopback.v`, three assigns | `wukong/bringup/uart-loopback/` |
| `qmtech-ep4cgx150-eth-ref` | third-party Ethernet reference — `ethernet_test.v`, `crc.v`, `ipsend.v`, `udp.v`, `iprecieve.v`, `ram.v`, `pll_125.v` | `ep4cgx150/bringup/eth-ref/` |

## RETIRE — nothing drives it

| flow | why |
|---|---|
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
