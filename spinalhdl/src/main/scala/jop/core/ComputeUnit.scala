/**
  * ComputeUnit — Abstract base for JOP multi-cycle compute units
  *
  * All compute units share the same interface to the JOP pipeline:
  *   - a, b, c, d: four 32-bit stack operands (a=TOS, b=NOS, c=TOS-2, d=TOS-3)
  *   - opcode: JVM bytecode selects the operation
  *   - wr: pulse to start operation
  *   - resultLo: low 32-bit result (TOS for 32-bit ops, lo word for 64-bit)
  *   - resultHi: high 32-bit result (unused for 32-bit ops, hi word for 64-bit)
  *   - is64: true when result occupies both TOS and NOS (double/long)
  *   - busy: stalls pipeline until operation completes
  *
  * For 32-bit ops: operands are a and b.
  * For 64-bit ops: value1 = d:c (hi:lo), value2 = b:a (hi:lo).
  *
  * Concrete implementations:
  *   FloatComputeUnit   — IEEE 754 single-precision (fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg)
  *   DoubleComputeUnit  — IEEE 754 double-precision (dadd, dsub, dmul, ddiv, i2d, d2i, l2d, d2l, f2d, d2f, dcmpl, dcmpg)
  *   LongComputeUnit    — multi-cycle long ops (lmul, ldiv, lrem, lshl, lshr, lushr)
  *   IntegerComputeUnit — imul, idiv, irem
  */
package jop.core

import spinal.core._

case class ComputeUnitBundle() extends Bundle {
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

abstract class ComputeUnit extends Component {
  val io = ComputeUnitBundle()
}
