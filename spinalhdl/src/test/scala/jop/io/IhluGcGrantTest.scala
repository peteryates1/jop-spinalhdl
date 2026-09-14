package jop.io

import spinal.core._
import spinal.core.sim._
import jop.utils.JopSimDefaults

/**
 * Unit sim for IHLU's lock-granting rule during a stop-the-world — item 158.
 *
 * DISCIPLINE: docs/testing-discipline.md. Every case has its control, because
 * the rule being added can fail in BOTH directions and the two look nothing
 * alike from outside:
 *
 *   granting too much  -> the halt is not honoured; the collector compacts the
 *                         heap under a running core. Silent, and item 157 had
 *                         to be fixed first before it was even visible.
 *   granting too little -> a core already inside a critical section blocks on a
 *                         nested `synchronized` that can never be granted, the
 *                         collector spins waiting for it to halt, and the
 *                         cluster wedges. Loud, but a DEADLOCK, and the global
 *                         lock's non-reentrancy already broke the SMP GC for
 *                         days once (item 1) as corruption rather than a hang.
 *
 * So case 3 (an owner may still nest) is not decoration: it is the half that
 * says the rule did not deadlock the machine.
 */
object IhluGcGrantTest extends App {
  val cfg = IhluConfig(cpuCnt = 4, lockSlots = 8, reentrantBits = 4)

  JopSimDefaults.config.compile(Ihlu(cfg)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    var fails = 0
    def check(cond: Boolean, msg: String): Unit =
      if (!cond) { println(s"FAIL: $msg"); fails += 1 }

    for (i <- 0 until cfg.cpuCnt) {
      dut.io.syncIn(i).reqPulse #= false
      dut.io.syncIn(i).req     #= false
      dut.io.syncIn(i).s_in    #= false
      dut.io.syncIn(i).gcHalt  #= false
      dut.io.syncIn(i).data    #= 0
      dut.io.syncIn(i).op      #= false
    }
    dut.clockDomain.waitSampling(4)

    /** One lock/unlock request; IHLU services one core per 4-cycle window. */
    def request(core: Int, key: Long, unlock: Boolean): Unit = {
      dut.io.syncIn(core).data     #= key
      dut.io.syncIn(core).op       #= unlock
      dut.io.syncIn(core).reqPulse #= true
      dut.clockDomain.waitSampling()
      dut.io.syncIn(core).reqPulse #= false
    }

    /** Settle long enough for the round-robin to reach every core. */
    def settle(): Unit = dut.clockDomain.waitSampling(cfg.cpuCnt * 8)

    def halted(core: Int): Boolean = dut.io.syncOut(core).halted.toBoolean
    def owns(core: Int): Boolean =
      (0 until cfg.lockSlots).exists(s =>
        dut.valid(s).toBoolean && dut.owner(s).toInt == core)

    // ---- set the scene: cores 1 and 2 each own a lock -------------------
    request(1, 0x1111, unlock = false); settle()
    request(2, 0x2222, unlock = false); settle()
    check(owns(1), "setup: core 1 should own a lock")
    check(owns(2), "setup: core 2 should own a lock")
    check(!owns(3), "setup: core 3 should own nothing")

    // ---- core 0 asks the world to stop ----------------------------------
    dut.io.syncIn(0).gcHalt #= true
    settle()

    // 1) A CORE OWNING NOTHING MUST NOT BE GRANTED A NEW LOCK.
    //    This is the whole rule. Without it core 3 takes a free slot, becomes
    //    an owner, and is exempt from the halt it just walked into -- so the
    //    halted set is not monotone and the collector can never conclude the
    //    world has stopped.
    request(3, 0x3333, unlock = false); settle()
    check(!owns(3),
      "core 3 owned NOTHING and was granted a new lock during a gcHalt; " +
      "it is now exempt from the halt it walked into. Status item 158.")
    check(halted(3),
      "core 3's blocked request must leave it HALTED (hasPending -> lockWait), " +
      "or it is spinning inside the stop-the-world instead of stopped")

    // 2) THE CONTROL for case 1: a core that already owns a lock MAY take
    //    another. A nested `synchronized` inside a critical section needs a
    //    NEW slot, and refusing it blocks an owner that the collector is
    //    waiting to drain -- the collector spins forever and the cluster
    //    wedges. This is the deadlock the rule must not introduce.
    request(2, 0x2ABC, unlock = false); settle()
    check(owns(2), "core 2 still owns its first lock")
    val nested = (0 until cfg.lockSlots).exists(s =>
      dut.valid(s).toBoolean && dut.owner(s).toInt == 2 &&
      dut.entry(s).toLong == 0x2ABCL)
    check(nested,
      "DEADLOCK: core 2 was already an owner and was refused a NESTED lock " +
      "during a gcHalt. It can never reach its monitorexit, and the collector " +
      "waits for it forever. Status item 158.")

    // 3) An owner must still be able to UNLOCK and drain.
    request(1, 0x1111, unlock = true); settle()
    check(!owns(1),
      "core 1 could not release its lock during a gcHalt; draining is the " +
      "entire reason the exemption exists")
    check(halted(1), "core 1 owns nothing now, so the halt must apply to it")

    // 4) THE CONTROL for the whole rule: with no gcHalt in force, a core
    //    owning nothing is granted normally. Otherwise case 1 would pass just
    //    as well with locking broken outright.
    dut.io.syncIn(0).gcHalt #= false
    settle()
    request(3, 0x3333, unlock = false); settle()
    check(owns(3),
      "CONTROL: with no halt in force, a core owning nothing was still " +
      "refused a lock -- the rule is firing unconditionally")

    println(if (fails == 0) "PASS: IHLU grants drain but do not admit during a stop-the-world"
            else s"FAILED ($fails)")
    if (fails != 0) simFailure(s"$fails checks failed")
  }
}
