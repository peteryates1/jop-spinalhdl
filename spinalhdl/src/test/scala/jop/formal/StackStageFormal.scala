package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.pipeline.{StackStage, StackConfig}

/**
 * Formal verification for the StackStage component.
 *
 * Source: jop/pipeline/StackStage.scala
 *
 * Properties verified:
 * - Flag computation (zf, nf, eq)
 * - SP management
 */
class StackStageFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  /** Helper: set all inputs to anyseq, disable debug write port */
  def setupDut(dut: StackStage): Unit = {
    anyseq(dut.io.din)
    anyseq(dut.io.dirAddr)
    anyseq(dut.io.opd)
    anyseq(dut.io.jpc)
    anyseq(dut.io.selSub)
    anyseq(dut.io.selAmux)
    anyseq(dut.io.enaA)
    anyseq(dut.io.selBmux)
    anyseq(dut.io.selLog)
    anyseq(dut.io.selShf)
    anyseq(dut.io.selLmux)
    anyseq(dut.io.selImux)
    anyseq(dut.io.selRmux)
    anyseq(dut.io.selSmux)
    anyseq(dut.io.selMmux)
    anyseq(dut.io.selRda)
    anyseq(dut.io.selWra)
    anyseq(dut.io.wrEna)
    anyseq(dut.io.enaB)
    anyseq(dut.io.enaVp)
    anyseq(dut.io.enaAr)
    dut.io.debugRamWrEn := False
    dut.io.debugRamWrAddr := U(0)
    dut.io.debugRamWrData := B(0)
    dut.io.debugRamAddr := U(0)
  }

  /** Helper: set all inputs to specific values (for constrained tests).
    * When setSmux=false, selSmux is left undriven so caller can assign it. */
  def setupDutConstrained(dut: StackStage, setSmux: Boolean = true): Unit = {
    dut.io.din := B(0)
    dut.io.dirAddr := U(0)
    dut.io.opd := B(0)
    dut.io.jpc := U(0)
    dut.io.selSub := False
    dut.io.selAmux := False
    dut.io.enaA := False
    dut.io.selBmux := False
    dut.io.selLog := B(0, 2 bits)
    dut.io.selShf := B(0, 2 bits)
    dut.io.selLmux := B(0, 3 bits)
    dut.io.selImux := B(0, 2 bits)
    dut.io.selRmux := B(0, 2 bits)
    if (setSmux) { dut.io.selSmux := B(0, 2 bits) }
    dut.io.selMmux := False
    dut.io.selRda := B(0, 3 bits)
    dut.io.selWra := B(0, 3 bits)
    dut.io.wrEna := False
    dut.io.enaB := False
    dut.io.enaVp := False
    dut.io.enaAr := False
    dut.io.debugRamWrEn := False
    dut.io.debugRamWrAddr := U(0)
    dut.io.debugRamWrData := B(0)
    dut.io.debugRamAddr := U(0)
  }

  test("zero flag correctness") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.zf === (dut.io.aout === B(0, 32 bits)))
        }
      })
  }

  test("negative flag correctness") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.nf === dut.io.aout(31))
        }
      })
  }

  test("equal flag correctness") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.eq === (dut.io.aout === dut.io.bout))
        }
      })
  }

  test("SP decrement on selSmux=01") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDutConstrained(dut, setSmux = false)

        // Force selSmux to decrement (pop)
        dut.io.selSmux := B"2'b01"

        when(pastValidAfterReset()) {
          assert(dut.io.debugSp === (past(dut.io.debugSp) - 1).resize(dut.config.ramWidth))
        }
      })
  }

  test("SP increment on selSmux=10") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDutConstrained(dut, setSmux = false)

        // Force selSmux to increment (push)
        dut.io.selSmux := B"2'b10"

        when(pastValidAfterReset()) {
          assert(dut.io.debugSp === (past(dut.io.debugSp) + 1).resize(dut.config.ramWidth))
        }
      })
  }

  test("SP hold on selSmux=00") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(StackStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDutConstrained(dut, setSmux = false)

        // Force selSmux to hold
        dut.io.selSmux := B"2'b00"

        when(pastValidAfterReset()) {
          assert(stable(dut.io.debugSp))
        }
      })
  }
}
