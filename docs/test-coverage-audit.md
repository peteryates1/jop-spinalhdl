# JOP Test Coverage Audit

Date: 2026-03-01 (updated from 2026-02-24)

## 1. Bytecode Coverage Matrix

JOP implements bytecodes either in microcode (`jvm.asm` / `jvm_call.inc` / `jvm_long.inc`) or
in Java (`JVM.java`). The jump table in the assembler maps each JVM bytecode to its handler.

### Legend

- **MC** = Microcode implementation (in `.asm` / `.inc` files)
- **JV** = Java implementation (in `JVM.java`, dispatched via `sys_noim`)
- **N/A** = Not implemented / not applicable to JOP
- **T** = Tested by JVM test suite
- **IT** = Implicitly tested (used by test infrastructure but not explicitly targeted)
- **(none)** = No direct test coverage

### 1.1 Constants and Loads

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| nop | 0x00 | MC | IT | (used internally) |
| aconst_null | 0x01 | MC | T | BranchTest1 (null checks), NullPointer |
| iconst_m1 | 0x02 | MC | T | ConstLoad |
| iconst_0..5 | 0x03-0x08 | MC | T | ConstLoad, many tests |
| lconst_0 | 0x09 | MC | T | LongTest, LongArithmetic |
| lconst_1 | 0x0A | MC | T | LongTest |
| fconst_0 | 0x0B | MC | T | FloatTest |
| fconst_1 | 0x0C | JV | T | FloatTest (implicit via 1.0F literals) |
| fconst_2 | 0x0D | JV | T | FloatTest (implicit via 2.0F literals) |
| dconst_0 | 0x0E | MC | T | TypeConversion (implicit) |
| dconst_1 | 0x0F | JV | T | TypeConversion (implicit) |
| bipush | 0x10 | MC | T | ConstLoad (6, 127, -127) |
| sipush | 0x11 | MC | T | ConstLoad (128, 255, 32767) |
| ldc | 0x12 | MC | T | ConstLoad (32768), many tests |
| ldc_w | 0x13 | MC | IT | (large constant pools) |
| ldc2_w | 0x14 | MC | T | LongTest (long constants) |

### 1.2 Loads and Stores

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| iload | 0x15 | MC | T | (used throughout) |
| lload | 0x16 | MC | T | LongTest |
| fload | 0x17 | MC | T | FloatTest |
| dload | 0x18 | MC | T | TypeConversion |
| aload | 0x19 | MC | T | (used throughout) |
| iload_0..3 | 0x1A-0x1D | MC | T | (used throughout) |
| lload_0..3 | 0x1E-0x21 | MC | T | LongTest |
| fload_0..3 | 0x22-0x25 | MC | T | FloatTest |
| dload_0..3 | 0x26-0x29 | MC | T | TypeConversion |
| aload_0..3 | 0x2A-0x2D | MC | T | (used throughout) |
| istore | 0x36 | MC | T | (used throughout) |
| lstore | 0x37 | MC | T | LongTest |
| fstore | 0x38 | MC | T | FloatTest |
| dstore | 0x39 | MC | T | TypeConversion |
| astore | 0x3A | MC | T | (used throughout) |
| istore_0..3 | 0x3B-0x3E | MC | T | (used throughout) |
| lstore_0..3 | 0x3F-0x42 | MC | T | LongTest |
| fstore_0..3 | 0x43-0x46 | MC | T | FloatTest |
| dstore_0..3 | 0x47-0x4A | MC | T | TypeConversion |
| astore_0..3 | 0x4B-0x4E | MC | T | (used throughout) |

### 1.3 Array Operations

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| iaload | 0x2E | MC | T | ArrayTest3, IntArithmetic (arrays in helper methods) |
| laload | 0x2F | MC | T | LongTest (testArray) |
| faload | 0x30 | MC | T | FloatArray |
| daload | 0x31 | JV (noim) | (none) | Not implemented |
| aaload | 0x32 | MC | T | ArrayTest2, SystemCopy |
| baload | 0x33 | MC | T | ArrayTest3 (byte arrays) |
| caload | 0x34 | MC | T | ArrayTest3 (char arrays), String ops |
| saload | 0x35 | MC | T | ArrayTest3 (short arrays) |
| iastore | 0x4F | MC | T | ArrayTest3 |
| lastore | 0x50 | MC | T | LongTest (testArray) |
| fastore | 0x51 | MC | T | FloatArray |
| dastore | 0x52 | JV (noim) | (none) | Not implemented |
| aastore | 0x53 | JV | T | ArrayTest2, PutRef, SystemCopy |
| bastore | 0x54 | MC | T | ArrayTest3 |
| castore | 0x55 | MC | T | ArrayTest3, StackManipulation |
| sastore | 0x56 | MC | T | ArrayTest3 |

### 1.4 Stack Manipulation

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| pop | 0x57 | MC | IT | (used throughout) |
| pop2 | 0x58 | MC | IT | (long operations) |
| dup | 0x59 | MC | IT | (used throughout) |
| dup_x1 | 0x5A | MC | T | StackManipulation |
| dup_x2 | 0x5B | MC | T | StackManipulation |
| dup2 | 0x5C | MC | T | StackManipulation |
| dup2_x1 | 0x5D | MC | T | StackManipulation |
| dup2_x2 | 0x5E | MC | T | StackManipulation |
| swap | 0x5F | MC | T | SwapTest (BCEL-injected SWAP bytecodes) |

### 1.5 Integer Arithmetic

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| iadd | 0x60 | MC | T | IntArithmetic (12 cases) |
| isub | 0x64 | MC | T | IntArithmetic (12 cases) |
| imul | 0x68 | MC | T | IntArithmetic (15 cases), Imul |
| idiv | 0x6C | JV | T | IntArithmetic (18 cases) |
| irem | 0x70 | JV | T | Logic2 |
| ineg | 0x74 | MC | T | Logic2 |
| ishl | 0x78 | MC | T | Logic1 |
| ishr | 0x7A | MC | T | Logic1 |
| iushr | 0x7C | MC | T | Logic1 |
| iand | 0x7E | MC | T | IntArithmetic (13 cases) |
| ior | 0x80 | MC | T | IntArithmetic (17 cases) |
| ixor | 0x82 | MC | T | IntArithmetic (17 cases) |
| iinc | 0x84 | MC | T | IntArithmetic (incTest) |

### 1.6 Long Arithmetic

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| ladd | 0x61 | MC | T | LongTest, LongArithmetic |
| lsub | 0x65 | MC | T | LongTest, LongArithmetic |
| lmul | 0x69 | JV | T | LongTest (22 cases) |
| ldiv | 0x6D | JV | T | LongTest (13 cases), LongArithmetic |
| lrem | 0x71 | JV | T | LongTest (8 cases) |
| lneg | 0x75 | MC | T | LongTest (7 cases) |
| lshl | 0x79 | MC | T | LongTest (10 cases) |
| lshr | 0x7B | MC | T | LongTest (8 cases) |
| lushr | 0x7D | MC | T | LongTest (9 cases) |
| land | 0x7F | MC | T | LongTest, LongArithmetic |
| lor | 0x81 | MC | T | LongTest, LongArithmetic |
| lxor | 0x83 | MC | T | LongTest, LongArithmetic |

### 1.7 Float Arithmetic

| Bytecode | Opcode | Impl (Software) | Impl (HW FPU) | Tested | Test(s) |
|---|---|---|---|---|---|
| fadd | 0x62 | JV (SoftFloat) | MC → HW FPU | T | FloatTest |
| fsub | 0x66 | JV (SoftFloat) | MC → HW FPU | T | FloatTest |
| fmul | 0x6A | JV (SoftFloat) | MC → HW FPU | T | FloatTest (test_fmul, 5 cases) |
| fdiv | 0x6E | JV (SoftFloat) | MC → HW FPU | T | FloatTest (test_fdiv, 5 cases) |
| frem | 0x72 | JV (SoftFloat) | JV (SoftFloat) | T | FloatTest (test_frem) |
| fneg | 0x76 | JV | JV | T | FloatTest (test_fneg) |

Note: With `fpuMode = Hardware`, fadd/fsub/fmul/fdiv are dispatched via FPU jump table
to microcode handlers that use the HW FPU (BmbFpu I/O peripheral wrapping VexRiscv FpuCore).
Other float operations (frem, fneg, fcmp, f2i) remain in Java software.
Both paths verified: `JopJvmTestsBramSim` (Software) and `JopFpuBramSim` (Hardware) — 60/60 pass.

### 1.8 Double Arithmetic

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| dadd | 0x63 | JV (SoftFloat64) | T | DoubleArithmetic |
| dsub | 0x67 | JV (SoftFloat64) | T | DoubleArithmetic |
| dmul | 0x6B | JV (SoftFloat64) | T | DoubleArithmetic |
| ddiv | 0x6F | JV (SoftFloat64) | T | DoubleArithmetic |
| drem | 0x73 | JV (SoftFloat64) | T | DoubleArithmetic |
| dneg | 0x77 | JV | T | DoubleArithmetic |

Note: Double arithmetic is implemented in `SoftFloat64`. The DoubleArithmetic test covers
all operations (dadd, dsub, dmul, ddiv, drem, dneg) plus dcmpl/dcmpg comparisons.
The TypeConversion test exercises d2i, d2l, d2f, f2d, i2d, l2d conversions separately.

### 1.9 Type Conversions

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| i2l | 0x85 | MC | T | LongTest, TypeConversion |
| i2f | 0x86 | JV (SoftFloat) | T | TypeConversion (i2x) |
| i2d | 0x87 | JV (SoftFloat64) | T | TypeConversion (i2x) |
| l2i | 0x88 | MC | T | LongTest, TypeConversion |
| l2f | 0x89 | JV (SoftFloat) | T | TypeConversion (l2x) |
| l2d | 0x8A | JV (SoftFloat64) | T | TypeConversion (l2x) |
| f2i | 0x8B | JV (SoftFloat) | T | FloatTest (test_f2i, 16 cases) |
| f2l | 0x8C | JV (SoftFloat) | T | TypeConversion (f2x) |
| f2d | 0x8D | JV (SoftFloat64) | T | TypeConversion (f2x) |
| d2i | 0x8E | JV (SoftFloat64) | T | TypeConversion (d2x) |
| d2l | 0x8F | JV (SoftFloat64) | T | TypeConversion (d2x) |
| d2f | 0x90 | JV (SoftFloat) | T | TypeConversion (d2x) |
| i2b | 0x91 | JV | T | Conversion, TypeConversion |
| i2c | 0x92 | MC | T | Conversion, TypeConversion |
| i2s | 0x93 | JV | T | Conversion, TypeConversion |

### 1.10 Comparisons

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| lcmp | 0x94 | MC | T | LongTest (15 cases) |
| fcmpl | 0x95 | JV (SoftFloat) | T | FloatTest (test_fcmp) |
| fcmpg | 0x96 | JV (SoftFloat) | T | FloatTest (test_fcmp) |
| dcmpl | 0x97 | JV (SoftFloat64) | T | DoubleArithmetic (test_dcmp) |
| dcmpg | 0x98 | JV (SoftFloat64) | T | DoubleArithmetic (test_dcmp) |

### 1.11 Branches

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| ifeq | 0x99 | MC | T | BranchTest3 |
| ifne | 0x9A | MC | T | BranchTest3 |
| iflt | 0x9B | MC | T | BranchTest3 |
| ifge | 0x9C | MC | T | BranchTest3 |
| ifgt | 0x9D | MC | T | BranchTest3 |
| ifle | 0x9E | MC | T | BranchTest3 |
| if_icmpeq | 0x9F | MC | T | BranchTest2 |
| if_icmpne | 0xA0 | MC | T | BranchTest2 |
| if_icmplt | 0xA1 | MC | T | BranchTest2 |
| if_icmpge | 0xA2 | MC | T | BranchTest2 |
| if_icmpgt | 0xA3 | MC | T | BranchTest2 |
| if_icmple | 0xA4 | MC | T | BranchTest2 |
| if_acmpeq | 0xA5 | MC | T | Ifacmp, BranchTest1 |
| if_acmpne | 0xA6 | MC | T | Ifacmp, BranchTest1 |
| goto | 0xA7 | MC | IT | (loops in all tests) |
| ifnull | 0xC6 | MC | T | BranchTest1 |
| ifnonnull | 0xC7 | MC | T | BranchTest1 |

### 1.12 Switch

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| tableswitch | 0xAA | JV | T | Switch (sw: 5 cases) |
| lookupswitch | 0xAB | JV | T | Switch (lsw: 6 cases), Switch2 |

### 1.13 Field Access

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| getstatic | 0xB2 | MC | T | Static, Clinit, Clinit2 |
| putstatic | 0xB3 | MC | T | Static, Clinit, Clinit2 |
| getfield | 0xB4 | MC | T | Basic, Basic2, IntField, ShortField, ByteField, CharField, BooleanField, FloatField, DoubleField, ObjectField, NativeMethods |
| putfield | 0xB5 | MC | T | Basic, Basic2, IntField, etc. |
| getstatic_ref | 0xB2* | MC | T | Static (reference statics) |
| putstatic_ref | 0xE1 | JV | T | PutRef (sref) |
| getfield_ref | 0xB4* | MC | T | ObjectField, PutRef |
| putfield_ref | 0xE3 | JV | T | PutRef (ref field), ObjectField |
| getfield_long | - | MC | T | LongField, DoubleField |
| putfield_long | - | MC | T | LongField, DoubleField |
| getstatic_long | - | MC | T | LongStaticField |
| putstatic_long | - | MC | T | LongStaticField |

### 1.14 Method Invocation

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| invokevirtual | 0xB6 | MC | T | Basic, Iface (via class refs), InstanceOfTest (super.exec) |
| invokespecial | 0xB7 | MC | T | InvokeSpecial (private methods, constructors) |
| invokestatic | 0xB8 | MC | T | Static, all static helper methods |
| invokeinterface | 0xB9 | MC | T | Iface (extensive: A/B/C interfaces, D/E/F/X/Y/Z classes) |
| invokesuper | 0xEC | MC | T | InvokeSpecial (super.bar), InvokeSuper, InstanceOfTest |

### 1.15 Object Operations

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| new | 0xBB | JV | T | Basic, every test with `new` |
| newarray | 0xBC | JV | T | ArrayTest3 (all primitive types) |
| anewarray | 0xBD | JV | T | ArrayTest2, PutRef |
| arraylength | 0xBE | MC | T | ArrayTest2, ArrayTest3 |
| athrow | 0xBF | JV | T | Except (throw1-8), AthrowTest, PutRef |
| checkcast | 0xC0 | JV | T | InstanceCheckcast, CheckCast (with CCE catching) |
| instanceof | 0xC1 | JV | T | InstanceCheckcast, CheckCast, InstanceOfTest |
| monitorenter | 0xC2 | MC | IT | (synchronized blocks in JVM.java/GC) |
| monitorexit | 0xC3 | MC | IT | (synchronized blocks in JVM.java/GC) |
| multianewarray | 0xC5 | JV | T | MultiArray, ArrayMulti |

### 1.16 Return

| Bytecode | Opcode | Impl | Tested | Test(s) |
|---|---|---|---|---|
| ireturn | 0xAC | MC | T | (all methods returning int) |
| lreturn | 0xAD | MC | T | LongTest |
| freturn | 0xAE | MC | T | FloatTest |
| dreturn | 0xAF | MC | T | TypeConversion (double methods) |
| areturn | 0xB0 | MC | T | (all methods returning references) |
| return | 0xB1 | MC | T | (all void methods) |

### 1.17 Not Implemented / Not Applicable

| Bytecode | Opcode | Status | Notes |
|---|---|---|---|
| jsr | 0xA8 | N/A | Obsolete since Java 6, `JVMHelp.noim()` |
| ret | 0xA9 | N/A | Obsolete since Java 6, `JVMHelp.noim()` |
| goto_w | 0xC8 | N/A | `JVMHelp.noim()` |
| jsr_w | 0xC9 | N/A | `JVMHelp.noim()` |
| wide | 0xC4 | N/A | `JVMHelp.noim()` (wide iinc not supported by toolchain) |
| breakpoint | 0xCA | N/A | `JVMHelp.noim()` |

---

## 2. Exception Edge Coverage

### 2.1 Tested Exception Types

| Exception Type | Hardware/Software | Tested | Test(s) | Notes |
|---|---|---|---|---|
| NullPointerException (explicit throw) | Software | T | NullPointer T0, PutRef | Java `throw new NPE()` |
| NullPointerException (invokevirtual) | MC (null_pointer) | T | NullPointer T1 | Microcode null check |
| NullPointerException (getfield int) | HW (HANDLE_READ) | T | NullPointer T2 | Hardware handle=0 check |
| NullPointerException (putfield int) | HW (HANDLE_WRITE) | T | NullPointer T3 | Hardware handle=0 check |
| NullPointerException (getfield long) | MC (long_null_pointer) | T | NullPointer T4 | Microcode null check |
| NullPointerException (putfield long) | MC (long_null_pointer) | T | NullPointer T5 | Microcode null check |
| NullPointerException (getfield ref) | HW (HANDLE_READ) | T | NullPointer T6 | Hardware handle=0 check |
| NullPointerException (putfield ref) | JV (putfield_ref) | T | NullPointer T7, PutRef | Java-level via getField |
| NullPointerException (invokeinterface) | MC (null_pointer) | T | NullPointer T9 | Microcode null check |
| NullPointerException (invokespecial) | MC (null_pointer) | T | NullPointer T3 | Separated from invokestatic |
| NullPointerException (invokesuper) | MC (null_pointer) | (none) | **GAP** | Has microcode null check |
| NullPointerException (aaload on null) | HW (HANDLE_READ) | T | NullPointer T12 | Hardware handle=0 check |
| NullPointerException (aastore on null) | JV (aastore) | T | PutRef (refa[0] on null) | |
| NullPointerException (iaload on null) | HW (HANDLE_READ) | T | NullPointer T10 | Hardware handle=0 check |
| NullPointerException (iastore on null) | HW | T | NullPointer T11 | Hardware handle=0 check |
| ArrayIndexOutOfBoundsException (negative) | HW (HANDLE_READ) | T | PutRef (refa[-1]) | Hardware MSB check |
| ArrayIndexOutOfBoundsException (upper) | HW (HANDLE_BOUND) | T | PutRef (refa[2] on size=1) | Hardware bounds check |
| ArrayIndexOutOfBoundsException (laload) | MC (array_bound) | T | LongArrayBounds T1, T2 | Microcode bound check |
| ArrayIndexOutOfBoundsException (lastore) | MC (array_bound_store) | T | LongArrayBounds T3, T4 | Microcode bound check |
| ArithmeticException (idiv by zero) | JV (f_idiv) | T | DivZero | Direct Java throw (hardware exception replaced) |
| ArithmeticException (irem by zero) | JV (f_irem) | T | DivZero | Direct Java throw (hardware exception replaced) |
| ClassCastException | JV (f_checkcast) | T | CheckCast (4 CCE catches) | Both class and interface |
| StackOverflowError (explicit throw) | Software | T | HwExceptionTest T1 | throw+catch SOE (no HW trigger exists) |
| IllegalMonitorStateException (explicit throw) | Software | T | HwExceptionTest T2 | throw+catch IMSE |
| IllegalMonitorStateException (HW path) | HW (IO_EXCPT) | T | HwExceptionTest T3 | Native.wr(EXC_MON) -> sys_exc -> IMSE |

### 2.2 Summary

- **Well tested**: NPE (13 sub-tests: explicit throw, invokevirtual, getfield/putfield int/long/ref, invokespecial, invokeinterface, iaload, iastore, aaload), AIOOBE (negative + upper bounds), CCE (4 catches), athrow (8 tests), ArithmeticException (div-by-zero, idiv + irem + ldiv + lrem), StackOverflowError (throw+catch), IllegalMonitorStateException (throw+catch + HW exception path via IO_EXCPT)
- **Remaining gaps**: NPE on invokesuper (has microcode null check but untestable — `this` is always non-null in super calls)

---

## 3. SMP Test Gaps

### 3.1 Current SMP Tests

| Test | Cores | App | What it Verifies |
|---|---|---|---|
| JopSmpNCoreHelloWorldSim | 2 (default) | NCoreHelloWorld | All cores boot, print, toggle watchdog |
| JopSmpSmallNCoreDebugSim | 2 | Small debug | SMP lock debugging |
| JopSmpSdramNCoreHelloWorldSim | 4 (default) | NCoreHelloWorld | Multi-core over SDRAM path |
| JopSmpDdr3NCoreHelloWorldSim | N | NCoreHelloWorld | SMP with DDR3 controller |
| JopSmpBramSim | 2 | Small (GC) | SMP GC allocation + collection |
| JopSmpCacheStressSim | 2 | SmpCacheTest | Cross-core A$/O$ snoop invalidation (20 rounds) |
| JopJvmTestsSmpBramSim | 2 | JvmTests (DoAll) | 52/53 JVM tests under arbitration pressure |
| JopIhluNCoreHelloWorldSim | 2 | NCoreHelloWorld | IHLU per-object locking |
| JopIhluGcBramSim | 2 | HelloWorld (GC) | IHLU + GC drain mechanism |

### 3.2 Missing SMP Tests

| Gap | Risk | Description |
|---|---|---|
| Lock contention stress test | HIGH | No test with >2 cores hammering `synchronized` blocks concurrently. Current SMP tests use simple watchdog toggles. |
| ~~Cache snoop invalidation under load~~ | ~~HIGH~~ | DONE — `JopSmpCacheStressSim` tests cross-core A$/O$ snoop invalidation with 20 rounds of array + field writes/reads. |
| GC halt during active computation | MEDIUM | `JopSmpBramSim` runs GC but doesn't verify that halted cores resume correctly with consistent state after GC completes. |
| SMP exception handling | MEDIUM | No test throws exceptions across multiple cores simultaneously. Shared pre-allocated exception objects (NPExc, ABExc) could have race conditions. |
| 4+ core BRAM GC | MEDIUM | `JopSmpBramSim` is 2-core only. 4-core BRAM GC sim exists for SDRAM but not BRAM. |
| Core-to-core signal boot race | LOW | Boot protocol tested implicitly but no adversarial timing test. |
| ~~JVM tests on SMP~~ | ~~MEDIUM~~ | DONE — `JopJvmTestsSmpBramSim` runs 52/53 tests on 2-core SMP (DeepRecursion excluded — needs stack cache in SMP). |

---

## 4. Cache Coherency Test Gaps

### 4.1 Method Cache

- **Tested**: Implicitly by all tests (every method call exercises the method cache).
- **Gap**: No test for method cache eviction and reload under memory pressure. No test for the tag=0 false hit fix (bug #25 regression).
- **Recommendation**: A test that calls many distinct methods (exceeding cache capacity) and verifies correct execution after eviction/reload.

### 4.2 Object Cache (O$)

- **Tested**: NativeMethods tests O$ behavior explicitly (field caching with `Native.getField`/`putField`). All field tests exercise O$ implicitly.
- **Gap**: No test for O$ snoop invalidation in SMP (one core writes a field, another reads it). The `readObjectCache` persistence fix (bug #12) has no dedicated regression test.
- **Recommendation**: SMP test where Core 0 writes a field and Core 1 reads it, verifying the snoop invalidation clears the O$ entry.

### 4.3 Array Cache (A$)

- **Tested**: All array operations exercise A$. LongTest exercises long array ops. ArrayTest2/3 exercise various types.
- **Gap**: No test for A$ snoop invalidation (one core does iastore, another does iaload on same array). The `snoopDuringFill` flag (preventing re-validation if snoop fires mid-fill) is untested. No test for A$ eviction behavior (FIFO, 16 entries).
- **Recommendation**: SMP test where Core 0 does `iastore` and Core 1 does `iaload`, verifying cross-core coherency through snoop invalidation.

### 4.4 JBC Cache

- **Tested**: Method cache reloads JBC as part of method invocation. Pipelined BC fill tested implicitly.
- **Gap**: No explicit test for JBC byte ordering (bug #5 — `BC_WRITE` byte-swap). No test for JBC reload after eviction from method cache.

---

## 5. Regression Coverage for 25 Fixed Bugs

| # | Bug | Regression Test? | Risk if Regresses |
|---|---|---|---|
| 1 | JBC RAM read-write collision (readSync/writeFirst) | No dedicated test | HIGH - Silent data corruption in bytecode fetch |
| 2 | iastore 3-operand timing (IAST_WAIT) | T - ArrayTest3 (iastore on all types) | HIGH - Would corrupt stack |
| 3 | HANDLE_ACCESS I/O routing (addrIsIo for HWO) | No dedicated test | MEDIUM - Only affects HardwareObject fields |
| 4 | SpinalHDL `+\|` vs `+^` (saturating vs expanding) | No dedicated test | LOW - Only affects array length edge case |
| 5 | Putfield bcopd timing (PF_WAIT) | T - All putfield tests (IntField etc.) | HIGH - All putfield would fail |
| 6 | BmbSdramCtrl32 burst read corruption (isBurst flag) | Partial - SDRAM sims | HIGH - Affects all SDRAM reads |
| 7 | SdramCtrl CKE gating (SdramCtrlNoCke) | Partial - SDRAM sims under load | HIGH - Multi-core backpressure |
| 8 | CmpSync GC halt deadlock (lock owner exempt) | Partial - JopSmpBramSim (GC) | CRITICAL - Deadlock in SMP GC |
| 9 | DebugProtocol GOT_CORE payload length | T - JopDebugProtocolSim | MEDIUM - Debug protocol broken |
| 10 | DebugController packWord resize | T - JopDebugProtocolSim | MEDIUM - Debug protocol broken |
| 11 | JopCore debugHalted wiring | T - JopDebugProtocolSim | LOW - Debug only |
| 12 | readArrayCache/readObjectCache persistence | No dedicated test | HIGH - Stale cache data on I/O reads |
| 13 | Array cache SMP coherency (snoop bus) | Partial - SMP sims with A$ | HIGH - Stale array data in SMP |
| 14 | A$ fill interleaving corruption (burst read) | Partial - SDRAM SMP sims | CRITICAL - Data corruption in 4-core SDRAM |
| 15 | BytecodeFetchStage branch type remapping | T - Ifacmp (if_acmpeq/ne), BranchTest1 (ifnull/ifnonnull) | HIGH - Wrong branch decisions |
| 16 | System.arraycopy A$ stale data | T - SystemCopy | MEDIUM - Stale data after arraycopy |
| 17 | sys_exc wrong method offset (ldi 6 -> 8) | T - NullPointer, PutRef (exceptions) | CRITICAL - All hardware exceptions broken |
| 18 | invokevirtual null_pointer missing delay slots | T - NullPointer T1 | HIGH - Stack corruption on NPE |
| 19 | GC conservative stack scanner null dereference | Partial - GC sims with null-heavy code | HIGH - GC crash on null refs |
| 20 | Method cache tag=0 false hit (tagValid) | T - PutRef (was failing before fix) | HIGH - Bytecode fetch corruption |
| 21 | invokespecial null check (separated from invokestatic) | No dedicated test | HIGH - NPE not thrown for invokespecial on null |
| 22 | NullPointer T3 (invokespecial null) | T - NullPointer test exists but invokespecial null sub-test was dropped | See NullPointer.java header comments |
| 23 | JBC byte ordering | No dedicated test | HIGH - All bytecodes garbled |
| 24 | Pipelined BC fill timing | Implicit - all tests use method cache | MEDIUM - Only manifests under specific timing |
| 25 | Method cache tag=0 false hit (listed as #20) | Same as #20 | - |

### Bugs with NO regression test (highest risk)

1. **Bug #1 (JBC RAM collision)** - No test specifically triggers read-write collision timing
2. **Bug #3 (HANDLE_ACCESS I/O routing)** - No HardwareObject test in JVM suite
3. **Bug #4 (`+|` vs `+^`)** - No edge-case array length test
4. **Bug #12 (cache persistence)** - No test that does I/O read followed by memory read in same idle state
5. **Bug #21 (invokespecial null check)** - The NullPointer test header says "invokespecial is NOT tested"

---

## 6. Simulation Harness Gaps

### 6.1 Current Simulation Harnesses

#### Core Single-Core Simulations

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopCoreBramSim | BRAM | Smallest | 2M | Basic single-core boot + Hello World |
| JopCoreBramLargeSim | BRAM (2MB) | Smallest | - | Address wrapping effects |
| JopCoreWithSdramSim | SDRAM | Smallest | 500k | SDRAM path |
| JopSmallGcBramSim | BRAM | Small (GC) | 20M | GC allocation + collection |
| JopSmallGcSdramSim | SDRAM | Small (GC) | 5M | GC over SDRAM |
| JopSmallGcCacheSim | DDR3/Cache | Small (GC) | - | DDR3 cache path (LruCacheCore) |
| JopSmallGcMigSim | DDR3/MIG | Small (GC) | - | DDR3 MIG stub path |
| JopSmallGcHighLatencySim | DDR3 model | Small (GC) | - | GC under high DDR3 latency (20-60 cycles) |
| JopSmallGcGhdlSim | BRAM | Small (GC) | - | GHDL-compatible sim (non-Verilator) |
| JopEchoSim | BRAM | echo.asm | - | UART echo microcode, bit-serial RX/TX |
| JopCoreLatencySweep | BRAM | Smallest | - | 0-5 extra latency cycles |
| JopCoreLatencyDebug | BRAM | Smallest | - | Detailed latency debugging with BMB trace |

#### Serial Boot Simulations

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopSdramSerialSim | SDRAM | serial protocol | - | Serial program download to SDRAM |
| JopSdramSerialBootSim | SDRAM | serial boot + app | - | Full serial boot sequence |
| JopDdr3SerialBootSim | DDR3 model | serial boot + app | - | Serial boot with DDR3 controller |

#### JVM Test Simulations

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopJvmTestsBramSim | BRAM | JvmTests (DoAll) | 25M | 53 JVM bytecode tests |
| JopJvmTestsSmpBramSim | BRAM (2-core) | JvmTests (DoAll) | 40M | 52/53 JVM tests on 2-core SMP |
| JopJvmTestsStackCacheBramSim | BRAM (512KB) | JvmTests (DoAll) | - | JVM tests with stack cache + DMA spill |

#### SMP Simulations

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopSmpNCoreHelloWorldSim | BRAM | NCoreHelloWorld | 40M | SMP boot + watchdog |
| JopSmpSmallNCoreDebugSim | BRAM (2-core) | Small debug | - | SMP lock debugging |
| JopSmpSdramNCoreHelloWorldSim | SDRAM | NCoreHelloWorld | 5M | SMP over SDRAM |
| JopSmpDdr3NCoreHelloWorldSim | DDR3 model | NCoreHelloWorld | - | SMP with DDR3 controller |
| JopSmpBramSim | BRAM | Small (GC) | 20M | SMP GC |
| JopSmpCacheStressSim | BRAM (2-core) | SmpCacheTest | 20M | Cross-core A$/O$ snoop (20 rounds) |
| JopIhluNCoreHelloWorldSim | BRAM | NCoreHelloWorld | 40M | IHLU per-object locking (2-core) |
| JopIhluGcBramSim | BRAM (2-core) | HelloWorld (GC) | 100M | IHLU + GC drain mechanism |

#### FPU Simulations

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopFpuBramSim | BRAM | JvmTests (DoAll) | 27M | 60 JVM tests with HW FPU (float ops via BmbFpu) |
| BmbFpuSim | Unit test | — | — | BmbFpu I/O peripheral (9 operations: ADD, SUB, MUL, DIV) |
| JopFpuAdapterSim | Unit test | — | — | JopFpuAdapter FSM (22 operations with IEEE 754 verification) |

#### Debug & Special

| Harness | Memory | App | Cycles | What it Tests |
|---|---|---|---|---|
| JopDebugProtocolSim | BRAM | Smallest | 250k | Debug protocol commands (39 checks) |
| JopDebugSim | BRAM | Smallest | - | TCP debug server (port 4567, Eclipse) |
| JopInterruptSim | BRAM (2MB) | InterruptTest | 4M | Timer interrupt chain |
| JopGcTraceCaptureSim | BRAM + cache | Small (GC) | - | BMB trace capture to gc_bmb_trace.hex |
| JopStackCacheWaveSim | BRAM (512KB) | JvmTests | - | Stack cache with waveform (.vcd) |

### 6.2 Missing Harness Combinations

| Gap | Priority | Description |
|---|---|---|
| JVM tests on SDRAM | MEDIUM | `JopJvmTestsBramSim` only runs on BRAM. SDRAM latency could expose timing bugs. |
| ~~JVM tests on SMP~~ | ~~HIGH~~ | DONE — `JopJvmTestsSmpBramSim` (52/53 pass on 2-core SMP). |
| SMP GC on SDRAM | MEDIUM | `JopSmpBramSim` runs GC on BRAM. SDRAM SMP GC sim would test BmbSdramCtrl32 under GC pressure. |
| Interrupt + GC combined | LOW | No test combining timer interrupts with GC activity. |
| Long-running SDRAM stability | LOW | `JopSmallGcSdramSim` runs 5M cycles. FPGA hardware runs hours. Simulation gap is large. |

---

## 7. Priority List: Top 10 Missing Tests by Bug-Hiding Risk

| Rank | Missing Test | Risk | Rationale |
|---|---|---|---|
| ~~1~~ | ~~**SMP cache coherency stress test**~~ | ~~CRITICAL~~ | DONE — `JopSmpCacheStressSim` tests cross-core A$/O$ snoop invalidation (T1: array, T2: fields, T3: 20 rounds). |
| ~~2~~ | ~~**invokespecial null pointer test**~~ | ~~HIGH~~ | DONE — `NullPointer.java` T3 tests invokespecial on null (catches NPE). |
| ~~3~~ | ~~**invokeinterface/invokesuper null pointer test**~~ | ~~HIGH~~ | DONE — `NullPointer.java` T9 tests invokeinterface on null (catches NPE). |
| ~~4~~ | ~~**JVM test suite on SMP**~~ | ~~HIGH~~ | DONE — `JopJvmTestsSmpBramSim` runs 52/53 on 2-core SMP (DeepRecursion excluded — needs stack cache). |
| ~~5~~ | ~~**Array NPE tests** (iaload/iastore on null array)~~ | ~~HIGH~~ | DONE — `NullPointer.java` T10 (iaload), T11 (iastore), T12 (aaload) test null array accesses. |
| ~~6~~ | ~~**Long array bounds exception test**~~ | ~~MEDIUM~~ | DONE — `LongArrayBounds.java` T1-T4 test AIOOBE on laload/lastore (negative index + upper bounds). |
| ~~7~~ | ~~**Static long field test**~~ | ~~MEDIUM~~ | DONE — `LongStaticField.java` tests getstatic_long/putstatic_long with multiple values. |
| ~~8~~ | ~~**Double arithmetic test**~~ | ~~MEDIUM~~ | DONE — `DoubleArithmetic.java` tests dadd, dsub, dmul, ddiv, drem, dneg, dcmpl, dcmpg. |
| ~~9~~ | ~~**Cache persistence bug regression**~~ | ~~MEDIUM~~ | DONE — `CachePersistence.java` tests I/O read followed by object/array field reads (bug #12 regression). |
| ~~10~~ | ~~**Float array test**~~ | ~~LOW~~ | DONE — `FloatArray.java` tests faload/fastore with float arrays (4 elements, overwrite, arraylength). |

---

## 8. Recommended Test Additions

### 8.1 ~~SMP Cache Coherency Stress Test~~ — DONE

Implemented as `SmpCacheTest.java` + `JopSmpCacheStressSim`. Tests cross-core A$/O$ snoop invalidation (T1: array, T2: fields, T3: 20 rounds).

### 8.2 ~~invokespecial Null Pointer Test~~ — DONE

Implemented as `NullPointer.java` T3 (invokespecial on null reference, catches NPE).

### 8.3 ~~invokeinterface Null Pointer Test~~ — DONE

Implemented as `NullPointer.java` T9 (invokeinterface on null reference, catches NPE).

### 8.4 ~~JVM Test Suite on SMP~~ — DONE

Implemented as `JopJvmTestsSmpBramSim` (2-core, 52/53 pass — DeepRecursion needs stack cache).

### 8.5 ~~Array Null Pointer Tests~~ — DONE

Implemented as `NullPointer.java` T10 (iaload on null), T11 (iastore on null), T12 (aaload on null).

### 8.6 ~~Long Array Bounds Exception Test~~ — DONE

Implemented as `LongArrayBounds.java` T1-T4 (negative index + upper bounds for both laload and lastore).

### 8.7 ~~Static Long Field Test~~ — DONE

Implemented as `LongStaticField.java` (getstatic_long/putstatic_long with multiple values).

### 8.8 ~~Double Arithmetic Test~~ — DONE

Implemented as `DoubleArithmetic.java` (dadd, dsub, dmul, ddiv, drem, dneg, dcmpl, dcmpg).

### 8.9 ~~Cache Persistence Regression Test~~ — DONE

Implemented as `CachePersistence.java` (I/O read followed by object/array field reads, bug #12 regression).

### 8.10 ~~Float Array Test~~ — DONE

Implemented as `FloatArray.java` (faload/fastore with float arrays, 4 elements + overwrite + arraylength).

---

## 9. Summary Statistics

### Bytecode Coverage

- **Total JOP-implemented bytecodes**: ~175 (excluding reserved/unimplemented)
- **Directly tested**: ~155 (89%)
- **Implicitly tested** (via test infrastructure): ~15 (9%)
- **Untested**: ~4 (2%) — a few edge cases (swap now tested via BCEL injection)

### JVM Test Suite

- **Total test classes**: 61 (in `java/apps/JvmTests/src/jvm/`)
- **Tests run by DoAll**: 53
- **Infrastructure**: 2 (TestCase base class, NullTestIface interface), 1 coordinator (DoAll)
- **SMP**: 52/53 pass on 2-core (DeepRecursion excluded — needs stack cache)

### Exception Coverage

- **Exception types defined**: 6 (NPE, AIOOBE, ArithmeticException, CCE, SOError, IMSE)
- **Tested exception types**: 6 (NPE, AIOOBE, ArithmeticException, CCE, StackOverflowError, IllegalMonitorStateException)
- **Partially tested**: 0
- **Untested**: 0

### Formal Verification

- **Suites**: 16 (SymbiYosys + Z3)
- **Total properties**: 97

### Bug Regression Coverage

- **Total bugs fixed**: 31
- **With direct regression test**: 17 (55%)
- **With partial regression test** (sim coverage): 8 (26%)
- **With NO regression test**: 6 (19%)

### Simulation Harnesses

- **Total runnable harnesses**: 37
- **Core single-core**: 12
- **Serial boot**: 3
- **JVM test**: 3 (BRAM, SMP, stack cache)
- **FPU**: 3 (system integration, I/O peripheral, adapter)
- **SMP**: 8 (CmpSync, IHLU, cache stress, DDR3)
- **Debug & special**: 5
- **Verilog generation**: 3 (Questa, SDRAM Questa, Vivado xsim)

### SMP Coverage

- **SMP boot**: Tested (2-core BRAM, 4-core SDRAM, DDR3)
- **SMP GC**: Tested (2-core BRAM)
- **SMP cache coherency**: Tested (`JopSmpCacheStressSim` — cross-core A$/O$ snoop, 20 rounds)
- **SMP JVM tests**: Tested (`JopJvmTestsSmpBramSim` — 52/53 pass on 2-core)
- **SMP IHLU**: Tested (`JopIhluNCoreHelloWorldSim` — 2-core with per-object locking, `JopIhluGcBramSim` — GC with IHLU)
- **SMP DDR3**: Tested (`JopSmpDdr3NCoreHelloWorldSim`)
- **SMP lock contention**: NOT stress-tested
- **SMP exception handling**: NOT tested

### Recommended Tests — All 10 DONE

All 10 originally recommended tests have been implemented, plus 2 additional tests:
- **SwapTest**: Tests SWAP bytecode (0x5F) via BCEL injection (javac never emits it)
- **HwExceptionTest**: Tests StackOverflowError (throw+catch), IllegalMonitorStateException (throw+catch + HW exception path via IO_EXCPT)

Remaining gap: NPE on invokesuper (untestable — `this` is always non-null in super calls)
