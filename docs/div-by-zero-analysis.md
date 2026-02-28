# Division by Zero ArithmeticException Analysis

> **STATUS: RESOLVED.** Option A (recommended below) has been implemented:
> `f_idiv`, `f_irem`, `f_ldiv`, and `f_lrem` in `JVM.java` now use
> `throw JVMHelp.ArithExc` instead of the hardware exception path.
> The `DivZero` test (3 sub-tests) is enabled and passes. See
> [Bugs and Issues](bugs-and-issues.md) for the summary.

## Summary

JOP's `idiv`, `irem`, `ldiv`, and `lrem` bytecodes are implemented in Java
(not microcode), and their division-by-zero handling originally triggered a
software exception via the hardware exception mechanism. This exception was
architecturally intended to be catchable, but the mechanism was fragile due
to an asynchronous exception firing mid-method that created a non-standard
stack state. This document traces the exact code path, identifies the root
cause of failure, and documents the fix.

## 1. How `idiv` Is Currently Dispatched

### Bytecode-to-microcode mapping

The `idiv` bytecode (0x6C) has **no microcode implementation**. The original
microcode for `idiv` and `irem` was commented out in `asm/src/jvm.asm`
(lines 999-1209) with the note:

```
// idiv, irem   WRONG when one operand is 0x80000000
//   but is now in JVM.java
```

This dates to 2004-02-07 when "div/rem moved to JVM.java".

### Jump table entry

In `asm/generated/JumpTableData.scala`, the entries for all division/remainder
bytecodes map to `sys_noim` (address 0x0B1):

| Bytecode | Opcode | Jump table entry | Target |
|----------|--------|-----------------|--------|
| idiv     | 0x6C   | 0x0B1           | sys_noim |
| ldiv     | 0x6D   | 0x0B1           | sys_noim |
| fdiv     | 0x6E   | 0x0B1           | sys_noim (soft float) |
| ddiv     | 0x6F   | 0x0B1           | sys_noim (soft double) |
| irem     | 0x70   | 0x0B1           | sys_noim |
| lrem     | 0x71   | 0x0B1           | sys_noim |
| frem     | 0x72   | 0x0B1           | sys_noim |
| drem     | 0x73   | 0x0B1           | sys_noim |

### sys_noim dispatch mechanism

When BytecodeFetchStage encounters `idiv` (0x6C), the jump table outputs the
`sys_noim` microcode address. The `sys_noim` handler in `asm/src/jvm.asm`
(line 811) performs a "Java-in-Java" dispatch:

```asm
sys_noim:
    ldjpc
    ldi 1
    sub
    stjpc           // back up JPC to point at the triggering bytecode
    nop
    nop
    ldm jjp         // load JVM method table pointer
    nop opd
    ld_opd_8u       // re-read the bytecode opcode from JBC RAM
    ldi 255
    and
    dup
    add             // *2 (each method table entry is 2 words)
    add             // jjp + 2 * bytecode_value
    jmp invoke      // invoke the corresponding JVM.f_xxx() method
    nop
    nop
```

This reads the bytecode value (0x6C = 108), computes the method table index
`jjp + 2 * 108 = jjp + 216`, and invokes `JVM.f_idiv()` via the standard
`invoke` microcode mechanism. The `invoke` creates a proper stack frame
with saved SP, JPC (return address in the calling method), VP, CP, and MP.

### Java implementation

`JVM.f_idiv()` in `java/runtime/src/jop/com/jopdesign/sys/JVM.java`
(line 210):

```java
static int f_idiv(int a, int b) {
    if (b == 0) {
        // division by zero exception
        Native.wrMem(Const.EXC_DIVZ, Const.IO_EXCPT);
        return 0;
    }
    // ... 32-iteration long division algorithm ...
}
```

The `EXC_DIVZ` constant is 8 (defined in `Const.java` line 144). This is
a **software** exception number (hardware exceptions use 1-7).

## 2. The Exact Code Path for Division by Zero

### Step-by-step execution trace

Given user code:
```java
try {
    int c = a / b;  // b == 0
} catch (ArithmeticException e) {
    caught = true;
}
```

**Phase 1: Dispatch to f_idiv**

1. Pipeline fetches `idiv` bytecode at JPC position P in user method
2. Jump table maps 0x6C to `sys_noim`
3. `sys_noim` backs up JPC to P, re-reads bytecode, computes method index
4. `invoke` microcode creates stack frame saving user method context
   (old_sp, old_jpc = P+1, old_vp, old_cp, old_mp)
5. `f_idiv(a, b)` begins executing

**Phase 2: Division-by-zero detection and I/O write**

6. `f_idiv` evaluates `b == 0` -- true
7. `Native.wrMem(Const.EXC_DIVZ, Const.IO_EXCPT)` executes:
   - The PreLinker replaces this with `jopsys_wrmem` bytecode
   - Microcode: `stmwa` (pop IO_EXCPT address), `stmwd` (pop EXC_DIVZ data),
     `wait`, `wait`, `nop nxt`
   - The memory controller routes the write to BmbSys (device 0, sub-addr 4)

**Phase 3: Exception trigger in BmbSys**

8. BmbSys receives write to addr 4 (IO_EXCPT):
   ```scala
   excTypeReg := io.wrData(7 downto 0)  // stores 8 (EXC_DIVZ)
   excPend := True
   ```
9. Next cycle: `io.exc = excPend && !excDly` fires a single-cycle pulse
10. BytecodeFetchStage latches `excPend` (remains set until next `jfetch`)

**Phase 4: sys_exc preemption (THE CRITICAL POINT)**

11. `jopsys_wrmem` completes with `nop nxt`, which triggers `jfetch`
12. BytecodeFetchStage's `excPend` is set, so the jump table outputs
    `sysExcAddr` instead of the next bytecode (`iconst_0` for `return 0`)
13. **The `return 0` statement in `f_idiv` NEVER EXECUTES**
14. `sys_exc` fires INSIDE `f_idiv`'s execution context

**Phase 5: sys_exc handler**

15. `sys_exc` microcode (jvm.asm line 791):
    ```asm
    sys_exc:
        ldjpc           // current JPC (inside f_idiv's bytecodes)
        ldi 1
        sub
        stjpc           // back up by 1
        ldm jjhp
        ldi 8           // except() is method index 4, offset 4*2=8
        add
        jmp invoke      // invoke JVMHelp.except()
    ```
16. The `invoke` creates a new frame on top of f_idiv's interrupted state,
    saving f_idiv's context (mp, cp, vp, corrected jpc)

**Phase 6: JVMHelp.except() and throw**

17. `except()` (JVMHelp.java line 95):
    ```java
    static void except() {
        saved_sp = Native.getSP();
        if (Native.rdMem(Const.IO_EXCPT) == Const.EXC_SPOV) {
            Native.setSP(Const.STACK_OFF);
        }
        handleException();
    }
    ```
18. `handleException()` reads IO_EXCPT, gets EXC_DIVZ (8), does
    `throw ArithExc` (the pre-allocated ArithmeticException)

**Phase 7: athrow dispatch**

19. The `throw` compiles to `athrow` bytecode (0xBF)
20. `athrow` maps to `sys_noim`, which invokes `JVM.f_athrow(ArithExc)`
21. `f_athrow` walks the stack frame chain:

```
Frame chain (bottom to top):
  user method (testIdivZero)    <-- has try/catch handler
  f_idiv frame (from sys_noim) <-- no handler
  except() frame (from sys_exc) <-- no handler
  f_athrow frame (from sys_noim for athrow)
```

22. `f_athrow` walks UP through the frames:
    - f_athrow's own frame: skip
    - except() frame: no handler for ArithmeticException, unwind
    - f_idiv frame: use `mp+1` to compute `fp = vp + args + locals`, skip
    - **user method frame: the saved PC should be near `idiv`, within
      the try block's [begin, end) range**

## 3. Root Cause: Why the Exception Is Not Reliably Catchable

The mechanism described above is architecturally intended to work, and
the code path is logically correct. However, there are multiple
interacting issues that make it fragile and prone to failure:

### 3.1 Asynchronous exception firing mid-method (PRIMARY CAUSE)

The `Native.wrMem(EXC_DIVZ, IO_EXCPT)` write triggers the exception
mechanism immediately. The `io.exc` pulse from BmbSys reaches
BytecodeFetchStage and latches `excPend` within 1-2 cycles. The very next
bytecode fetch after `jopsys_wrmem` completes is preempted by `sys_exc`.

This means `sys_exc` fires **inside** `f_idiv`, not after `f_idiv` returns.
The `f_idiv` method is interrupted at an arbitrary point in its bytecode
stream. The `return 0` statement never executes.

This creates a non-standard stack state: `f_idiv`'s locals and any
temporaries from the if-check evaluation are on the stack, but the
method did not complete its return sequence. The `sys_exc` handler
creates a new invoke frame on top of this interrupted state.

While `f_athrow`'s frame-walking algorithm uses the method struct
(args + locals counts) to compute frame boundaries (which should be
correct regardless of stack contents), the interrupted state of `f_idiv`
means:

- **The stack depth inside `f_idiv` at the point of interruption may
  not match what `f_athrow` expects.** The `f_athrow` algorithm assumes
  clean frame boundaries, but if `f_idiv` has extra temporary values on
  the stack (from the bytecodes between `jopsys_wrmem` and the interrupted
  `iconst_0`), these could shift the frame pointer calculation.

- **The JPC saved by `sys_exc` points into `f_idiv`'s bytecodes**, not
  the user method. This is correctly handled by `f_athrow` (it skips
  `f_idiv`'s frame since it has no handler), but adds an extra frame
  to traverse.

### 3.2 Contrast with working hardware exceptions (NPE/ABE)

Hardware exceptions for null pointer and array bounds work correctly
because they fire from the **memory controller**, which is at the
microcode level. The exception fires during a memory operation
(`HANDLE_READ`, etc.), and the `nop nxt` at the end of the offending
microcode sequence triggers the `jfetch` that picks up `sys_exc`.
At that point:

- The JPC points to the bytecode that caused the violation (e.g.,
  `getfield`, `iaload`)
- The stack is in a known state (the microcode cleaned up)
- `sys_exc` saves the correct JPC and creates a clean frame

For division by zero, the exception fires from JAVA code via an I/O
write, which means:
- The pipeline is deep inside `f_idiv`'s bytecode stream
- The JPC points to a location inside `f_idiv`, not the user method
- An extra frame (f_idiv) is in the chain

### 3.3 Stack state uncertainty

When `jopsys_wrmem` completes inside `f_idiv`, the stack should be
clean (both arguments to `wrMem` were popped by `stmwa`/`stmwd`).
The next bytecode would be `iconst_0` (to prepare the `return 0`
result). Since `iconst_0` is preempted before execution, no extra
value is pushed.

However, the JVM stack state depends on the exact bytecode sequence
generated by `javac`. If the compiler generates different code
(e.g., optimized bytecodes), the stack state at the interruption
point could vary.

### 3.4 excTypeReg persistence

After `except()` reads `IO_EXCPT` and gets `EXC_DIVZ`, the
`excTypeReg` register in BmbSys retains the value 8. If any
subsequent exception fires before a new value is written, the stale
EXC_DIVZ value could cause confusion. However, this is unlikely to
be the root cause since the read happens before the throw.

### 3.5 Historical context

The original JOP (VHDL) has the **exact same code** in its `JVM.java`,
with the same `Native.wrMem(EXC_DIVZ, IO_EXCPT)` pattern. The original
JOP test suites also did not test div-by-zero catching:

- The `jvm/` test suite (Martin Schoeberl) has no `DivZero` test
- The `jvmtest/` suite (Guenther Wimpassinger) has div-by-zero tests
  in `TcIntArithmetic.divTest()`, but `TcIntArithmetic` is **commented
  out** in `JopTestSuite.java` (with note "iinc constant to big")
- `TcLongArithmetic` is enabled and has `ldiv`-by-zero tests, but its
  pass/fail status on JOP hardware is unknown

This suggests the feature was designed but never fully validated.

## 4. Recommended Fixes

### Option A: Throw from Java instead of triggering hardware exception (LOW EFFORT)

**Change**: Replace the I/O write with a direct Java `throw`.

```java
static int f_idiv(int a, int b) {
    if (b == 0) {
        throw JVMHelp.ArithExc;  // direct throw, no I/O write
    }
    // ... division algorithm ...
}
```

Similarly for `f_irem`, `f_ldiv`, `f_lrem`.

**Why this works**: A Java `throw` compiles to `athrow` bytecode, which
goes through `sys_noim -> f_athrow()`. The `f_athrow` stack walk starts
from `f_idiv`'s frame and correctly finds the handler in the calling
method. No I/O write, no `sys_exc`, no asynchronous exception timing.

This is the same mechanism used by `checkcast` (line 775 in JVM.java):
```java
throw JVMHelp.CCExc;
```

And by `handleException()` itself (line 158):
```java
throw ArithExc;
```

Both of these work correctly, proving that `throw` from Java runtime
methods invoked via `sys_noim` is a valid pattern.

**Effort**: ~30 minutes. Change 4 methods (`f_idiv`, `f_irem`, `f_ldiv`,
`f_lrem`), rebuild Java (`cd java && make all`), rebuild microcode,
test in simulation.

**Changes required**:
1. `java/runtime/src/jop/com/jopdesign/sys/JVM.java`: Replace
   `Native.wrMem(Const.EXC_DIVZ, Const.IO_EXCPT); return 0;` with
   `throw JVMHelp.ArithExc;` in all 4 methods
2. `java/runtime/src/jop/com/jopdesign/sys/JVMHelp.java`: Verify
   `ArithExc` is `public static` (it is, line 47)
3. `java/apps/JvmTests/src/jvm/DoAll.java`: Enable `DivZero` test
4. Rebuild: `cd java && make clean && make all`
5. Test: `sbt "Test / runMain jop.system.JopJvmTestsBramSim"`

### Option B: Microcode division with hardware exception (HIGH EFFORT)

**Change**: Implement `idiv`/`irem` in microcode with a hardware
exception path (like NPE/ABE).

The original microcode for `idiv` and `irem` exists in `jvm.asm`
(commented out, lines 999-1209). It's a 32-iteration loop. To add
div-by-zero detection:

```asm
idiv:
    stm b           // save divisor
    stm a           // save dividend
    ldm b
    nop
    bnz idiv_nonzero
    nop
    nop
    // b == 0: fire hardware exception
    pop             // clean up stack
    ldi io_exc
    stmwa
    ldi exc_divz    // new constant: exc_divz = 8
    stmwd
    wait
    wait
    nop nxt
idiv_nonzero:
    // ... existing 32-iteration division loop ...
```

**Why this works**: The hardware exception fires at the microcode level,
just like NPE/ABE. The `nop nxt` at the end triggers `sys_exc` with
the JPC pointing at the `idiv` bytecode in the user method, creating
a clean exception path.

**Effort**: ~4-8 hours. Requires:
- Uncommenting and modifying the microcode (add zero-check)
- Updating `jvm.asm` jump table labels so `idiv`/`irem` have
  microcode addresses instead of `sys_noim`
- Rebuilding the microcode ROM (`asm/generated/mem_rom.dat`)
- Regenerating the jump table (`asm/generated/JumpTableData.scala`)
- Verifying the 32-iteration loop still fits in microcode ROM
- The division algorithm has a known bug with `0x80000000` (noted
  in comments) which was the original reason for moving to Java

**Disadvantage**: The commented-out microcode has a known bug with
`0x80000000` operands. The Java implementation handles this case
correctly. Moving back to microcode means either accepting the bug
or adding more microcode to handle it.

### Option C: Fix the I/O-triggered exception path (MEDIUM EFFORT)

**Change**: Instead of writing to `IO_EXCPT` and returning, write to
`IO_EXCPT` and immediately enter an infinite loop. The `sys_exc`
fires at the loop's `jfetch`, which is a clean state.

```java
if (b == 0) {
    Native.wrMem(Const.EXC_DIVZ, Const.IO_EXCPT);
    for (;;) {}  // sys_exc will preempt this
}
```

**Why this might work**: The `for(;;){}` compiles to `goto $self` (a
single-byte infinite loop). The `sys_exc` fires at the first `jfetch`
of this goto, which is a known clean stack state. However, the saved
JPC still points inside `f_idiv`, so the frame walk still has to
traverse f_idiv's frame.

**Effort**: ~1 hour. But this is a hack and doesn't address the
fundamental issue.

### Recommended approach

**Option A is strongly recommended.** It is the simplest, most correct,
and most maintainable fix. It reuses the existing `throw` mechanism
that already works for `checkcast`, `nullPoint()`, `arrayBound()`,
and other runtime exceptions. It eliminates the asynchronous exception
timing entirely.

## 5. Related Issues

### 5.1 All four division methods affected

| Method | Bytecode | Line in JVM.java |
|--------|----------|-------------------|
| `f_idiv` | idiv (0x6C) | 210-249 |
| `f_irem` | irem (0x70) | 305-338 |
| `f_ldiv` | ldiv (0x6D) | 250-288 |
| `f_lrem` | lrem (0x71) | 339-372 |

All four use the same pattern:
```java
if (b == 0) {
    Native.wrMem(Const.EXC_DIVZ, Const.IO_EXCPT);
    return 0;  // or return 0L
}
```

### 5.2 Float/double division

`fdiv` (0x6E) and `ddiv` (0x6F) delegate to `SoftFloat32.float_div()` and
`SoftFloat64.double_div()` respectively. IEEE 754 specifies that float/double
division by zero produces infinity (not an exception), so no ArithmeticException
is needed.

`frem` (0x72) and `drem` (0x73) similarly delegate to soft float routines.

### 5.3 EXC_DIVZ constant

`EXC_DIVZ = 8` (Const.java line 144) is documented as a "software generated"
exception number, outside the hardware range (1-7). Both `BmbSys` and
`JVMHelp.handleException()` correctly handle this value. If Option A is
implemented, `EXC_DIVZ` becomes unused but can be left in place for
documentation.

### 5.4 DivZero test

`java/apps/JvmTests/src/jvm/math/DivZero.java` tests three scenarios:
1. `testIdivZero()` -- catch ArithmeticException from `int a / 0`
2. `testIremZero()` -- catch ArithmeticException from `int a % 0`
3. `testNormalAfterException()` -- verify normal division works after catch

This test is enabled in `DoAll.java` and passes (all 58 JVM tests pass).

## 6. Key Files

| File | Purpose |
|------|---------|
| `java/runtime/src/jop/com/jopdesign/sys/JVM.java` | f_idiv/f_irem/f_ldiv/f_lrem implementations |
| `java/runtime/src/jop/com/jopdesign/sys/JVMHelp.java` | except(), handleException(), pre-allocated exceptions |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | EXC_DIVZ = 8, IO_EXCPT address |
| `java/runtime/src/jop/com/jopdesign/sys/Native.java` | wrMem() native method declaration |
| `asm/src/jvm.asm` | sys_noim, sys_exc handlers; commented-out idiv/irem microcode |
| `asm/generated/JumpTableData.scala` | Jump table: idiv/irem -> 0x0B1 (sys_noim) |
| `spinalhdl/src/main/scala/jop/io/BmbSys.scala` | IO_EXCPT register, exc pulse generation |
| `spinalhdl/src/main/scala/jop/pipeline/BytecodeFetchStage.scala` | excPend latch, sys_exc dispatch |
| `java/apps/JvmTests/src/jvm/math/DivZero.java` | Disabled test case |
| `java/apps/JvmTests/src/jvm/DoAll.java` | Test registration (DivZero commented out) |
