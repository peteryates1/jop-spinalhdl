# DB_FPGA Daughter Board — Ethernet (1Gbps GMII)

Ethernet support for the QMTECH DB_FPGA daughter board with RTL8211EG PHY,
running at **1Gbps over GMII** (8-bit, 125 MHz).

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

## GMII Architecture

The Ethernet subsystem uses three asynchronous clock domains:

| Clock | Source | Frequency | Domain |
|-------|--------|-----------|--------|
| c1 (pll\|clk[1]) | DRAM PLL | 80 MHz | System: CPU, memory, I/O read MUX |
| ethPll clk[0] | Ethernet PLL (pll_125.v) | 125 MHz | TX: MacTxBuffer pop side, GMII output, drives `e_gtxc` |
| e_rxc | PHY pin | 125 MHz | RX: MacRxBuffer push side, byte packing |

In GMII mode (`IoConfig.ethGmii = true`):

- **TX clock**: FPGA generates 125 MHz via `pll_125.v` (50 MHz × 5/2),
  output to PHY on `e_gtxc`. The `e_txc` pin is unused.
- **RX clock**: PHY provides 125 MHz on `e_rxc` (source-synchronous with
  RX data). FPGA captures RX data using I/O block registers
  (`FAST_INPUT_REGISTER ON` constraint) clocked by `e_rxc`.
- **Data width**: 8 bits (vs 4 bits in MII mode). `PhyParameter(txDataWidth=8, rxDataWidth=8)`.
- PHY auto-negotiates 1000BASE-T FD via MDIO registers 0, 4, 9.

The dual-port RAMs in MacRxBuffer and MacTxBuffer cross between PHY
clock domains (e_rxc / ethPll) and the system clock (c1).

### RX Clocking: Source-Synchronous vs Mesochronous

The PHY's `e_rxc` is a clock-data-recovery (CDR) recovered clock — it is
**not** phase-locked to the FPGA's reference oscillator. This makes
mesochronous clocking (using a PLL-generated 125 MHz) unreliable for
data capture.

Tested approaches and results (100-packet ping tests at 1Gbps):

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

The correct approach uses `e_rxc` directly as the capture clock with
`FAST_INPUT_REGISTER ON` constraints in the `.qsf` file to place the
input registers in the I/O block. Sampling on the **rising edge**
provides the best results (~2.5% ICMP loss); falling edge was worse (16%).

The remaining ~2.5% loss at 1Gbps is from residual timing margin issues
on the Column I/O clock routing. This is easily handled by TCP
retransmission and is acceptable for embedded networking.

Note: `e_rxc` is on PIN_B10, a Column I/O pin that cannot reach the
global clock network. This is fine for source-synchronous capture —
the I/O block registers only need the bank-local clock routing.

### SpinalHDL Configuration

```scala
IoConfig.qmtechDbFpga  // sets hasEth=true, ethGmii=true
// IoConfig.phyDataWidth returns 8 for GMII, 4 for MII
```

Key files:
- `IoConfig.scala` — `ethGmii` flag, `phyDataWidth` helper
- `JopSdramTop.scala` — `EthPll` BlackBox, GMII clock domains, TX/RX adapters
- `JopCore.scala` / `JopCluster.scala` — parameterized `PhyParameter` from `ioConfig.phyDataWidth`
- `BmbEth.scala` — already parameterized, no GMII-specific changes needed

## SDC Timing Constraints

```sdc
# 50 MHz input clock
create_clock -period 20.000 -name clk_in [get_ports clk_in]

derive_pll_clocks
derive_clock_uncertainty

# PLL 125 MHz for GMII TX is auto-derived by derive_pll_clocks

# Ethernet PHY RX clock (125 MHz GMII, source-synchronous with RX data)
create_clock -period 8.000 -name e_rxc [get_ports e_rxc]

# All clock domains are asynchronous to each other
set_clock_groups -asynchronous \
    -group {pll|altpll_component|auto_generated|pll1|clk[1] \
            pll|altpll_component|auto_generated|pll1|clk[2] \
            pll|altpll_component|auto_generated|pll1|clk[3]} \
    -group {ethPll|altpll_component|auto_generated|pll1|clk[0]} \
    -group {e_rxc}

# PHY RX data: source-synchronous with e_rxc, captured by I/O block registers
set_false_path -from [get_ports {e_rxd[*] e_rxdv e_rxer}]

# GMII TX: clock and data from same PLL output
set_false_path -to [get_ports {e_gtxc}]
set_false_path -to [get_ports {e_txd[*] e_txen e_txer}]
```

## Historical Issues

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
