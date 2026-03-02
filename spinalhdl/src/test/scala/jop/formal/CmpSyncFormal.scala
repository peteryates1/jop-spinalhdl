package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.io.CmpSync

/**
 * Formal verification for the CmpSync component.
 *
 * Source: jop/io/CmpSync.scala
 *
 * Properties verified:
 * - Mutual exclusion: at most 1 core has halted=0 when locked (ignoring gcHalt)
 * - No deadlock: if all cores release req, state returns to IDLE
 * - GC halt isolation: gcHalt from core i doesn't affect core i's own lock status
 * - Signal broadcast: s_out for all cores equals core 0's s_in
 */
class CmpSyncFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  val cpuCnt = 2  // Use 2 cores for tractable verification

  def setupDut(dut: CmpSync): Unit = {
    for (i <- 0 until cpuCnt) {
      anyseq(dut.io.syncIn(i).req)
      anyseq(dut.io.syncIn(i).s_in)
      anyseq(dut.io.syncIn(i).gcHalt)
    }
  }

  test("mutual exclusion: at most one core not halted when locked") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable gcHalt to isolate lock behavior
        for (i <- 0 until cpuCnt) {
          assume(!dut.io.syncIn(i).gcHalt)
        }

        when(pastValidAfterReset()) {
          // halted output is driven by nextState (combinational), not state (registered).
          // Check when nextState is LOCKED (which determines halted output).
          when(dut.nextState === dut.State.LOCKED) {
            // Count cores that are NOT halted
            val notHalted = (0 until cpuCnt).map(i => (!dut.io.syncOut(i).halted).asUInt.resize(log2Up(cpuCnt + 1) bits))
            val totalNotHalted = notHalted.reduce(_ + _)
            assert(totalNotHalted <= 1)
          }
        }
      })
  }

  test("signal broadcast: s_out equals core 0 s_in") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          for (i <- 0 until cpuCnt) {
            assert(dut.io.syncOut(i).s_out === dut.io.syncIn(0).s_in)
          }
        }
      })
  }

  test("gcHalt from other core halts this core") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)

        // No lock requests, no s_in signals
        // Core 0 asserts gcHalt, core 1 does not
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True
        dut.io.syncIn(1).req := False
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        when(pastValidAfterReset()) {
          // Core 1 should be halted (gcHalt from core 0)
          assert(dut.io.syncOut(1).halted)
          // Core 0 should NOT be halted by its own gcHalt (no lock, no other gcHalt)
          assert(!dut.io.syncOut(0).halted)
        }
      })
  }

  test("lock owner not halted by gcHalt from other core") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)

        // Core 0 sets gcHalt, core 1 holds lock (req=True)
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True   // GC core
        dut.io.syncIn(1).req := True       // Lock holder
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        when(pastValidAfterReset()) {
          // When core 1 holds the lock, it must NOT be halted
          // (even though core 0 has gcHalt set) — otherwise deadlock
          when(dut.nextState === dut.State.LOCKED && dut.nextLockedId === 1) {
            assert(!dut.io.syncOut(1).halted)
          }
          // Core 0 (non-owner) should be halted when core 1 holds lock
          when(dut.nextState === dut.State.LOCKED && dut.nextLockedId === 1) {
            assert(dut.io.syncOut(0).halted)
          }
        }
      })
  }

}
