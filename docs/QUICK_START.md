# Quick Start Guide

## Project Structure Overview

```
jop/
├── docs/
│   ├── agents/                      # Agent workflow templates
│   │   ├── vhdl-tester-workflow.md
│   │   ├── spinalhdl-developer-workflow.md
│   │   ├── spinalhdl-tester-workflow.md
│   │   └── reviewer-workflow.md
│   └── test-vectors/
│       └── test-vector-format.md    # Test vector specification
│
├── verification/
│   ├── test-vectors/                # Shared JSON test vectors
│   │   ├── schema/
│   │   │   └── test-vector-schema.json
│   │   └── modules/
│   │       └── example-bytecode-fetch.json
│   │
│   ├── cocotb/                      # Python/CocoTB/GHDL tests
│   │   ├── tests/
│   │   │   └── test_example.py
│   │   ├── util/
│   │   │   └── test_vectors.py      # JSON loader for Python
│   │   └── Makefile
│   │
│   └── scalatest/                   # Scala/SpinalSim tests
│       └── src/test/scala/jop/
│           └── util/
│               ├── TestVectorLoader.scala  # JSON loader for Scala
│               └── TestHelpers.scala
│
├── core/spinalhdl/                  # SpinalHDL implementation
│   ├── src/main/scala/jop/
│   │   ├── JopConfig.scala          # Board configurations
│   │   ├── pipeline/                # Pipeline stages (to be created)
│   │   ├── memory/                  # Memory subsystem (to be created)
│   │   └── types/                   # Type definitions (to be created)
│   ├── generated/                   # Generated VHDL/Verilog
│   ├── build.sbt
│   └── project/
│
├── original/vhdl/core/              # Reference VHDL (to be populated)
│
└── tools/scripts/
    └── validate_vectors.py          # Test vector validator
```

## First Steps

### 1. ✅ Original VHDL (Already Done!)

The original VHDL files are referenced from:
```bash
/home/peter/git/jop.arch/jop/vhdl/
```

Core files are also copied to `original/vhdl/core/` for convenience.

**Important References:**
- [original/REFERENCE.md](../original/REFERENCE.md) - Reference repository guide
- [docs/MODULE_DEPENDENCIES.md](MODULE_DEPENDENCIES.md) - Module dependency graph
- [docs/agents/REFERENCE_FILES.md](agents/REFERENCE_FILES.md) - File reference guide

### 2. Start with One Module

**Recommended: Start with `mul.vhd`** (multiplier)
- Small and self-contained
- Only depends on jop_types
- Good for learning the workflow
- Clear functionality

See [MODULE_DEPENDENCIES.md](MODULE_DEPENDENCIES.md) for the full dependency graph.

Pick a simple module to start with (e.g., a register file or simple state machine).

#### As vhdl-tester Agent:

1. **Analyze the VHDL module**
   ```bash
   # Study the module in original/vhdl/core/<module>.vhd
   # Document signals, behavior, timing
   ```

2. **Create test vectors**
   ```bash
   # Create verification/test-vectors/modules/<module>.json
   # Follow the schema in verification/test-vectors/schema/test-vector-schema.json
   # Use example-bytecode-fetch.json as template
   ```

3. **Validate test vectors**
   ```bash
   python tools/scripts/validate_vectors.py
   ```

4. **Create CocoTB tests**
   ```bash
   # Create verification/cocotb/tests/test_<module>.py
   # Use test_example.py as template
   ```

5. **Run tests against original VHDL**
   ```bash
   cd verification/cocotb
   make TOPLEVEL=<module> MODULE=tests.test_<module>
   ```

#### As spinalhdl-developer Agent:

1. **Create SpinalHDL module**
   ```bash
   # Create core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala
   # Translate from VHDL
   ```

2. **Generate VHDL**
   ```bash
   cd core/spinalhdl
   sbt "runMain jop.pipeline.<Module>"
   ```

3. **Test generated VHDL**
   ```bash
   # Copy generated VHDL to CocoTB test area
   cp generated/<Module>.vhd ../../verification/cocotb/dut/

   # Run CocoTB tests
   cd ../../verification/cocotb
   make TOPLEVEL=<module> MODULE=tests.test_<module>
   ```

#### As spinalhdl-tester Agent:

1. **Create ScalaTest suite**
   ```bash
   # Create verification/scalatest/src/test/scala/jop/pipeline/<Module>Spec.scala
   # Load test vectors using TestVectorLoader
   ```

2. **Run tests**
   ```bash
   cd core/spinalhdl
   sbt test
   ```

#### As reviewer Agent:

1. **Verify test parity**
   ```bash
   # Compare CocoTB and ScalaTest results
   # Check coverage
   # Validate equivalence
   ```

2. **Accept or reject**
   - If all criteria met → module ACCEPTED
   - If issues found → create issues for respective agents

## Development Workflow

### Daily Workflow

```
1. Pull latest changes
2. Select next module or continue current
3. Follow agent workflow for your role:
   - vhdl-tester: Create/update test vectors and CocoTB tests
   - spinalhdl-developer: Port VHDL to SpinalHDL
   - spinalhdl-tester: Create ScalaTest suite
   - reviewer: Validate and track progress
4. Commit and push changes
5. Update migration status
```

### Parallel Development

Multiple modules can be worked on in parallel:
- Different agents work on different modules
- Use git branches for isolation
- Reviewer merges completed modules

## Key Commands

### Test Vectors

```bash
# Validate all test vectors
python tools/scripts/validate_vectors.py

# Validate specific file
python tools/scripts/validate_vectors.py verification/test-vectors/modules/<module>.json
```

### CocoTB Testing

```bash
cd verification/cocotb

# Run tests for a module
make TOPLEVEL=<module> MODULE=tests.test_<module>

# Enable waveforms
make TOPLEVEL=<module> MODULE=tests.test_<module> WAVES=1

# Clean
make clean
```

### SpinalHDL Development

```bash
cd core/spinalhdl

# Compile Scala code
sbt compile

# Run tests
sbt test

# Generate VHDL for specific module
sbt "runMain jop.pipeline.<Module>"

# Generate complete core
sbt "runMain jop.JopCore"

# Format code
sbt scalafmt

# Coverage
sbt clean coverage test coverageReport
```

## Tips

### For vhdl-tester

- Start with reset behavior tests
- Test each microcode instruction independently
- Cover edge cases (overflow, underflow, boundaries)
- Use meaningful test names
- Document expected behavior in test descriptions

### For spinalhdl-developer

- Keep SpinalHDL code idiomatic (use Bundles, Streams, etc.)
- Preserve signal names from VHDL when possible
- Add comments for non-obvious translations
- Test incrementally (module by module)
- Check generated VHDL for readability

### For spinalhdl-tester

- Use same test vectors as CocoTB
- Leverage SpinalHDL simulation features
- Create reusable test utilities
- Compare results with CocoTB tests
- Monitor simulation performance

### for reviewer

- Automate comparisons where possible
- Track metrics (coverage, performance, resource usage)
- Document all findings
- Update migration status regularly
- Celebrate milestones!

## Next Steps

1. **Pick first module** - Start with something simple
2. **Follow vhdl-tester workflow** - Create test vectors and CocoTB tests
3. **Verify original VHDL** - Ensure tests pass on original
4. **Port to SpinalHDL** - Follow spinalhdl-developer workflow
5. **Create ScalaTest suite** - Follow spinalhdl-tester workflow
6. **Review and accept** - Follow reviewer workflow
7. **Repeat** for next module

## Getting Help

- Review workflow documentation in [docs/agents/](../agents/)
- Check test vector format in [docs/test-vectors/](../test-vectors/)
- Look at examples:
  - Test vectors: `verification/test-vectors/modules/example-bytecode-fetch.json`
  - CocoTB test: `verification/cocotb/tests/test_example.py`
  - SpinalHDL config: `core/spinalhdl/src/main/scala/jop/JopConfig.scala`

## Common Issues

### CocoTB tests fail to find test vectors
- Check path in `TestVectorLoader("../../test-vectors")`
- Verify JSON file exists and is valid
- Run `python tools/scripts/validate_vectors.py`

### SpinalHDL generation fails
- Check Scala syntax
- Verify SpinalHDL version compatibility
- Review error messages for missing imports

### Test parity fails
- Compare test vector loading between Python and Scala
- Check value parsing (hex vs decimal)
- Verify cycle timing matches

### GHDL compilation errors
- Check VHDL syntax in generated code
- Verify entity/architecture match
- Check signal types and port directions
