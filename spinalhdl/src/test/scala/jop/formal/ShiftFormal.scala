package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.core.Shift

/**
 * Formal verification for the Shift (Barrel Shifter) component.
 *
 * Source: jop/core/Shift.scala (214 lines)
 *
 * The shifter is purely combinational (0-cycle latency).
 * Shift types:
 *   00 (USHR): Unsigned shift right (zero fill)
 *   01 (SHL):  Shift left (zero fill)
 *   10 (SHR):  Arithmetic shift right (sign extend)
 *
 * BMC depth 2 is sufficient: 1 reset cycle + 1 check cycle.
 */
class ShiftFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("USHR correctness") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(Shift(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.din)
        anyseq(dut.io.off)
        dut.io.shtyp := B"2'b00" // USHR

        when(pastValidAfterReset()) {
          assert(dut.io.dout === (dut.io.din |>> dut.io.off))
        }
      })
  }

  test("SHL correctness") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(Shift(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.din)
        anyseq(dut.io.off)
        dut.io.shtyp := B"2'b01" // SHL

        when(pastValidAfterReset()) {
          assert(dut.io.dout === (dut.io.din |<< dut.io.off))
        }
      })
  }

  test("SHR correctness") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(Shift(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.din)
        anyseq(dut.io.off)
        dut.io.shtyp := B"2'b10" // SHR

        when(pastValidAfterReset()) {
          // Arithmetic right shift: sign-extend from MSB
          val signed = dut.io.din.asSInt
          val expected = (signed >> dut.io.off).asUInt
          assert(dut.io.dout === expected)
        }
      })
  }

  test("full shift by 31") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(Shift(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.din)
        anyseq(dut.io.shtyp)
        dut.io.off := U(31, 5 bits) // shift by 31

        when(pastValidAfterReset()) {
          when(dut.io.shtyp === B"2'b00") {
            // USHR by 31: only MSB remains at bit 0
            assert(dut.io.dout === (dut.io.din |>> 31))
          }
          when(dut.io.shtyp === B"2'b01") {
            // SHL by 31: only LSB remains at bit 31
            assert(dut.io.dout === (dut.io.din |<< 31))
          }
          when(dut.io.shtyp === B"2'b10") {
            // SHR by 31: sign bit fills all positions
            val signed = dut.io.din.asSInt
            val expected = (signed >> 31).asUInt
            assert(dut.io.dout === expected)
          }
        }
      })
  }

}
