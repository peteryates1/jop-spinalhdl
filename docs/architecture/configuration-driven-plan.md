# Configuration-Driven Constant Management Plan

This plan addresses all 16 dependency categories identified in
`constant-dependencies.md`, using three strategies:

1. **Generate** — extend ConstGenerator (or add new generators) so that
   downstream files are derived from SpinalHDL config, not manually maintained.
2. **Parameterize** — replace hardcoded values with config-derived parameters
   passed through constructors or generated files.
3. **Validate** — where generation is impractical, add compile-time or
   test-time cross-checks that fail loudly on mismatch.

## Strategy Summary

| § | Category | Risk | Strategy | Effort |
|---|----------|------|----------|--------|
| 1 | Bytecode opcodes | Critical | Generate Instruction.java from BytecodeConfig | Medium |
| 2 | GC handle struct | Critical | Validate — cross-check test | Low |
| 3 | Method cache sizing | High | Generate MAX_BC into Const.java; parameterize MethodCache | Low |
| 4 | I/O addresses | High | Generate microcode constants (jvm_const.inc) | Medium |
| 5 | Memory / heap | High | Generate heap limit into Const.java from memConfig | Low |
| 6 | Jopa assembler | High | Generate Jopa constants file from JopCoreConfig | Medium |
| 7 | JOPizer linker | High | Generate ClassStructConstants; validate METHOD_MAX_SIZE | Low |
| 8 | Device register maps | High | Generate Java HW constants from SpinalHDL register defs | High |
| 9 | Stack layout | Medium | Generate STACK_OFF into Const.java | Low |
| 10 | Clock / baud rate | Medium | Generate baud rate into download.py config | Low |
| 11 | SDRAM timing | Medium | Validate — board→device binding already exists | Low |
| 12 | Memory style | Medium | Parameterize .mif paths from config | Low |
| 13 | Interrupt config | Medium | Already generated; add exception number cross-check | Low |
| 14 | JopSim simulator | Medium | Generate JopSimConfig.java from JopCoreConfig | Medium |
| 15 | Microcode widths | Low | Already validated (require) | None |
| 16 | Runtime feature flags | Low | Already generated | None |

---

## Phase 1: Expand ConstGenerator (Low Effort, High Impact)

Extend the existing `ConstGenerator` to emit constants that are currently
hardcoded independently in Java. These are all simple value emissions — no
new infrastructure needed.

### 1a. Method cache sizing (§3)

Add to `Const.java` generation:

```java
public static final int MAX_BC = <2^methodSizeBits>;       // from JopCoreConfig
public static final int MAX_BC_MASK = MAX_BC - 1;
public static final int METHOD_CACHE_WORDS = <2^(jpcWidth-2)>; // effective cache
```

Then update `Cache.java` to import from `Const` instead of defining its own.

**Source of truth**: `JopCoreConfig.jpcWidth` and `JopConstants.METHOD_SIZE_BITS`

### 1b. Stack layout (§9)

Add to `Const.java` generation:

```java
public static final int STACK_OFF = <scratchSize>;          // from StackConfig
public static final int STACK_SIZE = <2^ramWidth>;
```

Currently `STACK_OFF=64` is hardcoded in both `Const.java` and
`StackStage.scala`. Making `Const.java` derive from config means only
`StackStage` owns the value.

**Source of truth**: `StackStage.scratchSize` (currently 64)

### 1c. Heap safety (§5)

Add to `Const.java` generation:

```java
public static final int MEM_END_WORDS = <usableMemWords>;   // from JopMemoryConfig
public static final int STACK_REGION_WORDS = <stackRegionWordsPerCore>;
```

Then update `Startup.java` to use `Const.MEM_END_WORDS` as the heap ceiling
instead of the fixed `appEnd + 262144` fallback. The `IO_MEM_SIZE` hardware
register already provides this at runtime, but having it in Const.java too
enables compile-time reasoning.

**Source of truth**: `JopMemoryConfig.usableMemWords`

### 1d. Clock / baud rate (§10)

Add to `Const.java` generation:

```java
public static final int CLK_FREQ = <clkFreq>;
public static final int UART_BAUD_RATE = <uartBaudRate>;
```

Then generate a `fpga/scripts/config.json` or update `download.py` to read
baud rate from `Const.java` or a shared config file. Simplest approach:
ConstGenerator also writes a one-line `baud_rate.txt` that `download.py` reads.

**Source of truth**: `JopCoreConfig.clkFreq` and `uartBaudRate`

### Phase 1 Validation

Add an sbt test that:
1. Runs `ConstGenerator.generate()` for each preset
2. Compiles the generated `Const.java`
3. Verifies `MAX_BC`, `STACK_OFF`, `MEM_END_WORDS` match expected values

---

## Phase 2: Bytecode Opcode Unification (§1, Critical)

The most dangerous dependency. Three independent sources define the same
opcode numbers.

### Approach: Generate Instruction.java opcode table

Add `InstructionGenerator` that reads `BytecodeConfig` and emits the
`ia[]` array in `Instruction.java`. The existing `Instruction.java` has
~200 lines of `ia[0x??] = "name"` assignments — these would be generated
from `BytecodeConfig.entries`.

**Steps**:
1. Add `InstructionGenerator.scala` that reads `BytecodeConfig.defaultEntries`
   and custom instructions (dspmul, lddsp, lddsph)
2. Generate the `ia[]` initialization block and instruction class assignments
3. Add `sbt "runMain jop.generate.InstructionGeneratorMain --write"` target
4. Add to Makefile's microcode build step

**Microcode (jvm.asm)**: Handler labels in jvm.asm cannot easily be generated
(they contain the actual microcode logic). Instead, add a cross-validation
test that parses jvm.asm labels and checks they match BytecodeConfig entries.

### Validation test

```scala
test("BytecodeConfig opcodes match jvm.asm labels") {
  val asmLabels = parseJvmAsmLabels("asm/src/jvm.asm")
  val configEntries = BytecodeConfig.defaultEntries
  for ((opcode, entry) <- configEntries) {
    assert(asmLabels.contains(entry.name),
      s"Opcode 0x${opcode.toHexString}: ${entry.name} missing from jvm.asm")
  }
}
```

---

## Phase 3: Java Toolchain Constants (§6, §7, §14)

### 3a. Jopa assembler constants (§6)

Generate `JopaConfig.java` (or a properties file) from `JopCoreConfig`:

```java
// Generated from JopCoreConfig
public static final int ADDRBITS = <pcWidth>;       // 12
public static final int INSTLEN = <instrWidth>;     // 10
public static final int RAM_LEN = <2^ramWidth>;     // 256
public static final int ROM_LEN = <2^pcWidth>;      // 4096
```

Then update `Jopa.java` to import these instead of defining its own.

**Source of truth**: `JopCoreConfig.pcWidth`, `instrWidth`, `ramWidth`

### 3b. JOPizer class structure (§7)

`ClassStructConstants.java` defines architectural constants (`CLS_HEAD=5`,
`METH_STR=2`, etc.) that match the GC and pipeline. These are true
architectural constants unlikely to change, but should be cross-validated.

Add a test that verifies `ClassStructConstants` values match any corresponding
SpinalHDL constants (currently there is no SpinalHDL definition of CLS_HEAD,
so this is primarily documenting the dependency).

For `METHOD_MAX_SIZE`: generate or validate against `2^methodSizeBits * 4`
(words to bytes).

### 3c. JopSim / JOPConfig (§14)

Generate `JopSimConfig.java` from `JopCoreConfig`:

```java
// Generated from JopCoreConfig
public static final int CACHE_BLOCKS = <2^blockBits>;        // 16
public static final int CACHE_SIZE_WORDS = <2^(jpcWidth-2)>; // 512
public static final int MAX_MEM = <mainMemSize / 4>;
```

Then update `JopSim.java` and `JOPConfig.java` to import from the generated
file.

---

## Phase 4: Microcode I/O Constants (§4)

Microcode uses `bipush` with hardcoded values for SYS_BASE and UART_BASE.
These can't be generated as Java constants, but can be generated as an
assembler include file.

### Approach: Generate jvm_const.inc

Add to ConstGenerator (or a new `MicrocodeConstGenerator`):

```
; Generated from JopIoSpace — do not edit
SYS_BASE = 0xF0        ; = JopIoSpace.SYS_BASE - 0x80, sign-extended
UART_BASE = 0xEE       ; = JopIoSpace.UART_BASE - 0x80
IO_CNT_OFF = 0x70      ; = SYS_BASE offset for timer
IO_WD_OFF = 0x73       ; = SYS_BASE offset for watchdog
```

Then update `jvm.asm` to `#include "jvm_const.inc"` and replace hardcoded
`bipush` values with symbolic names.

**Prerequisite**: Verify Jopa supports `#include` or `.include` directives.
If not, add a simple preprocessor step to the Makefile, or use `#define`
if Jopa's C preprocessor pass supports it.

**Alternative** (simpler): Add a cross-validation test that parses the
`bipush` values in `jvm.asm` and checks they match `JopIoSpace` constants.
Less elegant but zero microcode changes.

---

## Phase 5: Device Register Maps (§8)

This is the highest-effort item. Each I/O device has register bit definitions
in SpinalHDL and corresponding Java driver constants.

### Approach: SpinalHDL register metadata + generator

Extend `DeviceType` with a `registerBits` method that returns bit-field
definitions:

```scala
case object Uart extends DeviceType {
  // ... existing fields ...
  override def registerBits = Map(
    "STATUS" -> Seq(
      BitField("TDRE", 0, "TX data register empty"),
      BitField("RDRF", 1, "RX data register full")
    )
  )
}
```

Then add `HwConstGenerator` that emits Java constants for each device:

```java
// Generated from DeviceType.Uart
public class UartHw {
  public static final int MSK_TDRE = 1 << 0;
  public static final int MSK_RDRF = 1 << 1;
}
```

**Scope**: UART (2 bits), Ethernet (6 bits), SD Native (11 bits),
VGA Text (~5 registers), VGA DMA (~3 registers). Total ~30 bit definitions.

### Alternative (lower effort)

Add a cross-validation test that reads the SpinalHDL register definitions
and the Java driver constants, verifying they match. No code generation,
but catches drift.

---

## Phase 6: GC Handle Structure (§2)

### Approach: Cross-validation test only

The GC handle structure (`HANDLE_SIZE=8`, `OFF_PTR=0`, etc.) is an
architectural constant that hasn't changed since original JOP. Generation
is overkill — a test is sufficient.

```scala
test("GC handle constants match ClassStructConstants") {
  // Parse GC.java for HANDLE_SIZE, OFF_* constants
  // Parse ClassStructConstants.java for related values
  // Assert consistency
}
```

---

## Phase 7: SDRAM and Memory Style (§11, §12)

### 7a. SDRAM timing (§11)

Already addressed by the board→device binding system. The `Board` definition
ties a board to specific `MemoryDevice` entries in `Parts.scala`. No
additional generation needed — just ensure presets reference the correct
board.

**Validation**: Add a test that every JopConfig preset's memory device
matches its board's memory device list.

### 7b. Memory style / .mif paths (§12)

Replace hardcoded `.mif` paths with config-derived paths:

```scala
case class MemoryStyle(
  // ... existing ...
  mifBasePath: String = "../../asm/generated/serial"  // configurable
)
```

Then pass this from `JopCoreConfig` through to `MemoryStyle` ROM/RAM
factories.

---

## Implementation Priority

```
Phase 1  ──────────────────────────────  1-2 days
  ConstGenerator: MAX_BC, STACK_OFF, MEM_END_WORDS, CLK_FREQ, BAUD_RATE
  Update Cache.java, Startup.java to use generated values
  Validation test

Phase 2  ──────────────────────────────  1-2 days
  InstructionGenerator from BytecodeConfig
  jvm.asm cross-validation test

Phase 3  ──────────────────────────────  1-2 days
  JopaConfig generation (ADDRBITS, RAM_LEN, etc.)
  JopSimConfig generation
  METHOD_MAX_SIZE validation

Phase 4  ──────────────────────────────  1 day
  jvm_const.inc generation or cross-validation test
  Makefile integration

Phase 5  ──────────────────────────────  2-3 days
  DeviceType.registerBits metadata
  HwConstGenerator for Java driver constants
  Or: cross-validation test (1 day)

Phase 6  ──────────────────────────────  0.5 day
  GC handle cross-validation test

Phase 7  ──────────────────────────────  0.5 day
  .mif path parameterization
  Board↔device validation test
```

## Build Integration

All generators should run as part of the standard build:

```makefile
# In asm/Makefile or top-level Makefile
generate-config:
	sbt "runMain jop.generate.ConstGeneratorMain $(PRESET) --write"
	sbt "runMain jop.generate.InstructionGeneratorMain --write"
	sbt "runMain jop.generate.JopaConfigGeneratorMain --write"

# Existing microcode build depends on generated config
all: generate-config assemble
```

The `sbt test` suite should include all cross-validation tests so that
mismatches are caught before synthesis or deployment.

## Dependency Flow After Implementation

```
JopConfig (single source of truth)
  │
  ├─→ ConstGenerator ──→ Const.java (I/O, features, MAX_BC, STACK_OFF, ...)
  ├─→ InstructionGenerator ──→ Instruction.java (opcode table)
  ├─→ JopaConfigGenerator ──→ JopaConfig.java (ADDRBITS, RAM_LEN, ...)
  ├─→ JopSimConfigGenerator ──→ JopSimConfig.java (cache, timing, memory)
  ├─→ HwConstGenerator ──→ UartHw.java, EthHw.java, ... (register bits)
  ├─→ MicrocodeConstGenerator ──→ jvm_const.inc (I/O addresses)
  │
  └─→ Cross-validation tests
        ├─→ BytecodeConfig ↔ jvm.asm labels
        ├─→ GC.java ↔ ClassStructConstants.java
        ├─→ Board ↔ MemoryDevice binding
        └─→ METHOD_MAX_SIZE ↔ cache size
```

All `(NO LINK)` paths in the dependency graph become either generated or
validated. No silent drift possible.
