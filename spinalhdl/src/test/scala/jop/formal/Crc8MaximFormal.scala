package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.debug.Crc8Maxim

/**
 * Formal verification for the Crc8Maxim component.
 *
 * Source: jop/debug/Crc8Maxim.scala (58 lines)
 *
 * Properties verified:
 * - clear resets CRC register to zero
 * - CRC is stable when neither enabled nor cleared
 * - Non-zero byte from zero CRC produces non-zero CRC (polynomial property)
 */
class Crc8MaximFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("clear resets CRC to zero") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(Crc8Maxim())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.clear)
        anyseq(dut.io.enable)
        anyseq(dut.io.data)

        when(pastValidAfterReset()) {
          when(past(dut.io.clear)) {
            assert(dut.crcReg === 0)
          }
        }
      })
  }

  test("CRC stable when not enabled and not cleared") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(Crc8Maxim())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.clear)
        anyseq(dut.io.enable)
        anyseq(dut.io.data)

        when(pastValidAfterReset()) {
          when(!past(dut.io.clear) && !past(dut.io.enable)) {
            assert(stable(dut.crcReg))
          }
        }
      })
  }

  test("non-zero byte from zero CRC produces non-zero CRC") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(Crc8Maxim())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.clear)
        anyseq(dut.io.enable)
        anyseq(dut.io.data)

        when(pastValidAfterReset()) {
          // Polynomial 0x31 has no zero-byte fixpoints from init=0
          when(past(dut.io.enable) && past(dut.io.data =/= 0) &&
               !past(dut.io.clear) && past(dut.crcReg === 0)) {
            assert(dut.crcReg =/= 0)
          }
        }
      })
  }
}
