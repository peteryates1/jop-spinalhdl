# Colorlight i5 v7.0 — bring-up

**Status: Stage 1 (BRAM) working on hardware.** `HelloWorld.jop` downloads over the
DAPLink serial port and runs.

This is the project's first board on a fully open-source toolchain — every other
target goes through Quartus or Vivado. Nothing is shared with those flows.

## Hardware

| | |
|---|---|
| Module | Colorlight i5 v7.0 |
| FPGA | LFE5U-25F-6BG381C (ECP5, CABGA381, speed grade 6) |
| Carrier | i5 ext board (the "breakout" board) |
| SDRAM | EM638325BK-6H, 8 MB, **32-bit wide** |
| Flash | GD25Q16, 2 MB — ships **write-locked** |
| Debug | ARM mbed DAPLink (`0d28:0204`), JTAG + CDC serial on one USB cable |
| Baud | 1 Mbaud (verified) — 46 KB download in 0.7 s at 63 KB/s |

Only the USB cable is connected. JTAG programming and the JOP serial download
both go through the DAPLink, so no second cable or external adapter is needed.

## Pins

| Signal | Pin | Source |
|---|---|---|
| `clk_in` (25 MHz) | P3 | Colorlight-FPGA-Projects README, "Clock" |
| `ser_txd` | J17 | `src/i5/uart_tx/top.lpf`, `src/i5/picosoc/top.lpf` |
| `ser_rxd` | H18 | as above |
| `led[0]` (D2) | U16 | README "LED"; also SODIMM pin 41 |

### Do not use U16/R16 for the UART

`riscvOnColorlight-5A-75B/README.md` lists `Uart TX U16 / Uart RX R16`. That is the
**5A-75B**, a different module. On the i5, U16 is the LED and R16 is not bonded out
to the SODIMM connector at all. The pins above come from Colorlight's own i5
examples, which agree with each other and are confirmed working on hardware.

## Clock

25 MHz oscillator → `EHXPLLL` → **40 MHz** system clock.

The PLL wrapper `fpga/colorlight-i5/pll_jop_i5.v` is `ecppll` output kept verbatim.
Regenerate with:

```
ecppll --clkin 25 --clkout0 40 --module pll_jop_i5 --file pll_jop_i5.v
```

Do not hand-edit the dividers. `ecppll` picks a set it guarantees will lock
(Fpfd 5 MHz, Fvco 600 MHz — inside the legal 400–800 band). An out-of-band Fvco
gives a PLL that never asserts LOCK, which presents as the core sitting silently
in reset with no other symptom to go on.

### Why 40 MHz and not 50

The design is strongly seed-sensitive. At a 50 MHz target it routed anywhere from
**47.6 to 50.6 MHz** across nextpnr seeds 1–5:

| seed | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Fmax | 49.51 | **50.56** | 49.85 | 47.61 | 49.16 |

Seed 2 passes 50 MHz — by 0.56 MHz, on a ±3 MHz spread. That is seed noise, not
headroom, and every future edit re-rolls it. At 40 MHz the design closes at
**46.11 MHz (seed 1)**, ~15% margin, and the seed stops mattering.

The critical path is the familiar JOP one: JBC RAM output → bytecode byte select →
`branchOffset` → `fetch.pcMux`, of which 5.8 ns is DP16KD clock-to-out. Shortening
that is how to earn 50 MHz properly — a luckier seed is not a fix.

## Resource usage (Stage 1, 64 KB BRAM, single core)

| Resource | Used | Available | % |
|---|---|---|---|
| TRELLIS_COMB (LUT4) | 7379 | 24288 | 30% |
| TRELLIS_FF | 3348 | 24288 | 13% |
| **DP16KD (EBR)** | **40** | **56** | **71%** |
| MULT18X18D | 0 | 28 | 0% |
| EHXPLLL | 1 | 2 | 50% |

**Block RAM is the binding constraint, not logic.** Main memory is 64 KB, not the
128 KB the EP4CGX150 BRAM preset uses: the LFE5U-25F has 1008 Kbit of EBR in total
and 128 KB alone would be 1024 Kbit before a single cache or the microcode store.
There is plenty of logic headroom (70% of LUTs free) — SMP or extra compute units
are limited by EBR, so they need SDRAM first.

## Build and run

```
cd fpga/colorlight-i5
make                 # generate -> yosys -> nextpnr-ecp5 -> ecppack
make program         # load into SRAM over DAPLink
make download        # send HelloWorld.jop over the DAPLink serial port (1 Mbaud)
```

The FPGA must be reprogrammed before each download — the serial bootloader only
listens once, right after configuration. This matches the other boards.

`make program` loads SRAM only (volatile). Flash boot is not available: the i5's
GD25Q16 ships write-locked, and unlocking needs the procedure from
[kazkojima/colorlight-i5-tips#spiflash](https://github.com/kazkojima/colorlight-i5-tips#spiflash).

### Baud rate

1 Mbaud, verified on hardware. 40 MHz divides exactly for it — `UartCtrl` divides
by baud x 5 samples, so `40e6/(1e6*5) = 8` with no remainder — which is why this
rate rather than a rounder-looking one. There is no baud error to trade off, only
the DAPLink CDC firmware's own limit.

Measured on the 46 KB `HelloWorld.jop`:

| baud | time | rate |
|---|---|---|
| 115200 | 4.1 s | 11 KB/s |
| **1000000** | **0.7 s** | **63 KB/s** |

2 Mbaud also divides exactly (divider 4) if the DAPLink will take it — untested.

### Serial port

The DAPLink CDC endpoint is addressed by serial number, not `/dev/ttyACMn` — the
ACM numbers renumber across replugs and this host has other CMSIS-DAP and CDC
devices. Interface `-if01` is the CDC data endpoint; `-if00` is the HID debug
endpoint openFPGALoader uses. See `fpga/scripts/usb_serial_map`.

## Expected output

```
Small boot
GC init...
GC: classic (no card table - generational disabled)
GC done
CI
OK
M0
Hello World!
```

`GC: classic` is **correct** for Stage 1, not a fault. The preset has no card
table, so `GC.genActive` disables the generational collector at boot rather than
running it with a permanently empty remembered set. A card table follows once
SDRAM lands and there is a heap worth collecting.

## Gotcha hit during bring-up: stale generated Verilog

Changing the preset's `clkFreq` from 50 to 40 MHz reprograms the PLL — which lives
in `fpga/colorlight-i5/`, not in the generated Verilog — but the old **UART baud
divider stayed baked into the `.v`** because the Makefile's `.v` target had no
dependency on the Scala sources.

The result was not silence but plausible garbage: the board transmitted at
40e6/86/5 = **93023 baud** while the host listened at 115200, producing a steady
`d6 96 94` pattern instead of `aa aa aa`. Same family as the stale-`.jop` traps
elsewhere in this project — a wrong artefact that still *does something*.

The Makefile now makes `$(GENDIR)/$(TOP).v` depend on `$(SCALA_SRC)` and the
microcode `.dat` files. If the UART ever emits a stable repeating non-`0xAA`
pattern, suspect a clock/baud mismatch before suspecting the wiring.

## Next: Stage 2 (SDRAM)

The EM638325BK-6H is 8 MB and unusually **32 bits wide**, with **CKE tied high, CS
tied low and all four DQM tied low** — none of those are driven by the FPGA.

Two consequences:

1. **No byte masking.** Any sub-word write would need read-modify-write. JOP's main
   memory is word-addressed, so a JOP word is exactly one SDRAM access and nothing
   needs masking — this actually suits JOP well.
2. **`BmbSdramCtrl32` is the wrong shape.** It is explicitly a 32-bit-BMB-to-16-bit-SDRAM
   bridge that splits each word into two half-word bursts. Here the bus is already
   32 bits wide, so a 32-bit path is needed instead — this is the main piece of new
   work for Stage 2, not a parameter change.

`SdramCtrlNoCke` already exists in the project and is relevant given CKE is tied.

The PLL will also need a second, phase-shifted output for the SDRAM clock pin, the
way every other SDR board here does it. `EHXPLLL` has CLKOS/CLKOS2/CLKOS3 for that,
so it is a wrapper change rather than a structural one.

## References

- `/srv/git/Colorlight-FPGA-Projects` — [wuxx/Colorlight-FPGA-Projects](https://github.com/wuxx/Colorlight-FPGA-Projects); pinouts and i5 examples
- `/srv/git/riscvOnColorlight-5A-75B` — [ghent360/riscvOnColorlight-5A-75B](https://github.com/ghent360/riscvOnColorlight-5A-75B); **5A-75B, different pinout**
- `/srv/git/atari800-spinalhdl/boards/i5-7v0` — prior ECP5 work in this workspace; PLL example, no UART
