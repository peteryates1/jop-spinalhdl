/**
  * SimpleFpu — Standalone Configurable IEEE 754 Single-Precision FPU
  *
  * Minimal FPU with configurable operations for area-constrained FPGAs.
  * Each operation can be included/excluded via SimpleFpuConfig flags.
  *
  * Operations:
  *   0: ADD    1: SUB    2: MUL    3: DIV
  *   4: I2F    5: F2I    6: FCMPL  7: FCMPG
  *
  * Interface: load opa/opb/opcode, pulse start, wait for ready pulse.
  * Result appears on io.result when ready pulses.
  *
  * Special value handling follows Java semantics:
  *   - NaN propagation, canonical NaN = 0x7FC00000
  *   - Inf arithmetic per JLS
  *   - Subnormals flushed to zero (FTZ)
  *   - F2I: truncate toward zero, clamp on overflow, 0 on NaN
  *   - FCMPL: NaN → −1;  FCMPG: NaN → +1
  *
  * Rounding: Round-to-Nearest-Even (RNE)
  *
  * Internal mantissa format (26 bits):
  *   bit 25     = hidden bit (1 for normalized)
  *   bits 24..2 = 23 fraction bits
  *   bit 1      = guard bit
  *   bit 0      = round bit
  */
package jop.io

import spinal.core._

case class SimpleFpuConfig(
    withAdd:  Boolean = true,
    withMul:  Boolean = true,
    withDiv:  Boolean = true,
    withI2F:  Boolean = false,
    withF2I:  Boolean = false,
    withFcmp: Boolean = false
)

case class SimpleFpu(config: SimpleFpuConfig = SimpleFpuConfig()) extends Component {
  val io = new Bundle {
    val opa    = in Bits (32 bits)
    val opb    = in Bits (32 bits)
    val opcode = in UInt (4 bits)
    val start  = in Bool ()
    val result = out Bits (32 bits)
    val ready  = out Bool ()
  }

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
    val ADD_ALIGN, ADD_EXEC, ADD_NORM   = newElement()
    val MUL_STEP1, MUL_STEP2           = newElement()
    val DIV_INIT, DIV_ITER              = newElement()
    val I2F_EXEC                        = newElement()
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

  val resultReg = Reg(Bits(32 bits)) init (0)
  val readyReg  = Reg(Bool()) init (False)
  val opcodeReg = Reg(UInt(4 bits)) init (0)
  val opaReg    = Reg(Bits(32 bits)) init (0)

  io.result := resultReg
  io.ready  := readyReg

  // ========================================================================
  // Unpack: IEEE 754 → internal
  //   mant layout: bit25=hidden, bits24..2=frac, bit1=guard, bit0=round
  // ========================================================================
  def unpackFloat(bits: Bits, sign: Bool, exp: SInt, mant: UInt,
                  isZero: Bool, isInf: Bool, isNaN: Bool): Unit = {
    val rawExp  = bits(30 downto 23).asUInt
    val rawFrac = bits(22 downto 0).asUInt
    sign := bits(31)
    when(rawExp === 0) {
      // Zero or subnormal → flush to zero
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
  // Pack: internal → IEEE 754 (for returning unpacked values directly)
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

  // ========================================================================
  // Operation-specific registers (always declared; unused ones optimized away)
  // ========================================================================
  val addMantA   = Reg(UInt(28 bits)) init (0)
  val addMantB   = Reg(UInt(28 bits)) init (0)
  val addIsSubOp = Reg(Bool()) init (False)

  val mulProdHi  = Reg(UInt(48 bits)) init (0)

  val divRemainder = Reg(UInt(28 bits)) init (0)
  val divDivisor   = Reg(UInt(28 bits)) init (0)
  val divQuotient  = Reg(UInt(26 bits)) init (0)
  val divCount     = Reg(UInt(5 bits)) init (0)

  // ========================================================================
  // CLZ helpers
  // ========================================================================
  def clz28(v: UInt): UInt = {
    val count = UInt(5 bits)
    count := 28
    // Ascending order: last-assignment-wins → highest set bit takes priority
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
  readyReg := False

  switch(state) {

    // ----------------------------------------------------------------------
    is(State.IDLE) {
      when(io.start) {
        opcodeReg := io.opcode
        opaReg    := io.opa
        sticky    := False
        if (config.withI2F) {
          when(io.opcode === 4) {
            state := State.I2F_EXEC
          } otherwise {
            state := State.UNPACK
          }
        } else {
          state := State.UNPACK
        }
      }
    }

    // ----------------------------------------------------------------------
    is(State.UNPACK) {
      unpackFloat(opaReg, aSign, aExp, aMant, aZero, aInf, aNaN)
      unpackFloat(io.opb, bSign, bExp, bMant, bZero, bInf, bNaN)
      state := State.DONE
      resultReg := B(0, 32 bits)

      if (config.withAdd) {
        when(opcodeReg === 0 || opcodeReg === 1) {
          when(opcodeReg === 1) { bSign := !io.opb(31) }
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
        when(opcodeReg === 5) { state := State.F2I_EXEC }
      }
      if (config.withFcmp) {
        when(opcodeReg === 6 || opcodeReg === 7) { state := State.FCMP_EXEC }
      }
    }

    // ======================================================================
    // ADD/SUB
    // ======================================================================
    if (config.withAdd) {
      is(State.ADD_ALIGN) {
        when(aNaN || bNaN) {
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen (aInf && bInf) {
          when(aSign === bSign) {
            resultReg := aSign ## POS_INF(30 downto 0)
          } otherwise {
            resultReg := CANONICAL_NAN
          }
          state := State.DONE
        } elsewhen (aInf) {
          resultReg := aSign ## POS_INF(30 downto 0); state := State.DONE
        } elsewhen (bInf) {
          resultReg := bSign ## POS_INF(30 downto 0); state := State.DONE
        } elsewhen (aZero && bZero) {
          resultReg := (aSign & bSign) ## B"31'x0"; state := State.DONE
        } elsewhen (aZero) {
          resultReg := packFloat(bSign, bExp, bMant); state := State.DONE
        } elsewhen (bZero) {
          resultReg := packFloat(aSign, aExp, aMant); state := State.DONE
        } otherwise {
          val expDiff = (aExp - bExp).resize(10 bits)
          addIsSubOp := (aSign =/= bSign)

          when(expDiff >= 0) {
            // A has larger or equal exponent — shift B right
            addMantA := (U"2'b00" @@ aMant).resized
            val bExt = (U"2'b00" @@ bMant).resize(28 bits)
            when(expDiff >= 27) {
              addMantB := U(0, 28 bits); sticky := (bMant =/= 0)
            } otherwise {
              val shAmt = expDiff.asUInt.resize(5 bits)
              addMantB := (bExt |>> shAmt).resize(28 bits)
              sticky := ((bExt & ((U(1, 28 bits) |<< shAmt) - 1)) =/= 0)
            }
            resExp := aExp; resSign := aSign
          } otherwise {
            // B has larger exponent — B goes in addMantA (unshifted), A in addMantB (shifted)
            // This ensures addMantA >= addMantB for subtraction, keeping sign logic correct
            addMantA := (U"2'b00" @@ bMant).resized
            val negDiff = (-expDiff).resize(10 bits)
            val aExt = (U"2'b00" @@ aMant).resize(28 bits)
            when(negDiff >= 27) {
              addMantB := U(0, 28 bits); sticky := (aMant =/= 0)
            } otherwise {
              val shAmt = negDiff.asUInt.resize(5 bits)
              addMantB := (aExt |>> shAmt).resize(28 bits)
              sticky := ((aExt & ((U(1, 28 bits) |<< shAmt) - 1)) =/= 0)
            }
            resExp := bExp; resSign := bSign
          }
          state := State.ADD_EXEC
        }
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
          resultReg := POS_ZERO; state := State.DONE
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
          // Shift right by 2: leading 1 at bit 27 → move to bit 25
          resMant(25 downto 2) := sum(27 downto 4)
          resMant(1) := sum(3)   // guard
          resMant(0) := sum(2)   // round
          sticky := sticky | sum(1) | sum(0)
          resExp := resExp + 2; state := State.ROUND
        } elsewhen (sum(26)) {
          // Shift right by 1: leading 1 at bit 26 → move to bit 25
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
            resultReg := resSign ## B"31'x0"; state := State.DONE
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
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen ((aInf && bZero) || (aZero && bInf)) {
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen (aInf || bInf) {
          resultReg := (aSign ^ bSign) ## POS_INF(30 downto 0); state := State.DONE
        } elsewhen (aZero || bZero) {
          resultReg := (aSign ^ bSign) ## B"31'x0"; state := State.DONE
        } otherwise {
          resSign := aSign ^ bSign
          resExp := aExp + bExp
          state := State.MUL_STEP2
        }
      }

      is(State.MUL_STEP2) {
        // 24x24 → 48-bit multiply; aMant(25:2) = {hidden, 23 frac} = 24 bits
        val mantA24 = aMant(25 downto 2)
        val mantB24 = bMant(25 downto 2)
        val product = mantA24 * mantB24  // 48-bit result

        mulProdHi := product

        // Product of two 1.xxx values: result in [1.0, 4.0)
        // Product bit 47 set → result >= 2.0, shift right 1
        // Product bit 46 set → result in [1.0, 2.0), already positioned
        when(product(47)) {
          // Map: product(47)→resMant(25)=hidden, product(46:24)→frac, product(23)→guard, product(22)→round
          resMant := product(47 downto 22).resized
          sticky := product(21 downto 0) =/= 0
          resExp := resExp + 1
        } otherwise {
          // Map: product(46)→resMant(25)=hidden, product(45:23)→frac, product(22)→guard, product(21)→round
          resMant := product(46 downto 21).resized
          sticky := product(20 downto 0) =/= 0
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
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen (aInf && bInf) {
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen (aInf) {
          resultReg := (aSign ^ bSign) ## POS_INF(30 downto 0); state := State.DONE
        } elsewhen (bZero && aZero) {
          resultReg := CANONICAL_NAN; state := State.DONE
        } elsewhen (bZero) {
          resultReg := (aSign ^ bSign) ## POS_INF(30 downto 0); state := State.DONE
        } elsewhen (aZero) {
          resultReg := (aSign ^ bSign) ## B"31'x0"; state := State.DONE
        } elsewhen (bInf) {
          resultReg := (aSign ^ bSign) ## B"31'x0"; state := State.DONE
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
      is(State.I2F_EXEC) {
        val intVal = opaReg.asSInt
        when(intVal === 0) {
          resultReg := POS_ZERO; state := State.DONE
        } otherwise {
          val negative = intVal(31)
          val absVal = UInt(32 bits)
          when(negative) { absVal := (-intVal).asUInt }
            .otherwise   { absVal := intVal.asUInt }

          val lz = clz32(absVal)
          resSign := negative
          // Exponent: leading 1 at bit (31 - lz), value = 2^(31-lz) * 1.xxx
          resExp := (S(31, 10 bits) - lz.resize(10 bits).asSInt)

          // Place into resMant: hidden at bit 25, frac at 24:2, guard at 1, round at 0
          // absVal leading 1 at bit (31-lz); we want it at bit 25 of 26-bit resMant
          // If (31-lz) > 25: shift right by (31-lz-25) = (6-lz)
          // If (31-lz) <= 25: shift left by (25-(31-lz)) = (lz-6)
          when(lz < 6) {
            val shR = (U(6, 6 bits) - lz).resize(5 bits)
            val mask = (U(1, 32 bits) |<< shR) - 1
            sticky := (absVal & mask) =/= 0
            resMant := (absVal |>> shR).resize(26 bits)
          } otherwise {
            val shL = (lz - 6).resize(5 bits)
            sticky := False
            resMant := (absVal |<< shL).resize(26 bits)
          }
          state := State.ROUND
        }
      }
    }

    // ======================================================================
    // F2I
    // ======================================================================
    if (config.withF2I) {
      is(State.F2I_EXEC) {
        when(aNaN) {
          resultReg := B(0, 32 bits); state := State.DONE
        } elsewhen (aZero) {
          resultReg := B(0, 32 bits); state := State.DONE
        } elsewhen (aInf) {
          resultReg := Mux(aSign, B"32'x80000000", B"32'x7FFFFFFF")
          state := State.DONE
        } otherwise {
          // aMant(25:2) = {hidden, 23 frac} = 24-bit significand
          // Value = significand * 2^(exp - 23)
          val mant24 = aMant(25 downto 2)
          when(aExp >= 31) {
            when(aSign) {
              resultReg := B"32'x80000000"
            } otherwise {
              resultReg := B"32'x7FFFFFFF"
            }
            state := State.DONE
          } elsewhen (aExp < 0) {
            // |value| < 1 → truncate to 0
            resultReg := B(0, 32 bits); state := State.DONE
          } otherwise {
            // 0 <= exp <= 30: integer = mant24 >> (23 - exp)
            val shR = (S(23, 10 bits) - aExp).asUInt.resize(5 bits)
            val intVal = (mant24 |>> shR).resize(32 bits)
            when(aSign) {
              resultReg := (-intVal.asSInt).asBits
            } otherwise {
              resultReg := intVal.asBits
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
          resultReg := Mux(opcodeReg === 6, B"32'xFFFFFFFF", B"32'x00000001")
          state := State.DONE
        } otherwise {
          when(aZero && bZero) {
            resultReg := B(0, 32 bits)
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
            when(aLess)        { resultReg := B"32'xFFFFFFFF" }
            .elsewhen(bLess)   { resultReg := B"32'x00000001" }
            .otherwise         { resultReg := B(0, 32 bits) }
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
        resultReg := resSign ## B"8'xFF" ## B"23'x0"  // overflow → Inf
      } elsewhen (biasedExp <= 0) {
        resultReg := resSign ## B"31'x0"  // underflow → zero (FTZ)
      } otherwise {
        resultReg := resSign ## biasedExp(7 downto 0).asBits ## frac
      }
      state := State.DONE
    }

    // ======================================================================
    // DONE
    // ======================================================================
    is(State.DONE) {
      readyReg := True
      state := State.IDLE
    }
  }
}
