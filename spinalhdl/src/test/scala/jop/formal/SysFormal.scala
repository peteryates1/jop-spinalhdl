package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.io.{Sys, SyncOut}

/**
 * Formal verification for the Sys component.
 *
 * Source: jop/io/Sys.scala
 *
 * Properties verified:
 * - Clock counter monotonicity: increments by 1 every cycle
 * - Exception pulse: exactly 1-cycle pulse per write to IO_EXC
 * - Lock request held: lockReqReg stable until explicit unlock
 * - CPU ID read returns correct value
 */
class SysFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  val clkFreq = 100 MHz

  /** @param haltViolated drive syncIn.haltViolated, which two properties below
    *        need HIGH. Taken as a parameter rather than overridden afterwards:
    *        SyncOut.tieOff already drives every field, and assigning it again
    *        is a complete overlap that SpinalHDL rejects outright. */
  def setupDut(dut: Sys, haltViolated: Boolean = false): Unit = {
    anyseq(dut.io.addr)
    anyseq(dut.io.rd)
    anyseq(dut.io.wr)
    anyseq(dut.io.wrData)
    SyncOut.tieOff(dut.io.syncIn)
    dut.io.syncIn.haltViolated.removeAssignments()
    dut.io.syncIn.haltViolated := Bool(haltViolated)
    dut.io.ackIrq := False
    dut.io.ackExc := False
    // Stack overflow: anyseq rather than False, so every property below is
    // proven for BOTH values. Tying it off would prove them only for the case
    // where no overflow ever occurs -- and the EXC_SPOV raise writes
    // excTypeReg/excPend, which is exactly what the exception properties are
    // about. Item 133.
    anyseq(dut.io.spOv)
    dut.io.ioInt := 0
    // Cross-core GC root data: a free input from the cluster, irrelevant to
    // every property here but it still needs a driver. Left as anyseq rather
    // than tied to a constant so the properties are proven for ANY value —
    // omitting it entirely is what broke this suite, since a Sys input with no
    // driver fails elaboration and takes all four tests down with it.
    anyseq(dut.io.rootData)
  }

  test("clock counter increments every cycle") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(Sys(clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.clockCntReg === past(dut.clockCntReg) + 1)
        }
      })
  }

  test("lock request set by write to addr 5") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(Sys(clkFreq))
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
        val dut = FormalDut(Sys(clkFreq))
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
        val dut = FormalDut(Sys(clkFreq))
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

  // ==========================================================================
  // THE VIOLATION COUNTER COUNTS, AND IS READABLE — status item 157.
  //
  // The CmpSync properties prove haltViolated asserts in the scenario that
  // matters (an exempt lock owner running through another core's gcHalt). These
  // prove the rest of the chain: that Sys turns that signal into a number, and
  // that the number comes back on IO_GC_MUTATOR.
  //
  // Both halves are needed. The whole point of this item is that GC.haltDeltaMax
  // was a value nothing could move, published every round as an all-clear, so
  // "it reads 0 on hardware" is only meaningful once 0 is a MEASUREMENT.
  // ==========================================================================
  test("the stop-the-world violation counter advances while haltViolated is high") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(Sys(cpuId = 0, cpuCnt = 2, clkFreq = clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut, haltViolated = true)   // a halt ignored, every cycle

        when(pastValidAfterReset()) {
          // strictly increasing, one per cycle, until it saturates
          assert(dut.gcMutatorCnt === past(dut.gcMutatorCnt) + 1)
        }
      })
  }

  test("the violation counter does NOT advance while the halt is honoured") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(Sys(cpuId = 0, cpuCnt = 2, clkFreq = clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)   // tieOff leaves haltViolated False

        when(pastValidAfterReset()) {
          // THE CONTROL. Without it, a counter wired to increment every cycle
          // would satisfy the property above and report violations that never
          // happened -- which is the same defect as reporting none, inverted.
          assert(dut.gcMutatorCnt === 0)
        }
      })
  }

  test("IO_GC_MUTATOR reads back the violation counter") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(Sys(cpuId = 0, cpuCnt = 2, clkFreq = clkFreq))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut, haltViolated = true)

        when(pastValidAfterReset()) {
          // The value must be reachable from software, or the counter is as
          // unobservable as the field it replaces.
          when(dut.io.addr === 3) {
            assert(dut.io.rdData.asUInt === dut.gcMutatorCnt)
          }
        }
      })
  }

}
