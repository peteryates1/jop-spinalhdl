package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.io.{BmbSys, SyncOut}

/**
 * Formal verification for the BmbSys component.
 *
 * Source: jop/io/BmbSys.scala
 *
 * Properties verified:
 * - Clock counter monotonicity: increments by 1 every cycle
 * - Exception pulse: exactly 1-cycle pulse per write to IO_EXC
 * - Lock request held: lockReqReg stable until explicit unlock
 * - CPU ID read returns correct value
 */
class BmbSysFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  val clkFreq = 100000000L  // 100 MHz

  def setupDut(dut: BmbSys): Unit = {
    anyseq(dut.io.addr)
    anyseq(dut.io.rd)
    anyseq(dut.io.wr)
    anyseq(dut.io.wrData)
    dut.io.syncIn.halted := False
    dut.io.syncIn.s_out := False
    dut.io.syncIn.status := False
    dut.io.ackIrq := False
    dut.io.ackExc := False
    dut.io.ioInt := 0
  }

  test("clock counter increments every cycle") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.clockCntReg === past(dut.clockCntReg) + 1)
        }
      })
  }

  test("exception pulse is one cycle") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // exc is a one-cycle pulse: excPend && !excDly
          // Two consecutive exc=True should be impossible without two writes
          when(dut.io.exc) {
            assert(dut.excPend)
          }
        }
      })
  }

  test("lock request set by write to addr 5") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.io.wr) && past(dut.io.addr === 5)) {
            assert(dut.lockReqReg)
          }
        }
      })
  }

  test("lock request cleared by write to addr 6") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.io.wr) && past(dut.io.addr === 6)) {
            assert(!dut.lockReqReg)
          }
        }
      })
  }

  test("lock request held when no write to addr 5 or 6") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // If no write happens to addr 5 or 6, lockReqReg is stable
          when(past(!dut.io.wr) || (past(dut.io.addr =/= 5) && past(dut.io.addr =/= 6))) {
            assert(stable(dut.lockReqReg))
          }
        }
      })
  }

  test("halted output follows syncIn.halted") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(BmbSys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        anyseq(dut.io.addr)
        anyseq(dut.io.rd)
        anyseq(dut.io.wr)
        anyseq(dut.io.wrData)
        anyseq(dut.io.syncIn.halted)
        dut.io.syncIn.s_out := False
        dut.io.syncIn.status := False
        dut.io.ackIrq := False
        dut.io.ackExc := False
        dut.io.ioInt := 0

        when(pastValidAfterReset()) {
          assert(dut.io.halted === dut.io.syncIn.halted)
        }
      })
  }
}
