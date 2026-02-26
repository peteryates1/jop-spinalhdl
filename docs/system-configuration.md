# JOP System Configuration Reference

This document describes the configuration parameters for JOP system generation.
All configuration is via Scala case classes — no external config files are needed.

## Configuration Hierarchy

```
JopCluster                          # Top-level: N cores + arbiter + sync
├── cpuCnt: Int                     # Number of CPU cores
├── baseConfig: JopCoreConfig       # Shared core configuration (per-core overrides applied)
│   ├── memConfig: JopMemoryConfig  # Memory layout and bus parameters
│   ├── ioConfig: IoConfig          # I/O device presence and parameters
│   ├── useStackCache: Boolean      # 3-bank rotating stack cache with DMA spill/fill
│   └── useIhlu: Boolean            # Per-object hardware locking (vs global lock)
├── debugConfig: DebugConfig        # Optional on-chip debug subsystem
└── separateStackDmaBus: Boolean    # Separate BMB bus for stack DMA (test harness only)
```

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
          │    .jop program   │  Code, class data, constants
          │                   │
appEnd    ├──────────────────┤
          │   Handle area     │  handle_cnt * 8 words
          ├──────────────────┤
          │      Heap         │  Object/array data (GC managed)
          │   (grows up →)    │
mem_size  └──────────────────┘
```

With stack cache and per-core stack regions (`stackRegionWordsPerCore > 0`):

```
Word Address
0x000000  ┌──────────────────┐
          │    .jop program   │  Code, class data, constants
          │                   │
appEnd    ├──────────────────┤
          │   Handle area     │  handle_cnt * 8 words
          ├──────────────────┤
          │      Heap         │  Object/array data (GC managed)
          │   (grows up →)    │
memEnd    ├──────────────────┤  memEnd = memWords - cpuCnt * stackRegion
          │  Core N-1 stack   │  Highest-numbered core (lowest address)
          ├──────────────────┤
          │       ...         │
          ├──────────────────┤
          │  Core 1 stack     │
          ├──────────────────┤
          │  Core 0 stack     │  Core 0 at highest address
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
| `useIhlu` | Boolean | false | Use IHLU per-object lock (vs CmpSync global lock) |
| `useStackCache` | Boolean | false | Enable 3-bank rotating stack cache with DMA |
| `spillBaseAddrOverride` | Option[Int] | None | Override spill address (e.g., `Some(0)` for dedicated spill BRAM) |

### Jump Table Variants

| Variant | Use |
|---------|-----|
| `JumpTableInitData.simulation` | Simulation with embedded program (microcode + JBC pre-loaded) |
| `JumpTableInitData.serial` | FPGA serial boot (microcode starts with UART download loop) |

## JopMemoryConfig

Memory system parameters. Defined in `jop/memory/JopMemoryConfig.scala`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `dataWidth` | Int | 32 | Data path width (fixed) |
| `addressWidth` | Int | 24 | Word address width (24 = 64MB addressable) |
| `mainMemSize` | BigInt | 8MB | Main memory size in bytes |
| `scratchSize` | BigInt | 4KB | Scratchpad RAM size in bytes |
| `burstLen` | Int | 0 | DMA burst length: 0=BRAM, 4=SDR, 8=DDR3 |
| `useOcache` | Boolean | true | Enable object cache (16-entry, 8 fields) |
| `ocacheWayBits` | Int | 4 | Object cache entries (log2) |
| `ocacheIndexBits` | Int | 3 | Fields per cache entry (log2) |
| `useAcache` | Boolean | true | Enable array cache (16-entry, 4 elements/line) |
| `acacheWayBits` | Int | 4 | Array cache entries (log2) |
| `acacheFieldBits` | Int | 2 | Elements per cache line (log2) |
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
| `hasSdSpi` | Boolean | false | Instantiate BmbSdSpi (SD card via SPI) |
| `hasSdNative` | Boolean | false | Instantiate BmbSdNative (SD card native mode) |
| `hasVgaDma` | Boolean | false | Instantiate BmbVgaDma (VGA framebuffer DMA) |
| `hasVgaText` | Boolean | false | Instantiate BmbVgaText (VGA text mode) |
| `uartBaudRate` | Int | 1000000 | UART baud rate in Hz (1 Mbaud default) |

**Constraints:** SD SPI and SD Native are mutually exclusive. VGA DMA and VGA Text
are mutually exclusive.

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
| 15 | — | — | (free) |

## Board Configurations

### QMTECH EP4CGX150 — SDR SDRAM (Primary)

```scala
// In JopSdramTop.scala
JopCoreConfig(
  memConfig = JopMemoryConfig(burstLen = 4),  // SDR burst
  jumpTable = JumpTableInitData.serial,
  clkFreqHz = 80000000L,                      // 80 MHz (16-core) or 100 MHz (≤8-core)
  ioConfig = IoConfig(hasEth = ..., ...)
)
// SDRAM: W9825G6JH6, 256Mbit, CAS=3
// SMP: cpuCnt up to 16
```

### QMTECH EP4CGX150 — BRAM

```scala
// In JopBramTop.scala
JopCoreConfig(
  memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)  // 128KB on-chip
  // burstLen=0 (default), simulation jump table
)
```

### Trenz CYC5000 — SDR SDRAM

```scala
// In JopCyc5000Top.scala
JopCoreConfig(
  memConfig = JopMemoryConfig(burstLen = 0),  // No burst (pipelined single-word)
  jumpTable = JumpTableInitData.serial,
  clkFreqHz = 80000000L                       // 80 MHz
)
// SDRAM: W9864G6JT, 64Mbit, CAS=2
// SMP: cpuCnt up to 2 (resource-limited)
```

### Alchitry Au V2 — DDR3

```scala
// In JopDdr3Top.scala
JopCoreConfig(
  memConfig = JopMemoryConfig(addressWidth = 26, burstLen = burstLen),
  jumpTable = JumpTableInitData.serial,
  clkFreqHz = 100000000L                      // 100 MHz
)
// DDR3: MT41K128M16JT, 2Gbit
// Write-back cache: 32KB L2 (4-way, 512 sets)
// SMP: cpuCnt up to 2 (resource-limited on XC7A35T)
```

### Simulation Harnesses

```scala
// BRAM simulation (JopCoreTestHarness)
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

// SDRAM with stack cache (future)
JopCoreConfig(
  memConfig = JopMemoryConfig(
    mainMemSize = 32 * 1024 * 1024,        // 32MB
    burstLen = 4,
    stackRegionWordsPerCore = 8192          // 32KB per core
  ),
  useStackCache = true
)
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

### SMP Behaviour

- **cpuCnt = 1**: Direct BMB connection (no arbiter overhead)
- **cpuCnt >= 2**: Round-robin BMB arbiter + CmpSync (or IHLU) for synchronization
- Each core gets a unique `cpuId` (0 to cpuCnt-1)
- Core 0 has UART; other cores have `hasUart = false`
- Boot: Core 0 runs init; other cores wait for `IO_SIGNAL`
