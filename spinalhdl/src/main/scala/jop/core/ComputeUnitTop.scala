/**
  * ComputeUnitTop — Top-level CU wrapper for JOP pipeline
  *
  * Instantiates all four CU cores (ICU, FCU, LCU, DCU) and manages:
  *   - Shared operand stack (4 deep, fed by stop instructions)
  *   - Unit selection and operation dispatch (from sthw instruction)
  *   - Result sequencing (for ldop instruction)
  *
  * Unit selection encoding (sthw opcode[5:4]):
  *   00 = ICU (IntegerComputeUnit)
  *   01 = FCU (FloatComputeUnit)
  *   10 = LCU (LongComputeUnit)
  *   11 = DCU (DoubleComputeUnit)
  *
  * Result pop ordering for 64-bit results:
  *   First ldop pushes result_hi, second ldop pushes result_lo.
  *   After both: stack is ..., result_hi, result_lo (TOS=result_lo)
  */
package jop.core

import spinal.core._

case class ComputeUnitTop(
  icuConfig: IntegerComputeUnitConfig = IntegerComputeUnitConfig(),
  fcuConfig: FloatComputeUnitConfig = FloatComputeUnitConfig(),
  lcuConfig: LongComputeUnitConfig = LongComputeUnitConfig(),
  dcuConfig: DoubleComputeUnitConfig = DoubleComputeUnitConfig(),
  hasIcu: Boolean = true,
  hasFcu: Boolean = true,
  hasLcu: Boolean = true,
  hasDcu: Boolean = true
) extends Component {
  val io = ComputeUnitBundle()

  // Instantiate only the units the config actually dispatches to.
  //
  // Implementation choice is per bytecode (Hardware / Microcode / Java), so the
  // right question is "does ANY instruction map to this unit in hardware?" —
  // JopCoreConfig.needs*Compute answers exactly that, and JopPipeline passes it.
  //
  // Previously all four were instantiated unconditionally and the per-unit
  // `with*` flags were relied on to hollow them out for synthesis to prune. That
  // works for FCU (disappears) and DCU (4 LE shell), but NOT for LCU: its base
  // 64-bit ALU (ladd/lsub/lneg/lcmp) has no `with*` flag and is unconditional,
  // so 403 LEs of unreachable logic survived in every core — ~4.8k LEs across a
  // 12-core SMP build. Skipping instantiation removes the shells too.
  val icu = if (hasIcu) Some(IntegerComputeUnit(icuConfig)) else None
  val fcu = if (hasFcu) Some(FloatComputeUnit(fcuConfig))   else None
  val lcu = if (hasLcu) Some(LongComputeUnit(lcuConfig))    else None
  val dcu = if (hasDcu) Some(DoubleComputeUnit(dcuConfig))  else None

  /** Unit i's io, if present. Index matches the opcode's unit-select field. */
  val unitIo: Seq[Option[ComputeUnitCoreBundle]] =
    Seq(icu.map(_.io), fcu.map(_.io), lcu.map(_.io), dcu.map(_.io))

  // ========================================================================
  // Shared operand stack (4 deep)
  // ========================================================================
  val opStack = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opSp = Reg(UInt(3 bits)) init(0)

  when(io.push) {
    opStack(opSp.resize(2)) := io.din
    opSp := opSp + 1
  }
  when(io.start) {
    opSp := 0  // reset for next operation
  }

  // ========================================================================
  // Route operands and control to all units
  // ========================================================================
  val unitSel = io.opcode(5 downto 4)
  val latchedUnitSel = Reg(UInt(2 bits)) init(0)
  when(io.start) { latchedUnitSel := unitSel }

  // Route operands, op and start to whichever units exist
  for ((u, i) <- unitIo.zipWithIndex) u.foreach { cu =>
    cu.operands := opStack
    cu.op       := io.opcode(3 downto 0)
    cu.start    := io.start && unitSel === i
  }

  // ========================================================================
  // Result sequencing
  // ========================================================================
  val resultPtr = Reg(UInt(1 bits)) init(0)
  when(io.start) { resultPtr := 0 }
  when(io.pop) { resultPtr := resultPtr + 1 }

  // Result mux — select active unit's result, sequence hi then lo
  // Absent units read as zero. Their select value is unreachable — decode never
  // emits an opcode for a unit that has no hardware instruction — so the
  // constant only exists to keep the mux total.
  private def pick(f: ComputeUnitCoreBundle => UInt, width: Int) =
    latchedUnitSel.mux((0 to 3).map { i =>
      i -> unitIo(i).map(f).getOrElse(U(0, width bits))
    }: _*)

  val activeResultLo    = pick(_.resultLo, 32)
  val activeResultHi    = pick(_.resultHi, 32)
  val activeResultCount = pick(_.resultCount, 2)

  // First pop: if 2-word result, return Hi; if 1-word, return Lo
  // Second pop: return Lo
  io.dout := Mux(resultPtr === 0 && activeResultCount === 2, activeResultHi, activeResultLo)

  // Busy: OR of all units, plus start (immediate busy on dispatch cycle)
  io.busy := unitIo.flatten.map(_.busy).foldLeft(False)(_ || _) || io.start
}
