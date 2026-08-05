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
  dcuConfig: DoubleComputeUnitConfig = DoubleComputeUnitConfig()
) extends Component {
  val io = ComputeUnitBundle()

  // Instantiate all 4 CU cores
  val icu = IntegerComputeUnit(icuConfig)
  val fcu = FloatComputeUnit(fcuConfig)
  val lcu = LongComputeUnit(lcuConfig)
  val dcu = DoubleComputeUnit(dcuConfig)

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

  // Route operands and op to all units (unrolled — heterogeneous types)
  icu.io.operands := opStack
  icu.io.op := io.opcode(3 downto 0)
  fcu.io.operands := opStack
  fcu.io.op := io.opcode(3 downto 0)
  lcu.io.operands := opStack
  lcu.io.op := io.opcode(3 downto 0)
  dcu.io.operands := opStack
  dcu.io.op := io.opcode(3 downto 0)

  icu.io.start := io.start && unitSel === 0
  fcu.io.start := io.start && unitSel === 1
  lcu.io.start := io.start && unitSel === 2
  dcu.io.start := io.start && unitSel === 3

  // ========================================================================
  // Result sequencing
  // ========================================================================
  val resultPtr = Reg(UInt(1 bits)) init(0)
  when(io.start) { resultPtr := 0 }
  when(io.pop) { resultPtr := resultPtr + 1 }

  // Result mux — select active unit's result, sequence hi then lo
  val activeResultLo = latchedUnitSel.mux(
    0 -> icu.io.resultLo, 1 -> fcu.io.resultLo,
    2 -> lcu.io.resultLo, 3 -> dcu.io.resultLo
  )
  val activeResultHi = latchedUnitSel.mux(
    0 -> icu.io.resultHi, 1 -> fcu.io.resultHi,
    2 -> lcu.io.resultHi, 3 -> dcu.io.resultHi
  )
  val activeResultCount = latchedUnitSel.mux(
    0 -> icu.io.resultCount, 1 -> fcu.io.resultCount,
    2 -> lcu.io.resultCount, 3 -> dcu.io.resultCount
  )

  // First pop: if 2-word result, return Hi; if 1-word, return Lo
  // Second pop: return Lo
  io.dout := Mux(resultPtr === 0 && activeResultCount === 2, activeResultHi, activeResultLo)

  // Busy: OR of all units, plus start (immediate busy on dispatch cycle)
  io.busy := icu.io.busy || fcu.io.busy || lcu.io.busy || dcu.io.busy || io.start
}
