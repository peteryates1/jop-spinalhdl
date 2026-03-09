/**
  * ComputeUnit — Interface definitions for JOP compute units
  *
  * New decoupled architecture using three microcode instructions:
  *   - stop (0x01F): pops TOS into CU's internal operand stack
  *   - sthw (0x140 + 6-bit operand): starts CU operation, 6-bit selects unit+op
  *   - ldop (0x0E1): pops CU result stack, pushes onto JOP stack
  *
  * ComputeUnitBundle: top-level interface between pipeline and ComputeUnitTop
  * ComputeUnitCoreBundle: per-unit internal interface (used by ICU/FCU/LCU/DCU)
  *
  * Concrete implementations:
  *   IntegerComputeUnit — imul, idiv, irem
  *   FloatComputeUnit   — fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg
  *   LongComputeUnit    — ladd, lsub, lmul, ldiv, lrem, lcmp, lshl, lshr, lushr
  *   DoubleComputeUnit  — dadd, dsub, dmul, ddiv, i2d, d2i, l2d, d2l, f2d, d2f, dcmpl, dcmpg
  */
package jop.core

import spinal.core._

/** Legacy bundle — kept temporarily for migration reference */
case class ComputeUnitLegacyBundle() extends Bundle {
  val a        = in UInt (32 bits)   // TOS
  val b        = in UInt (32 bits)   // NOS
  val c        = in UInt (32 bits)   // TOS-2
  val d        = in UInt (32 bits)   // TOS-3
  val wr       = in Bool ()          // sthw asserted — capture operands + start op
  val opcode   = in Bits (8 bits)    // JVM bytecode selects operation
  val resultLo = out UInt (32 bits)  // TOS for 32-bit ops, lo word for 64-bit
  val resultHi = out UInt (32 bits)  // unused for 32-bit ops, hi word for 64-bit
  val is64     = out Bool ()         // true -> result occupies both TOS and NOS
  val busy     = out Bool ()         // stalls pipeline until done
}

/** Top-level CU interface between pipeline and ComputeUnitTop */
case class ComputeUnitBundle() extends Bundle {
  val din    = in UInt(32 bits)      // Data from stack TOS (for stop instruction)
  val push   = in Bool()             // Push din into CU operand stack (stop)
  val opcode = in UInt(6 bits)       // Unit select [5:4] + operation [3:0] (sthw)
  val start  = in Bool()             // Start operation (sthw)
  val dout   = out UInt(32 bits)     // Result word (for ldop instruction)
  val pop    = in Bool()             // Pop result (ldop)
  val busy   = out Bool()            // Stalls pipeline until done
}

/** Per-unit core interface (ICU/FCU/LCU/DCU each use this) */
case class ComputeUnitCoreBundle() extends Bundle {
  val operands    = in Vec(UInt(32 bits), 4)   // Operand stack contents
  val op          = in UInt(4 bits)            // Operation select (from sthw[3:0])
  val start       = in Bool()                  // Start pulse
  val resultLo    = out UInt(32 bits)          // Low result word
  val resultHi    = out UInt(32 bits)          // High result word (for 64-bit results)
  val resultCount = out UInt(2 bits)           // Number of result words (1 or 2)
  val busy        = out Bool()                 // Unit is busy
}
