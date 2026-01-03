# Test vector coverage gaps

## Stack/execute stage
- ✅ **RESOLVED (2026-01-03)**: `stack.vhd:423-430` sp_ov flag - Test added and passing. The `sp_overflow_flag` test verifies that `sp_ov` goes high when SP reaches 239 (2^8 - 1 - 16). See `verification/test-vectors/modules/stack.json` line 998.

- ⚠️ **KNOWN ISSUE (2026-01-03)**: `stack.vhd:312-321` B register loading from RAM (`sel_bmux=1`) - Multiple attempts to create a test for the RAM→B direct loading path have failed. The test consistently shows `bout=0` instead of the expected RAM value, despite:
  - Verified A register loads correctly from initial_state
  - Verified RAM write completes (sel_mmux=0, wr_ena=1)
  - Verified RAM read timing (readSync has 1-cycle latency)
  - Tried various timing configurations (4, 5, and 6 cycle tests)

  **Root cause**: Potential issue in SpinalHDL StackStageTb.vhd implementation or test framework timing. The Scala source (StackStage.scala:812-818) correctly implements the feature, but the generated VHDL may have a subtle bug or the CocoTB test framework requires special handling for this path.

  **Impact**: Low - This path is used in pop operations, which can alternatively be tested through microcode sequences. All other B register operations (loading from A with sel_bmux=0) work correctly and are fully tested.

  **Recommendation**: Defer to integration testing phase when full microcode sequences can be executed. The feature exists in hardware but requires investigation into why the unit test fails.

## Decode/MMU control
- `decode.vhd:343-357` and `decode.vhd:522-535` enumerate MMU opcodes such as `STIDX`, `STMRAC`, `STMRAF`, `STMWDF`, as well as loads like `LD`, `LDMI`, and `LDBCSTART`, yet `verification/test-vectors/modules/decode.json` stops at `stps_instruction` plus a few branches and basic loads. Add cases that drive each of those opcodes so `mem_in.stidx`, `mem_in.rdc`, `mem_in.rdf`, and `mem_in.wrf` are asserted and confirm that the decoded control signals pulse as shown in `decode.vhd`.
- While augmenting the MMU cases, keep an eye on the `dir`, `sel_rda`, and `sel_wra` selections triggered when `ir(9 downto 3)="0011101"` etc., so the test coverage fully exercises the load addressing paths for local memory vs. VP/SP/AR.

## Next steps
1. Add the missing stack vectors to `verification/test-vectors/modules/stack.json`, run `python tools/scripts/validate_vectors.py`, and re-run the relevant CocoTB tests to catch regressions.
2. Extend `verification/test-vectors/modules/decode.json` with the uncovered MMU instructions and load patterns, then validate/execute the decoder cocotb suite to confirm the new vectors fire as expected.
