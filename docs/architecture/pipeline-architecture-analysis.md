# JOP Pipeline Architecture Analysis

## Executive Summary

**Question:** Should we continue with direct VHDL→SpinalHDL module-by-module translation, or redesign using SpinalHDL's pipeline API?

**Recommendation:** **Hybrid Approach** - Use SpinalHDL Pipeline API for pipeline stages while keeping standalone components (mul, shift) as separate modules.

---

## Current Status

### Completed Modules (Direct Translation)
- ✅ **mul.vhd → Mul.scala** - Bit-serial multiplier (standalone)
- ✅ **shift.vhd → Shift.scala** - Barrel shifter (combinational)

**Success Factors:**
- Isolated functionality with clear interfaces
- No pipeline dependencies
- Easy to verify independently
- Reusable components

---

## Original VHDL Architecture Analysis

### JOP Pipeline Structure (from core.vhd)

```
bcfetch → fetch → decode → stack (with ALU components)
   ↓        ↓       ↓         ↓
[Branch] [Instr] [Control] [Execute]
```

**Components:**
1. **bcfetch** - Bytecode fetch and branch logic
2. **fetch** - Instruction fetch from microcode ROM
3. **decode** - Decode microcode, generate control signals
4. **stack** - Stack-based ALU with execution units (uses mul, shift)

**Signal Flow:**
```vhdl
-- Massive number of interconnect signals:
signal br, jmp, jbr     : std_logic;          -- Branch control
signal opd              : std_logic_vector;    -- Operand data
signal sel_sub, sel_amux, ena_a : std_logic;  -- ALU control
signal sel_bmux, sel_log, sel_shf : std_logic; -- More ALU control
signal sel_lmux, sel_imux, sel_rmux : std_logic;
signal stk_zf, stk_nf, stk_eq, stk_lt : std_logic; -- Flags
-- ... and many more
```

**Issues with Direct Translation:**
1. **Tight coupling** - Components communicate via dozens of individual signals
2. **Manual staging** - Each component manually implements its pipeline register
3. **Scattered control** - Control logic distributed across multiple files
4. **Difficult retiming** - Moving logic between stages requires manual rewiring

---

## SpinalHDL Pipeline API Overview

### Key Concepts

**1. Node** - A stage in the pipeline
```scala
val fetch = Node()
val decode = Node()
val execute = Node()
```

**2. Payload** - Data that flows through the pipeline
```scala
val PC = Payload(UInt(32 bits))
val INSTRUCTION = Payload(Bits(32 bits))
```

**3. StageLink** - Connects nodes with automatic staging
```scala
val s0 = Node()
val s1 = Node()
StageLink(s0, s1)  // Automatic register insertion
```

**4. Accessing Data**
```scala
s0(PC) := 0x1000        // Write to payload at stage s0
val instr = s1(INSTRUCTION)  // Read from payload at stage s1
```

### Advantages

✅ **Automatic Register Inference** - No manual `Reg()` needed between stages
✅ **Signal Propagation** - Payloads automatically flow through stages
✅ **Easy Retiming** - Move logic between stages without rewiring
✅ **Centralized Control** - Control signals managed by pipeline framework
✅ **Stall/Flush Support** - Built-in hazard handling mechanisms
✅ **Clearer Intent** - Pipeline structure is explicit in code

### Example: Simple 3-Stage Pipeline
```scala
class SimplePipeline extends Component {
  val pipeline = new Pipeline {
    val fetch = new Stage
    val decode = new Stage
    val execute = new Stage

    // Define payloads
    val PC = Payload(UInt(32 bits))
    val INSTR = Payload(Bits(32 bits))
    val RESULT = Payload(UInt(32 bits))

    // Connect stages
    connect(fetch, decode)(Connection.M2S())
    connect(decode, execute)(Connection.M2S())

    // Fetch stage logic
    fetch.area {
      PC := fetchLogic()
      INSTR := readInstr(PC)
    }

    // Decode stage logic
    decode.area {
      val decoded = decodeInstr(INSTR)
      // Automatically propagates to next stage
    }

    // Execute stage logic
    execute.area {
      RESULT := alu(INSTR, operands)
    }
  }
}
```

**Sources:**
- [SpinalHDL Pipeline API Documentation](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/introduction.html)
- [SpinalHDL Pipeline Examples](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Examples/index.html)

---

## Architectural Approaches

### Option 1: Continue Direct Translation

**Approach:** Translate each VHDL component to SpinalHDL 1:1

```scala
// core/spinalhdl/src/main/scala/jop/core/
Core.scala       // Instantiates all components
Bcfetch.scala    // Direct translation
Fetch.scala      // Direct translation
Decode.scala     // Direct translation
Stack.scala      // Direct translation
```

**Pros:**
- ✅ Stays close to original VHDL
- ✅ Easy to verify against VHDL (same structure)
- ✅ Reuses completed mul/shift modules
- ✅ Incremental progress (module by module)

**Cons:**
- ❌ Misses SpinalHDL pipeline features
- ❌ Manual signal wiring between stages
- ❌ Difficult to add hazard detection later
- ❌ Hard to retime or optimize pipeline
- ❌ Less idiomatic SpinalHDL code

### Option 2: Full Pipeline API Redesign

**Approach:** Redesign using SpinalHDL Pipeline API

```scala
class JopPipeline extends Component {
  val pipeline = new Pipeline {
    val bcfetch = new Stage  // Bytecode fetch
    val fetch = new Stage    // Microcode fetch
    val decode = new Stage   // Decode
    val execute = new Stage  // Stack/ALU execution

    // Define all payloads
    val JPC = Payload(UInt(jpcWidth bits))
    val BYTECODE = Payload(Bits(8 bits))
    val MICROCODE = Payload(Bits(instrWidth bits))
    val TOS = Payload(UInt(32 bits))
    val NOS = Payload(UInt(32 bits))
    // ... etc

    // Connect stages with automatic staging
    connect(bcfetch, fetch)(Connection.M2S())
    connect(fetch, decode)(Connection.M2S())
    connect(decode, execute)(Connection.M2S())

    bcfetch.area { /* Bytecode fetch logic */ }
    fetch.area { /* Microcode fetch logic */ }
    decode.area { /* Decode logic */ }
    execute.area {
      // Use mul and shift as execution units
      val multiplier = Mul()
      val shifter = Shift()
    }
  }
}
```

**Pros:**
- ✅ Idiomatic SpinalHDL
- ✅ Easier retiming and optimization
- ✅ Built-in stall/flush support
- ✅ Cleaner code structure
- ✅ Better for future enhancements

**Cons:**
- ❌ Requires redesign (not 1:1 translation)
- ❌ Harder to verify against original VHDL
- ❌ Steeper learning curve
- ❌ Discards completed module work (fetch, decode as separate files)
- ❌ All-or-nothing approach (can't do incrementally)

### Option 3: Hybrid Approach (RECOMMENDED)

**Approach:** Use Pipeline API for pipeline structure, keep ALU components separate

```scala
// Execution units (standalone, reusable)
core/spinalhdl/src/main/scala/jop/core/
  Mul.scala      ✅ KEEP - Standalone multiplier
  Shift.scala    ✅ KEEP - Standalone shifter
  Alu.scala      - Arithmetic/logic unit (to be created)

// Pipeline structure
core/spinalhdl/src/main/scala/jop/pipeline/
  JopPipeline.scala     - Main pipeline using Pipeline API
  BcfetchStage.scala    - Bytecode fetch stage logic
  FetchStage.scala      - Microcode fetch stage logic
  DecodeStage.scala     - Decode stage logic
  ExecuteStage.scala    - Execute stage logic (uses Mul, Shift, Alu)
  StackLogic.scala      - Stack management logic
```

**Pipeline Structure:**
```scala
class JopPipeline extends Component {
  val pipeline = new Pipeline {
    // Define stages
    val bcfetch = new Stage("bcfetch")
    val fetch = new Stage("fetch")
    val decode = new Stage("decode")
    val execute = new Stage("execute")

    // Define payloads (data flowing through pipeline)
    val JPC = Payload(UInt(jpcWidth bits))
    val OPCODE = Payload(Bits(8 bits))
    val MICROCODE = Payload(Bits(instrWidth bits))
    val CONTROL = Payload(new Bundle {
      val sel_sub = Bool()
      val sel_amux = Bits(2 bits)
      val ena_a = Bool()
      // ... all control signals as bundle
    })
    val TOS = Payload(UInt(32 bits))
    val NOS = Payload(UInt(32 bits))

    // Connect stages
    connect(bcfetch, fetch)(Connection.M2S())
    connect(fetch, decode)(Connection.M2S())
    connect(decode, execute)(Connection.M2S())

    // Stage logic areas
    bcfetch.area {
      // Bytecode fetch logic
      when(branchCondition) { JPC := branchTarget }
    }

    fetch.area {
      // Microcode ROM lookup
      MICROCODE := microcodeRom(OPCODE)
    }

    decode.area {
      // Decode microcode to control signals
      CONTROL.sel_sub := MICROCODE(bit_sel_sub)
      CONTROL.sel_amux := MICROCODE(bits_sel_amux)
    }

    execute.area {
      // Instantiate execution units (standalone components)
      val mul = Mul()
      val shift = Shift()
      val alu = Alu()

      // Use control signals to drive execution units
      shift.io.din := TOS
      shift.io.off := NOS(4 downto 0)
      shift.io.shtyp := CONTROL.sel_shf

      mul.io.ain := TOS
      mul.io.bin := NOS
      mul.io.wr := CONTROL.mul_wr

      // Stack update logic
      when(CONTROL.ena_a) {
        TOS := aluResult
      }
    }
  }
}
```

**Pros:**
- ✅ Uses SpinalHDL Pipeline API for pipeline structure
- ✅ Keeps standalone ALU components (mul, shift) reusable
- ✅ Easier to verify (execution units tested separately)
- ✅ Incremental approach possible
- ✅ Better than pure direct translation
- ✅ Clearer separation of concerns

**Cons:**
- ⚠️ Mixed approach (some new design, some translation)
- ⚠️ Need to learn Pipeline API
- ⚠️ Still requires understanding original VHDL pipeline flow

---

## Recommended Workflow

### Phase 1: Research & Prototyping
1. **Study Pipeline API** - Create simple example pipeline
2. **Map VHDL signals to Payloads** - Identify what flows through stages
3. **Prototype one stage** - Implement bcfetch or fetch as proof of concept
4. **Validate approach** - Run simple test to verify pipeline works

### Phase 2: Implement Execution Units
1. ✅ **Mul, Shift** - Already complete
2. **Create Alu.scala** - Arithmetic/logic operations (add, sub, and, or, xor)
3. **Test standalone** - Verify each unit independently

### Phase 3: Build Pipeline Structure
1. **Create JopPipeline.scala** - Define stages and payloads
2. **Implement BcfetchStage** - Bytecode fetch logic
3. **Implement FetchStage** - Microcode fetch logic
4. **Implement DecodeStage** - Decode microcode
5. **Implement ExecuteStage** - Stack + ALU execution

### Phase 4: Integration & Testing
1. **Connect execution units** - Wire mul, shift, alu into execute stage
2. **Test individual stages** - Stage-by-stage verification
3. **Test full pipeline** - End-to-end microcode execution
4. **Compare with VHDL** - Use CocoTB to verify behavioral equivalence

---

## Signal Mapping Strategy

### VHDL Signals → SpinalHDL Payloads

**Current VHDL (scattered across components):**
```vhdl
-- In core.vhd:
signal br, jmp, jbr : std_logic;
signal opd : std_logic_vector(31 downto 0);
signal sel_sub, sel_amux, ena_a : std_logic;
-- ... dozens more
```

**SpinalHDL Pipeline (organized by stage):**
```scala
// Payloads flow through pipeline automatically
val BRANCH_CONTROL = Payload(new Bundle {
  val br = Bool()
  val jmp = Bool()
  val jbr = Bool()
})

val ALU_CONTROL = Payload(new Bundle {
  val sel_sub = Bool()
  val sel_amux = Bits(2 bits)
  val ena_a = Bool()
  val sel_bmux = Bits(2 bits)
  // ... group related signals
})

val STACK_DATA = Payload(new Bundle {
  val tos = UInt(32 bits)
  val nos = UInt(32 bits)
  val sp = UInt(spWidth bits)
})
```

**Benefits:**
- Signals grouped logically into bundles
- Automatic propagation through stages
- Clear data dependencies
- Type safety

---

## Verification Strategy

### Hybrid Verification Approach

**1. Unit Testing (Standalone Components)**
- ✅ Mul, Shift tested independently (already done)
- ✅ Alu tested independently (to be created)
- Uses ScalaTest + SpinalSim

**2. Stage Testing (Pipeline Stages)**
- Test each stage in isolation
- Feed known inputs, verify outputs
- Use Pipeline API's stage access features

**3. Integration Testing (Full Pipeline)**
- Execute microcode sequences
- Compare against VHDL golden model
- Use shared JSON test vectors (like mul/shift)

**4. CocoTB Cross-Validation**
- Generate VHDL from SpinalHDL pipeline
- Run original CocoTB tests
- Verify cycle-accurate equivalence

---

## Decision Matrix

| Criteria | Direct Translation | Full Pipeline API | Hybrid (Recommended) |
|----------|-------------------|-------------------|----------------------|
| **Idiomatic SpinalHDL** | ❌ Low | ✅ High | ✅ High |
| **Verification Complexity** | ✅ Low | ❌ High | ⚠️ Medium |
| **Reusability** | ⚠️ Medium | ✅ High | ✅ High |
| **Retiming/Optimization** | ❌ Hard | ✅ Easy | ✅ Easy |
| **Learning Curve** | ✅ Easy | ❌ Steep | ⚠️ Moderate |
| **Incremental Progress** | ✅ Yes | ❌ No | ✅ Yes |
| **Keeps mul/shift work** | ✅ Yes | ⚠️ Reintegrate | ✅ Yes |
| **Future Enhancements** | ❌ Hard | ✅ Easy | ✅ Easy |
| **Code Maintainability** | ⚠️ Medium | ✅ High | ✅ High |

**Score:**
- Direct Translation: 5/9 ✅
- Full Pipeline API: 5/9 ✅
- **Hybrid: 8/9 ✅ WINNER**

---

## Next Steps

### Immediate Action Items

1. **Study Pipeline API**
   - Read [SpinalHDL Pipeline Documentation](https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/introduction.html)
   - Create simple example (2-3 stage pipeline)
   - Understand Node, Stage, Payload, Connection concepts

2. **Create Prototype**
   - Implement one stage (e.g., fetch) using Pipeline API
   - Test basic functionality
   - Validate approach

3. **Define Pipeline Structure**
   - Map VHDL signals to Payloads
   - Define stage boundaries
   - Create skeleton JopPipeline.scala

4. **Implement Alu Component**
   - Create Alu.scala (add, sub, and, or, xor operations)
   - Test with ScalaTest
   - Similar approach to mul/shift

5. **Build First Stage**
   - Implement one complete stage (bcfetch or fetch)
   - Integrate with test framework
   - Verify against VHDL

### Questions to Answer

- Should we keep decode as a separate component or integrate into pipeline?
- How to handle stack operations (separate component or part of execute stage)?
- What granularity for Payloads (individual signals or bundles)?
- How to structure test vectors for pipeline (vs standalone components)?

---

## Conclusion

**Recommendation: Hybrid Approach**

Use SpinalHDL's Pipeline API for the pipeline structure while keeping execution units (mul, shift, alu) as standalone, reusable components. This balances:

- ✅ Idiomatic SpinalHDL (pipeline API)
- ✅ Incremental progress (stage by stage)
- ✅ Verification feasibility (unit + integration testing)
- ✅ Preserves completed work (mul, shift remain standalone)
- ✅ Future flexibility (easy to enhance/optimize)

This approach provides the best of both worlds: modern pipeline infrastructure with well-tested, reusable execution units.

**Next Concrete Step:** Create a simple Pipeline API prototype to validate the approach.
