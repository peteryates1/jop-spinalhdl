/**
  * ComputeUnit — Abstract base for JOP multi-cycle compute units
  *
  * All compute units share the same 64-bit interface to the JOP pipeline:
  *   - operand0/operand1: input operands (64 bits each)
  *   - opcode: JVM bytecode selects the operation
  *   - wr: pulse to start operation
  *   - result: 64-bit output (32-bit results use lower half, zero-extended)
  *   - is64: true when result occupies both TOS and NOS (double/long)
  *   - busy: stalls pipeline until operation completes
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
  val operand0 = in UInt (64 bits)   // A (or {hi32, lo32} for wide operands)
  val operand1 = in UInt (64 bits)   // B
  val wr       = in Bool ()          // sthw asserted — capture operands + start op
  val opcode   = in Bits (8 bits)    // JVM bytecode selects operation
  val result   = out UInt (64 bits)  // 32-bit ops use lower half, zero-extended
  val is64     = out Bool ()         // true -> result occupies both TOS and NOS
  val busy     = out Bool ()         // stalls pipeline until done
}

abstract class ComputeUnit extends Component {
  val io = ComputeUnitBundle()
}
