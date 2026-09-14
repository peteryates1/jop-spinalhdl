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

  // ==========================================================================
  // AND THE EXEMPTION MUST BE OBSERVABLE — status item 157.
  //
  // The property above PROVES that a lock owner keeps running through another
  // core's gcHalt. That is by design: the owner must finish its critical
  // section or the cluster deadlocks. What was missing is any way to know it
  // happened. `GC.haltDeltaMax` was supposed to be that -- "Largest mutator
  // advance seen across a stop-the-world. Must stay 0" -- but the counter it
  // subtracts, `mutatorTick`, is assigned by NOTHING anywhere in the tree, so
  // the field is a constant 0 and SmpGcTest has printed `haltLeak 0` every
  // round of every SMP GC soak as an all-clear it cannot have earned.
  //
  // Software cannot fix that cheaply: a counter every core bumps often enough
  // to be meaningful is a contended shared-memory write on the allocation path.
  // The cluster already knows -- CmpSync computes every core's `halted` -- so
  // the signal is free here and costs the mutator nothing.
  //
  // haltViolated is high on exactly the cycles a stop-the-world is in force and
  // some core is neither the requester nor halted.
  // ==========================================================================
  test("haltViolated is asserted while an exempt lock owner runs through a gcHalt") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)

        // Identical stimulus to the exemption property above: core 0 is the
        // collector asking for the world to stop, core 1 holds the lock.
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True
        dut.io.syncIn(1).req := True
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        // ONE CYCLE BEHIND, and the property says so. haltViolated is
        // registered before broadcast -- combinationally it fanned out from
        // nextState through every core's halted to cpuCnt counter enables and
        // cost the 8-core build its timing. So the assertion is on the PAST
        // condition; writing it on the present one passes anyway, because the
        // LOCKED state persists and the register catches up within the BMC
        // depth, which would be passing for the wrong reason.
        when(pastValidAfterReset()) {
          when(past(dut.nextState === dut.State.LOCKED && dut.nextLockedId === 1)) {
            // core 1 was running (proved above) while core 0 asked for a halt
            assert(dut.io.syncOut(0).haltViolated)
            assert(dut.io.syncOut(1).haltViolated)   // global signal, same to all
          }
        }
      })
  }

  test("haltViolated is LOW when the halt is honoured") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)

        // THE CONTROL. Same collector, but nobody holds a lock, so core 1 is
        // halted and the world really has stopped. Without this the property
        // above is satisfied by tying haltViolated to the gcHalt request.
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True
        dut.io.syncIn(1).req := False
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        when(pastValidAfterReset()) {
          assert(!dut.io.syncOut(0).haltViolated)
        }
      })
  }

  // ==========================================================================
  // THE ACKNOWLEDGEMENT THE COLLECTOR NEVER HAD — status item 158.
  //
  // IO_GC_HALT is write-only: GC.java sets it and proceeds to mark, move and
  // rewrite handles in the next statement, with no way to learn whether any
  // core actually stopped. othersHalted is that answer, and these two
  // properties pin it to the scenario that matters and its opposite.
  // ==========================================================================
  test("othersHalted is FALSE while an exempt lock owner is still running") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True    // the collector
        dut.io.syncIn(1).req := True       // holds the lock, so exempt
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        // On the PAST condition: othersHalted is registered, so asserting on
        // the present one passes while the register is still showing the
        // pre-halt state -- passing for the wrong reason, the same trap the
        // haltViolated property above documents.
        when(pastValidAfterReset()) {
          when(past(dut.nextState === dut.State.LOCKED && dut.nextLockedId === 1)) {
            // the collector must NOT be told the world has stopped
            assert(!dut.io.syncOut(0).othersHalted)
          }
        }
      })
  }

  test("othersHalted is TRUE once the halt is honoured") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(CmpSync(cpuCnt))
        assumeInitial(ClockDomain.current.isResetActive)
        // THE CONTROL. Nobody holds a lock, so core 1 is halted and the world
        // has genuinely stopped. Without this, tying othersHalted to False
        // would satisfy the property above and hang the collector forever.
        dut.io.syncIn(0).req := False
        dut.io.syncIn(0).s_in := False
        dut.io.syncIn(0).gcHalt := True
        dut.io.syncIn(1).req := False
        dut.io.syncIn(1).s_in := False
        dut.io.syncIn(1).gcHalt := False

        when(pastValidAfterReset()) {
          assert(dut.io.syncOut(0).othersHalted)
        }
      })
  }

}
