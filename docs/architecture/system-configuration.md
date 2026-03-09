# JOP System Configuration Reference

This document describes the configuration parameters for JOP system generation.
All configuration is via Scala case classes — no external config files are needed.

## Configuration Hierarchy

```
JopConfig                               # Top-level: assembly + systems
├── assembly: SystemAssembly            # Physical hardware (board, FPGA, devices)
│   ├── fpgaBoard: Board                # Board with devices, pin mappings, LEDs
│   └── fpga: FpgaDevice                # FPGA chip (family, package, speed grade)
├── systems: Seq[JopSystem]             # Processor cluster(s)
│   └── JopSystem                       # A single processor system
│       ├── memory: String              # Memory device name or role ("bram" for on-chip)
│       ├── bootMode: BootMode          # Serial, Flash, or Simulation
│       ├── clkFreq: HertzNumber        # System clock frequency (after PLL)
│       ├── cpuCnt: Int                 # Number of CPU cores
│       ├── coreConfig: JopCoreConfig   # Default core configuration
│       └── drivers: Seq[DeviceDriver]  # Device drivers to instantiate
├── interconnect: Option[InterconnectConfig]  # Cross-system (multi-system only)
└── monitors: Seq[MonitorConfig]        # Hardware monitors (watchdog, etc.)

JopTop(config: JopConfig)              # Unified FPGA top-level Component
├── PLL factory                        # Board-specific PLL BlackBox
├── ResetGenerator                     # 3-bit counter gated by PLL locked
├── JopCluster                         # N cores + arbiter + CmpSync
├── MemoryControllerFactory            # BRAM / SDR / DDR3 dispatch
└── HangDetector                       # Optional diagnostic UART mux

JopCluster                             # Instantiated by JopTop
├── cpuCnt: Int                        # Number of CPU cores
├── baseConfig: JopCoreConfig          # Shared core configuration
│   ├── memConfig: JopMemoryConfig     # Memory layout and bus parameters
│   ├── ioConfig: IoConfig             # I/O device presence and parameters
│   ├── useStackCache: Boolean         # 3-bank rotating stack cache with DMA spill/fill
│   └── useIhlu: Boolean               # Per-object hardware locking (vs global lock)
├── debugConfig: DebugConfig           # Optional on-chip debug subsystem
└── separateStackDmaBus: Boolean       # Separate BMB bus for stack DMA (test harness only)
```

### Verilog Generation

All Verilog is generated through `JopTopVerilog` with named presets:

```bash
sbt "runMain jop.system.JopTopVerilog <preset> [args]"
```

| Preset | Board | Memory | Entity Name |
|--------|-------|--------|-------------|
| `ep4cgx150Serial` | QMTECH EP4CGX150 | SDR SDRAM | `JopSdramTop` |
| `ep4cgx150Smp N` | QMTECH EP4CGX150 | SDR SDRAM | `JopSmpSdramTop` |
| `ep4cgx150HwMath` | QMTECH EP4CGX150 | SDR SDRAM | `JopSdramTop` |
| `ep4cgx150HwFloat` | QMTECH EP4CGX150 | SDR SDRAM | `JopSdramTop` |
| `cyc5000Serial` | Trenz CYC5000 | SDR SDRAM | `JopCyc5000Top` |
| `auSerial` | Alchitry Au V2 | DDR3 | `JopDdr3Top` |
| `wukongSdram` | Wukong XC7A100T | SDR SDRAM | `JopSdramWukongTop` |
| `wukongDdr3` | Wukong XC7A100T | DDR3 | `JopDdr3WukongTop` |
| `wukongBram` | Wukong XC7A100T | BRAM | `JopBramWukongTop` |
| `minimum` | QMTECH EP4CGX150 | SDR SDRAM | `JopSdramTop` |

Entity names are backward-compatible with existing Quartus/Vivado projects.

## Memory Layout

### Physical Address Space

JOP uses a 32-bit address space partitioned by the top 2 bits:

| Bits [31:30] | Range | Use |
|:---:|---|---|
| `00` | `0x00000000`–`0x3FFFFFFF` | Main memory (BRAM / SDRAM / DDR3) |
| `01` | `0x40000000`–`0x7FFFFFFF` | Reserved |
| `10` | `0x80000000`–`0xBFFFFFFF` | Scratchpad RAM (4KB) |
| `11` | `0xC0000000`–`0xFFFFFFFF` | I/O space |

### Main Memory Map

Without stack cache (`useStackCache = false`):

```
Word Address
0x000000  ┌──────────────────┐
          │   .jop program   │  Code, class data, constants
          │                  │
appEnd    ├──────────────────┤
          │  Handle area     │  handle_cnt * 8 words
          ├──────────────────┤
          │     Heap         │  Object/array data (GC managed)
          │  (grows up →)    │
mem_size  └──────────────────┘
```

With stack cache and per-core stack regions (`stackRegionWordsPerCore > 0`):

```
Word Address
0x000000  ┌──────────────────┐
          │   .jop program   │  Code, class data, constants
          │                  │
appEnd    ├──────────────────┤
          │   Handle area    │  handle_cnt * 8 words
          ├──────────────────┤
          │       Heap       │  Object/array data (GC managed)
          │   (grows up →)   │
memEnd    ├──────────────────┤  memEnd = memWords - cpuCnt * stackRegion
          │  Core N-1 stack  │  Highest-numbered core (lowest address)
          ├──────────────────┤
          │       ...        │
          ├──────────────────┤
          │  Core 1 stack    │
          ├──────────────────┤
          │  Core 0 stack    │  Core 0 at highest address
memWords  └──────────────────┘  Top of physical memory
```

The Java runtime reads `IO_MEM_SIZE` (BmbSys register 14) to determine `memEnd`.
When `IO_MEM_SIZE` returns 0, the legacy heap size (`appEnd + 16384`) is used.

### Stack Cache Spill Addressing

Each core's stack spill region starts at `spillBaseAddr` and grows upward:

```
spillBaseAddr = memWords - (cpuId + 1) * stackRegionWordsPerCore
```

Within the region, bank data is written at offset `(bankBase - scratchSize)` words
from `spillBaseAddr`. The 3-bank rotating cache spills/fills individual 192-word
banks via DMA as the virtual stack pointer crosses bank boundaries.

## JopCoreConfig

Unified configuration for a single JOP core. Defined in `jop/system/JopCore.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `dataWidth` | Int | 32 | Data path width (fixed) |
| `pcWidth` | Int | 11 | Microcode PC width (2K ROM) |
| `instrWidth` | Int | 10 | Microcode instruction width |
| `jpcWidth` | Int | 11 | Java PC width (2KB method cache) |
| `ramWidth` | Int | 8 | Stack RAM address width (256 entries) |
| `blockBits` | Int | 4 | Method cache block bits (16 blocks) |
| `memConfig` | JopMemoryConfig | default | Memory system configuration |
| `jumpTable` | JumpTableInitData | simulation | Bytecode-to-microcode jump table |
| `cpuId` | Int | 0 | CPU identifier (set per-core by JopCluster) |
| `cpuCnt` | Int | 1 | Total CPU count (set by JopCluster) |
| `ioConfig` | IoConfig | default | I/O device configuration |
| `clkFreqHz` | Long | 100000000 | Clock frequency in Hz |
| `fpuMode` | FpuMode | Software | Floating-point mode: `Software` (SoftFloat) or `Hardware` (HW FPU) |
| `useDspMul` | Boolean | false | DSP multiply (1-cycle 32×32→64) |
| `useHwDiv` | Boolean | false | Hardware integer divider (BmbDiv) |
| `useIhlu` | Boolean | false | Use IHLU per-object lock (vs CmpSync global lock) |
| `useStackCache` | Boolean | false | Enable 3-bank rotating stack cache with DMA |
| `spillBaseAddrOverride` | Option[Int] | None | Override spill address (e.g., `Some(0)` for dedicated spill BRAM) |

### FpuMode

| Value | Description |
|-------|-------------|
| `FpuMode.Software` | All float operations handled by SoftFloat32 in Java (default) |
| `FpuMode.Hardware` | fadd/fsub/fmul/fdiv use HW FPU via microcode I/O; fneg/fcmp/frem/f2i stay in Java. Double operations always use SoftFloat64. |

The convenience method `config.hasFpu` returns `true` when `fpuMode == Hardware`.
Use `config.withFpuJumpTable` to auto-select the matching FPU jump table variant.
Use `config.withMathJumpTable` to auto-select the matching DSP/DIV jump table variant.
When composing, call `.withFpuJumpTable` first, then `.withMathJumpTable`.

### Jump Table Variants

| Variant | Use |
|---------|-----|
| `JumpTableInitData.simulation` | Simulation with embedded program (microcode + JBC pre-loaded) |
| `JumpTableInitData.serial` | FPGA serial boot (microcode starts with UART download loop) |
| `JumpTableInitData.flash` | FPGA flash boot (microcode starts with SPI flash download loop) |
| `JumpTableInitData.simulationFpu` | Simulation with FPU-enabled microcode (float ops → HW FPU handlers) |
| `JumpTableInitData.serialFpu` | FPGA serial boot with FPU-enabled microcode |
| `JumpTableInitData.simulationDsp` | Simulation with DSP multiply microcode |
| `JumpTableInitData.serialDsp` | FPGA serial boot with DSP multiply microcode |
| `JumpTableInitData.simulationDiv` | Simulation with HW divider microcode |
| `JumpTableInitData.serialDiv` | FPGA serial boot with HW divider microcode |
| `JumpTableInitData.simulationHwMath` | Simulation with DSP multiply + HW divider microcode |
| `JumpTableInitData.serialHwMath` | FPGA serial boot with DSP multiply + HW divider microcode |

## JopMemoryConfig

Memory system parameters. Defined in `jop/memory/JopMemoryConfig.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `dataWidth` | Int | 32 | Data path width (fixed) |
| `addressWidth` | Int | 24 | Word address width including 2 type bits (24 = 64MB, 28 max = 256MB) |
| `mainMemSize` | BigInt | 8MB | Main memory size in bytes |
| `scratchSize` | BigInt | 4KB | Scratchpad RAM size in bytes |
| `burstLen` | Int | 0 | DMA burst length: 0=BRAM, 4=SDR, 8=DDR3 |
| `useOcache` | Boolean | true | Enable object cache (16-entry, 8 fields) |
| `ocacheWayBits` | Int | 4 | Object cache entries (log2) |
| `ocacheIndexBits` | Int | 3 | Fields per cache entry (log2) |
| `ocacheMaxIndexBits` | Int | 8 | Max field index (256 fields/object) |
| `useAcache` | Boolean | true | Enable array cache (16-entry, 4 elements/line) |
| `acacheWayBits` | Int | 4 | Array cache entries (log2) |
| `acacheFieldBits` | Int | 2 | Elements per cache line (log2) |
| `acacheMaxIndexBits` | Int | 24 | Max array index width |
| `stackRegionWordsPerCore` | Int | 0 | Per-core stack region size in words (0 = legacy) |

### burstLen Values

| Value | Memory Type | Description |
|:---:|---|---|
| 0 | BRAM | Pipelined single-word reads (no burst) |
| 4 | SDR SDRAM | 4-word burst (16 bytes) |
| 8 | DDR3 | 8-word burst (32 bytes) |

**Note:** `burstLen=0` is incompatible with SMP (`cpuCnt > 1`) due to BMB arbiter
interleaving during BC_FILL. SMP requires `burstLen >= 4`.

### stackRegionWordsPerCore

When set to a non-zero value, each core gets a dedicated stack spill region at the
top of main memory. The usable memory for heap is reduced accordingly. The value is
exposed to the Java runtime via the `IO_MEM_SIZE` I/O register.

Recommended values:
- **8192** (32KB) — sufficient for most Java applications
- **16384** (64KB) — deep recursion or large stack frames

## StackCacheConfig

Stack cache parameters (active when `useStackCache = true`).
Defined in `jop/pipeline/StackStage.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `numBanks` | Int | 3 | Bank count (fixed: active + ready + anti-thrash) |
| `bankSize` | Int | 192 | Usable entries per bank |
| `scratchSize` | Int | 64 | Scratch area size (addresses 0-63, never rotated) |
| `virtualSpWidth` | Int | 16 | Virtual SP/VP/AR register width |
| `spillBaseAddr` | Int | 0x780000 | Spill area base word address (auto-computed) |
| `burstLen` | Int | 4 | DMA burst length (inherited from JopMemoryConfig) |
| `wordAddrWidth` | Int | 24 | Word address width |

The `spillBaseAddr` is normally computed automatically by `JopCoreConfig`:
- **stackRegionWordsPerCore > 0**: `memWords - (cpuId + 1) * stackRegionWordsPerCore`
- **Legacy BRAM** (burstLen=0): `memWords * 3/4 + cpuId * 4096`
- **Legacy SDRAM/DDR3**: `0x780000 + cpuId * 0x8000`

## IoConfig

I/O device presence and parameters. Defined in `jop/system/IoConfig.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `hasUart` | Boolean | true | Instantiate BmbUart (TX/RX with 16-entry FIFOs) |
| `hasEth` | Boolean | false | Instantiate BmbEth + BmbMdio (Ethernet MAC) |
| `ethGmii` | Boolean | false | 8-bit GMII (1Gbps) vs 4-bit MII (100Mbps) |
| `hasSdSpi` | Boolean | false | Instantiate BmbSdSpi (SD card via SPI) |
| `hasSdNative` | Boolean | false | Instantiate BmbSdNative (SD card native mode) |
| `hasVgaDma` | Boolean | false | Instantiate BmbVgaDma (VGA framebuffer DMA) |
| `hasVgaText` | Boolean | false | Instantiate BmbVgaText (VGA text mode) |
| `hasConfigFlash` | Boolean | false | Instantiate BmbCfgFlash (W25Q128 SPI flash boot) |
| `uartBaudRate` | Int | 1000000 | UART baud rate in Hz (1 Mbaud default) |
| `mdioClkDivider` | Int | 40 | MDIO clock divider |
| `sdSpiClkDivInit` | Int | 199 | SD SPI init clock divider (~200 kHz @ 80MHz) |
| `sdNativeClkDivInit` | Int | 99 | SD Native init clock divider (~400 kHz @ 80MHz) |
| `vgaDmaFifoDepth` | Int | 512 | VGA DMA CDC FIFO depth in 32-bit words |
| `cfgFlashClkDivInit` | Int | 3 | Config flash SPI clock divider (~10 MHz @ 80MHz) |

**Constraints:**
- `!(hasSdSpi && hasSdNative)` — SD SPI and SD Native are mutually exclusive (share pins)
- `!(hasVgaDma && hasVgaText)` — VGA DMA and VGA Text are mutually exclusive (share pins)
- `!ethGmii || hasEth` — GMII requires Ethernet enabled

### IoConfig Presets

| Preset | Devices |
|--------|---------|
| `IoConfig.minimal` | UART only |
| `IoConfig.qmtechSdram` | UART + Ethernet (MII) + Config Flash |
| `IoConfig.qmtechDbFpga` | UART + Ethernet (GMII 1Gbps) + SD Native + VGA Text |
| `IoConfig.qmtechDbFpgaVgaDma` | UART + Ethernet (GMII 1Gbps) + SD Native + VGA DMA |

## I/O Address Map

8-bit `ioAddr` derived from bipush range `0x80`–`0xFF`:

| ioAddr Range | Peripheral | Slots |
|:---:|---|:---:|
| `0x80–0x8F` | BmbSys (system registers) | 4 |
| `0x90–0x93` | BmbUart | 1 |
| `0x98–0x9F` | BmbEth | 2 |
| `0xA0–0xA7` | BmbMdio | 2 |
| `0xA8–0xAB` | BmbSdSpi | 1 |
| `0xAC–0xAF` | BmbVgaDma | 1 |
| `0xB0–0xBF` | BmbSdNative | 4 |
| `0xC0–0xCF` | BmbVgaText | 4 |
| `0xD0–0xD3` | BmbCfgFlash | 1 |
| `0xE0–0xE3` | BmbDiv | 1 |
| `0xF0–0xF3` | BmbFpu | 1 |

## DebugConfig

On-chip debug subsystem. Defined in `jop/debug/DebugConfig.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `numBreakpoints` | Int | 4 | Hardware breakpoint slots per core (0-8) |
| `baudRate` | Int | 1000000 | Debug UART baud rate in Hz |
| `hasMemAccess` | Boolean | true | Enable BMB master for memory access |

## CacheConfig (DDR3 L2)

L2 write-back cache for DDR3 memory path. Defined in `jop/ddr3/CacheConfig.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `addrWidth` | Int | 28 | Address width in bits |
| `dataWidth` | Int | 128 | Cache line width in bits (must be byte-aligned) |
| `setCount` | Int | 256 | Cache sets (must be power-of-2) |
| `wayCount` | Int | 4 | Associativity (1, 2, or 4 way only) |

## BmbSys I/O Register Map

System I/O device at base address `0xFFFFFF80` (`Const.IO_BASE`).
4-bit sub-address (registers 0-15).

| Addr | Read | Write | Java Constant |
|:---:|---|---|---|
| 0 | Clock cycle counter | Interrupt enable | `IO_CNT` / `IO_INT_ENA` |
| 1 | Microsecond counter | Timer compare value | `IO_US_CNT` / `IO_TIMER` |
| 2 | Interrupt source number | SW interrupt trigger | `IO_INTNR` / `IO_SWINT` |
| 3 | — | Watchdog | `IO_WD` |
| 4 | Exception type | Exception trigger | `IO_EXCPT` |
| 5 | Lock status (halted, error) | Lock acquire | `IO_LOCK` |
| 6 | CPU ID | Lock release | `IO_CPU_ID` / `IO_UNLOCK` |
| 7 | Signal (boot sync) | Signal set | `IO_SIGNAL` |
| 8 | — | Interrupt mask | `IO_INTMASK` |
| 9 | — | Clear all interrupts | `IO_INTCLEARALL` |
| 10 | — | Deadline | `IO_DEADLINE` |
| 11 | CPU count | — | `IO_CPUCNT` |
| 12 | — | Performance counter reset | `IO_PERFCNT` |
| 13 | — | GC halt (freeze other cores) | `IO_GC_HALT` |
| 14 | Usable memory end (words) | — | `IO_MEM_SIZE` |
| 15 | FPU capability (1=present) | — | `IO_FPU_CAP` |

## Board Configurations (JopConfig Presets)

All board configurations are defined as `JopConfig` presets in `jop/config/JopConfig.scala`.
The unified `JopTop(config)` component handles all boards — no per-board top files needed.

### QMTECH EP4CGX150 — SDR SDRAM (Primary)

```scala
JopConfig.ep4cgx150Serial
// JopConfig(
//   assembly = SystemAssembly.qmtechWithDb,
//   systems = Seq(JopSystem(
//     name = "main", memory = "W9825G6JH6", bootMode = BootMode.Serial,
//     clkFreq = 80 MHz,
//     drivers = Seq(DeviceDriver.Uart, DeviceDriver.EthGmii, DeviceDriver.SdNative))))
// SDRAM: W9825G6JH6, 256Mbit, CAS=3
// SMP: JopConfig.ep4cgx150Smp(n) for 2-16 cores
```

### Trenz CYC5000 — SDR SDRAM

```scala
JopConfig.cyc5000Serial
// JopConfig(
//   assembly = SystemAssembly.cyc5000,
//   systems = Seq(JopSystem(
//     name = "main", memory = "W9864G6JT", bootMode = BootMode.Serial,
//     clkFreq = 100 MHz, drivers = Seq(DeviceDriver.UartFt2232))))
// SDRAM: W9864G6JT, 64Mbit, CAS=2
```

### Alchitry Au V2 — DDR3

```scala
JopConfig.auSerial
// JopConfig(
//   assembly = SystemAssembly.alchitryAuV2,
//   systems = Seq(JopSystem(
//     name = "main", memory = "MT41K128M16JT-125:K", bootMode = BootMode.Serial,
//     clkFreq = 250/3 MHz, drivers = Seq(DeviceDriver.UartFt2232))))
// DDR3: MT41K128M16JT, 2Gbit (256MB)
// Write-back cache: 32KB L2 (4-way, 512 sets)
// GC: MAX_HANDLES=65536 caps handle table for large memory
```

### QMTECH XC7A100T Wukong V3 — DDR3 / SDR / BRAM

```scala
JopConfig.wukongDdr3     // DDR3 via MIG, WukongMigBlackBox (no CS pin)
JopConfig.wukongSdram    // SDR SDRAM via SdramCtrlNoCke
JopConfig.wukongBram     // On-chip BRAM (simulation boot mode)
// All use SystemAssembly.wukong with DeviceDriver.UartCh340
```

### Hardware Math Variants

```scala
JopConfig.ep4cgx150HwMath   // IntegerComputeUnit: idiv + irem in hardware
JopConfig.ep4cgx150HwFloat  // FloatComputeUnit: fadd/fsub/fmul/fdiv/i2f/f2i/fcmpl/fcmpg
```

### Minimum Resources

```scala
JopConfig.minimum  // Pure microcode imul, Java idiv/irem, UART only
```

### Simulation Harnesses

```scala
// Unified JopTop BRAM simulation (JopTopBramSim)
JopTop(
  config = JopConfig.wukongBram,
  romInit = romData, ramInit = ramData,
  mainMemInit = Some(mainMemData), mainMemSize = 256 * 1024,
  simulation = true  // Bypasses PLL/reset/MIG for Verilator
)

// Legacy BRAM simulation (JopCoreTestHarness — still works)
JopCoreConfig(
  memConfig = JopMemoryConfig(mainMemSize = 256 * 1024)  // 256KB
)

// Stack cache simulation (JopStackCacheTestHarness)
JopCoreConfig(
  memConfig = JopMemoryConfig(mainMemSize = 512 * 1024),
  useStackCache = true,
  spillBaseAddrOverride = Some(0)  // Dedicated spill BRAM at address 0
)
// Uses separateStackDmaBus=true with 64KB dedicated spill BRAM
```

## JopCluster

System-level integration. Defined in `jop/system/JopCluster.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `cpuCnt` | Int | (required) | Number of CPU cores (1 = single-core, 2+ = SMP) |
| `baseConfig` | JopCoreConfig | (required) | Base core config (cpuId/cpuCnt overridden per core) |
| `debugConfig` | Option[DebugConfig] | None | Debug subsystem (None = no debug, zero cost) |
| `romInit` | Option[Seq[BigInt]] | None | Microcode ROM initialization |
| `ramInit` | Option[Seq[BigInt]] | None | Stack RAM initialization |
| `jbcInit` | Option[Seq[BigInt]] | None | JBC RAM initialization |
| `separateStackDmaBus` | Boolean | false | Route stack DMA to separate BMB port |
| `perCoreUart` | Boolean | false | Per-core UART TX pins (SMP debug) |
| `perCoreConfigs` | Option[Seq[JopCoreConfig]] | None | Per-core config overrides |

### SMP Behaviour

- **cpuCnt = 1**: Direct BMB connection (no arbiter overhead)
- **cpuCnt >= 2**: Round-robin BMB arbiter + CmpSync (or IHLU) for synchronization
- Each core gets a unique `cpuId` (0 to cpuCnt-1)
- Core 0 has UART; other cores have `hasUart = false`
- Boot: Core 0 runs init; other cores wait for `IO_SIGNAL`
