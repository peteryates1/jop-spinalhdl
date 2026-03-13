# Configuration-Driven JOP System

## Implementation Status

| Phase | Description | Status |
|:---:|---|---|
| 1 | Jopa Assembler Cleanup | **Done** — superset ROM targets, dead target removal |
| 2 | Superset Microcode Build | **Done** — 3 targets (serial, flash, simulation) |
| 3 | Jump Table Patching in Scala | **Done** — `resolveJumpTable` with per-bytecode patching |
| 4 | Per-Instruction Core Configuration | **Done** — `Implementation` enum, `JopCoreConfig` per-bytecode fields |
| 5 | Hardware Description (Parts, Board, Assembly) | **Done** — `jop.config` package with full board/FPGA/memory metadata |
| 5b | Unified FPGA Top-Level (`JopTop`) | **Done** — replaces 7 board-specific tops, verified on hardware (QMTECH EP4CGX150) and simulation |
| 5c | PLL / Reset / Memory Controller Factories | **Done** — `Pll.create()`, `ResetGenerator`, `MemoryControllerFactory` |
| 5d | `JopTopVerilog` Entry Point | **Done** — 12 presets (incl. `max1000Sdram`, `ep4ce6Sdram`), backward-compatible entity names |
| 5e | Simulation Mode | **Done** — `JopTop(simulation=true)` bypasses PLL/MIG for Verilator |
| 5f | Vendor-Specific Memory Primitives | **Done** — `MemoryStyle` config: `AlteraLpm` (lpm_rom/lpm_ram_dp BlackBox with .mif) vs `Generic` (SpinalHDL Mem). Auto-derived from FPGA manufacturer. Small FPGA presets disable caches. |
| 5g | Pluggable I/O (HasBusIo / IoAddressAllocator) | **Done** — `IoDeviceDescriptor`, `HasBusIo` trait, `IoAddressAllocator`, compact I/O address layout (Sys 0xF0-0xFF, boot 0xEE-0xEF, auto-alloc 0xED down) |
| 5h | Declarative Assembly Config | Not started — `DeviceInstance`, `DeviceTypes` registry, per-core devices, per-cluster `Const.java`, 11-step migration from `IoConfig` boolean flags |
| 6 | Board-Specific Modular Java Runtime | Not started |
| 7 | Simulation Harness Dedup | Not started |
| 8 | Build System | Not started |
| 9 | IDE Integration (Eclipse) | Not started |
| 10 | Cross-Cutting Concerns | Not started |

### Key Files (Phases 1-5)

| File | Description |
|------|-------------|
| `jop/config/JopConfig.scala` | Top-level config: presets, validation, entity naming |
| `jop/config/Board.scala` | Board/FPGA/device metadata with pin mappings |
| `jop/config/Parts.scala` | FPGA and memory device catalog |
| `jop/config/IoConfig.scala` | I/O device configuration (boolean flags — to be replaced by DeviceInstance) |
| `jop/config/JopCoreConfig.scala` | Per-core bytecode implementation choices |
| `jop/io/IoDeviceDescriptor.scala` | Device descriptor: name, addrBits, interruptCount, factory |
| `jop/io/IoAddressAllocator.scala` | Per-core I/O address allocation (fixed + auto, largest-first) |
| `jop/io/HasBusIo.scala` | Trait for bus-connected I/O devices with generic BMB wiring |
| `jop/system/JopTop.scala` | Unified FPGA top-level Component |
| `jop/system/JopTopVerilog.scala` | Single Verilog generation entry point |
| `jop/system/JopSpinalConfig.scala` | SpinalConfig factory (Altera vs Xilinx) |
| `jop/system/JopCore.scala` | Per-core device instantiation and address allocation |
| `jop/system/JopCluster.scala` | Multi-core cluster: per-core device restriction, pin passthrough |
| `jop/system/pll/Pll.scala` | PLL factory (per-board BlackBox dispatch) |
| `jop/system/ResetGenerator.scala` | Extracted reset generation |
| `jop/system/HangDetector.scala` | Diagnostic UART mux |
| `jop/system/memory/MemoryControllerFactory.scala` | BRAM/SDR/DDR3 dispatch |
| `jop/generate/ConstGenerator.scala` | Generates Const.java from I/O address allocation |

## Contents

- [Problem](#problem)
- [Design Principle](#design-principle)
  - [Three-way per-bytecode choice](#three-way-per-bytecode-choice)
  - [Configurable Bytecodes (~48 total)](#configurable-bytecodes-48-total)
- [Key Insight: Superset ROM + Jump Table Patching](#key-insight-superset-rom--jump-table-patching)
  - [ROM Size Budget](#rom-size-budget)
- [Compute Units — `sthw` (start hardware)](#compute-units--sthw-start-hardware)
  - [Problem with current I/O-based peripherals](#problem-with-current-io-based-peripherals)
  - [Solution: unified compute dispatch](#solution-unified-compute-dispatch)
  - [All HW bytecodes become identical](#all-hw-bytecodes-become-identical)
  - [ALU vs Compute Module — the 1-cycle rule](#alu-vs-compute-module--the-1-cycle-rule)
  - [Compute unit components](#compute-unit-components)
  - [Operand mapping](#operand-mapping)
  - [What this eliminates](#what-this-eliminates)
  - [What stays the same](#what-stays-the-same)
  - [64-bit operations (sthw/wait with 4-register TOS)](#64-bit-operations-sthwwait-with-4-register-tos)
  - [1-cycle ALU path (optional 64-bit datapath)](#1-cycle-alu-path-optional-64-bit-datapath)
- [Phase 1: Jopa Assembler Cleanup](#phase-1-jopa-assembler-cleanup)
- [Phase 2: Superset Microcode Build](#phase-2-superset-microcode-build)
- [Phase 3: Jump Table Patching in Scala](#phase-3-jump-table-patching-in-scala)
- [Phase 4: Per-Instruction Core Configuration](#phase-4-per-instruction-core-configuration)
  - [Implementation enum](#implementation-enum)
  - [JopCoreConfig — per-instruction fields](#jopcoreconfig--per-instruction-fields)
  - [Convenience presets](#convenience-presets)
  - [Deleted](#deleted)
  - [Update consumers](#update-consumers)
- [Phase 5: Hardware Description — System / Board / FPGA / Memory / Devices](#phase-5-hardware-description--system--board--fpga--memory--devices)
  - [Parts — reusable hardware facts](#parts--reusable-hardware-facts)
  - [Boards — assemblies with pin mappings](#boards--assemblies-with-pin-mappings)
  - [Board presets](#board-presets)
  - [Pin assignments — generated from board data](#pin-assignments--generated-from-board-data)
  - [JOP System layer — processor system organization](#jop-system-layer--processor-system-organization)
  - [System presets](#system-presets)
  - [What the system assembly drives](#what-the-system-assembly-drives)
  - [Unified generation entry point](#unified-generation-entry-point)
- [Phase 6: Board-Specific Modular Java Runtime](#phase-6-board-specific-modular-java-runtime)
  - [Current state](#current-state)
  - [Vision: board-specific runtime JAR from modular components](#vision-board-specific-runtime-jar-from-modular-components)
  - [Module structure](#module-structure)
  - [Module selection from config](#module-selection-from-config)
  - [JDK class library — sourced from OpenJDK 6](#jdk-class-library--sourced-from-openjdk-6)
  - [Const.java generation](#1-constjava-generation)
  - [JVM.java bytecode handlers](#2-jvmjava-bytecode-handlers)
  - [Device driver inclusion](#3-device-driver-inclusion)
  - [Build integration](#4-build-integration)
  - [Heterogeneous core considerations](#heterogeneous-core-considerations)
- [Phase 7: Simulation Harness Dedup](#phase-7-simulation-harness-dedup-separate-lower-priority)
- [Execution Order](#execution-order)
- [Files to Modify](#files-to-modify)
- [Verification](#verification)
- [Phase 8: Build System](#phase-8-build-system)
  - [Architecture: Shared Build Library + Multiple Front-Ends](#architecture-shared-build-library--multiple-front-ends)
  - [Full dependency chain](#full-dependency-chain)
  - [Build library API](#build-library-api)
  - [CLI front-end](#cli-front-end)
  - [Eclipse front-end](#eclipse-front-end)
  - [Incremental build awareness](#incremental-build-awareness)
  - [Configuration format](#configuration-format)
  - [Where the library lives](#where-the-library-lives)
- [Phase 9: IDE Integration (Eclipse)](#phase-9-ide-integration-eclipse)
  - [Eclipse plugin layers](#eclipse-plugin-layers)
  - [Configuration UI](#configuration-ui)
  - [Simulation from IDE](#simulation-from-ide)
- [Phase 10: Cross-Cutting Concerns](#phase-10-cross-cutting-concerns)
  - [Config-to-artifact version binding](#config-to-artifact-version-binding)
  - [PreLinker / JOPizer config awareness](#prelinker--jopizer-config-awareness)
  - [Configuration validation](#configuration-validation)
  - [WCET analysis](#wcet-analysis)
  - [Migration path](#migration-path)
- [Open Items](#open-items)

---

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

**Configuration drives everything.** `JopConfig` is the single source of truth. Every downstream artifact is derived from it — no manual synchronization between layers.

```
JopConfig
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
- Which compute units get instantiated (IntegerComputeUnit, LongComputeUnit, FloatComputeUnit, DoubleComputeUnit)
- Which internal hardware each compute unit includes (e.g., IntegerComputeUnit with Mul but not DivUnit)
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
- **Microcode** → bit-serial Mul unit (18 cycles, ~244 LCs)
- **Hardware** → DSP-inferred Mul unit (1 registered cycle, ~4 DSP18x18) via Compute Module `sthw`/`wait`
- **Java** → sys_noim → JVM.f_imul()

**Long arithmetic** (10) — `ladd` `lsub` `lneg` `lshl` `lshr` `lushr` `land` `lor` `lxor` `lcmp`
- **Microcode** (existing handlers in base ROM) — default
- **Hardware** → LongComputeUnit (64-bit add/sub/neg/cmp + barrel shifter + DSP multiply) ✓ **IMPLEMENTED**
- Always available: **Java** (sys_noim)

**Long multiply** (1) — `lmul`
- **Java** or **Hardware** → LongComputeUnit (DSP cascade via `sthw`/`wait`) ✓ **IMPLEMENTED**

**Integer/long divide** (4) — `idiv` `irem` `ldiv` `lrem`
- **Java** or **Hardware** (IntegerComputeUnit for idiv/irem; ldiv/lrem stay Java — no hardware divider in LongComputeUnit)

**Float arithmetic** (8) — `fadd` `fsub` `fmul` `fdiv` `fneg` `frem` `fcmpl` `fcmpg`
- Today: **Java** or **Hardware** (FloatComputeUnit for fadd/fsub/fmul/fdiv; fneg/fcmpl/fcmpg→ALU, frem→FloatComputeUnit, future HW)

**Double arithmetic** (8) — `dadd` `dsub` `dmul` `ddiv` `dneg` `drem` `dcmpl` `dcmpg`
- **Java** → sys_noim (SoftFloat64) — default
- **Hardware** → DoubleComputeUnit (IEEE 754 double-precision: dadd/dsub/dmul/ddiv/dcmpl/dcmpg) ✓ **IMPLEMENTED**
- dneg/drem stay Java (no hardware support)

**Type conversions** (12) — `i2f` `i2d` `f2i` `f2l` `f2d` `d2i` `d2l` `d2f` `l2f` `l2d` `i2b` `i2s`
- **Microcode** (i2l, l2i, i2c already exist)
- **Hardware** → FloatComputeUnit handles i2f/f2i; DoubleComputeUnit handles i2d/d2i/l2d/d2l/f2d/d2f ✓ **IMPLEMENTED**
- Remaining (f2l, l2f, i2b, i2s): Java or microcode

**Constants** (3) — `fconst_1` `fconst_2` `dconst_1`
- Today: **Java**; trivially implementable as **Microcode**

**Derived hardware instantiation:**
- `needsLongAlu` = any of ladd/lsub/lneg/land/lor/lxor/lcmp is Hardware → 64-bit ALU in pipeline (optional)
- `needsBarrelShifter` = any of lshl/lshr/lushr is Hardware → 64-bit barrel shifter in pipeline (optional)
- `needsIntegerCompute` = any of imul/idiv/irem is Hardware → IntegerComputeUnit (internal HW per-bytecode)
- `needsFloatCompute` = any of fadd/fsub/fmul/fdiv/frem is Hardware → FloatComputeUnit
- `needsLongCompute` = any of lmul/ldiv/lrem is Hardware → LongComputeUnit (internal HW per-bytecode)
- `needsDoubleCompute` = any of dadd/.../drem is Hardware → DoubleComputeUnit
- `needs4RegTos` = any 64-bit Hardware operation → extend TOS from 2 to 4 registers

## Key Insight: Superset ROM + Jump Table Patching

Optional HW handlers (FPU, DSP lmul, HW div) are **appended at the end** of the microcode ROM without shifting any base bytecode addresses. Comparing `JumpTableData` (base) vs `FpuJumpTableData` (FPU), 252 of 256 entries are identical. Only the configurable entries differ.

**Solution:** Build ONE superset ROM per boot mode (all features enabled: `-DFPU_ATTACHED -DDSP_MUL -DHW_DIV`). The superset ROM contains ALL microcode and HW handlers. At SpinalHDL elaboration, construct the jump table per-bytecode:
- **Java** → patch entry to `sys_noim`
- **Microcode** → use the microcode handler address from the superset ROM
- **Hardware** → use the HW handler address from the superset ROM

Result: **12 Makefile targets → 3.** Future features add `#ifdef` blocks to jvm.asm and `-D` flags — no new Makefile targets needed. The superset ROM grows but never splits.

Note: With the compute units, `imul: Hardware` uses the same `sthw` pattern as all other HW bytecodes — IntegerComputeUnit handles the dispatch. `imul: Microcode` uses a pure microcode shift-and-add handler (~32 iterations, ~640 cycles). **Both handlers exist in the superset ROM** — the jump table entry selects which one is active.

**Critical: imul is IMP_ASM.** Unlike float/div bytecodes (which are IMP_JAVA — JOPizer replaces them with `invokestatic`), `imul` (0x68) stays as a raw bytecode in the .jop file. The jump table **must always** point to a working handler. When `imul: Microcode`, the jump table points to the software shift-and-add handler. When `imul: Hardware`, it points to the `sthw` handler. Setting `imul: Java` would require changing JOPizer's IMP_ASM→IMP_JAVA classification for 0x68 (see Phase 10: JOPizer config awareness).

This means the superset ROM **must** contain the microcode software handler even when HW is enabled. All `#ifdef` guards have been removed — both `_hw` and `_sw` labeled handlers exist unconditionally:

```asm
// Both handlers always present in superset ROM
imul_hw:                    // jump table points here when imul: Hardware
    stop                    // push value2 (TOS) → CU operand stack
    stop                    // push value1 (NOS) → CU operand stack
    sthw 0                  // start ICU multiply, busy stalls pipeline
    ldop nxt                // pop result from CU → TOS

imul_sw:                    // jump table points here when imul: Microcode
    stm  b                  // pure microcode shift-and-add (~640 cycles)
    stm  a
    ldi  0
    stm  c
    ldi  32
imul_loop:
    // ... (32-iteration loop)
    ldm  c nxt
```

The Jopa assembler generates both addresses as `altEntries` in `JumpTableData.scala`. Currently 13 bytecodes have HW/SW alternatives: ladd, lsub, lneg, lcmp, lshl, lshr, lushr, imul, lmul, fadd, fsub, fmul, fdiv. `resolveJumpTable` picks the right address based on `imul: Microcode` vs `imul: Hardware`. **No separate bare builds needed** — one superset ROM serves all configurations.

### ROM Size Budget

Current base ROM: ~700-900 instructions (includes long microcode handlers). FPU handlers: ~50. DSP lmul: ~60. HW div: ~30. Software imul: ~35. **Total superset: ~1075 of 2048 slots (53%).** Future long ALU HW handlers (~200) + double FPU (~100) + expanded float conversions (~50) would reach ~1425 (70%). Plenty of headroom.

With the compute units (see below), HW handler microcode shrinks dramatically — all HW bytecodes share the same ~4 instruction pattern instead of 9-10 instructions each. ROM budget improves further.

## Compute Units — `sthw` (start hardware)

> **Implementation status (2026-03-09):** All four compute units (ICU, FCU, LCU, DCU) are fully implemented and integrated via `ComputeUnitTop`. The CU interface has been refactored from the old `a/b/c/d/wr/opcode(8-bit)` bundle to a decoupled architecture using three microcode instructions: `stop` (push operand), `sthw` (start with 6-bit opcode), and `ldop` (pop result). See `docs/architecture/compute-unit-design.md` for the full specification. The superset ROM contains both HW (`_hw`) and SW (`_sw`) handlers for all configurable bytecodes — 13 `altEntries` in `JumpTableData.scala` enable per-bytecode HW/SW selection at elaboration time. 52/52 BRAM JVM tests pass, 568/570 unit tests pass (2 pre-existing JopFileLoaderSpec failures).

Four named compute units handle multi-cycle hardware-accelerated bytecodes:
- **IntegerComputeUnit** — imul, idiv, irem (32-bit integer multiply + divide) ✓ **IMPLEMENTED**
- **FloatComputeUnit** — fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg (single-precision float) ✓ **IMPLEMENTED**
- **LongComputeUnit** — ladd, lsub, lmul, ldiv, lrem, lcmp, lshl, lshr, lushr (64-bit long) ✓ **IMPLEMENTED**
- **DoubleComputeUnit** — dadd, dsub, dmul, ddiv, dcmpl, dcmpg, i2d, d2i, l2d, d2l, f2d, d2f (double-precision float) ✓ **IMPLEMENTED**

Each is independently conditional — only instantiated when needed. All share the `stop`/`sthw`/`ldop` microcode pattern with an internal 4-deep operand stack and result sequencing via `ComputeUnitTop`.

### Problem with current I/O-based peripherals

> **Note (2026-03-09):** All compute units (ICU, FCU, LCU, DCU) have been migrated to the decoupled `stop`/`sthw`/`ldop` pattern via `ComputeUnitTop`. BmbDiv and BmbFpu I/O peripherals have been removed. The old `stmul`/`ldmul` instructions replaced by `stop`/`sthw`/`ldop`. See `docs/architecture/compute-unit-design.md`.

~~Today, FPU and DIV are BMB I/O peripherals accessed via generic memory-mapped I/O. The Mul unit is a pipeline component with dedicated `stmul`/`ldmul` instructions.~~ This created two problems (now solved):

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

Replace `stmul`/`ldmul` and the I/O-based FPU/DIV with a generic microcode instruction:

- **`sthw`** (start hardware) — captures TOS and NOS (and C, D with 4-register TOS), dispatches to the appropriate compute unit based on the **bytecode** that triggered this handler. The bytecode is already available in a pipeline register. The selected compute unit asserts busy until the result is ready.

The compute unit writes results directly back into the stack registers (TOS, and NOS for 64-bit results) — no explicit load instruction needed. The pipeline stalls via busy until the result is written back, then `wait nxt` completes the bytecode.

**Instruction naming**: `sthw` (start hardware). Follows JOP's `st` prefix convention (`stmul`, `stmwa`). No `ldhw` needed — result writeback is implicit.

**Dispatch**: The pipeline routes `sthw` to the correct compute unit based on bytecode:
- `0x68` (imul), `0x6C` (idiv), `0x70` (irem) → IntegerComputeUnit
- `0x69` (lmul), `0x6D` (ldiv), `0x71` (lrem) → LongComputeUnit
- `0x62`-`0x72` (float ops), `0x86`/`0x8B` (conversions) → FloatComputeUnit
- `0x63`-`0x73` (double ops), `0x85`/`0x87`-`0x90` (conversions) → DoubleComputeUnit

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

**The 64-bit ALU is optional.** On small FPGAs (CYC5000 with ~25K LEs, or area-constrained multi-core), a 64-bit adder + comparator + barrel shifter may be too expensive. The three Implementation levels handle this naturally:

| Implementation | ladd example | Cycles | Resources |
|---------------|-------------|--------|-----------|
| Java | sys_noim → Java runtime | ~thousands | Zero hardware |
| Microcode | 26-cycle half-add algorithm | 26 | Zero (uses existing 32-bit ALU) |
| Hardware | 64-bit ALU in pipeline | 1 | ~64-bit adder, comparator, barrel shifter |

`Microcode` is the default for all long ops — JOP fits in small FPGAs with no extra hardware, and long operations run 10-100× faster than Java fallback. `Hardware` is opt-in for FPGAs with headroom.

**1-cycle ALU/Stack (requires `Implementation.Hardware`, optional 64-bit datapath):**

| Category | Operations | Notes |
|----------|-----------|-------|
| Integer (existing) | iadd, isub, iand, ior, ixor, ineg, ishl, ishr, iushr | Already in 32-bit pipeline |
| Long bitwise | land, lor, lxor, lneg | 64-bit bitwise — trivial with 64-bit datapath |
| Long arithmetic | ladd, lsub | 64-bit adder (vs 26-38 microcode cycles) |
| Long compare | lcmp | 64-bit comparator → {-1, 0, 1} (vs 80 microcode cycles) |
| Long shift | lshl, lshr, lushr | 64-bit barrel shifter (most LUT-expensive) |
| Float simple | fneg | Sign bit flip |
| Float compare | fcmpl, fcmpg | Exponent/mantissa comparison → {-1, 0, 1} |

**2+ cycle Compute Module (sthw/wait pattern):**

| Compute Unit | Operations | Cycles | Internal HW |
|-------------|-----------|--------|-------------|
| IntegerComputeUnit | imul (bit-serial) | ~18 | Mul (radix-4) |
| IntegerComputeUnit | imul (DSP) | 1 (registered) | Mul (DSP inferred) |
| IntegerComputeUnit | idiv, irem | ~34 | DivUnit (32-bit) |
| LongComputeUnit | lmul | varies | Mul (DSP cascade) |
| LongComputeUnit | ldiv, lrem | ~66 | DivUnit (64-bit) |
| FloatComputeUnit | fadd, fsub, fmul, fdiv, frem | varies | IEEE 754 single |
| FloatComputeUnit | i2f, f2i, f2l, l2f | varies | IEEE 754 single |
| DoubleComputeUnit | dadd, dsub, dmul, ddiv, drem | varies | IEEE 754 double |
| DoubleComputeUnit | i2d, d2i, d2f, f2d, l2d, d2l | varies | IEEE 754 double |

Note: DSP imul is 1 registered cycle but uses DSP blocks, not ALU LUTs. It lives in IntegerComputeUnit — the `sthw`/`wait` pattern handles both bit-serial and DSP uniformly (DSP just finishes in 1 cycle so `wait` doesn't actually stall).

### Compute unit components

Each compute unit lives in the pipeline. All share a common interface — four 32-bit operands (a/b/c/d matching JVM stack), split 32-bit result, busy signal. Each is independently conditional — only instantiated when the config requires it.

```scala
/** Common interface for all compute units */
case class ComputeUnitBundle() extends Bundle {
  val a        = in UInt(32 bits)   // TOS
  val b        = in UInt(32 bits)   // NOS
  val c        = in UInt(32 bits)   // TOS-2
  val d        = in UInt(32 bits)   // TOS-3
  val wr       = in Bool()          // sthw asserted — capture operands, start
  val opcode   = in Bits(8 bits)    // bytecode selects operation within unit
  val resultLo = out UInt(32 bits)  // TOS for 32-bit ops, lo word for 64-bit
  val resultHi = out UInt(32 bits)  // unused for 32-bit ops, hi word for 64-bit
  val is64     = out Bool()         // true → write both TOS and NOS
  val busy     = out Bool()         // stalls pipeline until done
}
```

**IntegerComputeUnit** — imul, idiv, irem (32-bit operands, 32-bit result).
Internal hardware is per-bytecode conditional: `imul: Hardware, idiv: Software` → Mul instantiated, DivUnit not.

```scala
case class IntegerComputeUnit(config: IntegerComputeUnitConfig) extends ComputeUnit {

  // Mul: only if imul=Hardware. Bit-serial (radix-4, ~18 cycles) or DSP (1 cycle)
  val mul = config.needsIntMul generate Mul(useDsp = true)

  // DivUnit: only if idiv=Hardware or irem=Hardware. Binary restoring, ~34 cycles
  val div = config.needsIntDiv generate DivUnit(width = 32)

  io.is64 := False  // always 32-bit result

  switch(io.opcode) {
    if (config.needsIntMul)
      is(0x68) { /* imul → mul */ }
    if (config.needsIntDiv) {
      is(0x6C) { /* idiv → div, mode=QUOT */ }
      is(0x70) { /* irem → div, mode=REM */ }
    }
  }
}
```

**LongComputeUnit** — ladd, lsub, lneg, lcmp, lshl, lshr, lushr, lmul (64-bit operands, 64-bit result). ✓ **IMPLEMENTED**
Handles three categories: trivial ALU ops (ladd/lsub/lneg/lcmp — single-cycle 64-bit), barrel shifter ops (lshl/lshr/lushr — single-cycle), and DSP multiply (lmul — DSP cascade). No ldiv/lrem in hardware (those stay Java).

```scala
case class LongComputeUnit(config: LongComputeUnitConfig) extends ComputeUnit {

  // 64-bit ALU for ladd/lsub/lneg/lcmp
  // 64-bit barrel shifter for lshl/lshr/lushr
  // DSP cascade multiply for 64×64→64 (lmul)

  io.is64 := True  // always 64-bit result

  switch(io.opcode) {
    is(0x61) { /* ladd → 64-bit add */ }
    is(0x65) { /* lsub → 64-bit sub */ }
    is(0x75) { /* lneg → 64-bit negate */ }
    is(0x94) { /* lcmp → 64-bit compare */ }
    is(0x78) { /* lshl → barrel shift left */ }
    is(0x7A) { /* lshr → barrel shift right (arithmetic) */ }
    is(0x7C) { /* lushr → barrel shift right (logical) */ }
    is(0x69) { /* lmul → DSP cascade multiply */ }
  }
}
```

**FloatComputeUnit** — fadd, fsub, fmul, fdiv, frem, i2f, f2i, f2l, l2f (IEEE 754 single):

```scala
case class FloatComputeUnit(config: FloatComputeUnitConfig) extends ComputeUnit {

  // IEEE 754 single-precision FPU

  io.is64 := False  // default: 32-bit result

  switch(io.opcode) {
    is(0x62) { /* fadd → fpu, op=ADD */ }
    is(0x66) { /* fsub → fpu, op=SUB */ }
    is(0x6A) { /* fmul → fpu, op=MUL */ }
    is(0x6E) { /* fdiv → fpu, op=DIV */ }
    is(0x72) { /* frem → fpu, op=REM */ }
    is(0x86) { /* i2f → fpu, op=I2F */ }
    is(0x8B) { /* f2i → fpu, op=F2I */ }
    is(0x8C) { io.is64 := True /* f2l → fpu, op=F2L, 64-bit result */ }
    is(0x89) { /* l2f → fpu, op=L2F (64-bit input, 32-bit result) */ }
  }
}
```

**DoubleComputeUnit** — dadd, dsub, dmul, ddiv, dcmpl, dcmpg, i2d, d2i, l2d, d2l, f2d, d2f (IEEE 754 double). ✓ **IMPLEMENTED**
Handles double arithmetic (dadd/dsub/dmul/ddiv), double comparison (dcmpl/dcmpg), and type conversions (i2d/d2i/l2d/d2l/f2d/d2f). No drem/dneg in hardware (those stay Java).

```scala
case class DoubleComputeUnit(config: DoubleComputeUnitConfig) extends ComputeUnit {

  // IEEE 754 double-precision FPU

  io.is64 := True  // default: 64-bit result

  switch(io.opcode) {
    is(0x63) { /* dadd → fpu, op=ADD */ }
    is(0x67) { /* dsub → fpu, op=SUB */ }
    is(0x6B) { /* dmul → fpu, op=MUL */ }
    is(0x6F) { /* ddiv → fpu, op=DIV */ }
    is(0x97) { io.is64 := False /* dcmpl → fpu, op=CMPL, 32-bit result */ }
    is(0x98) { io.is64 := False /* dcmpg → fpu, op=CMPG, 32-bit result */ }
    is(0x85) { /* i2d → fpu, op=I2D (32-bit input, 64-bit result) */ }
    is(0x87) { io.is64 := False /* d2i → fpu, op=D2I, 32-bit result */ }
    is(0x90) { io.is64 := False /* d2f → fpu, op=D2F, 32-bit result */ }
    is(0x8D) { /* f2d → fpu, op=F2D (32-bit input, 64-bit result) */ }
    is(0x8A) { /* l2d → fpu, op=L2D (64-bit input, 64-bit result) */ }
    is(0x8F) { /* d2l → fpu, op=D2L (64-bit input, 64-bit result) */ }
  }
}
```

**Pipeline dispatch** — routes `sthw` to the correct compute unit:

```scala
// In JopPipeline — conditional instantiation + dispatch
val intCompute    = config.needsIntegerCompute generate IntegerComputeUnit(config)
val longCompute   = config.needsLongCompute generate LongComputeUnit(config)
val floatCompute  = config.needsFloatCompute generate FloatComputeUnit(config)
val doubleCompute = config.needsDoubleCompute generate DoubleComputeUnit(config)

// Busy = OR of all active compute units
val computeBusy =
  intCompute.map(_.io.busy).getOrElse(False) ||
  longCompute.map(_.io.busy).getOrElse(False) ||
  floatCompute.map(_.io.busy).getOrElse(False) ||
  doubleCompute.map(_.io.busy).getOrElse(False)

// Result MUX — latched active unit on sthw, writeback to TOS/NOS when done
```
```

### Operand mapping

With 4-register TOS (a=TOS, b=NOS, c=TOS-2, d=TOS-3):

```
JVM stack:    ..., value1_hi(d), value1_lo(c), value2_hi(b), value2_lo(a)

ComputeUnit:  a = TOS,   b = NOS,   c = TOS-2, d = TOS-3

32-bit ops:   a = first operand (TOS), b = second operand (NOS)
              c, d unused

64-bit ops:   value1 = d:c (hi:lo) — deeper on stack (left operand for divide)
              value2 = b:a (hi:lo) — top of stack (right operand for divide)
```

This is the same mapping for all operations — every compute unit's operand ports are wired the same way. The bytecode tells the active unit whether to use 2 or 4 of the operands.

### What this eliminates

- **BmbFpu** I/O peripheral → removed (replaced by FloatComputeUnit)
- **BmbDiv** I/O peripheral → removed (replaced by IntegerComputeUnit / LongComputeUnit)
- **I/O address space**: 0xE0-0xE3 (DIV) and 0xF0-0xF3 (FPU) freed up
- **`stmul`/`ldmul`** microcode instructions → replaced by `sthw` + implicit writeback
- **Per-bytecode microcode handlers**: fadd/fsub/fmul/fdiv/idiv/irem each had ~9-10 unique instructions → all share one ~4 instruction pattern
- **I/O wiring in JopCore**: no more `fpuBusy`, `divBusy` I/O bus plumbing

### What stays the same

- **DivUnit** (binary restoring) — same algorithm, just no BMB wrapper
- **Pipeline stall mechanism** — busy signal still stalls the pipeline, just comes from the active compute unit instead of I/O bus

### 64-bit operations (sthw/wait with 4-register TOS)

With 4-register TOS (a/b/c/d), `sthw` captures all 4 registers. The compute unit sees four 32-bit operands:

```
a = TOS     (value2_lo)     b = NOS     (value2_hi)
c = TOS-2   (value1_lo)     d = TOS-3   (value1_hi)
```

The hardware writes resultLo back to TOS (and resultHi to NOS for 64-bit results). No explicit load instruction needed — the `wait nxt` completes when the result is written back.

```asm
// Every 64-bit→64-bit HW bytecode (lmul, ldiv, lrem, dadd, dsub, dmul, ddiv, drem):
<bytecode>:
    sthw            // capture a,b,c,d — bytecode selects unit
    pop             // remove value2 low (a)
    pop             // remove value2 high (b)
    wait            // stall pipeline while computing
    wait nxt        // resultLo→TOS, resultHi→NOS
```

Stack evolution:
```
Before:   ..., v1_hi(d), v1_lo(c), v2_hi(b), v2_lo(a)   [4 items]
sthw:     ..., v1_hi,    v1_lo,    v2_hi,    v2_lo       captures all 4, no pop
pop:      ..., v1_hi,    v1_lo,    v2_hi                  remove a
pop:      ..., v1_hi,    v1_lo                             remove b
wait:     ..., v1_hi,    v1_lo                             stall, computing...
wait nxt: ..., res_hi,   res_lo                            HW writes resultHi→NOS, resultLo→TOS
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

### 1-cycle ALU path (optional 64-bit datapath)

When `Implementation.Hardware` is selected for long ALU operations, the pipeline handles them directly — no `sthw`, no Compute Module, no microcode. The 4-register TOS provides both 64-bit operands to the ALU combinationally:

```
Pipeline ALU input:   d:c op b:a   (value1 op value2)
Pipeline ALU output:  resultHi → NOS, resultLo → TOS
Stack management:     pop 2 (same as 32-bit binary ops pop 1)
```

These bytecodes execute like `iadd` does today — the ALU result is available in the same cycle, the pipeline pops the consumed operands, and `nxt` fetches the next bytecode. Zero microcode overhead.

**This is entirely optional.** When left at `Implementation.Microcode` (the default), the existing microcode handlers run on the 32-bit pipeline with no extra hardware. The three options for each long operation:

| | ladd (example) | Cycles | Extra HW |
|-|---------------|--------|----------|
| **Java** | sys_noim → Java runtime | ~thousands | None |
| **Microcode** (default) | 26-cycle half-add on 32-bit ALU | 26 | None |
| **Hardware** | 64-bit adder in pipeline | 1 | ~64-bit adder |

Current microcode costs (what `Implementation.Hardware` eliminates):
- ladd: 26 cycles (half-add algorithm to avoid 32-bit overflow)
- lsub: 38 cycles (negate + half-add)
- lneg: 34 cycles (negate + fall-through to ladd)
- land/lor/lxor: 8 cycles each (save/restore 4 regs, apply op to each half)
- lcmp: 80 cycles (sign overflow detection + conditional subtraction + three-way branch)
- lshl/lshr/lushr: 28 cycles each (conditional branch on shift count, cross-carry)

The barrel shifter (lshl/lshr/lushr) can be configured independently via `needsBarrelShifter` — on area-constrained FPGAs, enable long arithmetic ALU but leave shifts in microcode.

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

  // Integer — 2+ cycle ops → IntegerComputeUnit
  imul:  Implementation = Implementation.Microcode,  // Microcode=bit-serial 18cyc, Hardware→IntegerComputeUnit(DSP)
  idiv:  Implementation = Implementation.Java,       // Hardware→IntegerComputeUnit(DivUnit ~34cyc)
  irem:  Implementation = Implementation.Java,       // Hardware→IntegerComputeUnit(DivUnit ~34cyc)

  // Long — 1-cycle ops → ALU (with 64-bit datapath), 2+ cycle → LongComputeUnit
  ladd:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit add, 1 cycle)
  lsub:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit sub, 1 cycle)
  lmul:  Implementation = Implementation.Java,       // Hardware→LongComputeUnit(DSP cascade)
  ldiv:  Implementation = Implementation.Java,       // Hardware→LongComputeUnit(DivUnit 64-bit ~66cyc)
  lrem:  Implementation = Implementation.Java,       // Hardware→LongComputeUnit(DivUnit 64-bit ~66cyc)
  lneg:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit negate, 1 cycle)
  lshl:  Implementation = Implementation.Microcode,  // Hardware→ALU(barrel shifter) or Compute
  lshr:  Implementation = Implementation.Microcode,  // Hardware→ALU(barrel shifter) or Compute
  lushr: Implementation = Implementation.Microcode,  // Hardware→ALU(barrel shifter) or Compute
  land:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit AND, 1 cycle)
  lor:   Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit OR, 1 cycle)
  lxor:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit XOR, 1 cycle)
  lcmp:  Implementation = Implementation.Microcode,  // Hardware→ALU(64-bit compare, 1 cycle)

  // Float — arithmetic → FloatComputeUnit, simple ops → ALU
  fadd:  Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  fsub:  Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  fmul:  Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  fdiv:  Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  frem:  Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  fneg:  Implementation = Implementation.Java,  // Hardware→ALU(sign bit flip, 1 cycle)
  fcmpl: Implementation = Implementation.Java,  // Hardware→ALU(float compare, 1 cycle)
  fcmpg: Implementation = Implementation.Java,  // Hardware→ALU(float compare, 1 cycle)

  // Double — arithmetic → DoubleComputeUnit, simple ops → ALU
  dadd:  Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  dsub:  Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  dmul:  Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  ddiv:  Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  drem:  Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  dneg:  Implementation = Implementation.Java,  // Hardware→ALU(sign bit flip, 1 cycle)
  dcmpl: Implementation = Implementation.Java,  // Hardware→ALU(double compare, 1 cycle)
  dcmpg: Implementation = Implementation.Java,  // Hardware→ALU(double compare, 1 cycle)

  // Type conversions — per-bytecode, routed to the appropriate compute unit
  i2f:   Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  i2d:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  f2i:   Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  f2l:   Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  f2d:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  d2i:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  d2l:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  d2f:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
  l2f:   Implementation = Implementation.Java,  // Hardware→FloatComputeUnit
  l2d:   Implementation = Implementation.Java,  // Hardware→DoubleComputeUnit
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

  // Compute units (2+ cycle, sthw/wait pattern) — each independently conditional
  def needsIntegerCompute: Boolean = Seq(imul, idiv, irem).exists(_ == Implementation.Hardware)
  def needsLongCompute: Boolean    = Seq(lmul, ldiv, lrem).exists(_ == Implementation.Hardware)
  def needsFloatCompute: Boolean   = Seq(fadd, fsub, fmul, fdiv, frem).exists(_ == Implementation.Hardware)
  def needsDoubleCompute: Boolean  = Seq(dadd, dsub, dmul, ddiv, drem).exists(_ == Implementation.Hardware)
  def needsAnyCompute: Boolean     = needsIntegerCompute || needsLongCompute ||
                                     needsFloatCompute || needsDoubleCompute

  // Internal hardware within each compute unit — also per-bytecode conditional
  // e.g., IntegerComputeUnit with imul=HW, idiv=SW → Mul instantiated, DivUnit not
  def needsIntMul: Boolean  = imul == Implementation.Hardware
  def needsIntDiv: Boolean  = Seq(idiv, irem).exists(_ == Implementation.Hardware)
  def needsLongMul: Boolean = lmul == Implementation.Hardware
  def needsLongDiv: Boolean = Seq(ldiv, lrem).exists(_ == Implementation.Hardware)

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

  /** DSP multiply (imul → IntegerComputeUnit, lmul → LongComputeUnit) */
  def dspMul = JopCoreConfig(imul = Implementation.Hardware, lmul = Implementation.Hardware)

  /** HW integer divide (idiv/irem → IntegerComputeUnit DivUnit) */
  def hwDiv = JopCoreConfig(idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** Full HW integer math (IntegerComputeUnit: DSP + DivUnit) */
  def hwMath = JopCoreConfig(
    imul = Implementation.Hardware, lmul = Implementation.Hardware,
    idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** HW single-precision float (fadd/fsub/fmul/fdiv → FloatComputeUnit) */
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

- `fpuMode: FpuMode.FpuMode` → replaced by `needsFloatCompute` (derived from per-bytecode config)
- `useDspMul: Boolean` → replaced by `needsIntMul` (derived: `imul == Hardware`)
- `useHwDiv: Boolean` → replaced by `needsIntDiv` (derived from per-bytecode config)
- `jumpTable: JumpTableInitData` → replaced by `resolveJumpTable(base)`
- `withFpuJumpTable` / `withMathJumpTable` / `isSerialJumpTable` → deleted
- `FpuMode` enum → deleted
- `MulImpl` enum → deleted (imul uses Implementation like everything else)
- `BmbFpu` / `BmbDiv` I/O peripherals → removed (replaced by named compute units)

### Update consumers

`JopCluster`, `JopSdramTop`, `JopCyc5000Top`, `JopDdr3Top`, all sim harnesses — replace:
- `fpuMode = FpuMode.Hardware` → `coreConfig.needsFloatCompute`
- `useDspMul = true` → `coreConfig.needsIntMul`
- `useHwDiv = true` → `coreConfig.needsIntDiv`
- `jumpTable = JumpTableInitData.serialHwMath` → `coreConfig.resolveJumpTable(base)`
- DivUnit instantiation → per-unit: `IntegerComputeUnit`, `FloatComputeUnit`, etc.
- Long ALU width → `coreConfig.needsLongAlu` → 64-bit ALU datapath in pipeline
- TOS register count → `coreConfig.needs4RegTos` → 4-register TOS (A/B/C/D)

The JopSdramTop constructor simplifies — no more `jumpTable`, `fpuMode`, `useDspMul`, `useHwDiv` parameters. These are all derived from `coreConfig`.

## Phase 5: Hardware Description — System / Board / FPGA / Memory / Devices

The physical hardware is modeled as a layered hierarchy:

- **Parts** — concrete components with fixed datasheet parameters (FPGA, memory, peripherals)
- **Boards** — PCBs that wire device signals to FPGA pins
- **System Assembly** — a collection of boards (no artificial limit on count or type)
- **JOP System** — a processor system targeting a specific FPGA on the assembly. Multiple JOP systems can coexist on one assembly (e.g., Wukong dual-subsystem: SDR + DDR3).

```
SystemAssembly: "wukong-dev" (the physical hardware on the desk)
  |
  +-- Board: qmtech-wukong-xc7a100t (single board with FPGA + two memories)
  |     +-- FPGA: XC7A100T-1FGG676C (Artix-7)
  |     +-- Memory: MT41K128M16JT-125:K (DDR3, 256 MB)
  |     +-- Memory: W9825G6JH6 (SDR SDRAM, 32 MB)
  |     +-- Devices: RTL8211EG (Ethernet), SD_CARD, HDMI, LED, SWITCH
  |     +-- Clock: 50 MHz oscillator
  |
  +-- JopSystem: "compute" (targets XC7A100T, uses DDR3)
  |     +-- Memory: MT41K128M16JT-125:K
  |     +-- Boot: Serial
  |     +-- Cores: 4
  |     +-- Per-core: imul=Hardware, fadd=Hardware, ...
  |     +-- Devices: RTL8211EG → BmbEthRgmii, SD_CARD → BmbSdNative
  |
  +-- JopSystem: "io" (targets same XC7A100T, uses SDR SDRAM)
  |     +-- Memory: W9825G6JH6
  |     +-- Boot: Serial
  |     +-- Cores: 2
  |     +-- Per-core: imul=Microcode, fadd=Java (minimal)
  |     +-- Devices: HDMI → BmbHdmiOut
  |
  +-- Interconnect: FIFO message queues between "compute" and "io"
  +-- Monitor: watchdog FSM (independent of both JOP systems)
```

**Simple case** (QMTECH EP4CGX150 + daughter board):

```
SystemAssembly: "qmtech-dev"
  |
  +-- Board: qmtech-ep4cgx150 (FPGA module)
  |     +-- FPGA: EP4CGX150DF27I7
  |     +-- Memory: W9825G6JH6 (SDR SDRAM)
  |     +-- Devices: 2 LEDs, 2 switches, 50 MHz oscillator
  |
  +-- Board: qmtech-fpga-db-v4 (daughter board)
  |     +-- Devices: CP2102N (UART), RTL8211EG (Ethernet), VGA, SD_CARD,
  |     |            7-segment, 5 LEDs, 5 switches, 2× PMOD
  |     +-- Connects via: J2, J3 headers
  |
  +-- JopSystem: "main"
        +-- Memory: W9825G6JH6
        +-- Boot: Serial
        +-- Cores: 1
        +-- Devices: CP2102N → BmbUart, RTL8211EG → BmbEthRgmii
```

Predefined composite boards (convenience aliases):

```scala
// A "board" can be a composite of physical boards that always go together
object Board {
  def QmtechEP4CGX150_FPGA_DB_V4 = CompositeBoard(
    Seq(Board.QmtechEP4CGX150, Board.QmtechFpgaDbV4))
}
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

### Boards — unified type with pin mappings

A board is a PCB with devices wired to an FPGA. No distinction between "FPGA board" and "carrier board" at the type level — they're all boards with devices. The FPGA lives on one board; other boards connect via headers/connectors. The assembly resolves the full pin chain.

```scala
/** A device mounted on a board with its signal-to-FPGA-pin mapping */
case class BoardDevice(
  part: String,                               // part number or device name
  role: Option[String] = None,                // optional role disambiguation ("sdram_main", "sdram_stack")
  mapping: Map[String, String] = Map.empty,   // device signal → FPGA pin (resolved through connectors)
)

/** A physical PCB — any board in the system */
case class Board(
  name: String,                    // "qmtech-ep4cgx150"
  fpga: Option[FpgaDevice] = None, // present if this board carries the FPGA
  devices: Seq[BoardDevice] = Seq.empty,
  connectors: Seq[String] = Seq.empty,  // headers/connectors for inter-board wiring
) {
  def hasFpga: Boolean = fpga.isDefined
  def allDevices: Seq[BoardDevice] = devices
}

/** Convenience: composite board = multiple physical boards treated as one */
case class CompositeBoard(boards: Seq[Board]) {
  require(boards.count(_.hasFpga) == 1, "Exactly one board must carry the FPGA")
  def fpga: FpgaDevice = boards.find(_.hasFpga).get.fpga.get
  def allDevices: Seq[BoardDevice] = boards.flatMap(_.devices)
  def name: String = boards.map(_.name).mkString("+")
}

/** System assembly — a collection of boards */
case class SystemAssembly(
  name: String,
  boards: Seq[Board],
) {
  require(boards.count(_.hasFpga) >= 1, "At least one board must carry an FPGA")
  def fpgaDevices: Seq[FpgaDevice] = boards.flatMap(_.fpga)
  def allDevices: Seq[BoardDevice] = boards.flatMap(_.devices)
  def findDevice(part: String): Option[BoardDevice] = allDevices.find(_.part == part)
  def findDeviceByRole(role: String): Option[BoardDevice] = allDevices.find(_.role.contains(role))
}
```

### Device-to-component mapping — SpinalHDL variant selection

Each physical device on a board can be driven by one or more SpinalHDL components. Some devices have multiple driver variants (e.g., SD card via SPI vs native interface). The JOP system config selects which variant to use.

```scala
/** Maps a physical device to a SpinalHDL component variant */
sealed trait DeviceDriver {
  def devicePart: String           // which physical device this drives
  def componentName: String        // SpinalHDL component class name
}

object DeviceDriver {
  // UART
  case object Uart extends DeviceDriver {
    val devicePart = "CP2102N"
    val componentName = "BmbUart"
  }

  // Ethernet — single variant (RGMII)
  case object EthRgmii extends DeviceDriver {
    val devicePart = "RTL8211EG"
    val componentName = "BmbEthRgmii"
  }

  // SD card — two variants
  case object SdSpi extends DeviceDriver {
    val devicePart = "SD_CARD"
    val componentName = "BmbSdSpi"
  }
  case object SdNative extends DeviceDriver {
    val devicePart = "SD_CARD"
    val componentName = "BmbSdNative"
  }

  // VGA — two variants
  case object VgaDma extends DeviceDriver {
    val devicePart = "VGA"
    val componentName = "BmbVgaDma"
  }
  case object VgaText extends DeviceDriver {
    val devicePart = "VGA"
    val componentName = "BmbVgaText"
  }
}
```

The `JopSystem` config references drivers by type. `JopConfig` validates that each driver's physical device exists on the assembly (see JOP System layer section below).

### Board presets

```scala
object Board {
  // --- FPGA modules ---
  def QmtechEP4CGX150 = Board(
    name = "qmtech-ep4cgx150",
    fpga = Some(FpgaDevice.EP4CGX150DF27I7),
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
      BoardDevice("SWITCH", mapping = Map("sw0" -> "PIN_AD23", "sw1" -> "PIN_AD24"))),
    connectors = Seq("J2", "J3"))

  def CYC5000 = Board(
    name = "cyc5000",
    fpga = Some(FpgaDevice.`5CEBA2F17A7`),
    devices = Seq(
      BoardDevice("W9864G6JT", mapping = Map(/* ... */)),
      BoardDevice("CLOCK_12MHz", mapping = Map(/* ... */)),
      BoardDevice("FT2232H", mapping = Map(/* JTAG + UART */)),
      BoardDevice("LED", mapping = Map(/* 5 LEDs */))))

  def AlchitryAuV2 = Board(
    name = "alchitry-au-v2",
    fpga = Some(FpgaDevice.XC7A35T),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", mapping = Map(/* DDR3 pins */)),
      BoardDevice("CLOCK_100MHz", mapping = Map(/* ... */)),
      BoardDevice("FT2232H", mapping = Map(/* JTAG + UART */))))

  // Wukong — single board with FPGA + two memory devices + peripherals
  def WukongXC7A100T = Board(
    name = "qmtech-wukong-xc7a100t",
    fpga = Some(FpgaDevice.XC7A100T),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", role = Some("ddr3"), mapping = Map(/* DDR3 pins */)),
      BoardDevice("W9825G6JH6", role = Some("sdr"), mapping = Map(/* SDR SDRAM pins */)),
      BoardDevice("RTL8211EG", mapping = Map(/* Ethernet pins */)),
      BoardDevice("SD_CARD", mapping = Map(/* SD pins */)),
      BoardDevice("HDMI", mapping = Map(/* HDMI pins */)),
      BoardDevice("CLOCK_50MHz", mapping = Map(/* ... */)),
      BoardDevice("LED", mapping = Map(/* ... */)),
      BoardDevice("SWITCH", mapping = Map(/* ... */))))

  // --- Carrier/daughter boards (no FPGA) ---
  def QmtechFpgaDbV4 = Board(
    name = "qmtech-fpga-db-v4",
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
      BoardDevice("PMOD_J11", mapping = Map(/* ... */))),
    connectors = Seq("J2", "J3"))

  // --- Composite board aliases ---
  /** QMTECH EP4CGX150 module + DB_FPGA_V4 daughter board — the standard dev setup */
  def QmtechEP4CGX150_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechEP4CGX150, QmtechFpgaDbV4)
}

object SystemAssembly {
  /** QMTECH EP4CGX150 + daughter board — primary dev platform */
  def qmtechWithDb = SystemAssembly("qmtech-ep4cgx150-db-v4",
    Board.QmtechEP4CGX150_FPGA_DB_V4)

  /** CYC5000 standalone */
  def cyc5000 = SystemAssembly("cyc5000", Seq(Board.CYC5000))

  /** Alchitry Au V2 standalone */
  def alchitryAuV2 = SystemAssembly("alchitry-au-v2", Seq(Board.AlchitryAuV2))

  /** Wukong standalone (two memories, dual-subsystem capable) */
  def wukong = SystemAssembly("wukong-xc7a100t", Seq(Board.WukongXC7A100T))
}
```

### Pin assignments — generated from board data

Pin assignments are derived from `BoardDevice.mapping`. The board data is the single source of truth — no more manually maintained `.qsf`/`.xdc` files.

Currently stored in reusable `.qsf` includes:
- `fpga/qmtech-ep4cgx150-core.qsf` — FPGA board: clock, LEDs, switches, SDRAM
- `fpga/qmtech-ep4cgx150-db.qsf` — Carrier board: UART, Ethernet, VGA, SD, 7-seg, PMODs

Migration: existing `.qsf` files can be generated from `SystemAssembly` data, or kept as hand-maintained references until generation is implemented.

### JOP System layer — processor system organization

A **JopSystem** is a processor cluster targeting a specific FPGA and memory on the assembly. Most assemblies have one JopSystem. The Wukong dual-subsystem has two, each using a different memory.

#### Memory map and I/O space

Each core sees a 32-bit address space split into two regions:

| Range | Scope | Description |
|-------|-------|-------------|
| `0x00000000`–`0xBFFFFFFF` | Per-cluster (shared) | Memory — all cores in a cluster see the same SDRAM/BRAM |
| `0xC0000000`–`0xFFFFFFFF` | Per-core (private) | I/O — each core has its own devices, independently addressed |

Because I/O is per-core, two cores can both have `uart0` at address 0xEE without conflict — they are separate physical UART instances in separate address spaces. A core that doesn't have a particular device simply has dead space at that address.

#### Device scopes

I/O devices exist at three levels:

| Scope | Instantiation | Example |
|-------|--------------|---------|
| **Per-core** | Each core has its own instance in its private I/O space | UART, SPI, I2C |
| **Per-cluster** | Shared across cores in a cluster, BMB-arbitrated | Shared timer |
| **System-level** | Shared across the whole device | Boot device (cluster 0/core 0), watchdog FSM, inter-cluster FIFO |

Per-core devices are instantiated per-core with independent address allocation. Per-cluster and system-level devices require BMB bus arbitration for concurrent access from multiple cores.

> **Note:** Only single-cluster systems are supported initially. Multi-cluster support (inter-cluster FIFO, cross-cluster boot) is modeled in the config but not yet implemented.

#### Boot model

The boot device (UART or config flash) exists only on **cluster 0, core 0**. It is a system-level resource, not per-cluster.

- **Cluster 0, core 0** owns the boot device: serial download (development) or config flash read (production)
- Other cores in cluster 0 wait until boot completes
- In multi-cluster systems, cluster 0/core 0 loads other clusters' `.jop` images over a FIFO channel from config flash (different flash offsets per cluster). Cluster 1/core 0 receives via FIFO and loads its cluster's memory.
- Config flash is the presumed boot device for production JOP systems

The boot device occupies the fixed address 0xEE–0xEF in cluster 0/core 0's I/O space. UART and config flash are mutually exclusive boot devices sharing this address.

#### Named device instances

Devices are declared as named instances with a type and connector mapping, not boolean flags. This supports multiples of the same type (e.g., two UARTs, three SPIs):

```scala
/** A named device instance within a core or cluster */
case class DeviceInstance(
  deviceType: String,                            // "uart", "sdspi", "sdnative", "i2c", "spi", "ethernet"
  mapping: Map[String, String] = Map.empty,      // signal → board connector pin (e.g., "txd" -> "j10.1")
  params: Map[String, Any] = Map.empty,          // device-specific parameters
)
```

The `mapping` connects FPGA device signals to board connectors/headers. `"txd" -> "j10.1"` means "route uart0's txd to PMOD header J10 pin 1". The board definition separately maps `j10.1` to a physical FPGA pin — the device config doesn't know or care about FPGA pin numbers.

The `params` map holds device-specific configuration: baud rate for UART, clock divider for SPI, etc. The device factory reads these at elaboration time.

#### Device type registry

Device types map to `HasBusIo` factories. New device types are registered once; instances are created by name:

```scala
/** Registry of device types → factories */
object DeviceTypes {
  val registry: Map[String, DeviceTypeInfo] = Map(
    "uart"     -> DeviceTypeInfo(addrBits = 1, interruptCount = 2,
                    factory = (c, p) => Uart(p.getOrElse("baudRate", c.uartBaudRate).asInstanceOf[Int], c.clkFreq)),
    "sdspi"    -> DeviceTypeInfo(addrBits = 2, interruptCount = 1,
                    factory = (c, p) => SdSpi(p.getOrElse("clkDivInit", 199).asInstanceOf[Int])),
    "sdnative" -> DeviceTypeInfo(addrBits = 4, interruptCount = 1,
                    factory = (c, p) => SdNative(p.getOrElse("clkDivInit", 99).asInstanceOf[Int])),
    "ethernet" -> DeviceTypeInfo(addrBits = 4, interruptCount = 1,
                    factory = (c, p) => Eth(/* clock domains from context */)),
    "spi"      -> DeviceTypeInfo(addrBits = 2, interruptCount = 1,
                    factory = (c, p) => GenericSpi(/* params */)),
    "i2c"      -> DeviceTypeInfo(addrBits = 2, interruptCount = 1,
                    factory = (c, p) => GenericI2c(/* params */)),
    "cfgflash" -> DeviceTypeInfo(addrBits = 1, interruptCount = 0,
                    factory = (c, p) => ConfigFlash(p.getOrElse("clkDivInit", 3).asInstanceOf[Int])),
  )
}
```

#### Per-core configuration

Each core declares its own devices and bytecode implementations:

```scala
/** Per-core configuration: bytecodes + I/O devices */
case class CoreConfig(
  bytecodes: BytecodeConfig = BytecodeConfig(),
  devices: Map[String, DeviceInstance] = Map.empty,  // name → instance
)

/** Bytecode implementation choices (compact form) */
case class BytecodeConfig(
  imul: Implementation = Implementation.Microcode,
  idiv: Implementation = Implementation.Java,
  irem: Implementation = Implementation.Java,
  // Long arithmetic
  ladd: Implementation = Implementation.Microcode,
  lsub: Implementation = Implementation.Microcode,
  // ... etc for all configurable bytecodes
  // Float
  fadd: Implementation = Implementation.Java,
  // ... etc
) {
  // Wildcard convenience: "i*:hw" sets all integer ops to Hardware
  def withIntegerHw: BytecodeConfig = copy(imul = Implementation.Hardware, idiv = Implementation.Hardware, irem = Implementation.Hardware)
  def withFloatHw: BytecodeConfig = copy(fadd = Implementation.Hardware, /* ... */)
  def withLongHw: BytecodeConfig = copy(ladd = Implementation.Hardware, lsub = Implementation.Hardware, /* ... */)
  def withDoubleHw: BytecodeConfig = copy(/* ... */)
  def withDspMul: BytecodeConfig = copy(imul = Implementation.HardwareDsp)
}
```

#### Cluster configuration

A cluster is a group of cores sharing a memory controller. Each cluster has its own memory type, arbiter, and optional cluster-level devices:

```scala
sealed trait BootMode { def dirName: String }
object BootMode {
  case object Serial extends BootMode     { val dirName = "serial" }
  case object Flash extends BootMode      { val dirName = "flash" }
  case object Simulation extends BootMode { val dirName = "simulation" }
}

sealed trait ArbiterType
object ArbiterType {
  case object RoundRobin extends ArbiterType
  case object Tdma extends ArbiterType
}

/** A cluster of cores sharing a memory controller and a single .jop binary.
  * Boot device is system-level (cluster 0/core 0 only). Other clusters receive
  * their .jop via FIFO from cluster 0. */
case class ClusterConfig(
  name: String,
  memory: String,                                    // memory device (by part or role)
  arbiterType: ArbiterType = ArbiterType.RoundRobin,
  clkFreqHz: Long,
  cores: Seq[CoreConfig],                            // per-core config (length = core count)
  clusterDevices: Map[String, DeviceInstance] = Map.empty,  // cluster-level shared devices
  flashOffset: Long = 0L,                            // .jop offset in config flash (multi-cluster)
) {
  def cpuCnt: Int = cores.length

  // Superset of all cores' device descriptors (for address allocation + Const.java)
  def allDeviceTypes: Set[String] =
    cores.flatMap(_.devices.values.map(_.deviceType)).toSet
}
```

#### Top-level system configuration

```scala
/** Top-level configuration — assembly + boot + clusters + system-level devices.
  * Boot device (UART or cfgFlash) is system-level, owned by cluster 0/core 0.
  * Watchdog FSM monitors the whole device (system-level). */
case class JopConfig(
  assembly: SystemAssembly,
  bootMode: BootMode,                                        // Serial/Flash/Simulation (system-level)
  clusters: Seq[ClusterConfig],
  systemDevices: Map[String, DeviceInstance] = Map.empty,    // boot device, watchdog FSM
  interconnect: Option[InterconnectConfig] = None,           // cross-cluster FIFOs
) {
  require(clusters.nonEmpty, "At least one cluster required")

  // Single-cluster convenience
  def cluster: ClusterConfig = {
    require(clusters.length == 1, "Use .clusters for multi-cluster configs")
    clusters.head
  }

  // --- Derived ---
  def fpgaFamily: FpgaFamily = assembly.fpgaDevices.head.family
  def romPath: String = s"asm/generated/${bootMode.dirName}/mem_rom.dat"
  def ramPath: String = s"asm/generated/${bootMode.dirName}/mem_ram.dat"

  // Validate: each cluster's memory must exist on the assembly
  clusters.foreach { cl =>
    require(
      assembly.findDevice(cl.memory).isDefined ||
      assembly.findDeviceByRole(cl.memory).isDefined,
      s"Cluster '${cl.name}' references memory '${cl.memory}' " +
      s"but assembly '${assembly.name}' has no such device")
  }

  // Collect all device instances across all scopes for pin mapping validation
  def allDeviceInstances: Seq[(String, DeviceInstance)] =
    clusters.flatMap(cl =>
      cl.cores.flatMap(_.devices.toSeq) ++
      cl.clusterDevices.toSeq
    ) ++ systemDevices.toSeq
}

/** Cross-cluster interconnect */
case class InterconnectConfig(
  fifoDepth: Int = 16,
  dataWidth: Int = 32,
)

/** Hardware monitor */
sealed trait MonitorConfig
case class WatchdogConfig(timeoutMs: Int = 1000) extends MonitorConfig
```

#### Address allocation

The `IoAddressAllocator` runs once per cluster on the **superset** (union) of all cores' device descriptors. This ensures every device type gets the same address regardless of which core it's on — necessary because all cores in a cluster share one `.jop` binary and one `Const.java`.

A core that doesn't have a particular device simply has dead space at that address. Software uses the per-core existence flags in `Const.java` (or the Sys CPU ID register) to know which devices are available.

```
Per-core I/O address space (0x80-0xFF):
  0xF0-0xFF  Sys (always present, 16 addrs)
  0xEE-0xEF  Boot device (UART or cfgFlash, core 0 only, fixed)
  0xED down  Per-core devices (auto-allocated from superset, largest first)

Cluster-level devices: separate BMB address range (memory-mapped, not I/O space)
System-level devices: separate BMB address range
```

#### Generated constants (Const.java)

One `Const.java` is generated **per cluster** with flat `static final int` constants. Device names include instance numbers:

```java
// Generated: Cluster 0 Const.java — system devices + superset of cluster's per-core devices
public interface Const {
    int IO_BASE = -128;

    // System-level boot device (cluster 0/core 0 only, fixed at 0xEE)
    int BOOT_STATUS = IO_BASE + 0x6E;  // -18
    int BOOT_DATA   = IO_BASE + 0x6F;  // -17

    // Per-core devices (superset allocation — not all exist on every core)
    int ETH0_STATUS  = IO_BASE + 0x68;
    int ETH0_CMD     = IO_BASE + 0x69;
    // ...
    int SD0_STATUS   = IO_BASE + 0x64;
    int SD0_DATA     = IO_BASE + 0x65;
    // ...

    // Per-core existence flags
    boolean CORE0_HAS_ETH0 = true;
    boolean CORE1_HAS_ETH0 = false;
}
```

All constants are compile-time (`bipush` operands for `Native.rd`/`Native.wr`). No runtime indexing — the flat constants are zero-cost on the JOP stack architecture. Most boards have a single UART (the boot device on core 0); other cores communicate through core 0 via shared memory.

Each cluster gets its own `Const.java` and `.jop` binary compiled against it. In multi-cluster systems, cluster 0 loads other clusters' `.jop` from config flash at different offsets. Cluster 1's `Const.java` would not include boot device constants (it receives its `.jop` via FIFO, not directly from flash).

### System presets — builder pattern with `copy()`

```scala
object JopConfig {
  // Common core configs (no boot device — that's system-level)
  val bareCore = CoreConfig()  // no devices — communicates through core 0 via shared memory

  val hwMathCore = CoreConfig(
    bytecodes = BytecodeConfig()
      .withDspMul.withIntegerHw.withFloatHw.withLongHw.withDoubleHw)

  // --- Single-cluster presets (common case) ---

  /** QMTECH EP4CGX150 + daughter board — primary dev platform */
  def qmtechSerial = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Serial,
    systemDevices = Map(
      "uart0" -> DeviceInstance("uart", Map("txd" -> "db.uart_txd", "rxd" -> "db.uart_rxd"))),
    clusters = Seq(ClusterConfig(
      name = "main",
      memory = "W9825G6JH6",
      clkFreqHz = 80000000L,
      cores = Seq(CoreConfig(
        devices = Map(
          "eth0" -> DeviceInstance("ethernet", Map("mdc" -> "db.eth_mdc", "mdio" -> "db.eth_mdio" /*, ... */)),
          "sd0"  -> DeviceInstance("sdspi", Map("sclk" -> "db.sd_clk", "mosi" -> "db.sd_cmd" /*, ... */))))))))

  /** SMP: N cores — core 0 has eth + sd, others bare.
    * Boot UART is system-level (cluster 0/core 0). Most boards have 1 UART;
    * other cores communicate through core 0 via shared memory.
    * Optional: extra UARTs on PMOD headers for cores that need them. */
  def qmtechSmp(n: Int) = {
    val base = qmtechSerial
    val core0 = base.cluster.cores.head  // has eth0, sd0
    base.copy(clusters = Seq(base.cluster.copy(
      name = s"smp$n",
      cores = core0 +: Seq.fill(n - 1)(bareCore))))
  }

  /** SMP with per-core UARTs on PMOD headers (optional, for debugging) */
  def qmtechSmpWithUarts(n: Int) = {
    val base = qmtechSerial
    val core0 = base.cluster.cores.head
    val extras = (1 until n).map { i =>
      CoreConfig(devices = Map(
        "uart0" -> DeviceInstance("uart", Map("txd" -> s"j11.${i*2+1}", "rxd" -> s"j11.${i*2+2}"))))
    }
    base.copy(clusters = Seq(base.cluster.copy(name = s"smp$n", cores = core0 +: extras)))
  }

  /** CYC5000 standalone */
  def cyc5000Serial = JopConfig(
    assembly = SystemAssembly.cyc5000,
    bootMode = BootMode.Serial,
    systemDevices = Map("uart0" -> DeviceInstance("uart")),
    clusters = Seq(ClusterConfig(
      name = "main",
      memory = "W9864G6JT",
      clkFreqHz = 100000000L,
      cores = Seq(bareCore))))

  /** Alchitry Au V2 with flash boot */
  def auFlash = JopConfig(
    assembly = SystemAssembly.alchitryAuV2,
    bootMode = BootMode.Flash,
    systemDevices = Map(
      "uart0"    -> DeviceInstance("uart"),
      "cfgflash" -> DeviceInstance("cfgflash", params = Map("clkDivInit" -> 15))),
    clusters = Seq(ClusterConfig(
      name = "main",
      memory = "MT41K128M16JT-125:K",
      clkFreqHz = 83333333L,
      cores = Seq(bareCore))))

  /** Simulation (no physical board) */
  def simulation = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Simulation,
    systemDevices = Map("uart0" -> DeviceInstance("uart")),
    clusters = Seq(ClusterConfig(
      name = "sim",
      memory = "W9825G6JH6",
      clkFreqHz = 100000000L,
      cores = Seq(bareCore))))

  // --- Multi-cluster preset (Wukong dual-subsystem) ---
  // Boot device (cfgFlash) is system-level, owned by cluster 0/core 0.
  // Cluster 0 loads cluster 1's .jop over FIFO channel from config flash.
  // Each cluster has its own flash offset and its own Const.java / .jop binary.
  // Watchdog FSM monitors the whole device (system-level).
  // NOTE: multi-cluster not yet implemented, modeled here for future support.

  /** Wukong: heavy compute on DDR3 + light I/O on SDR SDRAM */
  def wukongDual = JopConfig(
    assembly = SystemAssembly.wukong,
    bootMode = BootMode.Flash,
    systemDevices = Map(
      "uart0"    -> DeviceInstance("uart"),
      "cfgflash" -> DeviceInstance("cfgflash"),
      "watchdog" -> DeviceInstance("watchdog", params = Map("timeoutMs" -> 2000))),
    clusters = Seq(
      ClusterConfig(
        name = "compute",
        memory = "ddr3",
        clkFreqHz = 100000000L,
        flashOffset = 0x800000L,          // cluster 0 .jop at 8 MB
        cores = CoreConfig(devices = Map( // core 0: peripherals
          "eth0" -> DeviceInstance("ethernet"),
          "sd0"  -> DeviceInstance("sdnative")))
          +: Seq.fill(3)(hwMathCore)),    // cores 1-3: compute only
      ClusterConfig(
        name = "io",
        memory = "sdr",
        clkFreqHz = 50000000L,
        flashOffset = 0xC00000L,          // cluster 1 .jop at 12 MB
        cores = Seq.fill(2)(bareCore))),  // receives .jop via FIFO from cluster 0
    interconnect = Some(InterconnectConfig(fifoDepth = 64)))

  // --- Minimum resource preset (fits MAX1000, small Lattice, etc.) ---

  def minimum = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Serial,
    systemDevices = Map("uart0" -> DeviceInstance("uart")),
    clusters = Seq(ClusterConfig(
      name = "min",
      memory = "W9825G6JH6",
      clkFreqHz = 80000000L,
      cores = Seq(CoreConfig(
        bytecodes = BytecodeConfig(imul = Implementation.Microcode))))))
}
```

### Migration from current IoConfig

The current `IoConfig` with boolean flags (`hasUart`, `hasEth`, etc.) maps to the new model as follows:

| Current (IoConfig) | New (per-core DeviceInstance) |
|---|---|
| `hasUart = true` | `"uart0" -> DeviceInstance("uart")` |
| `hasEth = true, ethGmii = true` | `"eth0" -> DeviceInstance("ethernet", params = Map("gmii" -> true))` |
| `hasSdSpi = true` | `"sd0" -> DeviceInstance("sdspi")` |
| `hasSdNative = true` | `"sd0" -> DeviceInstance("sdnative")` |
| `hasVgaText = true` | `"vga0" -> DeviceInstance("vgatext")` |
| `hasConfigFlash = true` | `"cfgflash" -> DeviceInstance("cfgflash")` |

The `IoAddressAllocator` and `HasBusIo` infrastructure remain unchanged — they already work with named devices and dynamic allocation. The change is in how device lists are built: from boolean flags on a global `IoConfig` to named instances on each `CoreConfig`.

**ConstGenerator migration:** Currently generates one `Const.java` from a superset `IoConfig`. New model generates one `Const.java` per cluster from the superset of all cores' device descriptors within that cluster. Device constants use named format (`ETH0_STATUS`, `SD0_DATA`) and include per-core existence flags (`CORE0_HAS_ETH0`).

**Incremental migration path:**
1. Add `DeviceInstance`, `DeviceTypes` registry, and `CoreConfig` types alongside existing `IoConfig`
2. Add `effectiveDeviceDescriptors()` on `JopCoreConfig` — falls back to `IoConfig` when `devices` is empty
3. Wire `JopCore` through `effectiveDeviceDescriptors()` (no behavior change)
4. Add `IoConfig.toDevices()` bridge and unit tests verifying both paths produce identical descriptors
5. Add query methods (`hasDevice()`, `hasEth`, etc.) that work on either path
6. Migrate `JopCluster` per-core device restriction to `restrictToCore()` method
7. Migrate presets one at a time (pilot: ep4cgx150Serial, then simple→complex order)
8. Migrate `ConstGenerator` to per-cluster superset generation with named constants
9. Migrate top-level conditional ports to query methods
10. Merge `DeviceDriver` pin mapping into `DeviceInstance.mapping`
11. Remove `IoConfig` boolean flags, `DeviceDriver`, and backward-compat fallbacks

### What the config drives

The `JopConfig` determines everything downstream:

| Layer | Derived from | Output |
|-------|-------------|--------|
| **Memory controller** | `JopSystem.memory` part → MemoryDevice.memType | SDR→BmbSdramCtrl32, DDR3→BmbCacheBridge+MIG, BRAM→BmbOnChipRam |
| **Top-level I/O ports** | Assembly board devices + system drivers | Only ports for devices with active drivers |
| **PLL configuration** | Board clock device + JopSystem.clkFreqHz | Input freq → output freq |
| **FPGA family** | Board FPGA device → FpgaFamily | Synthesis tool, DSP type, memory primitives |
| **Device drivers** | JopSystem.drivers | SpinalHDL component instantiation + pin wiring |
| **Pin assignments** | BoardDevice.mapping for each active driver | .qsf/.xdc constraint files |
| **Microcode ROM** | JopSystem.bootMode + coreConfig | ROM path, jump table, gcc -D flags |
| **JOPizer config** | coreConfig bytecodes | IMP_ASM/IMP_JAVA per bytecode |
| **Runtime modules** | coreConfig (SoftFloat needs) + assembly devices | Which .java files to compile |
| **Interconnect** | JopConfig.interconnect (multi-system only) | FIFO instantiation + arbitration |

Currently this mapping is implicit in separate top-level files (`JopSdramTop`, `JopDdr3Top`, `JopCyc5000Top`). With `JopConfig`, a single generic top-level dispatches based on config. Migration path: keep existing top-level files but have them read from `JopConfig` instead of manual params.

### Unified generation entry point

**File:** `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` (new)

Single `sbt runMain jop.system.JopGenerate <preset>` replaces 20 separate entry points. Old `object JopSdramTopVerilog extends App` etc. become thin wrappers for backward compat.

## Phase 6: Board-Specific Modular Java Runtime

### Current state

The runtime is 61 hand-curated files — minimal JDK stubs (25 `java.lang`, 4 `java.io`, 0 `java.util`), 10 HW drivers, 15 system classes. Everything is compiled together regardless of target hardware. Configuration is scattered:

- `Const.java`: `SUPPORT_FLOAT = true`, `SUPPORT_DOUBLE = true` — manual boolean flags
- `Const.java`: `IO_FPU = IO_BASE+0x70`, `IO_DIV = IO_BASE+0x60` — hardcoded I/O addresses
- `JVM.java`: `f_fadd()` → `if (Const.SUPPORT_FLOAT) SoftFloat32.float_add(a,b)` — compile-time branching
- `com/jopdesign/hw/`: All drivers always compiled, even if carrier board lacks the device
- No `java.util` (no collections, no HashMap/ArrayList) — severely limits application development
- JDK stubs are minimal reimplementations, not sourced from an actual JDK

### Vision: board-specific runtime JAR from modular components

The runtime is assembled from **modules** selected by `JopConfig`. Each board gets a tailored runtime JAR containing exactly the JDK classes, software fallbacks, and device drivers it needs.

```
JopConfig
  |
  +--→ Core modules (always included)
  |      jop.sys: Startup, Native, GC, JVM, JVMHelp, Memory, Scheduler
  |      java.lang: Object, String, Throwable, Integer, Long, ...
  |      java.io: InputStream, OutputStream, PrintStream, IOException
  |
  +--→ JDK library modules (optional, from OpenJDK 6)
  |      java.util: Collections, HashMap, ArrayList, LinkedList, ...
  |      java.util.regex: Pattern, Matcher
  |      java.text: NumberFormat, DateFormat, ...
  |      java.math: BigInteger, BigDecimal
  |      java.net: URL, Socket, InetAddress (requires networking HW)
  |
  +--→ Software math modules (from core config)
  |      SoftFloat32: included if any core has float bytecodes = Java
  |      SoftFloat64: included if any core has double bytecodes = Java
  |
  +--→ Device driver modules (from board devices)
  |      SerialPort: always (boot requires UART)
  |      EthMac + Mdio: board has RTL8211EG
  |      SdNative: board has native SD interface
  |      SdSpi: board has SPI SD interface
  |      VgaDma: board has DMA VGA controller
  |      VgaText: board has text-mode VGA
  |
  +--→ Application library modules (optional)
         fat32: FAT32 filesystem (requires SD driver)
         networking: TCP/IP stack (requires Ethernet driver)
         ... future modules as needed
```

### Module structure

```scala
sealed trait RuntimeModule {
  def name: String
  def sourcePaths: Seq[String]          // .java source dirs/files
  def dependencies: Seq[RuntimeModule]   // required modules
}

object RuntimeModule {
  // --- Core (always included) ---
  case object Core extends RuntimeModule {
    val name = "core"
    val sourcePaths = Seq("src/jop/com/jopdesign/sys/", "src/jvm/java/lang/", "src/jvm/java/io/")
    val dependencies = Seq.empty
  }

  // --- JDK library modules ---
  case object Collections extends RuntimeModule {
    val name = "collections"
    val sourcePaths = Seq("src/jdk/java/util/")
    val dependencies = Seq(Core)
  }

  case object Regex extends RuntimeModule {
    val name = "regex"
    val sourcePaths = Seq("src/jdk/java/util/regex/")
    val dependencies = Seq(Core, Collections)
  }

  case object BigMath extends RuntimeModule {
    val name = "bigmath"
    val sourcePaths = Seq("src/jdk/java/math/")
    val dependencies = Seq(Core)
  }

  // --- Software math fallbacks ---
  case object SoftFloat32 extends RuntimeModule {
    val name = "softfloat32"
    val sourcePaths = Seq("src/jop/com/jopdesign/sys/SoftFloat32.java")
    val dependencies = Seq(Core)
  }

  case object SoftFloat64 extends RuntimeModule {
    val name = "softfloat64"
    val sourcePaths = Seq("src/jop/com/jopdesign/sys/SoftFloat64.java")
    val dependencies = Seq(Core)
  }

  // --- Device drivers (one per concrete device) ---
  case object EthernetMac extends RuntimeModule {
    val name = "ethernet"
    val sourcePaths = Seq("src/jop/com/jopdesign/hw/EthMac.java",
                          "src/jop/com/jopdesign/hw/Mdio.java")
    val dependencies = Seq(Core)
  }

  case object SdNative extends RuntimeModule {
    val name = "sd-native"
    val sourcePaths = Seq("src/jop/com/jopdesign/hw/SdNative.java")
    val dependencies = Seq(Core)
  }

  // --- Application libraries ---
  case object Fat32 extends RuntimeModule {
    val name = "fat32"
    val sourcePaths = Seq("src/lib/fat32/")
    val dependencies = Seq(Core)  // requires an SD driver at runtime
  }

  case object Networking extends RuntimeModule {
    val name = "networking"
    val sourcePaths = Seq("src/lib/networking/", "src/jdk/java/net/")
    val dependencies = Seq(Core, EthernetMac)
  }
}
```

### Module selection from config

```scala
def resolveModules(config: JopConfig): Set[RuntimeModule] = {
  val modules = mutable.Set[RuntimeModule](RuntimeModule.Core)

  // Software math — from union of all systems' cores' configs
  val allCores = config.systems.flatMap(_.coreConfigs)
  if (allCores.exists(c => Seq(c.fadd, c.fsub, c.fmul, c.fdiv).contains(Implementation.Java)))
    modules += RuntimeModule.SoftFloat32
  if (allCores.exists(c => Seq(c.dadd, c.dsub, c.dmul, c.ddiv).contains(Implementation.Java)))
    modules += RuntimeModule.SoftFloat64

  // Device drivers — from active drivers across all systems (not all board devices)
  val allDrivers = config.systems.flatMap(_.drivers)
  allDrivers.foreach {
    case DeviceDriver.EthRgmii => modules += RuntimeModule.EthernetMac
    case DeviceDriver.SdNative => modules += RuntimeModule.SdNative
    case DeviceDriver.SdSpi    => modules += RuntimeModule.SdSpi
    case DeviceDriver.VgaDma   => modules += RuntimeModule.VgaDma
    case DeviceDriver.VgaText  => modules += RuntimeModule.VgaText
    case _ =>  // Uart is always included via Core
  }

  // Application libraries — explicitly requested
  config.requestedModules.foreach(modules += _)

  // Resolve transitive dependencies
  closeDependencies(modules)
}
```

### JDK class library — sourced from OpenJDK 6

**Current**: JDK stubs are minimal reimplementations (e.g., `String.java` in 300 lines). Missing entire packages (`java.util`, `java.text`, `java.math`, `java.net`).

**Source**: OpenJDK 6 at `/srv/git/java/jdk6/jdk/src/share/classes/` — 12,555 files, complete JDK 1.6 class library. Unmodified official source.

**Approach**: Create a JOP-specific JDK module tree sourced from OpenJDK 6, adapted for JOP's constraints:

```
java/runtime/src/jdk/           # JDK classes adapted for JOP
  java/util/                     # From OpenJDK 6 + JOP adaptations
    ArrayList.java
    HashMap.java
    LinkedList.java
    Collections.java
    Iterator.java
    ...
  java/math/
    BigInteger.java
    BigDecimal.java
    ...
  java/text/
    NumberFormat.java
    ...
  java/net/                      # Only when networking module selected
    URL.java
    Socket.java
    InetAddress.java
    ...
```

**Adaptations needed** (separate project/effort):
- Remove native method calls that assume HotSpot (replace with JOP `Native` equivalents or pure Java)
- Remove `sun.*` internal dependencies where possible
- Remove threading assumptions that don't match JOP's cooperative scheduling
- Remove `SecurityManager` checks (JOP has no security manager)
- Ensure all classes pass through JOPizer without `invokedynamic` or other unsupported bytecodes
- Class file version: must compile with `-source 1.6 -target 1.6`
- Test incrementally: add one package at a time, verify with JVM test suite

**Priority order** for JDK packages:
1. `java.util` (Collections) — highest impact, most applications need this
2. `java.math` (BigInteger/BigDecimal) — useful for crypto, financial
3. `java.text` (formatting) — useful for string processing
4. `java.net` (networking) — requires Ethernet HW driver + TCP/IP stack
5. `java.util.regex` — pattern matching, depends on collections

This is a **separate ongoing effort** — not blocking the configuration-driven design. Each JDK module is added independently and tested.

### 1. Const.java generation

```java
// Generated from JopConfig — do not edit manually
public class Const {
  // From IoConfig
  public static final int IO_BASE  = -128;
  // Note: IO_FPU and IO_DIV no longer needed — compute units are pipeline
  // components accessed via sthw, not memory-mapped I/O peripherals.

  // From JopCoreConfig (union of all cores' capabilities)
  public static final boolean SUPPORT_FLOAT  = true;   // any core has fadd/fsub/... != Java
  public static final boolean SUPPORT_DOUBLE = true;    // any core has dadd/dsub/... != Java

  // From SystemAssembly (board devices)
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

### 3. Device driver inclusion

Driver inclusion matches the **exact hardware** on the board. A device with multiple driver implementations (e.g., SD card: native vs SPI) only includes the driver that matches the actual hardware interface. The board definition specifies the concrete device, and each device maps to exactly one driver.

| Board Device | Java Driver | Included When |
|-------------|-------------|---------------|
| CP2102N | `SerialPort.java` | Always (boot requires UART) |
| RTL8211EG | `EthMac.java`, `Mdio.java` | Board has RTL8211EG |
| SD_NATIVE | `SdNative.java` | Board has native SD interface |
| SD_SPI | `SdSpi.java` | Board has SPI SD interface |
| VGA_DMA | `VgaDma.java` | Board has DMA VGA controller |
| VGA_TEXT | `VgaText.java` | Board has text-mode VGA |

Each `BoardDevice` entry in the system assembly maps to a specific driver — not a category of drivers. If the carrier board has a native SD interface, only `SdNative.java` is included. If a different board uses SPI for SD, only `SdSpi.java` is included. Both are never included unless the system physically has both interfaces.

### 4. Build integration

```makefile
# Build board-specific runtime JAR from system config
generate-runtime:
    sbt "runMain jop.system.GenerateRuntime qmtech-serial"
    # Outputs: resolved module list, generated Const.java, javac source paths
    cd java && make runtime SYSTEM=qmtech-serial
```

The build collects source paths from all resolved modules, compiles with `javac6 -source 1.6 -target 1.6`, and produces a board-specific runtime. Different board configs produce different JARs — a CYC5000 without Ethernet gets no `EthMac.java`, no `Networking` module, smaller .jop file.

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
| 5 | 4 | Add Implementation enum, per-instruction config, derived methods | `JopCore.scala` |
| 6 | 4 | Update JopCluster/JopSdramTop/JopCyc5000Top to use derived flags | top-level files |
| 7 | 4 | Update all sim harnesses | `src/test/scala/jop/system/*.scala` |
| 8 | 4 | Delete old FpuMode, old JumpTableData variants, old variant dirs | cleanup |
| 9 | 5 | Add Board, SystemAssembly, DeviceDriver, JopSystem, JopConfig, JopGenerate | new files |
| 10 | 5 | Convert old entry points to thin wrappers | top-level files |
| 10a | 5 | Restructure jvm.asm: both imul handlers unconditional, eliminate bare builds | `asm/src/jvm.asm` |
| 11 | 6 | Runtime module system, Const.java generation, board-specific build | `java/runtime/`, build scripts |
| 12 | 6+ | JDK class library from OpenJDK 6 (separate ongoing effort) | `java/runtime/src/jdk/` |
| 13 | 6+ | Application libraries: FAT32, networking, etc. | `java/runtime/src/lib/` |
| 14 | 8 | Build library (`jop-build`): orchestrator, builders, config hash | `java/tools/src/.../build/` |
| 15 | 8 | CLI front-end (`jop` command) wrapping build library | `java/tools/src/.../build/cli/` |
| 16 | 9 | Eclipse plugin: project wizard, builder, download action | Eclipse plugin project |
| 17 | 9 | DAP debug adapter (IDE-agnostic, shared by Eclipse/VS Code) | `java/tools/src/.../build/dap/` |
| 18 | 10 | Config validation, resource estimation, WCET timing model | cross-cutting |
| 19 | — | Full verification | all sims + FPGA + CLI + IDE workflow |

## Files to Modify

| File | Action |
|------|--------|
| `java/tools/src/com/jopdesign/tools/Jopa.java` | Remove 2 outputs, add `-n`, generate `extends JumpTableSource` |
| `asm/Makefile` | 3 superset targets (was 12 variant targets) |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` | `disable()`, simplified factory methods |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTableSource.scala` | **New**: trait for generated objects |
| `spinalhdl/src/main/scala/jop/system/JopCore.scala` | Per-instruction config, derived flags, delete old |
| `spinalhdl/src/main/scala/jop/system/JopConfig.scala` | **New**: JopSystem, JopConfig, InterconnectConfig |
| `spinalhdl/src/main/scala/jop/system/Board.scala` | **New**: Board, BoardDevice, SystemAssembly, DeviceDriver |
| `spinalhdl/src/main/scala/jop/system/Parts.scala` | **New**: FpgaDevice, MemoryDevice, FpgaFamily |
| `spinalhdl/src/main/scala/jop/system/JopSdramTop.scala` | Use derived flags, read from JopConfig |
| `spinalhdl/src/main/scala/jop/system/JopCluster.scala` | Accept resolved jumpTable, per-core configs |
| `spinalhdl/src/main/scala/jop/system/JopCyc5000Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` | **New**: unified Verilog entry point |
| `spinalhdl/src/main/scala/jop/system/GenerateRuntime.scala` | **New**: generates Const.java from config |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | Generated from config (was manual) |
| `java/runtime/src/jdk/` | **New**: JDK classes from OpenJDK 6, adapted for JOP |
| `java/runtime/src/lib/` | **New**: Application libraries (FAT32, networking, etc.) |
| `java/Makefile` | Board-specific runtime build with module selection |
| `java/tools/src/com/jopdesign/build/` | **New**: shared build library (orchestrator, builders, config, listeners, test runner) |
| `java/tools/src/com/jopdesign/build/cli/` | **New**: `jop` CLI front-end (thin wrapper over build library) |
| `java/tools/src/com/jopdesign/build/dap/` | **New**: DAP debug adapter (IDE-agnostic debug server) |
| `java/tools/src/com/jopdesign/build/FpgaTestRunner.java` | **New**: FPGA hardware test (upload, UART capture, result parsing) |
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
10. `jop build qmtech-serial` — CLI full build produces identical artifacts to manual make
11. `jop build --app Smallest` — inner loop app build matches manual javac + JOPizer
12. `jop test --suite` — FPGA hardware test: 59/60 pass, matches simulation results
13. `jop test HelloWorld.jop --expect "Hello World!"` — single-app FPGA test passes
14. Eclipse project import → save .java → auto-build → .jop matches CLI output
15. Eclipse "Test on FPGA" button → JUnit-style results match CLI output

---

## Phase 8: Build System

### Problem

Today the build is spread across 4 directories with independent Makefiles:
- `asm/Makefile` — microcode assembly (gcc + jopa → ROM/RAM)
- `java/Makefile` — tools + runtime + apps (javac + PreLinker + JOPizer → .jop)
- `spinalhdl/` via sbt — SpinalHDL elaboration (→ Verilog)
- `fpga/*/Makefile` — FPGA synthesis (Quartus/Vivado → bitstream)

A developer must run these in the right order, with the right parameters, and keep them in sync manually. Change a config? Rebuild microcode, regenerate Const.java, recompile runtime, re-elaborate Verilog, re-synthesize, relink the .jop. Miss a step and you get silent corruption.

### Architecture: Shared Build Library + Multiple Front-Ends

The build system is a **Java library** (`jop-build`) that encodes the full dependency chain and build logic. Both the CLI and Eclipse call the same library — no duplicated build logic.

```
                    +-----------+      +------------------+
                    |  jop CLI  |      | Eclipse plugin   |
                    | (thin)    |      | (thin)           |
                    +-----+-----+      +--------+---------+
                          |                     |
                          v                     v
                 +----------------------------------+
                 |       jop-build library           |
                 |  (Java, all build logic lives here)|
                 +----------------------------------+
                 |  BuildConfig     — parse JSON     |
                 |  MicrocodeBuilder — [1] asm       |
                 |  RuntimeBuilder  — [2] javac      |
                 |  VerilogBuilder  — [3] sbt        |
                 |  FpgaBuilder     — [4] synth      |
                 |  AppBuilder      — [5] .jop       |
                 |  Downloader      — [6] serial     |
                 |  BuildOrchestrator — dependency    |
                 |                     graph + cache  |
                 +----------------------------------+
                          |
                          v
                  Makefile / sbt / Quartus / Vivado
                  (actual tool invocations)
```

**Key principle:** The library is the authority. The CLI and Eclipse plugin are just ways to invoke it. A build triggered from Eclipse produces identical artifacts to the same build from CLI.

### Full dependency chain

```
JopConfig (single source of truth)
  |
  |  [1] Microcode assembly (once per boot mode, rarely changes)
  |      Config → gcc -D flags → jopa → mem_rom.dat, mem_ram.dat, JumpTable.scala
  |
  |  [2] Runtime generation (once per system config)
  |      Config → Const.java + module selection → javac → runtime classes + JAR
  |
  |  [3] SpinalHDL elaboration (once per system config)
  |      Config + ROM/RAM .dat → sbt runMain → Verilog (.v files)
  |
  |  [4] FPGA synthesis (once per Verilog change, slow ~5-30 min)
  |      Verilog + .qsf/.xdc → Quartus/Vivado → bitstream (.sof/.bit)
  |
  |  [5] Application build (per app, fast ~5 sec)
  |      App .java + runtime JAR → javac → PreLinker → JOPizer → .jop
  |
  |  [6] Download + run
  |      bitstream + .jop → serial download → FPGA running
  |
  |  [7] FPGA test (optional, after [6])
  |      Upload test .jop → capture UART output → parse results → pass/fail report
```

Steps [1]-[4] are **infrastructure** — done once per config change, cached. Step [5] is the **inner development loop** — fast, done on every app change. Step [6] is **deploy**. Step [7] is **verification** — automated hardware testing.

### Build library API

```java
package com.jopdesign.build;

/**
 * Core build library — same API called by CLI and Eclipse.
 * All methods are synchronous, report progress via BuildListener.
 */
public class BuildOrchestrator {
    private final BuildConfig config;
    private final BuildListener listener;
    private final Path projectRoot;

    public BuildOrchestrator(Path projectRoot, BuildConfig config, BuildListener listener) { ... }

    /** Full build — only rebuilds steps whose inputs changed */
    public BuildResult buildAll() { ... }

    /** App-only build — inner loop, skips [1]-[4] */
    public BuildResult buildApp(String appName) { ... }

    /** Verilog generation only */
    public BuildResult buildVerilog() { ... }

    /** FPGA synthesis only */
    public BuildResult buildFpga() { ... }

    /** Download .jop to hardware */
    public void download(Path jopFile, DownloadOptions options) { ... }

    /** Run SpinalHDL simulation with given .jop */
    public void simulate(Path jopFile, SimOptions options) { ... }

    /** Run FPGA hardware test — upload .jop, capture UART, parse results */
    public TestResult testFpga(Path jopFile, FpgaTestOptions options) { ... }

    /** Run full test suite on FPGA — build DoAll.jop, upload, verify pass/fail */
    public TestResult testSuite(FpgaTestOptions options) { ... }

    /** Check what's stale and would be rebuilt */
    public BuildPlan dryRun() { ... }
}

/** Progress reporting — CLI prints to stdout, Eclipse updates progress bar */
public interface BuildListener {
    void onStepStart(BuildStep step, String description);
    void onStepProgress(BuildStep step, int percent);
    void onStepComplete(BuildStep step, BuildStepResult result);
    void onOutput(BuildStep step, String line);  // stdout/stderr from tools
}

/** Build configuration loaded from JSON or constructed programmatically */
public class BuildConfig {
    public static BuildConfig fromJson(Path jsonFile) { ... }
    public static BuildConfig fromPreset(String presetName) { ... }

    public String systemName;
    public String bootMode;
    public Map<String, String> coreConfig;   // bytecode → Implementation
    public List<String> modules;             // runtime modules
    public String fpgaTarget;                // board preset
}
```

### CLI front-end

The `jop` CLI is a thin wrapper around `BuildOrchestrator`:

```bash
# Full build from config (first time, or after config change)
jop build qmtech-serial

# App-only rebuild (inner loop — fast, skips [1]-[4])
jop build --app Smallest

# Just regenerate Verilog (after config change, before synthesis)
jop build --verilog qmtech-serial

# FPGA synthesis (after Verilog change)
jop build --fpga qmtech-serial

# Download and run
jop download HelloWorld.jop
jop download -e HelloWorld.jop   # with UART monitor

# Dry run — show what would be rebuilt
jop build --dry-run qmtech-serial

# Create new application project
jop create-project --system qmtech-serial --name my-jop-app

# Run simulation
jop simulate --system qmtech-serial HelloWorld.jop

# FPGA hardware test — upload .jop, capture UART, check output
jop test HelloWorld.jop                 # single app test
jop test --suite                        # full JVM test suite (DoAll.jop)
jop test --suite --timeout 120          # with custom timeout (seconds)
jop test --expect "Hello World!"        # check for specific output string
jop test --cycles                       # report per-test cycle counts
```

The CLI implements `BuildListener` to print progress bars and tool output to the terminal.

### FPGA hardware test (step [7])

Automated hardware verification: upload a .jop to the FPGA, capture UART output in real time, parse test results, report pass/fail. Reuses the same serial protocol as `Downloader` and the same UART output parsing as the SpinalHDL simulation harnesses.

**Protocol:**

1. Program FPGA (if bitstream newer than last programmed, or `--force-program`)
2. Upload .jop via serial boot protocol (same as `jop download`)
3. Open UART listener on same serial port
4. Capture lines until: (a) all expected tests complete, (b) timeout, or (c) end marker detected
5. Parse each line for test result pattern: `T<name> Ok` or `T<name> FAIL`
6. Report summary: passed/failed/total, per-test cycle counts (optional), wall-clock time

**Test result parsing:**

The UART output format from DoAll.jop (and individual tests) follows a consistent pattern already used by the simulation harnesses:

```
GC info                    → ignored (preamble)
CI0                        → class init, reset cycle counter
T01 IntArithmetic Ok       → test passed
T02 Logic2 Ok              → test passed
T15 FloatTest FAIL         → test failed
...
M0: 59/60 Ok               → summary line
```

`FpgaTestRunner` parses this same format. The simulation harnesses (`JopMinMaxSim`, `JopJvmTestsBramSim`) already implement this parsing — the FPGA test runner shares the parsing logic.

```java
public class FpgaTestRunner {
    private final Downloader downloader;
    private final SerialPort uart;
    private final BuildListener listener;

    /**
     * Upload .jop and run, capturing UART output.
     * Returns structured test results.
     */
    public TestResult run(Path jopFile, FpgaTestOptions options) {
        // 1. Upload .jop
        downloader.download(jopFile, options.downloadOptions());

        // 2. Switch serial port to UART listener mode
        List<TestCase> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        StringBuilder fullOutput = new StringBuilder();

        // 3. Read lines until timeout or completion
        while (!timedOut(startTime, options.timeoutMs)) {
            String line = uart.readLine(1000);  // 1 sec read timeout
            if (line == null) continue;

            fullOutput.append(line).append("\n");
            listener.onOutput(BuildStep.TEST, line);

            // Parse test result lines
            TestCase tc = parseTestLine(line);
            if (tc != null) results.add(tc);

            // Check for summary/completion marker
            if (isSummaryLine(line)) break;
        }

        // 4. Build result
        long elapsed = System.currentTimeMillis() - startTime;
        int passed = (int) results.stream().filter(t -> t.passed).count();
        int failed = results.size() - passed;

        return new TestResult(results, passed, failed, elapsed, fullOutput.toString());
    }

    /** Parse "T01 IntArithmetic Ok" or "T01 IntArithmetic FAIL" */
    private TestCase parseTestLine(String line) {
        if (!line.startsWith("T")) return null;
        if (line.endsWith("Ok")) return new TestCase(line.trim(), true);
        if (line.endsWith("FAIL")) return new TestCase(line.trim(), false);
        return null;
    }
}

public class TestResult {
    public final List<TestCase> tests;
    public final int passed, failed;
    public final long elapsedMs;
    public final String rawOutput;

    public boolean allPassed() { return failed == 0; }

    /** Print summary to stdout */
    public void printSummary() {
        for (TestCase tc : tests) {
            System.out.printf("  %-40s %s%n", tc.name, tc.passed ? "OK" : "FAIL");
        }
        System.out.printf("%n%d/%d passed (%.1f sec)%n", passed, passed + failed, elapsedMs / 1000.0);
    }
}

public class FpgaTestOptions {
    public int timeoutMs = 120_000;           // 2 min default (DoAll.jop takes ~60 sec at 80 MHz)
    public String serialPort = null;          // auto-detect from board config
    public boolean reportCycles = false;      // parse per-test cycle counts
    public String expectOutput = null;        // check for specific string in output
    public boolean programFpga = true;        // program bitstream before upload
}
```

**Test suite mode** (`jop test --suite`):

Builds `DoAll.jop` (step [5] if stale), uploads, and expects the standard 60-test output. Reports pass/fail per test and overall score:

```
$ jop test --suite
Building DoAll.jop...
Programming FPGA...
Uploading DoAll.jop (51 KB)...
Running tests...
  T01 IntArithmetic                        OK
  T02 Logic2                               OK
  ...
  T59 HwExceptionTest                      OK
  T60 DeepRecursion                        TIMEOUT

59/60 passed (47.3 sec)
```

**Single-app test** (`jop test HelloWorld.jop --expect "Hello World!"`):

Uploads the app, captures output, checks for expected string:

```
$ jop test HelloWorld.jop --expect "Hello World!"
Programming FPGA...
Uploading HelloWorld.jop...
Output:
  Hello World!
PASS: found expected output
```

**Cycle count reporting** (`jop test --suite --cycles`):

Measures wall-clock time between test result lines. Not cycle-accurate (depends on UART baud rate and OS scheduling), but gives relative performance comparison between configs:

```
$ jop test --suite --cycles
  T01 IntArithmetic                        OK      0.8 sec
  T02 Logic2                               OK      0.3 sec
  ...
  T15 FloatTest                            OK      2.1 sec   ← slow on min config
  ...
59/60 passed (47.3 sec)
```

For true cycle counts, use SpinalHDL simulation (`jop simulate --cycles`) which has access to the cycle counter.

**Serial port auto-detection:**

The board config knows which USB-serial chip is present (`CP2102N`, `FT2232H`). `FpgaTestRunner` scans `/dev/ttyUSB*` or `/dev/ttyACM*` and matches by USB VID/PID:

| Chip | VID:PID | Boards |
|------|---------|--------|
| CP2102N | 10C4:EA60 | QMTECH DB V4 |
| FT2232H | 0403:6010 | CYC5000, Alchitry Au V2 |

Override with `--port /dev/ttyUSB0` if auto-detection fails.

**Eclipse integration:**

Eclipse toolbar: "Test on FPGA" button calls `orchestrator.testSuite(options)`. Results displayed in a JUnit-style test view — green/red bars per test, click to see UART output. Same `FpgaTestRunner` underneath.

### Eclipse front-end

Eclipse calls the **same `BuildOrchestrator` API** — no separate build logic. The Eclipse plugin adds IDE-specific behavior on top:

```java
// Eclipse builder calls the shared library
public class JopEclipseBuilder extends IncrementalProjectBuilder {
    @Override
    protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) {
        Path projectRoot = getProject().getLocation().toFile().toPath();
        BuildConfig config = BuildConfig.fromJson(projectRoot.resolve(".settings/jop.json"));

        // Eclipse BuildListener bridges to IProgressMonitor
        BuildListener listener = new EclipseProgressListener(monitor, getConsole());

        BuildOrchestrator orchestrator = new BuildOrchestrator(projectRoot, config, listener);
        orchestrator.buildApp(getAppName());
        return null;
    }
}
```

Eclipse adds:
- **Auto-build on save** — triggers `buildApp()` via the builder mechanism
- **Progress bar** — `BuildListener.onStepProgress()` → Eclipse progress monitor
- **Console output** — `BuildListener.onOutput()` → Eclipse console view
- **Error markers** — parse compiler errors from `BuildStepResult`, mark source lines
- **Classpath management** — `.classpath` points to runtime JAR from `buildAll()` step [2]

But the actual build logic, dependency tracking, and tool invocations are all in `jop-build`.

### Incremental build awareness

Each step produces artifacts with a **config hash**. If the config hasn't changed, the step is skipped:

```
asm/generated/serial/mem_rom.dat     # hash of (boot_mode + feature flags)
java/runtime/build/qmtech-serial/    # hash of (core config + board devices)
generated/verilog/JopSdramTop.v      # hash of (full system config)
fpga/qmtech-ep4cgx150-sdram/jop.sof # hash of (Verilog files)
```

The inner app development loop (step [5]) only needs the runtime classes — which are already built and cached for this board config. Both CLI and Eclipse benefit from the same cache.

### Configuration format

A single JSON file drives both CLI and Eclipse:

```json
{
  "system": "qmtech-serial",
  "coreConfig": {
    "imul": "Hardware",
    "fadd": "Hardware",
    "fsub": "Hardware",
    "fmul": "Hardware",
    "fdiv": "Hardware"
  },
  "modules": ["collections", "fat32"],
  "clkFreqHz": 80000000
}
```

- **CLI**: `jop build --config my-board.json`
- **Eclipse**: `.settings/jop.json` in project root, read by builder
- **Programmatic**: `BuildConfig.fromJson(path)` or construct in Scala/Java code

JSON schema provided for IDE validation (Eclipse, VS Code, IntelliJ all support JSON schema).

### Where the library lives

```
java/tools/src/com/jopdesign/build/
  BuildOrchestrator.java    # dependency graph + incremental cache
  BuildConfig.java          # JSON config parsing
  BuildListener.java        # progress reporting interface
  BuildStep.java            # enum: MICROCODE, RUNTIME, VERILOG, FPGA, APP, DOWNLOAD, TEST
  BuildResult.java          # per-step results (success/fail, artifacts, timing)
  MicrocodeBuilder.java     # step [1]: gcc + jopa invocation
  RuntimeBuilder.java       # step [2]: Const.java gen + javac + JAR
  VerilogBuilder.java       # step [3]: sbt runMain invocation
  FpgaBuilder.java          # step [4]: Quartus/Vivado invocation
  AppBuilder.java           # step [5]: javac + PreLinker + JOPizer
  Downloader.java           # step [6]: serial download
  FpgaTestRunner.java       # step [7]: FPGA hardware test (upload + UART capture + parse)
  Simulator.java            # sbt sim invocation
  TestResult.java           # per-test results (name, pass/fail, cycles, output)
  FpgaTestOptions.java      # timeout, expected output, cycle reporting, serial port
```

This lives alongside the existing JOP tools (`Jopa.java`, `PreLinker.java`, etc.) and builds into the same `jopa.jar` (or a separate `jop-build.jar`). Both CLI and Eclipse depend on this JAR.

## Phase 9: IDE Integration (Eclipse)

### Vision

A developer opens Eclipse, creates a JOP application project targeting a specific board. Eclipse knows the board's runtime (code completion, API docs), builds the .jop on save, downloads to hardware, and supports source-level debugging — all without leaving the IDE. **All build logic comes from the shared `jop-build` library** (Phase 8) — the Eclipse plugin is a thin UI layer.

### Project structure

```
my-jop-app/
  .project                    # Eclipse project file (generated)
  .classpath                  # Points to board-specific runtime JAR
  .settings/
    jop.json                  # Board + system config (shared with CLI)
  src/
    com/example/MyApp.java    # Application source
  build/
    classes/                  # javac output
    pp/                       # PreLinker output
    MyApp.jop                 # JOPizer output
```

### Eclipse plugin layers

**Layer 1: Project setup (config-driven)**

```bash
# CLI creates Eclipse-compatible project structure
jop create-project --system qmtech-serial --name my-jop-app

# Or from Eclipse: File → New → JOP Application Project (wizard)
```

Both paths use `BuildOrchestrator` to generate the same files:
- `.project` with JOP nature (or standard Java nature + custom builder)
- `.classpath` referencing the board-specific runtime JAR
- `.settings/jop.json` recording which system config this project targets
- Build configuration: javac6 flags, PreLinker, JOPizer pipeline

**Layer 2: Build integration (Eclipse builder)**

```
Save .java file
  → Eclipse incremental javac (using board runtime on classpath)
  → JopEclipseBuilder calls BuildOrchestrator.buildApp()
      → PreLinker
      → JOPizer → .jop in build/
```

The builder wraps the shared library. Compiler errors from `BuildStepResult` are mapped to Eclipse problem markers on source lines.

For infrastructure rebuilds (config change, Verilog, FPGA synthesis), the developer uses the same CLI commands or a toolbar action that calls `BuildOrchestrator.buildAll()`.

**Layer 3: Download (toolbar action)**

```
Toolbar button: "Download to JOP"
  → Calls BuildOrchestrator.download(jopFile, options)
  → Console shows: progress bar, then UART output
```

Auto-detection of serial port from `SystemAssembly` (knows which USB-serial chip is on the carrier board).

**Layer 4: Debug (debug adapter)**

Source-level debugging over UART debug protocol:

```
Eclipse Debug Configuration: "JOP Debug"
  → Serial port: auto-detected (or manual)
  → .jop file: build/MyApp.jop
  → Source path: src/
  → Capabilities: breakpoint, step, inspect locals, stack trace
```

Implementation: **Debug Adapter Protocol (DAP)** — modern, IDE-agnostic. The DAP server is a Java process (part of `jop-build`) that maps UART debug protocol to DAP. Works with Eclipse, VS Code, and IntelliJ.

The existing `DebugProtocol` / `DebugController` in SpinalHDL provides: halt/resume, read/write memory, read stack, read method cache. The DAP adapter adds: mapping to source locations (via .jop metadata + PreLinker symbol tables).

### Configuration UI

**Phase 1: JSON config with schema validation**

The `.settings/jop.json` file (shared with CLI) is the config source. Eclipse JSON editor with schema validation provides auto-complete and error highlighting — no custom plugin UI needed.

**Phase 2: Custom wizard (optional, future)**

Wizard-style: Select board → Configure cores → Select modules → Generate project. Writes the same `jop.json`. Richer experience, more development effort. Only justified once the core workflow is stable.

### Simulation from IDE

Run Configuration: "JOP Simulation" — calls `BuildOrchestrator.simulate()`:

```
  → sbt "Test / runMain jop.system.JopCoreBramSim" --jop build/MyApp.jop
  → Console shows: simulated UART output
  → Optional: waveform viewer (GTKWave) for hardware debugging
```

This uses the same `JopConfig` as the FPGA build but targets the BRAM simulation backend. Useful for testing before hardware is available.

## Phase 10: Cross-Cutting Concerns

### Config-to-artifact version binding

**Problem:** If you rebuild the FPGA with a new config but forget to rebuild the .jop (or vice versa), the runtime assumptions don't match the hardware. Silent corruption.

**Solution:** Config hash embedded in both bitstream and .jop:

```scala
// JopConfig produces a deterministic hash
def configHash: String = sha256(
  coreConfigs.map(_.toString) ++
  Seq(bootMode.toString, cpuCnt.toString, ioConfig.toString)
).take(8)  // 8-char hex
```

- **Verilog**: Config hash baked into a readable register (e.g., I/O address `IO_CONFIG_HASH`)
- **.jop**: Config hash stored in .jop file header (JOPizer stamps it)
- **Boot check**: `Startup.java` reads `IO_CONFIG_HASH`, compares with embedded hash, warns on mismatch

This doesn't prevent running mismatched builds (that would brick development), but it provides a clear diagnostic: "Warning: .jop built for config a3f2b1c0, hardware is config 7e91d4a2".

### PreLinker / JOPizer config awareness

**CRITICAL:** JOPizer needs to know which bytecodes are handled by hardware/microcode vs Java software. This is NOT optional — it determines whether JOPizer replaces a bytecode with `invokestatic` (IMP_JAVA) or leaves it as a raw bytecode for the jump table (IMP_ASM).

Currently `JopInstr.java` hardcodes IMP_ASM vs IMP_JAVA per bytecode. Most math/float bytecodes are IMP_JAVA (JOPizer replaces them with calls to SoftFloat etc.). But `imul` (0x68) is IMP_ASM — JOPizer leaves it in the bytecode stream, and the jump table dispatches it to the microcode handler at runtime.

**The problem:** If a bytecode is IMP_ASM but the config says `Java`, the jump table patches it to `sys_noim` — but JOPizer didn't replace it with `invokestatic`, so the raw bytecode hits `sys_noim` and the Java fallback handler runs (which may be a stub). If a bytecode is IMP_JAVA but the config says `Hardware`, JOPizer already replaced it with `invokestatic` — the hardware handler in the jump table is dead code (never reached).

**Solution:** JOPizer's IMP_ASM/IMP_JAVA classification must be driven by the same config:

| Config | JOPizer | Jump Table |
|--------|---------|------------|
| **Java** | IMP_JAVA (replace with invokestatic → SoftFloat etc.) | sys_noim (unreachable) |
| **Microcode** | IMP_ASM (leave raw bytecode) | microcode handler addr |
| **Hardware** | IMP_ASM (leave raw bytecode) | HW microcode handler addr |

Implementation: generate `JopInstr.java` (or a config file read by JOPizer) from `JopCoreConfig`. For each configurable bytecode, `Implementation.Java` → IMP_JAVA, otherwise → IMP_ASM.

Additional config the tools may need:
- **Memory layout** — heap start, stack size, method cache size → already in `Const.java` via `JopMemoryConfig`
- **Bytecode implementation** — which bytecodes are Java vs microcode/hardware → drives IMP_ASM/IMP_JAVA in JOPizer
- **Config hash** — JOPizer stamps .jop header with hash from generated `Const.java`

Changes needed:
1. `JopInstr.java`: Make IMP_ASM/IMP_JAVA configurable per bytecode (read from config or generated source)
2. `JOPizer.java`: Read bytecode config to determine replacement behavior
3. Build chain: config generates `JopInstr` classification before JOPizer runs

### Configuration validation

Errors at elaboration time (SpinalHDL `require()` checks):

```scala
// In JopCoreConfig
require(!(fadd == Implementation.Hardware && fdiv == Implementation.Hardware &&
          fsub != Implementation.Hardware),
  "FloatComputeUnit: if fadd and fdiv are Hardware, fsub must be too (shared FPU)")

// In JopConfig / JopSystem
require(cpuCnt >= 1 && cpuCnt <= 16)
perCoreConfigs.foreach(pcc =>
  require(pcc.length == cpuCnt))

// Resource estimation warnings (non-fatal)
if (coreConfig.needsLongAlu && config.fpgaFamily == FpgaFamily.CycloneV)
  println(s"Warning: 64-bit ALU on ${config.assembly.name} — verify LUT budget")
```

Future: resource estimation from config (LEs, DSPs, BRAMs) — compare against FPGA capacity before running synthesis. Saves 5-30 minutes of failed synthesis.

### WCET analysis

Original JOP had WCET analysis tooling (in `jopmin/tools/`). With per-instruction `Implementation` choices, cycle counts change:

- `imul: Microcode` → 18 cycles; `imul: Hardware` → ~4 cycles (sthw/wait)
- `ladd: Microcode` → 26 cycles; `ladd: Hardware` → 1 cycle (ALU)

WCET analysis needs a **timing model** derived from `JopCoreConfig`:

```scala
def bytecodeTimingModel(config: JopCoreConfig): Map[Int, CycleCount] = {
  // For each bytecode, return (best-case, worst-case) cycles
  // based on config's Implementation choice
  Map(
    0x68 -> (if (config.imul == Hardware) CycleCount(4, 4) else CycleCount(18, 18)),
    0x61 -> (if (config.ladd == Hardware) CycleCount(1, 1) else CycleCount(26, 26)),
    // ...
  )
}
```

This is a **future enhancement** — the existing WCET tools work with fixed timing tables. Config-driven timing tables are an incremental improvement.

### Migration path

Existing setups transition incrementally:

1. **Phase 1-4** (core config): Existing code keeps working. Old `JopCoreConfig` fields (`fpuMode`, `useDspMul`, `useHwDiv`) become deprecated aliases that set the new per-instruction fields. Old Verilog generation entry points become thin wrappers. Old sim harnesses updated to use new config.

2. **Phase 5** (hardware description): Existing `.qsf` files kept as-is. New `SystemAssembly` data can generate `.qsf` files, but hand-maintained originals are the reference during migration. No breaking changes.

3. **Phase 6** (runtime): Existing `Const.java` kept as-is until runtime generation is stable. New generated `Const.java` validated against the hand-maintained one before switching over.

4. **Phase 8-9** (build/IDE): Existing `make` workflow continues to work. The `jop-build` library wraps the same Makefiles and sbt commands. `jop` CLI and Eclipse plugin are additive — they call `jop-build` which calls the same tools. Eclipse project generation is opt-in.

At each phase, existing workflows are not broken — new capabilities are added alongside, validated, then old paths deprecated.

---

## Open Items

Areas referenced in the document that need further detail:

1. **Device registry for MemoryDevice lookup** (Phase 5) — `BoardDevice.part` references part numbers that need to resolve to `MemoryDevice` objects for controller instantiation. The registry mechanism (global map, companion object lookup) is not yet defined. Simplest approach: `MemoryDevice` companion object with lookup by part number.

2. **RuntimeModule dependency resolution** (Phase 6) — `closeDependencies()` is referenced for transitive module dependency resolution but the algorithm (topological sort, cycle detection) is not specified.

3. **OpenJDK 6 source setup** (Phase 6) — The document references `/srv/git/java/jdk6/` which is machine-specific. Needs setup instructions: where to obtain the source, how to configure the path, and whether it should be version-controlled or referenced externally.

4. **Build config JSON schema** (Phase 8) — Example JSON snippets exist but no formal JSON Schema definition. Needed for IDE validation (Eclipse/VS Code auto-complete and error highlighting in `.settings/jop.json`).

5. **Superset ROM with both imul handlers** — Currently `#ifdef HW_MUL` selects one or the other. Need to restructure jvm.asm to have both `imul_hw:` and `imul_sw:` labels always present. Jopa must export both addresses so `resolveJumpTable` can pick the right one. This is the key step to eliminate separate bare builds.

6. **JOPizer config awareness** (Phase 10) — JOPizer must know per-bytecode IMP_ASM vs IMP_JAVA. Options: (a) generate `JopInstr.java` from config, (b) pass a config file to JOPizer at runtime, (c) generate a `BytecodeConfig.java` that JOPizer reads. Option (a) is cleanest — one generated file replaces the hardcoded table.

7. **Dual-subsystem interconnect** — FIFO message queues between two JOP systems on the same FPGA. Interface: BMB slave on each system, shared FIFO memory. Need to define the protocol (push/pop semantics, flow control, interrupt-on-data).

8. **Jopa alternate-handler addresses** — To support `imul: Microcode` vs `imul: Hardware` from the same superset ROM, Jopa's `JumpTableData` needs to export both handler addresses per bytecode. Current format only has one entry per bytecode. Need either: (a) a second parallel address array for alternate handlers, or (b) named labels that Jopa exports (e.g., `imul_hw_addr`, `imul_sw_addr`) alongside the jump table.
