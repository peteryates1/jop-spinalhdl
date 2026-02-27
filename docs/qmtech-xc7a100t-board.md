# QMTECH XC7A75T/100T/200T Core Board

## Overview

QMTECH Artix-7 core board with onboard DDR3 SDRAM. Available with three FPGA
variants (XC7A75T, XC7A100T, XC7A200T) sharing identical PCB and pin assignments.
Connects to the [DB_FPGA daughter board](qmtech-db-fpga.md) via dual 32x2 pin
headers (J2, J3) for Ethernet, UART, VGA, and SD card.

Reference files: `/srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/`

## FPGA

The XC7A100T variant (primary target):

- **Device**: Xilinx Artix-7 — XC7A100T-FGG676-2
- **Logic Cells**: 101,440
- **LUTs**: 63,400
- **Flip-flops**: 126,800
- **Block RAM**: 4,860 Kbit (135 x 36 Kbit BRAM)
- **DSP slices**: 240
- **Package**: FGG676 (676-pin BGA)
- **Speed grade**: -2
- **Transceivers**: 8 GTP (6.6 Gbps)

Other variants (same PCB, different FPGA):

| Variant | Logic Cells | BRAM | DSP |
|---------|:-----------:|:----:|:---:|
| XC7A75T | 75,520 | 3,780 Kbit | 180 |
| XC7A100T | 101,440 | 4,860 Kbit | 240 |
| XC7A200T | 215,360 | 13,140 Kbit | 740 |

## Clock

- **Oscillator**: 50 MHz (PIN U22, LVCMOS33)
- DDR3 MIG generates internal clocks from 50 MHz reference (333 MHz DDR3 clock)
- JOP system clock: separate PLL from 50 MHz input (target 100 MHz)

## DDR3 SDRAM

**Component**: Micron MT41K128M16XX-15E — DDR3L, 2 Gbit (256 MB), 16-bit data bus.

| Parameter | Value |
|-----------|-------|
| Capacity | 2 Gbit (256 MB) |
| Data width | 16-bit |
| Row address | 14-bit |
| Column address | 10-bit |
| Banks | 8 (3-bit bank address) |
| Speed | DDR3L-1333 (667 MHz data rate) |
| Voltage | 1.35V (SSTL135) |
| Burst length | 8 (fixed for DDR3) |

This is the same DDR3 chip family (MT41K128M16) as the Alchitry Au V2. The
existing JOP DDR3 subsystem (MIG + BmbCacheBridge + LruCacheCore + CacheToMigAdapter)
should work with only pin reassignment and MIG regeneration for the new board.

### DDR3 Pin Assignments

From `/srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/Software_XC7A100T/DDR3.ucf`:

**Address bus [13:0]:**

| Signal | Pin |
|--------|-----|
| `ddr3_addr[0]` | E17 |
| `ddr3_addr[1]` | G17 |
| `ddr3_addr[2]` | F17 |
| `ddr3_addr[3]` | C17 |
| `ddr3_addr[4]` | G16 |
| `ddr3_addr[5]` | D16 |
| `ddr3_addr[6]` | H16 |
| `ddr3_addr[7]` | E16 |
| `ddr3_addr[8]` | H14 |
| `ddr3_addr[9]` | F15 |
| `ddr3_addr[10]` | F20 |
| `ddr3_addr[11]` | H15 |
| `ddr3_addr[12]` | C18 |
| `ddr3_addr[13]` | G15 |

**Bank address [2:0]:**

| Signal | Pin |
|--------|-----|
| `ddr3_ba[0]` | B17 |
| `ddr3_ba[1]` | D18 |
| `ddr3_ba[2]` | A17 |

**Control signals:**

| Signal | Pin | I/O Standard |
|--------|-----|-------------|
| `ddr3_ras_n` | A19 | SSTL135 |
| `ddr3_cas_n` | B19 | SSTL135 |
| `ddr3_we_n` | A18 | SSTL135 |
| `ddr3_cke` | E18 | SSTL135 |
| `ddr3_odt` | G19 | SSTL135 |
| `ddr3_reset_n` | H17 | LVCMOS15 |

**Clock (differential):**

| Signal | Pin |
|--------|-----|
| `ddr3_ck_p` | F18 |
| `ddr3_ck_n` | F19 |

**Data bus [15:0]:**

| Signal | Pin |
|--------|-----|
| `ddr3_dq[0]` | D21 |
| `ddr3_dq[1]` | C21 |
| `ddr3_dq[2]` | B22 |
| `ddr3_dq[3]` | B21 |
| `ddr3_dq[4]` | D19 |
| `ddr3_dq[5]` | E20 |
| `ddr3_dq[6]` | C19 |
| `ddr3_dq[7]` | D20 |
| `ddr3_dq[8]` | C23 |
| `ddr3_dq[9]` | D23 |
| `ddr3_dq[10]` | B24 |
| `ddr3_dq[11]` | B25 |
| `ddr3_dq[12]` | C24 |
| `ddr3_dq[13]` | C26 |
| `ddr3_dq[14]` | A25 |
| `ddr3_dq[15]` | B26 |

**Data mask and strobe:**

| Signal | Pin |
|--------|-----|
| `ddr3_dm[0]` | A22 |
| `ddr3_dm[1]` | C22 |
| `ddr3_dqs_p[0]` | B20 |
| `ddr3_dqs_n[0]` | A20 |
| `ddr3_dqs_p[1]` | A23 |
| `ddr3_dqs_n[1]` | A24 |

### System Pins

| Signal | Pin | I/O Standard | Function |
|--------|-----|-------------|----------|
| `sys_clk` | U22 | LVCMOS33 | 50 MHz oscillator |
| `sys_rst_n` | P4 | LVCMOS33 | Reset button (active low) |
| `led[0]` | T23 | LVCMOS33 | Core board LED 0 |
| `led[1]` | R23 | LVCMOS33 | Core board LED 1 |

### On-Board UART (CH340N)

| Signal | Pin | Direction |
|--------|-----|-----------|
| `uart_rx` | F3 | CH340N → FPGA |
| `uart_tx` | E3 | FPGA → CH340N |

The core board has a CH340N USB-to-UART bridge connected to a Mini USB
connector. When used with the DB_FPGA daughter board, the UART on the
daughter board (CP2102N) is on different FPGA pins via the J2/J3 connector.

### J2/J3 Connector Mapping

The J2 and J3 headers are 32x2 pin (64 pins each). All three variants
(XC7A75T, XC7A100T, XC7A200T) use the same FGG676 package and identical
pin assignments.

From schematic `QMTECH_XC7A75T_100T_200T-CORE-BOARD-V01-20210109.pdf`:

**J2** (Banks 13, 14, 15 — active-high, LVCMOS33):

| Pin | FPGA | Pin | FPGA | Pin | FPGA | Pin | FPGA |
|:---:|:----:|:---:|:----:|:---:|:----:|:---:|:----:|
| 1 | GND | 2 | GND | 3 | 3V3 | 4 | 3V3 |
| 5 | D26 | 6 | E26 | 7 | D25 | 8 | E25 |
| 9 | G26 | 10 | H26 | 11 | E23 | 12 | F23 |
| 13 | F22 | 14 | G22 | 15 | J26 | 16 | J25 |
| 17 | G21 | 18 | G20 | 19 | H22 | 20 | H21 |
| 21 | J21 | 22 | K21 | 23 | K26 | 24 | K25 |
| 25 | K23 | 26 | K22 | 27 | M26 | 28 | N26 |
| 29 | L23 | 30 | L22 | 31 | P26 | 32 | R26 |
| 33 | M25 | 34 | M24 | 35 | N22 | 36 | N21 |
| 37 | P24 | 38 | P23 | 39 | P25 | 40 | R25 |
| 41 | T25 | 42 | T24 | 43 | V21 | 44 | U21 |
| 45 | W23 | 46 | V23 | 47 | Y23 | 48 | Y22 |
| 49 | AA25 | 50 | Y25 | 51 | AC24 | 52 | AB24 |
| 53 | Y21 | 54 | W21 | 55 | Y26 | 56 | W25 |
| 57 | AC26 | 58 | AB26 | 59 | NC | 60 | NC |
| 61 | NC | 62 | NC | 63 | VIN | 64 | VIN |

**J3** (Banks 34, 35 — active-high, LVCMOS33):

| Pin | FPGA | Pin | FPGA | Pin | FPGA | Pin | FPGA |
|:---:|:----:|:---:|:----:|:---:|:----:|:---:|:----:|
| 1 | GND | 2 | GND | 3 | VCCO | 4 | VCCO |
| 5 | B5 | 6 | A5 | 7 | B4 | 8 | A4 |
| 9 | A3 | 10 | A2 | 11 | D4 | 12 | C4 |
| 13 | C2 | 14 | B2 | 15 | E5 | 16 | D5 |
| 17 | C1 | 18 | B1 | 19 | E1 | 20 | D1 |
| 21 | F2 | 22 | E2 | 23 | G4 | 24 | F4 |
| 25 | G2 | 26 | G1 | 27 | J4 | 28 | H4 |
| 29 | H2 | 30 | H1 | 31 | H9 | 32 | G9 |
| 33 | M2 | 34 | L2 | 35 | L5 | 36 | K5 |
| 37 | M4 | 38 | L4 | 39 | N3 | 40 | N2 |
| 41 | M6 | 42 | M5 | 43 | K1 | 44 | J1 |
| 45 | R3 | 46 | P3 | 47 | T4 | 48 | T3 |
| 49 | P6 | 50 | P5 | 51 | N1 | 52 | M1 |
| 53 | R1 | 54 | P1 | 55 | T2 | 56 | R2 |
| 57 | U2 | 58 | U1 | 59 | NC | 60 | NC |
| 61 | NC | 62 | NC | 63 | VIN | 64 | VIN |

27 I/O pairs per header (54 I/O pins each, 108 total). Pin 1-2 = ground,
pin 3-4 = bank power (3V3 for J2, VCCO_34_35 for J3), pin 59-62 = NC,
pin 63-64 = VIN (unregulated input power).

### DB_FPGA Peripheral Pin Assignments (XC7A100T)

Derived by mapping the [EP4CGX150 cross-reference](qmtech-ep4cgx150-board.md#dbfpga-peripheral-to-connector-cross-reference)
connector pin numbers to XC7A100T FPGA pins via the tables above. The DB_FPGA
connector pinout is identical across all QMTECH core boards.

**UART (CP2102N):**

| Signal | Connector | FPGA Pin |
|--------|:---------:|:--------:|
| TX (FPGA→CP2102N) | J3-13 | C2 |
| RX (CP2102N→FPGA) | J3-14 | B2 |

**SD Card:**

| Signal | Connector | FPGA Pin |
|--------|:---------:|:--------:|
| SD_CLK | J2-9 | G26 |
| SD_CMD | J2-10 | H26 |
| SD_DAT0 | J2-8 | E25 |
| SD_DAT1 | J2-7 | D25 |
| SD_DAT2 | J2-12 | F23 |
| SD_DAT3/CS | J2-11 | E23 |
| SD_CD | J2-6 | E26 |

**Ethernet (RTL8211EG, GMII):**

| Signal | Connector | FPGA Pin |
|--------|:---------:|:--------:|
| MDC | J2-14 | G22 |
| MDIO | J2-13 | F22 |
| RESET_N | J2-24 | K25 |
| RXC | J2-35 | N22 |
| RXDV | J2-40 | R25 |
| RXD[0] | J2-39 | P25 |
| RXD[1] | J2-38 | P23 |
| RXD[2] | J2-37 | P24 |
| RXD[3] | J2-36 | N21 |
| RXD[4] | J2-34 | M24 |
| RXD[5] | J2-33 | M25 |
| RXD[6] | J2-32 | R26 |
| RXD[7] | J2-31 | P26 |
| RXER | J2-30 | L22 |
| GTXC | J2-27 | M26 |
| TXEN | J2-26 | K22 |
| TXER | J2-15 | J26 |
| TXD[0] | J2-25 | K23 |
| TXD[1] | J2-23 | K26 |
| TXD[2] | J2-22 | K21 |
| TXD[3] | J2-21 | J21 |
| TXD[4] | J2-19 | H22 |
| TXD[5] | J2-18 | G20 |
| TXD[6] | J2-17 | G21 |
| TXD[7] | J2-16 | J25 |

**VGA (RGB 5-6-5):**

| Signal | Connector | FPGA Pin |
|--------|:---------:|:--------:|
| HS | J2-42 | T24 |
| VS | J2-41 | T25 |
| R[4] | J2-55 | Y26 |
| R[3] | J2-54 | W21 |
| R[2] | J2-57 | AC26 |
| R[1] | J2-56 | W25 |
| R[0] | J2-58 | AB26 |
| G[5] | J2-49 | AA25 |
| G[4] | J2-48 | Y22 |
| G[3] | J2-51 | AC24 |
| G[2] | J2-50 | Y25 |
| G[1] | J2-52 | AB24 |
| G[0] | J2-53 | Y21 |
| B[4] | J2-44 | U21 |
| B[3] | J2-43 | V21 |
| B[2] | J2-46 | V23 |
| B[1] | J2-45 | W23 |
| B[0] | J2-47 | Y23 |

## JOP DDR3 Compatibility

This board uses the same MT41K128M16 DDR3 chip as the Alchitry Au V2 (the
existing JOP DDR3 platform). Porting JOP requires:

1. **MIG regeneration**: New Vivado MIG IP targeting XC7A100T-FGG676-2 with
   the pin assignments above. The MIG local interface (28-bit address, 128-bit
   data) will be identical.

2. **Pin constraint file**: New XDC with DDR3 pins, system clock, UART (from
   DB_FPGA), and LEDs.

3. **JopDdr3Top adaptation**: New top-level (or parameterized variant) for
   the XC7A100T with its PLL configuration. The DDR3 subsystem
   (BmbCacheBridge → LruCacheCore → CacheToMigAdapter → MIG) is unchanged.

4. **DB_FPGA peripherals**: Ethernet, UART, VGA, SD card via daughter board.
   Pin assignments will differ from EP4CGX150 — need to map J2/J3 connector
   pins to XC7A100T FPGA pins using the core board schematic.

### JOP Configuration (estimated)

```scala
JopCoreConfig(
  memConfig = JopMemoryConfig(
    addressWidth = 28,                    // 26-bit physical word + 2 type bits = 256 MB
    mainMemSize = 256L * 1024 * 1024,     // 256 MB DDR3
    burstLen = 8,                         // DDR3 burst
    stackRegionWordsPerCore = 8192        // 32 KB per core
  ),
  clkFreqHz = 100000000L,
  ioConfig = IoConfig(hasEth = true, ethGmii = true, hasVga = true, hasSdNative = true)
)
```

### Address Flow

Same as Alchitry Au V2:

```
JOP pipeline → BmbMemoryController → BMB bus → BmbCacheBridge → LruCacheCore → CacheToMigAdapter → MIG
  28-bit word     (aoutAddr<<2).resized   30-bit byte   addr(27:0)→28-bit   28-bit cache    28-bit MIG
  [27:26]=type                            [29:28]=00    strips type bits     line addr       app_addr
```

## FPGA Resource Budget

XC7A100T vs other JOP platforms:

| Resource | XC7A100T | XC7A35T (Au V2) | EP4CGX150 |
|----------|:--------:|:---------------:|:---------:|
| LUTs / LEs | 63,400 | 20,800 | 149,760 |
| Block RAM | 4,860 Kbit | 1,800 Kbit | 6,635 Kbit |
| DSP / Mult | 240 | 90 | 360 |

Estimated JOP capacity on XC7A100T:

| Config | LUTs (est.) | % of XC7A100T |
|--------|:-----------:|:-------------:|
| 1-core + DDR3 cache | ~4,000 | 6% |
| 4-core SMP | ~18,000 | 28% |
| 8-core SMP | ~36,000 | 57% |
| 12-core SMP | ~54,000 | 85% |

The XC7A100T has 3x the logic of the Alchitry Au V2's XC7A35T, making 8-core
SMP feasible. With the XC7A200T variant (215K logic cells), 16+ cores would be
possible with DDR3 bandwidth.

## Example Projects

In `/srv/git/qmtech/QMTECH_XC7A75T-100T-200T_Core_Board/XC7A100T/Software_XC7A100T/`:

| Project | Description |
|---------|-------------|
| Test01_led_key | LED blink + button test (50 MHz reference) |
| Test04_DDR3_mig_7series | Xilinx MIG DDR3 controller + traffic generator |

For DB_FPGA peripheral examples, see `/srv/git/qmtech/CYCLONE_IV_EP4CE15/Software/`
(same daughter board, different FPGA pin assignments).

## Daughter Board

Connects to [QMTECH DB_FPGA daughter board](qmtech-db-fpga.md) via J2/J3 headers.
The J2/J3 connector mapping above provides the FPGA pin for each connector pin.
To determine DB_FPGA peripheral pin assignments for this core board, cross-reference
the DB_FPGA connector pinout with the tables above.

For reference, the EP4CGX150 core board's [cross-reference table](qmtech-ep4cgx150-board.md#dbfpga-peripheral-to-connector-cross-reference)
shows which DB_FPGA connector pins correspond to which peripherals — the connector
pin numbers are the same across all QMTECH core boards, only the FPGA pin names differ.
