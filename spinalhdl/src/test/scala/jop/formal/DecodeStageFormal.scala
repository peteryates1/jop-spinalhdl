package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.pipeline.{DecodeStage, DecodeConfig}

/**
 * Formal verification for the DecodeStage component.
 *
 * Source: jop/pipeline/DecodeStage.scala (1306 lines)
 *
 * The decode stage translates 10-bit microcode instructions into control signals.
 * Properties focus on mutual exclusion and signal validity.
 */
class DecodeStageFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("memory operation mutual exclusion") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(DecodeStage())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.instr)
        anyseq(dut.io.zf)
        anyseq(dut.io.nf)
        anyseq(dut.io.eq)
        anyseq(dut.io.lt)
        anyseq(dut.io.bcopd)
        anyseq(dut.io.stall)

        when(pastValidAfterReset()) {
          // At most one of {rd, wr, addrWr} should be active per cycle
          val memCount = dut.io.memIn.rd.asUInt +^ dut.io.memIn.wr.asUInt +^ dut.io.memIn.addrWr.asUInt
          assert(memCount <= 1)
        }
      })
  }

  test("field operation mutual exclusion") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(DecodeStage())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.instr)
        anyseq(dut.io.zf)
        anyseq(dut.io.nf)
        anyseq(dut.io.eq)
        anyseq(dut.io.lt)
        anyseq(dut.io.bcopd)
        anyseq(dut.io.stall)

        when(pastValidAfterReset()) {
          // At most one of {getfield, putfield, getstatic, putstatic} active
          val fieldCount = dut.io.memIn.getfield.asUInt +^
            dut.io.memIn.putfield.asUInt +^
            dut.io.memIn.getstatic.asUInt +^
            dut.io.memIn.putstatic.asUInt
          assert(fieldCount <= 1)
        }
      })
  }

  test("br and jmp mutual exclusion") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(DecodeStage())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.instr)
        anyseq(dut.io.zf)
        anyseq(dut.io.nf)
        anyseq(dut.io.eq)
        anyseq(dut.io.lt)
        anyseq(dut.io.bcopd)
        anyseq(dut.io.stall)

        when(pastValidAfterReset()) {
          // br and jmp should never both be active
          assert(!(dut.io.br && dut.io.jmp))
        }
      })
  }

}
