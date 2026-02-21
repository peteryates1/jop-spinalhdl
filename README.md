# JOP - Java Optimized Processor (SpinalHDL)

A complete reimplementation of the [Java Optimized Processor](https://github.com/jop-devel/jop) (JOP) in [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/). JOP is a hardware implementation of the Java Virtual Machine as a soft-core processor for FPGAs, originally developed by Martin Schoeberl. See [jopdesign.com](https://www.jopdesign.com/) for the original project.

This port runs Java programs on FPGA hardware. The primary development platform is the **QMTECH EP4CGX150** (Altera Cyclone IV GX + SDR SDRAM), which supports single-core and SMP (2-core) configurations with stable garbage collection.

Built with [Claude Code](https://code.claude.com/docs/en/quickstart).

## Status

**Working on hardware.** The processor boots and runs Java programs at 100 MHz:

- **SDRAM + SMP (primary)**: 2-core SMP on QMTECH EP4CGX150 (Cyclone IV, W9825G6JH6 SDR SDRAM) — both cores verified running independently on hardware, with CmpSync global lock and round-robin BMB arbitration
- **SDRAM (single-core)**: Serial boot over UART into SDR SDRAM on two boards — QMTECH EP4CGX150 (Cyclone IV) and Trenz CYC5000 (Cyclone V, W9864G6JT)
- **BRAM**: Self-contained, program embedded in block RAM (QMTECH EP4CGX150)
- **DDR3**: Serial boot through write-back cache into DDR3 (Alchitry Au V2, Xilinx Artix-7) — basic programs work, GC hangs at first collection (unresolved, see [DDR3 GC hang notes](docs/ddr3-gc-hang.md))
- **GC support**: Automatic garbage collection with hardware-accelerated object copying (`memCopy`), tested 98,000+ rounds (BRAM), 9,800+ rounds (CYC5000 SDRAM), 2,000+ rounds (QMTECH SDRAM)

## Project Goals

- Port JOP from VHDL to SpinalHDL/Scala for modern tooling and configurability
- Maintain cycle-accurate compatibility with the original implementation
- Target multiple FPGA boards with configurable system generation
- Upgrade Java target from JDK 1.5/1.6 to modern versions (future)

## Architecture

```
Single-core:

 ┌──────────────┐                    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 │   bytecode   │                    │  microcode   │    │  microcode   │    │  microcode   │
 │    fetch     ├─────────┬─────────▶│    fetch     ├─┬─▶│   decode     ├───▶│   execute    │◀─┐
 │  translate   │         │          │              │ |  │              │    │  (tos/nos)   │  |
 └──────┬───────┘         │          └──────┬───────┘ |  └──────────────┘    └──────┬───────┘  |
        │                 │                 │         |                       spill & fill     |
┌───────┼────────┐        │                 │         |                             |          |
│┌──────┴───────┐│ ┌──────┴───────┐  ┌──────┴───────┐ |  ┌──────────────┐    ┌──────┴───────┐  |
│| method cache ││ |  jump tbl    │  │microcode rom │ └──│ Address Gen  ├───▶│ stack buffer │  |
│├──────────────┤│ └──────────────┘  └──────────────┘    └──────────────┘    └──────────────┘  |
│| object cache ││                                                                             |
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

SMP (2-core):

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
              │  │  CmpSync  │  (global lock)
              │  └───────────┘
       ┌──────┴───────┐
       │    SDRAM     │
       │    memory    │
       └──────────────┘
```

The pipeline fetches Java bytecodes, translates them via a jump table into microcode addresses, fetches and decodes microcode instructions, then executes them on a two-register stack machine with a 256-entry on-chip stack RAM (64 entries reserved for local variables and constants, 192 for the operand stack).

Memory access uses SpinalHDL's BMB (Bus Master Bridge) interconnect, supporting on-chip BRAM (single-cycle response), off-chip SDR SDRAM (variable latency with automatic pipeline stalling), and DDR3 SDRAM via a write-back cache and Xilinx MIG controller. The SMP configuration adds a round-robin BMB arbiter and `CmpSync` global lock for multi-core synchronization.

## Project Structure

```
jop/
├── spinalhdl/src/main/scala/jop/
│   ├── pipeline/              # Pipeline stages (fetch, decode, stack, bytecode)
│   ├── memory/                # Memory controller, method cache, object cache, SDRAM ctrl
│   ├── ddr3/                  # DDR3 subsystem (cache, MIG adapter, clock wizard)
│   ├── io/                    # I/O slaves (BmbSys, BmbUart)
│   ├── system/                # System integration (JopCore, FPGA tops, SMP)
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
│   ├── scripts/               # download.py, monitor.py, usb_serial_map
│   ├── ip/                    # Third-party IP (Altera SDRAM controller)
│   ├── alchitry-au/           # DDR3 FPGA project (Vivado)
│   ├── cyc5000-sdram/         # SDRAM FPGA project (Quartus, Cyclone V)
│   ├── qmtech-ep4cgx150-bram/ # BRAM FPGA project (Quartus, Cyclone IV)
│   └── qmtech-ep4cgx150-sdram/# SDRAM FPGA project (Quartus, Cyclone IV)
├── java/
│   ├── tools/jopa/            # Jopa microcode assembler
│   ├── tools/src/             # JOPizer, PreLinker, common framework
│   ├── runtime/src/           # JOP runtime + JDK stubs (JDK 6)
│   └── apps/                  # Java application builds
├── verification/cocotb/         # CocoTB/GHDL verification tests
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
- **Python 3.8+**, **GHDL**, **CocoTB** (for VHDL reference tests, optional)

### Build and Run Simulation

```bash
# 1. Build microcode assembler and generate microcode
cd java/tools/jopa && make
cd ../../../asm && make

# 2. Compile SpinalHDL (from project root)
sbt compile

# 3. Build Java toolchain, runtime, and test apps
cd java && make all && cd ..

# 4. Run BRAM simulation (prints "Hello World!" in a loop)
sbt "Test / runMain jop.system.JopCoreBramSim"

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
# SDRAM target — QMTECH EP4CGX150, Altera Cyclone IV (primary, serial boot, 100 MHz)
cd fpga/qmtech-ep4cgx150-sdram
make microcode   # Assemble serial boot microcode
make generate    # Generate Verilog
make build       # Quartus synthesis
make program     # Program FPGA
make download    # Download HelloWorld.jop over UART
make monitor     # Watch serial output

# SMP (2-core) — QMTECH EP4CGX150, Altera Cyclone IV (serial boot, 100 MHz)
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

# DDR3 target — Alchitry Au V2, Xilinx Artix-7 (serial boot, 100 MHz, GC hang unresolved)
cd fpga/alchitry-au
make generate    # Generate Verilog from SpinalHDL
make ips         # Generate MIG + ClkWiz Vivado IPs (first time only)
make bitstream   # Vivado synthesis + implementation + bitstream
make program     # Program FPGA via JTAG
make download    # Download HelloWorld.jop over UART
make monitor     # Watch serial output
```

### Running Tests

```bash
# SpinalSim tests (Verilator)
sbt test

# Latency sweep (verify correct operation at 0-5 extra memory cycles)
sbt "Test / runMain jop.system.JopCoreLatencySweep"

# Reference simulator
sbt "runMain jop.JopSimulatorSim"

# CocoTB/GHDL verification tests (requires JOP_HOME pointing to original JOP repo)
cd verification/cocotb
make test_all            # VHDL reference module tests (mul, shift, fetch, decode, stack, bcfetch)
make test_jop_simulator  # Full system test with HelloWorld.jop (~13 min)
make test_method_cache   # Method cache tag lookup
make test_interrupt      # Interrupt/exception handling
make help                # List all available test targets
```

## Supported FPGA Boards

| Board | FPGA | Memory | Toolchain | Status |
|-------|------|--------|-----------|--------|
| **[QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD)** | **Altera Cyclone IV GX** | **W9825G6JH6 SDR SDRAM** | **Quartus Prime** | **Primary — 100 MHz, single-core + SMP (2-core)** |
| [QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Altera Cyclone IV GX | BRAM (on-chip) | Quartus Prime | Working at 100 MHz |
| [Trenz CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A) | Altera Cyclone V E (5CEBA2U15C8N) | W9864G6JT SDR SDRAM | Quartus Prime | Working at 80 MHz |
| [Alchitry Au V2](https://shop.alchitry.com/products/alchitry-au) | Xilinx Artix-7 (XC7A35T) | MT41K128M16JT DDR3 | Vivado | 100 MHz — GC hangs ([details](docs/ddr3-gc-hang.md)) |

### Resource Usage

All builds at 100 MHz except CYC5000 (80 MHz). Cyclone IV uses Logic Elements (4-input LUT + FF), Cyclone V uses ALMs (8-input fracturable LUT + 2 FFs), Artix-7 uses LUTs (6-input). Numbers are not directly comparable across families.

| Component | EP4CGX150 BRAM | EP4CGX150 SDRAM | EP4CGX150 SMP (2-core) | CYC5000 SDRAM | Artix-7 DDR3 |
|-----------|:-:|:-:|:-:|:-:|:-:|
| | LEs | LEs | LEs | ALMs | LUTs |
| **JOP Core** | **5,426** | **5,447** | **5,447 x2** | **1,821** | |
| — Pipeline | 2,948 | 2,999 | 2,999 x2 | 928 | |
| — Memory controller | 985 | 960 | 960 x2 | 357 | |
| — Method cache | 599 | 600 | 600 x2 | 143 | |
| — Object cache | 899 | 892 | 892 x2 | 393 | |
| Memory backend | 103 | 657 | 657 | 231 | |
| I/O (BmbSys + BmbUart) | 326 | 333 | ~660 | 138 | |
| BMB Arbiter + CmpSync | — | — | ~200 | — | |
| **System total** | **5,856** | **6,461** | **~12,400** | **2,231** | **9,879** |
| % of EP4CGX150 (150K LEs) | 4% | 4% | 8% | — | — |
| Registers | 2,108 | 2,428 | ~4,900 | 2,698 | 9,603 |
| Block RAM | 1,054 Kbit | 28 Kbit | 56 Kbit | 28 Kbit | 9 Kbit |

Notes:
- EP4CGX150 BRAM uses 1,054 Kbit block RAM for program memory (128 M9Ks); SDRAM builds store programs in external RAM
- SMP (2-core) uses ~8% of EP4CGX150's 150K LEs, leaving substantial headroom for additional cores
- Artix-7 total includes MIG DDR3 controller + write-back cache
- Vivado does not report per-hierarchy utilization; Artix-7 core-only numbers not available from build reports

## Implementation Status

### Complete

- **Pipeline**: All four stages — bytecode fetch/translate, microcode fetch, decode, execute (stack)
- **Memory controller**: BMB bus with two-layer design (combinational + state machine for BC fill, getfield, iaload), pipelined BC fill overlaps memory reads with JBC writes, configurable BMB burst reads for SDRAM, hardware `memCopy` for GC object relocation
- **Method cache**: 16-block tag-only cache (32 words/block, FIFO replacement) skips redundant bytecode fills; 2-cycle hit, 3-cycle + fill on miss
- **Object cache**: 16-entry fully associative field cache (8 fields/entry, FIFO replacement) shortcuts getfield to 0 busy cycles on hit; write-through on putfield; invalidated on `stidx`/`cinval`
- **Stack buffer**: 256-entry on-chip RAM (64 for 32 local variables + 32 constants, 192 for operand stack) with spill/fill, ALU, shifter, 33-bit comparator
- **Jump table**: Bytecode-to-microcode translation (generated from `jvm.asm` by Jopa)
- **Multiplier**: 17-cycle radix-4 Booth multiplier
- **I/O subsystem**: `BmbSys` (cycle/microsecond counters, watchdog, CPU ID) and `BmbUart` (TX/RX with 16-entry FIFOs) as reusable `jop.io` components
- **SDRAM system (primary)**: `JopSdramTop` / `JopCyc5000Top` — serial boot over UART into SDR SDRAM using Altera `altera_sdram_tri_controller` (QMTECH EP4CGX150 at 100 MHz + Trenz CYC5000 at 80 MHz). Both support `cpuCnt` parameter for single-core or SMP
- **SMP (2-core)**: `JopSdramTop(cpuCnt=2)` — 2-core SMP with round-robin BMB arbiter, `CmpSync` global lock for `monitorenter`/`monitorexit`, per-core `BmbSys` with unique CPU ID, boot synchronization via `IO_SIGNAL`. Verified on QMTECH EP4CGX150 hardware (both cores running independently, per-core LED watchdog)
- **BRAM system**: `JopBramTop` — complete system with on-chip memory at 100 MHz (QMTECH EP4CGX150, Altera Cyclone IV)
- **DDR3 system**: `JopDdr3Top` — serial boot over UART through write-back cache into DDR3 at 100 MHz (Alchitry Au V2, Xilinx Artix-7), with standalone `Ddr3ExerciserTop` memory test. GC hangs at first collection on FPGA (passes in simulation)
- **Microcode tooling**: Jopa assembler generates VHDL and Scala outputs from `jvm.asm`
- **GC support**: Hardware `memCopy` for stop-the-world garbage collection, tested with allocation-heavy GC app (98,000+ rounds on BRAM, 9,800+ on CYC5000 SDRAM, 2,000+ on QMTECH SDRAM)
- **Exception infrastructure**: Null pointer and array bounds detection states wired through pipeline to `BmbSys` exception register (checks currently disabled pending GC null-handle fix)
- **Simulation**: BRAM sim, SDRAM sim, serial boot sim, latency sweep (0-5 extra cycles), GC stress test, GHDL event-driven sim

### Known Issues

- **DDR3 GC hang** — GC-enabled apps hang at the first garbage collection (R12) on the Alchitry Au V2. Passes in all simulations including Vivado xsim with real MIG RTL. DDR3 data path verified correct via exerciser (780+ loops) and trace replayer (16K entries). Root cause is likely physical timing at the MIG UI interface under heavy GC traffic. See [investigation notes](docs/ddr3-gc-hang.md).
- **Exception detection disabled** — Null pointer and array bounds check states are implemented and wired through the pipeline, but currently disabled in `BmbMemoryController`. GC's conservative stack scanning accesses handle address 0 via getfield/iaload; with checks enabled this correctly throws `EXC_NP` but crashes the GC. Re-enable after fixing GC `push()` to skip null refs.

### Next Steps

- Memory controller — remaining features from VHDL `mem_sc.vhd`:
  - **Exception detection** (MEDIUM) — states and wiring implemented but **currently disabled**: GC accesses null handles during conservative stack scanning. VHDL `ialrb` upper bounds check is also dead code (gated by `rdy_cnt /= 0`). Re-enable after fixing GC `push()` to skip null refs.
  - **Atomic memory operations** (LOW — multicore only) — `atmstart`/`atmend` inputs exist in `MemCtrlInput` but are never processed; VHDL sets an `atomic` output flag for monitorenter/monitorexit
  - **Address translation on read paths** (LOW — multicore only) — VHDL applies combinational `translateAddr` (GC copy relocation) to all reads; SpinalHDL only applies within copy states (single-core simplification, causes timing violation at 100 MHz)
  - **Scoped memory / illegal assignment** (LOW — RTSJ only) — VHDL tracks `putref_reg` and `dest_level` for scope checks on putstatic, putfield, iastore; SpinalHDL has no scope tracking
  - **Data cache control signals** (LOW — performance) — VHDL outputs `state_dcache` (bypass/direct_mapped/full_assoc per operation), `tm_cache` (disable caching during BC fill); SpinalHDL has none
  - **Fast-path array access (`iald23`)** (LOW — performance) — VHDL shortcut state overlaps address computation with data availability for single-cycle memory; SpinalHDL uses uniform HANDLE_* states
- Interrupt handling — verify timer interrupts and scheduler preemption work correctly
- Java test cases from original JOP (`/srv/git/jop/java/target/src/test/{jvm,jvmtest}`)
- Performance measurement
- DDR3 GC hang — resolve the R12 hang on Alchitry Au V2 ([investigation notes](docs/ddr3-gc-hang.md))
- DDR3 burst optimization — method cache fills could use burst reads through the cache bridge
- SMP GC — garbage collection across multiple cores (requires address translation on read paths)
- Const.java -> pull out Const and Configuration — core(s)/system/memory/caches
- Jopa refactor
- JOPizer/WCETPreprocess — refactor, updated libraries
- Target JDK modernization (8 as minimum)
- Port target code — networking, etc.
- Eclipse tooling — microcode/Java debug via Verilator simulation and FPGA remote debug
- Additional FPGA board targets
- Stack cache — extend to external memory with spill/fill for deeper stack support
- object cache is slowing clock - is it worth it?
- add quartus pll generator
- Faster serial download — currently limited by per-word USB round-trip latency (~15s for 32KB)
- Use Exerciser to find boundary performance for SDRAM/DDR3

## Key Technical Details

- **Bus**: SpinalHDL BMB (Bus Master Bridge). BRAM gives single-cycle accept, next-cycle response (matches original SimpCon `rdy_cnt=1`). SDRAM and DDR3 stall automatically via busy signal.
- **Memory controller**: Layer 1 is combinational (simple rd/wr). Layer 2 is a state machine for multi-cycle operations (bytecode fill, getfield, array access). BC fill is pipelined — issues the next read while writing the previous response to JBC RAM, saving ~1 cycle per word. Configurable burst reads (`burstLen=4` for SDR SDRAM). Hardware `memCopy` state machine for GC object relocation.
- **DDR3 subsystem** (`jop.ddr3` package): Write-back cache bridge (`BmbCacheBridge`) converts 32-bit BMB transactions to 128-bit cache lines. `LruCacheCore` provides a 16-set write-back cache with LRU replacement and dirty byte tracking. `CacheToMigAdapter` interfaces with the Xilinx MIG DDR3 controller. Clock wizard generates 100 MHz system and 200 MHz reference clocks from the board oscillator.
- **Object cache**: Fully associative field value cache (16 entries, 8 fields each). Getfield hits return data in 0 busy cycles (combinational tag match, registered data output). Putfield does write-through on tag hit. FIFO replacement, invalidated on array stores and explicit `cinval`.
- **Handle format**: `H[0]` = data pointer, `H[1]` = array length. Array elements start at `data_ptr[0]`.
- **I/O subsystem**: Reusable `BmbSys` and `BmbUart` components in `jop.io` package. System slave provides clock cycle counter, prescaled microsecond counter, watchdog register, and CPU ID. UART slave provides buffered TX/RX with 16-entry FIFOs.
- **SMP**: `JopSdramTop(cpuCnt=N)` / `JopCyc5000Top(cpuCnt=N)` instantiate N `JopCore`s with a round-robin BMB arbiter for shared memory access. `CmpSync` provides a global lock (round-robin fair arbitration) for `monitorenter`/`monitorexit`. Each core has its own `BmbSys` (unique CPU ID, independent watchdog). Core 0 initializes the system; other cores wait for a boot signal via `IO_SIGNAL`.
- **Serial boot**: Microcode polls UART for incoming bytes, assembles 4 bytes into 32-bit words, writes to external memory. Download script (`download.py`) sends `.jop` files with word-level echo verification.

## Documentation

Design notes and investigation logs in `docs/`:

- [Microcode and ROMs](docs/MICROCODE_AND_ROMS.md) — microcode architecture, ROM/RAM layout, Jopa assembler
- [Microcode Instructions](docs/microcode.md) — table of all microcode instructions and encodings
- [Stack Architecture](docs/STACK_ARCHITECTURE.md) — stack buffer, spill/fill, local variables
- [Jopa Tool](docs/JOPA_TOOL.md) — microcode assembler usage and output formats
- [Implementation Notes](docs/implementation-notes.md) — bugs found, cache details, I/O subsystem, SMP, memCopy
- [Cache Analysis](docs/cache-analysis.md) — method cache and object cache performance analysis
- [Memory Controller Comparison](docs/memory-controller-comparison.md) — VHDL vs SpinalHDL memory controller
- [Memory Architecture Plan](docs/memory-architecture-plan.md) — memory subsystem design decisions
- [Stack Immediate Timing](docs/stack-immediate-timing.md) — stack stage timing for immediate operations
- [SDR SDRAM GC Hang](docs/sdr-sdram-gc-hang.md) — resolved: SpinalHDL SdramCtrl DQ timing issue
- [DDR3 GC Hang](docs/ddr3-gc-hang.md) — unresolved: GC hangs at R12 on Alchitry Au V2

## References

- JOP project: https://github.com/jop-devel/jop
- JOP web site: https://www.jopdesign.com/
- JOP Thesis: Martin Schoeberl, [JOP: A Java Optimized Processor for Embedded Real-Time Systems](https://www.jopdesign.com/thesis/thesis.pdf)
- SpinalHDL: https://spinalhdl.github.io/SpinalDoc-RTD/
- CocoTB: https://docs.cocotb.org/

## License

TBD - Following original JOP licensing
