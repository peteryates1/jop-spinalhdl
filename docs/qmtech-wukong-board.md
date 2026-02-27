# QMTECH XC7A100T Wukong Board V3

## Overview

Self-contained Artix-7 development board with DDR3, SDR SDRAM, Gigabit Ethernet,
HDMI, USB UART, and SD card all built-in. **Does not require the DB_FPGA daughter
board** — all peripherals are on the main PCB.

Available with XC7A100T or XC7A200T FPGA variants on the same PCB.

Reference files: `/srv/git/qmtech/QM_XC7A100T_WUKONG_BOARD/V3/`

Schematic: `V3/Hardware/QMTECH-XC7A100T_200T-Wukong-Board-V03-20240121.pdf`

## FPGA

- **Device**: Xilinx Artix-7 — XC7A100T-FGG676 (or XC7A200T variant)
- **Logic Cells**: 101,440 (XC7A100T) / 215,360 (XC7A200T)
- **LUTs**: 63,400 / 134,600
- **Block RAM**: 4,860 Kbit / 13,140 Kbit
- **DSP slices**: 240 / 740
- **Package**: FGG676 (676-pin BGA)
- **Speed grade**: -2
- **GTP Transceivers**: 8 (6.6 Gbps)

## Clock

- **Oscillator**: 50 MHz, SG-8002JC (PIN U22, LVCMOS33)
- DDR3 MIG generates 333 MHz DDR3 clock from 50 MHz reference
- JOP system clock: separate PLL from 50 MHz input

## Peripherals

All peripherals are built into the main board:

| Peripheral | Component | Interface | Notes |
|------------|-----------|-----------|-------|
| **DDR3** | MT41K128M16JT-125 | DDR3L x16, 256 MB | Primary memory (1.35V SSTL135) |
| **SDR SDRAM** | W9825G6KH-6 | SDR x16, 32 MB | Secondary memory (3.3V LVCMOS) |
| **Ethernet** | RTL8211EG PHY | GMII (1 Gbps) | 25 MHz PHY reference crystal |
| **HDMI** | TPD12S016 buffer | DVI-D output | With DDC I2C + CEC |
| **UART** | CH340N USB-to-UART | TX/RX + USB mini-B | |
| **SD card** | microSD slot | 4-bit / SPI | With card detect |
| **Flash** | N25Q064A | Quad-SPI, 64 Mbit (8 MB) | FPGA configuration flash |
| **GPIO** | 2 LEDs, 2 buttons | LVCMOS33 | |
| **PMOD** | J10, J11, J13, J14 | 12-pin GPIO each | General purpose expansion |

## DDR3 SDRAM

**Component**: Micron MT41K128M16JT-125 — DDR3L, 2 Gbit (256 MB), 16-bit.

Same DDR3 chip as the Alchitry Au V2 and the QMTECH XC7A100T Core Board.
The existing JOP DDR3 subsystem should work with MIG regeneration and pin
reassignment.

| Parameter | Value |
|-----------|-------|
| Capacity | 2 Gbit (256 MB) |
| Data width | 16-bit |
| Address | 14-bit row, 10-bit column, 3-bit bank |
| Speed | DDR3L-1333 (667 MHz data rate) |
| Voltage | 1.35V |
| I/O standard | SSTL135 |

### DDR3 Pin Assignments

From `DDR3.ucf`:

| Signal | Pins |
|--------|------|
| `ddr3_addr[13:0]` | E17, G17, F17, C17, G16, D16, H16, E16, H14, F15, F20, H15, C18, G15 |
| `ddr3_ba[2:0]` | B17, D18, A17 |
| `ddr3_ras_n` | A19 |
| `ddr3_cas_n` | B19 |
| `ddr3_we_n` | A18 |
| `ddr3_cke` | E18 |
| `ddr3_odt` | G19 |
| `ddr3_reset_n` | H17 |
| `ddr3_ck_p / ck_n` | F18 / F19 |
| `ddr3_dq[15:0]` | D21..D20, C23..B26 |
| `ddr3_dqs_p[1:0]` | B20, A23 |
| `ddr3_dqs_n[1:0]` | A20, A24 |
| `ddr3_dm[1:0]` | A22, C22 |

## SDR SDRAM

**Component**: Winbond W9825G6KH-6 — 256 Mbit (32 MB), 16-bit data bus.

Same chip family as the QMTECH EP4CGX150 core board's W9825G6JH6. The existing
JOP SDRAM path (BmbSdramCtrl32 with 32→16 bridge) would work here, though on
Xilinx the Altera tri-state controller BlackBox would need to be replaced with
SpinalHDL's SdramCtrl (or an equivalent Xilinx controller).

| Parameter | Value |
|-----------|-------|
| Capacity | 256 Mbit (32 MB) |
| Data width | 16-bit |
| Row address | 13-bit |
| Column address | 9-bit |
| Banks | 4 (2-bit) |
| CAS latency | 2 or 3 |
| Max frequency | 166 MHz |
| I/O standard | 3.3V LVCMOS |

### SDR SDRAM Pin Assignments

From `Test10_SDRAM` project XDC:

**Control signals:**

| Signal | Pin |
|--------|-----|
| `SDCLK0` | G22 |
| `SDCKE0` | H22 |
| `SDCS0` | L25 |
| `RAS` | K26 |
| `CAS` | K25 |
| `SDWE` | J26 |

**Bank address [1:0]:**

| Signal | Pin |
|--------|-----|
| `Bank[0]` | M25 |
| `Bank[1]` | M26 |

**Data mask [1:0]:**

| Signal | Pin |
|--------|-----|
| `DQM[0]` | J25 |
| `DQM[1]` | K23 |

**Address bus [12:0]:**

| Signal | Pin |
|--------|-----|
| `Address[0]` | R26 |
| `Address[1]` | P25 |
| `Address[2]` | P26 |
| `Address[3]` | N26 |
| `Address[4]` | M24 |
| `Address[5]` | M22 |
| `Address[6]` | L24 |
| `Address[7]` | L23 |
| `Address[8]` | L22 |
| `Address[9]` | K21 |
| `Address[10]` | R25 |
| `Address[11]` | K22 |
| `Address[12]` | J21 |

**Data bus [15:0]:**

| Signal | Pin |
|--------|-----|
| `Data[0]` | D25 |
| `Data[1]` | D26 |
| `Data[2]` | E25 |
| `Data[3]` | E26 |
| `Data[4]` | F25 |
| `Data[5]` | G25 |
| `Data[6]` | G26 |
| `Data[7]` | H26 |
| `Data[8]` | J24 |
| `Data[9]` | J23 |
| `Data[10]` | H24 |
| `Data[11]` | H23 |
| `Data[12]` | G24 |
| `Data[13]` | F24 |
| `Data[14]` | F23 |
| `Data[15]` | E23 |

## JOP Porting Considerations

The Wukong board is interesting for JOP because it offers **two memory paths**:

1. **DDR3 path** (256 MB): Use the existing MIG-based DDR3 subsystem from the
   Alchitry Au V2. Same chip, same interface width. Requires MIG regeneration
   for XC7A100T pin assignments. Full 256 MB addressable with `addressWidth=28`.

2. **SDR SDRAM path** (32 MB): Use the existing BmbSdramCtrl32 SDRAM subsystem.
   Same W9825G6 chip as the EP4CGX150. Requires replacing the Altera controller
   BlackBox with SpinalHDL's `SdramCtrl` (or `SdramCtrlNoCke`). This is the
   simpler path — no MIG IP needed, pure SpinalHDL.

The SDR SDRAM path would be a useful first step: get JOP running with familiar
SDRAM infrastructure, verify Ethernet/UART work, then upgrade to DDR3 for the
larger memory and bandwidth.

### Built-In Peripherals vs DB_FPGA

The Wukong board has all peripherals on-board, eliminating the DB_FPGA:

| Feature | Wukong V3 | EP4CGX150 + DB_FPGA |
|---------|:---------:|:-------------------:|
| Ethernet | RTL8211EG (same PHY) | RTL8211EG (same PHY) |
| Display | HDMI (DVI-D) | VGA (RGB 5-6-5) |
| UART | CH340N (USB mini-B) | CP2102N (USB micro-B) |
| SD card | microSD | microSD |
| VGA | No | Yes |
| 7-segment | No | Yes (3-digit) |
| PMOD | 4 connectors | 2 connectors |

The Ethernet PHY is the same (RTL8211EG, GMII), so `BmbEth` + `BmbMdio` should
work directly. The display output is HDMI instead of VGA — `BmbVgaText` would
need an RGB-to-DVI serializer (available as Xilinx IP or open-source VHDL).

### Estimated JOP Capacity

| Config | LUTs (est.) | % of XC7A100T | % of XC7A200T |
|--------|:-----------:|:-------------:|:-------------:|
| 1-core + DDR3 | ~4,000 | 6% | 3% |
| 4-core SMP | ~18,000 | 28% | 13% |
| 8-core SMP | ~36,000 | 57% | 27% |
| 12-core SMP | ~54,000 | 85% | 40% |
| 16-core SMP | ~72,000 | >100% | 53% |

The XC7A200T variant would comfortably support 16-core SMP with DDR3 — a
compelling alternative to the EP4CGX150 with significantly more memory bandwidth.

## Example Projects

In `/srv/git/qmtech/QM_XC7A100T_WUKONG_BOARD/V3/Software/XC7A100T/`:

| Project | Description |
|---------|-------------|
| Test01_led_key | LED blink + button test |
| Test04_DDR3_MIG | Xilinx MIG DDR3 controller + traffic generator |
| Test05_usb_uart_CH340N | USB UART serial test (CH340N) |
| Test06_HDMI_OUT | HDMI video output (RGB2DVI, color bar) |
| Test08_GMII_Ethernet | Gigabit Ethernet PHY test |
| Test10_SDRAM | SDR SDRAM read/write test (W9825G6KH-6) |

## Power

Multi-rail supply with buck converters:
- 5V input via USB or DC jack
- 1.0V (VCCINT), 1.8V (VCCAUX), 1.35V (DDR3), 3.3V (I/O), 1.5V (mixed)
- TPS563201 and MP8712 regulators

## Comparison with Other JOP Platforms

| Feature | Wukong V3 | XC7A100T Core | EP4CGX150 | Au V2 (XC7A35T) |
|---------|:---------:|:------------:|:---------:|:---------------:|
| LUTs / LEs | 63,400 | 63,400 | 149,760 | 20,800 |
| Block RAM | 4,860 Kbit | 4,860 Kbit | 6,635 Kbit | 1,800 Kbit |
| DDR3 | 256 MB | 256 MB | — | 256 MB |
| SDR SDRAM | 32 MB | — | 32 MB | — |
| Ethernet | Built-in | Via DB_FPGA | Via DB_FPGA | — |
| Display | HDMI | VGA (DB_FPGA) | VGA (DB_FPGA) | — |
| Self-contained | Yes | No | No | No |
| Max JOP cores | ~12 | ~12 | 16 | 2-3 |
