/**
  * IntegerComputeUnit — Multi-cycle 32-bit Integer Operations for JOP
  *
  * Extends ComputeUnit with integer multiply/divide/remainder operations.
  * Each operation can be included/excluded via IntegerComputeUnitConfig flags.
  *
  * Operations (selected by JVM bytecode):
  *   0x68: imul    0x6C: idiv    0x70: irem
  *
  * Interface: load a/b/opcode, pulse wr, wait for busy to deassert.
  * Result appears on io.resultLo (32 bits). io.is64 is always false.
  *
  * Algorithms:
  *   imul — Radix-4 sequential multiply (16 iterations, ~18 cycles)
  *   idiv — Binary restoring division (32 iterations, ~36 cycles)
  *   irem — Same divider as idiv, returns remainder
  *
  * Special cases:
  *   idiv/irem by zero: result = 0
  *   MIN_VALUE / -1:    quotient = MIN_VALUE, remainder = 0
  */
package jop.core

import spinal.core._

case class IntegerComputeUnitConfig(
    withMul: Boolean = true,    // imul (0x68)
    withDiv: Boolean = false,   // idiv (0x6C)
    withRem: Boolean = false    // irem (0x70)
)

case class IntegerComputeUnit(config: IntegerComputeUnitConfig = IntegerComputeUnitConfig()) extends ComputeUnit {

  // ========================================================================
  // FSM States — all declared unconditionally; unused ones optimized away
  // ========================================================================
  object State extends SpinalEnum {
    val IDLE                              = newElement()
    val MUL_EXEC                          = newElement()
    val DIV_SETUP, DIV_EXEC, DIV_DONE     = newElement()
    val DONE                              = newElement()
  }

  val state = Reg(State()) init (State.IDLE)

  // ========================================================================
  // Registers — all declared unconditionally; unused ones optimized away
  // ========================================================================
  val resultReg = Reg(UInt(64 bits)) init (0)
  val opcodeReg = Reg(UInt(2 bits)) init (0)
  val opaReg    = Reg(UInt(32 bits)) init (0)
  val opbReg    = Reg(UInt(32 bits)) init (0)

  // Multiply registers (radix-4)
  val mulA     = Reg(UInt(32 bits)) init (0)
  val mulB     = Reg(UInt(32 bits)) init (0)
  val mulP     = Reg(UInt(32 bits)) init (0)
  val mulCount = Reg(UInt(5 bits)) init (0)

  // Divide registers (binary restoring)
  val divDividend  = Reg(UInt(32 bits)) init (0)
  val divDivisor   = Reg(UInt(32 bits)) init (0)
  val divRemainder = Reg(UInt(33 bits)) init (0)
  val divQuotient  = Reg(UInt(32 bits)) init (0)
  val divQuotSign  = Reg(Bool()) init (False)
  val divRemSign   = Reg(Bool()) init (False)
  val divCount     = Reg(UInt(6 bits)) init (0)

  // ========================================================================
  // IO wiring
  // ========================================================================
  io.resultLo := resultReg(31 downto 0)
  io.resultHi := resultReg(63 downto 32)
  io.busy   := (state =/= State.IDLE)
  io.is64   := False

  // ========================================================================
  // FSM
  // ========================================================================
  switch(state) {

    // ----------------------------------------------------------------------
    is(State.IDLE) {
      when(io.wr) {
        opaReg := io.a
        opbReg := io.b

        // Decode JVM bytecode to internal opcode
        switch(io.opcode) {
          is(B"8'x68") { opcodeReg := 0 }  // imul
          is(B"8'x6C") { opcodeReg := 1 }  // idiv
          is(B"8'x70") { opcodeReg := 2 }  // irem
        }

        // Route to appropriate state (unrecognized opcodes stay IDLE)
        if (config.withMul) {
          when(io.opcode === B"8'x68") {
            mulA := io.a
            mulB := io.b
            mulP := 0
            mulCount := 0
            state := State.MUL_EXEC
          }
        }
        if (config.withDiv || config.withRem) {
          when(io.opcode === B"8'x6C" || io.opcode === B"8'x70") {
            state := State.DIV_SETUP
          }
        }
      }
    }

    // ======================================================================
    // IMUL — Radix-4 Sequential (16 iterations)
    // ======================================================================
    if (config.withMul) {
      is(State.MUL_EXEC) {
        // Two's complement wrapping: lower 32 bits are the same for
        // signed and unsigned multiply, so no magnitude conversion needed.
        val prod = UInt(32 bits)
        prod := mulP

        // Radix-4: process 2 bits of multiplier per cycle
        val prodAfterB0 = UInt(32 bits)
        when(mulB(0)) {
          prodAfterB0 := prod + mulA
        } otherwise {
          prodAfterB0 := prod
        }

        val prodFinal = UInt(32 bits)
        when(mulB(1)) {
          prodFinal := (prodAfterB0(31 downto 1) +^ mulA(30 downto 0)).resize(31) @@ prodAfterB0(0)
        } otherwise {
          prodFinal := prodAfterB0
        }

        mulP := prodFinal
        mulA := mulA(29 downto 0) @@ U"2'b00"
        mulB := U"2'b00" @@ mulB(31 downto 2)
        mulCount := mulCount + 1

        when(mulCount === 15) {
          resultReg := prodFinal.resize(64)
          state := State.DONE
        }
      }
    }

    // ======================================================================
    // IDIV/IREM — Binary Restoring Division (32 iterations)
    // ======================================================================
    if (config.withDiv || config.withRem) {
      is(State.DIV_SETUP) {
        // JVM idiv/irem: value1 / value2 where value2=TOS=io.a, value1=NOS=io.b
        val dividend = opbReg.asSInt   // NOS = value1 (dividend)
        val divisor  = opaReg.asSInt   // TOS = value2 (divisor)

        // Special case: divide by zero -> result = 0
        when(opaReg === 0) {
          resultReg := 0
          state := State.DONE
        }
        // Special case: MIN_VALUE / -1 -> quotient = MIN_VALUE, remainder = 0
        .elsewhen(dividend === S(32 bits, (31) -> true, default -> false) &&
                  divisor === S(-1, 32 bits)) {
          when(opcodeReg === 1) {
            // idiv: MIN_VALUE
            resultReg := opbReg.resize(64)
          } otherwise {
            // irem: 0
            resultReg := 0
          }
          state := State.DONE
        } otherwise {
          // Convert to magnitudes, record signs
          divQuotSign := dividend(31) ^ divisor(31)
          divRemSign  := dividend(31)

          when(dividend(31)) {
            divDividend := (-dividend).asUInt
          } otherwise {
            divDividend := opbReg
          }

          when(divisor(31)) {
            divDivisor := (-divisor).asUInt
          } otherwise {
            divDivisor := opaReg
          }

          divRemainder := 0
          divQuotient  := 0
          divCount     := 0
          state := State.DIV_EXEC
        }
      }

      is(State.DIV_EXEC) {
        // Shift remainder left by 1, bring in MSB of dividend
        val shiftedRem = ((divRemainder(31 downto 0) @@ divDividend(31).asUInt.resize(1))).resize(33)
        val trialSub = (shiftedRem - divDivisor.resize(33))

        when(!trialSub(32)) {
          // trial >= 0: accept subtraction
          divRemainder := trialSub
          divQuotient := (divQuotient |<< 1) | 1
        } otherwise {
          // trial < 0: restore (keep shifted remainder)
          divRemainder := shiftedRem
          divQuotient := (divQuotient |<< 1)
        }

        // Shift dividend left for next iteration
        divDividend := (divDividend |<< 1).resize(32)

        divCount := divCount + 1
        when(divCount === 31) {
          state := State.DIV_DONE
        }
      }

      is(State.DIV_DONE) {
        // Apply signs
        val signedQuot = UInt(32 bits)
        when(divQuotSign) {
          signedQuot := (-divQuotient.asSInt).asUInt
        } otherwise {
          signedQuot := divQuotient
        }

        val signedRem = UInt(32 bits)
        when(divRemSign) {
          signedRem := (-divRemainder.resize(32).asSInt).asUInt
        } otherwise {
          signedRem := divRemainder.resize(32)
        }

        when(opcodeReg === 1) {
          resultReg := signedQuot.resize(64)
        } otherwise {
          resultReg := signedRem.resize(64)
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
