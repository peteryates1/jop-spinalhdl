# QMTECH XC7A100T Wukong Board V3

## Overview

Self-contained Artix-7 development board with DDR3, SDR SDRAM, Gigabit Ethernet,
HDMI, USB UART, and SD card all built-in. **Does not require the DB_FPGA daughter
board** — all peripherals are on the main PCB.

GitHub: <https://github.com/ChinaQMTECH/QM_XC7A100T_WUKONG_BOARD>

Reference files: `/srv/git/qmtech/QM_XC7A100T_WUKONG_BOARD/V3/`

Schematic: [QMTECH-XC7A100T_200T-Wukong-Board-V03-20240121.pdf](https://github.com/ChinaQMTECH/QM_XC7A100T_WUKONG_BOARD/blob/main/V3/Hardware/QMTECH-XC7A100T_200T-Wukong-Board-V03-20240121.pdf)
(local: `V3/Hardware/QMTECH-XC7A100T_200T-Wukong-Board-V03-20240121.pdf`)

## FPGA

- **Device**: Xilinx Artix-7 — XC7A100T-FGG676
- **Logic Cells**: 101,440
- **LUTs**: 63,400
- **Block RAM**: 4,860 Kbit
- **DSP slices**: 240
- **Package**: FGG676 (676-pin BGA)
- **Speed grade**: -2
- **GTP Transceivers**: 8 (6.6 Gbps)

## Clock

- **Oscillator**: 50 MHz, SG-8002JC (PIN M21, LVCMOS33)
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

## System Pins

| Signal | Pin | I/O Standard | Bank | Function |
|--------|-----|-------------|------|----------|
| `SYS_CLK` | M21 | LVCMOS33 | 14 | 50 MHz oscillator (Y1) |
| `SYS_RST_N` | H7 | LVCMOS33 | 35 | Reset button KEY0/SW2 (active low) |
| `LED0` | G21 | LVCMOS33 | 15 | User LED 0 (D5) |
| `LED1` | G20 | LVCMOS33 | 15 | User LED 1 (D6) |
| `KEY1` | M6 | LVCMOS33 | 34 | Push button (SW3) |

## UART (CH340N USB-to-UART)

| Signal | Pin | Direction |
|--------|-----|-----------|
| `UART_TX` | E3 | FPGA → CH340N |
| `UART_RX` | F3 | CH340N → FPGA |

USB connector: J4 (Mini USB Type-B).

## Ethernet (RTL8211EG, GMII)

From `Test08_GMII_Ethernet` XDC. Same PHY as DB_FPGA.

**Transmit path:**

| Signal | Pin |
|--------|-----|
| `ETH_TXC` (GTX_CLK) | U1 |
| `ETH_TX_EN` | T2 |
| `ETH_TX_ER` | J1 |
| `ETH_TXD[0]` | R2 |
| `ETH_TXD[1]` | P1 |
| `ETH_TXD[2]` | N2 |
| `ETH_TXD[3]` | N1 |
| `ETH_TXD[4]` | M1 |
| `ETH_TXD[5]` | L2 |
| `ETH_TXD[6]` | K2 |
| `ETH_TXD[7]` | K1 |

**Receive path:**

| Signal | Pin |
|--------|-----|
| `ETH_RXC` | P4 |
| `ETH_RX_DV` | L3 |
| `ETH_RX_ER` | U5 |
| `ETH_RXD[0]` | M4 |
| `ETH_RXD[1]` | N3 |
| `ETH_RXD[2]` | N4 |
| `ETH_RXD[3]` | P3 |
| `ETH_RXD[4]` | R3 |
| `ETH_RXD[5]` | T3 |
| `ETH_RXD[6]` | T4 |
| `ETH_RXD[7]` | T5 |

**Management and status:**

| Signal | Pin |
|--------|-----|
| `ETH_MDC` | H2 |
| `ETH_MDIO` | H1 |
| `ETH_RESET_N` | R1 |
| `ETH_COL` | U4 |
| `ETH_CRS` | U2 |

All Ethernet signals are Bank 34, LVCMOS33.
25 MHz PHY reference crystal (Y2) is on-board, independent of 50 MHz system clock.

## HDMI Output (DVI-D via TPD12S016)

From `Test06_HDMI_OUT` XDC.

**TMDS differential pairs:**

| Signal | Pin | I/O Standard |
|--------|-----|-------------|
| `HDMI_D0_P` | E1 | TMDS_33 |
| `HDMI_D0_N` | D1 | TMDS_33 |
| `HDMI_D1_P` | F2 | TMDS_33 |
| `HDMI_D1_N` | E2 | TMDS_33 |
| `HDMI_D2_P` | G2 | TMDS_33 |
| `HDMI_D2_N` | G1 | TMDS_33 |
| `HDMI_CLK_P` | D4 | TMDS_33 |
| `HDMI_CLK_N` | C4 | TMDS_33 |

**Control signals (via TPD12S016 level shifter):**

| Signal | Pin | I/O Standard | Function |
|--------|-----|-------------|----------|
| `HDMI_SCL` | B2 | LVCMOS33 | I2C clock (DDC, 4.7K pullup) |
| `HDMI_SDA` | A2 | LVCMOS33 | I2C data (DDC, 4.7K pullup) |
| `HDMI_HPD` | A3 | LVCMOS33 | Hot Plug Detect |
| `HDMI_CEC` | B1 | LVCMOS33 | Consumer Electronics Control |

## SD Card (microSD, J9)

| Signal | Pin | Bank | Function |
|--------|-----|------|----------|
| `SD_CLK` | L4 | 34 | Clock |
| `SD_CMD` | J8 | 35 | Command / MOSI |
| `SD_DAT0` | M5 | 34 | Data 0 / MISO |
| `SD_DAT1` | M7 | 34 | Data 1 |
| `SD_DAT2` | H6 | 35 | Data 2 |
| `SD_DAT3` | J6 | 35 | Data 3 / CS |
| `SD_CD` | N6 | 34 | Card detect |

## Configuration Flash (N25Q064A, Quad-SPI)

Uses dedicated FPGA configuration pins in Bank 14.

| Signal | Pin | Bank | Function |
|--------|-----|------|----------|
| `FLASH_CS_N` | P18 | 14 | Chip select (FCS_B, active low) |
| `FLASH_DQ0` | R14 | 14 | IO[0] / SO |
| `FLASH_DQ1` | R15 | 14 | IO[1] / SI (MOSI) |
| `FLASH_DQ2` | P14 | 14 | IO[2] / WP |
| `FLASH_DQ3` | N14 | 14 | IO[3] / HOLD |
| `FLASH_CLK` | H13 | — | CCLK (dedicated, use STARTUPE2) |

Bitstream config: SPIx4, 50 MHz, CFGBVS=VCCO, CONFIG_VOLTAGE=3.3V.
Post-configuration flash access requires STARTUPE2 primitive for CCLK.

## PMOD Connectors

Standard 12-pin PMOD (8 I/O + 2 GND + 2 VCC).

**J10 (Bank 35, LVCMOS33):**

| Pin | FPGA Pin | Pin | FPGA Pin |
|:---:|----------|:---:|----------|
| 1 | D5 | 7 | E5 |
| 2 | G5 | 8 | E6 |
| 3 | G7 | 9 | D6 |
| 4 | G8 | 10 | G6 |
| 5 | GND | 11 | GND |
| 6 | VCC 3.3V | 12 | VCC 3.3V |

**J11 (Bank 35, LVCMOS33):**

| Pin | FPGA Pin | Pin | FPGA Pin |
|:---:|----------|:---:|----------|
| 1 | H4 | 7 | J4 |
| 2 | F4 | 8 | G4 |
| 3 | A4 | 9 | B4 |
| 4 | A5 | 10 | B5 |
| 5 | GND | 11 | GND |
| 6 | VCC 3.3V | 12 | VCC 3.3V |

**J13 (Bank 14, LVCMOS33):**

| Pin | FPGA Pin | Pin | FPGA Pin |
|:---:|----------|:---:|----------|
| 1 | N22 | 7 | P20 |
| 2 | N21 | 8 | N23 |
| 3 | R20 | 9 | P21 |
| 4 | T22 | 10 | R21 |
| 5 | GND | 11 | GND |
| 6 | VCC 3.3V | 12 | VCC 3.3V |

**J14 (Bank 14, LVCMOS33):**

| Pin | FPGA Pin | Pin | FPGA Pin |
|:---:|----------|:---:|----------|
| 1 | P23 | 7 | N24 |
| 2 | R23 | 8 | P24 |
| 3 | T24 | 9 | R22 |
| 4 | T25 | 10 | T23 |
| 5 | GND | 11 | GND |
| 6 | VCC 3.3V | 12 | VCC 3.3V |

All PMOD pins are on dedicated I/O — no sharing conflicts with other
on-board peripherals.

## J12 Breakout Header (Bank 13, LVCMOS33)

40-pin (20x2) header. Full Bank 13 I/O breakout.

| Pin | FPGA Pin | Pin | FPGA Pin |
|:---:|----------|:---:|----------|
| 1 | VIN (5V) | 2 | VIN (5V) |
| 3 | U15 | 4 | U16 |
| 5 | V16 | 6 | V17 |
| 7 | V18 | 8 | W18 |
| 9 | V19 | 10 | W19 |
| 11 | T20 | 12 | U20 |
| 13 | W21 | 14 | Y21 |
| 15 | U22 | 16 | V22 |
| 17 | V23 | 18 | W23 |
| 19 | AB24 | 20 | AC24 |
| 21 | AA24 | 22 | AB25 |
| 23 | V24 | 24 | W24 |
| 25 | AB26 | 26 | AC26 |
| 27 | Y25 | 28 | AA25 |
| 29 | W25 | 30 | Y26 |
| 31 | V26 | 32 | W26 |
| 33 | U25 | 34 | U26 |
| 35 | NC | 36 | NC |
| 37 | NC | 38 | NC |
| 39 | VCCO_13 (3.3V) | 40 | VCCO_13 (3.3V) |

## FPGA I/O Banks

| Bank | Voltage | Primary Function |
|------|---------|------------------|
| 13 | 3.3V | J12 breakout header (16 I/O pairs) |
| 14 | 3.3V | SYS_CLK + SDR SDRAM (address, partial control) + config flash + PMOD J13/J14 |
| 15 | 3.3V | LEDs + SDR SDRAM (data, partial control) |
| 16 | 1.35V | DDR3 (all: address, data, control, DQS, DM, clock) |
| 34 | 3.3V | Ethernet + SD card (CLK/DAT0/DAT1/CD) + KEY1 |
| 35 | 3.3V | HDMI + UART + PMOD J10/J11 + SD card (CMD/DAT2/DAT3) + reset |

## JOP Implementation Status

The Wukong board has **four working FPGA tops**, all proven on hardware:

### JopSdramWukongTop — SDR SDRAM (Primary)

JOP running on the on-board W9825G6KH-6 at **100 MHz**. Serial-boot "Hello World!"
verified working. Uses `BmbSdramCtrl32` with `SdramCtrlNoCke` (pure SpinalHDL,
no vendor IP besides ClkWiz).

- **Source**: `spinalhdl/src/main/scala/jop/system/JopSdramWukongTop.scala`
- **Clock**: Board 50 MHz → ClkWiz → 100 MHz system + 100 MHz -108° SDRAM clock
- **Memory**: 32 MB SDR SDRAM, CAS=3, direct BMB (no cache)
- **Features**: Hang detector, DiagUart mux, heartbeat LED (board clock domain)
- **Timing**: WNS = -0.141 ns (6 failing paths) — marginal at 100 MHz
- **Build**: `make jop-sdram-generate && make jop-sdram-build`

### JopDdr3WukongTop — DDR3

JOP running on DDR3 via MIG at **100 MHz** (MIG ui_clk). Same DDR3 subsystem as
Alchitry Au V2 (LruCacheCore + CacheToMigAdapter) with `WukongMigBlackBox`
(no `ddr3_cs_n` pin — Wukong MIG disables CS).

- **Source**: `spinalhdl/src/main/scala/jop/system/JopDdr3WukongTop.scala`
- **Clock**: Board 50 MHz → ClkWiz → 100 MHz (MIG sys) + 200 MHz (MIG ref)
- **Memory**: 256 MB DDR3 (MT41K128M16JT), 32KB write-back L2 cache
- **Features**: Same hang detector / DiagUart mux as SDRAM top
- **Build**: `make ddr3-generate && make ddr3-build`

### SdramExerciserWukongTop — SDRAM Test

Standalone SDRAM exerciser (no JOP). Three tests loop continuously, reporting
pass/fail via UART at 1 Mbaud. Used to validate SDRAM hardware and timing
before bringing up JOP.

- **Source**: `spinalhdl/src/main/scala/jop/system/SdramExerciserWukongTop.scala`
- **Tests**: Sequential fill+readback, memCopy, write-then-read (thousands of loops PASS)
- **Build**: `make sdram-generate && make sdram-build`

### JopBramWukongTop — BRAM

JOP with on-chip BRAM (128 KB). Board bring-up and UART verification only.

- **Source**: `spinalhdl/src/main/scala/jop/system/JopBramWukongTop.scala`
- **Build**: `make generate && make build`

### FPGA Build Flow

All builds use Vivado non-project (in-process) flow. ClkWiz IP is shared between
the SDRAM exerciser and JOP SDRAM top (`make sdram-create-ip`).

```bash
cd fpga/qmtech-xc7a100t-wukong
make jop-sdram-generate   # SpinalHDL -> Verilog
make sdram-create-ip      # ClkWiz IP (once)
make jop-sdram-build      # Vivado synth + impl + bitstream
make jop-sdram-program    # openFPGALoader via dirtyJtag
make jop-sdram-monitor    # UART monitor (after serial download)
```

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

| Config | LUTs (est.) | % of XC7A100T |
|--------|:-----------:|:-------------:|
| 1-core + DDR3 | ~4,000 | 6% |
| 4-core SMP | ~18,000 | 28% |
| 8-core SMP | ~36,000 | 57% |
| 12-core SMP | ~54,000 | 85% |

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
