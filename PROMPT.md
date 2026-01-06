# JOP SpinalHDL Port - Continuation Prompt

## Project Overview

You are working on porting the JOP (Java Optimized Processor) from VHDL to SpinalHDL. JOP is a 4-stage pipeline processor that executes Java bytecode directly in hardware using microcode. The project uses a Test-Driven Development (TDD) approach, implementing features incrementally with comprehensive test coverage.

## Current Status: Phase B Complete ✅

**All 24 tests passing (100%)**
- BytecodeFetchStage: 12/12 tests ✅
- Stack: 7/7 tests ✅
- JumpTable: 5/5 tests ✅

### Completed Phases

#### Phase A: BytecodeFetchStage Foundation
- JPC (Java Program Counter) register with increment logic
- JBC RAM (2KB synchronous bytecode cache)
- JumpTable integration (bytecode → microcode address translation)
- JPC write for method calls (jpc_wr)
- Priority logic: jpc_wr > jfetch > hold
- **Tests:** 8/8 passing
- **Commits:** `f89d9b8`, `af078c9`

#### Phase B.1: Operand Accumulation
- 16-bit jopd register (operand accumulator)
- Low byte always updates from RAM every cycle
- High byte shifts from low byte on jopdfetch
- Supports multi-byte immediate operands
- **Tests:** 2/2 passing
- **Commit:** `9e1b64c`

#### Phase B.2: Branch Logic
- Branch capture (save jinstr and jpc_br on jfetch)
- Branch target calculation with sign extension
- 15 branch types: goto, ifeq, ifne, iflt, ifge, ifgt, ifle, if_icmpeq, if_icmpne, if_icmplt, if_icmpge, if_icmpgt, if_icmple
- Condition evaluation based on flags (zf, nf, eq, lt)
- Priority logic: jpc_wr > jmp > jfetch > hold
- **Tests:** 4/4 passing
- **Commit:** `cc6ad4f`

### Documentation Status
- ✅ `docs/PHASE-A-STATUS.md` - Phase A implementation details
- ✅ `docs/PHASE-B-STATUS.md` - Phase B.1 & B.2 implementation details
- ✅ Comprehensive status documentation with technical decisions and lessons learned

## Next Steps: Phase C Recommendations

### Option 1: Execute Stage (Recommended)
**Why:** Natural progression; enables end-to-end bytecode execution

**Tasks:**
1. Examine VHDL `core/decode.vhd` and `core/fetch.vhd` to understand execute stage
2. Create `core/spinalhdl/src/main/scala/jop/pipeline/ExecuteStage.scala`
3. Implement microcode fetch and execution logic
4. Add ALU operations for basic bytecode instructions
5. Integrate with BytecodeFetchStage (provide jfetch, jopdfetch, jbr signals)
6. Generate condition flags (zf, nf, eq, lt) for branch logic
7. Write comprehensive tests (TDD approach)
8. Test simple bytecode sequences end-to-end

### Option 2: Method Cache Integration
**Why:** Required for method calls and returns

**Tasks:**
1. Examine VHDL `core/cache/` directory
2. Understand method cache architecture
3. Implement method cache lookup and fill logic
4. Integrate with BytecodeFetchStage
5. Add cache miss handling
6. Write tests for cache operations

### Option 3: Interrupt/Exception Handling
**Why:** Needed for complete processor functionality

**Tasks:**
1. Examine VHDL interrupt handling logic
2. Add interrupt vectors and handlers
3. Implement exception handling (e.g., sys_noim for unimplemented instructions)
4. Add save/restore PC logic
5. Write tests for interrupt scenarios

## Key Technical Information

### File Locations

**Implementation Files:**
- `core/spinalhdl/src/main/scala/jop/pipeline/BytecodeFetchStage.scala` (234 lines)
- `core/spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` (128 lines)
- `core/spinalhdl/src/main/scala/jop/pipeline/Stack.scala`

**Test Files:**
- `core/spinalhdl/src/test/scala/jop/pipeline/BytecodeFetchStageTest.scala` (702 lines)
- `core/spinalhdl/src/test/scala/jop/pipeline/JumpTableTest.scala`
- `core/spinalhdl/src/test/scala/jop/pipeline/StackTest.scala`

**VHDL Reference:**
- `original/vhdl/core/bcfetch.vhd` - BytecodeFetch reference
- `original/vhdl/core/fetch.vhd` - Microcode fetch reference
- `original/vhdl/core/decode.vhd` - Decode/Execute reference
- `original/vhdl/core/cache/` - Method cache implementations

**Documentation:**
- `docs/PHASE-A-STATUS.md` - Phase A details
- `docs/PHASE-B-STATUS.md` - Phase B.1 & B.2 details
- `README.md` - Project overview
- `docs/JOPA_TOOL.md` - Jopa microcode assembler

### Build & Test Commands

```bash
# Run all tests
cd core/spinalhdl && sbt test

# Run specific test suite
sbt "testOnly jop.pipeline.BytecodeFetchStageTest"

# Regenerate microcode (after modifying asm/*.mic)
cd asm && make

# Generate Verilog (for synthesis)
cd core/spinalhdl && sbt "runMain jop.JopTop"
```

### Important Technical Patterns

#### 1. Test-Driven Development (TDD)
**Always write tests FIRST, then implement:**
```scala
// 1. Write test describing expected behavior
"feature name" in {
  // Setup DUT and test data
  // Execute operations
  // Assert expected results
}

// 2. Implement feature to make test pass
// 3. Verify all tests still pass
// 4. Commit
```

#### 2. Synchronous RAM Timing
**JBC RAM has 1-cycle read latency:**
```scala
// Cycle N: Set address
jbcAddr := newAddress

// Cycle N+1: Wait for RAM output
dut.clockDomain.waitSampling()
sleep(1)  // Let combinational logic settle

// Cycle N+1: Read data
val data = jbcData  // Now has data from newAddress
```

#### 3. Register Update Patterns
**SpinalHDL registers update on clock edge:**
```scala
// Unconditional assignment (always happens)
register := value

// Conditional assignment
when(condition) {
  register := value
}

// Priority chain (first match wins)
when(highPriority) {
  register := value1
}.elsewhen(medPriority) {
  register := value2
}.otherwise {
  register := value3
}
```

#### 4. Forward Declaration Pattern
**When signal is used before assignment:**
```scala
// Early in code: declare
val jmp = Bool()

// Use in priority logic
when(jmp) {
  // ... use jmp ...
}

// Later in code: assign
jmp := False
when(condition) {
  jmp := True
}
```

### BytecodeFetchStage I/O Interface

**Inputs:**
```scala
val jfetch    = in Bool()         // Fetch bytecode (from Execute)
val jopdfetch = in Bool()         // Fetch operand byte (from Execute)
val jpc_wr    = in Bool()         // Write JPC from stack (method call)
val jbr       = in Bool()         // Branch evaluation enable (from Execute)
val din       = in Bits(32 bits)  // Stack data input (for jpc_wr)

// Branch condition flags (from Execute/ALU)
val zf = in Bool()                // Zero flag
val nf = in Bool()                // Negative flag
val eq = in Bool()                // Equal flag
val lt = in Bool()                // Less-than flag
```

**Outputs:**
```scala
val jpc_out = out UInt(config.jpcWidth + 1 bits)  // Current JPC (12-bit)
val opd     = out Bits(config.opdWidth bits)      // Operand (16-bit)
val jmaddr  = out Bits(config.jmpWidth bits)      // Microcode address (11-bit)
```

### Key Hardware Behaviors

#### Operand Accumulation
```scala
// Low byte ALWAYS updates from RAM (every cycle)
jopd(7 downto 0) := jbcData

// High byte shifts from low byte only on jopdfetch
when(io.jopdfetch) {
  jopd(15 downto 8) := jopd(7 downto 0)
}
```

#### Branch Logic
```scala
// Capture on jfetch: save instruction and branch start PC
when(io.jfetch) {
  jinstr := jbcData
  jpc_br := jpc
}

// Branch target calculation (signed offset)
val branchOffset = (jopd(config.jpcWidth - 8 downto 0) ## jbcData).asSInt.resize(config.jpcWidth + 1 bits)
jmp_addr := (jpc_br.asSInt + branchOffset).asUInt

// Branch condition evaluation (15 types)
val tp = jinstr(3 downto 0)  // Branch type from instruction low bits
jmp := False
when(io.jbr) {
  switch(tp) {
    is(9)  { when(io.zf) { jmp := True } }              // ifeq/ifnull
    is(10) { when(!io.zf) { jmp := True } }             // ifne/ifnonnull
    is(7)  { jmp := True }                              // goto (unconditional)
    // ... 12 more branch types
  }
}
```

#### Priority Logic
```scala
// JPC Update Priority: jpc_wr > jmp > jfetch > hold
when(io.jpc_wr) {
  jpc := io.din(config.jpcWidth downto 0).asUInt      // Method call (highest)
}.elsewhen(jmp) {
  jpc := jmp_addr                                      // Branch taken
}.elsewhen(io.jfetch || io.jopdfetch) {
  jpc := jpc + 1                                       // Sequential fetch
}
// else: hold current value (default register behavior)
```

## Development Workflow

### 1. Before Starting
- Read relevant VHDL files to understand behavior
- Check existing tests to understand patterns
- Review phase status documents for context

### 2. Implementation Process
- Write tests FIRST (TDD approach)
- Implement minimal code to pass tests
- Run tests frequently during development
- Add comments explaining non-obvious logic

### 3. Testing Standards
- Test normal operation paths
- Test edge cases (overflow, priority conflicts)
- Test timing/sequencing (especially with synchronous RAM)
- Aim for 100% test coverage
- All tests must pass before committing

### 4. Commit Standards
- Commit when logical chunk is complete (feature, phase)
- Write descriptive commit messages
- Include test results in commit message
- One feature per commit (atomic commits)

### 5. Documentation Standards
- Update status documents after completing phases
- Document technical decisions and rationale
- Include lessons learned
- Keep README.md updated with high-level progress

## Common Pitfalls to Avoid

1. **Don't skip test writing** - TDD approach has proven very effective
2. **Don't ignore RAM timing** - Synchronous RAM has 1-cycle latency
3. **Don't forget `sleep(1)`** - Needed after `waitSampling()` for combinational logic
4. **Don't guess VHDL behavior** - Read the VHDL carefully, it's the specification
5. **Don't add features not requested** - Keep implementation focused and minimal
6. **Don't batch commits** - Commit each logical chunk separately

## Questions to Ask

If you encounter ambiguity or need clarification:

1. **Architecture questions:** Check VHDL reference implementation first
2. **Priority questions:** Look at existing priority chains (jpc_wr > jmp > jfetch)
3. **Timing questions:** Refer to synchronous RAM patterns in existing tests
4. **Interface questions:** Check BytecodeFetchStage I/O bundle and existing components

## Success Criteria

For each new feature or phase:
- ✅ All tests pass (100%)
- ✅ Behavior matches VHDL reference
- ✅ Code is clean and well-commented
- ✅ Commit message is descriptive
- ✅ Documentation is updated

## Starting Prompt for Next Session

"I'm continuing work on the JOP SpinalHDL port. I've completed Phase A (BytecodeFetchStage foundation) and Phase B (operand accumulation and branch logic). All 24 tests are passing.

I'm ready to start Phase C. Based on the recommendations in PROMPT.md, I'd like to implement the Execute Stage to enable end-to-end bytecode execution. Let's begin by examining the VHDL reference implementation in `original/vhdl/core/fetch.vhd` and `original/vhdl/core/decode.vhd` to understand the execute stage architecture."

---

**Note:** This project uses a systematic, test-driven approach. Always prioritize understanding existing code and VHDL reference before implementing new features. The goal is correctness and completeness, not speed.
