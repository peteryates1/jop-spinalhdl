# JOP - Java Optimized Processor (SpinalHDL)

A complete reimplementation of the [Java Optimized Processor](https://github.com/jop-devel/jop) (JOP) in [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/). JOP is a hardware implementation of the Java Virtual Machine as a soft-core processor for FPGAs, originally developed by Martin Schoeberl. See [jopdesign.com](https://www.jopdesign.com/) for the original project.

This port runs Java programs on FPGA hardware with BRAM or SDRAM memory backends.

Built with [Claude Code](https://claude.ai/claude-code).

## Status

**Working on hardware.** The processor boots and runs Java programs ("Hello World!" in a loop) on the QMTECH EP4CGX150 board at 100 MHz:

- **BRAM mode**: Self-contained, program embedded in block RAM
- **SDRAM mode**: Serial boot, downloads `.jop` files over UART into W9825G6JH6 SDRAM

## Project Goals

- Port JOP from VHDL to SpinalHDL/Scala for modern tooling and configurability
- Maintain cycle-accurate compatibility with the original implementation
- Target multiple FPGA boards with configurable system generation
- Upgrade Java target from JDK 1.5/1.6 to modern versions (future)

## Architecture

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
│└──────────────┘│ └──────────────┘  └──────────────┘    └──────────────┘    └──────────────┘  |
|     memory     │                                                                             |
|   controller   │◀────────────────────────────────────────────────────────────────────────────┘
└───────┬────────┘
        │  BMB Bus
        ├────────────────┐
 ┌──────┴───────┐ ┌──────┴───────┐
 |    memory    │ |     i/o      │
 | (BRAM/SDRAM) │ | (sys, uart)  │
 └──────────────┘ └──────────────┘
```

The pipeline fetches Java bytecodes, translates them via a jump table into microcode addresses, fetches and decodes microcode instructions, then executes them on a two-register stack machine with a 256-entry on-chip stack RAM (64 entries reserved for local variables and constants, 192 for the operand stack).

Memory access uses SpinalHDL's BMB (Bus Master Bridge) interconnect, supporting both on-chip BRAM (single-cycle response) and off-chip SDR SDRAM (variable latency with automatic pipeline stalling).

## Project Structure

```
jop/
├── spinalhdl/src/main/scala/jop/
│   ├── pipeline/              # Pipeline stages (fetch, decode, stack, bytecode)
│   ├── memory/                # Memory controller, method cache, SDRAM ctrl
│   ├── io/                    # I/O slaves (BmbSys, BmbUart)
│   ├── system/                # System integration (JopCore, FPGA tops)
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
│   ├── qmtech-ep4cgx150-bram/ # BRAM FPGA project (Quartus)
│   └── qmtech-ep4cgx150-sdram/# SDRAM FPGA project (Quartus)
├── java/
│   ├── tools/jopa/            # Jopa microcode assembler
│   ├── tools/src/             # JOPizer, PreLinker, common framework
│   ├── target/src/            # JOP runtime + JDK stubs (JDK 6)
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
- **Quartus Prime** (for FPGA synthesis, optional)
- **Python 3.8+**, **GHDL**, **CocoTB** (for VHDL reference tests, optional)

### Build and Run Simulation

```bash
# 1. Build microcode assembler and generate microcode
cd java/tools/jopa && make
cd ../../../asm && make

# 2. Compile SpinalHDL (from project root)
sbt compile

# 3. Run BRAM simulation (prints "Hello World!" in a loop)
sbt "Test / runMain jop.system.JopCoreBramSim"

# 4. Run SDRAM simulation
sbt "Test / runMain jop.system.JopCoreWithSdramSim"
```

### Build for FPGA

```bash
# BRAM target (self-contained, 100 MHz)
cd fpga/qmtech-ep4cgx150-bram
make generate    # Generate Verilog from SpinalHDL
make build       # Quartus synthesis
make program     # Program FPGA via USB-Blaster
make monitor     # Open serial monitor (1 Mbaud)

# SDRAM target (serial boot, 100 MHz)
cd fpga/qmtech-ep4cgx150-sdram
make microcode   # Assemble serial boot microcode
make generate    # Generate Verilog
make build       # Quartus synthesis
make program     # Program FPGA
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

| Board | FPGA | Memory | Status |
|-------|------|--------|--------|
| [QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Cyclone IV GX | BRAM | Working at 100 MHz |
| [QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Cyclone IV GX | W9825G6JH6 SDRAM | Working at 100 MHz |

### Planned

- [Trenz CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A) - Cyclone V E
- [Alchitry Au V2](https://shop.alchitry.com/products/alchitry-au) - Xilinx Artix-7

## Implementation Status

### Complete

- **Pipeline**: All four stages — bytecode fetch/translate, microcode fetch, decode, execute (stack)
- **Memory controller**: BMB bus with two-layer design (combinational + state machine for BC fill, getfield, iaload), pipelined BC fill overlaps memory reads with JBC writes, configurable BMB burst reads for SDRAM
- **Method cache**: 16-block tag-only cache (32 words/block, FIFO replacement) skips redundant bytecode fills; 2-cycle hit, 3-cycle + fill on miss
- **Stack buffer**: 256-entry on-chip RAM (64 for 32 local variables + 32 constants, 192 for operand stack) with spill/fill, ALU, shifter, 33-bit comparator
- **Jump table**: Bytecode-to-microcode translation (generated from `jvm.asm` by Jopa)
- **Multiplier**: 17-cycle radix-4 Booth multiplier
- **I/O subsystem**: `BmbSys` (cycle/microsecond counters, watchdog, CPU ID) and `BmbUart` (TX/RX with 16-entry FIFOs) as reusable `jop.io` components
- **BRAM system**: `JopBramTop` — complete system with on-chip memory at 100 MHz
- **SDRAM system**: `JopSdramTop` — serial boot over UART into SDR SDRAM at 100 MHz
- **Microcode tooling**: Jopa assembler generates VHDL and Scala outputs from `jvm.asm`
- **Simulation**: BRAM sim, SDRAM sim, serial boot sim, latency sweep (0-5 extra cycles), echo test

### Next Steps

- Memory controller — missing features from VHDL `mem_sc.vhd`:
  - **Exception detection** (HIGH) — `NP_EXC`, `AB_EXC`, `HANDLE_BOUND_READ/WAIT` states implemented with full signal wiring, but **currently disabled**: GC accesses null handles during conservative stack scanning. VHDL `ialrb` upper bounds check is also dead code (gated by `rdy_cnt /= 0`). Re-enable after fixing GC `push()` to skip null refs. Also needs `excw` wait state (VHDL waits for `rdy_cnt=0` before returning to idle).
  - **Atomic memory operations** (LOW — multicore only) — `atmstart`/`atmend` inputs exist in `MemCtrlInput` but are never processed; VHDL sets an `atomic` output flag for monitorenter/monitorexit
  - **Address translation on read paths** (LOW — multicore only) — VHDL applies combinational `translateAddr` (GC copy relocation) to all reads; SpinalHDL only applies within copy states (single-core simplification, causes timing violation at 100 MHz)
  - **Scoped memory / illegal assignment** (LOW — RTSJ only) — VHDL tracks `putref_reg` and `dest_level` for scope checks on putstatic, putfield, iastore; SpinalHDL has no scope tracking
  - **Object cache (`ocache`)** (LOW — performance) — VHDL integrates a field cache that shortcuts getfield to IDLE on hit; SpinalHDL always takes the full state machine path. See also `docs/cache-analysis.md`
  - **Data cache control signals** (LOW — performance) — VHDL outputs `state_dcache` (bypass/direct_mapped/full_assoc per operation), `tm_cache` (disable caching during BC fill), `cinval` (cache invalidation); SpinalHDL has none
  - **Fast-path array access (`iald23`)** (LOW — performance) — VHDL shortcut state overlaps address computation with data availability for single-cycle memory; SpinalHDL uses uniform HANDLE_* states
- Interrupt handling — verify timer interrupts and scheduler preemption work correctly
- Java test cases from original JOP (`/home/peter/git/jop/java/target/src/test/{jvm,jvmtest}`)
- Performance measurement
- Xilinx/AMD Artix-7 and DDR3 on Alchitry Au
  - Method cache optimization for DDR3 burst performance
- Multicore
- Configuration — core(s)/system/memory/caches
- Jopa refactor
- JOPizer/WCETPreprocess — refactor, updated libraries
- Target JDK modernization (8 as minimum)
- Port target code — networking, etc.
- Eclipse tooling — microcode/Java debug via Verilator simulation and FPGA remote debug
- Additional FPGA board targets

## Key Technical Details

- **Bus**: SpinalHDL BMB (Bus Master Bridge). BRAM gives single-cycle accept, next-cycle response (matches original SimpCon `rdy_cnt=1`). SDRAM stalls automatically via busy signal.
- **Memory controller**: Layer 1 is combinational (simple rd/wr). Layer 2 is a state machine for multi-cycle operations (bytecode fill, getfield, array access). BC fill is pipelined — issues the next read while writing the previous response to JBC RAM, saving ~1 cycle per word. Configurable burst reads (`burstLen=4` for SDR SDRAM).
- **Handle format**: `H[0]` = data pointer, `H[1]` = array length. Array elements start at `data_ptr[0]`.
- **I/O subsystem**: Reusable `BmbSys` and `BmbUart` components in `jop.io` package. System slave provides clock cycle counter, prescaled microsecond counter, watchdog register, and CPU ID. UART slave provides buffered TX/RX with 16-entry FIFOs.
- **Serial boot**: Microcode polls UART for incoming bytes, assembles 4 bytes into 32-bit words, writes to SDRAM. Download script (`download.py`) sends `.jop` files with byte-by-byte echo verification.

## References

- JOP project: https://github.com/jop-devel/jop
- JOP web site: https://www.jopdesign.com/
- JOP Thesis: Martin Schoeberl, [JOP: A Java Optimized Processor for Embedded Real-Time Systems](https://www.jopdesign.com/thesis/thesis.pdf)
- SpinalHDL: https://spinalhdl.github.io/SpinalDoc-RTD/
- CocoTB: https://docs.cocotb.org/

## License

TBD - Following original JOP licensing
