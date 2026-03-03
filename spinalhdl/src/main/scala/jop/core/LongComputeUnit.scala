/**
  * LongComputeUnit — Multi-cycle 64-bit Integer Operations for JOP
  *
  * Extends ComputeUnit with long integer operations.
  * Each operation can be included/excluded via LongComputeUnitConfig flags.
  *
  * Operations (selected by JVM bytecode):
  *   0x69: lmul    0x6D: ldiv    0x71: lrem
  *   0x79: lshl    0x7B: lshr    0x7D: lushr
  *
  * Interface: load operand0/operand1/opcode, pulse wr, wait for busy to deassert.
  * Result appears on io.result (64 bits). io.is64 is always true.
  *
  * Algorithms:
  *   lmul  — Radix-4 sequential multiply (32 iterations, ~34 cycles)
  *   ldiv  — Binary restoring division (64 iterations, ~68 cycles)
  *   lrem  — Same divider as ldiv, returns remainder
  *   lshl  — Combinatorial left shift (3 cycles)
  *   lshr  — Combinatorial arithmetic right shift (3 cycles)
  *   lushr — Combinatorial logical right shift (3 cycles)
  *
  * Special cases:
  *   ldiv/lrem by zero: result = 0
  *   MIN_VALUE / -1:    quotient = MIN_VALUE, remainder = 0
  */
package jop.core

import spinal.core._

case class LongComputeUnitConfig(
    withMul:   Boolean = true,   // lmul  (0x69)
    withDiv:   Boolean = true,   // ldiv  (0x6D)
    withRem:   Boolean = true,   // lrem  (0x71)
    withShift: Boolean = true    // lshl (0x79), lshr (0x7B), lushr (0x7D)
)

case class LongComputeUnit(config: LongComputeUnitConfig = LongComputeUnitConfig()) extends ComputeUnit {

  // ========================================================================
  // FSM States — all declared unconditionally; unused ones optimized away
  // ========================================================================
  object State extends SpinalEnum {
    val IDLE                              = newElement()
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
  val opcodeReg = Reg(UInt(3 bits)) init (0)
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
  io.result := resultReg
  io.busy   := (state =/= State.IDLE)
  io.is64   := True

  // ========================================================================
  // FSM
  // ========================================================================
  switch(state) {

    // ----------------------------------------------------------------------
    is(State.IDLE) {
      when(io.wr) {
        opaReg := io.operand0
        opbReg := io.operand1

        // Decode JVM bytecode to internal opcode
        switch(io.opcode) {
          is(B"8'x69") { opcodeReg := 0 }  // lmul
          is(B"8'x6D") { opcodeReg := 1 }  // ldiv
          is(B"8'x71") { opcodeReg := 2 }  // lrem
          is(B"8'x79") { opcodeReg := 3 }  // lshl
          is(B"8'x7B") { opcodeReg := 4 }  // lshr
          is(B"8'x7D") { opcodeReg := 5 }  // lushr
        }

        // Route to appropriate state
        state := State.DONE  // default fallback
        if (config.withMul) {
          when(io.opcode === B"8'x69") {
            mulA := io.operand0
            mulB := io.operand1
            mulP := 0
            mulCount := 0
            state := State.MUL_EXEC
          }
        }
        if (config.withDiv || config.withRem) {
          when(io.opcode === B"8'x6D" || io.opcode === B"8'x71") {
            state := State.DIV_SETUP
          }
        }
        if (config.withShift) {
          when(io.opcode === B"8'x79" || io.opcode === B"8'x7B" || io.opcode === B"8'x7D") {
            state := State.SHIFT_EXEC
          }
        }
      }
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
          when(opcodeReg === 1) {
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

        when(opcodeReg === 1) {
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

        when(opcodeReg === 3) {
          // lshl: logical left shift
          resultReg := (opaReg |<< shamt).resize(64)
        } elsewhen (opcodeReg === 4) {
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
