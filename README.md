# JOP - Java Optimized Processor (SpinalHDL Port)

Modernization and port of the [Java Optimized Processor](https://github.com/peteryates1/jop) from VHDL to SpinalHDL.

## Project Goals

- Modernize the JOP processor implementation
- Port core from VHDL to SpinalHDL/Scala
- Create configurable systems for various FPGA boards
- Maintain cycle-accurate compatibility with original
- Upgrade Java target from JDK 1.5/1.6 to modern versions

## Project Structure

```
jop/
├── original/              # Reference VHDL implementation
│   └── vhdl/core/
├── verification/          # Test infrastructure
│   ├── test-vectors/     # Shared JSON test vectors
│   ├── cocotb/           # Python/CocoTB/GHDL tests
│   └── scalatest/        # Scala/SpinalSim tests
├── core/
│   └── spinalhdl/        # New SpinalHDL implementation
├── asm/                   # Microcode assembler (NEW)
│   ├── src/              # Microcode source (jvm.asm)
│   ├── generated/        # Generated files (jtbl.vhd, JumpTableData.scala, etc.)
│   └── Makefile          # Microcode build system
├── java/                  # Java tooling (NEW)
│   └── jopa/             # Jopa microcode assembler
│       ├── src/          # Jopa source code
│       ├── dist/         # Built jopa.jar
│       └── Makefile      # Jopa build system
├── docs/
│   ├── agents/           # Agent workflow documentation
│   ├── verification/     # Test documentation and guides
│   └── test-vectors/     # Test vector format specification
└── tools/
    └── scripts/          # Build and validation tools
```

## Supported FPGA Boards

- [EP4CGX150DF27_CORE_BOARD](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD) - Cyclone IV GX
- [CYCLONE_IV_EP4CE15](https://github.com/ChinaQMTECH/CYCLONE_IV_EP4CE15) - Cyclone IV E
- [Alchitry Au](https://shop.alchitry.com/products/alchitry-au) - Xilinx Artix-7
- [MAX1000](https://www.trenz-electronic.de/en/MAX1000-IoT-Maker-Board-8kLE-8-MByte-SDRAM-8-MByte-Flash-6.15-x-2.5-cm/TEI0001-04-DBC87A) - Intel MAX10
- [CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A) - Cyclone V
- [CYC1000](https://www.trenz-electronic.de/en/CYC1000-with-Intel-Cyclone-10-LP-10CL025-C8-8-MByte-SDRAM-8-MByte-Flash/TEI0003-03-QFCT4A) - Cyclone 10 LP

## Pipeline Architecture

```
┌──────────────┐                    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   bytecode   │                    │  microcode   │    │  microcode   │    │  microcode   │
│    fetch     │─────────┬─────────▶│    fetch     │─┬─▶│   decode     │───▶│   execute    │
│  translate   │         │          │              │ |  │              │    │  (tos/nos)   │
└──────┬───────┘         │          └──────┬───────┘ |  └──────────────┘    └──────┬───────┘
       │                 │                 │         |                       spill & fill
┌──────┴───────┐  ┌──────┴───────┐  ┌──────┴───────┐ |  ┌──────────────┐    ┌──────┴───────┐
| method cache │  |  jump tbl    │  │microcode rom │ └──│ Address Gen  │───▶│ stack buffer │
└──────────────┘  └──────────────┘  └──────────────┘    └──────────────┘    └──────────────┘
```

## Development Workflow

The project uses a multi-agent approach for systematic migration:

### Agents

1. **vhdl-tester** - Creates golden reference tests using CocoTB/GHDL
2. **spinalhdl-developer** - Implements SpinalHDL port of VHDL modules
3. **spinalhdl-tester** - Creates equivalent tests using ScalaTest/SpinalSim
4. **reviewer** - Validates equivalence and quality across all agents

See [docs/agents/](docs/agents/) for detailed workflow documentation.

### Test Vectors

Test vectors are maintained in JSON format and shared between CocoTB (Python) and ScalaTest (Scala) test suites. This ensures a single source of truth.

- Format specification: [docs/test-vectors/test-vector-format.md](docs/test-vectors/test-vector-format.md)
- Schema: [verification/test-vectors/schema/test-vector-schema.json](verification/test-vectors/schema/test-vector-schema.json)
- Modules: [verification/test-vectors/modules/](verification/test-vectors/modules/)

## Reference Files

The original JOP VHDL implementation (298 files) is located at:
```
/home/peter/git/jop.arch/jop/vhdl/
```

Core files have been copied to `original/vhdl/core/` for convenience, but the full repository should be referenced for context.

- [original/REFERENCE.md](original/REFERENCE.md) - Guide to reference repository structure
- [docs/MODULE_DEPENDENCIES.md](docs/MODULE_DEPENDENCIES.md) - Module dependency graph and translation order
- [docs/MICROCODE_AND_ROMS.md](docs/MICROCODE_AND_ROMS.md) - **CRITICAL**: Jump table (jtbl.vhd) and microcode ROM
- [docs/JOPA_TOOL.md](docs/JOPA_TOOL.md) - Jopa microcode assembler (generates jtbl.vhd, rom.vhd, etc.)
- [docs/JOPA-SCALA-GENERATION-SUMMARY.md](docs/JOPA-SCALA-GENERATION-SUMMARY.md) - **NEW**: Scala jump table generation for SpinalHDL
- [docs/BYTECODE-FETCH-PLAN.md](docs/BYTECODE-FETCH-PLAN.md) - **NEW**: Bytecode fetch implementation plan
- [docs/STACK_ARCHITECTURE.md](docs/STACK_ARCHITECTURE.md) - Stack buffer layout (SP starts at 64!)
- [docs/verification/INTEGRATION-TESTING-GUIDE.md](docs/verification/INTEGRATION-TESTING-GUIDE.md) - Integration testing methodology
- [docs/verification/STACK-COVERAGE-SUMMARY.md](docs/verification/STACK-COVERAGE-SUMMARY.md) - Test coverage summary
- [docs/agents/REFERENCE_FILES.md](docs/agents/REFERENCE_FILES.md) - How agents should reference files
- [.reference-paths](.reference-paths) - Shell helper functions for file access

## Getting Started

### Prerequisites

#### For Microcode Assembly (Required)
- Java 8+ (for Jopa assembler)
- gcc (for C preprocessor)
- make

#### For VHDL Testing (CocoTB)
- Python 3.8+
- GHDL (VHDL simulator)
- CocoTB framework
- pytest

#### For SpinalHDL Development
- Java 11+
- Scala 2.13
- sbt (Scala Build Tool)
- SpinalHDL

#### For SpinalSim Testing
- Verilator (for simulation backend)
- ScalaTest

### Setup

1. Clone the repository:
```bash
git clone <repository-url>
cd jop
```

2. Build microcode assembler and generate microcode:
```bash
# Build Jopa assembler
cd java/jopa
make

# Generate microcode (jtbl.vhd, JumpTableData.scala, rom.vhd, etc.)
cd ../../asm
make
```

**Generated files:**
- `asm/generated/jtbl.vhd` - VHDL jump table (Java bytecode → microcode address)
- `asm/generated/JumpTableData.scala` - Scala jump table (for SpinalHDL)
- `asm/generated/rom.vhd` - Microcode ROM (VHDL)
- `asm/generated/mem_rom.dat` - Microcode ROM (simulation data)
- `asm/generated/mem_ram.dat` - Stack RAM initialization

3. Setup Python environment for CocoTB:
```bash
cd verification/cocotb
python -m venv venv
source venv/bin/activate  # or `venv\Scripts\activate` on Windows
pip install cocotb pytest ghdl
```

4. Setup Scala/SpinalHDL:
```bash
cd core/spinalhdl
sbt compile
# Note: Automatically includes asm/generated/JumpTableData.scala
```

### Build System

#### Microcode Assembly
The microcode assembler (Jopa) generates both VHDL and Scala outputs from the microcode source:

```bash
cd asm
make              # Assembles jvm.asm → generates all outputs
make clean        # Clean generated files
```

**Build Process:**
1. gcc preprocessor → expands macros in `jvm.asm`
2. Jopa assembler → generates:
   - `jtbl.vhd` (VHDL jump table)
   - `JumpTableData.scala` (Scala jump table) ← **NEW**
   - `rom.vhd` (microcode ROM)
   - `mem_rom.dat`, `mem_ram.dat` (simulation data)

**Note:** SpinalHDL's `build.sbt` automatically includes `asm/generated/` as a source directory, so `JumpTableData.scala` is available to all SpinalHDL components.

### Running Tests

#### Validate Test Vectors
```bash
python tools/scripts/validate_vectors.py
```

#### Run CocoTB Tests
```bash
cd verification/cocotb
make TOPLEVEL=<module> MODULE=tests.test_<module>
```

#### Run ScalaTest Tests
```bash
cd core/spinalhdl
sbt test
```

#### Generate VHDL from SpinalHDL
```bash
cd core/spinalhdl
sbt "runMain jop.JopCore"
# Output in: core/spinalhdl/generated/
```

## Migration Status

### Completed Components

**✅ Microcode Fetch Stage** (FetchStage.scala)
- 100% verified with CocoTB tests
- ROM-based microcode fetch
- Integration with decode stage

**✅ Decode Stage** (DecodeStage.scala)
- 100% verified with CocoTB tests
- All 45 microcode instructions tested
- Control signal generation

**✅ Stack Stage** (StackStage.scala)
- ⭐ 98/100 code review score - Production ready
- 73 unit tests passing (100%)
- Full stack buffer implementation
- ALU, shifter, comparison logic

**✅ Pipeline Integration** (JopCore.scala)
- 61 tests passing (100%)
- Phases 1-3 complete
- System-level validation

**✅ Microcode Tooling** (Jopa assembler)
- Generates VHDL jump table (jtbl.vhd)
- Generates Scala jump table (JumpTableData.scala) ← **NEW**
- Microcode ROM generation
- Integrated build system

### In Progress

**🔄 Bytecode Fetch Stage** (BytecodeFetchStage.scala)
- Planning complete
- Jump table data generation ready
- See [docs/BYTECODE-FETCH-PLAN.md](docs/BYTECODE-FETCH-PLAN.md) for details

### Deferred

**Method Cache, Memory Interface** - Phases B & C
**Interrupt/Exception Handling** - Phase E

See [NEXT-STEPS.md](NEXT-STEPS.md) and [docs/BYTECODE-FETCH-PLAN.md](docs/BYTECODE-FETCH-PLAN.md) for detailed roadmap.

## Contributing

This is a systematic migration project following the agent workflows documented in [docs/agents/](docs/agents/).

## References

- Original JOP: https://github.com/peteryates1/jop
- SpinalHDL: https://spinalhdl.github.io/SpinalDoc-RTD/
- CocoTB: https://docs.cocotb.org/

## License

TBD - Following original JOP licensing
