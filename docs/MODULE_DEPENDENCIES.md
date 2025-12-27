# Module Dependencies

This document maps the dependencies between JOP core modules to help agents understand what order to work in and what files to reference.

## Dependency Graph

```
jop_types.vhd (BASE - translate first!)
    ↓
    ├─→ bcfetch.vhd (bytecode fetch)
    │       ├─→ interfaces with: decode.vhd, cache.vhd
    │       └─→ uses: memory interface
    │
    ├─→ fetch.vhd (microcode fetch)
    │       ├─→ interfaces with: bcfetch.vhd, decode.vhd
    │       └─→ uses: microcode ROM
    │
    ├─→ decode.vhd (microcode decode)
    │       ├─→ interfaces with: fetch.vhd, stack.vhd
    │       └─→ decodes microcode instructions
    │
    ├─→ stack.vhd (execution unit)
    │       ├─→ interfaces with: decode.vhd
    │       ├─→ uses: mul.vhd, shift.vhd
    │       └─→ implements TOS/NOS stack operations
    │
    ├─→ cache.vhd (method cache)
    │       ├─→ interfaces with: bcfetch.vhd
    │       └─→ uses: memory interface
    │
    ├─→ mul.vhd (multiplier)
    │       └─→ used by: stack.vhd
    │
    └─→ shift.vhd (shifter)
            └─→ used by: stack.vhd

jopcpu.vhd (top-level integration)
    └─→ instantiates: bcfetch, fetch, decode, stack, cache, mul, shift

core.vhd (system integration)
    └─→ instantiates: jopcpu, memory interfaces, I/O
```

## Translation Order

### Phase 0: Foundation (START HERE)
1. **jop_types.vhd** → `jop/types/JopTypes.scala`
   - No dependencies
   - Used by everything
   - Constants, type definitions
   - **MUST BE DONE FIRST**

### Phase 1: Basic Pipeline (After jop_types)

**Option A: Bottom-up (Recommended)**
Start with modules that have fewest dependencies:

2. **mul.vhd** → `jop/pipeline/Mul.scala`
   - Simple, self-contained
   - Good first real module to port

3. **shift.vhd** → `jop/pipeline/Shift.scala`
   - Simple, self-contained
   - Good practice

4. **stack.vhd** → `jop/pipeline/Stack.scala`
   - Uses: mul, shift (already done)
   - Core execution unit

**Option B: Top-down**
Start with the fetch stages:

2. **bcfetch.vhd** → `jop/pipeline/BytecodeFetch.scala`
   - Bytecode fetch and translation
   - Interfaces are well-defined

3. **fetch.vhd** → `jop/pipeline/MicrocodeFetch.scala`
   - Microcode fetch
   - Connects to bcfetch

4. **decode.vhd** → `jop/pipeline/MicrocodeDecode.scala`
   - Microcode decode
   - Connects fetch to execute

### Phase 2: Memory Subsystem

5. **cache.vhd** → `jop/memory/MethodCache.scala`
   - Method cache implementation
   - Used by bcfetch

### Phase 3: Integration

6. **jopcpu.vhd** → `jop/JopCore.scala`
   - Integrate all modules
   - Top-level processor

7. **core.vhd** → `jop/JopSystem.scala`
   - System-level integration
   - Add memory and I/O interfaces

## File Dependencies

### jop_types.vhd
```vhdl
-- Self-contained
-- Defines:
--   - sc_cpu_out_type, sc_cpu_in_type (CPU interfaces)
--   - Constants (widths, depths, addresses)
```

### bcfetch.vhd
```vhdl
use work.jop_types.all;
-- Interfaces with:
--   - decode (provides bytecode)
--   - cache (fetches methods)
--   - memory system (reads bytecode)
```

### fetch.vhd
```vhdl
use work.jop_types.all;
-- Interfaces with:
--   - bcfetch (gets PC/addresses)
--   - decode (provides microcode)
--   - microcode ROM
```

### decode.vhd
```vhdl
use work.jop_types.all;
-- Interfaces with:
--   - fetch (receives microcode)
--   - stack (sends decoded operations)
```

### stack.vhd
```vhdl
use work.jop_types.all;
-- Instantiates:
--   - mul (multiplier)
--   - shift (shifter)
-- Interfaces with:
--   - decode (receives operations)
--   - memory (loads/stores)
```

### mul.vhd
```vhdl
use work.jop_types.all;
-- Self-contained multiplication unit
```

### shift.vhd
```vhdl
use work.jop_types.all;
-- Self-contained shift unit
```

### cache.vhd
```vhdl
use work.jop_types.all;
-- Interfaces with:
--   - bcfetch (provides bytecode)
--   - memory system
```

### jopcpu.vhd
```vhdl
use work.jop_types.all;
-- Instantiates all of the above
-- Top-level CPU entity
```

## Interface Signals

### Between bcfetch and fetch
- `jpc_out` - Java PC output from bcfetch
- `jpaddr` - Jump address
- `jfetch` - Fetch enable

### Between fetch and decode
- `mic_out` - Microcode instruction output
- `jmp_addr` - Jump address for microcode

### Between decode and stack
- `dec_op` - Decoded operation
- `tos`, `nos` - Top/next of stack values
- `sp` - Stack pointer

### Stack to/from memory
- `mem_addr` - Memory address
- `mem_data` - Memory data
- `mem_rd`, `mem_wr` - Read/write enables

## Recommended Starting Module

**Start with `mul.vhd`** because:
1. ✅ Small and self-contained
2. ✅ Only depends on jop_types
3. ✅ Clear functionality (multiplier)
4. ✅ Good for learning the workflow
5. ✅ Can be tested independently

**Workflow for mul.vhd:**
1. vhdl-tester: Analyze mul.vhd, create test vectors
2. vhdl-tester: Create CocoTB tests
3. spinalhdl-developer: Port to Mul.scala
4. spinalhdl-developer: Generate VHDL, test with CocoTB
5. spinalhdl-tester: Create ScalaTest
6. reviewer: Verify equivalence

Once mul.vhd is complete, you have a proven workflow and can tackle larger modules.

## Critical Files to Examine First

Before starting any module:

1. **Read jop_types.vhd** completely
   - Understand all type definitions
   - Note all constants
   - Identify common patterns

2. **Scan jopcpu.vhd**
   - See how modules connect
   - Understand signal flow
   - Note clocking and reset

3. **Read target module** thoroughly
   - Understand algorithm
   - Note state machines
   - Identify edge cases

## Cross-Module Testing

Some test scenarios require multiple modules:

- **Pipeline test**: bcfetch → fetch → decode → stack
- **Memory test**: stack ↔ cache ↔ bcfetch
- **ALU test**: decode → stack → mul/shift

Plan for integration tests after individual modules are complete.

## See Also

- [docs/agents/REFERENCE_FILES.md](agents/REFERENCE_FILES.md) - How to reference files
- [original/REFERENCE.md](../original/REFERENCE.md) - Reference repository structure
- Individual agent workflows in [docs/agents/](agents/)
