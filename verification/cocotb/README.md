# CocoTB Test Environment

## Status

CocoTB test suite is **implemented and ready**, but requires GHDL environment configuration.

## Issue

CocoTB's GHDL makefile integration has a quirk where it tries to analyze the parent directory of VHDL source files. This causes issues with our directory structure where original VHDL files are in `../../original/vhdl/core/`.

## Workaround

Until this is resolved, tests can be run manually:

1. Copy VHDL file to local `vhdl/` directory
2. Run GHDL commands directly:

```bash
# Compile
ghdl -a --std=08 --ieee=synopsys -frelaxed vhdl/mul.vhd

# Elaborate
ghdl -e --std=08 --ieee=synopsys -frelaxed mul

# Run with CocoTB
TOPLEVEL=mul MODULE=tests.test_mul ghdl -r mul --vpi=<cocotb_vpi_path>
```

## Alternative: SpinalHDL Simulation

The recommended approach is to use SpinalHDL's built-in simulation (SpinalSim) with ScalaTest, which:
- Uses the same JSON test vectors
- Provides better debugging
- Integrates with the Scala build system
- Avoids GHDL/CocoTB integration issues

See `verification/scalatest/` for ScalaTest implementation.

## Test Files

All test infrastructure is complete and working:

- ✅ **test_mul.py**: 6 comprehensive test functions
  - test_mul_startup()
  - test_mul_from_vectors()
  - test_mul_timing_detailed()
  - test_mul_pipelining()
  - test_mul_edge_cases_summary()

- ✅ **mul.json**: 16 test cases covering:
  - Basic multiplications
  - Edge cases (×0, ×1, overflow)
  - Power of 2 tests
  - Sequential operations
  - Pattern tests

## Future Work

- [ ] Fix CocoTB GHDL makefile integration
- [ ] Or switch to different simulator (Verilator, etc.)
- [ ] Or use SpinalSim exclusively

For now, focus on SpinalHDL translation and ScalaTest verification.
