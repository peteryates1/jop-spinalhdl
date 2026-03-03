# Configuration-Driven JOP System

## Problem

JOP has a combinatorial explosion of microcode variants, boilerplate-heavy Verilog generation, and fragile manual configuration wiring.

**Current state:**
- 12 Makefile targets in `asm/Makefile` (3 boot modes × 4 math combos, manually maintained)
- 11 generated JumpTableData Scala objects (one per variant, created via `sed` rename)
- 11 factory methods in `JumpTableInitData` (one per variant)
- 20 Verilog generation entry points across 5 files (70%+ duplicated boilerplate)
- 31 simulation harnesses with similar duplication
- Manual wiring: `jumpTable = JumpTableInitData.serialHwMath, useDspMul = true, useHwDiv = true` — must be kept in sync or the wrong microcode executes

**Growth problem:** Adding long ALU + double FPU would create 3 boot × 8 math × 4 float × 4 double = 384 combinations with the current approach.

**Root cause:** Configuration is scattered — boot mode, jump table selection, HW peripheral flags, pipeline params, and ROM/RAM paths are all specified independently at each call site.

## Design Principle

**Configuration drives everything.** `JopSystemConfig` is the single source of truth. Every downstream artifact is derived from it — no manual synchronization between layers.

```
JopSystemConfig
  |
  +--→ Microcode assembly
  |      gcc -D flags derived from per-core Implementation
  |      Boot mode → SERIAL/FLASH/SIMULATION preprocessor define
  |      Output: superset ROM + RAM per boot mode
  |
  +--→ Java runtime generation
  |      Const.java: I/O addresses (FPU=0xF0, DIV=0xE0) from IoConfig
  |      Const.java: SUPPORT_FLOAT/SUPPORT_DOUBLE flags from core config
  |      JVM.java: f_fadd() → SoftFloat32 vs HW I/O stub (per Implementation)
  |      HW device drivers: only included when carrier board has the device
  |      Output: runtime .class files tailored to this system
  |
  +--→ SpinalHDL elaboration
  |      Per-core: jump table patching, peripheral instantiation, pipeline params
  |      System: arbiter type, memory controller, I/O wiring, debug
  |      Board: top-level ports, PLL config
  |      Output: Verilog for this specific system
  |
  +--→ FPGA build
  |      .qsf/.xdc from SystemAssembly pin maps
  |      Synthesis tool (Quartus/Vivado) from FpgaFamily
  |      Output: bitstream
  |
  +--→ Application build (JOPizer)
         Memory layout from JopMemoryConfig
         Available APIs from runtime
         Output: .jop file
```

From the config, the system derives:
- Which jump table entries route to Java / microcode / HW handler
- Which HW peripherals get instantiated (BmbFpu, BmbDiv, future long ALU, double FPU)
- Which pipeline parameters change (Mul useDsp)
- Which ROM/RAM files to load (based on boot mode)
- Which Java runtime classes are needed (SoftFloat32, SoftFloat64, HW device drivers)
- Which I/O addresses are active (generated into Const.java)
- Which carrier board peripherals have drivers available

### Three-way per-bytecode choice

Every configurable bytecode uses the same uniform model — three implementation options:

| Option | Jump Table Entry | Meaning |
|--------|-----------------|---------|
| **Java** | sys_noim | Java runtime handles it (SoftFloat, etc.) |
| **Microcode** | microcode handler addr | Pure microcode implementation, no HW peripheral |
| **Hardware** | HW microcode handler addr | Microcode that uses an HW I/O peripheral |

Example: `fadd: Hardware, fdiv: Java` → FPU is instantiated (because fadd needs it), jump table routes fadd→HW handler but fdiv→sys_noim. The fdiv bytecode traps to Java's SoftFloat32.

Not all three options exist for every bytecode today. The framework supports all three; validation ensures the selected option has a corresponding handler in ROM.

### Configurable Bytecodes (~48 total)

**Integer multiply** (1) — `imul`
- **Microcode** → bit-serial Mul unit (`stmul`/`ldmul`, 18 cycles, ~244 LCs)
- **Hardware** → DSP-inferred Mul unit (`stmul`/`ldmul`, 4 cycles, ~4 DSP18x18)
- **Java** → sys_noim → JVM.f_imul()
- Note: the Mul unit is a pipeline component with dedicated `stmul`/`ldmul` instructions rather than a generic I/O peripheral, but the config model is the same as everything else.

**Long arithmetic** (10) — `ladd` `lsub` `lneg` `lshl` `lshr` `lushr` `land` `lor` `lxor` `lcmp`
- Today: **Microcode** (existing handlers in base ROM)
- Future: **Hardware** (long ALU peripheral)
- Always available: **Java** (sys_noim)

**Long multiply** (1) — `lmul`
- Today: **Java** or **Hardware** (DSP microcode using Mul unit `doutH` for upper 32 bits)

**Integer/long divide** (4) — `idiv` `irem` `ldiv` `lrem`
- Today: **Java** or **Hardware** (BmbDiv for idiv/irem; ldiv/lrem Java only, future HW)

**Float arithmetic** (8) — `fadd` `fsub` `fmul` `fdiv` `fneg` `frem` `fcmpl` `fcmpg`
- Today: **Java** or **Hardware** (BmbFpu for fadd/fsub/fmul/fdiv; rest Java only, future HW)

**Double arithmetic** (8) — `dadd` `dsub` `dmul` `ddiv` `dneg` `drem` `dcmpl` `dcmpg`
- Today: **Java** only
- Future: **Hardware** (double FPU peripheral)

**Type conversions** (12) — `i2f` `i2d` `f2i` `f2l` `f2d` `d2i` `d2l` `d2f` `l2f` `l2d` `i2b` `i2s`
- Today: **Java** (most), **Microcode** (i2l, l2i, i2c already exist)
- Future: **Hardware** (FPU/double FPU can handle some conversions)

**Constants** (3) — `fconst_1` `fconst_2` `dconst_1`
- Today: **Java**; trivially implementable as **Microcode**

**Derived peripheral instantiation:**
- `needsFpu` = any of fadd/fsub/fmul/fdiv/fneg/frem/fcmpl/fcmpg is Hardware → instantiate BmbFpu
- `needsHwDiv` = any of idiv/irem is Hardware → instantiate BmbDiv
- `needsDspMul` = imul is Hardware OR lmul is Hardware → `Mul(useDsp=true)`
- `needsLongAlu` = any of ladd/.../lcmp is Hardware → instantiate BmbLongAlu (future)
- `needsDoubleFpu` = any of dadd/.../dcmpg is Hardware → instantiate BmbDoubleFpu (future)

## Key Insight: Superset ROM + Jump Table Patching

Optional HW handlers (FPU, DSP lmul, HW div) are **appended at the end** of the microcode ROM without shifting any base bytecode addresses. Comparing `JumpTableData` (base) vs `FpuJumpTableData` (FPU), 252 of 256 entries are identical. Only the configurable entries differ.

**Solution:** Build ONE superset ROM per boot mode (all features enabled: `-DFPU_ATTACHED -DDSP_MUL -DHW_DIV`). The superset ROM contains ALL microcode and HW handlers. At SpinalHDL elaboration, construct the jump table per-bytecode:
- **Java** → patch entry to `sys_noim`
- **Microcode** → use the microcode handler address from the superset ROM
- **Hardware** → use the HW handler address from the superset ROM

Result: **12 Makefile targets → 3.** Future features add `#ifdef` blocks to jvm.asm and `-D` flags — no new Makefile targets needed. The superset ROM grows but never splits.

Note: With the unified compute unit, `imul: Hardware` (DSP) uses the same `sthw` pattern as all other HW bytecodes — the Mul sub-unit inside ComputeUnit handles the dispatch. `imul: Microcode` (bit-serial) retains its own microcode handler with the explicit wait loop, since the bit-serial Mul unit doesn't use the busy stall mechanism.

### ROM Size Budget

Current base ROM: ~700-900 instructions (includes long microcode handlers). FPU handlers: ~50. DSP lmul: ~60. HW div: ~30. **Total superset: ~1040 of 2048 slots (51%).** Future long ALU HW handlers (~200) + double FPU (~100) + expanded float conversions (~50) would reach ~1390 (68%). Plenty of headroom.

With the unified compute unit (see below), HW handler microcode shrinks dramatically — all HW bytecodes share the same ~4 instruction pattern instead of 9-10 instructions each. ROM budget improves further.

## Unified Compute Unit — `sthw` (start hardware)

### Problem with current I/O-based peripherals

Today, FPU and DIV are BMB I/O peripherals accessed via generic memory-mapped I/O. The Mul unit is a pipeline component with dedicated `stmul`/`ldmul` instructions. This creates two problems:

1. **I/O overhead**: fadd microcode is 9 instructions (load I/O address, set write address, do I/O write, pop, load read address, start I/O read, wait, wait, read result). imul DSP microcode is 4 instructions. The 5 extra instructions are pure plumbing.

2. **Inconsistency**: Mul is a pipeline component, FPU/DIV are I/O peripherals. Same concept (hardware-accelerated bytecode), different mechanisms.

Current microcode comparison:

```asm
// imul (DSP) — 4 instructions, pipeline Mul unit
imul:
    stmul           // capture TOS+NOS, start multiply
    pop             // pop second operand
    nop             // wait 1 cycle for registered result
    ldmul nxt       // read result

// fadd — 9 instructions, BMB I/O peripheral
fadd:
    ldi fpu_add     // push I/O address
    stmwa           // set address register
    stmwd           // I/O write (auto-capture + start)
    pop             // drop value1
    ldi fpu_res     // push result read address
    stmra           // start I/O read
    wait            // I/O read latency
    wait
    ldmrd nxt       // read result
```

### Solution: unified compute dispatch

Replace `stmul`/`ldmul` and the I/O-based FPU/DIV with two generic microcode instructions:

- **`sthw`** (start compute) — captures TOS and NOS (and C, D with future 4-register TOS), dispatches to the appropriate compute unit based on the **bytecode** that triggered this handler. The bytecode is already available in a pipeline register. The selected compute unit asserts busy until the result is ready.

The compute unit writes results directly back into the stack registers (TOS, and NOS for 64-bit results) — no explicit load instruction needed. The pipeline stalls via busy until the result is written back, then `wait nxt` completes the bytecode.

**Instruction naming**: `sthw` (start hardware). Follows JOP's `st` prefix convention (`stmul`, `stmwa`). No `ldhw` needed — result writeback is implicit.

### All HW bytecodes become identical

```asm
// Every 32-bit→32-bit HW bytecode (imul, fadd, fsub, fmul, fdiv, idiv, irem):
<bytecode>:
    sthw            // capture TOS+NOS, bytecode selects unit + operation
    pop             // remove second operand
    wait            // stall while computing
    wait nxt        // result written to TOS (overwrites first operand)
```

4 instructions. One microcode handler shared by ALL hardware-accelerated 32-bit bytecodes. The bytecode determines what happens — no I/O addresses, no operation encoding in microcode.

For idiv/irem, the div-by-zero check still happens before `sthw`:

```asm
idiv:
    dup             // copy divisor
    bnz idiv_ok     // check non-zero
    nop nop         // delay slots
    jmp sys_noim    // zero → Java ArithmeticException
    nop nop
idiv_ok:
    sthw            // capture + dispatch (bytecode 0x6C → div unit)
    pop
    wait
    wait nxt        // quotient written to TOS
```

### ALU vs Compute Module — the 1-cycle rule

**Principle:** Operations that complete in 1 cycle go in the **ALU/Stack pipeline**. Operations that take 2+ cycles go in the **Compute Module** with `sthw`/`wait` microcode.

The ALU is combinational — result available same cycle, no stall. The Compute Module is registered — `sthw` captures operands, `busy` stalls the pipeline, result writes back when done.

**1-cycle ALU/Stack (no microcode, no stall):**

| Category | Operations | Notes |
|----------|-----------|-------|
| Integer (existing) | iadd, isub, iand, ior, ixor, ineg, ishl, ishr, iushr | Already in 32-bit pipeline |
| Long bitwise | land, lor, lxor, lneg | 64-bit bitwise — trivial with 64-bit datapath |
| Long arithmetic | ladd, lsub | 64-bit adder (currently 26-38 microcode cycles!) |
| Long compare | lcmp | 64-bit comparator → {-1, 0, 1} (currently 80 cycles!) |
| Long shift | lshl, lshr, lushr | 64-bit barrel shifter (optional — LUT-expensive) |
| Float simple | fneg | Sign bit flip |
| Float compare | fcmpl, fcmpg | Exponent/mantissa comparison → {-1, 0, 1} |

**2+ cycle Compute Module (sthw/wait pattern):**

| Category | Operations | Cycles | Sub-unit |
|----------|-----------|--------|----------|
| Integer multiply | imul (bit-serial) | ~18 | Mul (radix-4) |
| Integer multiply | imul (DSP) | 1 (registered) | Mul (DSP inferred) |
| Integer divide | idiv, irem | ~34 | DivUnit |
| Long multiply | lmul | varies | Mul (DSP cascade) |
| Long divide | ldiv, lrem | ~66 | DivUnit (64-bit) |
| Float arithmetic | fadd, fsub, fmul, fdiv, frem | varies | FpuCore |
| Float conversion | i2f, f2i, f2l, l2f | varies | FpuCore |
| Double arithmetic | dadd, dsub, dmul, ddiv, drem | varies | DoubleFpuCore |
| Double conversion | i2d, d2i, d2f, f2d, l2d, d2l | varies | DoubleFpuCore |

Note: DSP imul is 1 registered cycle but uses DSP blocks, not ALU LUTs. It lives in the Compute Module alongside bit-serial imul — the `sthw`/`wait` pattern handles both uniformly (DSP just finishes in 1 cycle so `wait` doesn't actually stall).

### Hardware compute dispatch unit

Lives in the pipeline (like Mul today). Contains all optional compute sub-units, selected by configuration. Interface uses 2×64-bit operands — ready for long and double operations.

```scala
case class ComputeUnit(config: JopCoreConfig) extends Component {
  val io = new Bundle {
    // 2×64-bit operands (from 4 stack registers: A, B, C, D)
    //   32-bit ops: operand0 = A (lower 32 used), operand1 = B (lower 32 used)
    //   64-bit ops: operand0 = {B, A} (hi:lo),     operand1 = {D, C} (hi:lo)
    val operand0 = in UInt(64 bits)   // value2 (top of stack)
    val operand1 = in UInt(64 bits)   // value1 (below value2)
    val wr       = in Bool()          // sthw asserted — capture operands, start
    val opcode   = in Bits(8 bits)    // bytecode selects operation + sub-unit
    val result   = out UInt(64 bits)  // result (32-bit ops use lower half)
    val is64     = out Bool()         // true → write both TOS and NOS
    val busy     = out Bool()         // stalls pipeline until done
  }

  // --- Mul sub-unit (always present, bit-serial or DSP) ---
  // Accepts 32-bit operands from operand0/1 lower halves (imul)
  // or 64-bit operands for lmul (DSP cascade)
  val mul = Mul(useDsp = config.needsMul)

  // --- FPU sub-unit (conditional) ---
  // Accepts 32-bit IEEE 754 from operand0/1 lower halves
  val fpu = config.needsFpu generate FpuCore()

  // --- DIV sub-unit (conditional) ---
  // Accepts 32-bit (idiv/irem) or 64-bit (ldiv/lrem) operands
  val div = config.needsHwDiv generate DivUnit()

  // --- Future: DoubleFpuCore (64-bit IEEE 754) ---

  // Dispatch: bytecode → sub-unit
  switch(io.opcode) {
    // Integer
    is(0x68) { /* imul → mul, 32-bit */ }
    // Long
    is(0x69) { /* lmul → mul, 64-bit */ }
    is(0x6D) { /* ldiv → div, 64-bit, mode=QUOT */ }
    is(0x71) { /* lrem → div, 64-bit, mode=REM */ }
    // Float
    is(0x62) { /* fadd → fpu, op=ADD */ }
    is(0x66) { /* fsub → fpu, op=SUB */ }
    is(0x6A) { /* fmul → fpu, op=MUL */ }
    is(0x6E) { /* fdiv → fpu, op=DIV */ }
    is(0x72) { /* frem → fpu, op=REM */ }
    // Integer divide
    is(0x6C) { /* idiv → div, 32-bit, mode=QUOT */ }
    is(0x70) { /* irem → div, 32-bit, mode=REM */ }
    // Double (future)
    is(0x63) { /* dadd → doubleFpu, op=ADD */ }
    is(0x67) { /* dsub → doubleFpu, op=SUB */ }
    is(0x6B) { /* dmul → doubleFpu, op=MUL */ }
    is(0x6F) { /* ddiv → doubleFpu, op=DIV */ }
    is(0x73) { /* drem → doubleFpu, op=REM */ }
    // Type conversions (future)
    is(0x86) { /* i2f → fpu, op=I2F */ }
    is(0x8B) { /* f2i → fpu, op=F2I */ }
    // ...
  }

  // Busy = OR of all active sub-units
  io.busy := mul.io.busy || fpu.map(_.io.busy).getOrElse(False) || ...

  // Result MUX based on which sub-unit was started
  // (latched active unit on sthw, writeback to TOS/NOS when done)
}
```

### Operand mapping

With 4-register TOS (A=TOS, B=NOS, C=TOS-2, D=TOS-3):

```
JVM stack:    ..., value1_hi(D), value1_lo(C), value2_hi(B), value2_lo(A)

ComputeUnit:  operand0 = {B, A} = value2 (64-bit, top of stack)
              operand1 = {D, C} = value1 (64-bit, below)

32-bit ops:   operand0(31:0) = A = TOS
              operand1(31:0) = B = NOS
              upper 32 bits unused
```

This is the same mapping for all operations — the compute unit's operand ports are always wired the same way. The bytecode tells the sub-unit whether to use 32 or 64 bits of each operand.

### What this eliminates

- **BmbFpu** I/O peripheral → replaced by FPU sub-unit in ComputeUnit
- **BmbDiv** I/O peripheral → replaced by DIV sub-unit in ComputeUnit
- **I/O address space**: 0xE0-0xE3 (DIV) and 0xF0-0xF3 (FPU) freed up
- **`stmul`/`ldmul`** microcode instructions → replaced by `sthw` + implicit writeback
- **Per-bytecode microcode handlers**: fadd/fsub/fmul/fdiv/idiv/irem each had ~9-10 unique instructions → all share one ~4 instruction pattern
- **I/O wiring in JopCore**: no more `fpuBusy`, `divBusy` I/O bus plumbing

### What stays the same

- **FpuCore** (VexRiscv-derived IEEE 754) — the actual compute logic is unchanged, just wired differently
- **DivUnit** (binary restoring) — same algorithm, just no BMB wrapper
- **Pipeline stall mechanism** — busy signal still stalls the pipeline, just comes from ComputeUnit instead of I/O bus

### 64-bit operations (sthw/wait with 4-register TOS)

With 4-register TOS (A/B/C/D), `sthw` captures all 4 registers. The compute unit sees 2×64-bit operands:

```
operand0 = {B, A}  →  value2 (64-bit, B=high, A=low)
operand1 = {D, C}  →  value1 (64-bit, D=high, C=low)
```

The hardware writes the 64-bit result directly back into TOS and NOS. No explicit load instruction needed — the `wait nxt` completes when the result is written back.

```asm
// Every 64-bit→64-bit HW bytecode (lmul, ldiv, lrem, dadd, dsub, dmul, ddiv, drem):
<bytecode>:
    sthw            // capture {B,A} + {D,C}, bytecode selects unit
    pop             // remove value2 low (A)
    pop             // remove value2 high (B)
    wait            // stall pipeline while computing
    wait nxt        // result written to TOS(=result_low) and NOS(=result_high)
```

Stack evolution:
```
Before:   ..., v1_hi(D), v1_lo(C), v2_hi(B), v2_lo(A)   [4 items]
sthw:     ..., v1_hi,    v1_lo,    v2_hi,    v2_lo       captures all 4, no pop
pop:      ..., v1_hi,    v1_lo,    v2_hi                  remove A
pop:      ..., v1_hi,    v1_lo                             remove B
wait:     ..., v1_hi,    v1_lo                             stall, computing...
wait nxt: ..., res_hi,   res_lo                            HW overwrites TOS+NOS
```

Net stack effect: 4 → 2 = -2. Correct for all 64-bit→64-bit bytecodes.

32-bit→32-bit bytecodes use the same interface (upper 32 bits unused):

```asm
// Every 32-bit→32-bit HW bytecode (imul, fadd, fsub, fmul, fdiv, idiv, irem):
<bytecode>:
    sthw            // capture A+B, bytecode selects unit
    pop             // remove second operand
    wait            // stall pipeline while computing
    wait nxt        // result written to TOS (overwrites first operand)
```

Net stack effect: 2 → 1 = -1. Correct for imul, fadd, idiv, etc.

### 1-cycle ALU path (long bitwise/arithmetic)

For long operations that are 1-cycle with a 64-bit ALU (ladd, lsub, land, lor, lxor, lneg, lcmp), the pipeline handles them directly — no `sthw`, no Compute Module, no microcode at all. The 4-register TOS provides both 64-bit operands to the ALU combinationally:

```
Pipeline ALU input:   {D, C} op {B, A}
Pipeline ALU output:  result_hi → NOS, result_lo → TOS
Stack management:     pop 2 (same as 32-bit binary ops pop 1)
```

These bytecodes execute like `iadd` does today — the ALU result is available in the same cycle, the pipeline pops the consumed operands, and `nxt` fetches the next bytecode. Zero microcode overhead.

Current microcode cost of these operations (all eliminated by 64-bit ALU):
- ladd: 26 cycles (half-add algorithm to avoid 32-bit overflow)
- lsub: 38 cycles (negate + half-add)
- lneg: 34 cycles (negate + fall-through to ladd)
- land/lor/lxor: 8 cycles each (save/restore 4 regs, apply op to each half)
- lcmp: 80 cycles (sign overflow detection + conditional subtraction + three-way branch)
- lshl/lshr/lushr: 28 cycles each (conditional branch on shift count, cross-carry)

With a 64-bit ALU, all of these become 1 cycle. The long shift operations (lshl/lshr/lushr) require a 64-bit barrel shifter which is LUT-expensive; these could alternatively remain in the Compute Module if area is constrained.

This eliminates `ldhw` entirely — the compute unit writes results directly back into the stack registers via the same writeback path. Only `sthw` is a new microcode instruction.

---

## Phase 1: Jopa Assembler Cleanup

**File:** `java/tools/src/com/jopdesign/tools/Jopa.java`

1. **Remove 2 unused outputs:** `rom.vhd`, `jtbl.vhd` — legacy VHDL formats. **Keep** `rom.mif`, `ram.mif` — useful for reference and debug
2. **Add `-n <ObjectName>` flag:** Controls the generated Scala object name. Eliminates the fragile `sed` rename hack.
3. **Generate `extends JumpTableSource`:** Each generated object implements a common trait (see Phase 3)
4. **Keep:** `mem_rom.dat`, `mem_ram.dat`, `rom.mif`, `ram.mif`, `<ObjectName>.scala`

## Phase 2: Superset Microcode Build

**File:** `asm/Makefile`

Replace 12 variant targets with 3 superset builds:

```makefile
# Build superset ROMs — one per boot mode, ALL feature handlers included
simulation: ../java/tools/dist/jopa.jar
    mkdir -p generated/simulation
    gcc -E -C -P -DSIMULATION -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n SimulationJumpTable -s generated -d generated/simulation jvm.asm

serial: ../java/tools/dist/jopa.jar
    mkdir -p generated/serial
    gcc -E -C -P -DSERIAL -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n SerialJumpTable -s generated -d generated/serial jvm.asm

flash: ../java/tools/dist/jopa.jar
    mkdir -p generated/flash
    gcc -E -C -P -DFLASH -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n FlashJumpTable -s generated -d generated/flash jvm.asm

all: simulation serial
```

**Flash variants** (flash-au with different flash addr/clk params): The flash params (`FLASH_ADDR_B2`, `FLASH_CLK_DIV`, `SKIP_FLASH_RESET`) only affect the boot loop, not the feature handlers. Single `flash` target with default params; flash-au uses same ROM with different boot params if needed, or becomes a second flash target.

**Backward compat:** Old target names (`serial-fpu`, `serial-hwmath`, etc.) become aliases: `serial-fpu: serial`.

**Deleted:** All variant subdirs (`asm/generated/fpu/`, `serial-fpu/`, `dsp/`, `serial-dsp/`, `div/`, `serial-div/`, `hwmath/`, `serial-hwmath/`).

## Phase 3: Jump Table Patching in Scala

**File:** `spinalhdl/src/main/scala/jop/pipeline/JumpTableSource.scala` (new)

```scala
package jop

/** Trait implemented by all Jopa-generated jump table objects */
trait JumpTableSource {
  def entries: Seq[BigInt]
  def sysNoimAddr: Int
  def sysIntAddr: Int
  def sysExcAddr: Int
}
```

**File:** `spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` (modified)

```scala
case class JumpTableInitData(
  entries:     Seq[BigInt],
  sysNoimAddr: Int,
  sysIntAddr:  Int,
  sysExcAddr:  Int
) {
  /** Disable HW for specific bytecodes (patch to sys_noim) */
  def disable(bytecodes: Int*): JumpTableInitData =
    copy(entries = entries.zipWithIndex.map { case (addr, i) =>
      if (bytecodes.contains(i)) BigInt(sysNoimAddr) else addr
    })
}

object JumpTableInitData {
  // One superset per boot mode — all HW handlers present
  def simulation: JumpTableInitData = from(jop.SimulationJumpTable)
  def serial:     JumpTableInitData = from(jop.SerialJumpTable)
  def flash:      JumpTableInitData = from(jop.FlashJumpTable)

  private def from(src: JumpTableSource): JumpTableInitData =
    JumpTableInitData(src.entries, src.sysNoimAddr, src.sysIntAddr, src.sysExcAddr)
}
```

**Deleted:** All 11 old factory methods (`serialFpu`, `simulationDsp`, `serialHwMath`, etc.) and their corresponding generated Scala objects.

## Phase 4: Per-Instruction Core Configuration

**File:** `spinalhdl/src/main/scala/jop/system/JopCore.scala`

### Implementation enum

```scala
/** Per-bytecode implementation selection — uniform for all configurable bytecodes */
sealed trait Implementation
object Implementation {
  case object Java extends Implementation       // sys_noim → Java runtime fallback
  case object Microcode extends Implementation  // Pure microcode handler (no HW peripheral)
  case object Hardware extends Implementation   // Microcode → HW I/O peripheral
}
```

Every configurable bytecode uses the same `Implementation` enum. The physical realization splits into two paths based on cycle count: 1-cycle operations go into the ALU pipeline (e.g., ladd with 64-bit adder), 2+ cycle operations go into the Compute Module with `sthw`/`wait` (e.g., imul, fadd, idiv). The config model is uniform — `Implementation.Hardware` means "use hardware", and the system decides whether that's ALU or Compute Module based on the operation.

### JopCoreConfig — per-instruction fields

The config organizes bytecodes by category. Each field specifies Java/Microcode/Hardware. Not all options are available for every bytecode today — validation checks at elaboration.

```scala
case class JopCoreConfig(
  // --- Architectural params (unchanged) ---
  dataWidth: Int = 32, pcWidth: Int = 11, instrWidth: Int = 10,
  jpcWidth: Int = 11, ramWidth: Int = 8, blockBits: Int = 4,
  memConfig: JopMemoryConfig = JopMemoryConfig(),
  cpuId: Int = 0, cpuCnt: Int = 1,
  ioConfig: IoConfig = IoConfig(),
  clkFreqHz: Long = 100000000L,
  useIhlu: Boolean = false,
  useStackCache: Boolean = false,
  spillBaseAddrOverride: Option[Int] = None,

  // --- Per-instruction implementation selection ---
  // Order: add, sub, mul, div, rem, neg, shl, shr, ushr, and, or, xor, cmp/cmpl/cmpg

  // Integer — 2+ cycle ops go to Compute Module
  imul:  Implementation = Implementation.Microcode,  // Microcode=bit-serial 18cyc, Hardware=Compute(DSP)
  idiv:  Implementation = Implementation.Java,       // Hardware=Compute(DivUnit ~34cyc)
  irem:  Implementation = Implementation.Java,       // Hardware=Compute(DivUnit ~34cyc)

  // Long — 1-cycle ops go to ALU (with 64-bit datapath), 2+ cycle to Compute Module
  ladd:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit add, 1 cycle)
  lsub:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit sub, 1 cycle)
  lmul:  Implementation = Implementation.Java,       // Hardware=Compute(DSP cascade)
  ldiv:  Implementation = Implementation.Java,       // Hardware=Compute(DivUnit 64-bit ~66cyc)
  lrem:  Implementation = Implementation.Java,       // Hardware=Compute(DivUnit 64-bit ~66cyc)
  lneg:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit negate, 1 cycle)
  lshl:  Implementation = Implementation.Microcode,  // Hardware=ALU(barrel shifter) or Compute
  lshr:  Implementation = Implementation.Microcode,  // Hardware=ALU(barrel shifter) or Compute
  lushr: Implementation = Implementation.Microcode,  // Hardware=ALU(barrel shifter) or Compute
  land:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit AND, 1 cycle)
  lor:   Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit OR, 1 cycle)
  lxor:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit XOR, 1 cycle)
  lcmp:  Implementation = Implementation.Microcode,  // Hardware=ALU(64-bit compare, 1 cycle)

  // Float — 2+ cycle ops go to Compute Module, simple ops could go to ALU
  fadd:  Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  fsub:  Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  fmul:  Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  fdiv:  Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  frem:  Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  fneg:  Implementation = Implementation.Java,  // Hardware=ALU(sign bit flip, 1 cycle)
  fcmpl: Implementation = Implementation.Java,  // Hardware=ALU(float compare, 1 cycle)
  fcmpg: Implementation = Implementation.Java,  // Hardware=ALU(float compare, 1 cycle)

  // Double — same split: arithmetic to Compute Module, simple to ALU
  dadd:  Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  dsub:  Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  dmul:  Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  ddiv:  Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  drem:  Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  dneg:  Implementation = Implementation.Java,  // Hardware=ALU(sign bit flip, 1 cycle)
  dcmpl: Implementation = Implementation.Java,  // Hardware=ALU(double compare, 1 cycle)
  dcmpg: Implementation = Implementation.Java,  // Hardware=ALU(double compare, 1 cycle)

  // Type conversions — all 2+ cycle, go to Compute Module
  i2f:   Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  i2d:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  f2i:   Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  f2l:   Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  f2d:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  d2i:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  d2l:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  d2f:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  l2f:   Implementation = Implementation.Java,  // Hardware=Compute(FpuCore)
  l2d:   Implementation = Implementation.Java,  // Hardware=Compute(DoubleFpuCore)
  i2b:   Implementation = Implementation.Java,  // Microcode candidate (mask + sign extend)
  i2s:   Implementation = Implementation.Java,  // Microcode candidate (mask + sign extend)
) {
  // --- Derived: what hardware to instantiate ---

  // ALU extensions (1-cycle, combinational, in pipeline)
  private val longAluOps  = Seq(ladd, lsub, lneg, land, lor, lxor, lcmp)
  private val longShifts  = Seq(lshl, lshr, lushr)

  def needsLongAlu: Boolean       = longAluOps.exists(_ == Implementation.Hardware)
  def needsBarrelShifter: Boolean = longShifts.exists(_ == Implementation.Hardware)
  def needs4RegTos: Boolean       = needsLongAlu || needsBarrelShifter ||
    Seq(lmul, ldiv, lrem).exists(_ == Implementation.Hardware) ||
    Seq(dadd, dsub, dmul, ddiv, drem, dneg, dcmpl, dcmpg).exists(_ == Implementation.Hardware)

  // Compute Module sub-units (2+ cycle, sthw/wait pattern)
  def needsMul: Boolean       = imul == Implementation.Hardware || lmul == Implementation.Hardware
  def needsFpu: Boolean       = Seq(fadd, fsub, fmul, fdiv, frem).exists(_ == Implementation.Hardware)
  def needsHwDiv: Boolean     = Seq(idiv, irem, ldiv, lrem).exists(_ == Implementation.Hardware)
  def needsDoubleFpu: Boolean = Seq(dadd, dsub, dmul, ddiv, drem).exists(_ == Implementation.Hardware)
  def needsComputeUnit: Boolean = needsMul || needsFpu || needsHwDiv || needsDoubleFpu

  // --- Derived: jump table resolution ---
  // Maps bytecode opcode → configured Implementation
  // resolveJumpTable() selects the right address per bytecode from the superset ROM
  def resolveJumpTable(base: JumpTableInitData): JumpTableInitData = {
    // Bytecodes to patch to sys_noim (Java fallback)
    val javaPatches: Seq[Int] = configurableBytecodes.collect {
      case (bc, Implementation.Java) => bc
    }
    // Bytecodes to patch to microcode handler (when superset ROM has HW handler
    // but config wants Microcode — uses alternate address from ROM metadata)
    // For now: Java patches only. Microcode/HW distinction needs ROM metadata.
    base.disable(javaPatches: _*)
  }
}
```

**Note on Microcode vs Hardware resolution:** The superset ROM contains both microcode-only handlers (e.g., existing `ladd` at 0x436) and HW handlers (e.g., `fadd` HW at 0x5BF). For bytecodes that have both options, the Jopa-generated jump table data includes addresses for each. The `resolveJumpTable` method selects the appropriate address based on the configured Implementation. For ALU operations (1-cycle, like ladd with 64-bit ALU), `Implementation.Hardware` means the pipeline handles it directly — the jump table entry is irrelevant because the bytecode never reaches microcode.

### Convenience presets

```scala
object JopCoreConfig {
  /** All defaults: imul=microcode (bit-serial), long=microcode, float/double/div=java */
  def software = JopCoreConfig()

  /** DSP multiply (imul + lmul → Compute Module Mul) */
  def dspMul = JopCoreConfig(imul = Implementation.Hardware, lmul = Implementation.Hardware)

  /** HW integer divide (idiv/irem → Compute Module DivUnit) */
  def hwDiv = JopCoreConfig(idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** Full HW integer math (DSP mul + HW div) */
  def hwMath = JopCoreConfig(
    imul = Implementation.Hardware, lmul = Implementation.Hardware,
    idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** HW single-precision float (fadd/fsub/fmul/fdiv → Compute Module FpuCore) */
  def hwFloat = JopCoreConfig(
    fadd = Implementation.Hardware, fsub = Implementation.Hardware,
    fmul = Implementation.Hardware, fdiv = Implementation.Hardware)

  /** 64-bit ALU (1-cycle long arithmetic/bitwise/compare in pipeline) */
  def longAlu = JopCoreConfig(
    ladd = Implementation.Hardware, lsub = Implementation.Hardware,
    lneg = Implementation.Hardware,
    land = Implementation.Hardware, lor  = Implementation.Hardware,
    lxor = Implementation.Hardware, lcmp = Implementation.Hardware,
    lshl = Implementation.Hardware, lshr = Implementation.Hardware,
    lushr = Implementation.Hardware)

  /** Everything: 64-bit ALU + DSP mul + HW div + HW float */
  def hwAll = JopCoreConfig(
    // ALU (1-cycle)
    ladd = Implementation.Hardware, lsub = Implementation.Hardware,
    lneg = Implementation.Hardware,
    land = Implementation.Hardware, lor  = Implementation.Hardware,
    lxor = Implementation.Hardware, lcmp = Implementation.Hardware,
    lshl = Implementation.Hardware, lshr = Implementation.Hardware,
    lushr = Implementation.Hardware,
    // Compute Module (2+ cycle)
    imul = Implementation.Hardware, lmul = Implementation.Hardware,
    idiv = Implementation.Hardware, irem = Implementation.Hardware,
    ldiv = Implementation.Hardware, lrem = Implementation.Hardware,
    fadd = Implementation.Hardware, fsub = Implementation.Hardware,
    fmul = Implementation.Hardware, fdiv = Implementation.Hardware)
}
```

### Deleted

- `fpuMode: FpuMode.FpuMode` → replaced by `needsFpu` (derived from per-bytecode config)
- `useDspMul: Boolean` → replaced by `needsMul` (derived: `imul == Hardware || lmul == Hardware`)
- `useHwDiv: Boolean` → replaced by `needsHwDiv` (derived from per-bytecode config)
- `jumpTable: JumpTableInitData` → replaced by `resolveJumpTable(base)`
- `withFpuJumpTable` / `withMathJumpTable` / `isSerialJumpTable` → deleted
- `FpuMode` enum → deleted
- `MulImpl` enum → deleted (imul uses Implementation like everything else)
- `BmbFpu` / `BmbDiv` I/O peripherals → replaced by Compute Module sub-units

### Update consumers

`JopCluster`, `JopSdramTop`, `JopCyc5000Top`, `JopDdr3Top`, all sim harnesses — replace:
- `fpuMode = FpuMode.Hardware` → `coreConfig.needsFpu`
- `useDspMul = true` → `coreConfig.needsMul`
- `useHwDiv = true` → `coreConfig.needsHwDiv`
- `jumpTable = JumpTableInitData.serialHwMath` → `coreConfig.resolveJumpTable(base)`
- Mul/FpuCore/DivUnit instantiation → `coreConfig.needsComputeUnit` → single ComputeUnit
- Long ALU width → `coreConfig.needsLongAlu` → 64-bit ALU datapath in pipeline
- TOS register count → `coreConfig.needs4RegTos` → 4-register TOS (A/B/C/D)

The JopSdramTop constructor simplifies — no more `jumpTable`, `fpuMode`, `useDspMul`, `useHwDiv` parameters. These are all derived from `coreConfig`.

## Phase 5: Hardware Description — System / Board / FPGA / Memory / Devices

The physical hardware forms an assembly of boards connected together. An FPGA module plugs into a carrier/daughter board. The carrier board provides peripherals (UART, ethernet, SD card, LEDs) via its own connectors. Pin assignments flow through the chain: FPGA pin → FPGA board header → carrier board connector → peripheral.

```
System (physical assembly — the complete hardware on the desk)
  |
  +-- FPGA Board: qmtech-ep4cgx150 (module)
  |     +-- FPGA: EP4CGX150DF27I7 (Cyclone IV GX)
  |     +-- On-board memory: W9825G6JH6 (SDR SDRAM)
  |     +-- On-board devices: 2 LEDs, 2 switches, 50 MHz oscillator
  |     +-- Headers: J2 (60-pin), J3 (60-pin) → expose FPGA pins
  |
  +-- Carrier Board: qmtech-fpga-db-v4 (daughter board)
  |     +-- Connectors: J2, J3 → mate with FPGA board headers
  |     +-- Devices: CP2102N (UART), RTL8211EG (Ethernet), VGA, SD card,
  |     |            7-segment display, 5 LEDs, 5 switches, 2× PMOD
  |     +-- Pin mapping: carrier connector pin → FPGA board header pin → FPGA pin
  |
  +-- JOP System (logical — what runs on the FPGA)
  |     +-- Boot: Serial | Flash | Simulation
  |     +-- Arbiter: RoundRobin | TDMA | ...
  |     +-- Debug config
  |
  +-- JOP Core(s) (per-core configuration)
        +-- core(0) → imul: Hardware(Compute/DSP), ladd: Hardware(ALU), fadd: Hardware(Compute/FPU)
        +-- core(1) → imul: Microcode(bit-serial), ladd: Microcode, fadd: Java
```

### Parts — reusable hardware facts

Parts are concrete components with fixed parameters. A W9825G6JH6 is always the same chip — its datasheet doesn't change. Parts declare their signals but not how they're wired — that's the board's job.

```scala
// ==========================================================================
// Memory
// ==========================================================================

/** Memory interface type — determines which controller to instantiate */
sealed trait MemoryType
object MemoryType {
  case object BRAM extends MemoryType
  case object SDRAM_SDR extends MemoryType
  case object SDRAM_DDR2 extends MemoryType
  case object SDRAM_DDR3 extends MemoryType
}

/** Burst length capabilities */
sealed trait BurstLen
object BurstLen {
  case object B1 extends BurstLen
  case object B2 extends BurstLen
  case object B4 extends BurstLen
  case object B8 extends BurstLen
  case object Page extends BurstLen
}

/** A concrete memory device (datasheet parameters) */
case class MemoryDevice(
  name: String,
  memType: MemoryType,
  sizeBytes: Long,
  dataWidth: Int,
  bankWidth: Int,
  columnWidth: Int,
  rowWidth: Int,
  burstLengths: Seq[BurstLen] = Seq.empty,
  signals: Seq[String] = Seq.empty,   // signal names from datasheet
)

object MemoryDevice {
  def W9825G6JH6 = MemoryDevice(
    name = "W9825G6JH6",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 256L * 1024 * 1024 / 8,  // 256 Mbit = 32 MB
    dataWidth = 16, bankWidth = 2, columnWidth = 9, rowWidth = 13,
    burstLengths = Seq(BurstLen.B1, BurstLen.B2, BurstLen.B4, BurstLen.B8, BurstLen.Page),
    signals = Seq("CLK", "CKE", "CS_n", "RAS_n", "CAS_n", "WE_n",
                  "BA0", "BA1", "A0", "A1", "A2", "A3", "A4", "A5", "A6",
                  "A7", "A8", "A9", "A10", "A11", "A12",
                  "DQ0", "DQ1", "DQ2", "DQ3", "DQ4", "DQ5", "DQ6", "DQ7",
                  "DQ8", "DQ9", "DQ10", "DQ11", "DQ12", "DQ13", "DQ14", "DQ15",
                  "DQM0", "DQM1"))

  def W9864G6JT = MemoryDevice(
    name = "W9864G6JT",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 64L * 1024 * 1024 / 8,   // 64 Mbit = 8 MB
    dataWidth = 16, bankWidth = 2, columnWidth = 8, rowWidth = 12,
    burstLengths = Seq(BurstLen.B1, BurstLen.B2, BurstLen.B4, BurstLen.B8, BurstLen.Page),
    signals = Seq(/* same pattern as above */))

  def MT41K128M16JT = MemoryDevice(
    name = "MT41K128M16JT-125:K",
    memType = MemoryType.SDRAM_DDR3,
    sizeBytes = 2L * 1024 * 1024 * 1024 / 8,  // 2 Gbit = 256 MB
    dataWidth = 16, bankWidth = 3, columnWidth = 10, rowWidth = 14,
    burstLengths = Seq(BurstLen.B8),
    signals = Seq(/* DDR3 signal set */))
}

// ==========================================================================
// FPGA
// ==========================================================================

sealed trait Manufacturer
object Manufacturer {
  case object Altera extends Manufacturer    // Intel
  case object Xilinx extends Manufacturer    // AMD
}

/** FPGA family (determines synthesis tool, DSP type, memory primitives) */
sealed trait FpgaFamily { def manufacturer: Manufacturer }
object FpgaFamily {
  case object CycloneIV extends FpgaFamily  { val manufacturer = Manufacturer.Altera }
  case object CycloneV extends FpgaFamily   { val manufacturer = Manufacturer.Altera }
  case object Artix7 extends FpgaFamily     { val manufacturer = Manufacturer.Xilinx }
}

/** A concrete FPGA device */
case class FpgaDevice(
  name: String,               // "EP4CGX150DF27I7"
  family: FpgaFamily,
  pins: Seq[String] = Seq.empty,  // available I/O pins
)

object FpgaDevice {
  def EP4CGX150DF27I7 = FpgaDevice("EP4CGX150DF27I7", FpgaFamily.CycloneIV)
  def `5CEBA2F17A7`   = FpgaDevice("5CEBA2F17A7",     FpgaFamily.CycloneV)
  def XC7A35T         = FpgaDevice("XC7A35T",          FpgaFamily.Artix7)
  def XC7A100T        = FpgaDevice("XC7A100T-1FGG676C", FpgaFamily.Artix7)
}
```

### Boards — assemblies with pin mappings

A board wires device signals to FPGA pins. The mapping is a board-level fact — it comes from the PCB schematic.

```scala
/** A device mounted on a board with its signal-to-FPGA-pin mapping */
case class BoardDevice(
  device: String,                             // device name or part number
  mapping: Map[String, String] = Map.empty,   // device signal → FPGA pin
)

/** FPGA board — the module with FPGA + on-board devices */
case class FpgaBoard(
  name: String,                   // "qmtech-ep4cgx150"
  fpga: FpgaDevice,
  devices: Seq[BoardDevice],     // on-board devices with pin mappings
) {
  /** All memory devices on this board */
  def memories: Seq[MemoryDevice] = ???  // looked up from device registry
}

/** Carrier/daughter board — plugs into FPGA board headers */
case class CarrierBoard(
  name: String,                   // "qmtech-fpga-db-v4"
  connectors: Seq[String],       // "J2", "J3"
  devices: Seq[BoardDevice],     // devices with pin mappings (through connectors)
)

/** Complete physical system — FPGA board + optional carrier board(s) */
case class SystemAssembly(
  name: String,
  fpgaBoard: FpgaBoard,
  carrierBoards: Seq[CarrierBoard] = Seq.empty,
)
```

### Board presets

```scala
object FpgaBoard {
  def qmtechEp4cgx150 = FpgaBoard(
    name = "qmtech-ep4cgx150",
    fpga = FpgaDevice.EP4CGX150DF27I7,
    devices = Seq(
      BoardDevice("W9825G6JH6", mapping = Map(
        "CLK" -> "PIN_E22", "CKE" -> "PIN_K24",
        "CS_n" -> "PIN_H26", "RAS_n" -> "PIN_H25",
        "CAS_n" -> "PIN_G26", "WE_n" -> "PIN_G25",
        "BA0" -> "PIN_J25", "BA1" -> "PIN_J26",
        "A0" -> "PIN_L25", "A1" -> "PIN_L26", /* ... */
        "DQ0" -> "PIN_B25", "DQ1" -> "PIN_B26", /* ... */
        "DQM0" -> "PIN_F26", "DQM1" -> "PIN_H24")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_B14")),
      BoardDevice("LED", mapping = Map("led0" -> "PIN_A25", "led1" -> "PIN_A24")),
      BoardDevice("SWITCH", mapping = Map("sw0" -> "PIN_AD23", "sw1" -> "PIN_AD24"))))

  def cyc5000 = FpgaBoard(
    name = "cyc5000",
    fpga = FpgaDevice.`5CEBA2F17A7`,
    devices = Seq(
      BoardDevice("W9864G6JT", mapping = Map(/* ... */)),
      BoardDevice("CLOCK_12MHz", mapping = Map(/* ... */)),
      BoardDevice("FT2232H", mapping = Map(/* JTAG + UART */)),
      BoardDevice("LED", mapping = Map(/* 5 LEDs */))))

  def alchitryAuV2 = FpgaBoard(
    name = "alchitry-au-v2",
    fpga = FpgaDevice.XC7A35T,
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", mapping = Map(/* DDR3 pins */)),
      BoardDevice("CLOCK_100MHz", mapping = Map(/* ... */)),
      BoardDevice("FT2232H", mapping = Map(/* JTAG + UART */))))

  // Wukong board — two memory devices on one board
  def wukongXc7a100t = FpgaBoard(
    name = "qmtech-wukong-xc7a100t",
    fpga = FpgaDevice.XC7A100T,
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", mapping = Map(/* DDR3 pins */)),
      BoardDevice("W9825G6JH6", mapping = Map(/* SDR SDRAM pins */)),
      BoardDevice("CLOCK_50MHz", mapping = Map(/* ... */))))
}

object CarrierBoard {
  def qmtechDbV4 = CarrierBoard(
    name = "qmtech-fpga-db-v4",
    connectors = Seq("J2", "J3"),
    devices = Seq(
      BoardDevice("CP2102N", mapping = Map(
        "RXD" -> "PIN_AE21", "TXD" -> "PIN_AD20")),
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "PIN_A20", "MDIO" -> "PIN_A21",
        "RESET" -> "PIN_A15", /* ... */)),
      BoardDevice("VGA", mapping = Map(
        "HS" -> "PIN_A6", "VS" -> "PIN_A7",
        "R0" -> "PIN_E1", /* ... */)),
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "PIN_B21", "CMD" -> "PIN_A22", /* ... */)),
      BoardDevice("SEVEN_SEG", mapping = Map(/* ... */)),
      BoardDevice("LED", mapping = Map(
        "led2" -> "PIN_AD14", "led3" -> "PIN_AC14", /* ... */)),
      BoardDevice("PMOD_J10", mapping = Map(/* ... */)),
      BoardDevice("PMOD_J11", mapping = Map(/* ... */))))
}

object SystemAssembly {
  def qmtechWithDb = SystemAssembly("qmtech-ep4cgx150-db-v4",
    FpgaBoard.qmtechEp4cgx150, Seq(CarrierBoard.qmtechDbV4))

  def cyc5000 = SystemAssembly("cyc5000", FpgaBoard.cyc5000)

  def alchitryAuV2 = SystemAssembly("alchitry-au-v2", FpgaBoard.alchitryAuV2)

  def wukong = SystemAssembly("wukong-xc7a100t", FpgaBoard.wukongXc7a100t)
}
```

### Pin assignments — generated from board data

Pin assignments are derived from `BoardDevice.mapping`. The board data is the single source of truth — no more manually maintained `.qsf`/`.xdc` files.

Currently stored in reusable `.qsf` includes:
- `fpga/qmtech-ep4cgx150-core.qsf` — FPGA board: clock, LEDs, switches, SDRAM
- `fpga/qmtech-ep4cgx150-db.qsf` — Carrier board: UART, Ethernet, VGA, SD, 7-seg, PMODs

Migration: existing `.qsf` files can be generated from `SystemAssembly` data, or kept as hand-maintained references until generation is implemented.

### JOP System layer — processor system organization

```scala
sealed trait BootMode { def dirName: String }
object BootMode {
  case object Serial extends BootMode     { val dirName = "serial" }
  case object Flash extends BootMode      { val dirName = "flash" }
  case object Simulation extends BootMode { val dirName = "simulation" }
}

sealed trait ArbiterType
object ArbiterType {
  case object RoundRobin extends ArbiterType     // current default
  case object Tdma extends ArbiterType           // time-division multiple access
  // future: Priority, WeightedRR, ...
}

case class JopSystemConfig(
  name: String,
  system: SystemAssembly,                            // physical hardware
  bootMode: BootMode,
  arbiterType: ArbiterType = ArbiterType.RoundRobin,
  clkFreqHz: Long,                   // system clock (after PLL)
  cpuCnt: Int = 1,
  coreConfig: JopCoreConfig = JopCoreConfig(),       // default for all cores
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None,  // heterogeneous override
  ioConfig: IoConfig = IoConfig(),
  debugConfig: Option[DebugConfig] = None,
  perCoreUart: Boolean = false,
) {
  // --- Derived paths from boot mode ---
  def romPath: String = s"asm/generated/${bootMode.dirName}/mem_rom.dat"
  def ramPath: String = s"asm/generated/${bootMode.dirName}/mem_ram.dat"

  def baseJumpTable: JumpTableInitData = bootMode match {
    case BootMode.Serial     => JumpTableInitData.serial
    case BootMode.Flash      => JumpTableInitData.flash
    case BootMode.Simulation => JumpTableInitData.simulation
  }

  // --- Derived: per-core configs (heterogeneous or uniform) ---
  def coreConfigs: Seq[JopCoreConfig] = perCoreConfigs.getOrElse(
    Seq.fill(cpuCnt)(coreConfig)
  )

  // --- Derived from physical assembly ---
  def fpga: FpgaFamily = system.fpga
  def memoryType: MemoryType = system.memories.head

  // --- Validation ---
  require(cpuCnt >= 1)
  perCoreConfigs.foreach(pcc =>
    require(pcc.length == cpuCnt,
      s"perCoreConfigs length (${pcc.length}) must match cpuCnt ($cpuCnt)"))
}
```

### System presets

```scala
object JopSystemConfig {
  // QMTECH EP4CGX150 + DB_FPGA daughter board — primary platform
  def qmtechSerial = JopSystemConfig("qmtech-serial",
    system = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Serial,
    clkFreqHz = 80000000L)

  def qmtechSmp(n: Int) = qmtechSerial.copy(
    name = s"qmtech-smp$n", cpuCnt = n)

  def qmtechHwMath = qmtechSerial.copy(
    name = "qmtech-hwmath", coreConfig = JopCoreConfig.hwMath)

  // Heterogeneous: core 0 = fast math, core 1 = minimal
  def qmtechHetero = qmtechSerial.copy(
    name = "qmtech-hetero", cpuCnt = 2,
    perCoreConfigs = Some(Seq(JopCoreConfig.hwMath, JopCoreConfig.software)))

  // CYC5000
  def cyc5000Serial = JopSystemConfig("cyc5000-serial",
    system = SystemAssembly.cyc5000,
    bootMode = BootMode.Serial,
    clkFreqHz = 100000000L)

  // Alchitry Au V2
  def auSerial = JopSystemConfig("au-serial",
    system = SystemAssembly.alchitryAuV2,
    bootMode = BootMode.Serial,
    clkFreqHz = 83333333L)

  // Simulation (no physical board — assembly is just a placeholder)
  def simulation = JopSystemConfig("simulation",
    system = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Simulation,
    clkFreqHz = 100000000L)
}
```

### What the system assembly drives

The physical assembly determines:
- **Memory controller instantiation**: `SdrSdram` → `BmbSdramCtrl32` (or Altera BlackBox), `Ddr3` → `BmbCacheBridge` + MIG, `BramOnly` → `BmbOnChipRam`
- **Top-level I/O ports**: SDRAM pins vs DDR3 pins vs none
- **PLL configuration**: FPGA board oscillator freq → system clock
- **FPGA family**: Affects synthesis tool (Quartus vs Vivado), DSP block type, memory primitives
- **Available peripherals**: Carrier board determines which I/O devices exist (UART, Ethernet, SD, etc.)
- Future: pin assignment / constraint file generation from board data

Currently this mapping is implicit in separate top-level files (`JopSdramTop`, `JopDdr3Top`, `JopCyc5000Top`). With `SystemAssembly`, a single generic top-level could dispatch based on memory type. Migration path: keep existing top-level files but have them read from `JopSystemConfig` instead of manual params.

### Unified generation entry point

**File:** `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` (new)

Single `sbt runMain jop.system.JopGenerate <preset>` replaces 20 separate entry points. Old `object JopSdramTopVerilog extends App` etc. become thin wrappers for backward compat.

## Phase 6: Java Runtime Generation

The Java runtime currently has manual configuration scattered across several files:

**Current state:**
- `Const.java`: `SUPPORT_FLOAT = true`, `SUPPORT_DOUBLE = true` — manual boolean flags
- `Const.java`: `IO_FPU = IO_BASE+0x70`, `IO_DIV = IO_BASE+0x60` — hardcoded I/O addresses
- `JVM.java`: `f_fadd()` → `if (Const.SUPPORT_FLOAT) SoftFloat32.float_add(a,b)` — compile-time branching
- `com/jopdesign/hw/`: Device drivers (EthMac, SdNative, VgaDma, etc.) — always compiled, even if carrier board lacks the device

**Config-driven approach:**

The runtime is generated/configured from `JopSystemConfig`:

### 1. Const.java generation

```java
// Generated from JopSystemConfig — do not edit manually
public class Const {
  // From IoConfig
  public static final int IO_BASE  = -128;
  public static final int IO_FPU   = IO_BASE + 0x70;  // present: core config has needsFpu
  public static final int IO_DIV   = IO_BASE + 0x60;  // present: core config has needsHwDiv

  // From JopCoreConfig (union of all cores' capabilities)
  public static final boolean SUPPORT_FLOAT  = true;   // any core has fadd/fsub/... != Java
  public static final boolean SUPPORT_DOUBLE = true;    // any core has dadd/dsub/... != Java

  // From SystemAssembly
  public static final boolean HAS_ETHERNET = true;      // carrier board has RTL8211EG
  public static final boolean HAS_SD_CARD  = true;      // carrier board has SD slot
  public static final boolean HAS_VGA      = true;      // carrier board has VGA output
  // ...
}
```

### 2. JVM.java bytecode handlers

Today each `f_fadd()` etc. checks `Const.SUPPORT_FLOAT` at compile time. With config-driven generation, these become:

- **Implementation.Java** → `f_fadd()` calls `SoftFloat32.float_add()` (software fallback)
- **Implementation.Hardware** → `f_fadd()` is **never called** (jump table routes to HW microcode, JVM.java handler is dead code)
- **Implementation.Microcode** → `f_fadd()` is **never called** (jump table routes to microcode handler)

For heterogeneous configs (core 0 = HW float, core 1 = SW float), the runtime must include SoftFloat32 since core 1 needs it. The union of all cores' configs determines what software fallbacks are compiled in.

### 3. Device driver inclusion

Carrier board peripherals determine which HW driver classes are included:

| Carrier Board Device | Java Driver Class | Included When |
|---------------------|-------------------|---------------|
| CP2102N UART | `SerialPort.java` | Always (boot requires UART) |
| RTL8211EG Ethernet | `EthMac.java`, `Mdio.java` | `CarrierBoard.devices` contains Ethernet |
| SD card slot | `SdNative.java`, `SdSpi.java` | `CarrierBoard.devices` contains SD |
| VGA output | `VgaDma.java`, `VgaText.java` | `CarrierBoard.devices` contains VGA |

### 4. Build integration

```makefile
# Generate Const.java from system config, then build runtime
generate-runtime:
    sbt "runMain jop.system.GenerateRuntime qmtech-serial"
    cd java && make all
```

Or as an sbt task that generates `Const.java` before the Java toolchain runs.

### Heterogeneous core considerations

With per-core configs, the runtime is built for the **union** of all cores' capabilities:
- If *any* core uses `fadd: Java` → `SoftFloat32` must be in the runtime
- If *all* cores use `fadd: Hardware` → `SoftFloat32` can be excluded (saves .jop size)
- I/O address constants reflect the superset (all cores share the same address space)

This is a constraint: the `.jop` file is shared across all cores. Core-specific behavior comes from the jump table (patched per-core at elaboration time), not from the Java code.

## Phase 7: Simulation Harness Dedup (separate, lower priority)

Factor common sim patterns into a `SimRunner` utility. New sims use it; old sims migrate incrementally. Not part of the core refactoring.

---

## Execution Order

| # | Phase | Action | Files |
|---|-------|--------|-------|
| 1 | 1 | Jopa: remove unused outputs, add `-n` flag, `extends JumpTableSource` | `Jopa.java` |
| 2 | 2 | Makefile: 3 superset targets | `asm/Makefile` |
| 3 | 2 | Build + verify superset ROMs produce correct outputs | `asm/generated/` |
| 4 | 3 | Add `JumpTableSource` trait, `disable()`, 3 factory methods | `JumpTable.scala` |
| 5 | 4 | Add Implementation/MulImpl enums, per-instruction config, derived methods | `JopCore.scala` |
| 6 | 4 | Update JopCluster/JopSdramTop/JopCyc5000Top to use derived flags | top-level files |
| 7 | 4 | Update all sim harnesses | `src/test/scala/jop/system/*.scala` |
| 8 | 4 | Delete old FpuMode, old JumpTableData variants, old variant dirs | cleanup |
| 9 | 5 | Add SystemAssembly/FpgaBoard/CarrierBoard + JopSystemConfig + JopGenerate | new files |
| 10 | 5 | Convert old entry points to thin wrappers | top-level files |
| 11 | 6 | Const.java generation from config, runtime build integration | `Const.java`, build scripts |
| 12 | 6 | Device driver conditional inclusion | `java/runtime/` build |
| 13 | — | Full verification | all sims + FPGA |

## Files to Modify

| File | Action |
|------|--------|
| `java/tools/src/com/jopdesign/tools/Jopa.java` | Remove 4 outputs, add `-n`, generate `extends JumpTableSource` |
| `asm/Makefile` | 3 superset targets (was 12 variant targets) |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` | `disable()`, simplified factory methods |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTableSource.scala` | **New**: trait for generated objects |
| `spinalhdl/src/main/scala/jop/system/JopCore.scala` | Per-instruction config, derived flags, delete old |
| `spinalhdl/src/main/scala/jop/system/JopSystemConfig.scala` | **New**: unified system config |
| `spinalhdl/src/main/scala/jop/system/SystemAssembly.scala` | **New**: FpgaBoard, CarrierBoard, SystemAssembly |
| `spinalhdl/src/main/scala/jop/system/JopSdramTop.scala` | Use derived flags, read from JopSystemConfig |
| `spinalhdl/src/main/scala/jop/system/JopCluster.scala` | Accept resolved jumpTable, per-core configs |
| `spinalhdl/src/main/scala/jop/system/JopCyc5000Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` | **New**: unified Verilog entry point |
| `spinalhdl/src/main/scala/jop/system/GenerateRuntime.scala` | **New**: generates Const.java from config |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | Generated from config (was manual) |
| `java/Makefile` | Add `generate-runtime` target |
| `spinalhdl/src/main/scala/jop/system/IoConfig.scala` | No changes needed |
| `asm/generated/{fpu,serial-fpu,dsp,...}/` | **Delete**: old variant subdirs |
| `asm/generated/{Fpu,SerialFpu,Dsp,...}JumpTableData.scala` | **Delete**: old variant objects |

## Verification

1. `cd asm && make all` — 3 superset ROMs build
2. `sbt "runMain jop.system.GenerateRuntime simulation"` — Const.java generated correctly
3. `cd java && make all` — Java toolchain builds with generated Const.java
4. `sbt "Test / runMain jop.system.JopCoreBramSim"` — basic BRAM sim passes
5. `sbt "Test / runMain jop.system.JopJvmTestsBramSim"` — 59/60 JVM tests pass (software config)
6. `sbt "Test / runMain jop.system.JopFpuBramSim"` — 59/60 pass (hwFloat config)
7. `sbt "Test / runMain jop.system.JopHwMathBramSim"` — 59/60 pass (hwMath config)
8. `sbt "runMain jop.system.JopSdramTopVerilog"` — Verilog generates
9. FPGA build + HelloWorld.jop download — works on hardware
