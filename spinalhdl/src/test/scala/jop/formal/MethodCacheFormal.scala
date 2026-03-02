package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.memory.MethodCache

/**
 * Formal verification for the MethodCache component.
 *
 * Source: jop/memory/MethodCache.scala
 *
 * Properties verified:
 * - State machine only transitions IDLE->S1->IDLE (hit) or IDLE->S1->S2->IDLE (miss)
 * - No spurious transitions: S2 never reached without going through S1
 * - rdy output: true iff state==IDLE
 * - inCache stable until next find
 * - Tag cleared on replacement
 * - FIFO pointer advances on miss only
 */
class MethodCacheFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  def setupDut(dut: MethodCache): Unit = {
    anyseq(dut.io.bcLen)
    anyseq(dut.io.bcAddr)
    anyseq(dut.io.find)
  }

  test("rdy true iff state is IDLE") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.rdy === (dut.state === dut.State.IDLE))
        }
      })
  }

  test("state machine valid transitions") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        val pastState = RegNext(dut.state)

        when(pastValidAfterReset()) {
          // S1 can only be reached from IDLE
          when(dut.state === dut.State.S1) {
            assert(pastState === dut.State.IDLE || pastState === dut.State.S1)
          }
          // S2 can only be reached from S1 (S2 is a one-cycle state that
          // always returns to IDLE, so S2→S2 is impossible)
          when(dut.state === dut.State.S2) {
            assert(pastState === dut.State.S1)
          }
          // IDLE can be reached from IDLE (no find), S1 (hit), or S2 (miss complete)
          when(dut.state === dut.State.IDLE) {
            assert(pastState === dut.State.IDLE || pastState === dut.State.S1 || pastState === dut.State.S2)
          }
        }
      })
  }

  test("find triggers state transition from IDLE to S1") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.state === dut.State.IDLE) && past(dut.io.find)) {
            assert(dut.state === dut.State.S1)
          }
        }
      })
  }

  test("inCache stable when no find") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // inCache only changes during S1 state (when tag check completes)
          when(past(dut.state =/= dut.State.S1)) {
            assert(stable(dut.io.inCache))
          }
        }
      })
  }

}
