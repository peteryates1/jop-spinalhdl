# DB_FPGA Daughter Board — Ethernet (RTL8211EG)

Ethernet on the QMTECH DB_FPGA daughter board's RTL8211EG PHY, which speaks
GMII at 1 Gb/s (8-bit, 125 MHz) and MII at 100 Mb/s (4-bit, 25 MHz) on the
same J3 pins.

**On the EP4CGX150 core board JOP runs it at 100 Mb/s, MII** — `ep4cgx150DbFull`
on the DB_FPGA V4 and `ep4cgx150DbV5Mii` on the V5, both verified 2026-10-12
with ping, UDP and TCP echo, DHCP and DNS, and no receive errors on any
payload. At 1 Gb/s this core board's RX_CLK pin (B10) picks up extra edges when
six or more RXD lines rise together, so received frames are lost depending on
their content — the router's DHCP offer 68-100 % of the time. No PLL can take
that pin, so the FPGA cannot clean the clock up
([status item 166](../current-status.md#item-166)). `ep4cgx150DbV5Eth` keeps
GMII to reproduce it, and `xc7a100tDbEth` drives the same PHY at GMII from the
XC7A100T core board on the V5.

Build steps, per-board results and the TCP/IP stack are in
[networking.md](networking.md).

## Pin Mapping

Cross-reference between the DB_FPGA daughter board J3 header pins,
the EP4CE15 core board (reference design), and the EP4CGX150 core board.

## Method

The QMTech DB_FPGA daughter board connects via two 64-pin headers (J2, J3).
The EP4CE15 Starter Kit has a known-working Ethernet reference design
(`Project09_GMII_Ethernet`) with verified pin assignments to the U8 header.
The EP4CGX150 core board maps its U4 header to the same physical J3 connector.

Pin positions within the headers are identical across core boards --
only the FPGA pin names differ.

## Sources
- EP4CE15 reference: `ethernet_test.qsf` in [Project09_GMII_Ethernet.zip](https://github.com/ChinaQMTECH/CYCLONE_IV_EP4CE15/blob/master/Software/Project09_GMII_Ethernet.zip)
- EP4CE15 core board manual: [QMTECH_CycloneIV_EP4CE15_User_Manual(CoreBoard)-V02.pdf](https://github.com/ChinaQMTECH/CYCLONE_IV_EP4CE15/blob/master/QMTECH_CycloneIV_EP4CE15_User_Manual(CoreBoard)-V02.pdf) (U8 header table)
- EP4CGX150 core board manual: [QMTECH_CycloneIV_EP4CGX150GX_User_Manual(CoreBoard)-V01.pdf](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD/blob/main/QMTECH_CycloneIV_EP4CGX150GX_User_Manual(CoreBoard)-V01.pdf) (U4 header table)
- DB_FPGA schematic: [QMTECH_DB_For_FPGA_V04.pdf](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD/blob/main/Hardware/QMTECH-EP4CGX150GX-CORE-BOARD-V01-20220207.pdf)

## Header Mapping

| Board | UART Header (J2) | Ethernet Header (J3) |
|-------|-------------------|----------------------|
| EP4CE15 | U7 (R/P/N..B row pins) | U8 (AA/AB row pins) |
| EP4CGX150 | U5 (AD/AE/AF col pins) | U4 (A/B/C col pins) |

## Ethernet Pins (J3 Header)

| J3 Pin | EP4CE15 (U8) | EP4CGX150 (U4) | Signal | Direction |
|--------|-------------|----------------|--------|-----------|
| 11 | AA17 | A21 | e_mdio | bidir |
| 12 | AB17 | A20 | e_mdc | output |
| 13 | AA18 | A19 | e_txer | output |
| 14 | AB18 | A18 | e_txd[7] | output |
| 15 | AA19 | C17 | e_txd[6] | output |
| 16 | AB19 | B18 | e_txd[5] | output |
| 17 | AA20 | C16 | e_txd[4] | output |
| 18 | AB20 | B17 | e_txc | input |
| 19 | Y22 | A17 | e_txd[3] | output |
| 20 | Y21 | A16 | e_txd[2] | output |
| 21 | W22 | B15 | e_txd[1] | output |
| 22 | W21 | A15 | e_resetn | output |
| 23 | V22 | C15 | e_txd[0] | output |
| 24 | V21 | C14 | e_txen | output |
| 25 | U22 | C13 | e_gtxc | output |
| 28 | R21 | C11 | e_rxer | input |
| 29 | P22 | A13 | e_rxd[7] | input |
| 30 | P21 | A12 | e_rxd[6] | input |
| 31 | N22 | B11 | e_rxd[5] | input |
| 32 | N21 | A11 | e_rxd[4] | input |
| 33 | M22 | B10 | e_rxc | input |
| 34 | M21 | A10 | e_rxd[3] | input |
| 35 | L22 | C10 | e_rxd[2] | input |
| 36 | L21 | B9 | e_rxd[1] | input |
| 37 | K22 | A9 | e_rxd[0] | input |
| 38 | K21 | A8 | e_rxdv | input |

*Note: Pin 15 was originally transcribed as A17 (same as pin 19) due to a
PDF text-extraction error. Corrected to C17 per the U4 header table.*

In MII mode only `e_txd[3:0]` and `e_rxd[3:0]` carry data, the PHY supplies
the transmit clock on `e_txc`, and `e_gtxc` is not declared at all. The pin
assignments a build uses are generated from `QmtechFpgaDbV4` and
`QmtechFpgaDbV5` in `Board.scala`.

## Full U4 Header Pin Table (EP4CGX150)

### Odd Pins (1, 3, 5, ..., 55, 57)

| Pin | FPGA | Pin | FPGA | Pin | FPGA | Pin | FPGA |
|-----|------|-----|------|-----|------|-----|------|
| 1 | 3V3 | 17 | C16 | 33 | B10 | 49 | A3 |
| 3 | C21 | 19 | A17 | 35 | C10 | 51 | B2 |
| 5 | B23 | 21 | B15 | 37 | A9 | 53 | D1 |
| 7 | B21 | 23 | C15 | 39 | A7 | 55 | E2 |
| 9 | C19 | 25 | C13 | 41 | B7 | 57 | VIN |
| 11 | A21 | 27 | C12 | 43 | B5 | | |
| 13 | A19 | 29 | A13 | 45 | B4 | | |
| 15 | C17 | 31 | B11 | 47 | C5 | | |

### Even Pins (2, 4, 6, ..., 56, 58)

| Pin | FPGA | Pin | FPGA | Pin | FPGA | Pin | FPGA |
|-----|------|-----|------|-----|------|-----|------|
| 2 | 3V3 | 18 | B17 | 34 | A10 | 50 | A2 |
| 4 | B22 | 20 | A16 | 36 | B9 | 52 | B1 |
| 6 | A23 | 22 | A15 | 38 | A8 | 54 | C1 |
| 8 | A22 | 24 | C14 | 40 | A6 | 56 | E1 |
| 10 | B19 | 26 | B13 | 42 | B6 | 58 | VIN |
| 12 | A20 | 28 | C11 | 44 | A5 | | |
| 14 | A18 | 30 | A12 | 46 | A4 | | |
| 16 | B18 | 32 | A11 | 48 | C4 | | |

## RTL8211EG PHY Configuration (from DB_FPGA schematic)

- PHY Address: 0 (detected by MDIO scan; ID `001C C915`)
- AN[1:0] = 11 (auto-negotiation, advertise all)
- RXDLY: 2ns, TXDLY: 2ns
- SELRGV: 1 (3.3V I/O)
- COL/Mode: 0 (GMII interface)

## Clocking

Three clock domains, asynchronous to each other:

| Clock | Source | Frequency | Domain |
|-------|--------|-----------|--------|
| system | DRAM PLL output c1 | 80 MHz on `ep4cgx150DbFull` | CPU, memory, I/O read mux |
| TX | GMII: the FPGA's Ethernet PLL (`pll_125.v`, 50 MHz × 5/2), driven out on `e_gtxc`. MII: the PHY, on `e_txc` | 125 / 25 MHz | `MacTxBuffer` pop side, PHY TX |
| RX | the PHY, on `e_rxc` | 125 / 25 MHz | `MacRxBuffer` push side, byte packing |

The dual-port RAMs in `MacRxBuffer` and `MacTxBuffer` cross between the PHY
clock domains and the system clock.

**GMII** (the Ethernet device's `gmii` parameter): 8 data bits each way. The
FPGA makes the 125 MHz transmit clock and drives it to the PHY on `e_gtxc`;
`e_txc` is unused. The PHY returns a source-synchronous 125 MHz on `e_rxc`, and
the receive data is captured in I/O-element registers clocked by it. The
driver advertises 1000BASE-T FD (MDIO registers 0, 4, 9) because
`Const.ETH_GIGABIT`, which the generator derives from `gmii`, is true.

**MII** (no `gmii`; `phyDataWidth` 4): 4 data bits each way, both clocks from
the PHY at 25 MHz, and no `e_gtxc` port — when an MII design still declared
one, the fitter placed it on a pin the PHY drives (item 166).
`Const.ETH_GIGABIT` is false, so the driver advertises no 1000BASE-T and the
link comes up at 100 Mb/s even on a gigabit switch.

### RX clocking at 1 Gb/s: source-synchronous, not mesochronous

The PHY's `e_rxc` is a clock-data-recovery (CDR) recovered clock — it is
**not** phase-locked to the FPGA's reference oscillator. This makes
mesochronous clocking (using a PLL-generated 125 MHz) unreliable for
data capture.

Tested approaches and results (100-packet ping tests at 1 Gb/s, February 2026):

| Approach | ICMP Ping Loss |
|----------|----------------|
| PLL mesochronous 0° phase | 24% |
| PLL mesochronous 90° phase | 14% |
| PLL mesochronous 180° phase | 34% |
| CRC check disabled (mesochronous) | 30% |
| **Source-synchronous e_rxc rising edge + FAST_INPUT_REGISTER** | **2.5%** |
| Source-synchronous e_rxc falling edge + FAST_INPUT_REGISTER | 16% |

The mesochronous approaches all showed CRC errors because the PLL clock
has a random and drifting phase relationship with the PHY's recovered clock.
Disabling CRC checking confirmed the data was genuinely corrupted (not a
CRC checker bug) — upper-layer checksums (IP/ICMP) caught the same errors.

The correct approach uses `e_rxc` directly as the capture clock, with the input
registers in the I/O element (`FAST_INPUT_REGISTER`). Sampling on the **rising
edge** gave the best result (~2.5% ICMP loss); falling edge was worse (16%).

**The remaining ~2.5 % was not timing margin, and it was not harmless.** It was
put down to the column I/O clock routing at the time. Item 68 (2026-10-12)
then found the receive domain had gone untimed in the generated project; with
that fixed, the EP4CGX150 still lost 3-5 % of received frames. Item 166 traced
it to the board: `e_rxc` on PIN_B10 picks up extra edges when six or more RXD
lines rise together, so the loss depends on frame content, and the router's
DHCP offer was among the worst cases — DHCP could not bind. B10 is a column I/O
pin that cannot reach the global clock network, and no PLL can take it either
(Quartus error 176554), so the clock cannot be cleaned up inside the FPGA. That
is why the EP4CGX150 presets run MII.

## Configuration

The Ethernet device is part of the preset (`JopConfig.scala`):

```scala
// ep4cgx150DbFull -- the DB_FPGA V4, MII at 100 Mb/s
"eth" -> DeviceInstance(DeviceType.Ethernet,
  params = Map("phyDataWidth" -> 4),
  devicePart = Some("RTL8211EG"))

// ep4cgx150DbV5Eth -- the V5, GMII at 1 Gb/s, kept to reproduce item 166
"eth" -> devs("eth").copy(params = Map("gmii" -> true, "phyDataWidth" -> 8, "rxCapture" -> true))
```

`rxCapture` adds the MAC's receive-stream capture, the logic analyser that
found the extra RX_CLK edges (`Mac.scala`; read out by the `EthCapDiag` app).

Key files, under `spinalhdl/src/main/scala/jop/`:
- `config/JopConfig.scala` — the presets above
- `config/Board.scala` — the DB_FPGA's J3 pins (`QmtechFpgaDbV4`, `QmtechFpgaDbV5`)
- `io/DeviceTopWiring.scala` — the PHY clock domains, the 125 MHz PLL for GMII, the TX/RX adapters
- `io/Eth.scala` — the device: `Mac` (TX/RX buffers around SpinalHDL's `MacEth`) plus `Mdio`
- `generate/TimingConstraints.scala`, `generate/QsfGenerator.scala`, `generate/XdcGenerator.scala` — the constraints

## Constraints

Generated from the preset into `build/<config>/quartus/`; there is no
hand-written constraint file any more. `ep4cgx150DbV5Eth` (GMII) gets:

```sdc
create_clock -period 20.000 -name clk_in [get_ports clk_in]
create_clock -period 8.000 -name e_rxc [get_ports e_rxc]
derive_pll_clocks
derive_clock_uncertainty
set_clock_groups -asynchronous \
    -group {dramPll|altpll_component|auto_generated|pll1|clk[1] dramPll|altpll_component|auto_generated|pll1|clk[2] dramPll|altpll_component|auto_generated|pll1|clk[3]} \
    -group {ethPll|altpll_component|auto_generated|pll1|clk[0]} \
    -group {e_rxc}
```

plus `FAST_INPUT_REGISTER ON` for every receive pin in the `.qsf`, so each is
captured in its I/O element. An MII design (`ep4cgx150DbFull`) creates `e_txc`
as well, in a group of its own, and names no Ethernet PLL; both PHY clocks are
created at 8 ns, tighter than MII's 40 ns needs.

**Every clock a group names must be created.** Quartus drops a group that names
a clock nobody created, with only a warning (332174) — which is how the GMII
receive domain went untimed until 2026-10-12 (item 68).
`GeneratedConstraintsTest` now requires every grouped clock to be created or
derived, and `GmiiRxCaptureTest` that every receive pin feeds one register.

## Historical Issues

### RX_CLK picks up extra edges at 1 Gb/s (worked around 2026-10-12, status item 166)

With item 68 fixed, 3-5 % of received frames still failed, depending on their
content. A capture of the MAC's receive stream (`rxCapture`) showed a byte
arriving twice whenever six or more RXD lines rose together: extra edges on
`e_rxc`. The loss followed the EP4CGX150 core onto the DB_FPGA V5, where the
XC7A100T lost 1 frame in 2,162. Both EP4CGX150 presets moved to MII at
100 Mb/s: every payload received, DHCP and TCP working.

### The receive domain went untimed, and I/O reads came back one word late (fixed 2026-10-12, status item 68)

The link came up at 1 Gb/s and no packets moved. Two causes, and the EP4CGX150
had both. `BmbMemoryController` re-latched every I/O read after its strobe, so
the MAC's pop-on-read RX register reached the CPU one word late — on every
board. And the generated SDC named `e_rxc` in a clock group without creating
it: Quartus dropped the group with only a warning, the receive domain went
untimed, and that placement failed hold by 1.763 ns inside the MAC. Frames
arrived with words out of order behind a CRC check that passed. The same
missing `create_clock` as the 2026-02-25 entry below, this time in generated
constraints.

### Missing `create_clock` for PHY clocks (fixed 2026-02-25)

The original 100Mbps MII SDC had `set_false_path` between e_rxc/e_txc and the
PLL clock, but never declared `create_clock` for e_rxc or e_txc. Without
`create_clock`, Quartus did not recognise these signals as clocks, so the
entire PHY clock domains were **unconstrained**. This caused systematic data
corruption on Ethernet RX: every other 32-bit word had its lower 16 bits zeroed.

The SDC also referenced stale PLL clock names
(`pll|altpll_component|auto_generated|pll1|clk[N]`) instead of the actual
names used by Quartus 25.1 (`pll|altpll_component|pll|clk[N]`).

### Mesochronous PLL RX clocking causing 14-34% packet loss (fixed 2026-02-28)

The initial GMII implementation used a mesochronous approach: an FPGA PLL
generated a 125 MHz clock (same frequency as the PHY's `e_rxc`) to capture
RX data. This worked poorly because `e_rxc` is a CDR-recovered clock with
no phase relationship to the FPGA's oscillator. The random phase offset
caused intermittent setup/hold violations on RX data capture, manifesting
as CRC-32 errors and 14-34% packet loss depending on PLL phase shift.

Various PLL phase offsets were tested (0°, 90°, 180°) with 90° being best
at ~14% loss. Disabling CRC checking confirmed data was genuinely corrupted
(upper-layer IP/ICMP checksums caught the same errors).

The fix was to use `e_rxc` directly as the capture clock (source-synchronous)
with `FAST_INPUT_REGISTER ON` constraints to place input registers in the
I/O block. This provides proper setup/hold margins because `e_rxc` and
`e_rxd` have a guaranteed timing relationship from the PHY.

### e_rxd pin swap causing 100% CRC failure (fixed 2026-02-25)

During the MII-to-GMII upgrade, `e_rxd[5]` and `e_rxd[6]` pin assignments
in `jop_dbfpga.qsf` were **swapped** compared to the reference design:

| Signal | Reference (correct) | JOP (incorrect) |
|---|---|---|
| e_rxd[5] | PIN_B11 | PIN_A12 |
| e_rxd[6] | PIN_A12 | PIN_B11 |

This caused 100% CRC failure on all received frames at 1Gbps. Swapping bits
5 and 6 in the data stream corrupts every byte, making CRC-32 verification
impossible. The fix was to match the reference design pin assignments exactly.

**Lesson**: Always cross-verify FPGA pin assignments against the reference
design pin-by-pin, especially for multi-bit buses where transposition errors
are easy to introduce and hard to diagnose.
