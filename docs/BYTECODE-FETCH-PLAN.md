# Bytecode Fetch, Method Cache, and Memory Interface - Implementation Plan

**Date:** 2026-01-04
**Status:** PLANNING PHASE
**Scope:** BytecodeFetchStage, MethodCache, JumpTable, Memory Simulation

---

## Executive Summary

This plan outlines the implementation of the remaining JOP pipeline stages:
- **Bytecode Fetch Stage** - Fetches Java bytecodes and translates to microcode addresses
- **Method Cache** - Caches methods from main memory
- **Jump Table** - Maps bytecode opcodes → microcode ROM addresses
- **Memory Interface** - Simulated main memory for testing

**Recommended Approach:** Phased implementation starting with ROM-based testing, progressively adding cache and memory simulation.

**Estimated Effort:** 5-7 days (across all phases)

---

## Architecture Overview

### Current State (Completed)

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Microcode   │    │  Microcode   │    │  Microcode   │
│    Fetch     │───▶│   Decode     │───▶│   Execute    │
│  (FetchStage)│    │ (DecodeStage)│    │ (StackStage) │
└──────────────┘    └──────────────┘    └──────────────┘
       ▲
       │
┌──────┴───────┐
│Microcode ROM │
│ (test ROMs)  │
└──────────────┘

✅ 61 tests passing (100%)
✅ Production ready for microcode execution
```

### Target State (After This Plan)

```
┌──────────────┐         ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Bytecode   │         │  Microcode   │    │  Microcode   │    │  Microcode   │
│    Fetch     │────────▶│    Fetch     │───▶│   Decode     │───▶│   Execute    │
│   (NEW)      │ jpaddr  │ (FetchStage) │    │ (DecodeStage)│    │ (StackStage) │
└──────┬───────┘         └──────────────┘    └──────────────┘    └──────────────┘
       │                        │
       │                 ┌──────┴───────┐
       │                 │Microcode ROM │
       │                 └──────────────┘
       │
┌──────┴───────┐  ┌──────────────┐
│ Method Cache │  │  Jump Table  │
│   (NEW)      │  │    (NEW)     │
└──────┬───────┘  └──────────────┘
       │
┌──────┴───────┐
│ Main Memory  │
│  Simulation  │
│    (NEW)     │
└──────────────┘
```

**New Data Flow:**
1. BytecodeFetch reads bytecode from MethodCache/JBC RAM
2. JumpTable translates bytecode → microcode address (jpaddr)
3. FetchStage uses jpaddr to read microcode ROM
4. Decode → Execute (existing pipeline)

---

## Implementation Approaches - Trade-Off Analysis

### Approach 1: Full Implementation (VHDL-Equivalent)

**Pros:**
- ✅ Complete parity with original VHDL
- ✅ All features present (interrupts, exceptions, cache, MMU interface)
- ✅ Production-ready immediately

**Cons:**
- ❌ Complex - 6-8 days effort
- ❌ Hard to test incrementally
- ❌ High risk of bugs in initial integration
- ❌ Violates "progressive validation" approach

**Verdict:** NOT RECOMMENDED for initial implementation

---

### Approach 2: Simplified (Testing-Only)

**Pros:**
- ✅ Fast - 2-3 days
- ✅ Simple to test
- ✅ Sufficient for current test suite

**Cons:**
- ❌ Not production-ready
- ❌ Will need significant rework later
- ❌ Doesn't match VHDL architecture
- ❌ Limited learning value

**Verdict:** TOO SIMPLIFIED - doesn't provide path to production

---

### Approach 3: Phased Implementation (RECOMMENDED)

**Strategy:** Build progressively, testing at each phase

**Phase A: Core Bytecode Fetch (2 days)**
- ROM-based JBC storage (no cache yet)
- Basic bytecode fetch pipeline
- Jump table (hardcoded or file-loaded)
- Integration with existing FetchStage
- Testing: Simple bytecode sequences

**Phase B: Method Cache (2 days)**
- Direct-mapped cache implementation
- Cache lookup state machine
- Cache/JBC integration
- Testing: Cache hit/miss patterns

**Phase C: Memory Interface (1-2 days)**
- Simulated main memory (SpinalHDL Mem)
- Method loading from memory
- .jop file loading
- Testing: Load real JOP programs

**Phase D: Advanced Features (1-2 days, optional)**
- Interrupt/exception handling
- Branch optimizations
- Performance counters

**Pros:**
- ✅ Progressive validation (proven approach)
- ✅ Early testing at each phase
- ✅ Matches existing workflow (Phases 1-3)
- ✅ Provides production path
- ✅ Manageable complexity
- ✅ User can provide feedback between phases

**Cons:**
- ⚠️ Slightly longer than simplified approach
- ⚠️ Requires refactoring between phases (minimal)

**Verdict:** ⭐ RECOMMENDED - Best balance of risk, effort, and production readiness

---

## Recommended Implementation Plan (Phased Approach)

### Phase A: Core Bytecode Fetch (2 days)

#### A.1 Component: BytecodeFetchStage (Day 1, 4 hours)

**File:** `core/spinalhdl/src/main/scala/jop/pipeline/BytecodeFetchStage.scala`

**Configuration:**
```scala
case class BytecodeFetchConfig(
  jpcWidth: Int = 11,      // Java PC width (2KB bytecode space)
  pcWidth: Int = 11,       // Microcode ROM address width
  instrWidth: Int = 8      // Java bytecode width
) {
  require(jpcWidth >= 10, "Minimum 1KB bytecode cache")
  require(pcWidth == 11, "Microcode ROM is 2K instructions")

  def jbcDepth: Int = 1 << jpcWidth
}
```

**I/O Interface:**
```scala
val io = new Bundle {
  // From/to FetchStage (microcode fetch)
  val jfetch = in Bool()        // Fetch next bytecode
  val jopdfetch = in Bool()     // Fetch operand byte
  val jpaddr = out UInt(pcWidth bits)  // Microcode ROM address

  // Bytecode operands (16-bit)
  val opd = out Bits(16 bits)

  // Java PC control
  val jpcWr = in Bool()         // Load JPC from stack (method call)
  val din = in Bits(32 bits)    // JPC value from stack

  // Branch control (from decode)
  val jmp = in Bool()           // Take branch
  val zf = in Bool()            // Zero flag
  val nf = in Bool()            // Negative flag
  val eq = in Bool()            // Equal flag
  val lt = in Bool()            // Less than flag

  // JBC RAM interface (internal to component for Phase A)
  // Phase B: Move to MethodCache component
}
```

**Internal Components:**
```scala
// 1. Java PC register (jpc)
val jpc = Reg(UInt(jpcWidth bits)) init(0)

// 2. JBC RAM (ROM for Phase A)
val jbcRam = Mem(Bits(8 bits), 1 << jpcWidth)
// Initialize from ROM pattern or file

// 3. Bytecode register for branch decoding
val jinstr = Reg(Bits(8 bits)) init(0)

// 4. Operand accumulator (16-bit)
val jopd = Reg(Bits(16 bits)) init(0)

// 5. Branch PC save
val jpcBr = Reg(UInt(jpcWidth bits)) init(0)
```

**Key Logic Blocks:**

1. **JPC Update Logic:**
```scala
when(reset) {
  jpc := 0
}.elsewhen(io.jpcWr) {
  jpc := io.din(jpcWidth-1 downto 0).asUInt  // Method call
}.elsewhen(io.jmp) {
  jpc := jmpAddr  // Branch taken
}.elsewhen(io.jfetch || io.jopdfetch) {
  jpc := jpc + 1  // Increment
}.otherwise {
  jpc := jpc  // Hold
}
```

2. **Branch Target Calculation:**
```scala
// Decode branch type from jinstr
val branchType = jinstr  // 0x99=ifeq, 0x9A=ifne, etc.

// Calculate condition
val branchCondition = /* ... based on flags ... */

// Calculate offset (signed, 16-bit from jopd)
val branchOffset = jopd.asSInt.resize(jpcWidth).asUInt

// Calculate target
val jmpAddr = jpcBr + branchOffset
```

3. **JBC Read:**
```scala
// Registered address for ROM read
val jbcAddr = Reg(UInt(jpcWidth bits)) init(0)
jbcAddr := jpcMux  // Mux between jpc, jpc+1, jmpAddr

// ROM read (1 cycle latency)
val jbcData = jbcRam.readSync(jbcAddr, enable=True)
```

**Simplified for Phase A:**
- No interrupts/exceptions
- No method cache (JBC is ROM)
- No MMU write interface
- Branch support (essential for testing)

#### A.2 Component: JumpTable (Day 1, 2 hours)

**File:** `core/spinalhdl/src/main/scala/jop/JumpTable.scala`

**Two Implementation Options:**

**Option 1: Hardcoded Scala Map (Simple, for testing)**
```scala
class JumpTable(pcWidth: Int = 11) extends Component {
  val io = new Bundle {
    val bytecode = in Bits(8 bits)
    val jpaddr = out UInt(pcWidth bits)
  }

  // Hardcoded mapping (extracted from jtbl.vhd)
  val jumpTable = Map[Int, Int](
    0x00 -> 0x218,  // nop
    0x60 -> 0x194,  // iadd
    0x64 -> 0x19C,  // isub
    // ... 256 entries total
  )

  // Lookup logic
  io.jpaddr := U(jumpTable.getOrElse(
    io.bytecode.asUInt.toInt,
    0  // Default to address 0
  ), pcWidth bits)
}
```

**Option 2: ROM-Based (Matches VHDL, scalable)**
```scala
class JumpTable(pcWidth: Int = 11) extends Component {
  val io = new Bundle {
    val bytecode = in Bits(8 bits)
    val jpaddr = out UInt(pcWidth bits)
  }

  // 256-entry ROM (one per bytecode)
  val rom = Mem(UInt(pcWidth bits), 256)

  // Load from jtbl data file or hardcoded Seq
  rom.init(JumpTableData.entries)

  // Combinational lookup
  io.jpaddr := rom.readAsync(io.bytecode.asUInt)
}
```

**JumpTableData Object:**
```scala
object JumpTableData {
  // Extracted from /home/peter/git/jop.arch/jop/asm/generated/jtbl.vhd
  def entries: Seq[BigInt] = Seq(
    BigInt(0x218),  // 0x00 - nop
    BigInt(0x218),  // 0x01 - (unused)
    // ... 256 entries
  )
}
```

**Decision Point:** Start with Option 1 (simpler), migrate to Option 2 if needed.

#### A.3 Integration: JopCoreWithBytecode (Day 1, 2 hours)

**File:** `core/spinalhdl/src/main/scala/jop/JopCoreWithBytecode.scala`

```scala
case class JopCoreWithBytecodeConfig(
  // Extend JopCoreConfig
  core: JopCoreConfig = JopCoreConfig(),
  bcfetch: BytecodeFetchConfig = BytecodeFetchConfig()
) {
  require(bcfetch.pcWidth == core.pcWidth, "PC widths must match")
}

class JopCoreWithBytecode(
  config: JopCoreWithBytecodeConfig = JopCoreWithBytecodeConfig()
) extends Component {

  val io = /* ... TBD based on testing needs ... */

  // Instantiate components
  val bcfetchStage = new BytecodeFetchStage(config.bcfetch)
  val jumpTable = new JumpTable(config.bcfetch.pcWidth)
  val fetchStage = new FetchStage(config.core.fetchConfig)
  val decodeStage = new DecodeStage(config.core.decodeConfig)
  val stackStage = new StackStage(config.core.stackConfig)

  // Connect bytecode fetch → jump table
  jumpTable.io.bytecode := bcfetchStage.io.bytecode

  // Connect jump table → microcode fetch
  fetchStage.io.jpaddr := jumpTable.io.jpaddr

  // Connect microcode fetch → decode (existing)
  decodeStage.io.instr := fetchStage.io.dout

  // Connect decode → bytecode fetch (feedback)
  bcfetchStage.io.jfetch := fetchStage.io.jfetch
  bcfetchStage.io.jopdfetch := fetchStage.io.jopdfetch
  bcfetchStage.io.jmp := decodeStage.io.jmp
  // ... flags, etc.

  // Existing decode → stack connections
  // ... (from JopCore.scala)
}
```

#### A.4 Testing (Day 2, 8 hours)

**Test Strategy:** ROM-based bytecode sequences

**Test ROM Pattern 1: Simple Bytecode Sequence**
```scala
// Test: iload_0; iload_1; iadd; istore_2
def simpleArithmeticBytecodes: Seq[BigInt] = Seq(
  BigInt(0x1A),  // iload_0 (load local var 0)
  BigInt(0x1B),  // iload_1 (load local var 1)
  BigInt(0x60),  // iadd (add)
  BigInt(0x3D),  // istore_2 (store to local var 2)
  BigInt(0x00),  // nop
  // ... padding
)
```

**Tests to Create:**
1. `test_bcfetch_simple.py` - Basic bytecode fetch
2. `test_bcfetch_iadd_sequence.py` - Bytecode → microcode → execution
3. `test_bcfetch_branch.py` - Branch instructions (ifeq, goto)
4. `test_bcfetch_jpc_control.py` - JPC write (method call)
5. `test_jump_table.py` - Jump table lookups

**Success Criteria:**
- ✅ 10+ bytecode fetch tests passing
- ✅ Integration with existing FetchStage working
- ✅ Simple JVM sequences execute correctly
- ✅ Branch instructions functional

**Files Created:**
- `BytecodeFetchStage.scala` (~600 lines)
- `JumpTable.scala` (~100 lines)
- `JopCoreWithBytecode.scala` (~300 lines)
- `BytecodeFetchStageTb.scala` (~150 lines)
- `test_bcfetch_*.py` (5 files, ~300 lines each)

---

### Phase B: Method Cache (2 days)

#### B.1 Component: MethodCache (Day 3, 6 hours)

**File:** `core/spinalhdl/src/main/scala/jop/memory/MethodCache.scala`

**Configuration:**
```scala
case class MethodCacheConfig(
  jpcWidth: Int = 11,       // Cache size = 2^jpcWidth bytes (2KB)
  blockBits: Int = 3,       // Blocks = 2^blockBits (8 blocks)
  tagWidth: Int = 18        // Tag width (256KB address space)
) {
  require(jpcWidth >= 11, "Min 2KB cache (JOP file format limit)")
  require(jpcWidth <= 12, "Max 4KB cache (JOP file format limit)")

  def blocks: Int = 1 << blockBits
  def blockSize: Int = (1 << jpcWidth) / blocks
}
```

**I/O Interface:**
```scala
val io = new Bundle {
  // Cache lookup
  val find = in Bool()                    // Start lookup
  val bcAddr = in UInt(18 bits)           // Method address in main memory
  val bcLen = in UInt(10 bits)            // Method length (words)

  val rdy = out Bool()                    // Lookup complete
  val inCache = out Bool()                // Hit (1) or miss (0)
  val bcstart = out UInt((jpcWidth-2) bits)  // Cache address (word-aligned)

  // JBC RAM write interface (for loading methods)
  val jbcWrAddr = out UInt((jpcWidth-2) bits)
  val jbcWrData = out Bits(32 bits)
  val jbcWrEn = out Bool()
}
```

**State Machine:**
```scala
object CacheState extends SpinalEnum {
  val IDLE, LOOKUP, UPDATE_TAGS = newElement()
}

val state = Reg(CacheState()) init(CacheState.IDLE)
```

**Tag Array:**
```scala
// One tag per block
val tags = Vec(Reg(UInt(tagWidth bits)) init(0), blocks)

// Next block pointer (round-robin replacement)
val nxt = Reg(UInt(blockBits bits)) init(0)
```

**Lookup Logic:**
```scala
// Parallel tag comparison
val hits = tags.map(_ === io.bcAddr(tagWidth-1 downto jpcWidth-2))
val hitAny = hits.reduce(_ || _)
val hitIndex = OHToUInt(hits)  // One-hot to binary

// Block address calculation
val blockAddr = Mux(hitAny, hitIndex, nxt)
io.bcstart := blockAddr @@ U(0, jpcWidth-2-blockBits bits)
```

**Simplified for Phase B:**
- No MMU integration (methods pre-loaded or loaded via test)
- Simple round-robin replacement
- Tag-only cache (data in JBC RAM)

#### B.2 Component: JBC Dual-Port RAM (Day 3, 2 hours)

**File:** `core/spinalhdl/src/main/scala/jop/memory/JbcRam.scala`

**Challenge:** Different port widths (32-bit write, 8-bit read)

```scala
class JbcRam(jpcWidth: Int = 11) extends Component {
  val io = new Bundle {
    // Write port (32-bit, word-addressed)
    val wrAddr = in UInt((jpcWidth-2) bits)
    val wrData = in Bits(32 bits)
    val wrEn = in Bool()

    // Read port (8-bit, byte-addressed)
    val rdAddr = in UInt(jpcWidth bits)
    val rdData = out Bits(8 bits)
  }

  // Internal RAM (32-bit words)
  val ram = Mem(Bits(32 bits), 1 << (jpcWidth-2))

  // Write port (synchronous)
  ram.write(
    address = io.wrAddr,
    data = io.wrData,
    enable = io.wrEn
  )

  // Read port (synchronous address, combinational mux)
  val rdAddrReg = Reg(UInt(jpcWidth bits)) init(0)
  rdAddrReg := io.rdAddr

  val wordAddr = rdAddrReg(jpcWidth-1 downto 2)
  val byteSelect = rdAddrReg(1 downto 0)

  val wordData = ram.readAsync(wordAddr)

  // Byte selection mux
  io.rdData := byteSelect.mux(
    0 -> wordData(7 downto 0),
    1 -> wordData(15 downto 8),
    2 -> wordData(23 downto 16),
    3 -> wordData(31 downto 24)
  )
}
```

#### B.3 Integration (Day 4, 4 hours)

**Modifications to BytecodeFetchStage:**
- Replace internal JBC ROM with JbcRam instance
- Add cache interface
- Add method loading capability

**Modifications to JopCoreWithBytecode:**
- Instantiate MethodCache
- Connect BytecodeFetch ↔ MethodCache ↔ JbcRam

#### B.4 Testing (Day 4, 4 hours)

**Tests:**
1. `test_method_cache.py` - Cache hit/miss
2. `test_jbc_ram.py` - Dual-port RAM
3. `test_bcfetch_with_cache.py` - Integrated bytecode fetch + cache

**Success Criteria:**
- ✅ 8+ cache tests passing
- ✅ Method loading functional
- ✅ Cache hit/miss detection working
- ✅ Round-robin replacement correct

---

### Phase C: Memory Interface & JOP File Loading (1-2 days)

#### C.1 Component: MainMemorySimulation (Day 5, 4 hours)

**File:** `core/spinalhdl/src/main/scala/jop/memory/MainMemorySim.scala`

**Purpose:** Simulated main memory that loads .jop file data

```scala
class MainMemorySim(
  addrWidth: Int = 18,  // 256KB address space
  dataWidth: Int = 32
) extends Component {
  val io = new Bundle {
    // Simple memory interface
    val addr = in UInt(addrWidth bits)
    val din = in Bits(dataWidth bits)
    val dout = out Bits(dataWidth bits)
    val ncs = in Bool()   // Chip select (active low)
    val noe = in Bool()   // Output enable (active low)
    val nwr = in Bool()   // Write enable (active low)
  }

  // Memory array
  val mem = Mem(Bits(dataWidth bits), 1 << addrWidth)

  // Initialize from mem_main.dat (via JopFileLoader)
  // mem.init(JopFileLoader.loadMainMemory("mem_main.dat"))

  // Read/write logic
  val addrReg = Reg(UInt(addrWidth bits)) init(0)
  when(!io.ncs) {
    addrReg := io.addr
  }

  when(!io.ncs && !io.nwr) {
    mem.write(addrReg, io.din)
  }

  io.dout := Mux(!io.ncs && !io.noe,
    mem.readAsync(addrReg),
    B(0, dataWidth bits)
  )
}
```

#### C.2 Utility: JopFileLoader (Day 5, 2 hours)

**File:** `core/spinalhdl/src/main/scala/jop/utils/JopFileLoader.scala`

```scala
object JopFileLoader {
  /**
   * Load mem_main.dat into memory initialization sequence
   */
  def loadMainMemory(filepath: String): Seq[BigInt] = {
    import scala.io.Source

    Source.fromFile(filepath)
      .getLines()
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => BigInt(line.toInt))
      .toSeq
  }

  /**
   * Load mem_rom.dat (microcode ROM)
   */
  def loadMicrocodeRom(filepath: String): Seq[BigInt] = {
    // Similar to loadMainMemory
  }

  /**
   * Parse .jop file directly (optional - more complex)
   */
  def parseJopFile(filepath: String): JopFileData = {
    // Parse .jop text format
    // Extract sections (header, statics, bytecode, etc.)
  }
}
```

#### C.3 Testing (Day 5-6, 8 hours)

**Tests:**
1. `test_main_memory_sim.py` - Memory read/write
2. `test_jop_file_loader.py` - File parsing
3. `test_hello_world.py` - Load and execute HelloWorld.jop

**Success Criteria:**
- ✅ Memory simulation functional
- ✅ .jop file loading working
- ✅ Simple JOP programs execute correctly

---

### Phase D: Advanced Features (Optional, 1-2 days)

**Features:**
1. Interrupt handling (`int_pend`, `exc_pend`)
2. Exception handling
3. MMU write interface for method loading
4. Branch prediction/optimization
5. Performance counters

**Decision Point:** User can decide if these are needed immediately or deferred

---

## File Structure

```
core/spinalhdl/src/main/scala/jop/
├── pipeline/
│   ├── BytecodeFetchStage.scala        # NEW (Phase A) - ~600 lines
│   ├── FetchStage.scala                # Existing
│   ├── DecodeStage.scala               # Existing
│   └── StackStage.scala                # Existing
├── memory/
│   ├── MethodCache.scala               # NEW (Phase B) - ~400 lines
│   ├── JbcRam.scala                    # NEW (Phase B) - ~100 lines
│   └── MainMemorySim.scala             # NEW (Phase C) - ~200 lines
├── utils/
│   └── JopFileLoader.scala             # NEW (Phase C) - ~150 lines
├── JumpTable.scala                     # NEW (Phase A) - ~100 lines
├── JopCoreWithBytecode.scala           # NEW (Phase A) - ~300 lines
└── JopCoreWithBytecodeTestRom.scala    # NEW (Phase A) - ~500 lines (test ROMs)

verification/cocotb/tests/
├── test_bcfetch_simple.py              # NEW (Phase A)
├── test_bcfetch_iadd_sequence.py       # NEW (Phase A)
├── test_bcfetch_branch.py              # NEW (Phase A)
├── test_jump_table.py                  # NEW (Phase A)
├── test_method_cache.py                # NEW (Phase B)
├── test_jbc_ram.py                     # NEW (Phase B)
├── test_main_memory_sim.py             # NEW (Phase C)
└── test_hello_world.py                 # NEW (Phase C)

docs/
└── BYTECODE-FETCH-PLAN.md              # This file
```

**Total New Code:** ~3000 lines Scala, ~2400 lines Python tests

---

## Dependencies

### SpinalHDL Components
- ✅ FetchStage (existing)
- ✅ DecodeStage (existing)
- ✅ StackStage (existing)
- ✅ Mem (SpinalHDL primitive)
- ✅ Bundle, Component, Area (SpinalHDL core)

### External Data
- Jump table data (from `/home/peter/git/jop.arch/jop/asm/generated/jtbl.vhd`)
- Microcode ROM (from `mem_rom.dat`)
- Test programs (from `java/target/dist/bin/*.jop`)

### Tools
- ✅ GHDL (existing)
- ✅ CocoTB (existing)
- ✅ sbt (existing)
- Java tools (JOPizer, jop2dat) - optional for Phase C

---

## Testing Strategy

### Progressive Validation (Proven Approach)

**Phase A Tests:**
- Unit: BytecodeFetchStage, JumpTable
- Integration: BytecodeFetch + FetchStage
- System: Simple bytecode sequences (iadd, isub, branches)

**Phase B Tests:**
- Unit: MethodCache, JbcRam
- Integration: Cache + BytecodeFetch + JbcRam
- System: Method loading, cache hit/miss patterns

**Phase C Tests:**
- Unit: MainMemorySim, JopFileLoader
- Integration: Full memory hierarchy
- System: Real JOP programs (HelloWorld.jop)

**Test Coverage Target:** 100% (all new components)

**Test Count Estimate:**
- Phase A: 15-20 tests
- Phase B: 10-15 tests
- Phase C: 8-12 tests
- **Total:** 40-50 new tests

---

## Success Criteria

### Phase A (Core Bytecode Fetch)
- [x] BytecodeFetchStage component implemented
- [x] JumpTable component implemented
- [x] Integration with FetchStage working
- [x] 15+ tests passing (100%)
- [x] Simple bytecode sequences execute correctly
- [x] Branch instructions functional
- [x] Code review: 95+ score

### Phase B (Method Cache)
- [x] MethodCache component implemented
- [x] JbcRam dual-port component implemented
- [x] Cache hit/miss detection working
- [x] 10+ tests passing (100%)
- [x] Method loading functional
- [x] Code review: 95+ score

### Phase C (Memory & JOP Files)
- [x] MainMemorySim component implemented
- [x] JopFileLoader utility implemented
- [x] .jop file loading working
- [x] 10+ tests passing (100%)
- [x] HelloWorld.jop executes successfully
- [x] Code review: 95+ score

### Overall
- [x] Total: 40+ tests passing (100%)
- [x] All phases integrated
- [x] Documentation complete
- [x] Production-ready bytecode fetch pipeline

---

## Resource Estimates

| Phase | Component | Effort | Complexity | Risk |
|-------|-----------|--------|------------|------|
| **A** | BytecodeFetchStage | 6 hours | High | Medium |
| **A** | JumpTable | 2 hours | Low | Low |
| **A** | Integration | 2 hours | Medium | Medium |
| **A** | Testing | 8 hours | Medium | Low |
| **A** | **Subtotal** | **2 days** | - | - |
| **B** | MethodCache | 6 hours | High | Medium |
| **B** | JbcRam | 2 hours | Medium | Low |
| **B** | Integration | 4 hours | Medium | Low |
| **B** | Testing | 4 hours | Medium | Low |
| **B** | **Subtotal** | **2 days** | - | - |
| **C** | MainMemorySim | 4 hours | Medium | Low |
| **C** | JopFileLoader | 2 hours | Low | Low |
| **C** | Testing | 8 hours | Medium | Medium |
| **C** | **Subtotal** | **2 days** | - | - |
| **D** | Advanced Features | 8-16 hours | High | Medium |
| **D** | **Subtotal** | **1-2 days** | - | - |
| | **Total (A+B+C)** | **6 days** | - | - |
| | **Total (A+B+C+D)** | **7-8 days** | - | - |

**Note:** Estimates assume no major blockers. Actual time may vary ±20%.

---

## Open Questions for User

### 1. Phased Approach Confirmation
**Question:** Do you approve the phased approach (A→B→C→D)?
**Alternative:** Would you prefer a different order or scope?

### 2. Jump Table Data Source
**Question:** For JumpTable, should we:
- **Option A:** Hardcode the 256 entries in Scala (simple, fast)
- **Option B:** Load from file (e.g., parse jtbl.vhd or create .dat file)
- **Option C:** Generate from microcode source (requires Jopa integration)

**Recommendation:** Option A for Phase A, migrate to B/C if needed

### 3. Initial Test Program
**Question:** What bytecode sequences should we test first?
- **Option A:** Hand-crafted simple sequences (iload, iadd, istore)
- **Option B:** Extract from HelloWorld.jop
- **Option C:** Write minimal Java test and JOPize it

**Recommendation:** Option A for Phase A, Option C for Phase C

### 4. Memory Simulation Scope
**Question:** For Phase C, should the memory simulation:
- **Option A:** Just load .jop files (read-only after init)
- **Option B:** Support runtime writes (heap allocation, etc.)

**Recommendation:** Option A sufficient for testing bytecode fetch

### 5. MMU Integration Timing
**Question:** Should we integrate with MMU (memory management unit) in:
- **Phase B:** During cache implementation
- **Phase D:** As advanced feature
- **Later:** Separate MMU implementation phase

**Recommendation:** Phase D or later - not critical for bytecode fetch testing

### 6. Interrupt/Exception Priority
**Question:** Interrupt and exception handling needed for:
- **Phase A:** Essential for basic testing
- **Phase D:** Advanced feature only
- **Never:** Not needed for current scope

**Recommendation:** Phase D - not required for bytecode fetch validation

---

## Risk Analysis

### Technical Risks

**Risk 1: Jump Table Complexity**
- **Probability:** Low
- **Impact:** Medium
- **Mitigation:** Start with hardcoded Scala map, validate against jtbl.vhd

**Risk 2: Dual-Port RAM Timing**
- **Probability:** Medium
- **Impact:** Medium
- **Mitigation:** SpinalHDL Mem supports this pattern, CocoTB tests will validate

**Risk 3: Branch Logic Bugs**
- **Probability:** Medium
- **Impact:** High
- **Mitigation:** Comprehensive branch tests, compare against VHDL behavior

**Risk 4: Cache State Machine**
- **Probability:** Medium
- **Impact:** Medium
- **Mitigation:** Progressive testing, unit tests before integration

**Risk 5: Integration with Existing Pipeline**
- **Probability:** Low
- **Impact:** High
- **Mitigation:** Existing FetchStage well-tested, clear interface

### Schedule Risks

**Risk 1: Scope Creep**
- **Probability:** Medium
- **Impact:** High
- **Mitigation:** Stick to phased plan, defer advanced features

**Risk 2: Test Environment Issues**
- **Probability:** Low
- **Impact:** Medium
- **Mitigation:** Existing CocoTB infrastructure proven

---

## Next Steps

### Immediate (After Plan Approval)

1. **User Review:** Review this plan, answer open questions
2. **Create TODO List:** Break down Phase A into tasks
3. **Setup Files:** Create initial file structure
4. **Extract Jump Table Data:** Parse jtbl.vhd or create Scala map

### Phase A Start

1. Implement BytecodeFetchStage (6 hours)
2. Implement JumpTable (2 hours)
3. Create test ROM patterns (2 hours)
4. Integration with FetchStage (2 hours)
5. Create test suite (8 hours)
6. Code review

### After Each Phase

1. User feedback
2. Documentation update
3. Git commit
4. Proceed to next phase or adjust plan

---

## References

### VHDL Reference Files
- `/home/peter/git/jop.arch/jop/vhdl/core/bcfetch.vhd` - Bytecode fetch (495 lines)
- `/home/peter/git/jop.arch/jop/vhdl/core/cache.vhd` - Method cache (215 lines)
- `/home/peter/git/jop.arch/jop/vhdl/memory/jbc_generic.vhd` - JBC RAM
- `/home/peter/git/jop.arch/jop/asm/generated/jtbl.vhd` - Jump table

### SpinalHDL Reference Files
- `core/spinalhdl/src/main/scala/jop/pipeline/FetchStage.scala` - Existing microcode fetch
- `core/spinalhdl/src/main/scala/jop/pipeline/DecodeStage.scala` - Existing decode
- `core/spinalhdl/src/main/scala/jop/pipeline/StackStage.scala` - Existing execute

### Documentation
- `docs/verification/INTEGRATION-TESTING-GUIDE.md` - Testing methodology
- `docs/verification/STACK-COVERAGE-SUMMARY.md` - Example coverage doc
- `NEXT-STEPS.md` - Project roadmap
- `README.md` - Architecture overview

### Exploration Reports (from planning)
- SpinalHDL Pipeline Patterns (agent d7e9b08d)
- VHDL Bytecode Fetch Implementation (agent bd0da8af)
- JOP File Format & Memory (agent 05aaa9f3)

---

## Conclusion

This plan provides a clear roadmap for implementing bytecode fetch, method cache, and memory interface using a proven phased approach. The strategy balances:

- **Progressive validation** - Test at each phase
- **Production readiness** - Match VHDL architecture
- **Manageable complexity** - Break into digestible chunks
- **Early feedback** - User can guide direction

**Recommended Action:** Approve plan, answer open questions, proceed with Phase A.

---

**Last Updated:** 2026-01-04
**Next Review:** After user feedback on this plan
**Status:** AWAITING USER APPROVAL

## Answers to Questions
The plan includes 6 open questions I'd like your input on:

1. Phased Approach: Do you approve the A→B→C→D progression?
- yes
2. Jump Table Data: Hardcode in Scala vs. load from file?
- We'll have to modify ```java/tools/src/com/jopdesign/tools/Jopa.java``` to either generate a data file or scala directly - which ever is better.  
3. Initial Tests: Hand-crafted bytecode sequences vs. extract from HelloWorld.jop?
- Hand-crafted bytecode sequences
4. Memory Scope: Read-only after init vs. runtime writes?
- Initially it could be read-only for some simple hand-crafted bytecode sequences - but we'll soon need to create Java Objects etc.
5. MMU Integration: Phase B, Phase D, or later?
- Phase D
6. Interrupts/Exceptions: Phase A essential, Phase D advanced, or never?
- Phase E


