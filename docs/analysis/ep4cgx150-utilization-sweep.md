# EP4CGX150 Feature Utilization Sweep

Date: 2026-03-11
Platform: QMTECH EP4CGX150 Core Board + DB_FPGA V4 Daughter Board
FPGA: Altera Cyclone IV GX EP4CGX150DF27I7 (149,760 LEs, 6,635,520 memory bits, 360 embedded multiplier 9-bit elements)
Tool: Quartus Prime 25.1std Lite Edition, quartus_map (Analysis & Synthesis only)
Memory: SDR SDRAM (W9825G6JH6, 32 MB) via Altera triple-port SDRAM controller

## Methodology

Each variant starts from a bare SDR SDRAM + UART baseline (idiv/irem in HW,
everything else off) and enables one feature at a time. All variants share the
same PLL (dram_pll), Altera SDRAM controller, and base JOP core infrastructure.
Analysis & Synthesis (quartus_map) utilization is reported — no fitting or
place-and-route.

Source: `spinalhdl/src/main/scala/jop/system/AlteraUtilSweep.scala`
Script: `fpga/qmtech-ep4cgx150-sdram/util_sweep.sh`
Reports: `fpga/qmtech-ep4cgx150-sdram/output_files/util_sweep/*/output_files/util_sweep.map.summary`

## Results: Absolute Utilization

| Config | LEs | Comb | Regs | Mem bits | Mult9 | LE% |
|--------|----:|-----:|-----:|---------:|------:|----:|
| No ICU (no HW integer) | 9,369 | 7,775 | 3,603 | 30,976 | 0 | 6.3% |
| No A$ (idiv/irem HW, no array cache) | 8,400 | 7,478 | 2,968 | 28,928 | 0 | 5.6% |
| Baseline (idiv/irem HW) | 9,969 | 8,311 | 3,841 | 30,976 | 0 | 6.7% |
| + imul iterative | 10,361 | 8,703 | 4,040 | 30,976 | 0 | 6.9% |
| + imul DSP | 10,504 | 8,828 | 4,105 | 30,976 | 8 | 7.0% |
| + FCU (9 float ops) | 12,482 | 10,802 | 4,325 | 30,976 | 7 | 8.3% |
| + LCU (8 long ops) | 11,355 | 9,729 | 4,040 | 30,976 | 0 | 7.6% |
| + DCU (12 double ops) | 16,780 | 15,040 | 4,894 | 30,976 | 18 | 11.2% |
| All 4 CUs + DSP imul | 21,279 | 19,531 | 5,843 | 30,976 | 33 | 14.2% |
| + Ethernet GMII | 11,136 | 9,241 | 4,451 | 63,744 | 0 | 7.4% |
| + SD Native | 18,236 | 12,455 | 8,571 | 30,976 | 0 | 12.2% |
| + SD SPI | 10,111 | 8,439 | 3,904 | 30,976 | 0 | 6.7% |
| + VGA Text | 11,542 | 9,098 | 4,791 | 140,544 | 0 | 7.7% |
| + VGA DMA | 10,566 | 8,798 | 4,074 | 47,360 | 0 | 7.1% |
| + Ethernet + SD Native | 19,381 | 13,362 | 9,180 | 63,744 | 0 | 12.9% |
| + Ethernet + SD SPI | 11,272 | 9,362 | 4,514 | 63,744 | 0 | 7.5% |
| **Full** (all CUs + Eth + SD + VGA) | **32,360** | **25,459** | **12,134** | **173,312** | **33** | **21.6%** |

## Results: Per-Feature Marginal Cost

Delta measured from the "No ICU" baseline (9,369 LEs).

| Feature | LEs | Comb | Regs | Mult9 | Notes |
|---------|----:|-----:|-----:|------:|-------|
| **ICU** (idiv/irem) | +600 | +536 | +238 | 0 | Binary restoring divider |
| **ICU** (+ imul iterative) | +992 | +928 | +437 | 0 | Radix-4 Booth multiplier (~18 cycles) |
| **ICU** (+ imul DSP) | +1,135 | +1,053 | +502 | +8 | DSP multiply, 2-cycle latency |
| **FCU** (9 float ops) | +3,113 | +3,027 | +722 | +7 | IEEE 754 single: add/sub/mul/div/cmp/cvt |
| **LCU** (8 long ops) | +1,986 | +1,954 | +437 | 0 | 64-bit ALU + barrel shifter + lmul |
| **DCU** (12 double ops) | +7,411 | +7,265 | +1,291 | +18 | IEEE 754 double: add/sub/mul/div/cmp/cvt |
| **All 4 CUs + DSP** | +11,910 | +11,756 | +2,240 | +33 | Combined (less than sum — shared operand stack) |
| **Ethernet GMII** | +1,167 | +930 | +610 | 0 | RTL8211EG PHY + MDIO + CDC + 32 KB TX/RX buffers |
| **SD Native** | +8,867 | +4,680 | +4,968 | 0 | SpinalHDL SdcardCtrl (4-bit, cmd/data FSMs) |
| **SD SPI** | +742 | +664 | +301 | 0 | Simple SPI shift register + clock divider |
| **VGA Text** | +2,173 | +1,323 | +1,188 | 0 | Text-mode VGA (character ROM in block RAM) |
| **VGA DMA** | +1,197 | +1,023 | +471 | 0 | DMA-driven VGA (framebuffer in main memory) |
| **Array cache** | +1,569 | +833 | +873 | 0 | 16-entry FA, 4 elements/line (delta: baseline - no_acache) |

### Observations

1. **SD Native vs SD SPI**: SD Native costs 8,867 LEs — **12x more** than SD SPI
   (742 LEs). The SpinalHDL `SdcardCtrl` implements a full 4-bit native SD
   protocol engine with command/data FSMs, CRC generation, and bus width
   negotiation. BmbSdSpi is a bare SPI shift register — the SD protocol is
   handled entirely in software. For resource-constrained builds, SD SPI is the
   clear winner (at the cost of ~4x lower throughput: SPI = 1-bit vs native =
   4-bit).

2. **DCU is the most expensive CU**: Double-precision IEEE 754 at 7,411 LEs is
   ~2.4x the cost of FCU (single-precision, 3,113 LEs). The 52-bit mantissa
   multiplier uses 18 embedded multiplier 9-bit elements, and the wider datapath
   drives up combinational logic.

3. **All CUs are subadditive**: Sum of individual CU costs =
   600 + 3,113 + 1,986 + 7,411 = 13,110 LEs. Actual combined = 11,910 LEs.
   Savings of ~1,200 LEs from the shared ComputeUnitTop operand stack and
   result MUX.

4. **Ethernet is cheap**: The GMII PHY interface adds 1,167 LEs — mostly CDC
   synchronizers, MDIO management, and MAC TX/RX buffers (32 KB in block RAM,
   visible as +32,768 memory bits). The PHY itself (RTL8211EG) handles the
   heavy lifting.

5. **VGA DMA < VGA Text**: DMA-driven VGA (1,197 LEs) is cheaper than text-mode
   VGA (2,173 LEs) because the text engine includes a character generator ROM
   and attribute decoder. VGA Text uses 140,544 memory bits (character + font
   ROM in block RAM) vs VGA DMA's 47,360 (CDC FIFO only).

6. **EP4CGX150 has massive headroom**: At 149,760 LEs, the full build (all CUs +
   Eth + SD Native + VGA Text) uses only 21.6% of the device. SMP scaling is
   limited by SDRAM bandwidth, not LUT capacity.

## Cross-Platform Comparison (EP4CGX150 vs XC7A100T)

Both platforms use the same SpinalHDL source with different memory backends (SDR
SDRAM vs DDR3). Altera reports logic elements (LEs), Xilinx reports LUTs —
not directly comparable, but ratios reveal architectural differences.

| Feature | EP4CGX150 LEs | XC7A100T LUTs | Ratio (LE/LUT) |
|---------|-------------:|-------------:|------:|
| ICU (idiv/irem) | +600 | +405 | 1.48 |
| ICU (+ imul iter) | +992 | +705 | 1.41 |
| FCU (9 float ops) | +3,113 | +1,788 | 1.74 |
| LCU (8 long ops) | +1,986 | +1,452 | 1.37 |
| DCU (12 double ops) | +7,411 | +4,961 | 1.49 |
| All 4 CUs + DSP | +11,910 | +7,586 | 1.57 |
| Ethernet GMII | +1,167 | +969 | 1.20 |
| SD Native | +8,867 | +12,220 | 0.73 |
| SD SPI | +742 | +423 | 1.75 |
| Array cache | +1,569 | +506 | 3.10 |

Average LE/LUT ratio for compute units: ~1.5x (expected — Cyclone IV 4-input
LUTs vs Artix-7 6-input LUTs). SD Native is the notable exception at 0.73x,
suggesting the SpinalHDL SdcardCtrl maps more efficiently to Altera architecture
(likely the register-heavy FSMs benefit from Cyclone IV's LE structure).

## SMP Implications

Per additional core (with all CUs, no Ethernet/SD): approximately **4,600 LEs**
on EP4CGX150 (scaled from XC7A100T's ~7,200 LUTs at the 1.57x ratio, but
actual measurement needed).

Estimated max core counts at 85% LE budget (127,296 LEs), SDR SDRAM + all CUs:

| Cores | Est. LEs | LE% | Feasibility |
|------:|----------:|-----:|-------------|
| 1 | 21,279 | 14.2% | Comfortable |
| 4 | ~35,000 | 23.4% | Comfortable |
| 8 | ~53,000 | 35.4% | Comfortable |
| 16 | ~89,000 | 59.4% | Feasible (verified at 80 MHz) |
| 24 | ~126,000 | 84.1% | Tight |

## Comparison with Original VHDL JOP

The original VHDL JOP (from jopmin) was compiled for the same EP4CGX150 device
using Quartus 25.1 quartus_map. The VHDL 1-core build includes: SDRAM
controller, UART, SPI SD card, 7-segment display, LED/switch mux, and IHLU.
Ethernet pins are declared at the top level but were never implemented (no
working Ethernet MAC/PHY driver in jopmin).

The VHDL default uses larger caches than SpinalHDL: jpc_width=14 (16KB method
cache, 32 blocks) and OCACHE_WAY_BITS=5 (32-entry object cache). SpinalHDL
defaults to jpc_width=11 (2KB, 16 blocks) and wayBits=4 (16-entry). The
"matched caches" rows below use the same cache sizes as SpinalHDL. The VHDL JOP
has no array cache wired into mem_sc in the current source. SpinalHDL's array
cache costs 1,569 LEs on Altera (506 LUTs on Xilinx) — see the "No A$" row
above for the baseline without it.

| Design | LEs | Comb | Regs | Mem bits |
|--------|----:|-----:|-----:|---------:|
| VHDL JOP (1-core, original caches) | 9,608 | 6,767 | 4,734 | 173,056 |
| VHDL JOP (1-core, matched caches) | 7,950 | 5,648 | 3,869 | 54,272 |
| VHDL JOP (3-core, original caches) | 22,844 | 17,087 | 10,498 | 519,360 |
| VHDL JOP (3-core, matched caches) | 17,901 | 13,761 | 7,903 | 163,008 |
| SpinalHDL (1-core, UART only) | 9,969 | 8,311 | 3,841 | 30,976 |
| SpinalHDL (1-core, no A$) | 8,400 | 7,478 | 2,968 | 28,928 |
| SpinalHDL (1-core, comparable I/O) | 10,111 | 8,439 | 3,904 | 30,976 |

Notes:
- "Comparable I/O" = SpinalHDL sd_spi (SD SPI + UART) — the closest match to
  the VHDL build's I/O (which uses SPI-mode SD). The VHDL build also has 7-seg
  and LED/switch mux not present in SpinalHDL.
- With matched caches and no array cache (apples-to-apples), SpinalHDL uses
  ~6% more LEs (8,400 vs 7,950). The remaining gap is BMB bus infrastructure
  and the SpinalHDL memory controller's more combinational style.
- SpinalHDL uses far fewer memory bits: 31K vs 54K (matched) or 173K
  (original). The VHDL SDRAM controller uses block RAM buffers that SpinalHDL's
  Altera controller avoids.
- **Per-core cost (original caches)**: (22,844 - 9,608) / 2 = **6,618
  LEs/core**.
- **Per-core cost (matched caches)**: (17,901 - 7,950) / 2 = **4,976
  LEs/core** — much closer to SpinalHDL's estimated ~4,600 LEs/core. The gap
  was largely cache overhead.
- In the VHDL design, only core 0 has the full `scio` entity (UART, SPI SD,
  7-seg, LED/switch mux). Additional cores get only `sc_sys` (timer, interrupt,
  watchdog, sync). The arbiter, memory controller, and IHLU are shared. So the
  per-core figure is pure CPU cost: `jopcpu` pipeline + method cache + object
  cache + stack + mul + shift + `sc_sys`.

## Presets and Build Commands

```bash
# Generate Verilog for a single sweep variant
sbt "runMain jop.system.AlteraUtilSweep <label>"

# Run full sweep (all 16 variants)
cd fpga/qmtech-ep4cgx150-sdram && ./util_sweep.sh

# Run specific variants
cd fpga/qmtech-ep4cgx150-sdram && ./util_sweep.sh baseline fcu sd_spi
```
