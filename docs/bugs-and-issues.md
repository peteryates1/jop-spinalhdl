# Bugs and Issues

Master index of all bugs found and fixed during the SpinalHDL JOP port, plus
open JVM issues requiring workarounds. For detailed investigation notes on
each bug, see [Implementation Notes](implementation-notes.md).

---

## JVM Runtime Issues (All Fixed)

These were limitations in the JOP JVM runtime. All have been fixed.

### 1. System.arraycopy (FIXED — was misdiagnosed)

Originally reported as "`System.arraycopy()` crashes with a stack overflow".
The actual root cause was bug #2: `StringBuilder` had no `toString()` method,
so any `StringBuilder` operation ending in `.toString()` triggered infinite
recursion via `Object.toString()` → `"Object " + hashCode()` → new
`StringBuilder` → `.toString()` → ... → stack overflow.

With `StringBuilder.toString()` fixed (bug #2), `System.arraycopy` works
correctly. `StringConcat` test T7 verifies this: appends 20 chars to a
default-capacity (16) `StringBuilder`, forcing `ensureCapacity()` →
`System.arraycopy()` resize. Passes in simulation.

The two-pass `char[]` workarounds in `DirEntry.formatShortName()` and
`Fat32FileSystem.stripIllegalChars()` are no longer necessary but harmless.

### 2. String Concatenation with int (FIXED)

`"text" + intValue` compiles to `StringBuilder.append(int)` which calls
`Integer.toString()`. `StringBuilder` had no `toString()` method, so it
fell back to `Object.toString()` which does `"Object " + hashCode()` —
another string concatenation with int, creating infinite recursion and
stack overflow.

**Fix**: Added `StringBuilder.toString()` returning `new String(value, 0, count)`.

**Test**: `StringConcat` in JvmTests (58/58 pass). Tests `"x=" + 42`,
negative ints, zero, `String.valueOf(int)`, and multiple appends.

**Affected code**: Any Java source using `"text" + intValue` or
`String.valueOf(int)`. The existing hardware test programs already used
character-by-character workarounds which remain valid but are no longer
strictly necessary.

### 3. Object Memory Not Zeroed on Allocation (FIXED)

JOP's `GC.newObject()` and `GC.newArray()` did not zero the allocated data
area. All fields — especially references — contained garbage from whatever
previously occupied that memory. A `field == null` check would fail on an
uninitialized reference field because it contained a non-null garbage value.

The original code had a TODO comment acknowledging this: `// TODO: memory
initialization is needed // either on scope creation+exit or in new`.

**Fix**: Added zeroing loops in `GC.newObject()` and `GC.newArray()` that
write 0 to each word of the newly allocated data area (inside the
synchronized block, after `allocPtr -= size`). This is eager zeroing —
cost is proportional to object size, bounded and predictable for real-time.

Explicit constructors in existing code (FAT32 classes, etc.) are now
redundant but harmless — they serve as documentation of the expected defaults.

### 4. Hardware Division-by-Zero Exception Not Catchable

JOP's `idiv`/`irem` bytecodes are implemented in Java (not microcode) because
the original microcode had a bug with `0x80000000` operands. The Java
implementation handles division correctly, but the division-by-zero path
fires a hardware exception asynchronously (after JPC has advanced past the
`idiv` bytecode). The exception unwinds at the wrong JPC and cannot find a
matching `catch (ArithmeticException)` handler.

**Workaround**: Check for zero before dividing.

```java
// May not be catchable on JOP:
try {
    int result = a / b;
} catch (ArithmeticException e) {
    // handler may not be reached
}

// CORRECT — explicit check:
if (b == 0) {
    // handle error
} else {
    int result = a / b;
}
```

See [Division by Zero Analysis](div-by-zero-analysis.md) for the full
investigation.

---

## Fixed Bugs — RTL / Hardware

### SD Card Controller (BmbSdNative)

Three bugs found during hardware verification. All passed in simulation
because the testbench does not model real card timing.

| # | Bug | Fix | Found by |
|---|-----|-----|----------|
| 27 | WAIT_CRC_STATUS sampled immediately without waiting for start bit (DAT0 low) — falsely reported CRC errors on successful writes | Wait for DAT0 low before counting CRC status bits, with timeout | SD Native Exerciser |
| 28 | `dataTimeoutCnt` not reset on `startWrite` (only on `startRead`) — stale timeout values on writes after reads | Added `dataTimeoutCnt := 0` to `startWrite` handler | Code review after #27 |
| 29 | WAIT_BUSY checked for DAT0 high without first confirming DAT0 went low — false exit on CRC status end bit, next command issued during card programming | Two-phase wait: first DAT0 low (busy start), then DAT0 high (busy end) | FAT32 filesystem testing |

See [DB_FPGA SD Card](db-fpga-sd-card.md) for full details.

### VGA Text Controller (BmbVgaText)

| # | Bug | Fix |
|---|-----|-----|
| — | Pixel pipeline `bitIndex` used pipelined `hCounter` instead of `hLookAhead` (hCounter+3), causing 3-pixel circular shift within each character cell | `bitIndex` now uses pipelined `hLookAhead` for correct 3-stage pipeline alignment |

### Ethernet (GMII)

| # | Bug | Fix |
|---|-----|-----|
| — | `e_rxd[5]` and `e_rxd[6]` pin assignments swapped vs reference design | Corrected pin assignments — 100% CRC failure resolved |
| — | SDC never declared `create_clock` for PHY clocks — entire PHY clock domains unconstrained | Added explicit `create_clock` for `e_rxc`, corrected PLL clock names |

See [DB_FPGA Ethernet](db-fpga-ethernet.md) for full details.

### PLL Device Family

| # | Bug | Fix |
|---|-----|-----|
| — | `dram_pll.vhd` had `intended_device_family => "Cyclone II"` instead of `"Cyclone IV GX"` — c3 output stuck at GND | Changed to correct device family |

### SDR SDRAM

| # | Bug | Fix |
|---|-----|-----|
| — | SpinalHDL `SdramCtrl` placed DQ flip-flops in fabric instead of dedicated I/O cells — setup/hold violations invisible to STA | Replaced with Altera `altera_sdram_tri_controller` BlackBox |

See [SDR SDRAM GC Hang](sdr-sdram-gc-hang.md) for the full investigation.

---

## Fixed Bugs — Pipeline and Microcode

29 bugs found during the SpinalHDL port. These are numbered in the
[Implementation Notes](implementation-notes.md). Key categories:

### Microcode Bugs (Latent in Original VHDL JOP)

| # | Bug | Impact |
|---|-----|--------|
| 22 | `sys_exc` called `jjhp+6` (monitorState) instead of `jjhp+8` (except) — all hardware exceptions threw `IllegalMonitorStateException` | All NPE/ABE showed wrong exception type |
| 23 | `invokevirtual`/`invokeinterface` null check missing 2 delay slot NOPs — stack corruption on null invocation | Stack corruption |
| 25 | Method cache tag=0 false hit — evicted blocks matched addr=0 lookups (no tagValid bit) | Corrupt instruction fetch |

### Pipeline Bugs

| # | Bug | Impact |
|---|-----|--------|
| 7 | `iastore` 3-operand timing — captured addrReg before stack shifted (IAST_WAIT state missing) | Static field corruption on array stores |
| 11 | `putfield` bcopd not ready — `stpf` microcode has no `opd` flag, index captured as 0 (PF_WAIT state missing) | All putfield writes went to field 0 |
| 20 | Branch type remapping missing for `if_acmpeq`, `if_acmpne`, `ifnull`, `ifnonnull` — tested wrong conditions | Branch condition evaluation wrong |
| 29 | `BytecodeFetchStage` jopd corruption during stack cache rotation stall — `jopd(7:0)` updated every cycle during 770+ cycle stalls | Processor hang on deep recursion (200+ levels) |

### Memory Controller Bugs

| # | Bug | Impact |
|---|-----|--------|
| 8 | HANDLE_ACCESS missing I/O routing — HardwareObject field reads returned 0 | All `SysDevice` fields unreadable |
| 12 | BmbSdramCtrl32 burst data corruption — stale non-burst responses incremented burst word counter | Bytecode corruption in 4+ core SDRAM |
| 18 | Array cache no cross-core invalidation — `iastore` on Core X invisible to Core Y's A$ | 4-core + A$ failure |
| 19 | A$ fill interleaving — 4 single-word reads could be interleaved by arbiter | Core hang in 4+ core SDRAM |
| 21 | `System.arraycopy` doesn't invalidate A$ — `wrMem()` bypasses cache invalidation | Stale array data after copy |
| 26 | Hardware exception wrote to I/O 0x04 instead of 0x84 after I/O base change | Exceptions silently ignored |

### Other Pipeline/I/O Bugs

| # | Bug | Impact |
|---|-----|--------|
| 1 | BC fill write address off-by-one | Bytecode RAM corruption |
| 2 | iaload/caload +1 offset | Wrong array access results |
| 3 | JBC RAM read-write collision (SpinalHDL `readSync` dontCare) | Bytecode fetch corruption |
| 4 | Pipeline stall missing for high-latency memory | Incorrect memory results |
| 5 | UartCtrl RX one-cycle valid pulse (not buffered) | Dropped UART bytes |
| 9 | BmbSys missing IO_LOCK and IO_CPUCNT registers | GC deadlock, SMP failure |
| 10 | cpuStart NPE on core 1 boot (GC not ready) | SMP boot failure |
| 13 | SdramCtrl CKE gating creates response pipeline misalignment (simulation only) | Data corruption in sim |
| 17 | readAcache/readOcache flags persist across I/O reads — cache data returned instead of I/O | UART output 4x expansion |

### Debug Subsystem Bugs

| # | Bug | Impact |
|---|-----|--------|
| 14 | DebugProtocol GOT_CORE used wrong byte for payload length | All debug commands with payload dropped |
| 15 | DebugController `packWord` bit extraction on narrow signals — elaboration crash | Debug controller won't build |
| 16 | `debugHalted` wired to CmpSync halt instead of debug halt input | Halted status always False |

---

## Fixed Bugs — Software / Java Runtime

| # | Bug | Fix |
|---|-----|-----|
| 24 | `GC.push()` dereferences null handle (address 0) during conservative stack scanning — triggers hardware NPE during GC | Added `if (ref == 0) return;` guard |
| 10 | `Startup.java` cpuStart array requires GC before GC is ready — NPE on multi-core boot | Removed array; direct main loop entry |
| 21 | `System.arraycopy` raw writes (`wrMem`) don't invalidate array cache — stale data | Added `Native.invalidate()` after copy |

---

## Design Quirks (Not Bugs)

### SpinalHDL `+|` vs `+^`

`+|` is **saturating** add (clamps at max value). `+^` is **expanding** add
(result is 1 bit wider). Use `+^` when you need overflow-safe increment
(e.g., BMB length to byte count: `length +^ 1`). Using `+|` silently clamps
instead of expanding, which can be very hard to debug.

### SD Card Write Clock Speed

CMD24 (WRITE_BLOCK) fails at 20 MHz and 13.3 MHz on the QMTECH DB_FPGA
board due to signal integrity on SD card traces. Reads work at all speeds.
Use divider=3 (~10 MHz) for data transfers. See
[DB_FPGA SD Card](db-fpga-sd-card.md).

### Hardware Division-by-Zero

The `idiv`/`irem` microcode implementation has a known bug with `0x80000000`
operands (noted in comments from 2004). Division is now implemented in Java
(`JVM.java`) which handles this case correctly. The microcode is commented
out in `asm/src/jvm.asm`. See [Division by Zero Analysis](div-by-zero-analysis.md).
