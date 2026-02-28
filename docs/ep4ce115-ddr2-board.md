# A-E115FB DDR2 Board — EP4CE115 + 1GB DDR2 SODIMM

## Board Overview

Two-board system from Chinese manufacturer (A-E115FB):

**Core board (A-E115FB V2)**:
- **FPGA**: Altera Cyclone IV E — EP4CE115F23I7 (114,480 LEs, 3,888 Kbit M9K, 266 multipliers, 484-pin FBGA, industrial temp, speed grade 7)
- **Memory**: DDR2 SODIMM socket with 1GB module (Hynix HYS64T128021, DDR2-667)
- **Clock**: 27 MHz crystal oscillator (DDR2 PLL reference)
- **Config**: Active Serial (EPCS) or JTAG

**Bottom board (A-E115FB_bottom_2019)**:
- **Ethernet**: Marvell 88E1111 Gigabit PHY (10/100/1000 Mbps)
- **Audio**: Wolfson WM8731 codec
- **Display**: VGA output (ADV7123 DAC)
- **Storage**: microSD card slot
- **Serial**: CH340 USB-to-UART
- **GPIO**: Keys, LEDs, 7-segment displays

Reference files: `/srv/git/cycloneEthernet/`

## DDR2 SODIMM Interface

**Memory specifications:**
- DDR2-667 (333.5 MHz data rate, 166.75 MHz clock)
- 64-bit data bus, 8 data strobes, 8 data masks
- 14-bit address, 3-bit bank (8 banks)
- 2 chip selects, 2 CKE, 2 ODT (dual-rank SODIMM)
- I/O standard: SSTL-18 Class I (1.8V)
- FPGA I/O banks 3-6 (1.8V) for DDR2 signals

**Altera DDR2 controller IP** (`ddr2_64bit`):
- Altera ALTMEMPHY DDR2 High Performance Controller v13.1
- PLL reference: 27 MHz → generates PHY clock
- **Quartus version**: Must use **Quartus 18.1 or earlier**. Intel dropped
  DDR2 ALTMEMPHY support for Cyclone IV starting with Quartus 19.x. The IP
  may appear in newer Platform Designer catalogs (`alt_mem_if_civ_ddr2_emif`)
  but has known generation/compilation issues in 22.1+. The existing test
  projects (`DDR667_read_write`, `read_write_1G`) were built with Quartus 13.1.
  Use Quartus 18.1 for new DDR2 IP generation; the generated RTL is plain
  Verilog and can be instantiated from SpinalHDL via `BlackBox`.
- Local interface (user-facing):

| Signal | Width | Description |
|--------|------:|-------------|
| `local_address` | 26 | Memory address (128-bit granularity) |
| `local_wdata` | 128 | Write data |
| `local_rdata` | 128 | Read data |
| `local_rdata_valid` | 1 | Read data valid pulse |
| `local_read_req` | 1 | Read request |
| `local_write_req` | 1 | Write request |
| `local_burstbegin` | 1 | Burst begin marker |
| `local_ready` | 1 | Controller ready for commands |
| `local_init_done` | 1 | DDR2 calibration complete |
| `local_be` | 16 | Byte enables (128 bits / 8) |
| `local_size` | — | Burst length |
| `phy_clk` | 1 | Output clock (user logic runs on this) |

**Address space**: 2^26 × 128 bits = 2^26 × 16 bytes = **1 GB**

## Key Pin Assignments

### Clocks

| Signal | Pin | Notes |
|--------|-----|-------|
| CLK25M (25 MHz) | PIN_AB11 | Main user clock (core board oscillator) |
| CLK100M (100 MHz) | PIN_A11 | Secondary clock |
| CLK27M (27 MHz) | — | DDR2 PLL reference (on core board, not on connector) |

### Core Board Peripherals

| Signal | Pin | Notes |
|--------|-----|-------|
| LED D3 | PIN_A5 | Active low |
| LED D4 | PIN_B5 | Active low |
| LED D5 | PIN_C4 | Active low |
| LED D6 | PIN_C3 | Active low |
| KEY1 | PIN_N21 | Active low |
| KEY2 | PIN_N22 | Active low |

DDR2 pins use FPGA I/O banks 3-6 at 1.8V SSTL-18. Full pin assignment
TCL script: `ddr2_64bit_pin_assignments.tcl`

### 2.54mm Connectors

Each side of the core board has two connectors:
- **80-pin** dual-row header facing **down** — mates with the bottom board
- **60-pin** dual-row header facing **up** — user-accessible for external wiring

The 60-pin UP header is a subset of the 80-pin DOWN connector. Same FPGA pins,
different pin numbering, 10 signal pairs absent from the UP header per side.
Pin 1 is at the power end of the board on both connectors.
All connector signals pass through 22R series resistor packs (RP21-RP51).

Core board schematic: `EP4CE115_sch_pdf原理图/PDF/E115-IO-V2.pdf`
Bottom board schematic: `bottom-V2底板图.pdf` (labeled A-E40FB — older board,
same connector topology but FPGA pin names are for EP4CE40, not EP4CE115)

#### JTAG-Side DOWN Connector (80 pins)

Mates with bottom board. Pin 1 at power end. Signal names from bottom board
schematic, confirmed against EP4CE115 test project QSFs. Every signal pin is
assigned to a bottom board peripheral — no free pins.

| Pin | FPGA | Signal | Pin | FPGA | Signal |
|----:|------|--------|----:|------|--------|
| 1 | V3 | — | 2 | AA1 | AUD_XCK |
| 3 | Y2 | PHY_RST_N | 4 | Y1 | MDC |
| 5 | V4 | MDIO | 6 | T3 | TXD[7] |
| 7 | T4 | TXD[6] | 8 | T5 | TXD[5] |
| 9 | GND | | 10 | GND | |
| 11 | R4 | TXD[4] | 12 | R3 | TXD[3] |
| 13 | P4 | TXD[2] | 14 | P3 | TXD[1] |
| 15 | W2 | TXD[0] | 16 | W1 | TX_EN |
| 17 | V2 | GTX_CLK | 18 | V1 | TX_CLK |
| 19 | GND | | 20 | GND | |
| 21 | P5 | RX_CLK | 22 | N6 | RX_DV |
| 23 | N5 | RXD[0] | 24 | M5 | RXD[1] |
| 25 | U2 | RXD[2] | 26 | U1 | RXD[3] |
| 27 | R2 | RXD[4] | 28 | R1 | RXD[5] |
| 29 | GND | | 30 | GND | |
| 31 | R5 | RXD[6] | 32 | D6 | RXD[7] |
| 33 | M6 | AUD_ADCLRCK | 34 | L6 | AUD_ADCDAT |
| 35 | P2 | AUD_DACLRCK | 36 | P1 | AUD_DACDAT |
| 37 | N2 | AUD_BCLK | 38 | N1 | CH340 TX→FPGA |
| 39 | GND | | 40 | GND | |
| 41 | H5 | FPGA TX→CH340 | 42 | G5 | I2C_SDAT |
| 43 | M3 | I2C_SCLK | 44 | M4 | VGA_CLK |
| 45 | M2 | VGA_B[9] | 46 | M1 | VGA_B[8] |
| 47 | J1 | VGA_B[7] | 48 | J2 | VGA_B[6] |
| 49 | GND | | 50 | GND | |
| 51 | E1 | VGA_B[5] | 52 | D2 | VGA_B[4] |
| 53 | J4 | VGA_B[3] | 54 | J3 | VGA_B[2] |
| 55 | H1 | VGA_SYNC | 56 | H2 | VGA_BLANK |
| 57 | F1 | VGA_G[9] | 58 | F2 | VGA_G[8] |
| 59 | GND | | 60 | GND | |
| 61 | J6 | VGA_G[7] | 62 | H6 | VGA_G[6] |
| 63 | H3 | VGA_G[5] | 64 | H4 | VGA_G[4] |
| 65 | C1 | VGA_G[3] | 66 | C2 | VGA_G[2] |
| 67 | B1 | VGA_R[9] | 68 | B2 | VGA_R[8] |
| 69 | GND | | 70 | GND | |
| 71 | G3 | VGA_R[7] | 72 | H7 | VGA_R[6] |
| 73 | E3 | VGA_R[5] | 74 | E4 | VGA_R[4] |
| 75 | B3 | VGA_R[3] | 76 | A3 | VGA_R[2] |
| 77 | B4 | VGA_HS | 78 | A4 | VGA_VS |
| 79 | GND | | 80 | GND | |

Devices: Ethernet 88E1111 GMII (pins 3-32, 22 signals), Audio WM8731
(pins 2, 33-37, 42-43, 8 signals), UART CH340 (pins 38, 41), VGA ADV7123
(pins 44-78, 30 signals). V3 (pin 1) is the only unassigned signal pin.

#### JTAG-Side UP Header (60 pins)

User-accessible. Subset of the DOWN connector — same FPGA pins, different
pin numbering. Pin 1 at power end.

| Pin | FPGA | Signal | Pin | FPGA | Signal |
|----:|------|--------|----:|------|--------|
| 1 | AA1 | AUD_XCK | 2 | V3 | — |
| 3 | Y1 | MDC | 4 | Y2 | PHY_RST_N |
| 5 | V4 | MDIO | 6 | T3 | TXD[7] |
| 7 | T4 | TXD[6] | 8 | T5 | TXD[5] |
| 9 | R4 | TXD[4] | 10 | R3 | TXD[3] |
| 11 | P4 | TXD[2] | 12 | P3 | TXD[1] |
| 13 | V2 | GTX_CLK | 14 | V1 | TX_CLK |
| 15 | GND | | 16 | GND | |
| 17 | P5 | RX_CLK | 18 | N6 | RX_DV |
| 19 | N5 | RXD[0] | 20 | M5 | RXD[1] |
| 21 | U2 | RXD[2] | 22 | U1 | RXD[3] |
| 23 | R5 | RXD[6] | 24 | D6 | RXD[7] |
| 25 | M6 | AUD_ADCLRCK | 26 | L6 | AUD_ADCDAT |
| 27 | P2 | AUD_DACLRCK | 28 | P1 | AUD_DACDAT |
| 29 | N2 | AUD_BCLK | 30 | N1 | CH340 TX→FPGA |
| 31 | GND | | 32 | GND | |
| 33 | M3 | I2C_SCLK | 34 | M4 | VGA_CLK |
| 35 | M2 | VGA_B[9] | 36 | M1 | VGA_B[8] |
| 37 | J1 | VGA_B[7] | 38 | J2 | VGA_B[6] |
| 39 | E1 | VGA_B[5] | 40 | D2 | VGA_B[4] |
| 41 | J4 | VGA_B[3] | 42 | J3 | VGA_B[2] |
| 43 | H1 | VGA_SYNC | 44 | H2 | VGA_BLANK |
| 45 | F1 | VGA_G[9] | 46 | F2 | VGA_G[8] |
| 47 | GND | | 48 | GND | |
| 49 | H4 | VGA_G[4] | 50 | H3 | VGA_G[5] |
| 51 | C1 | VGA_G[3] | 52 | C2 | VGA_G[2] |
| 53 | B1 | VGA_R[9] | 54 | B2 | VGA_R[8] |
| 55 | G3 | VGA_R[7] | 56 | H7 | VGA_R[6] |
| 57 | E3 | VGA_R[5] | 58 | E4 | VGA_R[4] |
| 59 | B3 | VGA_R[3] | 60 | A3 | VGA_R[2] |

**Pins on DOWN only** (not accessible from UP header):

| DOWN Pin | FPGA | Signal | Device |
|---------:|------|--------|--------|
| 15 | W2 | TXD[0] | Ethernet |
| 16 | W1 | TX_EN | Ethernet |
| 27 | R2 | RXD[4] | Ethernet |
| 28 | R1 | RXD[5] | Ethernet |
| 41 | H5 | FPGA TX→CH340 | UART |
| 42 | G5 | I2C_SDAT | Audio |
| 61 | J6 | VGA_G[7] | VGA |
| 62 | H6 | VGA_G[6] | VGA |
| 77 | B4 | VGA_HS | VGA |
| 78 | A4 | VGA_VS | VGA |

Consequence: the UP header cannot support complete Ethernet (missing TXD[0],
TX_EN, RXD[4:5]), VGA (missing G[6:7], HS, VS), UART TX (H5), or Audio I2C
SDA (G5).

#### Power-Side DOWN Connector (80 pins)

Mates with bottom board. Pin 1 at power end (VCC5V). Bottom board carries
SD card, keys, IR, buzzer, and other peripherals. No EP4CE115 test project
assigns signals to these pins — the bottom board schematic labels them with
EP4CE40 pin names (same physical package, different FPGA). Signal mapping
requires metering the physical board.

| Pin | EP4CE115 | EP4CE40 | Pin | EP4CE115 | EP4CE40 |
|----:|----------|---------|----:|----------|---------|
| 1 | VCC5V | VCC | 2 | VCC5V | VCC |
| 3 | VCC5V | VCC | 4 | VCC5V | VCC |
| 5 | A20 | GND | 6 | B20 | GND |
| 7 | A19 | GND | 8 | B19 | GND |
| 9 | GND | GND | 10 | GND | GND |
| 11 | C19 | R19 | 12 | D19 | R20 |
| 13 | C18 | P15 | 14 | D18 | P16 |
| 15 | A18 | R21 | 16 | B18 | R22 |
| 17 | A17 | P21 | 18 | B17 | P22 |
| 19 | GND | GND | 20 | GND | GND |
| 21 | C17 | N17 | 22 | D17 | N18 |
| 23 | D15 | N19 | 24 | E15 | N20 |
| 25 | A16 | M21 | 26 | B16 | M22 |
| 27 | A15 | L21 | 28 | B15 | L22 |
| 29 | GND | GND | 30 | GND | GND |
| 31 | E16 | M19 | 32 | F15 | M20 |
| 33 | E14 | K21 | 34 | F13 | N22 |
| 35 | E9 | J21 | 36 | D10 | J22 |
| 37 | D8 | H21 | 38 | E8 | H22 |
| 39 | GND | GND | 40 | GND | GND |
| 41 | C10 | N16 | 42 | E7 | P17 |
| 43 | C13 | H19 | 44 | D13 | H20 |
| 45 | E13 | F21 | 46 | C15 | F22 |
| 47 | A14 | E21 | 48 | B14 | E22 |
| 49 | GND | GND | 50 | GND | GND |
| 51 | E12 | K17 | 52 | F14 | K18 |
| 53 | E11 | H16 | 54 | F11 | J17 |
| 55 | A13 | D21 | 56 | B13 | D22 |
| 57 | A10 | C21 | 58 | B10 | C22 |
| 59 | GND | GND | 60 | GND | GND |
| 61 | F9 | F19 | 62 | F10 | F20 |
| 63 | F7 | G18 | 64 | F8 | H17 |
| 65 | A9 | B21 | 66 | B9 | B22 |
| 67 | A8 | B19 | 68 | B8 | A19 |
| 69 | GND | GND | 70 | GND | GND |
| 71 | C7 | F17 | 72 | C8 | G17 |
| 73 | C6 | B16 | 74 | D7 | A16 |
| 75 | A7 | B18 | 76 | B7 | A18 |
| 77 | A6 | B17 | 78 | B6 | A17 |
| 79 | GND | GND | 80 | GND | GND |

Notes:
- Pins 5-8: EP4CE115 has FPGA I/O (A20, B20, A19, B19) but bottom board has
  GND at these positions. These pins are grounded through 22R when bottom
  board is attached — do not use as FPGA outputs with bottom board connected.
- Pins 73-78: bottom board EP4CE40 pins B16/A16/B18/A18/B17/A17 are in the
  PE connector region (SD card, keys, IR, buzzer) — SD card likely here.

#### Power-Side UP Header (60 pins)

User-accessible. Pin 1 at power end (VCC5V). Subset of DOWN connector.

| Pin | EP4CE115 | Pin | EP4CE115 | Pin | EP4CE115 |
|----:|----------|----:|----------|----:|----------|
| 1 | VCC5V | 21 | A15 | 41 | E11 |
| 2 | VCC5V | 22 | B15 | 42 | F11 |
| 3 | A20 | 23 | E16 | 43 | A13 |
| 4 | B20 | 24 | F15 | 44 | B13 |
| 5 | A19 | 25 | E14 | 45 | A10 |
| 6 | B19 | 26 | F13 | 46 | B10 |
| 7 | C19 | 27 | E9 | 47 | GND |
| 8 | D19 | 28 | D10 | 48 | GND |
| 9 | C18 | 29 | D8 | 49 | F7 |
| 10 | D18 | 30 | E8 | 50 | F8 |
| 11 | A18 | 31 | GND | 51 | A9 |
| 12 | B18 | 32 | GND | 52 | B9 |
| 13 | A17 | 33 | C13 | 53 | A8 |
| 14 | B17 | 34 | D13 | 54 | B8 |
| 15 | GND | 35 | E13 | 55 | C7 |
| 16 | GND | 36 | C15 | 56 | C8 |
| 17 | D15 | 37 | A14 | 57 | C6 |
| 18 | E15 | 38 | B14 | 58 | D7 |
| 19 | A16 | 39 | E12 | 59 | A7 |
| 20 | B16 | 40 | F14 | 60 | B7 |

**Pins on DOWN only** (not on UP header): A20/B20 (GND'd by bottom board),
A19/B19 (GND'd by bottom board), C10/E7, F9/F10, A6/B6.

Signal assignments unknown — need to meter the physical board.
Pins 3-14 (A20 through B17) likely include SD card and key connections
based on bottom board PE connector topology.

### Bottom Board Peripheral Pin Summary

**Ethernet (88E1111 GMII)** — 22 pins on JTAG-side connector (confirmed from
`tcpip_hw.pin` fitter output, which targets EP4CE115 despite QSF saying Arria V):
- MDC: Y1, MDIO: V4, PHY_RST_N: Y2
- TXD[7:0]: T3, T4, T5, R4, R3, P4, P3, W2
- TX_EN: W1, GTX_CLK: V2, TX_CLK: V1
- RXD[7:0]: D6, R5, R1, R2, U1, U2, M5, N5
- RX_DV: N6, RX_CLK: P5
- Link LED: C3 (core board LED D6, directly wired, not on connector)

**VGA (ADV7123)** — 30 pins on JTAG-side connector (confirmed from QSF):
- Red R[2:9]: A3, B3, E4, E3, H7, G3, B2, B1
- Green G[2:9]: C2, C1, H4, H3, H6, J6, F2, F1
- Blue B[2:9]: J3, J4, D2, E1, J2, J1, M1, M2
- VGA_CLK: M4, VGA_BLANK: H2, VGA_SYNC: H1, VGA_HS: B4, VGA_VS: A4

**Audio (WM8731)** — 8 pins on JTAG-side connector (confirmed from QSF):
- I2C_SCLK: M3, I2C_SDAT: G5
- AUD_BCLK: N2, AUD_XCK: AA1
- AUD_DACDAT: P1, AUD_DACLRCK: P2
- AUD_ADCDAT: L6, AUD_ADCLRCK: M6

**UART (CH340)** — 2 pins on JTAG-side connector (confirmed from QSF):
- FPGA TX → CH340 RX: H5 (DOWN pin 41 only, **broken** — signal doesn't reach CH340)
- CH340 TX → FPGA RX: N1 (DOWN pin 38 = UP pin 30, working)

**Keys** — on core board, active low, accent key caps:
- KEY[0]: N21, KEY[1]: T1, KEY[2]: N22 (not on connectors)

**SD Card, IR, Buzzer, DIP switches** — on power-side connector (EP4CE115
pin assignments unconfirmed, need metering). SD card likely near pins 73-78
of the power-side DOWN connector (EP4CE115 pins C6, D7, A7, B7, A6, B6).

### Recommended UART Pins (Pico Serial Bridge)

The JTAG-side connector has NO free pins — all are assigned to Ethernet,
VGA, Audio, or UART. The previous recommendation of V4/T3 was incorrect
(those are Ethernet MDIO/TXD[7]).

Use **power-side UP header** pins for a Pico 2W serial bridge. Pick pins
with dashes in the connector listing (confirmed regular I/O — pins without
dashes like E8, B8, D10, F13, F15 are likely VREF or dedicated clock inputs).
Avoid the SD card region (pins 3-14). Verify chosen pins are not loaded by
the bottom board with a multimeter before connecting.

| Signal | FPGA Pin | UP Header Pin | Pico 2W |
|--------|----------|---------------|---------|
| FPGA TX | A14 | 37 | GP1 (UART0 RX) |
| FPGA RX | B14 | 38 | GP0 (UART0 TX) |

Alternative: any adjacent pair of dashed pins on the power-side UP header
(e.g., C13/D13 at 33-34, A10/B10 at 45-46).

## Address Mapping for JOP

The DDR2 local interface maps cleanly to the existing LruCacheCore:

```
JOP pipeline → BmbMemoryController → BMB bus → BmbCacheBridge → LruCacheCore → CacheToDdr2Adapter → DDR2
  30-bit word     (aoutAddr<<2).resized   32-bit byte    addr(29:0)→30-bit   26-bit cache    26-bit DDR2
  [29:28]=type                            [31:30]=00     strips type bits     line addr       local_address
```

| Layer | Address Width | Granularity | Range |
|-------|--------------|-------------|-------|
| JOP word address (with type bits) | 30 bits | 32-bit word | 1 GB + type space |
| JOP physical word address | 28 bits | 32-bit word | 1 GB (2^28 words) |
| BMB byte address | 30 bits | byte | 1 GB |
| Cache line address | 26 bits | 128-bit (4 words) | 1 GB (2^26 lines) |
| DDR2 `local_address` | 26 bits | 128-bit (4 words) | 1 GB |

The cache line address (26 bits) matches the DDR2 `local_address` width exactly.
Both operate at 128-bit (16-byte) granularity. No address translation needed
between cache and DDR2 controller — direct wire.

JOP configuration:
```scala
JopCoreConfig(
  memConfig = JopMemoryConfig(
    addressWidth = 30,               // 28-bit physical word + 2 type bits = 1 GB
    mainMemSize = 1024L * 1024 * 1024, // 1 GB DDR2
    burstLen = 8,                    // DDR2 burst
    stackRegionWordsPerCore = 8192   // 32 KB per core
  ),
  // ...
)
```

## FPGA Resource Budget

EP4CE115 vs EP4CGX150 (current primary platform):

| Resource | EP4CE115 | EP4CGX150 | JOP per-core |
|----------|----------|-----------|-------------|
| LEs | 114,480 | 149,760 | ~5,400 |
| Block RAM | 3,888 Kbit | 6,635 Kbit | ~28 Kbit |
| Multipliers | 266 | 360 | 1 |

Estimated JOP capacity:

| Config | LEs (est.) | % of EP4CE115 | BRAM |
|--------|-----------|---------------|------|
| 1-core + DDR2 cache | ~8,000 | 7% | ~200 Kbit |
| 4-core SMP | ~25,000 | 22% | ~320 Kbit |
| 8-core SMP | ~47,000 | 41% | ~530 Kbit |
| 12-core SMP | ~69,000 | 60% | ~740 Kbit |

12 cores is comfortably within resource limits. The main constraint is block
RAM (3,888 Kbit total) — each core uses ~28 Kbit for method cache + stack RAM,
plus the shared L2 cache. A 128 KB L2 cache would use 1,024 Kbit (26% of BRAM),
leaving room for 12+ cores.

## Cache Line Width and DDR2 Burst Alignment

DDR2 has a minimum burst length — each access transfers a fixed number of 64-bit beats:

| Burst Length | Transfer Size | Local Beats (128-bit) |
|:---:|---:|---:|
| BL4 | 4 × 64 = 256 bits (32 bytes) | 2 |
| BL8 | 8 × 64 = 512 bits (64 bytes) | 4 |

The Altera DDR2 HP controller presents a 128-bit local interface (half-rate design:
2 × 64-bit DDR2 width). Each `local_size=1` access reads one 128-bit word, but the
DDR2 must execute a full BL4 or BL8 burst internally. The extra beats are wasted.

**Current LruCacheCore uses 128-bit (4-word) lines.** This wastes DDR2 bandwidth:

| Cache Line | BL4 Efficiency | BL8 Efficiency |
|-----------|:-:|:-:|
| 128-bit (current) | 50% (128 of 256 used) | 25% (128 of 512 used) |
| 256-bit | 100% | 50% |
| 512-bit | 200% (2 bursts needed) | 100% |

**Recommendation: widen cache lines to match DDR2 burst width.**

For BL4 (256-bit lines = 8 words):
- Perfect bandwidth match — every DDR2 burst fully utilized
- `local_size=2` per cache access (two 128-bit local beats)
- Cache address width: 26 - 1 = 25 bits (each address covers 2 local words)
- 256 sets × 4 ways × 32 bytes = 32 KB (same size, half the sets)
- Doubles spatial locality (8 consecutive words per line vs 4)

For BL8 (512-bit lines = 16 words):
- Perfect bandwidth match — every DDR2 burst fully utilized
- `local_size=4` per cache access (four 128-bit local beats)
- Cache address width: 26 - 2 = 24 bits
- 256 sets × 4 ways × 64 bytes = 64 KB (or 128 sets for 32 KB)
- Quadruples spatial locality but increases eviction cost

**LruCacheCore parameterization**: The `lineWidth` (currently hardcoded at 128 bits)
should become a configurable parameter (128/256/512). The main changes:

1. **Data BRAMs** — wider read/write per line. Can use multiple 128-bit BRAMs read
   in parallel, or widen the BRAM primitive. BRAM width is flexible on Cyclone IV
   (M9K supports up to 36-bit native, but multiple M9Ks can be ganged).
2. **BmbCacheBridge** — on a 32-bit word write, only dirty the relevant 32-bit slice
   within the wider line (byte enables select sub-line position). On read, extract
   the correct 32-bit word using the sub-line offset bits from the BMB address.
3. **Tag/set geometry** — wider lines mean fewer sets at the same total cache size
   (or same sets = larger cache). Tags need fewer bits (fewer sets = fewer index bits).
4. **Eviction cost** — wider lines mean more data to write back on dirty eviction.
   A 512-bit dirty eviction writes 4× as much as 128-bit. Mitigated by the DDR2
   burst matching (one burst per eviction regardless of line width).

**Benefit to DDR3 path too**: The Artix-7 DDR3 MIG also executes BL8 internally
(128-bit port × BL8 = 1024 bits per DRAM burst, but MIG manages this). Widening
to 256-bit lines on DDR3 would halve the number of cache misses for sequential
access patterns (BC fill, memCopy, GC handle scanning) — a significant win for
GC-heavy workloads that were the original DDR3 bottleneck.

## CacheToDdr2Adapter Design

The adapter converts LruCacheCore memory commands to the Altera DDR2 local
interface. This is simpler than the Xilinx MIG adapter (`CacheToMigAdapter`)
because the Altera interface is straightforward request/response:

```
LruCacheCore                          DDR2 HP Controller
  memCmd.valid  ──────────────────►   local_write_req / local_read_req
  memCmd.addr   ──────────────────►   local_address
  memCmd.wdata  ──────────────────►   local_wdata (128-bit per beat, multi-beat for wide lines)
  memCmd.isWrite ─────────────────►   (selects write_req vs read_req)
  memRsp.valid  ◄──────────────────   local_rdata_valid
  memRsp.rdata  ◄──────────────────   local_rdata (128-bit per beat)
  memCmd.ready  ◄──────────────────   local_ready
```

Key differences from MIG adapter:
- **No app_rdy/app_wdf_rdy split**: DDR2 HP uses single `local_ready` for both
  command and data (MIG has separate command and write-data channels)
- **No write data FIFO**: DDR2 HP accepts `local_wdata` on same cycle as
  `local_write_req` (MIG requires `app_wdf_data` with separate handshake)
- **Single clock domain**: user logic runs on `phy_clk` output from DDR2 controller
  (MIG has separate `ui_clk`)
- **Multi-beat transfers**: for 256-bit or 512-bit cache lines, the adapter issues
  `local_size=2` or `local_size=4` with `local_burstbegin` and streams consecutive
  128-bit beats to/from the cache

## Existing Test Projects

Core board (`/srv/git/cycloneEthernet/A-E115FB_core_V2/E115_core_test/`):
- `DDR667_read_write/` — DDR2 read/write test (16 addresses × 4 patterns)
- `read_write_1G/` — Full 1 GB DDR2 test
- `CLK_27M_TEST/` — Clock test
- `KEY_LED/`, `LED-TEST/` — GPIO tests

Bottom board (`/srv/git/cycloneEthernet/A-E115FB_bottom_2019/.../E115_core_test/`):
- `tcp_udp_tse_test/` — Ethernet TCP/UDP with Altera TSE MAC + 88E1111
- `USB_TTL_COM/` — UART loopback test (CH340, TX=PIN_H5, RX=PIN_N1)
- `VGA_7123_TEST/` — VGA output test (ADV7123)
- `Audio_Bypass/` — Audio loopback (WM8731)
- `SD_card/` — SD card test
- `WM8731_input_FFT_VGA/` — Audio FFT with VGA display

## Quartus Toolchain

**Quartus 18.1** (last version with full Cyclone IV DDR2 IP support):
- Required for generating DDR2 ALTMEMPHY controller IP
- `/opt/altera/18.1/quartus/bin/quartus_sh --flow compile <project>`
- SOF→RBF: `/opt/altera/18.1/quartus/bin/quartus_cpf -c input.sof output.rbf`
- All existing EP4CE115 test projects target this or earlier versions

**Quartus 25.1** (current):
- Supports EP4CE115 device (Quartus Prime Lite edition, free)
- Can compile designs, run fitter, generate bitstreams
- **Cannot generate new DDR2 controller IP** — ALTMEMPHY dropped for Cyclone IV
- Can instantiate pre-generated DDR2 controller RTL (Verilog from 18.1)
- All other IP (PLLs, on-chip RAM, ALTDDIO, etc.) works normally

**Recommended workflow for JOP on EP4CE115:**
1. Generate DDR2 controller IP once in Quartus 18.1 (or reuse from test projects)
2. Wrap the generated Verilog as a SpinalHDL `BlackBox`
3. Build and compile the full design in either Quartus version
4. Program via pico-usb-blaster (`program_fpga.c`) or `quartus_pgm`

## Architectural Considerations

### DDR2 Bandwidth

DDR2-667 at 64-bit: theoretical peak 5.3 GB/s. Effective bandwidth is much lower
due to row activation, refresh, bank conflicts, and the 128-bit local interface
running at half-rate. But even at 10% efficiency (530 MB/s), this is 3x the SDR
SDRAM bandwidth (160 MB/s) and comparable to the Artix-7 DDR3 path.

With 12 cores sharing the DDR2 bus through a round-robin arbiter + write-back
cache, effective per-core bandwidth depends on L2 cache hit rate. A 128 KB L2
(feasible in EP4CE115's 486 KB BRAM) would significantly reduce DDR2 traffic.

### GC with 1 GB Heap

With `MAX_HANDLES = 65536` and 1 GB DDR2:
- Handle area: 2 MB (0.2% of memory)
- Usable heap: ~1022 MB
- Sweep time: ~6 ms at 100 MHz (same as 256 MB — capped by MAX_HANDLES)
- GC init: ~5 ms (65K × 8 word writes)

The GC architecture works unchanged. If applications need more than 65536 live
objects, MAX_HANDLES can be raised — each doubling adds ~6 ms to sweep time.

### Per-Core L1 Cache (Future)

With 12 cores, the shared L2 becomes a bottleneck (arbiter serializes all misses).
A per-core L1 write-back cache (4-8 KB, simple direct-mapped) would absorb most
working-set accesses before they reach the arbiter. This requires a cache coherency
protocol between L1s — similar to existing A$/O$ snoops but for the general cache.

### SMP Synchronization at 12 Cores

CmpSync's global lock becomes a severe bottleneck with 12 cores — only one core can
be in any `synchronized` block at a time. Options:

- **Larger IHLU** — current 32-slot CAM may not be enough for 12 cores with many
  contested objects. 64 or 128 slots are feasible on EP4CE115 (extra CAM area is
  small relative to the FPGA's 114K LEs).
- **Partitioned heaps** — each core owns a heap region with per-core GC. Cross-core
  references through handles still work (handles are global). Eliminates GC STW for
  non-owning cores.
- **Read-only shared data** — class structures, constant pools, method bytecodes are
  immutable after loading. Only mutable object data needs coherency.

### Concurrent GC (Future)

The address translation hardware already exists in BmbMemoryController (`translateAddr`)
but is unused because it causes timing violations at 100 MHz. If pipelined:

- Mutator reads go through hardware read barrier (redirects to new location if object
  is being moved by GC)
- GC compaction runs on a dedicated core in background
- No STW pause for compaction — only brief STW for root snapshot
- JOP's handle indirection makes this uniquely cheap: only `handle[OFF_PTR]` redirects,
  not every pointer in the heap

This is what makes 1 GB actually useful for real-time: predictable sub-millisecond
GC pauses regardless of heap size.

### System Clock

The DDR2 PLL reference is 27 MHz. The `phy_clk` output from the DDR2 controller
runs at half the DDR2 rate. JOP would need a separate PLL for its system clock
(80-100 MHz), with a clock-domain crossing between the JOP domain and the DDR2
`phy_clk` domain — similar to the Artix-7 DDR3 path where JOP runs on the MIG
`ui_clk` (100 MHz derived from the DDR3 controller).

Alternatively, JOP could run directly on `phy_clk` if its frequency is suitable
(~166 MHz for DDR667 half-rate — faster than needed, but EP4CE115 speed grade 7
may not meet timing at 166 MHz for JOP logic).
