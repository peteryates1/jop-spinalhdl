# Project Structure

## Overview

This document describes the complete directory structure and file organization for the JOP SpinalHDL migration project.

## Directory Tree

```
jop/
│
├── README.md                        # Project overview
├── .gitignore                       # Git ignore patterns
├── notes.md                         # Project notes and planning
│
├── docs/                            # Documentation
│   ├── QUICK_START.md              # Quick start guide
│   ├── PROJECT_STRUCTURE.md        # This file
│   │
│   ├── agents/                     # Agent workflow templates
│   │   ├── vhdl-tester-workflow.md
│   │   ├── spinalhdl-developer-workflow.md
│   │   ├── spinalhdl-tester-workflow.md
│   │   └── reviewer-workflow.md
│   │
│   ├── test-vectors/               # Test vector documentation
│   │   └── test-vector-format.md
│   │
│   ├── architecture/               # (To be created)
│   │   ├── pipeline.md
│   │   ├── memory-subsystem.md
│   │   └── configuration.md
│   │
│   ├── verification/               # (To be created)
│   │   └── coverage/
│   │
│   ├── migration/                  # (To be created)
│   │   └── module-comparisons/
│   │
│   └── reviews/                    # (To be created by reviewer)
│       ├── migration-status.md
│       └── modules/
│
├── original/                       # Reference implementation
│   └── vhdl/
│       └── core/                   # Original VHDL core files
│           ├── fetch.vhd          # (To be populated from reference)
│           ├── decode.vhd
│           ├── execute.vhd
│           └── ...
│
├── verification/                   # Test infrastructure
│   │
│   ├── test-vectors/              # Shared JSON test vectors
│   │   ├── schema/
│   │   │   └── test-vector-schema.json
│   │   │
│   │   ├── modules/               # Per-module test vectors
│   │   │   ├── example-bytecode-fetch.json
│   │   │   ├── bytecode-fetch.json     # (To be created)
│   │   │   ├── microcode-fetch.json    # (To be created)
│   │   │   ├── microcode-decode.json   # (To be created)
│   │   │   └── execute.json            # (To be created)
│   │   │
│   │   ├── integration/           # Integration test vectors
│   │   │   └── pipeline.json      # (To be created)
│   │   │
│   │   └── microcode/             # Per-microcode instruction vectors
│   │       ├── nop.json           # (To be created)
│   │       ├── add.json           # (To be created)
│   │       └── ...
│   │
│   ├── cocotb/                    # Python/CocoTB/GHDL tests
│   │   ├── Makefile
│   │   │
│   │   ├── tests/
│   │   │   ├── __init__.py
│   │   │   ├── test_example.py
│   │   │   ├── test_fetch.py      # (To be created)
│   │   │   ├── test_decode.py     # (To be created)
│   │   │   └── test_execute.py    # (To be created)
│   │   │
│   │   ├── util/
│   │   │   ├── __init__.py
│   │   │   └── test_vectors.py    # JSON loader for Python
│   │   │
│   │   ├── fixtures/              # Test fixtures (if needed)
│   │   │
│   │   └── dut/                   # Generated VHDL for testing
│   │       └── (populated during testing)
│   │
│   └── scalatest/                 # Scala/SpinalSim tests
│       ├── src/
│       │   └── test/
│       │       └── scala/
│       │           └── jop/
│       │               ├── pipeline/      # (To be created)
│       │               │   ├── BytecodeFetchSpec.scala
│       │               │   ├── MicrocodeFetchSpec.scala
│       │               │   ├── MicrocodeDecodeSpec.scala
│       │               │   └── ExecuteSpec.scala
│       │               │
│       │               ├── util/
│       │               │   ├── TestVectorLoader.scala
│       │               │   └── TestHelpers.scala
│       │               │
│       │               ├── fixtures/      # (Optional)
│       │               │
│       │               ├── benchmark/     # (To be created)
│       │               │   └── PerformanceBenchmarks.scala
│       │               │
│       │               └── integration/   # (To be created)
│       │                   └── JopCoreSpec.scala
│       │
│       └── simWorkspace/          # SpinalSim workspace (generated)
│
├── core/                          # Implementation
│   └── spinalhdl/
│       ├── build.sbt              # SBT build configuration
│       ├── .scalafmt.conf         # Scala formatting config
│       │
│       ├── project/
│       │   ├── build.properties
│       │   └── plugins.sbt
│       │
│       ├── src/
│       │   └── main/
│       │       └── scala/
│       │           └── jop/
│       │               ├── JopCore.scala      # (To be created)
│       │               ├── JopConfig.scala
│       │               │
│       │               ├── pipeline/         # Pipeline stages
│       │               │   ├── BytecodeFetch.scala    # (To be created)
│       │               │   ├── MicrocodeFetch.scala   # (To be created)
│       │               │   ├── MicrocodeDecode.scala  # (To be created)
│       │               │   └── Execute.scala          # (To be created)
│       │               │
│       │               ├── memory/           # Memory subsystem
│       │               │   ├── MethodCache.scala      # (To be created)
│       │               │   ├── StackBuffer.scala      # (To be created)
│       │               │   └── MicrocodeRom.scala     # (To be created)
│       │               │
│       │               └── types/            # Type definitions
│       │                   ├── MicrocodeInstruction.scala  # (To be created)
│       │                   └── Interfaces.scala            # (To be created)
│       │
│       └── generated/             # Generated HDL output
│           ├── JopCore.vhd        # (Generated)
│           ├── JopCore.v          # (Generated)
│           └── ...
│
└── tools/                         # Build and utility tools
    └── scripts/
        ├── validate_vectors.py    # Test vector validator
        ├── compare_test_results.py      # (To be created)
        ├── verify_microcode_coverage.py # (To be created)
        ├── generate_status_report.py    # (To be created)
        └── capture_vectors.py           # (To be created)
```

## File Descriptions

### Root Level

- **README.md** - Project overview, goals, and basic usage
- **.gitignore** - Ignore patterns for build artifacts, IDE files
- **notes.md** - Development notes and planning

### docs/

Documentation for the project:

- **QUICK_START.md** - Getting started guide
- **PROJECT_STRUCTURE.md** - This file
- **agents/** - Workflow templates for each agent role
- **test-vectors/** - Test vector format specification
- **architecture/** - Architecture documentation (to be created)
- **verification/** - Verification methodology and coverage (to be created)
- **migration/** - Module-by-module migration notes (to be created)
- **reviews/** - Review results and migration status (created by reviewer)

### original/vhdl/core/

Original VHDL implementation from reference repository. Serves as golden reference.

### verification/test-vectors/

Shared JSON test vectors consumed by both CocoTB and ScalaTest:

- **schema/** - JSON schema for validation
- **modules/** - Per-module test vectors
- **integration/** - Integration test vectors
- **microcode/** - Per-microcode instruction test vectors

### verification/cocotb/

Python-based testing using CocoTB and GHDL:

- **Makefile** - Build and test execution
- **tests/** - Test modules
- **util/** - Test utilities (JSON loader, helpers)
- **fixtures/** - Test fixtures if needed
- **dut/** - VHDL files under test (original or generated)

### verification/scalatest/

Scala-based testing using ScalaTest and SpinalSim:

- **src/test/scala/jop/** - Test suites
  - **pipeline/** - Pipeline stage tests
  - **util/** - Test utilities (JSON loader, helpers)
  - **benchmark/** - Performance benchmarks
  - **integration/** - Integration tests
- **simWorkspace/** - SpinalSim generated files

### core/spinalhdl/

SpinalHDL implementation:

- **build.sbt** - SBT build configuration
- **project/** - SBT project configuration
- **src/main/scala/jop/** - Source code
  - **JopCore.scala** - Top-level core
  - **JopConfig.scala** - Configuration system
  - **pipeline/** - Pipeline stage implementations
  - **memory/** - Memory subsystem
  - **types/** - Type definitions and interfaces
- **generated/** - Generated VHDL/Verilog output

### tools/scripts/

Utility scripts:

- **validate_vectors.py** - Validate JSON test vectors against schema
- **compare_test_results.py** - Compare CocoTB vs ScalaTest results (to be created)
- **verify_microcode_coverage.py** - Check microcode coverage (to be created)
- **generate_status_report.py** - Generate migration status (to be created)
- **capture_vectors.py** - Capture test vectors from VHDL simulation (to be created)

## File Naming Conventions

### Test Vectors
- Format: `<module-name>.json`
- Example: `bytecode-fetch.json`, `microcode-decode.json`
- Lowercase with hyphens

### CocoTB Tests
- Format: `test_<module>.py`
- Example: `test_fetch.py`, `test_decode.py`
- Lowercase with underscores

### ScalaTest Suites
- Format: `<Module>Spec.scala`
- Example: `BytecodeFetchSpec.scala`, `MicrocodeDecodeSpec.scala`
- PascalCase with "Spec" suffix

### SpinalHDL Modules
- Format: `<Module>.scala`
- Example: `BytecodeFetch.scala`, `MicrocodeDecode.scala`
- PascalCase

### Generated VHDL
- Format: `<Module>.vhd`
- Example: `BytecodeFetch.vhd`, `JopCore.vhd`
- PascalCase

## Key Directories for Each Agent

### vhdl-tester
- Works in: `verification/test-vectors/`, `verification/cocotb/`
- Creates: Test vectors (JSON), CocoTB tests (Python)
- Reads: `original/vhdl/core/`

### spinalhdl-developer
- Works in: `core/spinalhdl/src/main/scala/jop/`
- Creates: SpinalHDL modules, generated VHDL
- Reads: `original/vhdl/core/`, `verification/test-vectors/`

### spinalhdl-tester
- Works in: `verification/scalatest/`
- Creates: ScalaTest suites
- Reads: `verification/test-vectors/`, `core/spinalhdl/`

### reviewer
- Works in: `docs/reviews/`
- Reads: All directories
- Creates: Review reports, migration status

## Build Artifacts (Not in Git)

These are generated and should not be committed:

- `verification/cocotb/sim_build/` - CocoTB simulation build
- `verification/cocotb/waveforms/` - Waveform files
- `verification/scalatest/simWorkspace/` - SpinalSim workspace
- `core/spinalhdl/target/` - SBT build artifacts
- `core/spinalhdl/generated/*.vhd` - Generated VHDL (can regenerate)
- `__pycache__/`, `*.pyc` - Python bytecode
- `.metals/`, `.bloop/` - IDE metadata
