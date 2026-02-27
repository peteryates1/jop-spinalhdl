package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.pipeline.{BytecodeFetchStage, BytecodeFetchConfig}

/**
 * Formal verification for the BytecodeFetchStage component.
 *
 * Source: jop/pipeline/BytecodeFetchStage.scala
 *
 * Note: This component contains a 256-entry JumpTable ROM and 2KB JBC RAM,
 * making deep BMC expensive. We use shallow depths and constrained inputs.
 *
 * Properties verified:
 * - No double-ack (ack_irq && ack_exc never both true)
 * - Exception ack requires jfetch
 * - Interrupt ack requires jfetch and ena
 * - IRQ latching regardless of ena
 */
class BytecodeFetchStageFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(120)

  /** Helper to drive all inputs with anyseq */
  def setupAllInputs(dut: BytecodeFetchStage): Unit = {
    anyseq(dut.io.jpc_wr)
    anyseq(dut.io.din)
    anyseq(dut.io.jfetch)
    anyseq(dut.io.jopdfetch)
    anyseq(dut.io.jbr)
    anyseq(dut.io.zf)
    anyseq(dut.io.nf)
    anyseq(dut.io.eq)
    anyseq(dut.io.lt)
    anyseq(dut.io.jbcWrAddr)
    anyseq(dut.io.jbcWrData)
    anyseq(dut.io.jbcWrEn)
    anyseq(dut.io.stall)
    anyseq(dut.io.irq)
    anyseq(dut.io.exc)
    anyseq(dut.io.ena)
  }

  test("no double acknowledge") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          assert(!(dut.io.ack_irq && dut.io.ack_exc))
        }
      })
  }

  test("exception acknowledge requires jfetch") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          when(dut.io.ack_exc) {
            assert(dut.io.jfetch)
          }
        }
      })
  }

  test("interrupt acknowledge requires jfetch and ena") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          when(dut.io.ack_irq) {
            assert(dut.io.jfetch)
            assert(dut.io.ena)
          }
        }
      })
  }

  test("interrupt latched even when disabled") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)

        // Constrain unused inputs to reduce state space
        dut.io.jpc_wr := False
        dut.io.din := B(0, 32 bits)
        dut.io.jopdfetch := False
        dut.io.jbr := False
        dut.io.zf := False
        dut.io.nf := False
        dut.io.eq := False
        dut.io.lt := False
        dut.io.jbcWrAddr := U(0)
        dut.io.jbcWrData := B(0)
        dut.io.jbcWrEn := False
        dut.io.stall := False

        // Scenario: IRQ arrives while ena=0, then ena goes high with jfetch
        dut.io.ena := False
        dut.io.exc := False
        dut.io.jfetch := False
        dut.io.irq := False

        val cycle = Reg(UInt(4 bits)) init (0)
        when(cycle < 7) { cycle := cycle + 1 }

        // Cycle 1: send IRQ while disabled
        when(cycle === 1) { dut.io.irq := True }

        // Cycle 4: enable interrupts and jfetch
        when(cycle === 4) {
          dut.io.ena := True
          dut.io.jfetch := True
        }

        // By cycle 4, the latched interrupt should fire ack_irq
        when(cycle === 4) {
          assert(dut.io.ack_irq)
        }
      })
  }
}
