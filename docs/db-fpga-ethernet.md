# DB_FPGA Daughter Board Pin Mapping for EP4CGX150

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

- EP4CE15 reference: `/srv/git/qmtech/CYCLONE_IV_EP4CE15/Software/Project09_GMII_Ethernet/ethernet_test.qsf`
- EP4CE15 core board manual: `QMTECH_CycloneIV_EP4CE15_User_Manual(CoreBoard)-V02.pdf` (U8 header table)
- EP4CGX150 core board manual: `QMTECH_CycloneIV_EP4CGX150GX_User_Manual(CoreBoard)-V01.pdf` (U4 header table)
- DB_FPGA schematic: `QMTECH_DB_For_FPGA_V04.pdf`

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

- PHY Address: 001 (address 1)
- AN[1:0] = 11 (auto-negotiation, advertise all)
- RXDLY: 2ns, TXDLY: 2ns
- SELRGV: 1 (3.3V I/O)
- COL/Mode: 0 (GMII interface)

## SDC Timing Constraints

The Ethernet MAC uses three clock domains:

| Clock | Source | Frequency | Domain |
|-------|--------|-----------|--------|
| c1 (pll\|clk[1]) | PLL | 80 MHz | System: CPU, memory, I/O read MUX |
| e_rxc | PHY pin | 25 MHz | RX: MacRxBuffer push side, nibble packing |
| e_txc | PHY pin | 25 MHz | TX: MacTxBuffer pop side, MII output |

The dual-port RAMs in MacRxBuffer and MacTxBuffer cross between PHY
clock domains (e_rxc/e_txc) and the system clock (c1).

### Bug: missing create_clock (fixed 2026-02-25)

The original SDC had `set_false_path` between e_rxc/e_txc and the PLL
clock, but never declared `create_clock` for e_rxc or e_txc.  Without
`create_clock`, Quartus did not recognise these signals as clocks at
all, so:

1. The `set_false_path` constraints were silently ignored (no matching
   clock objects).
2. The entire e_rxc and e_txc clock domains were **unconstrained** --
   Quartus placed and routed them with no timing requirements.
3. This caused systematic data corruption on Ethernet RX: every other
   32-bit word read from the MacRxBuffer had its lower 16 bits zeroed.

The same SDC also referenced stale PLL clock names
(`pll|altpll_component|auto_generated|pll1|clk[N]`) instead of the
actual names used by Quartus 25.1 (`pll|altpll_component|pll|clk[N]`).
This affected both the VGA false-path constraints and the Ethernet
constraints -- all were silently ignored.

### Fix

```sdc
create_clock -name "e_rxc" -period 40.000 [get_ports {e_rxc}]
create_clock -name "e_txc" -period 40.000 [get_ports {e_txc}]

set_clock_groups -asynchronous \
    -group {e_rxc} \
    -group {e_txc} \
    -group {pll|altpll_component|pll|clk[1] \
            pll|altpll_component|pll|clk[2]}
```

After the fix, all timing passes with positive slack across all three
timing models (slow 100C, slow -40C, fast -40C).
