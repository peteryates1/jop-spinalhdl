# Jopa Scala Jump Table Generation - Implementation Summary

**Date:** 2026-01-05
**Status:** ✅ COMPLETE

---

## Summary

Successfully modified the Jopa microcode assembler to automatically generate `JumpTableData.scala` for SpinalHDL alongside the existing `jtbl.vhd` VHDL output.

## Changes Made

### 1. Modified `java/jopa/src/com/jopdesign/tools/Jopa.java`

**Added Method:** `writeScalaJumpTable()` (lines 737-813)
- Generates Scala object with 256-entry jump table
- Maps Java bytecodes (0x00-0xFF) → microcode ROM addresses
- Includes bytecode mnemonics in comments
- For unmapped bytecodes, includes both bytecode name and "unmapped -> sys_noim"
- Exports special addresses (sys_noim, sys_int, sys_exc) as metadata

**Modified Method:** `pass2()` (line 559)
- Added call to `writeScalaJumpTable()` after jtbl.vhd generation
- Reuses existing `jinstrMap` data collected in pass1()
- No duplicate data structures needed

### 2. Modified `core/spinalhdl/build.sbt`

**Added Source Directory:** (line 42)
```scala
Compile / unmanagedSourceDirectories += baseDirectory.value / ".." / ".." / "asm" / "generated"
```
- Includes asm/generated as a Scala source directory
- No symlinks needed
- Works across different operating systems
- Automatically included in clean builds

### 3. Build Process Integration

**Workflow:**
```
cd asm/
make            # Runs gcc preprocessor + Jopa assembler
                # Generates:
                #   - jtbl.vhd (VHDL jump table)
                #   - JumpTableData.scala (NEW - Scala jump table)
                #   - rom.vhd, mem_rom.dat (microcode ROM)
                #   - mem_ram.dat (stack RAM init)

cd ../core/spinalhdl/
sbt compile     # Automatically finds and compiles JumpTableData.scala
```

## Generated File: JumpTableData.scala

**Location:** `asm/generated/JumpTableData.scala`
**Package:** `jop`
**Size:** 282 lines

**Structure:**
```scala
package jop

object JumpTableData {
  /**
   * Jump table entries (256 entries, indexed by Java bytecode)
   */
  def entries: Seq[BigInt] = Seq(
    BigInt(0x218),  // 0x00: nop
    BigInt(0x21A),  // 0x01: aconst_null
    BigInt(0x219),  // 0x02: iconst_m1
    BigInt(0x21A),  // 0x03: iconst_0
    // ... 252 more entries ...
    BigInt(0x0EC),  // 0x0C: fconst_1, unmapped -> sys_noim
    BigInt(0x0EC)   // 0xFF: unmapped -> sys_noim
  )

  /** Special addresses for interrupt/exception handling */
  val sysNoimAddr = 0x0EC  // sys_noim (not implemented)
  val sysIntAddr  = 0x0DA  // sys_int (interrupt handler)
  val sysExcAddr  = 0x0E2  // sys_exc (exception handler)
}
```

## Verification

### Compilation Test
```bash
cd core/spinalhdl
sbt compile
```

**Result:** ✅ SUCCESS
- Output: "compiling 12 Scala sources" (includes JumpTableData.scala)
- No compilation errors
- JumpTableData object accessible from package `jop`

### Generated Content Verification

**Sample Entries:**
```scala
BigInt(0x218),  // 0x00: nop
BigInt(0x21A),  // 0x01: aconst_null
BigInt(0x194),  // 0x60: iadd
BigInt(0x19C),  // 0x64: isub
BigInt(0x1A4),  // 0x68: imul
```

**Unmapped Bytecodes (with names):**
```scala
BigInt(0x0EC),  // 0x0C: fconst_1, unmapped -> sys_noim
BigInt(0x0EC),  // 0x53: aastore, unmapped -> sys_noim
BigInt(0x0EC),  // 0x62: fadd, unmapped -> sys_noim
```

**Total Entries:** 256 (0x00-0xFF)
**Implemented Instructions:** 188
**Unmapped Instructions:** 68 (all route to sys_noim handler)

## Usage in SpinalHDL

The generated jump table can now be used in BytecodeFetchStage or JumpTable components:

```scala
import jop.JumpTableData

// Option 1: ROM-based lookup
class JumpTable(pcWidth: Int = 11) extends Component {
  val io = new Bundle {
    val bytecode = in Bits(8 bits)
    val jpaddr = out UInt(pcWidth bits)
  }

  val rom = Mem(UInt(pcWidth bits), 256)
  rom.init(JumpTableData.entries.map(_.toInt))

  io.jpaddr := rom.readAsync(io.bytecode.asUInt)
}

// Option 2: Access special addresses
val noimAddr = JumpTableData.sysNoimAddr
val intAddr = JumpTableData.sysIntAddr
val excAddr = JumpTableData.sysExcAddr
```

## Benefits

1. **Single Source of Truth:** Jump table generated from microcode source (jvm.asm)
2. **No Manual Maintenance:** Automatically updated when microcode changes
3. **Type Safety:** Scala compile-time type checking
4. **No Duplication:** Reuses existing jinstrMap data from pass1()
5. **Cross-Platform:** No symlinks, works on all operating systems
6. **Integrated Build:** Automatic inclusion in sbt compilation

## Next Steps

Ready to proceed with Phase A implementation:
1. ✅ Jump table data available (JumpTableData.scala)
2. ✅ SpinalHDL can compile and access jump table
3. ⏩ Ready to implement BytecodeFetchStage component
4. ⏩ Ready to implement JumpTable component

## Files Modified

### Core Changes
- `java/jopa/src/com/jopdesign/tools/Jopa.java` (+77 lines)
- `core/spinalhdl/build.sbt` (+3 lines)

### Generated Files
- `asm/generated/JumpTableData.scala` (282 lines, auto-generated)

### Documentation
- `docs/jopa-scala-modification.md` (detailed modification guide)
- `docs/JOPA-SCALA-GENERATION-SUMMARY.md` (this file)

## Testing Checklist

- [x] Jopa compiles successfully
- [x] JumpTableData.scala generated
- [x] 256 entries present (0x00-0xFF)
- [x] Bytecode mnemonics included in comments
- [x] Unmapped bytecodes show "bytecode_name, unmapped -> sys_noim"
- [x] Special addresses (sys_noim, sys_int, sys_exc) exported
- [x] SpinalHDL build finds and compiles JumpTableData.scala
- [x] No compilation errors
- [x] Package `jop` accessible

---

**Status:** ✅ COMPLETE - Ready for Phase A (BytecodeFetchStage implementation)
