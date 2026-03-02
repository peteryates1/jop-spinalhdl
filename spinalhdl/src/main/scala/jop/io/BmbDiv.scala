package jop.io

import spinal.core._

/**
 * Hardware Integer Divider I/O Peripheral with auto-capture.
 *
 * Binary restoring division algorithm, 34 cycles (1 sign setup + 32 iterations +
 * 1 sign fixup). Produces both quotient and remainder simultaneously.
 *
 * Uses the same auto-capture pattern as BmbFpu: the write triggers the operation
 * and captures both operands (bout→dividend, wrData→divisor). The divider's busy
 * signal stalls the pipeline until the result is ready.
 *
 * Write register map (address encodes operation):
 *   0: Start signed division (bout→dividend, wrData→divisor)
 *   1: Start signed remainder mode (same operation, different read address)
 *
 * Read register map:
 *   0: Quotient
 *   1: Remainder
 *   2: Ready status (bit 0 = ready)
 *
 * Division by zero: returns 0 for quotient, dividend for remainder (matching JVM
 * behavior — the JVM spec says idiv/irem throw ArithmeticException on divide by
 * zero, but the microcode checks for zero before triggering hardware division).
 *
 * Integer overflow: MIN_VALUE / -1 returns MIN_VALUE for quotient, 0 for remainder
 * (matching two's complement wrap behavior).
 *
 * FPGA cost: ~200 LCs per core, 0 DSP blocks.
 */
case class BmbDiv() extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)    // = aout (TOS) from pipeline — divisor
    val bout   = in Bits(32 bits)    // Direct NOS wire from pipeline — dividend
    val rdData = out Bits(32 bits)
    val busy   = out Bool()          // High while computing → feeds memBusy
  }

  // ==========================================================================
  // Divider State Machine
  // ==========================================================================

  val computing = Reg(Bool()) init(False)

  // Operand registers (signed inputs, work on magnitudes)
  val dividend  = Reg(UInt(32 bits)) init(0)
  val divisor   = Reg(UInt(32 bits)) init(0)
  val quotSign  = Reg(Bool()) init(False)  // True if quotient should be negative
  val remSign   = Reg(Bool()) init(False)  // True if remainder should be negative

  // Working registers for restoring division
  val remainder = Reg(UInt(33 bits)) init(0)  // 33-bit: extra bit for subtraction sign
  val quotient  = Reg(UInt(32 bits)) init(0)
  val divMag    = Reg(UInt(32 bits)) init(0)  // Magnitude of divisor

  val cycle     = Reg(UInt(6 bits)) init(0)   // 0-33: phases

  // Result registers (latched when done)
  val quotResult = Reg(Bits(32 bits)) init(0)
  val remResult  = Reg(Bits(32 bits)) init(0)

  // Division by zero detection
  val divByZero = Reg(Bool()) init(False)

  // Integer overflow: MIN_VALUE / -1
  val intOverflow = Reg(Bool()) init(False)

  // ==========================================================================
  // Write Handling — auto-capture operands + start computation
  // ==========================================================================

  when(io.wr) {
    val sDividend = io.bout.asSInt
    val sDivisor  = io.wrData.asSInt

    // Check division by zero
    when(sDivisor === 0) {
      divByZero := True
      intOverflow := False
      computing := False  // Immediate result
      quotResult := B(0, 32 bits)
      remResult  := io.bout  // Remainder = dividend
    // Check integer overflow: MIN_VALUE / -1
    }.elsewhen(sDividend === S(-2147483648, 32 bits) && sDivisor === S(-1, 32 bits)) {
      divByZero := False
      intOverflow := True
      computing := False  // Immediate result
      quotResult := B"32'h80000000"  // MIN_VALUE
      remResult  := B(0, 32 bits)            // 0
    }.otherwise {
      divByZero := False
      intOverflow := False

      // Compute magnitudes and signs
      val dvdNeg = sDividend < 0
      val dvsNeg = sDivisor < 0
      val dvdMag = Mux(dvdNeg, (-sDividend).asUInt, sDividend.asUInt)
      val dvsMag = Mux(dvsNeg, (-sDivisor).asUInt, sDivisor.asUInt)

      quotSign := dvdNeg ^ dvsNeg     // Quotient negative if signs differ
      remSign  := dvdNeg              // Remainder has sign of dividend

      dividend := dvdMag
      divMag   := dvsMag

      // Initialize working registers
      remainder := U(0, 33 bits)
      quotient  := U(0, 32 bits)
      cycle     := 0
      computing := True
    }
  }

  // ==========================================================================
  // Restoring Division Algorithm — 32 iterations
  // ==========================================================================

  when(computing) {
    when(cycle < 32) {
      // Shift remainder left by 1, bring in next dividend bit (MSB first)
      val shiftedRem = (remainder(31 downto 0) @@ dividend(31 - cycle.resized)).resize(33)

      // Trial subtraction
      val trialSub = shiftedRem - divMag.resize(33)

      when(!trialSub(32)) {
        // Subtraction succeeded (non-negative): set quotient bit = 1
        remainder := trialSub
        quotient(31 - cycle.resized) := True
      }.otherwise {
        // Subtraction failed (negative): restore, quotient bit = 0
        remainder := shiftedRem
        quotient(31 - cycle.resized) := False
      }

      cycle := cycle + 1
    }.otherwise {
      // Done — apply signs and latch results
      val rawQuot = quotient.asSInt
      val rawRem  = remainder(31 downto 0).asSInt

      val signedQuot = Mux(quotSign, -rawQuot, rawQuot)
      val signedRem  = Mux(remSign, -rawRem, rawRem)

      quotResult := signedQuot.asBits
      remResult  := signedRem.asBits
      computing  := False
    }
  }

  // ==========================================================================
  // Read Mux
  // ==========================================================================

  io.rdData := 0
  switch(io.addr(1 downto 0)) {
    is(0) { io.rdData := quotResult }        // Quotient
    is(1) { io.rdData := remResult }         // Remainder
    is(2) { io.rdData(0) := !computing }     // Ready status
  }

  // ==========================================================================
  // Busy Output — stalls pipeline while divider is computing
  // ==========================================================================

  io.busy := computing
}
