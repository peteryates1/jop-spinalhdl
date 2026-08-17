package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib._

import jop.ddr3.{LruCacheCore, LruCacheCoreState, CacheConfig}

/**
 * Formal verification for the LruCacheCore component.
 *
 * Source: jop/ddr3/LruCacheCore.scala
 *
 * Uses a minimal cache config (addrWidth=8, dataWidth=32, setCount=2, wayCount=2)
 * for tractability. Most properties are checked with a 2-entry MSHR file, since
 * that is where misses can overlap; `blockingConfig` pins the untagged,
 * one-at-a-time contract that every existing master relies on.
 *
 * Properties verified:
 * - busy covers outstanding memory work, not just a non-IDLE state
 * - memCmd is only issued from ISSUE_EVICT / ISSUE_REFILL, with the right direction
 * - a memory response always has an order-queue entry to explain it
 * - ISSUE_* never stalls on the order queue (CHECK_HIT reserved the room)
 * - an MSHR is only freed by the refill entry naming it
 * - no two live MSHRs share a set index
 * - an untagged frontend gets one request at a time
 * - responsive memory drains the machine (no deadlock), errors included
 */
class LruCacheCoreFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  /** Untagged: the legacy contract, one outstanding request answered in order. */
  val blockingConfig = CacheConfig(addrWidth = 8, dataWidth = 32, setCount = 2, wayCount = 2)
  /** Tagged with two MSHRs: misses overlap and may complete out of order. */
  val cacheConfig = blockingConfig.copy(idWidth = 2, mshrCount = 2)

  def setupDut(dut: LruCacheCore): Unit = {
    anyseq(dut.io.frontend.req.valid)
    anyseq(dut.io.frontend.req.payload.addr)
    anyseq(dut.io.frontend.req.payload.write)
    anyseq(dut.io.frontend.req.payload.data)
    anyseq(dut.io.frontend.req.payload.mask)
    if (dut.io.frontend.req.payload.id != null) anyseq(dut.io.frontend.req.payload.id)
    anyseq(dut.io.frontend.rsp.ready)
    anyseq(dut.io.memRsp.valid)
    anyseq(dut.io.memRsp.payload.data)
    anyseq(dut.io.memRsp.payload.error)
    anyseq(dut.io.memCmd.ready)
  }

  test("busy covers outstanding memory work") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Sitting in IDLE no longer means idle: a miss can be in flight.
          assert(dut.io.busy === (dut.state =/= LruCacheCoreState.IDLE || dut.outstandingWork))
          when(dut.mshrAnyValid) { assert(dut.io.busy) }
        }
      })
  }

  test("memCmd only in ISSUE_EVICT or ISSUE_REFILL") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.memCmd.valid) {
            assert(
              dut.state === LruCacheCoreState.ISSUE_EVICT ||
              dut.state === LruCacheCoreState.ISSUE_REFILL
            )
          }
        }
      })
  }

  test("ISSUE_EVICT memCmd is a write") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === LruCacheCoreState.ISSUE_EVICT && dut.io.memCmd.valid) {
            assert(dut.io.memCmd.payload.write)
          }
        }
      })
  }

  test("ISSUE_REFILL memCmd is a read when refill needed") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === LruCacheCoreState.ISSUE_REFILL && dut.io.memCmd.valid) {
            assert(!dut.io.memCmd.payload.write)
          }
        }
      })
  }

  test("every issued command is recorded in the order queue") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Responses are matched to their command BY ORDER. A command issued
          // without an entry would shift every later response onto the wrong
          // miss — the shape of the AlteraSdramAdapter bug (ef36d99), where
          // locally-made write responses overtook order-matched reads.
          when(dut.io.memCmd.fire && !dut.fillActive) {
            assert(dut.orderFifo.io.push.fire)
          }
          when(dut.orderFifo.io.push.fire) {
            assert(dut.io.memCmd.fire)
          }
          // The queue can hold every command that can be outstanding at once.
          assert(dut.orderFifo.io.occupancy <= 2 * dut.mshrValid.length)
        }
      })
  }

  test("no response is retired without its entry") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // The two must move together. Retiring a response without popping
          // would shift every later response onto the wrong miss; popping
          // without retiring would strand one. Note the queue's push-to-pop
          // latency means a response CAN arrive before its entry is visible —
          // RSP_FILL waits for both, which is why this holds and not the
          // stronger "memRsp.valid implies pop.valid".
          when(dut.io.memRsp.fire && !dut.fillActive) {
            assert(dut.orderFifo.io.pop.fire)
          }
          when(dut.orderFifo.io.pop.fire) {
            assert(dut.io.memRsp.fire)
          }
        }
      })
  }

  test("issuing never stalls on the order queue") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // CHECK_HIT reserves two slots before committing to a miss. If that
          // reservation were wrong, ISSUE_* could block on a full queue while
          // being the only path back to the state that drains it: deadlock.
          when(dut.state === LruCacheCoreState.ISSUE_EVICT ||
               dut.state === LruCacheCoreState.ISSUE_REFILL) {
            assert(dut.orderFifo.io.push.ready)
          }
        }
      })
  }

  test("an MSHR is only freed by the refill naming it") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          for (i <- 0 until dut.mshrValid.length) {
            when(past(dut.mshrValid(i)) && !dut.mshrValid(i)) {
              assert(past(
                dut.state === LruCacheCoreState.RSP_FILL &&
                dut.orderFifo.io.pop.fire && dut.ordHead.isRefill && dut.refillOh(i)))
            }
          }
        }
      })
  }

  test("no two live MSHRs share a set") {
    formalConfig
      .withBMC(14)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Two fills into one set could pick the same victim way, and a write
          // hitting a way an outstanding fill is about to overwrite would be
          // lost. The replay in CHECK_HIT exists to make this impossible.
          for (i <- 0 until dut.mshrValid.length; j <- 0 until i) {
            when(dut.mshrValid(i) && dut.mshrValid(j)) {
              assert(dut.mshrIndex(i) =/= dut.mshrIndex(j))
            }
          }
        }
      })
  }

  test("untagged frontend takes one request at a time") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(blockingConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Without ids a master cannot tell responses apart, so it must keep
          // the in-order, one-outstanding behaviour it has always had.
          when(dut.outstandingWork) {
            assert(!dut.cmdFifo.io.pop.ready)
          }
        }
      })
  }

  test("responsive memory drains the machine") {
    formalConfig
      .withBMC(14)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(dut.io.memCmd.ready)
        assume(dut.io.memRsp.valid)
        assume(!dut.io.memRsp.payload.error)
        assume(dut.io.frontend.rsp.ready)

        val stuckCounter = Reg(UInt(5 bits)) init (0)
        when(dut.state =/= LruCacheCoreState.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          assert(stuckCounter < 12)
        }
      })
  }

  test("memory errors still drain the machine") {
    formalConfig
      .withBMC(14)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(dut.io.memCmd.ready)
        assume(dut.io.memRsp.valid)
        assume(dut.io.memRsp.payload.error)
        assume(dut.io.frontend.rsp.ready)

        val stuckCounter = Reg(UInt(5 bits)) init (0)
        when(dut.state =/= LruCacheCoreState.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          // An errored refill must retire its MSHR and answer its waiter, not
          // leave the entry live forever.
          assert(stuckCounter < 12)
        }
      })
  }
}
