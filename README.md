# JOP - Java Optimized Processor (SpinalHDL)

A complete reimplementation of the [Java Optimized Processor](https://github.com/jop-devel/jop) (JOP) in [SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/). JOP is a hardware implementation of the Java Virtual Machine as a soft-core processor for FPGAs, originally developed by Martin Schoeberl. See [jopdesign.com](https://www.jopdesign.com/) for the original project.

This port runs Java programs on FPGA hardware. The primary development platform is the **QMTECH EP4CGX150** (Altera Cyclone IV GX + SDR SDRAM), which supports single-core and SMP (up to 16-core) configurations with stable garbage collection. The **QMTECH Wukong V3** (Xilinx Artix-7 XC7A100T + DDR3) provides the full-featured configuration with all four hardware compute units, and the **A-E115FB** (Cyclone IV E + 1 GB DDR2) is the large-memory platform.

**Current state and open work items are tracked in [docs/current-status.md](docs/current-status.md)** — start there rather than here if you are picking the project up.

Built with [Claude Code](https://code.claude.com/docs/en/quickstart).

## Status

**Working on hardware.** The processor boots and runs Java programs on eight boards, at 40–100 MHz depending on the memory subsystem:

- **SDRAM + SMP (primary)**: up to 16-core SMP on QMTECH EP4CGX150 (Cyclone IV) and Trenz CYC5000 (Cyclone V) — all cores running independently with CmpSync global lock (or optional IHLU per-object locking), round-robin BMB arbitration, and GC stop-the-world halt (halts all other cores during garbage collection)
- **SDRAM (single-core)**: Serial boot over UART into SDR SDRAM on two boards — QMTECH EP4CGX150 (Cyclone IV) and Trenz CYC5000 (Cyclone V, W9864G6JT)
- **BRAM**: Self-contained, program embedded in block RAM (QMTECH EP4CGX150)
- **DDR3**: Serial boot through write-back cache into DDR3 (Alchitry Au V2, Xilinx Artix-7, full 256MB addressed) — single-core and 2-core SMP verified on hardware with GC (67K+ rounds single-core, NCoreHelloWorld SMP). See [DDR3 notes](docs/gc/ddr3-gc-hang.md).
- **DB_FPGA (full I/O)**: QMTECH EP4CGX150 + DB_FPGA daughter board at 80 MHz — Ethernet 1Gbps GMII, VGA text 80x30, SD card native 4-bit, TCP/IP networking (ICMP/UDP/TCP echo, DHCP, DNS, HTTP file server), FAT32 filesystem, all verified on hardware. JVM test suite: 64/64 on hardware.
- **Wukong DDR3 (full-featured)**: All four compute units (IntegerComputeUnit + FloatComputeUnit + LongComputeUnit + DoubleComputeUnit) with DSP imul, Ethernet (GMII 1Gbps), and SD Native. JVM test suite: 66/66 on hardware.
- **DDR2 (1 GB)**: Serial boot through a 256-bit-line write-back cache into 1 GB DDR2 on the A-E115FB (Cyclone IV E, ALTMEMPHY half-rate at 75 MHz). Memory verified at full capacity (77 passes, ~154 GB, zero errors); JVM suite 66/66 and the full GC suite pass on a ~1.07 GB heap. See [DDR2 bring-up](docs/boards/ae115fb-ddr2-bringup.md).
- **ECP5 / open-source toolchain**: Colorlight i5 v7.0 — 8 MB SDRAM at 40 MHz, DoAll 66/66, built entirely with yosys + nextpnr-ecp5 + ecppack. The only board here that needs no vendor tools. See [i5 bring-up](docs/boards/colorlight-i5-bringup.md).
- **8-core SMP**: Verified on QMTECH EP4CGX150 — all 8 cores running independently with per-core UART, tested via Pico debug probe
- **GC (generational)**: Generational collector is the default — hardware card-marking write barrier, nursery/tenure split, minor collections bounded by a young-object cap. Verified on four boards, with the minor pause measured on each:

  | board | memory | worst minor pause |
  |---|---|---:|
  | Trenz CYC5000 | 8 MB SDR | 10.2 ms |
  | QMTECH EP4CGX150 | SDR | 11.9 ms |
  | QMTECH XC7A100T + DB V5 | 256 MB DDR3 | 12.5 ms |
  | A-E115FB | 1 GB DDR2 | 14.1 ms |

  Falls back automatically to the classic mark-compact collector on cores built without a card table (generational is unsound without the barrier), and reports which collector is active at boot. Soak-tested to 705k rounds (EP4CGX150) and 537k rounds (A-E115FB) fault-free. See [GC stage 3 follow-ups](docs/gc/stage3-followups.md).

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
 │  translate   │         │          │              │ |  │              │    │ (tos/nos/cu) │  |
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
│   ├── core/                 # Compute units (IntegerCU, FloatCU, LongCU, DoubleCU, ComputeUnitTop)
│   ├── memory/                # Memory controller, method/object/array cache, SDRAM ctrl
│   ├── ddr3/                  # DDR3 subsystem (cache, MIG adapter, clock wizard)
│   ├── ddr2/                  # DDR2 subsystem (ALTMEMPHY blackbox, CacheToDdr2Adapter)
│   ├── io/                    # I/O slaves (BmbSys, BmbUart, BmbEth, BmbMdio, BmbSdNative, BmbSdSpi, BmbVgaText, Ihlu, CmpSync)
│   ├── debug/                 # Debug subsystem (protocol, controller, breakpoints, UART)
│   ├── config/                # Configuration hierarchy (JopConfig, Board, Parts, BoardDesign)
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
│   ├── quartus.mk, vivado.mk, console.mk  # the flow, once per toolchain
│   ├── scripts/vivado_*.tcl   # project/non-project build, shared by all Vivado boards
│   ├── alchitry-au/           # Alchitry Au V2 DDR3 (Vivado)
│   ├── alchitry-au-ddr3-test/ # DDR3 exerciser — SAME board, second project
│   ├── a-e115fb-ddr2/         # 1 GB DDR2 (Quartus 18.1, Cyclone IV E)
│   ├── colorlight-i5/         # ECP5 SDRAM (yosys/nextpnr, no vendor tools)
│   ├── cyc5000-sdram/         # Trenz CYC5000 SDRAM (Quartus, Cyclone V)
│   ├── max1000/               # Arrow MAX1000 fit check (Quartus, 10M08)
│   ├── qmtech-ep4cgx150-bram/      # BRAM, embedded image (Quartus)
│   ├── qmtech-ep4cgx150-bram-serial/ # BRAM, serial boot (Quartus)
│   ├── qmtech-ep4cgx150-sdram/     # primary SDRAM board (Quartus)
│   ├── qmtech-ep4cgx150-sdram-test/# SDRAM exerciser
│   ├── qmtech-xc7a100t-dbfpga-v5/  # DDR3 + DB_FPGA V5 (Vivado)
│   └── qmtech-xc7a100t-wukong/     # Wukong DDR3/SDR/dual (Vivado)
├── java/
│   ├── tools/src/             # JOPizer, PreLinker, Jopa, common framework
│   ├── runtime/src/           # JOP runtime + JDK stubs (JDK 6)
│   └── apps/                  # Java application builds
├── build/<config>/             # EVERYTHING generated — see docs/build-structure.md
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

# 3. Build Java toolchain, runtime, and every app the simulations load.
#    `make all` builds only the three HelloWorlds used by steps 4-5; the GC,
#    SMP, JVM-suite and benchmark simulations each load a different program.
#    `test-apps` builds all of them, and is what the steps below assume.
cd java && make test-apps && cd ..

# 4. Run BRAM simulation (prints "Hello World!" in a loop)
sbt "Test / runMain jop.system.JopCoreBramSim"

# 4b. Run unified JopTop BRAM simulation (same output, uses config-driven JopTop)
sbt "Test / runMain jop.system.JopTopBramSim"

# 5. Run SDRAM simulation (same Hello World, through the SDRAM controller;
#    slower to boot than BRAM, so give it time)
sbt "Test / runMain jop.system.JopCoreWithSdramSim"

# 6. Run GC stress test — needs GcStressTest.jop from step 3.
#    Prints "PASS: N GC cycles observed"
sbt "Test / runMain jop.system.JopSmallGcBramSim"

# 7. Run SMP simulation (2-core, NCoreHelloWorld — both cores toggle watchdog)
sbt "Test / runMain jop.system.JopSmpNCoreHelloWorldSim"

# 8. Run SMP GC simulation (2-core, garbage collection stress test)
sbt "Test / runMain jop.system.JopSmpBramSim"
```

### Build for FPGA

**Everything generated goes under `build/<config>/`** — RTL, constraints, the
Quartus/Vivado project, the Java image and the bitstream — keyed by the
invocation, so `ep4cgx150Smp 4` and `ep4cgx150Smp 12 36` never overwrite each
other. The flow itself is written once per toolchain (`fpga/quartus.mk`,
`fpga/vivado.mk`, `fpga/console.mk`, `fpga/scripts/vivado_*.tcl`) and each board
Makefile declares only what is specific to it.

**Read [docs/build-structure.md](docs/build-structure.md) first** — it covers the
layout, what a board declares versus inherits, and the two things that are never
constants (the baud comes from the build's own summary; probes and serial ports
resolve by serial number, never by `/dev/ttyUSB*` path).

#### Start here: Colorlight i5

**If you are running on hardware for the first time, use the Colorlight i5.**
It is the least you can go wrong with: one USB cable carries both programming
and the console, the whole toolchain is open source (`yosys`, `nextpnr-ecp5`,
`ecppack`, `openFPGALoader`) so there is no vendor install, and there is no
separate JTAG blaster to select — which is the step most likely to bite you on
the multi-board setups below.

```bash
cd fpga/colorlight-i5
make bitstream program download
```

Expect, in order: a `nextpnr` timing line, the bitstream loading, then the
board talking:

```
Info: Max frequency for clock '$glbnet$i5Pll_clkout0': 49.40 MHz (PASS at 40.00 MHz)
Loading: [==================================================] 100.00%
Done
...
Hello World!
Hello World!
```

**`nextpnr` prints `Max frequency` TWICE** — a post-place estimate first, then
the post-route figure. They can differ by 17 MHz on this board, and only the
second one is real. Read the last one.

#### The primary development board: QMTECH EP4CGX150

The board most configurations target, and the one with SMP up to 16 cores. It
needs Quartus and a JTAG blaster, so it is a bigger first step than the i5.

```bash
cd fpga/qmtech-ep4cgx150-sdram
make all                       # microcode + generate + build
make program download monitor
make console-info              # which tty, which baud, which image
```

`download` sends `build/<config>/java/apps/Smallest/HelloWorld.jop` and then
watches the console, so success looks the same as the i5 above — a boot banner
followed by `Hello World!` repeating. `program` runs a read-only JTAG chain
scan first and refuses if the board on the cable is not the one this
configuration expects; `quartus_pgm` reports success on a chain it never read,
so that check is the only thing standing between you and a silent no-op.

# Other configurations of the same board — each gets its own build/<config>/
make smp CORES=4               # 4-core SMP   (also CORES=8, CORES=12 MHZ=36)
make dbfpga                    # + Ethernet / VGA text / SD
make dbfpga-vgadma             # + VGA DMA framebuffer
make mc-fallback               # microcode fallback coverage build

# Xilinx — the Wukong drives seven configurations from one directory
cd fpga/qmtech-xc7a100t-wukong
make ddr3-build ddr3-program ddr3-download ddr3-monitor
make ddr3-smp-build DDR3_SMP_CORES=8
make jop-sdram-build           # same board, SDR memory path
make dual-build                # two independent clusters, DDR3 + SDR

# Alchitry Au V2 (Artix-7 + DDR3)
cd fpga/alchitry-au && make all && make run

# XC7A100T + DB_FPGA V5
cd fpga/qmtech-xc7a100t-dbfpga-v5 && make ddr3-build ddr3-program ddr3-download

# A-E115FB — 1 GB DDR2. Needs Quartus 18.1: Intel dropped Cyclone IV DDR2
# ALTMEMPHY after that version.
cd fpga/a-e115fb-ddr2 && make all && make program download monitor
# Verified on hardware 2026-08-29: boots and prints Hello World! over its CH340
# at 2 Mbaud. If the console will not open (EIO, and dmesg shows "-32"), the
# adapter's control endpoint has stalled: `fpga/scripts/usb_serial_map --reset
# ae115fb` clears it without unplugging anything.

# Trenz CYC5000. Quartus cannot see the board's FT2232H "Arrow USB Blaster",
# so `make program` converts the .sof to .rbf and uses openFPGALoader.
cd fpga/cyc5000-sdram && make all && make program download monitor

# Verify a board end to end, INCLUDING TIMING, in one command. It refuses to
# call a run a pass if the build missed timing: a bitstream can print the right
# answer while failing setup by 2.5 ns.
fpga/scripts/hw_verify.py ep4cgx150Serial --app JvmTests/DoAll --expect-text "66/66"
```

Verilog can also be generated directly for any preset:

```bash
sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"   # QMTECH SDRAM
sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 8"    # QMTECH 8-core SMP
sbt "runMain jop.system.JopTopVerilog cyc5000Serial"     # CYC5000 SDRAM
sbt "runMain jop.system.JopTopVerilog colorlightI5Sdram" # Colorlight i5 (ECP5)
sbt "runMain jop.system.JopTopVerilog auSerial"          # Alchitry Au V2 DDR3
sbt "runMain jop.system.JopTopVerilog wukongDdr3"        # Wukong DDR3
sbt "runMain jop.system.JopTopVerilog wukongFull"        # Wukong DDR3, all CUs + DSP
sbt "runMain jop.system.JopTopVerilog xc7a100tDbSerial"  # XC7A100T + DB_FPGA DDR3
sbt "runMain jop.system.JopTopVerilog ae115fbDdr2"       # A-E115FB 1 GB DDR2
sbt "runMain jop.system.JopTopVerilog max1000Sdram"      # MAX1000 (fit check only)
sbt "runMain jop.system.JopTopVerilog minimum"           # Minimum resources
```

Add `buildtree` to place the output under `build/<config>/` rather than the
legacy in-tree location; every board Makefile does this already.

> **Flash boot is currently REGRESSED.** Both the EP4CGX150 and the Alchitry Au
> booted autonomously from SPI flash and were fully hardware-verified, but the
> tops that generated those bitstreams were deleted in `7258661` (2026-03-13)
> and no `JopConfig` preset sets `bootMode = BootMode.Flash`. The UART flash
> programmers still work; the image they program cannot currently be built.
> See status item 82 and [flash boot](docs/boards/flash-boot.md).

### Running Tests

```bash
# SpinalSim tests (Verilator)
sbt test

# Formal verification (SymbiYosys + Z3) — 133 properties across 23 suites
sbt "testOnly jop.formal.*"

# Latency sweep (verify correct operation at 0-5 extra memory cycles)
sbt "Test / runMain jop.system.JopCoreLatencySweep"

# Timer interrupt end-to-end test (5 interrupts, handler dispatch, ~2.6M cycles)
sbt "Test / runMain jop.system.JopInterruptSim"

# Debug protocol test (39 checks: ping, halt, step, registers, memory, breakpoints)
sbt "Test / runMain jop.system.JopDebugProtocolSim"

# JVM test suite (66 tests, all pass)
sbt "Test / runMain jop.system.JopJvmTestsBramSim"

# JVM test suite on 2-core SMP (65/66 pass — DeepRecursion excluded)
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
| [QMTECH EP4CGX150](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) | Altera Cyclone IV GX | BRAM (on-chip) | Quartus Prime | Working at 100 MHz. JTAG bank is **2.5 V**; on the level-shifted [pico-usb-debug-jtag](https://github.com/peteryates1/pico-usb-debug-jtag) cable permanently since 2026-08-29 (`jop_sdram.sof` in 43 s, plus ~30 s of chain detection, then boots and runs). A genuine USB-Blaster also works and is ~5x faster — same 6 MHz clock, but USB-latency-bound rather than TCK-bound ([why](docs/pico-dirtyjtag-setup.md)) |
| [Trenz CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A) | Altera Cyclone V E (5CEBA2U15C8N) | W9864G6JT SDR SDRAM (8 MB) | Quartus Prime | 80 MHz — JVM 66/66. Its FT2232H "Arrow USB Blaster" is invisible to Quartus; program with `openFPGALoader -b cyc5000` (needs `.rbf`, not `.sof`) |
| [A-E115FB](docs/boards/ep4ce115-ddr2-board.md) | Altera Cyclone IV E (EP4CE115F23I7) | 1 GB DDR2 SODIMM (also BRAM) | Quartus Prime 18.1 | 75 MHz — largest heap; JVM 66/66 + full GC suite on ~1.07 GB ([bring-up](docs/boards/ae115fb-ddr2-bringup.md)). Program with a **genuine USB-Blaster** — a bare Pico clone cannot configure it (no level shifter for its non-3.3 V JTAG bank). The Terasic blaster is parked on this board permanently now that the EP4CGX150 has a level-shifted Pico of its own, so the two no longer share a cable. Note this row says 2.5 V while `docs/pico-dirtyjtag-setup.md` says 1.8 V — measure VTREF on header pin 4 before trusting either |
| [Alchitry Au V2](https://shop.alchitry.com/products/alchitry-au) | Xilinx Artix-7 (XC7A35T) | MT41K128M16JT DDR3 (256MB) | Vivado | 100 MHz — single-core + SMP (2-core), full 256MB addressed, GC working ([details](docs/gc/ddr3-gc-hang.md)) |
| [QMTECH Wukong V3](docs/boards/qmtech-wukong-board.md) | Xilinx Artix-7 (XC7A100T) | MT41K128M16JT DDR3 (256MB) + W9825G6KH SDR SDRAM (32MB) | Vivado | 100 MHz — full featured (all 4 CUs + DSP imul), 66/66 JVM tests on hardware |
| [QMTECH XC7A100T + DB_FPGA V5](docs/boards/qmtech-xc7a100t-board.md) | Xilinx Artix-7 (XC7A100T-FGG676) | MT41K128M16JT DDR3 (256MB) | Vivado | 100 MHz — end-to-end on hardware, JVM 66/66, GC verified. UART and DirtyJTAG both via the on-board RP2040 |
| [Colorlight i5 v7.0](docs/boards/colorlight-i5-bringup.md) | Lattice ECP5 (LFE5U-25F) | 8 MB SDR SDRAM (32-bit) | **yosys / nextpnr-ecp5 / ecppack** | 40 MHz — DoAll 66/66. The only board needing **no vendor tools**; UART and JTAG both over the ext board's DAPLink |
| [Arrow MAX1000](https://www.arrow.com/en/products/max1000/arrow-development-tools) | Altera MAX 10 (10M08) | 8 MB SDR SDRAM | Quartus Prime | **Fit check only** — board not at this site. 8k LEs against the EP4CGX150's 149k, so it catches area regressions early. No 1- or 2-core configuration is known to fit yet (status item 84) |

### Resource Usage

Measured from the build reports in this tree (2026-08-04, commit `48243a0`).
Every column is a configuration verified on hardware in that state.

> **These numbers drift, and they drift DOWNWARD too.** Rebuilt on 2026-08-26
> from RTL work done for other reasons, the XC7A100T DDR3 build went from
> 22,547 LUTs (35.6 %) to **12,872 (20.3 %)** and its slack from +0.010 ns to
> **+0.242 ns**; the Alchitry Au went from 64.5 % to **59.3 %** LUT. Nothing was
> lost — all four compute units and every cache are still present — the design
> simply got smaller while nobody was rebuilding these boards. Treat the table
> as a snapshot of one commit, not as current, and re-measure before sizing
> anything on it. See status items 86 and 90.

**Units are not comparable across families.** Cyclone IV counts Logic Elements
(4-input LUT + FF), Cyclone V counts ALMs (fracturable 8-input LUT + 2 FFs),
Artix-7 counts LUTs (6-input).

| | EP4CGX150 SDR | A-E115FB DDR2 | CYC5000 SDR | XC7A100T DDR3 |
|---|:-:|:-:|:-:|:-:|
| clock | 100 MHz | 75 MHz | 80 MHz | 100 MHz |
| unit | LEs | LEs | ALMs | LUTs |
| **JopCore** | **6,765** | **6,488** | **4,189** | n/a |
| — Pipeline | 2,486 | 2,187 | 1,289 | |
| — Memory controller | 3,497 | 3,589 | 2,363 | |
| — Method cache | 605 | 591 | 290 | |
| — Object cache | 898 | 874 | 799 | |
| — Array cache | 710 | 665 | 420 | |
| — Card table | 311 | 276 | 197 | |
| **System total** | **8,386** | **31,170** | **3,161** | **22,273** |
| % of device | 6% | 27% | 34% | 35% |
| Registers | 4,326 | 17,264 | 4,574 | 14,096 |
| Block RAM | 219 Kbit (3%) | 978 Kbit (25%) | 121 Kbit (7%) | 19.5 BRAM (14%) |
| Timing (WNS) | +0.479 ns | +0.543 ns | +0.896 ns | +0.001 ns |

Notes:
- **Per-entity rows come from synthesis (`.map.rpt`) hierarchy; system totals are
  post-fit (`.fit.rpt`).** They will not sum — optimisation moves logic across
  boundaries after synthesis. Use the totals for sizing and the breakdown only
  for relative cost.
- **System totals are not comparable between columns**, because the memory
  subsystem dominates and differs: the A-E115FB carries an ALTMEMPHY DDR2
  controller plus a 32 KB write-back cache with 256-bit lines, and the XC7A100T
  a Xilinx MIG plus its cache, while the SDR boards have a small SDRAM
  controller. The JopCore rows are the fairer comparison.
- The card table is per-board sized from `cardTableBudgetBytes`; its block RAM
  is included in the memory figures (64 KB on the A-E115FB, hence the 25%).
- **XC7A100T timing is marginal at +0.001 ns** and one download in seven
  misbehaved during regression testing. Worth re-implementing for margin before
  relying on that board.
- Vivado does not report per-hierarchy utilization, so the Artix-7 core-only
  numbers are unavailable from build reports.
- Artix-7 uses `readSync` for stack cache bank RAMs (auto-derived from FPGA
  family), enabling Xilinx BRAM inference. Altera uses `readAsync` (M9K/M10K
  supports async reads natively). See [distributed RAM optimization](docs/analysis/artix7-distram-optimization.md)
- See [Artix-7 core count estimates](docs/analysis/artix7-core-estimates.md) for
  scaling projections across the Artix-7 family
- **Not re-measured**: BRAM-only and SMP configurations. The previous table
  carried figures for EP4CGX150 BRAM, EP4CGX150 2-core SMP and Artix-7 DDR3 SMP
  from an unrecorded commit; they have been dropped rather than left undated.
  Rebuild those presets to restore them.


## Implementation Status

### Complete

- **Pipeline**: All four stages — bytecode fetch/translate, microcode fetch, decode, execute (stack)
- **Memory controller**: BMB bus with two-layer design (combinational + state machine for BC fill, getfield, iaload), pipelined BC fill overlaps memory reads with JBC writes, configurable BMB burst reads for SDRAM, hardware `memCopy` for GC object relocation
- **Method cache**: 16-block tag-only cache (32 words/block, FIFO replacement) skips redundant bytecode fills; 2-cycle hit, 3-cycle + fill on miss
- **Object cache**: 16-entry fully associative field cache (8 fields/entry, FIFO replacement) shortcuts getfield to 0 busy cycles on hit; write-through on putfield; invalidated on `stidx`/`cinval`
- **Array cache**: 16-entry fully associative element cache (4 elements/line, FIFO replacement) shortcuts iaload to 0 busy cycles on hit; 4-element line fill on miss (burst read on SDRAM); write-through on iastore; SMP-safe via cross-core snoop invalidation; two VHDL bugs fixed (idx_upper slice, FIFO nxt advancement)
- **Stack buffer**: 256-entry on-chip RAM (64 for 32 local variables + 32 constants, 192 for operand stack) with spill/fill, ALU, shifter, 33-bit comparator. Optional 3-bank rotating stack cache with DMA spill/fill extends the stack to external memory (16-bit virtual SP, 192 entries per bank, per-core stack regions). See [system configuration](docs/architecture/system-configuration.md).
- **Jump table**: Bytecode-to-microcode translation (generated from `jvm.asm` by Jopa)
- **Compute units**: Four pipeline-integrated hardware compute units via `ComputeUnitTop` — **IntegerComputeUnit** (imul radix-4 ~18 cycles or DSP 1-cycle, idiv/irem binary restoring ~36 cycles), **FloatComputeUnit** (IEEE 754 single-precision: fadd/fsub/fmul/fdiv/i2f/f2i/fcmpl/fcmpg, fneg via microcode), **LongComputeUnit** (64-bit: ladd/lsub/lneg/lcmp ALU, lshl/lshr/lushr barrel shifter, lmul DSP cascade), **DoubleComputeUnit** (IEEE 754 double-precision: dadd/dsub/dmul/ddiv/dcmpl/dcmpg + i2d/d2i/l2d/d2l/f2d/d2f conversions). All use unified `stop`/`sthw`/`ldop` microcode pattern. Per-bytecode `Implementation` selection (Java/Microcode/Hardware) in `JopCoreConfig`. Conditional instantiation — only CUs needed by the config are generated.
- **I/O subsystem**: `BmbSys` (cycle/microsecond counters, timer interrupt, watchdog, CPU ID), `BmbUart` (TX/RX with 16-entry FIFOs, RX/TX interrupt outputs), `BmbEth` (Ethernet MAC with GMII 1Gbps TX/RX using SpinalHDL `MacEth`, 125 MHz PLL for TX, PHY clock for RX, dual-clock FIFOs for PHY clock domain crossing), `BmbMdio` (MDIO PHY management with registered outputs and PHY reset control), `BmbSdNative` (SD card native 4-bit mode, hardware CRC7/CRC16, 512-byte block FIFO, verified on hardware at 10 MHz — [details](docs/peripherals/db-fpga-sd-card.md)), `BmbSdSpi` (SD card SPI mode, byte-at-a-time transfer with hardware clock generation), `BmbVgaText` (80x30 text-mode VGA, 640x480@60Hz, 8x16 font, CGA palette, RGB565 output, 25 MHz pixel clock from PLL c3), and `BmbVgaDma` (640x480@60Hz RGB565 framebuffer from SDRAM via DMA, StreamFifoCC for CDC, frame-based DMA restart at vsync) as reusable `jop.io` components. Timer interrupts verified end-to-end in simulation (`JopInterruptSim`). VGA text verified on hardware ([details](docs/peripherals/db-fpga-vga-text.md))
- **Unified top-level**: `JopTop(config)` — single config-driven Component replaces all board-specific top files. Supports BRAM, SDR SDRAM, and DDR3 across Altera (Cyclone IV/V) and Xilinx (Artix-7) FPGAs. `simulation` flag enables Verilator-compatible mode (bypasses PLL/reset/MIG BlackBoxes). Verilog generation via `JopTopVerilog` with named presets.
- **SDRAM system (primary)**: Serial boot over UART into SDR SDRAM using Altera `altera_sdram_tri_controller` (QMTECH EP4CGX150 at 100 MHz + Trenz CYC5000 at 80 MHz). Single-core and SMP via `JopConfig` presets.
- **SMP (2-core)**: 2-core SMP with round-robin BMB arbiter, `CmpSync` global lock for `monitorenter`/`monitorexit`, per-core `BmbSys` with unique CPU ID, boot synchronization via `IO_SIGNAL`, and GC stop-the-world halt via `IO_GC_HALT`. Verified on QMTECH EP4CGX150, CYC5000, and Alchitry Au V2 hardware (both cores running independently with per-core LED watchdog)
- **BRAM system**: Complete system with on-chip memory at 100 MHz (QMTECH EP4CGX150, Altera Cyclone IV; Wukong XC7A100T, Xilinx Artix-7)
- **DDR3 system**: Serial boot over UART through 32KB 4-way write-back cache into DDR3 at 100 MHz (Alchitry Au V2, Xilinx Artix-7, full 256MB addressed). Single-core and 2-core SMP verified with GC (67K+ rounds single-core at 8MB, 1870+ rounds at 256MB). Standalone `Ddr3ExerciserTop` memory test and `Ddr3TraceReplayerTop` BMB trace verification also available.
- **Microcode tooling**: Jopa assembler generates VHDL and Scala outputs from `jvm.asm`
- **GC support**: Generational collector by default, over a mark-compact base. New objects allocate in a nursery carved off the top of the heap; a hardware card table (`CardTable`, per-core, sized from `cardTableBudgetBytes`) records tenure→nursery pointers so minor collections need not scan the tenured set. Minor pause is bounded by a young-object cap derived from measured per-handle sweep cost, and the dirty-card scan is limited to the two used tenure regions rather than the whole span. Hardware `memCopy` for object relocation, hardware zero-fill DMA for free space, `MAX_HANDLES` cap (65536). **Generational mode is unsound without the card table**, so `GC.init` detects its absence (`IO_CARD_SHIFT == 0`) and falls back to classic mark-compact, naming the active collector at boot. SMP GC uses `IO_GC_HALT` to freeze other cores during collection. Design notes: [stage 1 card table](docs/gc/stage1-card-table-design.md), [stage 2 generational](docs/gc/stage2-generational-design.md), [stage 3 follow-ups](docs/gc/stage3-followups.md)
- **Hardware exception detection**: Null pointer and array bounds checks fully enabled — NPE fires on handle address 0, ABE fires on negative index (MSB) or index >= array length. Wired through BmbSys `exc` pulse to `sys_exc` microcode handler. Div-by-zero handled via Java `throw JVMHelp.ArithExc` in f_idiv/f_irem/f_ldiv/f_lrem.
- **Formal verification**: 133 properties verified across 23 test suites using SymbiYosys + Z3 — covers core arithmetic, all pipeline stages, memory subsystem (method cache, object cache, memory controller), DDR3 cache + MIG adapter, I/O (CmpSync, BmbSys, BmbUart), and BMB protocol compliance. See [formal verification docs](docs/formal-verification.md).
- **Debug subsystem** (`jop.debug` package): Optional on-chip debug controller with framed byte-stream protocol over dedicated UART. Supports halt/resume/single-step (microcode and bytecode), register and stack inspection, memory read/write, and up to 4 hardware breakpoints (JPC or microcode PC). Integrated into `JopCluster` via `DebugConfig`. Automated protocol test (`JopDebugProtocolSim`) verifies 39 checks across 14 test sequences.
- **JVM test suite**: 66 tests (`java/apps/JvmTests/`) — all pass. Covers arrays, branches, type casting, int/long arithmetic, long ops (add/sub/neg/cmp/shift/mul), type conversions (i2x/l2x/f2x/d2x), constant loading, float ops (add/sub/mul/div/neg/cmp/rem/i2f/f2i), double ops (add/sub/mul/div/neg/cmp/rem/conversions), field access for all types, exceptions (throw/catch, finally, nested, athrow, div-by-zero, null pointer with 13 sub-tests), instanceof, super method dispatch, object fields, interfaces, static initializers, stack manipulation, System.arraycopy (including StringBuilder resize), string concatenation with int, cache persistence regression, long static fields, deep recursion (200-level, exercises stack cache bank rotation), JDK collections (ArrayList, HashMap, HashSet, Vector, Stack, LinkedList, Hashtable), wrapper types, Math functions, I/O streams, BigInteger/BigDecimal, and DecimalFormat. Ported from original JOP `jvm/` suite and Wimpassinger `jvmtest/` suite.
- **SMP test coverage**: JVM test suite on 2-core SMP (65/66 pass), 8-core SMP verified on QMTECH EP4CGX150 (all 8 cores running independently with per-core UART via Pico debug probe), SMP cache coherency stress test (cross-core A$/O$ snoop invalidation with 20 rounds verified), SMP GC stress (2-core BRAM), IHLU per-object locking verified (NCoreHelloWorld + GC with 84 lock/unlock ops balanced, 3 GC cycles)
- **Simulation**: BRAM sim, SDRAM sim, serial boot sim, latency sweep (0-5 extra cycles), GC stress test, JVM test suite (single-core + SMP), SMP cache coherency test, timer interrupt test, debug protocol test
### Known Issues

- **burstLen=0 + SMP incompatibility** — `burstLen=0` (pipelined single-word BC_FILL) interleaves with the BMB arbiter in SMP mode, causing response-source misalignment. SMP requires `burstLen >= 4`. Single-core is unaffected. See [DDR3 notes](docs/gc/ddr3-gc-hang.md).

### TODO

Active work items:

- **Stack cache SDRAM integration** — 3-bank rotation working in BRAM simulation (66/66 tests pass); needs SDRAM integration with per-core stack regions (memory layout configured, GC bounds checking pending)
- **SMP test expansion** — lock contention stress test (>2 cores hammering `synchronized`), SMP exception handling test. Cache snoop and JVM-on-SMP tests done. See [test coverage audit](docs/test-coverage-audit.md)

### Future

Lower-priority or longer-term items:

- **DDR3 heap size cap** — `JopTop` unconditionally sets `mainMemSize = md.sizeBytes` (256 MB) for DDR3 clusters, ignoring any smaller value in `JopCoreConfig`. For a single-core cluster, 32 MB gives ~160 ms GC rounds vs ~1.3 s at 256 MB. Fix: add `ddr3HeapSizeCap: Option[BigInt]` to `JopSystem` (or use `min(cc.memConfig.mainMemSize, md.sizeBytes)` with a sentinel default) so presets can opt in to a smaller heap without breaking existing configs.
- **GC minor-pause copy phase** — generational GC is done (see Status), and the copy phase is now 79–82% of the remaining minor pause on every board. It is latency-bound rather than clock-bound: the handle table is 2 MB against a 32 KB cache and a handle is exactly one 256-bit cache line, so each of ~6400 handles swept costs a compulsory miss to find ~66 survivors. Fixing it means moving the young-generation bookkeeping into cache-resident side structures. Analysis, staged plan and open questions in [copy-phase redesign](docs/gc/copy-phase-redesign.md).
- **Major GC constant** — major collection is O(live) as intended but the constant is ~20–25x the minor sweep's and unexplained. Next action is a measurement (time `sortUseListByAddress()` separately), not a change; two prior hypotheses were wrong.
- Memory controller — remaining VHDL features: address translation on read paths (for concurrent GC), data cache control signals, fast-path array access (`iald23`)
- Interrupt handling — timer interrupts verified; UART RX/TX interrupts exercised in simulation; scheduler preemption not tested
- DDR3 burst optimization — method cache fills could use burst reads through the cache bridge
- Debug tooling — host-side debug client connecting to on-chip debug controller over UART
- Target JDK modernization (JDK 8 — requires `invokedynamic` support; JDK 6 class library Phases 0-8 complete: foundation interfaces, collections, extended lang/IO, extended collections, utility classes, BigInteger/BigDecimal, DecimalFormat)
- Performance measurement / benchmarking

## Key Technical Details

- **Bus**: SpinalHDL BMB (Bus Master Bridge). BRAM gives single-cycle accept, next-cycle response (matches original SimpCon `rdy_cnt=1`). SDRAM and DDR3 stall automatically via busy signal.
- **Memory controller**: Layer 1 is combinational (simple rd/wr). Layer 2 is a state machine for multi-cycle operations (bytecode fill, getfield, array access). BC fill is pipelined — issues the next read while writing the previous response to JBC RAM, saving ~1 cycle per word. Configurable burst reads (`burstLen=4` for SDR SDRAM). Hardware `memCopy` state machine for GC object relocation.
- **DDR3 subsystem** (`jop.ddr3` package): Write-back cache bridge (`BmbCacheBridge`) converts 32-bit BMB transactions to 128-bit cache lines (truncates BMB address to `cacheAddrWidth` bits, stripping type bits). `LruCacheCore` provides a 4-way set-associative 16KB write-back cache (256 sets, BRAM-based, PLRU replacement). `CacheToMigAdapter` interfaces with the Xilinx MIG DDR3 controller. Full 256MB DDR3 addressed (`addressWidth=28`, 28-bit word address including 2 type bits → 26-bit physical = 256MB). Clock wizard generates 100 MHz system and 200 MHz reference clocks from the board oscillator.
- **Object cache**: Fully associative field value cache (16 entries, 8 fields each). Getfield hits return data in 0 busy cycles (combinational tag match, registered data output). Putfield does write-through on tag hit. FIFO replacement, invalidated on array stores and explicit `cinval`.
- **Array cache**: Fully associative element value cache (16 entries, 4 elements per line). iaload hits return in 0 busy cycles; misses fill the entire 4-element aligned line (burst read on SDRAM to prevent interleaving). iastore does write-through on tag hit. Tags include handle address and upper index bits so different array regions map to different lines. SMP-safe via cross-core snoop invalidation (`CacheSnoopBus` — each core's iastore broadcasts on snoop bus, other cores selectively invalidate matching lines). Note: raw memory writes (`Native.wrMem`) bypass A$ — `System.arraycopy` calls `Native.invalidate()` after copy loops to ensure coherency.
- **Handle format**: `H[0]` = data pointer, `H[1]` = array length. Array elements start at `data_ptr[0]`.
- **I/O subsystem**: Reusable `BmbSys` and `BmbUart` components in `jop.io` package. System slave provides clock cycle counter, prescaled microsecond counter, timer interrupt, watchdog register, and CPU ID. UART slave provides buffered TX/RX with 16-entry FIFOs and per-source interrupt outputs (RX data available, TX FIFO empty). UART interrupts are wired to BmbSys interrupt sources (index 0 = timer, 1 = UART RX, 2 = UART TX). Ethernet subsystem (`BmbEth` + `BmbMdio`) supports MII (100Mbps, 4-bit) and GMII (1Gbps, 8-bit) modes via the device's `ethGmii` parameter, with a dedicated 125 MHz PLL for GMII TX and source-synchronous PHY clock for RX. SD card controllers: `BmbSdNative` (native 4-bit mode, hardware CRC7/CRC16, 512-byte block FIFO) and `BmbSdSpi` (SPI mode, byte-at-a-time), mutually exclusive (share card slot pins). Native mode verified on FPGA hardware at 10 MHz ([details](docs/peripherals/db-fpga-sd-card.md)). VGA text controller (`BmbVgaText`) provides 80x30 character display at 640x480@60Hz with CGA palette, cursor-based and direct-write modes, hardware clear/scroll, and RGB565 output via 25 MHz pixel clock from PLL c3.
- **SMP**: `JopTop(config)` with `cpuCnt >= 2` in the `JopSystem` instantiates N `JopCore`s with a round-robin BMB arbiter for shared memory access. `CmpSync` provides a global lock (round-robin fair arbitration) for `monitorenter`/`monitorexit`, with optional `Ihlu` per-object hardware locking (32-slot CAM, FIFO wait queues, reentrant) selectable via `useIhlu` config flag, plus a GC halt signal (`IO_GC_HALT`) that freezes all other cores during garbage collection. Each core has its own `BmbSys` (unique CPU ID, independent watchdog). Core 0 initializes the system; other cores wait for a boot signal via `IO_SIGNAL`. DDR3 SMP requires `burstLen >= 4` (pipelined single-word BC_FILL interleaves with arbiter at `burstLen=0`).
- **Debug subsystem**: Optional on-chip debug controller (`jop.debug` package) enabled via `DebugConfig` in `JopCluster`. Uses a dedicated UART (separate from the application UART) with a CRC-8/MAXIM framed protocol. `DebugProtocol` parses/builds frames, `DebugController` implements the command FSM (halt, resume, single-step, register/stack/memory read/write, breakpoint management), and `DebugBreakpoints` provides per-core hardware PC comparators. Supports multi-core targeting via core ID field in each command.
- **Serial boot**: Microcode polls UART for incoming bytes, assembles 4 bytes into 32-bit words, writes to external memory. Download script (`download.py`) sends `.jop` files with word-level echo verification.

## Documentation

Design notes and investigation logs in `docs/`:

- [Microcode Instructions](docs/architecture/microcode.md) — table of all microcode instructions and encodings
- [Stack Architecture](docs/architecture/STACK_ARCHITECTURE.md) — stack buffer, spill/fill, local variables
- [Jopa Tool](docs/architecture/JOPA_TOOL.md) — microcode assembler usage and output formats
- [Programmer's Guide](docs/programmers-guide.md) — I/O register maps and Java API for all devices (BmbSys, BmbUart, BmbEth, BmbMdio, BmbSdNative, BmbSdSpi, BmbVgaText)
- [Tuning Guide](docs/architecture/tuning-guide.md) — **what each configuration lever buys and costs**: `blockBits` vs `jpcWidth`, `l2SetCount`, `l2MshrCount`, core count; which resource actually binds (LUTs, not BRAM); how to measure each; and the plausible-sounding conclusions that measurement disproved
- [Build Structure](docs/build-structure.md) — **how a build is put together and what to type**: the `build/<config>/` layout, the shared per-toolchain flow, what a board Makefile declares versus inherits, and troubleshooting
- [System Configuration](docs/architecture/system-configuration.md) — configuration reference: memory layout, JopCoreConfig, JopMemoryConfig, board configs, I/O register map
- [Configuration-Driven Design](docs/architecture/configuration-driven-design.md) — unified `JopTop(config)` design: JopConfig hierarchy, board/FPGA/memory metadata, PLL/reset/memory controller factories, preset system
- [Compute Unit Design](docs/architecture/compute-unit-design.md) — IntegerCU, FloatCU, LongCU, DoubleCU: stop/sthw/ldop pattern, operand stack, per-bytecode configuration
- [Bugs and Issues](docs/bugs-and-issues.md) — master bug index: open JVM workarounds, fixed RTL/pipeline/microcode bugs
- [Implementation Notes](docs/architecture/implementation-notes.md) — bugs found, cache details, I/O subsystem, SMP, GC architecture, memCopy
- [Artix-7 Distributed RAM Optimization](docs/analysis/artix7-distram-optimization.md) — stack cache bank RAM `readSync` for BRAM inference on Xilinx (auto-derived from FPGA family)
- [Cache Analysis](docs/architecture/cache-analysis.md) — cache performance analysis and technology cost model
- [Memory Controller Comparison](docs/architecture/memory-controller-comparison.md) — VHDL vs SpinalHDL memory controller
- [Stack Immediate Timing](docs/analysis/stack-immediate-timing.md) — stack stage timing for immediate operations
- [Formal Verification](docs/formal-verification.md) — 133 BMC properties across 23 suites (SymbiYosys + Z3)
- [DB_FPGA Ethernet](docs/peripherals/db-fpga-ethernet.md) — 1Gbps GMII architecture, pin mapping, PHY config, SDC timing for RTL8211EG
- [DB_FPGA VGA Text](docs/peripherals/db-fpga-vga-text.md) — 80x30 text-mode VGA output, register map, Java API, setup guide
- [DB_FPGA SD Card](docs/peripherals/db-fpga-sd-card.md) — SD card native 4-bit mode, hardware verification, bugs found, clock speed constraints
- [FAT32 Filesystem](docs/peripherals/fat32-filesystem.md) — read-write FAT32 with LFN support, API reference, JOP workarounds, simulation and hardware testing
- [Networking](docs/peripherals/networking.md) — TCP/IP stack with ICMP ping, UDP/TCP echo, DHCP client, DNS resolver, HTTP/1.0 file server; verified on FPGA hardware
- [Flash Boot](docs/boards/flash-boot.md) — autonomous Active Serial boot from W25Q128, UART flash programmer, flash image format
- [pico-dirtyJtag Setup](docs/pico-dirtyjtag-setup.md) — program FPGAs via Raspberry Pi Pico + openFPGALoader (alternative to USB-Blaster)
- [Current Status](docs/current-status.md) — **read first**: where the project is, open items, and the traps that have cost real time
- [Generational GC — stage 1 card table](docs/gc/stage1-card-table-design.md), [stage 2 design](docs/gc/stage2-generational-design.md), [stage 3 follow-ups](docs/gc/stage3-followups.md)
- [GC copy-phase redesign](docs/gc/copy-phase-redesign.md) — the remaining 79-82% of the minor pause: analysis, staged plan, open questions
- [A-E115FB DDR2 bring-up](docs/boards/ae115fb-ddr2-bringup.md) — 1 GB DDR2, half-rate ALTMEMPHY, cache adapter, and everything that went wrong
- [EP4CE115 DDR2 board](docs/boards/ep4ce115-ddr2-board.md) — board reference
- [SDR SDRAM GC Hang](docs/gc/sdr-sdram-gc-hang.md) — resolved: SpinalHDL SdramCtrl DQ timing issue
- [DDR3 GC Hang](docs/gc/ddr3-gc-hang.md) — resolved (32KB L2 cache)

## References

- JOP project: https://github.com/jop-devel/jop
- JOP web site: https://www.jopdesign.com/
- JOP Thesis: Martin Schoeberl, [JOP: A Java Optimized Processor for Embedded Real-Time Systems](https://www.jopdesign.com/thesis/thesis.pdf)
- SpinalHDL: https://spinalhdl.github.io/SpinalDoc-RTD/

## License

TBD - Following original JOP licensing
