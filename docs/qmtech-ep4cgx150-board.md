# QMTECH EP4CGX150 Core Board

## Overview

Primary JOP development platform. QMTECH EP4CGX150DF27 core board with onboard
SDR SDRAM. Connects to the [DB_FPGA daughter board](qmtech-db-fpga.md) via dual
32x2 pin headers (J2, J3) for Ethernet, UART, VGA, and SD card.

GitHub: <https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD>

Reference files: `/srv/git/qmtech/EP4CGX150DF27_CORE_BOARD/`

Schematic: `QMTECH_EP4CGX150DF27_V2.pdf` (core board V2)

## FPGA

- **Device**: Altera Cyclone IV GX — EP4CGX150DF27I7
- **Logic Elements**: 149,760
- **Block RAM**: 6,635 Kbit (504 M9K blocks)
- **Multipliers**: 360 (18x18)
- **PLLs**: 6
- **GX Transceivers**: 4 (3.125 Gbps)
- **Package**: 672-pin FBGA (DF27)
- **Temperature**: Industrial (-40 to +100 C)
- **Speed grade**: 7
- **Core voltage**: 1.2V
- **I/O standard**: 3.3V LVCMOS (general purpose)

## Clock

- **Oscillator**: 50 MHz (PIN_B14)
- **PLL** (`dram_pll`): 50 MHz input
  - c0: 50 MHz (unused)
  - c1: 80 MHz — system clock (JOP cores, memory controller)
  - c2: 80 MHz, -3ns phase shift — SDRAM clock output
  - c3: 25 MHz — VGA pixel clock (640x480@60Hz)
- **Ethernet PLL** (`pll_125`): 50 MHz → 125 MHz for GMII TX clock

At 100 MHz (for 1-8 core configurations), the PLL is reconfigured manually
in `dram_pll.vhd`. The -3ns phase shift on c2 ensures SDRAM clock setup/hold
margins at the SDRAM chip.

## SDR SDRAM

**Component**: Winbond W9825G6JH6 — 256 Mbit (32 MB), 16-bit data bus.

| Parameter | Value |
|-----------|-------|
| Capacity | 256 Mbit (32 MB) |
| Data width | 16-bit |
| Row address | 13-bit (8192 rows) |
| Column address | 9-bit (512 columns) |
| Banks | 4 (2-bit bank address) |
| CAS latency | 3 |
| Speed grade | -6 (6ns CAS, 166 MHz max) |
| I/O standard | 3.3V LVCMOS |

**Address space**: 2^13 rows x 2^9 columns x 4 banks x 16 bits = 256 Mbit = 32 MB

**JOP SDRAM interface** (`BmbSdramCtrl32`): The JOP BMB bus is 32-bit but the
SDRAM is 16-bit. BmbSdramCtrl32 implements a 32-to-16-bit bridge that splits
each 32-bit word into two 16-bit SDRAM accesses. Burst reads (4-word BC fill,
4-element array cache fill) use SDRAM page-mode bursts for efficiency.

The SDRAM controller is an Altera-provided tri-state controller BlackBox
(`altera_sdram_tri_controller`) rather than SpinalHDL's `SdramCtrl`, which
had CKE gating issues under SMP backpressure (see `docs/sdr-sdram-gc-hang.md`).

### Pin Assignments

From `fpga/qmtech-ep4cgx150-sdram/jop_sdram.qsf`:

**SDRAM clock**: PIN_E22

**Control signals:**

| Signal | Pin |
|--------|-----|
| `sdram_CSn` | PIN_H26 |
| `sdram_CKE` | PIN_K24 |
| `sdram_WEn` | PIN_G25 |
| `sdram_RASn` | PIN_H25 |
| `sdram_CASn` | PIN_G26 |

**Bank address:**

| Signal | Pin |
|--------|-----|
| `sdram_BA[0]` | PIN_J25 |
| `sdram_BA[1]` | PIN_J26 |

**Address bus [12:0]:**

| Signal | Pin |
|--------|-----|
| `sdram_ADDR[0]` | PIN_L25 |
| `sdram_ADDR[1]` | PIN_L26 |
| `sdram_ADDR[2]` | PIN_M25 |
| `sdram_ADDR[3]` | PIN_M26 |
| `sdram_ADDR[4]` | PIN_N22 |
| `sdram_ADDR[5]` | PIN_N23 |
| `sdram_ADDR[6]` | PIN_N24 |
| `sdram_ADDR[7]` | PIN_M22 |
| `sdram_ADDR[8]` | PIN_M24 |
| `sdram_ADDR[9]` | PIN_L23 |
| `sdram_ADDR[10]` | PIN_K26 |
| `sdram_ADDR[11]` | PIN_L24 |
| `sdram_ADDR[12]` | PIN_K23 |

**Data mask:**

| Signal | Pin |
|--------|-----|
| `sdram_DQM[0]` | PIN_F26 |
| `sdram_DQM[1]` | PIN_H24 |

**Data bus [15:0]:**

| Signal | Pin |
|--------|-----|
| `sdram_DQ[0]` | PIN_B25 |
| `sdram_DQ[1]` | PIN_B26 |
| `sdram_DQ[2]` | PIN_C25 |
| `sdram_DQ[3]` | PIN_C26 |
| `sdram_DQ[4]` | PIN_D25 |
| `sdram_DQ[5]` | PIN_D26 |
| `sdram_DQ[6]` | PIN_E25 |
| `sdram_DQ[7]` | PIN_E26 |
| `sdram_DQ[8]` | PIN_H23 |
| `sdram_DQ[9]` | PIN_G24 |
| `sdram_DQ[10]` | PIN_G22 |
| `sdram_DQ[11]` | PIN_F24 |
| `sdram_DQ[12]` | PIN_F23 |
| `sdram_DQ[13]` | PIN_E24 |
| `sdram_DQ[14]` | PIN_D24 |
| `sdram_DQ[15]` | PIN_C24 |

### Other Pins

| Signal | Pin | Function |
|--------|-----|----------|
| `clk_in` | PIN_B14 | 50 MHz oscillator |
| `led[0]` | PIN_A25 | Core board LED 0 |
| `led[1]` | PIN_A24 | Core board LED 1 |

UART, Ethernet, VGA, and SD card pins are on the DB_FPGA daughter board.
See [QMTECH DB_FPGA Daughter Board](qmtech-db-fpga.md) for pin assignments.

## JOP Configuration

From `JopSdramTop.scala`:

```scala
JopCoreConfig(
  memConfig = JopMemoryConfig(burstLen = 4),  // addressWidth=25 (default, 32 MB)
  clkFreqHz = 80000000L,                      // 80 MHz (100 MHz for 1-8 cores)
  ioConfig = ioConfig                         // DB_FPGA peripherals when present
)
```

| Parameter | Value | Notes |
|-----------|-------|-------|
| `addressWidth` | 25 (default) | 23-bit physical word + 2 type bits = 32 MB |
| `mainMemSize` | 0 (auto) | Auto-derives to 32 MB from addressWidth |
| `burstLen` | 4 | 4-word SDRAM page-mode burst |
| `stackRegionWordsPerCore` | 8192 | 32 KB per-core stack region |
| System clock | 80 MHz | 100 MHz for <=8 cores |
| SDRAM clock | 80/100 MHz, -3ns phase | Matched to system clock |

### Address Flow

```
JOP pipeline → BmbMemoryController → BMB bus → BmbSdramCtrl32 → SDRAM
  25-bit word     (aoutAddr<<2).resized   27-bit byte   32→16 bridge    16-bit
  [24:23]=type                            [26:25]=00    2 accesses      W9825G6JH6
```

## SMP Scaling

The EP4CGX150 is the highest-capacity Cyclone IV GX available. SMP scaling
has been verified on hardware:

| Cores | Fmax | LEs | LE % | Timing | Status |
|:-----:|:----:|----:|:----:|--------|--------|
| 1 | 100 MHz | ~5,400 | 4% | +10 ns slack | Working |
| 2 | 100 MHz | ~11,000 | 7% | +6 ns slack | Working |
| 4 | 100 MHz | ~22,000 | 15% | +3 ns slack | Working |
| 8 | 100 MHz | ~44,000 | 29% | +1.9 ns slack | Working |
| 16 | 80 MHz | ~129,000 | 86% | +1.8 ns slack | Working |

At 16 cores the FPGA is near capacity. 8 cores at 100 MHz is the practical
sweet spot — comfortable resource headroom and no clock downgrade needed.

### Resource Budget (per core, approximate)

| Resource | Per Core | Shared Overhead |
|----------|:--------:|:---------------:|
| LEs | ~5,400 | ~3,000 (arbiter, CmpSync, SDRAM ctrl) |
| M9K blocks | ~3 | ~2 (SDRAM ctrl FIFOs) |
| Multipliers | 1 | 0 |

### SDRAM Bandwidth Under SMP

SDR SDRAM at 80-100 MHz with 16-bit bus:
- Peak bandwidth: 100 MHz x 2 bytes = 200 MB/s
- Effective (with row activation, refresh, 32→16 bridge overhead): ~100-130 MB/s

With N cores sharing the SDRAM through a round-robin arbiter:
- Per-core bandwidth depends on method cache, object cache, and array cache hit rates
- Cache hit rates are typically >90% for compute-bound JOP workloads
- Main SDRAM pressure comes from: BC fill (miss), GC handle scanning, memCopy

The 32→16-bit bridge means every 32-bit access requires 2 SDRAM cycles. Burst
reads (BC fill = 4 words = 8 SDRAM accesses) amortize row-activation overhead.

## Configuration and Boot

**JTAG programming**: USB-Blaster via JTAG header on core board.

**Flash boot**: EPCS128 serial configuration flash. The flash holds both the
FPGA bitstream and the JOP application binary:
- Offset 0x000000: FPGA bitstream (.rpd format)
- Offset 0x800000: JOP application (.jop binary)
- See `fpga/scripts/make_flash_image.py` for image creation

**Serial boot**: Default mode. FPGA loads via JTAG, then JOP application is
downloaded over UART at 1 Mbaud using `fpga/scripts/download.py`.

## FPGA Build

All build files are in `fpga/qmtech-ep4cgx150-sdram/`.

| Target | Command | Description |
|--------|---------|-------------|
| Single-core | `make full` | microcode + generate + build + program + download |
| SMP (2-core default) | `make full-smp` | SMP microcode + generate + build + program + download |
| DB_FPGA | `make full-dbfpga` | With Ethernet + VGA + SD card |
| Flash boot | `make full-flash-boot` | Build for autonomous flash boot |
| Flash program | `make program-flash` | Write bitstream + .jop to flash |

Timing constraints: `jop_sdram.sdc` — 50 MHz input, PLL-derived clocks,
asynchronous clock groups for Ethernet PLL and PHY RX clock.

## SignalTap Debug

The QSF includes a 96-signal SignalTap II configuration (commented out by
default). Signals include memory controller state, BMB bus handshake, SDRAM
bus, microcode PC, and Java bytecode PC. Useful for debugging SDRAM timing
issues and pipeline stalls. Enable by uncommenting the SignalTap section in
`jop_sdram.qsf`.

## Daughter Boards

The EP4CGX150 core board connects to daughter boards via dual 32x2 pin headers
(J2, J3) at 0.1" pitch.

**DB_FPGA**: See [QMTECH DB_FPGA Daughter Board](qmtech-db-fpga.md) for
peripheral details and pin assignments.

## Comparison with Other JOP Platforms

| Feature | EP4CGX150 (SDRAM) | EP4CE115 (DDR2) | XC7A35T (DDR3) |
|---------|:-----------------:|:---------------:|:--------------:|
| LEs / LUTs | 149,760 | 114,480 | 20,800 |
| Block RAM | 6,635 Kbit | 3,888 Kbit | 1,800 Kbit |
| Memory type | SDR SDRAM | DDR2 SODIMM | DDR3 |
| Memory size | 32 MB | 1 GB | 256 MB |
| Memory bus | 16-bit | 64-bit | 16-bit |
| Peak BW | 200 MB/s | 5.3 GB/s | 3.2 GB/s |
| Max JOP cores | 16 (verified) | ~12 (estimated) | 2-3 (estimated) |
| JOP Fmax | 80-100 MHz | TBD | 100 MHz |
| Status | Primary platform | Future | GC working |

The EP4CGX150's advantage is logic capacity (16-core SMP verified). The EP4CE115
and XC7A35T have far more memory bandwidth but fewer logic resources. The ideal
large-scale JOP platform would be an EP4CE115-class FPGA (or larger) with DDR2/DDR3
and the DB_FPGA daughter board for I/O.

## Reference Projects (QMTECH)

In `/srv/git/qmtech/EP4CGX150DF27_CORE_BOARD/`:

| Project | Description |
|---------|-------------|
| Project01_Test_Led | LED blink test |
| Project04_SDRAM | SDRAM read/write test |

For DB_FPGA peripheral examples (Ethernet, UART, VGA, SD card, 7-segment),
see the EP4CE15 examples in `/srv/git/qmtech/CYCLONE_IV_EP4CE15/Software/`
which use the same daughter board with different pin assignments.
