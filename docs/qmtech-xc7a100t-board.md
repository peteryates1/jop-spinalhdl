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

### Other Core Board Pins

| Signal | Pin | Function |
|--------|-----|----------|
| `sys_clk` | U22 | 50 MHz oscillator (LVCMOS33) |
| `sys_rst_n` | P4 | Reset button (active low, LVCMOS33) |
| `led[0]` | T23 | Core board LED 0 (LVCMOS33) |
| `led[1]` | R23 | Core board LED 1 (LVCMOS33) |

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
Pin assignments for the XC7A100T need to be mapped from the core board schematic
(connector-to-FPGA mapping differs per core board).
