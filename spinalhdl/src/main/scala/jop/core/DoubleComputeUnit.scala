/**
  * DoubleComputeUnit — Configurable IEEE 754 Double-Precision FPU for JOP
  *
  * Uses ComputeUnitCoreBundle interface with 4-bit operation codes:
  *   op 0: dadd    op 1: dsub    op 2: dmul    op 3: ddiv
  *   op 4: dcmpl   op 5: dcmpg   op 6: f2d     op 7: d2f
  *   op 8: i2d     op 9: d2i     op 10: l2d    op 11: d2l
  *
  * Operand mapping (from CU operand stack):
  *   4-operand binary ops (dadd/dsub/dmul/ddiv):
  *     value1 = operands(3):operands(2) (hi:lo)
  *     value2 = operands(1):operands(0) (hi:lo)
  *   2-operand double-input ops (d2i, d2f, d2l, dcmpl, dcmpg):
  *     double = operands(1):operands(0) (hi:lo)  [for d2i/d2f/d2l]
  *     For dcmpl/dcmpg: same as binary ops (4 operands)
  *   1-operand conversion ops (i2d, f2d):
  *     operands(0) = input value
  *   2-operand long-input ops (l2d):
  *     long = operands(1):operands(0) (hi:lo)
  *
  * Special value handling follows Java semantics.
  * Rounding: Round-to-Nearest-Even (RNE)
  */
package jop.core

import spinal.core._

case class DoubleComputeUnitConfig(
    withAdd:  Boolean = true,   // dadd/dsub
    withMul:  Boolean = true,   // dmul
    withDiv:  Boolean = true,   // ddiv
    withI2D:  Boolean = false,  // i2d (int32 -> double)
    withD2I:  Boolean = false,  // d2i (double -> int32)
    withL2D:  Boolean = false,  // l2d (long -> double)
    withD2L:  Boolean = false,  // d2l (double -> long)
    withF2D:  Boolean = false,  // f2d (float -> double)
    withD2F:  Boolean = false,  // d2f (double -> float)
    withDcmp: Boolean = false   // dcmpl/dcmpg
)

case class DoubleComputeUnit(config: DoubleComputeUnitConfig = DoubleComputeUnitConfig()) extends Component {

  val io = ComputeUnitCoreBundle()

  // ========================================================================
  // Constants
  // ========================================================================
  val CANONICAL_NAN = B"64'x7FF8000000000000"
  val POS_INF       = B"64'x7FF0000000000000"
  val POS_ZERO      = B"64'x0000000000000000"

  // Single-precision constants for D2F
  val SP_CANONICAL_NAN = B"32'x7FC00000"
  val SP_POS_INF       = B"32'x7F800000"

  // ========================================================================
  // FSM States
  // ========================================================================
  object State extends SpinalEnum {
    val IDLE, UNPACK                    = newElement()
    val ADD_ALIGN, ADD_EXEC, ADD_SELECT, ADD_NORM = newElement()
    val MUL_STEP1, MUL_STEP2, MUL_NORM = newElement()
    val DIV_INIT, DIV_ITER              = newElement()
    val I2D_EXEC                        = newElement()
    val D2I_EXEC                        = newElement()
    val L2D_EXEC, L2D_SHIFT             = newElement()
    val D2L_EXEC                        = newElement()
    val F2D_EXEC                        = newElement()
    val D2F_EXEC                        = newElement()
    val DCMP_EXEC                       = newElement()
    val ROUND, DONE                     = newElement()
  }

  val state = Reg(State()) init (State.IDLE)

  // ========================================================================
  // Unpacked double registers
  // ========================================================================
  val aSign = Reg(Bool()) init (False)
  val aExp  = Reg(SInt(13 bits)) init (0)
  val aMant = Reg(UInt(55 bits)) init (0)
  val aZero = Reg(Bool()) init (False)
  val aInf  = Reg(Bool()) init (False)
  val aNaN  = Reg(Bool()) init (False)

  val bSign = Reg(Bool()) init (False)
  val bExp  = Reg(SInt(13 bits)) init (0)
  val bMant = Reg(UInt(55 bits)) init (0)
  val bZero = Reg(Bool()) init (False)
  val bInf  = Reg(Bool()) init (False)
  val bNaN  = Reg(Bool()) init (False)

  // Result registers
  val resSign = Reg(Bool()) init (False)
  val resExp  = Reg(SInt(13 bits)) init (0)
  val resMant = Reg(UInt(55 bits)) init (0)
  val sticky  = Reg(Bool()) init (False)

  val resultReg = Reg(UInt(64 bits)) init (0)
  val opcodeReg = Reg(UInt(4 bits)) init (0)
  val opaReg    = Reg(Bits(64 bits)) init (0)
  val opbReg    = Reg(Bits(64 bits)) init (0)

  // D2F mode flag — when true, ROUND packs as single-precision
  val d2fMode = Reg(Bool()) init (False)

  io.resultLo := resultReg(31 downto 0)
  io.resultHi := resultReg(63 downto 32)
  io.busy   := (state =/= State.IDLE)
  // resultCount: 1 for d2i(9), d2f(7), dcmpl(4), dcmpg(5); 2 for everything else
  io.resultCount := Mux(opcodeReg === 9 || opcodeReg === 7 ||
                        opcodeReg === 4 || opcodeReg === 5,
                        U(1, 2 bits), U(2, 2 bits))

  // ========================================================================
  // Unpack: IEEE 754 binary64 -> internal
  // ========================================================================
  def unpackDouble(bits: Bits, sign: Bool, exp: SInt, mant: UInt,
                   isZero: Bool, isInf: Bool, isNaN: Bool): Unit = {
    val rawExp  = bits(62 downto 52).asUInt   // 11 bits
    val rawFrac = bits(51 downto 0).asUInt    // 52 bits
    sign := bits(63)
    when(rawExp === 0) {
      exp := S(0, 13 bits); mant := U(0, 55 bits)
      isZero := True; isInf := False; isNaN := False
    } elsewhen (rawExp === 0x7FF) {
      exp := S(2047, 13 bits)
      mant := (U"1'b1" @@ rawFrac @@ U"2'b00").resized
      isZero := False; isInf := (rawFrac === 0); isNaN := (rawFrac =/= 0)
    } otherwise {
      exp := (rawExp.resize(13 bits).asSInt - 1023)
      mant := (U"1'b1" @@ rawFrac @@ U"2'b00").resized
      isZero := False; isInf := False; isNaN := False
    }
  }

  // Helper: convert 64-bit Bits result to UInt
  def toResult(bits: Bits): UInt = bits.asUInt.resize(64)

  // Helper: convert 32-bit Bits result to 64-bit UInt (zero-extended)
  def toResult32(bits: Bits): UInt = bits.asUInt.resize(64)

  // ========================================================================
  // Operation-specific registers
  // ========================================================================
  val addMantA   = Reg(UInt(57 bits)) init (0)
  val addMantB   = Reg(UInt(57 bits)) init (0)
  val addIsSubOp = Reg(Bool()) init (False)
  val addDiffAB  = Reg(UInt(57 bits)) init (0)   // speculative A - B
  val addDiffBA  = Reg(UInt(57 bits)) init (0)   // speculative B - A
  val addSumAB   = Reg(UInt(57 bits)) init (0)   // A + B (for addition)
  val addAgeB    = Reg(Bool()) init (False)       // A >= B

  val mulProdHi  = Reg(UInt(106 bits)) init (0)

  val divRemainder = Reg(UInt(57 bits)) init (0)
  val divDivisor   = Reg(UInt(57 bits)) init (0)
  val divQuotient  = Reg(UInt(55 bits)) init (0)
  val divCount     = Reg(UInt(6 bits)) init (0)

  val l2dAbsVal    = Reg(UInt(64 bits)) init (0)
  val l2dLz        = Reg(UInt(7 bits)) init (0)

  // ========================================================================
  // CLZ helpers
  // ========================================================================
  def clz57(v: UInt): UInt = {
    val count = UInt(6 bits)
    count := 57
    for (i <- 0 to 56) {
      when(v(i)) { count := U(56 - i, 6 bits) }
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

  def clz64(v: UInt): UInt = {
    val count = UInt(7 bits)
    count := 64
    for (i <- 0 to 63) {
      when(v(i)) { count := U(63 - i, 7 bits) }
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
        sticky := False
        opcodeReg := io.op

        // Default: 4-operand binary ops
        // value1 = operands(3):operands(2), value2 = operands(1):operands(0)
        opaReg := (io.operands(3) ## io.operands(2)).asBits
        opbReg := (io.operands(1) ## io.operands(0)).asBits

        // Default route: UNPACK (for binary double ops)
        state := State.UNPACK

        // Single-operand conversions: i2d, f2d
        if (config.withI2D) {
          when(io.op === 8) {  // i2d
            opaReg := io.operands(0).asBits.resize(64)
            state := State.I2D_EXEC
          }
        }
        if (config.withF2D) {
          when(io.op === 6) {  // f2d
            opaReg := io.operands(0).asBits.resize(64)
            state := State.F2D_EXEC
          }
        }

        // 2-operand double/long-input conversions: d2i, d2f, d2l, l2d
        if (config.withD2I) {
          when(io.op === 9) {  // d2i
            opaReg := (io.operands(1) ## io.operands(0)).asBits
            state := State.UNPACK
          }
        }
        if (config.withD2F) {
          when(io.op === 7) {  // d2f
            opaReg := (io.operands(1) ## io.operands(0)).asBits
            state := State.UNPACK
          }
        }
        if (config.withD2L) {
          when(io.op === 11) {  // d2l
            opaReg := (io.operands(1) ## io.operands(0)).asBits
            state := State.UNPACK
          }
        }
        if (config.withL2D) {
          when(io.op === 10) {  // l2d
            opaReg := (io.operands(1) ## io.operands(0)).asBits
            state := State.L2D_EXEC
          }
        }
      }
    }

    // ----------------------------------------------------------------------
    is(State.UNPACK) {
      unpackDouble(opaReg, aSign, aExp, aMant, aZero, aInf, aNaN)
      unpackDouble(opbReg, bSign, bExp, bMant, bZero, bInf, bNaN)
      state := State.DONE
      resultReg := U(0, 64 bits)

      if (config.withAdd) {
        when(opcodeReg === 0 || opcodeReg === 1) {
          when(opcodeReg === 1) { bSign := !opbReg(63) }
          state := State.ADD_ALIGN
        }
      }
      if (config.withMul) {
        when(opcodeReg === 2) { state := State.MUL_STEP1 }
      }
      if (config.withDiv) {
        when(opcodeReg === 3) { state := State.DIV_INIT }
      }
      if (config.withD2I) {
        when(opcodeReg === 9) { state := State.D2I_EXEC }
      }
      if (config.withD2F) {
        when(opcodeReg === 7) { state := State.D2F_EXEC }
      }
      if (config.withD2L) {
        when(opcodeReg === 11) { state := State.D2L_EXEC }
      }
      if (config.withDcmp) {
        when(opcodeReg === 4 || opcodeReg === 5) { state := State.DCMP_EXEC }
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
            resultReg := toResult(aSign ## POS_INF(62 downto 0))
          } otherwise {
            resultReg := toResult(CANONICAL_NAN)
          }
          state := State.DONE
        } elsewhen (aInf) {
          resultReg := toResult(aSign ## POS_INF(62 downto 0)); state := State.DONE
        } elsewhen (bInf) {
          resultReg := toResult(bSign ## POS_INF(62 downto 0)); state := State.DONE
        } elsewhen (aZero && bZero) {
          resultReg := toResult((aSign & bSign) ## B"63'x0"); state := State.DONE
        } elsewhen (aZero) {
          // Return B repacked
          val bBiased = (bExp + 1023).resize(13 bits)
          val bPacked = bSign ## bBiased(10 downto 0).asBits ## bMant(53 downto 2).asBits
          resultReg := toResult(bPacked); state := State.DONE
        } elsewhen (bZero) {
          val aBiased = (aExp + 1023).resize(13 bits)
          val aPacked = aSign ## aBiased(10 downto 0).asBits ## aMant(53 downto 2).asBits
          resultReg := toResult(aPacked); state := State.DONE
        } otherwise {
          val expDiff = (aExp - bExp).resize(13 bits)
          addIsSubOp := (aSign =/= bSign)

          when(expDiff >= 0) {
            addMantA := (U"2'b00" @@ aMant).resized
            val bExt = (U"2'b00" @@ bMant).resize(57 bits)
            when(expDiff >= 56) {
              addMantB := U(0, 57 bits); sticky := (bMant =/= 0)
            } otherwise {
              val shAmt = expDiff.asUInt.resize(6 bits)
              addMantB := (bExt |>> shAmt).resize(57 bits)
              sticky := ((bExt & ((U(1, 57 bits) |<< shAmt) - 1)) =/= 0)
            }
            resExp := aExp; resSign := aSign
          } otherwise {
            addMantA := (U"2'b00" @@ bMant).resized
            val negDiff = (-expDiff).resize(13 bits)
            val aExt = (U"2'b00" @@ aMant).resize(57 bits)
            when(negDiff >= 56) {
              addMantB := U(0, 57 bits); sticky := (aMant =/= 0)
            } otherwise {
              val shAmt = negDiff.asUInt.resize(6 bits)
              addMantB := (aExt |>> shAmt).resize(57 bits)
              sticky := ((aExt & ((U(1, 57 bits) |<< shAmt) - 1)) =/= 0)
            }
            resExp := bExp; resSign := bSign
          }
          state := State.ADD_EXEC
        }
      }

      is(State.ADD_EXEC) {
        // Speculatively compute all results in parallel, register them.
        // This breaks the 57-bit compare -> 57-bit subtract critical path.
        addDiffAB := addMantA - addMantB
        addDiffBA := addMantB - addMantA
        addSumAB  := (addMantA + addMantB).resize(57 bits)
        addAgeB   := addMantA >= addMantB
        state := State.ADD_SELECT
      }

      is(State.ADD_SELECT) {
        // Select result from registered speculative computations (MUX only)
        val sumWide = UInt(57 bits)
        when(addIsSubOp) {
          when(addAgeB) {
            sumWide := addDiffAB
          } otherwise {
            sumWide := addDiffBA
            resSign := !resSign
          }
        } otherwise {
          sumWide := addSumAB
        }

        when(sumWide === 0 && !sticky) {
          resultReg := toResult(POS_ZERO); state := State.DONE
        } otherwise {
          addMantA := sumWide
          state := State.ADD_NORM
        }
      }

      is(State.ADD_NORM) {
        val sum = addMantA  // 57 bits; hidden bit was at bit 54

        when(sum(56)) {
          // Shift right by 2
          resMant(54 downto 2) := sum(56 downto 4)
          resMant(1) := sum(3)
          resMant(0) := sum(2)
          sticky := sticky | sum(1) | sum(0)
          resExp := resExp + 2; state := State.ROUND
        } elsewhen (sum(55)) {
          // Shift right by 1
          resMant(54 downto 2) := sum(55 downto 3)
          resMant(1) := sum(2)
          resMant(0) := sum(1)
          sticky := sticky | sum(0)
          resExp := resExp + 1; state := State.ROUND
        } elsewhen (sum(54)) {
          resMant := sum(54 downto 0)
          state := State.ROUND
        } otherwise {
          val lz = clz57(sum)
          when(sum === 0) {
            resultReg := toResult(resSign ## B"63'x0"); state := State.DONE
          } otherwise {
            val shAmt = UInt(6 bits)
            when(lz <= 2) { shAmt := 0 }
              .otherwise  { shAmt := lz - 2 }
            val shifted = (sum |<< shAmt).resize(57 bits)
            resMant := shifted(54 downto 0)
            resExp := resExp - shAmt.asSInt.resize(13 bits)
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
          resultReg := toResult((aSign ^ bSign) ## POS_INF(62 downto 0)); state := State.DONE
        } elsewhen (aZero || bZero) {
          resultReg := toResult((aSign ^ bSign) ## B"63'x0"); state := State.DONE
        } otherwise {
          resSign := aSign ^ bSign
          resExp := aExp + bExp
          state := State.MUL_STEP2
        }
      }

      is(State.MUL_STEP2) {
        // 53x53 -> 106-bit multiply; aMant(54:2) = {hidden, 52 frac} = 53 bits
        // Register the product — normalize in MUL_NORM to break the critical path
        val mantA53 = aMant(54 downto 2)
        val mantB53 = bMant(54 downto 2)
        mulProdHi := mantA53 * mantB53  // 106-bit result -> register
        state := State.MUL_NORM
      }

      is(State.MUL_NORM) {
        when(mulProdHi(105)) {
          resMant := mulProdHi(105 downto 51).resized
          sticky := mulProdHi(50 downto 0) =/= 0
          resExp := resExp + 1
        } otherwise {
          resMant := mulProdHi(104 downto 50).resized
          sticky := mulProdHi(49 downto 0) =/= 0
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
          resultReg := toResult((aSign ^ bSign) ## POS_INF(62 downto 0)); state := State.DONE
        } elsewhen (bZero && aZero) {
          resultReg := toResult(CANONICAL_NAN); state := State.DONE
        } elsewhen (bZero) {
          resultReg := toResult((aSign ^ bSign) ## POS_INF(62 downto 0)); state := State.DONE
        } elsewhen (aZero) {
          resultReg := toResult((aSign ^ bSign) ## B"63'x0"); state := State.DONE
        } elsewhen (bInf) {
          resultReg := toResult((aSign ^ bSign) ## B"63'x0"); state := State.DONE
        } otherwise {
          resSign := aSign ^ bSign

          val mantA53 = aMant(54 downto 2)  // 53 bits
          val mantB53 = bMant(54 downto 2)
          when(mantA53 >= mantB53) {
            divRemainder := (U"4'b0000" @@ (mantA53 - mantB53)).resized
            resExp := aExp - bExp
            divQuotient := U(1, 55 bits)
          } otherwise {
            divRemainder := (U"4'b0000" @@ mantA53).resized
            resExp := aExp - bExp - 1
            divQuotient := U(0, 55 bits)
          }
          divDivisor := (U"4'b0000" @@ mantB53).resized
          divCount := U(0, 6 bits)
          state := State.DIV_ITER
        }
      }

      is(State.DIV_ITER) {
        val trial = (divRemainder |<< 1).resize(57 bits) - divDivisor
        when(!trial(56)) {
          divRemainder := trial
          divQuotient := (divQuotient |<< 1) | 1
        } otherwise {
          divRemainder := (divRemainder |<< 1).resize(57 bits)
          divQuotient := (divQuotient |<< 1)
        }
        divCount := divCount + 1

        // After 54 iterations (+ 1 pre-comparison bit = 55 quotient bits)
        when(divCount === 53) {
          sticky := (divRemainder =/= 0)
          val q = divQuotient
          when(q(54)) {
            resMant := q
          } otherwise {
            resMant := (q |<< 1).resized
          }
          state := State.ROUND
        }
      }
    }

    // ======================================================================
    // I2D (int32 -> double)
    // ======================================================================
    if (config.withI2D) {
      is(State.I2D_EXEC) {
        val intVal = opaReg(31 downto 0).asSInt
        when(intVal === 0) {
          resultReg := toResult(POS_ZERO); state := State.DONE
        } otherwise {
          val negative = intVal(31)
          val absVal = UInt(32 bits)
          when(negative) { absVal := (-intVal).asUInt }
            .otherwise   { absVal := intVal.asUInt }

          val lz = clz32(absVal)
          resSign := negative

          // Exponent: leading 1 at bit (31 - lz)
          resExp := (S(31, 13 bits) - lz.resize(13 bits).asSInt)

          // All 32-bit ints fit exactly in 53-bit mantissa — no rounding needed
          // Place hidden bit at bit 54, frac at 53..2, guard=0, round=0
          // Leading 1 at bit (31-lz); want at bit 54 -> shift left by (54-(31-lz)) = (23+lz)
          val shL = (U(23, 6 bits) + lz).resize(6 bits)
          resMant := (absVal.resize(55 bits) |<< shL).resize(55 bits)
          sticky := False

          // Pack directly — exact, no rounding needed
          val biasedExp = (S(31, 13 bits) - lz.resize(13 bits).asSInt + 1023).resize(13 bits)
          // Frac = absVal shifted so hidden bit is removed, 52 bits
          val mantShifted = (absVal.resize(55 bits) |<< shL).resize(55 bits)
          val frac52 = mantShifted(53 downto 2).asBits  // 52 bits
          resultReg := toResult(negative ## biasedExp(10 downto 0).asBits ## frac52)
          state := State.DONE
        }
      }
    }

    // ======================================================================
    // D2I (double -> int32)
    // ======================================================================
    if (config.withD2I) {
      is(State.D2I_EXEC) {
        when(aNaN) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aZero) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aInf) {
          resultReg := Mux(aSign, B"32'x80000000", B"32'x7FFFFFFF").asUInt.resize(64)
          state := State.DONE
        } otherwise {
          val mant53 = aMant(54 downto 2)  // 53 bits: {hidden, 52 frac}
          when(aExp >= 31) {
            when(aSign) {
              resultReg := B"32'x80000000".asUInt.resize(64)
            } otherwise {
              resultReg := B"32'x7FFFFFFF".asUInt.resize(64)
            }
            state := State.DONE
          } elsewhen (aExp < 0) {
            resultReg := U(0, 64 bits); state := State.DONE
          } otherwise {
            // 0 <= exp <= 30: integer = mant53 >> (52 - exp)
            val shR = (S(52, 13 bits) - aExp).asUInt.resize(6 bits)
            val intVal = (mant53 |>> shR).resize(32 bits)
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
    // L2D (long -> double)
    // ======================================================================
    if (config.withL2D) {
      // Pipeline stage 1: negate + CLZ, register absVal and lz
      is(State.L2D_EXEC) {
        val longVal = opaReg.asSInt
        when(longVal === 0) {
          resultReg := toResult(POS_ZERO); state := State.DONE
        } otherwise {
          val negative = longVal(63)
          val absVal = UInt(64 bits)
          when(negative) { absVal := (-longVal).asUInt }
            .otherwise   { absVal := longVal.asUInt }

          l2dAbsVal := absVal
          l2dLz := clz64(absVal)
          resSign := negative
          state := State.L2D_SHIFT
        }
      }

      // Pipeline stage 2: barrel shift + sticky from registered absVal/lz
      is(State.L2D_SHIFT) {
        // Exponent: leading 1 at bit (63 - lz)
        resExp := (S(63, 13 bits) - l2dLz.resize(13 bits).asSInt)

        // 64-bit integer may not fit exactly in 53-bit mantissa
        // Place into resMant: hidden at bit 54, frac at 53:2, guard at 1, round at 0
        // Leading 1 at bit (63-lz); want at bit 54 -> shift = lz - 9
        // If lz < 9: right shift by (9-lz), losing bits -> needs rounding
        // If lz >= 9: left shift by (lz-9), exact or near-exact
        when(l2dLz < 9) {
          val shR = (U(9, 7 bits) - l2dLz).resize(6 bits)
          val mask = (U(1, 64 bits) |<< shR) - 1
          sticky := (l2dAbsVal & mask) =/= 0
          resMant := (l2dAbsVal |>> shR).resize(55 bits)
        } otherwise {
          val shL = (l2dLz - 9).resize(6 bits)
          sticky := False
          resMant := (l2dAbsVal.resize(55 bits) |<< shL).resize(55 bits)
        }
        state := State.ROUND
      }
    }

    // ======================================================================
    // D2L (double -> long)
    // ======================================================================
    if (config.withD2L) {
      is(State.D2L_EXEC) {
        when(aNaN) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aZero) {
          resultReg := U(0, 64 bits); state := State.DONE
        } elsewhen (aInf) {
          resultReg := Mux(aSign, B"64'x8000000000000000", B"64'x7FFFFFFFFFFFFFFF").asUInt
          state := State.DONE
        } otherwise {
          val mant53 = aMant(54 downto 2)  // 53 bits
          when(aExp >= 63) {
            when(aSign) {
              resultReg := B"64'x8000000000000000".asUInt
            } otherwise {
              resultReg := B"64'x7FFFFFFFFFFFFFFF".asUInt
            }
            state := State.DONE
          } elsewhen (aExp < 0) {
            resultReg := U(0, 64 bits); state := State.DONE
          } otherwise {
            // 0 <= exp <= 62: integer = mant53 << (exp - 52) or >> (52 - exp)
            when(aExp >= 52) {
              val shL = (aExp - 52).asUInt.resize(6 bits)
              val intVal = (mant53.resize(64 bits) |<< shL).resize(64 bits)
              when(aSign) {
                resultReg := (-intVal.asSInt).asBits.asUInt
              } otherwise {
                resultReg := intVal
              }
            } otherwise {
              val shR = (S(52, 13 bits) - aExp).asUInt.resize(6 bits)
              val intVal = (mant53 |>> shR).resize(64 bits)
              when(aSign) {
                resultReg := (-intVal.asSInt).asBits.asUInt
              } otherwise {
                resultReg := intVal
              }
            }
            state := State.DONE
          }
        }
      }
    }

    // ======================================================================
    // F2D (float32 -> double)
    // ======================================================================
    if (config.withF2D) {
      is(State.F2D_EXEC) {
        val floatBits = opaReg(31 downto 0)
        val fSign   = floatBits(31)
        val fRawExp = floatBits(30 downto 23).asUInt   // 8 bits
        val fFrac   = floatBits(22 downto 0).asUInt    // 23 bits

        when(fRawExp === 0) {
          // Zero or subnormal -> zero
          resultReg := toResult(fSign ## B"63'x0"); state := State.DONE
        } elsewhen (fRawExp === 0xFF) {
          when(fFrac === 0) {
            // Inf
            resultReg := toResult(fSign ## POS_INF(62 downto 0)); state := State.DONE
          } otherwise {
            // NaN
            resultReg := toResult(CANONICAL_NAN); state := State.DONE
          }
        } otherwise {
          // Normal: widen exponent (add 1023-127=896), zero-extend frac
          val dExp = (fRawExp.resize(11 bits) + U(896, 11 bits))
          val dFrac = (fFrac ## B"29'x0")  // 52 bits
          resultReg := toResult(fSign ## dExp.asBits ## dFrac)
          state := State.DONE
        }
      }
    }

    // ======================================================================
    // D2F (double -> float32)
    // ======================================================================
    if (config.withD2F) {
      is(State.D2F_EXEC) {
        when(aNaN) {
          resultReg := toResult32(SP_CANONICAL_NAN); state := State.DONE
        } elsewhen (aZero) {
          resultReg := toResult32(aSign ## B"31'x0"); state := State.DONE
        } elsewhen (aInf) {
          resultReg := toResult32(aSign ## SP_POS_INF(30 downto 0)); state := State.DONE
        } otherwise {
          // Check for exponent overflow/underflow in single precision
          val spExp = aExp + 127
          when(spExp >= 255) {
            // Overflow -> Inf
            resultReg := toResult32(aSign ## B"8'xFF" ## B"23'x0"); state := State.DONE
          } elsewhen (spExp <= 0) {
            // Underflow -> zero (FTZ)
            resultReg := toResult32(aSign ## B"31'x0"); state := State.DONE
          } otherwise {
            // Set d2fMode so ROUND uses single-precision packing
            d2fMode := True
            // resMant: take upper 26 bits of the double mantissa (hidden + 23 frac + guard + round)
            // aMant: bit54=hidden, 53..2=frac(52 bits), 1=guard, 0=round
            // For SP: need hidden + 23 frac + guard + round = 26 bits
            // Take aMant(54 downto 31) for hidden+23frac, aMant(30) for guard, aMant(29) for round
            resMant(54 downto 31) := aMant(54 downto 31)
            resMant(30) := aMant(30)  // guard
            resMant(29) := aMant(29)  // round
            resMant(28 downto 0) := U(0, 29 bits)
            sticky := aMant(28 downto 0) =/= 0
            resSign := aSign
            resExp := aExp
            state := State.ROUND
          }
        }
      }
    }

    // ======================================================================
    // DCMPL / DCMPG
    // ======================================================================
    if (config.withDcmp) {
      is(State.DCMP_EXEC) {
        when(aNaN || bNaN) {
          // opcodeReg 4=dcmpl (NaN->-1), 5=dcmpg (NaN->+1)
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
              when(aExp < bExp)      { aLess := True;  bLess := False }
              .elsewhen(aExp > bExp) { aLess := False; bLess := True }
              .elsewhen(aMant < bMant) { aLess := True;  bLess := False }
              .elsewhen(aMant > bMant) { aLess := False; bLess := True }
              .otherwise               { aLess := False; bLess := False }
            } otherwise {
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
    // ROUND (RNE) — double or single precision depending on d2fMode
    // ======================================================================
    is(State.ROUND) {
      when(d2fMode) {
        // Single-precision rounding (for D2F)
        // resMant(54 downto 31) = hidden + 23 frac, resMant(30) = guard, resMant(29) = round
        val guard    = resMant(30)
        val roundBit = resMant(29)
        val mantLsb  = resMant(31)
        val roundUp  = guard & (roundBit | sticky | mantLsb)

        val mantToRound = resMant(54 downto 31)  // 24 bits
        val rounded = UInt(25 bits)
        when(roundUp) {
          rounded := (U"1'b0" @@ mantToRound) + 1
        } otherwise {
          rounded := (U"1'b0" @@ mantToRound)
        }

        val finalExp = SInt(13 bits)
        val frac = Bits(23 bits)
        when(rounded(24)) {
          finalExp := resExp + 1
          frac := rounded(23 downto 1).asBits
        } otherwise {
          finalExp := resExp
          frac := rounded(22 downto 0).asBits
        }

        val biasedExp = (finalExp + 127).resize(13 bits)
        when(biasedExp >= 255) {
          resultReg := toResult32(resSign ## B"8'xFF" ## B"23'x0")
        } elsewhen (biasedExp <= 0) {
          resultReg := toResult32(resSign ## B"31'x0")
        } otherwise {
          resultReg := toResult32(resSign ## biasedExp(7 downto 0).asBits ## frac)
        }
        d2fMode := False
        state := State.DONE
      } otherwise {
        // Double-precision rounding
        // resMant: bit54=hidden, bits53..2=frac, bit1=guard, bit0=round
        val guard    = resMant(1)
        val roundBit = resMant(0)
        val mantLsb  = resMant(2)
        val roundUp  = guard & (roundBit | sticky | mantLsb)

        val mantToRound = resMant(54 downto 2)  // 53 bits
        val rounded = UInt(54 bits)
        when(roundUp) {
          rounded := (U"1'b0" @@ mantToRound) + 1
        } otherwise {
          rounded := (U"1'b0" @@ mantToRound)
        }

        val finalExp = SInt(13 bits)
        val frac = Bits(52 bits)
        when(rounded(53)) {
          finalExp := resExp + 1
          frac := rounded(52 downto 1).asBits
        } otherwise {
          finalExp := resExp
          frac := rounded(51 downto 0).asBits
        }

        val biasedExp = (finalExp + 1023).resize(13 bits)
        when(biasedExp >= 2047) {
          resultReg := toResult(resSign ## B"11'x7FF" ## B"52'x0")  // overflow -> Inf
        } elsewhen (biasedExp <= 0) {
          resultReg := toResult(resSign ## B"63'x0")  // underflow -> zero (FTZ)
        } otherwise {
          resultReg := toResult(resSign ## biasedExp(10 downto 0).asBits ## frac)
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
