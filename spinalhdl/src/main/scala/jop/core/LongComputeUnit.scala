/**
  * LongComputeUnit — Multi-cycle 64-bit Integer Operations for JOP
  *
  * Uses ComputeUnitCoreBundle interface with 4-bit operation codes:
  *   op 0: ladd    op 1: lsub    op 2: lmul    op 3: ldiv    op 4: lrem
  *   op 5: lcmp    op 6: lshl    op 7: lshr    op 8: lushr
  *
  * Operand mapping (from CU operand stack):
  *   Binary ops (4 operands): value1 = operands(3):operands(2) (hi:lo)
  *                            value2 = operands(1):operands(0) (hi:lo)
  *   Shift ops (3 operands):  value = operands(1):operands(2) (hi:lo)
  *                            shift amount = operands(0)
  *
  * Algorithms:
  *   ladd  — Combinational 64-bit add (1 cycle)
  *   lsub  — Combinational 64-bit subtract (1 cycle)
  *   lmul  — Radix-4 sequential multiply (32 iterations, ~34 cycles)
  *   ldiv  — Binary restoring division (64 iterations, ~68 cycles)
  *   lrem  — Same divider as ldiv, returns remainder
  *   lcmp  — Combinational signed 64-bit compare (1 cycle)
  *   lshl  — Combinatorial left shift (1 cycle)
  *   lshr  — Combinatorial arithmetic right shift (1 cycle)
  *   lushr — Combinatorial logical right shift (1 cycle)
  *
  * Special cases:
  *   ldiv/lrem by zero: result = 0
  *   MIN_VALUE / -1:    quotient = MIN_VALUE, remainder = 0
  */
package jop.core

import spinal.core._

case class LongComputeUnitConfig(
    withMul:   Boolean = true,   // lmul  (op 2)
    withDiv:   Boolean = true,   // ldiv  (op 3)
    withRem:   Boolean = true,   // lrem  (op 4)
    withShift: Boolean = true    // lshl (op 6), lshr (op 7), lushr (op 8)
)

case class LongComputeUnit(config: LongComputeUnitConfig = LongComputeUnitConfig()) extends Component {

  val io = ComputeUnitCoreBundle()

  // ========================================================================
  // FSM States — all declared unconditionally; unused ones optimized away
  // ========================================================================
  object State extends SpinalEnum {
    val IDLE                              = newElement()
    val LADD_EXEC                         = newElement()
    val LCMP_EXEC                         = newElement()
    val MUL_EXEC                          = newElement()
    val DIV_SETUP, DIV_EXEC, DIV_DONE     = newElement()
    val SHIFT_EXEC                        = newElement()
    val DONE                              = newElement()
  }

  val state = Reg(State()) init (State.IDLE)

  // ========================================================================
  // Registers — all declared unconditionally; unused ones optimized away
  // ========================================================================
  val resultReg = Reg(UInt(64 bits)) init (0)
  val opcodeReg = Reg(UInt(4 bits)) init (0)
  val opaReg    = Reg(UInt(64 bits)) init (0)
  val opbReg    = Reg(UInt(64 bits)) init (0)

  // Multiply registers (radix-4)
  val mulA     = Reg(UInt(64 bits)) init (0)
  val mulB     = Reg(UInt(64 bits)) init (0)
  val mulP     = Reg(UInt(64 bits)) init (0)
  val mulCount = Reg(UInt(6 bits)) init (0)

  // Divide registers (binary restoring)
  val divDividend  = Reg(UInt(64 bits)) init (0)
  val divDivisor   = Reg(UInt(64 bits)) init (0)
  val divRemainder = Reg(UInt(65 bits)) init (0)
  val divQuotient  = Reg(UInt(64 bits)) init (0)
  val divQuotSign  = Reg(Bool()) init (False)
  val divRemSign   = Reg(Bool()) init (False)
  val divCount     = Reg(UInt(7 bits)) init (0)

  // ========================================================================
  // IO wiring
  // ========================================================================
  io.resultLo := resultReg(31 downto 0)
  io.resultHi := resultReg(63 downto 32)
  io.busy   := (state =/= State.IDLE)
  // lcmp (op 5) returns 1 word, everything else returns 2
  io.resultCount := Mux(opcodeReg === 5, U(1, 2 bits), U(2, 2 bits))

  // ========================================================================
  // FSM
  // ========================================================================
  switch(state) {

    // ----------------------------------------------------------------------
    is(State.IDLE) {
      when(io.start) {
        opcodeReg := io.op

        // Default operand capture for binary 4-operand ops
        // value1 = operands(3):operands(2) (deeper pair), value2 = operands(1):operands(0) (TOS pair)
        opaReg := (io.operands(3) ## io.operands(2)).asUInt
        opbReg := (io.operands(1) ## io.operands(0)).asUInt

        // Route to appropriate state
        // ladd (op 0) / lsub (op 1)
        when(io.op === 0 || io.op === 1) {
          state := State.LADD_EXEC
        }

        // lmul (op 2)
        if (config.withMul) {
          when(io.op === 2) {
            mulA := (io.operands(3) ## io.operands(2)).asUInt
            mulB := (io.operands(1) ## io.operands(0)).asUInt
            mulP := 0
            mulCount := 0
            state := State.MUL_EXEC
          }
        }

        // ldiv (op 3) / lrem (op 4)
        if (config.withDiv || config.withRem) {
          when(io.op === 3 || io.op === 4) {
            state := State.DIV_SETUP
          }
        }

        // lcmp (op 5)
        when(io.op === 5) {
          state := State.LCMP_EXEC
        }

        // Shift ops (op 6/7/8): 3 operands
        // Microcode pops: amount→[0], val_lo→[1], val_hi→[2]
        if (config.withShift) {
          when(io.op >= 6 && io.op <= 8) {
            opaReg := (io.operands(2) ## io.operands(1)).asUInt  // value (hi:lo)
            opbReg := io.operands(0).resize(64)                  // shift amount
            state := State.SHIFT_EXEC
          }
        }
      }
    }

    // ======================================================================
    // LADD / LSUB — Single-cycle Combinational
    // ======================================================================
    is(State.LADD_EXEC) {
      when(opcodeReg === 0) {
        resultReg := (opaReg + opbReg).resize(64)
      } otherwise {
        resultReg := (opaReg - opbReg).resize(64)
      }
      state := State.DONE
    }

    // ======================================================================
    // LCMP — Single-cycle Signed Compare
    // ======================================================================
    is(State.LCMP_EXEC) {
      val sa = opaReg.asSInt
      val sb = opbReg.asSInt
      when(sa > sb) {
        resultReg := 1
      } .elsewhen(sa < sb) {
        resultReg := U(64 bits, default -> true)  // -1 as unsigned 64-bit
      } .otherwise {
        resultReg := 0
      }
      state := State.DONE
    }

    // ======================================================================
    // LMUL — Radix-4 Sequential (32 iterations)
    // ======================================================================
    if (config.withMul) {
      is(State.MUL_EXEC) {
        // Two's complement wrapping: lower 64 bits are the same for
        // signed and unsigned multiply, so no magnitude conversion needed.
        val prod = UInt(64 bits)
        prod := mulP

        // Radix-4: process 2 bits of multiplier per cycle
        val prodAfterB0 = UInt(64 bits)
        when(mulB(0)) {
          prodAfterB0 := prod + mulA
        } otherwise {
          prodAfterB0 := prod
        }

        val prodFinal = UInt(64 bits)
        when(mulB(1)) {
          prodFinal := (prodAfterB0(63 downto 1) +^ mulA(62 downto 0)).resize(63) @@ prodAfterB0(0)
        } otherwise {
          prodFinal := prodAfterB0
        }

        mulP := prodFinal
        mulA := mulA(61 downto 0) @@ U"2'b00"
        mulB := U"2'b00" @@ mulB(63 downto 2)
        mulCount := mulCount + 1

        when(mulCount === 31) {
          resultReg := prodFinal
          state := State.DONE
        }
      }
    }

    // ======================================================================
    // LDIV/LREM — Binary Restoring Division (64 iterations)
    // ======================================================================
    if (config.withDiv || config.withRem) {
      is(State.DIV_SETUP) {
        val aSigned = opaReg.asSInt
        val bSigned = opbReg.asSInt

        // Special case: divide by zero -> result = 0
        when(opbReg === 0) {
          resultReg := 0
          state := State.DONE
        }
        // Special case: MIN_VALUE / -1 -> quotient = MIN_VALUE, remainder = 0
        .elsewhen(aSigned === S(64 bits, (63) -> true, default -> false) &&
                  bSigned === S(-1, 64 bits)) {
          when(opcodeReg === 3) {
            // ldiv: MIN_VALUE
            resultReg := opaReg
          } otherwise {
            // lrem: 0
            resultReg := 0
          }
          state := State.DONE
        } otherwise {
          // Convert to magnitudes, record signs
          divQuotSign := aSigned(63) ^ bSigned(63)
          divRemSign  := aSigned(63)

          when(aSigned(63)) {
            divDividend := (-aSigned).asUInt
          } otherwise {
            divDividend := opaReg
          }

          when(bSigned(63)) {
            divDivisor := (-bSigned).asUInt
          } otherwise {
            divDivisor := opbReg
          }

          divRemainder := 0
          divQuotient  := 0
          divCount     := 0
          state := State.DIV_EXEC
        }
      }

      is(State.DIV_EXEC) {
        // Shift remainder left by 1, bring in MSB of dividend
        val shiftedRem = ((divRemainder(63 downto 0) @@ divDividend(63).asUInt.resize(1))).resize(65)
        val trialSub = (shiftedRem - divDivisor.resize(65))

        when(!trialSub(64)) {
          // trial >= 0: accept subtraction
          divRemainder := trialSub
          divQuotient := (divQuotient |<< 1) | 1
        } otherwise {
          // trial < 0: restore (keep shifted remainder)
          divRemainder := shiftedRem
          divQuotient := (divQuotient |<< 1)
        }

        // Shift dividend left for next iteration
        divDividend := (divDividend |<< 1).resize(64)

        divCount := divCount + 1
        when(divCount === 63) {
          state := State.DIV_DONE
        }
      }

      is(State.DIV_DONE) {
        // Apply signs
        val signedQuot = UInt(64 bits)
        when(divQuotSign) {
          signedQuot := (-divQuotient.asSInt).asUInt
        } otherwise {
          signedQuot := divQuotient
        }

        val signedRem = UInt(64 bits)
        when(divRemSign) {
          signedRem := (-divRemainder.resize(64).asSInt).asUInt
        } otherwise {
          signedRem := divRemainder.resize(64)
        }

        when(opcodeReg === 3) {
          resultReg := signedQuot
        } otherwise {
          resultReg := signedRem
        }
        state := State.DONE
      }
    }

    // ======================================================================
    // LSHL / LSHR / LUSHR — Single-cycle Combinatorial
    // ======================================================================
    if (config.withShift) {
      is(State.SHIFT_EXEC) {
        val shamt = opbReg(5 downto 0)  // JVM masks shift amount to 6 bits

        when(opcodeReg === 6) {
          // lshl: logical left shift
          resultReg := (opaReg |<< shamt).resize(64)
        } elsewhen (opcodeReg === 7) {
          // lshr: arithmetic right shift (sign-extending)
          resultReg := (opaReg.asSInt >> shamt).asUInt
        } otherwise {
          // lushr: logical right shift
          resultReg := (opaReg |>> shamt).resize(64)
        }
        state := State.DONE
      }
    }

    // ======================================================================
    // DONE
    // ======================================================================
    is(State.DONE) {
      state := State.IDLE
    }
  }
}
