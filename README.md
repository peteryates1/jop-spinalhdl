# JOP - Java Optimized Processor (SpinalHDL)

A complete reimplementation of the [Java Optimized Processor](https://github.com/jop-devel/jop) (JOP) in [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/). JOP is a hardware implementation of the Java Virtual Machine as a soft-core processor for FPGAs, originally developed by Martin Schoeberl. See [jopdesign.com](https://www.jopdesign.com/) for the original project.

This port runs Java programs on FPGA hardware. The primary development platform is the **QMTECH EP4CGX150** (Altera Cyclone IV GX + SDR SDRAM), which supports single-core and SMP (up to 16-core) configurations with stable garbage collection.

Built with [Claude Code](https://code.claude.com/docs/en/quickstart).

## Status

**Working on hardware.** The processor boots and runs Java programs at 100 MHz:

- **SDRAM + SMP (primary)**: up to 16-core SMP on QMTECH EP4CGX150 (Cyclone IV) and Trenz CYC5000 (Cyclone V) — all cores running independently with CmpSync global lock (or optional IHLU per-object locking), round-robin BMB arbitration, and GC stop-the-world halt (halts all other cores during garbage collection)
- **SDRAM (single-core)**: Serial boot over UART into SDR SDRAM on two boards — QMTECH EP4CGX150 (Cyclone IV) and Trenz CYC5000 (Cyclone V, W9864G6JT)
- **BRAM**: Self-contained, program embedded in block RAM (QMTECH EP4CGX150)
- **DDR3**: Serial boot through write-back cache into DDR3 (Alchitry Au V2, Xilinx Artix-7, full 256MB addressed) — single-core and 2-core SMP verified on hardware with GC (67K+ rounds single-core, NCoreHelloWorld SMP). See [DDR3 notes](docs/gc/ddr3-gc-hang.md).
- **GC support**: Automatic garbage collection with hardware-accelerated object copying (`memCopy`), MAX_HANDLES cap (65536) for large memories. Tested 98,000+ rounds (BRAM), 9,800+ rounds (CYC5000 SDRAM), 2,000+ rounds (QMTECH SDRAM), 67,000+ rounds (DDR3 8MB), 1,870+ rounds (DDR3 256MB)

## Project Goals

- Port JOP from VHDL to SpinalHDL/Scala for modern tooling and configurability
- Maintain cycle-accurate compatibility with the original implementation
- Target multiple FPGA boards with configurable system generation
- Upgrade Java target from JDK 1.5/1.6 to modern versions (future)

## Architecture

### Single-core
```
 ┌──────────────┐                    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 │   bytecode   │                    │  microcode   │    │  microcode   │    │  microcode   │
 │    fetch     ├─────────┬─────────▶│    fetch     ├─┬─▶│   decode     ├───▶│   execute    │◀─┐
 │  translate   │         │          │              │ |  │              │    │  (tos/nos)   │  |
 └──────┬───────┘         │          └──────┬───────┘ |  └──────────────┘    └──────┬───────┘  |
        │                 │                 │         |                       spill & fill     |
┌───────┼────────┐        │                 │         |                             |          |
│┌──────┴───────┐│ ┌──────┴───────┐  ┌──────┴───────┐ |  ┌──────────────┐    ┌──────┴───────┐  |
│| method cache ││ |  jump tbl    │  │microcode rom │ └──│ Address Gen  ├───▶│ stack buffer │  |
││ object cache ││ └──────────────┘  └──────────────┘    └──────────────┘    └──────────────┘  |
│| array cache  ││                                                                             |
│└──────────────┘│                                                                             |
|     memory     │                                                                             |
|   controller   │◀────────────────────────────────────────────────────────────────────────────┘
└───────┬────────┘
        │  BMB Bus
        ├────────────────┐
 ┌──────┴───────┐ ┌──────┴───────┐
 |    memory    │ |     i/o      │
 |(BRAM/SDRAM/  │ | (sys, uart)  │
 |    DDR3)     │ └──────────────┘
 └──────────────┘
```
### SMP (2-core)
```
 ┌─────────────────┐    ┌─────────────────┐
 │    JopCore 0    │    │    JopCore 1    │
 │  pipeline+memctl│    │  pipeline+memctl│
 │  I/O (sys,uart) │    │  I/O (sys)      │
 └────┬────────┬───┘    └────┬────────┬───┘
      │  BMB   │ sync        │  BMB   │ sync
      │        └──────┬──────┘        │
      └───────┬───────│───────────────┘
        ┌─────┴──────┐│
        │ BMB Arbiter││ (round-robin)
        └─────┬──────┘│
              │  ┌────┴──────┐
              │  │CmpSync/IHLU│  (global/per-object lock)
              │  └────────────┘
       ┌──────┴───────┐
       │    SDRAM     │
       │    memory    │
       └──────────────┘
```

The pipeline fetches Java bytecodes, translates them via a jump table into microcode addresses, fetches and decodes microcode instructions, then executes them on a two-register stack machine with a 256-entry on-chip stack RAM (64 entries reserved for local variables and constants, 192 for the operand stack).

Memory access uses SpinalHDL's BMB (Bus Master Bridge) interconnect, supporting on-chip BRAM (single-cycle response), off-chip SDR SDRAM (variable latency with automatic pipeline stalling), and DDR3 SDRAM via a write-back cache and Xilinx MIG controller. The SMP configuration adds a round-robin BMB arbiter and `CmpSync` global lock (or optional `Ihlu` per-object hardware locking) for multi-core synchronization.

## Project Structure

```
jop/
├── spinalhdl/src/main/scala/jop/
│   ├── pipeline/              # Pipeline stages (fetch, decode, stack, bytecode)
│   ├── memory/                # Memory controller, method/object/array cache, SDRAM ctrl
│   ├── ddr3/                  # DDR3 subsystem (cache, MIG adapter, clock wizard)
│   ├── io/                    # I/O slaves (BmbSys, BmbUart, BmbEth, BmbMdio, BmbSdNative, BmbSdSpi, BmbVgaText, Ihlu, CmpSync)
│   ├── debug/                 # Debug subsystem (protocol, controller, breakpoints, UART)
│   ├── config/                # Configuration hierarchy (JopConfig, Board, Parts, IoConfig)
│   ├── system/                # System integration (JopCore, JopTop, JopCluster, SMP)
│   ├── types/                 # JOP types and constants
│   └── utils/                 # File loaders, utilities
├── spinalhdl/src/test/scala/jop/
│   ├── system/                # System-level simulations (BRAM, SDRAM, serial boot)
│   ├── memory/                # Memory controller tests
│   └── pipeline/              # Pipeline stage tests
├── asm/
│   ├── src/                   # Microcode source (jvm.asm, echo.asm)
│   └── generated/             # Generated jump tables, ROM/RAM data
├── fpga/
│   ├── scripts/               # download.py, monitor.py, make_flash_image.py, flash_program.py
│   ├── ip/                    # Third-party IP (Altera SDRAM controller)
│   ├── alchitry-au/           # DDR3 FPGA project (Vivado)
│   ├── cyc5000-sdram/         # SDRAM FPGA project (Quartus, Cyclone V)
│   ├── qmtech-ep4cgx150-bram/ # BRAM FPGA project (Quartus, Cyclone IV)
│   ├── qmtech-ep4cgx150-sdram/# SDRAM FPGA project (Quartus, Cyclone IV)
│   └── qmtech-ep4cgx150-eth-ref/ # Reference Ethernet design (1Gbps GMII UDP echo)
├── java/
│   ├── tools/src/             # JOPizer, PreLinker, Jopa, common framework
│   ├── runtime/src/           # JOP runtime + JDK stubs (JDK 6)
│   └── apps/                  # Java application builds
├── docs/                        # Architecture and reference docs
└── build.sbt                  # Top-level SBT build
```

## Getting Started

### Prerequisites

- **Java 11+** and **sbt** (Scala Build Tool)
- **Verilator** (simulation backend for SpinalSim)
- **Java 8+**, **gcc**, **make** (for Jopa microcode assembler)
- **Vivado** (for Alchitry Au V2 / Xilinx FPGA synthesis)
- **Quartus Prime** (for QMTECH EP4CGX150 / Altera FPGA synthesis, optional)

### Build and Run Simulation

```bash
# 1. Build microcode assembler and generate microcode
cd java/tools && make dist/jopa.jar
cd ../../asm && make

# 2. Compile SpinalHDL (from project root)
sbt compile

# 3. Build Java toolchain, runtime, and test apps
cd java && make all && cd ..

# 4. Run BRAM simulation (prints "Hello World!" in a loop)
sbt "Test / runMain jop.system.JopCoreBramSim"

# 4b. Run unified JopTop BRAM simulation (same output, uses config-driven JopTop)
sbt "Test / runMain jop.system.JopTopBramSim"

# 5. Run SDRAM simulation
sbt "Test / runMain jop.system.JopCoreWithSdramSim"

# 6. Run GC stress test (allocates arrays, triggers garbage collection)
sbt "Test / runMain jop.system.JopSmallGcBramSim"

# 7. Run SMP simulation (2-core, NCoreHelloWorld — both cores toggle watchdog)
sbt "Test / runMain jop.system.JopSmpNCoreHelloWorldSim"

# 8. Run SMP GC simulation (2-core, garbage collection stress test)
sbt "Test / runMain jop.system.JopSmpBramSim"
```

### Build for FPGA

```bash
# SDRAM target — QMTECH EP4CGX150, Altera Cyclone IV (primary, serial boot, 80 MHz)
cd fpga/qmtech-ep4cgx150-sdram
make microcode   # Assemble serial boot microcode
make generate    # Generate Verilog (uses JopTopVerilog ep4cgx150Serial)
make build       # Quartus synthesis
make program     # Program FPGA
make download    # Download HelloWorld.jop over UART
make monitor     # Watch serial output

# Or generate Verilog directly for any preset:
sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"   # QMTECH SDRAM
sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 8"    # QMTECH 8-core SMP
sbt "runMain jop.system.JopTopVerilog cyc5000Serial"     # CYC5000 SDRAM
sbt "runMain jop.system.JopTopVerilog auSerial"          # Alchitry Au V2 DDR3
sbt "runMain jop.system.JopTopVerilog wukongSdram"       # Wukong SDR SDRAM
sbt "runMain jop.system.JopTopVerilog wukongDdr3"        # Wukong DDR3
sbt "runMain jop.system.JopTopVerilog wukongBram"        # Wukong BRAM
sbt "runMain jop.system.JopTopVerilog minimum"           # Minimum resources

# Flash boot — autonomous boot from SPI flash, no JTAG needed after programming
cd fpga/qmtech-ep4cgx150-sdram
make full-flash-boot              # Microcode + generate + build flash boot design
make flash-image                  # Create combined flash image (bitstream + .jop)
make generate-flash-programmer    # Generate UART-SPI bridge
make build-flash-programmer       # Build UART-SPI bridge
make program-flash-programmer     # Load UART-SPI bridge via JTAG
sudo python3 ../scripts/flash_program.py output_files/flash_image.bin --port /dev/ttyUSB0 -v
# Power-cycle board — boots automatically from flash

# DB_FPGA daughter board — QMTECH EP4CGX150 + Ethernet/VGA/SD (serial boot, 80 MHz)
cd fpga/qmtech-ep4cgx150-sdram
make full-dbfpga           # Complete flow: microcode + generate-dbfpga + build-dbfpga
make program-dbfpga        # Program FPGA
make download JOP_FILE=java/apps/Small/EthTest.jop  # Download Ethernet test
make monitor               # Watch serial output

# SMP (2-core) — QMTECH EP4CGX150, Altera Cyclone IV (serial boot, 80 MHz)
cd fpga/qmtech-ep4cgx150-sdram
make full-smp    # Complete flow: microcode + generate-smp + build-smp
make program-smp # Program FPGA
make download    # Download NCoreHelloWorld.jop over UART
make monitor     # Watch serial output (both cores toggle LEDs independently)

# BRAM target — QMTECH EP4CGX150, Altera Cyclone IV (self-contained, 100 MHz)
cd fpga/qmtech-ep4cgx150-bram
make generate    # Generate Verilog from SpinalHDL
make build       # Quartus synthesis
make program     # Program FPGA via USB-Blaster
make monitor     # Open serial monitor (1 Mbaud)

# SDRAM target — Trenz CYC5000, Altera Cyclone V (serial boot, 80 MHz)
cd fpga/cyc5000-sdram
make microcode   # Assemble serial boot microcode
make generate    # Generate Verilog
make build       # Quartus synthesis
make program     # Program FPGA via JTAG
make download    # Download HelloWorld.jop over UART
make monitor     # Watch serial output

# SMP (2-core) — Trenz CYC5000, Altera Cyclone V (serial boot, 80 MHz)
cd fpga/cyc5000-sdram
make full-smp    # Complete flow: microcode + generate-smp + build-smp
make program-smp # Program FPGA
make download    # Download NCoreHelloWorld.jop over UART
make monitor     # Watch serial output

# DDR3 target — Alchitry Au V2, Xilinx Artix-7 (serial boot, 100 MHz)
cd fpga/alchitry-au
make generate    # Generate Verilog from SpinalHDL (single-core)
make ips         # Generate MIG + ClkWiz Vivado IPs (first time only)
make bitstream   # Vivado synthesis + implementation + bitstream
make program     # Program FPGA via JTAG
make download    # Download HelloWorld.jop over UART
make monitor     # Watch serial output

# DDR3 SMP (2-core) — Alchitry Au V2, Xilinx Artix-7 (serial boot, 100 MHz)
make generate-smp  # Generate SMP Verilog (2-core)
make project-smp   # Create Vivado project
make bitstream-smp # Build bitstream
make program-smp   # Program FPGA via JTAG
# Wait ~5s for MIG calibration, then:
make JOP=../../java/apps/Small/NCoreHelloWorld.jop download
make monitor
```

### Running Tests

```bash
# SpinalSim tests (Verilator)
sbt test

# Formal verification (SymbiYosys + Z3) — 97 properties across 16 suites
sbt "testOnly jop.formal.*"

# Latency sweep (verify correct operation at 0-5 extra memory cycles)
sbt "Test / runMain jop.system.JopCoreLatencySweep"

# Timer interrupt end-to-end test (5 interrupts, handler dispatch, ~2.6M cycles)
sbt "Test / runMain jop.system.JopInterruptSim"

# Debug protocol test (39 checks: ping, halt, step, registers, memory, breakpoints)
sbt "Test / runMain jop.system.JopDebugProtocolSim"

# JVM test suite (53 tests, all pass)
sbt "Test / runMain jop.system.JopJvmTestsBramSim"

# JVM test suite on 2-core SMP (52/53 pass — DeepRecursion needs stack cache)
sbt "Test / runMain jop.system.JopJvmTestsSmpBramSim"

# SMP cache coherency stress test (cross-core A$/O$ snoop invalidation)
sbt "Test / runMain jop.system.JopSmpCacheStressSim"

# IHLU (per-object locking) SMP test (2-core, NCoreHelloWorld)
sbt "Test / runMain jop.system.JopIhluNCoreHelloWorldSim"

# IHLU GC test (2-core, GC with per-object locking)
sbt "Test / runMain jop.system.JopIhluGcBramSim"
```

## Supported FPGA Boards

| Board | FPGA | Memory | Toolchain | Status |
|-------|------|--------|-----------|--------|
| **[QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD)** | **Altera Cyclone IV GX** | **W9825G6JH6 SDR SDRAM** | **Quartus Prime** | **Primary — 100 MHz (1-8 core), 80 MHz (16-core)** |
| [QMTECH EP4CGX150 + DB_FPGA](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Altera Cyclone IV GX | W9825G6JH6 SDR SDRAM | Quartus Prime | 80 MHz — Ethernet 1Gbps GMII ([details](docs/peripherals/db-fpga-ethernet.md)), VGA text 80x30 ([details](docs/peripherals/db-fpga-vga-text.md)), SD card native 4-bit ([details](docs/peripherals/db-fpga-sd-card.md)) |
| [QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Altera Cyclone IV GX | BRAM (on-chip) | Quartus Prime | Working at 100 MHz |
| [Trenz CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A) | Altera Cyclone V E (5CEBA2U15C8N) | W9864G6JT SDR SDRAM | Quartus Prime | Working at 80 MHz |
| A-E115FB | Altera Cyclone IV E (EP4CE115F23I7) | BRAM (on-chip) | Quartus Prime | BRAM only, programmed via [pico-dirtyJtag](docs/pico-dirtyjtag-setup.md) |
| [Alchitry Au V2](https://shop.alchitry.com/products/alchitry-au) | Xilinx Artix-7 (XC7A35T) | MT41K128M16JT DDR3 (256MB) | Vivado | 100 MHz — single-core + SMP (2-core), full 256MB addressed, GC working ([details](docs/gc/ddr3-gc-hang.md)) |

### Resource Usage

All builds at 100 MHz except CYC5000 (80 MHz). Cyclone IV uses Logic Elements (4-input LUT + FF), Cyclone V uses ALMs (8-input fracturable LUT + 2 FFs), Artix-7 uses LUTs (6-input). Numbers are not directly comparable across families.

| Component | EP4CGX150 BRAM | EP4CGX150 SDRAM | EP4CGX150 SMP (2-core) | CYC5000 SDRAM | Artix-7 DDR3 | Artix-7 DDR3 SMP |
|-----------|:-:|:-:|:-:|:-:|:-:|:-:|
| | LEs | LEs | LEs | ALMs | LUTs | LUTs |
| **JOP Core** | **5,426** | **5,447** | **5,447 x2** | **1,821** | | |
| — Pipeline | 2,948 | 2,999 | 2,999 x2 | 928 | | |
| — Memory controller | 985 | 960 | 960 x2 | 357 | | |
| — Method cache | 599 | 600 | 600 x2 | 143 | | |
| — Object cache | 899 | 892 | 892 x2 | 393 | | |
| Memory backend | 103 | 657 | 657 | 231 | | |
| I/O (BmbSys + BmbUart) | 326 | 333 | ~660 | 138 | | |
| BMB Arbiter + CmpSync | — | — | ~200 | — | | |
| **System total** | **5,856** | **6,461** | **~12,400** | **2,231** | **12,021** | **19,069** |
| % of device | 4% | 4% | 8% | — | 57.8% | 91.7% |
| Registers | 2,108 | 2,428 | ~4,900 | 2,698 | 10,279 | 15,049 |
| Block RAM | 1,054 Kbit | 28 Kbit | 56 Kbit | 28 Kbit | 450 Kbit | 540 Kbit |
| Timing (WNS) | | | | | +0.115 ns | +0.197 ns |

Notes:
- EP4CGX150 BRAM uses 1,054 Kbit block RAM for program memory (128 M9Ks); SDRAM builds store programs in external RAM
- SMP (2-core) uses ~8% of EP4CGX150's 150K LEs, leaving substantial headroom for additional cores
- Artix-7 single-core uses 16KB L2 cache; SMP uses 32KB L2 cache (512 sets × 4 ways). Per-core cost ~4,400 LUT, ~2,900 FF, ~2.5 BRAM
- Vivado does not report per-hierarchy utilization; Artix-7 core-only numbers not available from build reports
- Artix-7 single-core LUT includes ~1,584 LUT of distributed RAM from stack cache bank RAMs (`readAsync` not supported by Xilinx BRAM). Converting to `readSync` would save ~1,584 LUT/core. See [distributed RAM optimization](docs/analysis/artix7-distram-optimization.md)
- See [Artix-7 core count estimates](docs/analysis/artix7-core-estimates.md) for scaling projections across the Artix-7 family

## Implementation Status

### Complete

- **Pipeline**: All four stages — bytecode fetch/translate, microcode fetch, decode, execute (stack)
- **Memory controller**: BMB bus with two-layer design (combinational + state machine for BC fill, getfield, iaload), pipelined BC fill overlaps memory reads with JBC writes, configurable BMB burst reads for SDRAM, hardware `memCopy` for GC object relocation
- **Method cache**: 16-block tag-only cache (32 words/block, FIFO replacement) skips redundant bytecode fills; 2-cycle hit, 3-cycle + fill on miss
- **Object cache**: 16-entry fully associative field cache (8 fields/entry, FIFO replacement) shortcuts getfield to 0 busy cycles on hit; write-through on putfield; invalidated on `stidx`/`cinval`
- **Array cache**: 16-entry fully associative element cache (4 elements/line, FIFO replacement) shortcuts iaload to 0 busy cycles on hit; 4-element line fill on miss (burst read on SDRAM); write-through on iastore; SMP-safe via cross-core snoop invalidation; two VHDL bugs fixed (idx_upper slice, FIFO nxt advancement)
- **Stack buffer**: 256-entry on-chip RAM (64 for 32 local variables + 32 constants, 192 for operand stack) with spill/fill, ALU, shifter, 33-bit comparator. Optional 3-bank rotating stack cache with DMA spill/fill extends the stack to external memory (16-bit virtual SP, 192 entries per bank, per-core stack regions). See [system configuration](docs/architecture/system-configuration.md).
- **Jump table**: Bytecode-to-microcode translation (generated from `jvm.asm` by Jopa)
- **Multiplier**: 17-cycle radix-4 Booth multiplier
- **I/O subsystem**: `BmbSys` (cycle/microsecond counters, timer interrupt, watchdog, CPU ID), `BmbUart` (TX/RX with 16-entry FIFOs, RX/TX interrupt outputs), `BmbEth` (Ethernet MAC with GMII 1Gbps TX/RX using SpinalHDL `MacEth`, 125 MHz PLL for TX, PHY clock for RX, dual-clock FIFOs for PHY clock domain crossing), `BmbMdio` (MDIO PHY management with registered outputs and PHY reset control), `BmbSdNative` (SD card native 4-bit mode, hardware CRC7/CRC16, 512-byte block FIFO, verified on hardware at 10 MHz — [details](docs/peripherals/db-fpga-sd-card.md)), `BmbSdSpi` (SD card SPI mode, byte-at-a-time transfer with hardware clock generation), and `BmbVgaText` (80x30 text-mode VGA, 640x480@60Hz, 8x16 font, CGA palette, RGB565 output, 25 MHz pixel clock from PLL c3) as reusable `jop.io` components. Timer interrupts verified end-to-end in simulation (`JopInterruptSim`). VGA text verified on hardware ([details](docs/peripherals/db-fpga-vga-text.md))
- **Unified top-level**: `JopTop(config)` — single config-driven Component replaces all board-specific top files. Supports BRAM, SDR SDRAM, and DDR3 across Altera (Cyclone IV/V) and Xilinx (Artix-7) FPGAs. `simulation` flag enables Verilator-compatible mode (bypasses PLL/reset/MIG BlackBoxes). Verilog generation via `JopTopVerilog` with named presets.
- **SDRAM system (primary)**: Serial boot over UART into SDR SDRAM using Altera `altera_sdram_tri_controller` (QMTECH EP4CGX150 at 100 MHz + Trenz CYC5000 at 80 MHz). Single-core and SMP via `JopConfig` presets.
- **SMP (2-core)**: 2-core SMP with round-robin BMB arbiter, `CmpSync` global lock for `monitorenter`/`monitorexit`, per-core `BmbSys` with unique CPU ID, boot synchronization via `IO_SIGNAL`, and GC stop-the-world halt via `IO_GC_HALT`. Verified on QMTECH EP4CGX150, CYC5000, and Alchitry Au V2 hardware (both cores running independently with per-core LED watchdog)
- **BRAM system**: Complete system with on-chip memory at 100 MHz (QMTECH EP4CGX150, Altera Cyclone IV; Wukong XC7A100T, Xilinx Artix-7)
- **DDR3 system**: Serial boot over UART through 32KB 4-way write-back cache into DDR3 at 100 MHz (Alchitry Au V2, Xilinx Artix-7, full 256MB addressed). Single-core and 2-core SMP verified with GC (67K+ rounds single-core at 8MB, 1870+ rounds at 256MB). Standalone `Ddr3ExerciserTop` memory test and `Ddr3TraceReplayerTop` BMB trace verification also available.
- **Microcode tooling**: Jopa assembler generates VHDL and Scala outputs from `jvm.asm`
- **GC support**: Mark-compact garbage collection with incremental mark/compact phases (bounded per-allocation increments) and STW fallback. Hardware `memCopy` for GC object relocation, MAX_HANDLES cap (65536) prevents O(N) sweep explosion on large memories (256MB+). Tested with allocation-heavy GC app (98,000+ rounds on BRAM, 9,800+ on CYC5000 SDRAM, 2,000+ on QMTECH SDRAM, 1,870+ on 256MB DDR3). SMP GC uses `IO_GC_HALT` to freeze all other cores during collection, preventing concurrent SDRAM access to partially-moved objects
- **Hardware exception detection**: Null pointer and array bounds checks fully enabled — NPE fires on handle address 0, ABE fires on negative index (MSB) or index >= array length. Wired through BmbSys `exc` pulse to `sys_exc` microcode handler. Div-by-zero handled via Java `throw JVMHelp.ArithExc` in f_idiv/f_irem/f_ldiv/f_lrem.
- **Formal verification**: 97 properties verified across 16 test suites using SymbiYosys + Z3 — covers core arithmetic, all pipeline stages, memory subsystem (method cache, object cache, memory controller), DDR3 cache + MIG adapter, I/O (CmpSync, BmbSys, BmbUart), and BMB protocol compliance. See [formal verification docs](docs/formal-verification.md).
- **Debug subsystem** (`jop.debug` package): Optional on-chip debug controller with framed byte-stream protocol over dedicated UART. Supports halt/resume/single-step (microcode and bytecode), register and stack inspection, memory read/write, and up to 4 hardware breakpoints (JPC or microcode PC). Integrated into `JopCluster` via `DebugConfig`. Automated protocol test (`JopDebugProtocolSim`) verifies 39 checks across 14 test sequences.
- **JVM test suite**: 53 tests (`java/apps/JvmTests/`) — all pass. Covers arrays, branches, type casting, int/long arithmetic, type conversions (i2x/l2x/f2x/d2x), constant loading, float/double ops (add/sub/mul/div/neg/cmp/rem), field access for all types, exceptions (throw/catch, finally, nested, athrow, div-by-zero, null pointer with 13 sub-tests), instanceof, super method dispatch, object fields, interfaces, static initializers, stack manipulation, System.arraycopy (including StringBuilder resize), string concatenation with int, cache persistence regression, long static fields, deep recursion (200-level, exercises stack cache bank rotation), and more. Ported from original JOP `jvm/` suite and Wimpassinger `jvmtest/` suite.
- **SMP test coverage**: JVM test suite on 2-core SMP (52/53 pass, DeepRecursion excluded — needs stack cache), SMP cache coherency stress test (cross-core A$/O$ snoop invalidation with 20 rounds verified), SMP GC stress (2-core BRAM), IHLU per-object locking verified (NCoreHelloWorld + GC with 84 lock/unlock ops balanced, 3 GC cycles)
- **Simulation**: BRAM sim, SDRAM sim, serial boot sim, latency sweep (0-5 extra cycles), GC stress test, JVM test suite (single-core + SMP), SMP cache coherency test, timer interrupt test, debug protocol test
### Known Issues

- **burstLen=0 + SMP incompatibility** — `burstLen=0` (pipelined single-word BC_FILL) interleaves with the BMB arbiter in SMP mode, causing response-source misalignment. SMP requires `burstLen >= 4`. Single-core is unaffected. See [DDR3 notes](docs/gc/ddr3-gc-hang.md).

### TODO

Active work items:

- **Stack cache SDRAM integration** — 3-bank rotation working in BRAM simulation (53/53 tests pass); needs SDRAM integration with per-core stack regions (memory layout configured, GC bounds checking pending)
- **Stack cache bank RAM optimization** — convert `readAsync` to `readSync` on bank RAMs to enable Xilinx BRAM inference, saving ~1,584 LUTs on Artix-7 (81% → ~73% utilization). Altera is unaffected (M9K/M10K supports async reads natively). See [distributed RAM optimization](docs/analysis/artix7-distram-optimization.md)
- **SMP test expansion** — lock contention stress test (>2 cores hammering `synchronized`), SMP exception handling test. Cache snoop and JVM-on-SMP tests done. See [test coverage audit](docs/test-coverage-audit.md)
- **DDR3 SMP GC** — run GC stress test on dual-core DDR3 (NCoreHelloWorld verified, GC stress not yet tested in SMP mode)

### Future

Lower-priority or longer-term items:

- Memory controller — remaining VHDL features: atomic memory operations (`atmstart`/`atmend`), address translation on read paths (for concurrent GC), data cache control signals, fast-path array access (`iald23`)
- Interrupt handling — timer interrupts verified; UART RX/TX interrupts wired but not exercised; scheduler preemption not tested
- DDR3 burst optimization — method cache fills could use burst reads through the cache bridge
- Debug tooling — host-side debug client connecting to on-chip debug controller over UART
- Target JDK modernization (8 as minimum)
- Port target code — networking, etc.
- XDC constraint generation — Xilinx pin constraints from board data (QSF generator exists for Altera)
- Faster serial download — currently limited by per-word USB round-trip latency (~15s for 32KB)
- Performance measurement / benchmarking

## Key Technical Details

- **Bus**: SpinalHDL BMB (Bus Master Bridge). BRAM gives single-cycle accept, next-cycle response (matches original SimpCon `rdy_cnt=1`). SDRAM and DDR3 stall automatically via busy signal.
- **Memory controller**: Layer 1 is combinational (simple rd/wr). Layer 2 is a state machine for multi-cycle operations (bytecode fill, getfield, array access). BC fill is pipelined — issues the next read while writing the previous response to JBC RAM, saving ~1 cycle per word. Configurable burst reads (`burstLen=4` for SDR SDRAM). Hardware `memCopy` state machine for GC object relocation.
- **DDR3 subsystem** (`jop.ddr3` package): Write-back cache bridge (`BmbCacheBridge`) converts 32-bit BMB transactions to 128-bit cache lines (truncates BMB address to `cacheAddrWidth` bits, stripping type bits). `LruCacheCore` provides a 4-way set-associative 16KB write-back cache (256 sets, BRAM-based, PLRU replacement). `CacheToMigAdapter` interfaces with the Xilinx MIG DDR3 controller. Full 256MB DDR3 addressed (`addressWidth=28`, 28-bit word address including 2 type bits → 26-bit physical = 256MB). Clock wizard generates 100 MHz system and 200 MHz reference clocks from the board oscillator.
- **Object cache**: Fully associative field value cache (16 entries, 8 fields each). Getfield hits return data in 0 busy cycles (combinational tag match, registered data output). Putfield does write-through on tag hit. FIFO replacement, invalidated on array stores and explicit `cinval`.
- **Array cache**: Fully associative element value cache (16 entries, 4 elements per line). iaload hits return in 0 busy cycles; misses fill the entire 4-element aligned line (burst read on SDRAM to prevent interleaving). iastore does write-through on tag hit. Tags include handle address and upper index bits so different array regions map to different lines. SMP-safe via cross-core snoop invalidation (`CacheSnoopBus` — each core's iastore broadcasts on snoop bus, other cores selectively invalidate matching lines). Note: raw memory writes (`Native.wrMem`) bypass A$ — `System.arraycopy` calls `Native.invalidate()` after copy loops to ensure coherency.
- **Handle format**: `H[0]` = data pointer, `H[1]` = array length. Array elements start at `data_ptr[0]`.
- **I/O subsystem**: Reusable `BmbSys` and `BmbUart` components in `jop.io` package. System slave provides clock cycle counter, prescaled microsecond counter, timer interrupt, watchdog register, and CPU ID. UART slave provides buffered TX/RX with 16-entry FIFOs and per-source interrupt outputs (RX data available, TX FIFO empty). UART interrupts are wired to BmbSys interrupt sources (index 0 = timer, 1 = UART RX, 2 = UART TX). Ethernet subsystem (`BmbEth` + `BmbMdio`) supports MII (100Mbps, 4-bit) and GMII (1Gbps, 8-bit) modes via `IoConfig.ethGmii`, with a dedicated 125 MHz PLL for GMII TX and source-synchronous PHY clock for RX. SD card controllers: `BmbSdNative` (native 4-bit mode, hardware CRC7/CRC16, 512-byte block FIFO) and `BmbSdSpi` (SPI mode, byte-at-a-time), mutually exclusive (share card slot pins). Native mode verified on FPGA hardware at 10 MHz ([details](docs/peripherals/db-fpga-sd-card.md)). VGA text controller (`BmbVgaText`) provides 80x30 character display at 640x480@60Hz with CGA palette, cursor-based and direct-write modes, hardware clear/scroll, and RGB565 output via 25 MHz pixel clock from PLL c3.
- **SMP**: `JopTop(config)` with `cpuCnt >= 2` in the `JopSystem` instantiates N `JopCore`s with a round-robin BMB arbiter for shared memory access. `CmpSync` provides a global lock (round-robin fair arbitration) for `monitorenter`/`monitorexit`, with optional `Ihlu` per-object hardware locking (32-slot CAM, FIFO wait queues, reentrant) selectable via `useIhlu` config flag, plus a GC halt signal (`IO_GC_HALT`) that freezes all other cores during garbage collection. Each core has its own `BmbSys` (unique CPU ID, independent watchdog). Core 0 initializes the system; other cores wait for a boot signal via `IO_SIGNAL`. DDR3 SMP requires `burstLen >= 4` (pipelined single-word BC_FILL interleaves with arbiter at `burstLen=0`).
- **Debug subsystem**: Optional on-chip debug controller (`jop.debug` package) enabled via `DebugConfig` in `JopCluster`. Uses a dedicated UART (separate from the application UART) with a CRC-8/MAXIM framed protocol. `DebugProtocol` parses/builds frames, `DebugController` implements the command FSM (halt, resume, single-step, register/stack/memory read/write, breakpoint management), and `DebugBreakpoints` provides per-core hardware PC comparators. Supports multi-core targeting via core ID field in each command.
- **Serial boot**: Microcode polls UART for incoming bytes, assembles 4 bytes into 32-bit words, writes to external memory. Download script (`download.py`) sends `.jop` files with word-level echo verification.

## Documentation

Design notes and investigation logs in `docs/`:

- [Microcode Instructions](docs/architecture/microcode.md) — table of all microcode instructions and encodings
- [Stack Architecture](docs/architecture/STACK_ARCHITECTURE.md) — stack buffer, spill/fill, local variables
- [Jopa Tool](docs/architecture/JOPA_TOOL.md) — microcode assembler usage and output formats
- [Programmer's Guide](docs/programmers-guide.md) — I/O register maps and Java API for all devices (BmbSys, BmbUart, BmbEth, BmbMdio, BmbSdNative, BmbSdSpi, BmbVgaText)
- [System Configuration](docs/architecture/system-configuration.md) — configuration reference: memory layout, JopCoreConfig, JopMemoryConfig, IoConfig, board configs, I/O register map
- [Configuration-Driven Design](docs/architecture/configuration-driven-design.md) — unified `JopTop(config)` design: JopConfig hierarchy, board/FPGA/memory metadata, PLL/reset/memory controller factories, preset system
- [Bugs and Issues](docs/bugs-and-issues.md) — master bug index: open JVM workarounds, fixed RTL/pipeline/microcode bugs
- [Implementation Notes](docs/architecture/implementation-notes.md) — bugs found, cache details, I/O subsystem, SMP, GC architecture, memCopy
- [Artix-7 Distributed RAM Optimization](docs/analysis/artix7-distram-optimization.md) — stack cache bank RAM `readAsync` → `readSync` for BRAM inference on Xilinx
- [Cache Analysis](docs/architecture/cache-analysis.md) — cache performance analysis and technology cost model
- [Memory Controller Comparison](docs/architecture/memory-controller-comparison.md) — VHDL vs SpinalHDL memory controller
- [Stack Immediate Timing](docs/analysis/stack-immediate-timing.md) — stack stage timing for immediate operations
- [Formal Verification](docs/formal-verification.md) — 98 BMC properties across all components (SymbiYosys + Z3)
- [DB_FPGA Ethernet](docs/peripherals/db-fpga-ethernet.md) — 1Gbps GMII architecture, pin mapping, PHY config, SDC timing for RTL8211EG
- [DB_FPGA VGA Text](docs/peripherals/db-fpga-vga-text.md) — 80x30 text-mode VGA output, register map, Java API, setup guide
- [DB_FPGA SD Card](docs/peripherals/db-fpga-sd-card.md) — SD card native 4-bit mode, hardware verification, bugs found, clock speed constraints
- [FAT32 Filesystem](docs/peripherals/fat32-filesystem.md) — read-write FAT32 with LFN support, API reference, JOP workarounds, simulation and hardware testing
- [Flash Boot](docs/boards/flash-boot.md) — autonomous Active Serial boot from W25Q128, UART flash programmer, flash image format
- [pico-dirtyJtag Setup](docs/pico-dirtyjtag-setup.md) — program FPGAs via Raspberry Pi Pico + openFPGALoader (alternative to USB-Blaster)
- [SDR SDRAM GC Hang](docs/gc/sdr-sdram-gc-hang.md) — resolved: SpinalHDL SdramCtrl DQ timing issue
- [DDR3 GC Hang](docs/gc/ddr3-gc-hang.md) — resolved (32KB L2 cache)

## References

- JOP project: https://github.com/jop-devel/jop
- JOP web site: https://www.jopdesign.com/
- JOP Thesis: Martin Schoeberl, [JOP: A Java Optimized Processor for Embedded Real-Time Systems](https://www.jopdesign.com/thesis/thesis.pdf)
- SpinalHDL: https://spinalhdl.github.io/SpinalDoc-RTD/

## License

TBD - Following original JOP licensing
