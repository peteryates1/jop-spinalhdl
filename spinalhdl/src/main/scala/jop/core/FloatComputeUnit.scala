/**
  * FloatComputeUnit — Configurable IEEE 754 Single-Precision FPU for JOP
  *
  * Uses ComputeUnitCoreBundle interface with 4-bit operation codes:
  *   op 0: fadd    op 1: fsub    op 2: fmul    op 3: fdiv
  *   op 4: fcmpl   op 5: fcmpg   op 6: i2f     op 7: f2i
  *
  * Operand mapping (from CU operand stack):
  *   operands(0) = value2 (TOS at time of first stop)
  *   operands(1) = value1 (NOS at time of first stop)
  *
  * For non-commutative ops (fsub, fdiv, fcmpl, fcmpg), operands are swapped
  * so opaReg=value1(NOS), opbReg=value2(TOS).
  *
  * Special value handling follows Java semantics:
  *   - NaN propagation, canonical NaN = 0x7FC00000
  *   - Inf arithmetic per JLS
  *   - Subnormals flushed to zero (FTZ)
  *   - F2I: truncate toward zero, clamp on overflow, 0 on NaN
  *   - FCMPL: NaN -> -1;  FCMPG: NaN -> +1
  *
  * Rounding: Round-to-Nearest-Even (RNE)
  *
  * Internal mantissa format (26 bits):
  *   bit 25     = hidden bit (1 for normalized)
  *   bits 24..2 = 23 fraction bits
  *   bit 1      = guard bit
  *   bit 0      = round bit
  */
package jop.core

import spinal.core._

case class FloatComputeUnitConfig(
    withAdd:  Boolean = true,
    withMul:  Boolean = true,
    withDiv:  Boolean = true,
    withI2F:  Boolean = false,
    withF2I:  Boolean = false,
    withFcmp: Boolean = false
)

case class FloatComputeUnit(config: FloatComputeUnitConfig = FloatComputeUnitConfig()) extends Component {

  val io = ComputeUnitCoreBundle()

  // ========================================================================
  // Constants
  // ========================================================================
  val CANONICAL_NAN = B"32'x7FC00000"
  val POS_INF       = B"32'x7F800000"
  val POS_ZERO      = B"32'x00000000"

  // ========================================================================
  // FSM States — all declared unconditionally; unused ones optimized away
  // ========================================================================
  object State extends SpinalEnum {
    val IDLE, UNPACK                    = newElement()
    val ADD_ALIGN, ADD_SHIFT, ADD_EXEC, ADD_NORM = newElement()
    val MUL_STEP1, MUL_STEP2, MUL_NORM           = newElement()
    val DIV_INIT, DIV_ITER                      = newElement()
    val I2F_EXEC, I2F_SHIFT                     = newElement()
    val F2I_EXEC                        = newElement()
    val FCMP_EXEC                       = newElement()
    val ROUND, DONE                     = newElement()
  }

  val state = Reg(State()) init (State.IDLE)

  // ========================================================================
  // Unpacked float registers
  // ========================================================================
  val aSign = Reg(Bool()) init (False)
  val aExp  = Reg(SInt(10 bits)) init (0)
  val aMant = Reg(UInt(26 bits)) init (0)
  val aZero = Reg(Bool()) init (False)
  val aInf  = Reg(Bool()) init (False)
  val aNaN  = Reg(Bool()) init (False)

  val bSign = Reg(Bool()) init (False)
  val bExp  = Reg(SInt(10 bits)) init (0)
  val bMant = Reg(UInt(26 bits)) init (0)
  val bZero = Reg(Bool()) init (False)
  val bInf  = Reg(Bool()) init (False)
  val bNaN  = Reg(Bool()) init (False)

  // Result registers
  val resSign = Reg(Bool()) init (False)
  val resExp  = Reg(SInt(10 bits)) init (0)
  val resMant = Reg(UInt(26 bits)) init (0)
  val sticky  = Reg(Bool()) init (False)

  val resultReg = Reg(UInt(64 bits)) init (0)
  val opcodeReg = Reg(UInt(4 bits)) init (0)
  val opaReg    = Reg(Bits(32 bits)) init (0)
  val opbReg    = Reg(Bits(32 bits)) init (0)

  io.resultLo := resultReg(31 downto 0)
  io.resultHi := resultReg(63 downto 32)
  io.busy   := (state =/= State.IDLE)
  io.resultCount := U(1, 2 bits)  // always 1 result word for float ops

  // ========================================================================
  // Unpack: IEEE 754 -> internal
  //   mant layout: bit25=hidden, bits24..2=frac, bit1=guard, bit0=round
  // ========================================================================
  def unpackFloat(bits: Bits, sign: Bool, exp: SInt, mant: UInt,
                  isZero: Bool, isInf: Bool, isNaN: Bool): Unit = {
    val rawExp  = bits(30 downto 23).asUInt
    val rawFrac = bits(22 downto 0).asUInt
    sign := bits(31)
    when(rawExp === 0) {
      // Zero or subnormal -> flush to zero
      exp := S(0, 10 bits); mant := U(0, 26 bits)
      isZero := True; isInf := False; isNaN := False
    } elsewhen (rawExp === 0xFF) {
      exp := S(255, 10 bits)
      mant := (U"1'b1" @@ rawFrac @@ U"2'b00").resized
      isZero := False; isInf := (rawFrac === 0); isNaN := (rawFrac =/= 0)
    } otherwise {
      exp := (rawExp.resize(10 bits).asSInt - 127)
      mant := (U"1'b1" @@ rawFrac @@ U"2'b00").resized
      isZero := False; isInf := False; isNaN := False
    }
  }

  // ========================================================================
  // Pack: internal -> IEEE 754 (for returning unpacked values directly)
  //   Expects mant with hidden at bit 25, frac at bits 24..2
  // ========================================================================
  def packFloat(sign: Bool, exp: SInt, mant: UInt): Bits = {
    val packed = Bits(32 bits)
    val biasedExp = (exp + 127).resize(10 bits)
    when(biasedExp >= 255) {
      packed := sign ## B"8'xFF" ## B"23'x0"
    } elsewhen (biasedExp <= 0) {
      packed := sign ## B"31'x0"
    } otherwise {
      packed := sign ## biasedExp(7 downto 0).asBits ## mant(24 downto 2).asBits
    }
    packed
  }

  // Helper: convert 32-bit Bits result to 64-bit UInt (zero-extended)
  def toResult(bits: Bits): UInt = bits.asUInt.resize(64)

  // ========================================================================
  // Operation-specific registers (always declared; unused ones optimized away)
  // ========================================================================
  val addMantA      = Reg(UInt(28 bits)) init (0)
  val addMantB      = Reg(UInt(28 bits)) init (0)
  val addIsSubOp    = Reg(Bool()) init (False)
  val addShiftInput = Reg(UInt(28 bits)) init (0)  // extended mantissa to barrel-shift
  val addShAmt      = Reg(UInt(5 bits)) init (0)   // shift amount (registered expDiff)
  val addFlushShift = Reg(Bool()) init (False)      // true when expDiff >= 27
  val addFlushSticky = Reg(Bool()) init (False)     // pre-computed sticky for flush case

  val mulProdHi  = Reg(UInt(48 bits)) init (0)

  val divRemainder = Reg(UInt(28 bits)) init (0)
  val divDivisor   = Reg(UInt(28 bits)) init (0)
  val divQuotient  = Reg(UInt(26 bits)) init (0)
  val divCount     = Reg(UInt(5 bits)) init (0)

  val i2fAbsVal = Reg(UInt(32 bits)) init (0)  // registered absolute value
  val i2fLz     = Reg(UInt(6 bits)) init (0)   // registered leading zero count

  // ========================================================================
  // CLZ helpers
  // ========================================================================
  def clz28(v: UInt): UInt = {
    val count = UInt(5 bits)
    count := 28
    // Ascending order: last-assignment-wins -> highest set bit takes priority
    for (i <- 0 to 27) {
      when(v(i)) { count := U(27 - i, 5 bits) }
    }
    count
  }

  def clz32(v: UInt): UInt = {
    val count = UInt(6 bits)
    count := 32
    for (i <- 0 to 31) {
      when(v(i)) { count := U(31 - i, 6 bits) }
    }
    count
  }

  // ========================================================================
  // FSM
  // ========================================================================
  switch(state) {

    // ----------------------------------------------------------------------
    is(State.IDLE) {
      when(io.start) {
        opaReg := io.operands(0).asBits
        opbReg := io.operands(1).asBits
        sticky := False

        // For non-commutative binary ops: swap so opaReg=value1(NOS), opbReg=value2(TOS)
        // Stack: [value1=NOS, value2=TOS], operands(0)=value2, operands(1)=value1
        // fsub(1), fdiv(3), fcmpl(4), fcmpg(5): need opaReg=value1, opbReg=value2
        when(io.op === 1 || io.op === 3 || io.op === 4 || io.op === 5) {
          opaReg := io.operands(1).asBits  // value1
          opbReg := io.operands(0).asBits  // value2
        }

        // Store internal opcode
        opcodeReg := io.op

        // Route to appropriate state (unrecognized ops stay IDLE)
        if (config.withAdd) {
          when(io.op === 0 || io.op === 1) { state := State.UNPACK }  // fadd/fsub
        }
        if (config.withMul) {
          when(io.op === 2) { state := State.UNPACK }  // fmul
        }
        if (config.withDiv) {
          when(io.op === 3) { state := State.UNPACK }  // fdiv
        }
        if (config.withFcmp) {
          when(io.op === 4 || io.op === 5) { state := State.UNPACK }  // fcmpl/fcmpg
        }
        if (config.withI2F) {
          when(io.op === 6) { state := State.I2F_EXEC }  // i2f
        }
        if (config.withF2I) {
          when(io.op === 7) { state := State.UNPACK }  // f2i
        }
      }
    }

    // ----------------------------------------------------------------------
    is(State.UNPACK) {
      unpackFloat(opaReg, aSign, aExp, aMant, aZero, aInf, aNaN)
      unpackFloat(opbReg, bSign, bExp, bMant, bZero, bInf, bNaN)
      state := State.DONE
      resultReg := U(0, 64 bits)

      if (config.withAdd) {
        when(opcodeReg === 0 || opcodeReg === 1) {
          when(opcodeReg === 1) { bSign := !opbReg(31) }
          state := State.ADD_ALIGN
        }
      }
      if (config.withMul) {
        when(opcodeReg === 2) { state := State.MUL_STEP1 }
      }
      if (config.withDiv) {
        when(opcodeReg === 3) { state := State.DIV_INIT }
      }
      if (config.withF2I) {
        when(opcodeReg === 7) { state := State.F2I_EXEC }
      }
      if (config.withFcmp) {
        when(opcodeReg === 4 || opcodeReg === 5) { state := State.FCMP_EXEC }
      }
    }

    // ======================================================================
    // ADD/SUB
    // ======================================================================
    if (config.withAdd) {
      is(State.ADD_ALIGN) {
        when(aNaN || bNaN) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (aInf && bInf) {
          when(aSign === bSign) {
            resultReg := toResult(aSign ## POS_INF(30 downto 0))
          } otherwise {
            resultReg := toResult(CANONICAL_NAN)
          }
          state := State.DONE
        } elsewhen (aInf) {
          resultReg := toResult(aSign ## POS_INF(30 downto 0)); state := State.DONE
        } elsewhen (bInf) {
          resultReg := toResult(bSign ## POS_INF(30 downto 0)); state := State.DONE
        } elsewhen (aZero && bZero) {
          resultReg := toResult((aSign & bSign) ## B"31'x0"); state := State.DONE
        } elsewhen (aZero) {
          resultReg := toResult(packFloat(bSign, bExp, bMant)); state := State.DONE
        } elsewhen (bZero) {
          resultReg := toResult(packFloat(aSign, aExp, aMant)); state := State.DONE
        } otherwise {
          // Pipeline stage 1: compute expDiff, register shift params → ADD_SHIFT
          val expDiff = (aExp - bExp).resize(10 bits)
          addIsSubOp := (aSign =/= bSign)

          when(expDiff >= 0) {
            // A has larger or equal exponent — will shift B right
            addMantA := (U"2'b00" @@ aMant).resized
            addShiftInput := (U"2'b00" @@ bMant).resize(28 bits)
            addFlushSticky := (bMant =/= 0)
            when(expDiff >= 27) {
              addFlushShift := True
            } otherwise {
              addFlushShift := False
              addShAmt := expDiff.asUInt.resize(5 bits)
            }
            resExp := aExp; resSign := aSign
          } otherwise {
            // B has larger exponent — B goes in addMantA (unshifted), A shifted
            addMantA := (U"2'b00" @@ bMant).resized
            val negDiff = (-expDiff).resize(10 bits)
            addShiftInput := (U"2'b00" @@ aMant).resize(28 bits)
            addFlushSticky := (aMant =/= 0)
            when(negDiff >= 27) {
              addFlushShift := True
            } otherwise {
              addFlushShift := False
              addShAmt := negDiff.asUInt.resize(5 bits)
            }
            resExp := bExp; resSign := bSign
          }
          state := State.ADD_SHIFT
        }
      }

      // Pipeline stage 2: barrel shift + sticky from registered inputs
      is(State.ADD_SHIFT) {
        when(addFlushShift) {
          addMantB := U(0, 28 bits)
          sticky := addFlushSticky
        } otherwise {
          addMantB := (addShiftInput |>> addShAmt).resize(28 bits)
          sticky := ((addShiftInput & ((U(1, 28 bits) |<< addShAmt) - 1)) =/= 0)
        }
        state := State.ADD_EXEC
      }

      is(State.ADD_EXEC) {
        val sumWide = UInt(28 bits)
        when(addIsSubOp) {
          when(addMantA >= addMantB) {
            sumWide := addMantA - addMantB
          } otherwise {
            sumWide := addMantB - addMantA
            resSign := !resSign
          }
        } otherwise {
          sumWide := (addMantA + addMantB).resize(28 bits)
        }

        when(sumWide === 0 && !sticky) {
          resultReg := toResult(POS_ZERO); state := State.DONE
        } otherwise {
          addMantA := sumWide
          state := State.ADD_NORM
        }
      }

      is(State.ADD_NORM) {
        // addMantA (28 bits) has the sum; hidden bit was at bit 25 before add.
        // Overflow can put leading 1 at bit 26 (for addition).
        // For subtraction, leading 1 can be below bit 25.
        val sum = addMantA

        when(sum(27)) {
          // Shift right by 2: leading 1 at bit 27 -> move to bit 25
          resMant(25 downto 2) := sum(27 downto 4)
          resMant(1) := sum(3)   // guard
          resMant(0) := sum(2)   // round
          sticky := sticky | sum(1) | sum(0)
          resExp := resExp + 2; state := State.ROUND
        } elsewhen (sum(26)) {
          // Shift right by 1: leading 1 at bit 26 -> move to bit 25
          resMant(25 downto 2) := sum(26 downto 3)
          resMant(1) := sum(2)   // guard
          resMant(0) := sum(1)   // round
          sticky := sticky | sum(0)
          resExp := resExp + 1; state := State.ROUND
        } elsewhen (sum(25)) {
          // Already normalized: leading 1 at bit 25
          resMant := sum(25 downto 0)
          state := State.ROUND
        } otherwise {
          // Leading 1 is below bit 25: shift left to normalize
          val lz = clz28(sum)
          when(sum === 0) {
            resultReg := toResult(resSign ## B"31'x0"); state := State.DONE
          } otherwise {
            // Leading 1 at bit (27 - lz); we want it at bit 25
            // shAmt = (27 - lz) needs to go to 25, so shift left by (lz - 2)
            val shAmt = UInt(5 bits)
            when(lz <= 2) { shAmt := 0 }
              .otherwise  { shAmt := lz - 2 }
            val shifted = (sum |<< shAmt).resize(28 bits)
            resMant := shifted(25 downto 0)
            resExp := resExp - shAmt.asSInt.resize(10 bits)
            state := State.ROUND
          }
        }
      }
    }

    // ======================================================================
    // MUL
    // ======================================================================
    if (config.withMul) {
      is(State.MUL_STEP1) {
        when(aNaN || bNaN) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen ((aInf && bZero) || (aZero && bInf)) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (aInf || bInf) {
          resultReg := toResult((aSign ^ bSign) ## POS_INF(30 downto 0)); state := State.DONE
        } elsewhen (aZero || bZero) {
          resultReg := toResult((aSign ^ bSign) ## B"31'x0"); state := State.DONE
        } otherwise {
          resSign := aSign ^ bSign
          resExp := aExp + bExp
          state := State.MUL_STEP2
        }
      }

      is(State.MUL_STEP2) {
        // 24x24 -> 48-bit multiply; aMant(25:2) = {hidden, 23 frac} = 24 bits
        // Register the product — normalize in MUL_NORM to break the critical path
        val mantA24 = aMant(25 downto 2)
        val mantB24 = bMant(25 downto 2)
        mulProdHi := mantA24 * mantB24  // 48-bit result -> register
        state := State.MUL_NORM
      }

      is(State.MUL_NORM) {
        // Product of two 1.xxx values: result in [1.0, 4.0)
        // Product bit 47 set -> result >= 2.0, shift right 1
        // Product bit 46 set -> result in [1.0, 2.0), already positioned
        when(mulProdHi(47)) {
          // Map: product(47)->resMant(25)=hidden, product(46:24)->frac, product(23)->guard, product(22)->round
          resMant := mulProdHi(47 downto 22).resized
          sticky := mulProdHi(21 downto 0) =/= 0
          resExp := resExp + 1
        } otherwise {
          // Map: product(46)->resMant(25)=hidden, product(45:23)->frac, product(22)->guard, product(21)->round
          resMant := mulProdHi(46 downto 21).resized
          sticky := mulProdHi(20 downto 0) =/= 0
        }
        state := State.ROUND
      }
    }

    // ======================================================================
    // DIV — restoring radix-2 with pre-comparison
    // ======================================================================
    if (config.withDiv) {
      is(State.DIV_INIT) {
        when(aNaN || bNaN) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (aInf && bInf) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (aInf) {
          resultReg := toResult((aSign ^ bSign) ## POS_INF(30 downto 0)); state := State.DONE
        } elsewhen (bZero && aZero) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (bZero) {
          resultReg := toResult((aSign ^ bSign) ## POS_INF(30 downto 0)); state := State.DONE
        } elsewhen (aZero) {
          resultReg := toResult((aSign ^ bSign) ## B"31'x0"); state := State.DONE
        } elsewhen (bInf) {
          resultReg := toResult((aSign ^ bSign) ## B"31'x0"); state := State.DONE
        } otherwise {
          resSign := aSign ^ bSign

          // Pre-comparison to ensure remainder < divisor at all times
          val mantA24 = aMant(25 downto 2)  // 24 bits: {hidden, 23 frac}
          val mantB24 = bMant(25 downto 2)
          when(mantA24 >= mantB24) {
            // Quotient >= 1.0: first bit = 1, remainder = mantA - mantB
            divRemainder := (U"4'b0000" @@ (mantA24 - mantB24)).resized
            resExp := aExp - bExp
            divQuotient := U(1, 26 bits)
          } otherwise {
            // Quotient < 1.0: first bit = 0, remainder = mantA
            divRemainder := (U"4'b0000" @@ mantA24).resized
            resExp := aExp - bExp - 1
            divQuotient := U(0, 26 bits)
          }
          divDivisor := (U"4'b0000" @@ mantB24).resized
          divCount := U(0, 5 bits)
          state := State.DIV_ITER
        }
      }

      is(State.DIV_ITER) {
        // Restoring radix-2: remainder is always < divisor at start of iteration
        val trial = (divRemainder |<< 1).resize(28 bits) - divDivisor
        when(!trial(27)) {
          // trial >= 0: accept
          divRemainder := trial
          divQuotient := (divQuotient |<< 1) | 1
        } otherwise {
          // trial < 0: restore
          divRemainder := (divRemainder |<< 1).resize(28 bits)
          divQuotient := (divQuotient |<< 1)
        }
        divCount := divCount + 1

        // After 25 iterations (+ 1 pre-comparison bit = 26 quotient bits)
        when(divCount === 25) {
          sticky := (divRemainder =/= 0)
          val q = divQuotient  // 26 bits
          when(q(25)) {
            // Quotient >= 1.0: hidden bit already at bit 25
            resMant := q
          } otherwise {
            // Quotient < 1.0: leading 1 at bit 24, shift left
            resMant := (q |<< 1).resized
          }
          state := State.ROUND
        }
      }
    }

    // ======================================================================
    // I2F
    // ======================================================================
    if (config.withI2F) {
      // Pipeline stage 1: negate + CLZ, register absVal and lz
      is(State.I2F_EXEC) {
        val intVal = opaReg.asSInt
        when(intVal === 0) {
          resultReg := toResult(POS_ZERO); state := State.DONE
        } otherwise {
          val negative = intVal(31)
          val absVal = UInt(32 bits)
          when(negative) { absVal := (-intVal).asUInt }
            .otherwise   { absVal := intVal.asUInt }

          i2fAbsVal := absVal
          i2fLz := clz32(absVal)
          resSign := negative
          state := State.I2F_SHIFT
        }
      }

      // Pipeline stage 2: barrel shift + sticky from registered absVal/lz
      is(State.I2F_SHIFT) {
        resExp := (S(31, 10 bits) - i2fLz.resize(10 bits).asSInt)

        // Place into resMant: hidden at bit 25, frac at 24:2, guard at 1, round at 0
        // absVal leading 1 at bit (31-lz); we want it at bit 25 of 26-bit resMant
        // If (31-lz) > 25: shift right by (6-lz)
        // If (31-lz) <= 25: shift left by (lz-6)
        when(i2fLz < 6) {
          val shR = (U(6, 6 bits) - i2fLz).resize(5 bits)
          val mask = (U(1, 32 bits) |<< shR) - 1
          sticky := (i2fAbsVal & mask) =/= 0
          resMant := (i2fAbsVal |>> shR).resize(26 bits)
        } otherwise {
          val shL = (i2fLz - 6).resize(5 bits)
          sticky := False
          resMant := (i2fAbsVal |<< shL).resize(26 bits)
        }
        state := State.ROUND
      }
    }

    // ======================================================================
    // F2I
    // ======================================================================
    if (config.withF2I) {
      is(State.F2I_EXEC) {
        when(aNaN) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aZero) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aInf) {
          resultReg := Mux(aSign, B"32'x80000000", B"32'x7FFFFFFF").asUInt.resize(64)
          state := State.DONE
        } otherwise {
          // aMant(25:2) = {hidden, 23 frac} = 24-bit significand
          // Value = significand * 2^(exp - 23)
          val mant24 = aMant(25 downto 2)
          when(aExp >= 31) {
            when(aSign) {
              resultReg := B"32'x80000000".asUInt.resize(64)
            } otherwise {
              resultReg := B"32'x7FFFFFFF".asUInt.resize(64)
            }
            state := State.DONE
          } elsewhen (aExp < 0) {
            // |value| < 1 -> truncate to 0
            resultReg := U(0, 64 bits); state := State.DONE
          } otherwise {
            // 0 <= exp <= 30: integer = mant24 >> (23 - exp)
            val shR = (S(23, 10 bits) - aExp).asUInt.resize(5 bits)
            val intVal = (mant24 |>> shR).resize(32 bits)
            when(aSign) {
              resultReg := (-intVal.asSInt).asBits.asUInt.resize(64)
            } otherwise {
              resultReg := intVal.resize(64)
            }
            state := State.DONE
          }
        }
      }
    }

    // ======================================================================
    // FCMPL / FCMPG
    // ======================================================================
    if (config.withFcmp) {
      is(State.FCMP_EXEC) {
        when(aNaN || bNaN) {
          // opcodeReg 4=fcmpl (NaN->-1), 5=fcmpg (NaN->+1)
          resultReg := Mux(opcodeReg === 4, B"32'xFFFFFFFF", B"32'x00000001").asUInt.resize(64)
          state := State.DONE
        } otherwise {
          when(aZero && bZero) {
            resultReg := U(0, 64 bits)
          } otherwise {
            val aLess = Bool()
            val bLess = Bool()
            when(aSign && !bSign) {
              aLess := True; bLess := False
            } elsewhen (!aSign && bSign) {
              aLess := False; bLess := True
            } elsewhen (!aSign && !bSign) {
              // Both positive: larger exp/mant = larger value
              when(aExp < bExp)      { aLess := True;  bLess := False }
              .elsewhen(aExp > bExp) { aLess := False; bLess := True }
              .elsewhen(aMant < bMant) { aLess := True;  bLess := False }
              .elsewhen(aMant > bMant) { aLess := False; bLess := True }
              .otherwise               { aLess := False; bLess := False }
            } otherwise {
              // Both negative: larger magnitude = smaller value
              when(aExp > bExp)      { aLess := True;  bLess := False }
              .elsewhen(aExp < bExp) { aLess := False; bLess := True }
              .elsewhen(aMant > bMant) { aLess := True;  bLess := False }
              .elsewhen(aMant < bMant) { aLess := False; bLess := True }
              .otherwise               { aLess := False; bLess := False }
            }
            when(aLess)        { resultReg := B"32'xFFFFFFFF".asUInt.resize(64) }
            .elsewhen(bLess)   { resultReg := B"32'x00000001".asUInt.resize(64) }
            .otherwise         { resultReg := U(0, 64 bits) }
          }
          state := State.DONE
        }
      }
    }

    // ======================================================================
    // ROUND (RNE) — inline packing to correctly extract fraction
    // ======================================================================
    is(State.ROUND) {
      // resMant: bit25=hidden, bits24..2=frac, bit1=guard, bit0=round
      val guard    = resMant(1)
      val roundBit = resMant(0)
      val mantLsb  = resMant(2)
      // RNE: round up when guard && (round || sticky || lsb)
      val roundUp  = guard & (roundBit | sticky | mantLsb)

      // Extract {hidden, 23 frac} = 24 bits, with extra bit for carry
      val mantToRound = resMant(25 downto 2)  // 24 bits
      val rounded = UInt(25 bits)
      when(roundUp) {
        rounded := (U"1'b0" @@ mantToRound) + 1
      } otherwise {
        rounded := (U"1'b0" @@ mantToRound)
      }

      // Extract fraction and compute final exponent
      val finalExp = SInt(10 bits)
      val frac = Bits(23 bits)
      when(rounded(24)) {
        // Rounding caused mantissa overflow (carry out of hidden bit)
        finalExp := resExp + 1
        frac := rounded(23 downto 1).asBits
      } otherwise {
        finalExp := resExp
        frac := rounded(22 downto 0).asBits
      }

      // Pack IEEE 754 result
      val biasedExp = (finalExp + 127).resize(10 bits)
      when(biasedExp >= 255) {
        resultReg := toResult(resSign ## B"8'xFF" ## B"23'x0")  // overflow -> Inf
      } elsewhen (biasedExp <= 0) {
        resultReg := toResult(resSign ## B"31'x0")  // underflow -> zero (FTZ)
      } otherwise {
        resultReg := toResult(resSign ## biasedExp(7 downto 0).asBits ## frac)
      }
      state := State.DONE
    }

    // ======================================================================
    // DONE
    // ======================================================================
    is(State.DONE) {
      state := State.IDLE
    }
  }
}
