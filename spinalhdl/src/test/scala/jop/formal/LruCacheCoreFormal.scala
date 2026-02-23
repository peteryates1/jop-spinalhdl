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
 * for tractability.
 *
 * Properties verified:
 * - Initial state: IDLE, not busy
 * - State machine returns to IDLE given responsive memory
 * - No memCmd without valid state
 * - WAIT_EVICT_RSP accepts successful evictions unconditionally
 */
class LruCacheCoreFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(180)

  val cacheConfig = CacheConfig(addrWidth = 8, dataWidth = 32, setCount = 2, wayCount = 2)

  def setupDut(dut: LruCacheCore): Unit = {
    anyseq(dut.io.frontend.req.valid)
    anyseq(dut.io.frontend.req.payload.addr)
    anyseq(dut.io.frontend.req.payload.write)
    anyseq(dut.io.frontend.req.payload.data)
    anyseq(dut.io.frontend.req.payload.mask)
    anyseq(dut.io.frontend.rsp.ready)
    anyseq(dut.io.memRsp.valid)
    anyseq(dut.io.memRsp.payload.data)
    anyseq(dut.io.memRsp.payload.error)
    anyseq(dut.io.memCmd.ready)
  }

  test("initial state after reset") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.state === LruCacheCoreState.IDLE)
          assert(!dut.io.busy)
        }
      })
  }

  test("busy reflects non-IDLE state") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.busy === (dut.state =/= LruCacheCoreState.IDLE))
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

  test("exception returns to IDLE from WAIT_EVICT_RSP") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(dut.io.memRsp.payload.error)
        assume(dut.io.memCmd.ready)
        assume(dut.io.frontend.rsp.ready)

        when(pastValidAfterReset()) {
          when(past(dut.state === LruCacheCoreState.WAIT_EVICT_RSP) &&
               past(dut.io.memRsp.valid) && past(dut.io.memRsp.payload.error) &&
               past(dut.rspFifo.io.push.ready)) {
            assert(dut.state === LruCacheCoreState.IDLE)
          }
        }
      })
  }

  test("exception returns to IDLE from WAIT_REFILL_RSP") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(dut.io.memRsp.payload.error)
        assume(dut.io.memCmd.ready)
        assume(dut.io.frontend.rsp.ready)

        when(pastValidAfterReset()) {
          when(past(dut.state === LruCacheCoreState.WAIT_REFILL_RSP) &&
               past(dut.io.memRsp.valid) && past(dut.io.memRsp.payload.error) &&
               past(dut.rspFifo.io.push.ready)) {
            assert(dut.state === LruCacheCoreState.IDLE)
          }
        }
      })
  }

  test("responsive memory returns to IDLE") {
    formalConfig
      .withBMC(20)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(dut.io.memCmd.ready)
        assume(dut.io.memRsp.valid)
        assume(!dut.io.memRsp.payload.error)
        assume(dut.io.frontend.rsp.ready)

        val stuckCounter = Reg(UInt(4 bits)) init(0)
        when(dut.state =/= LruCacheCoreState.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          assert(stuckCounter < 10)
        }
      })
  }

  test("WAIT_EVICT_RSP accepts successful eviction unconditionally") {
    formalConfig
      .withBMC(20)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === LruCacheCoreState.WAIT_EVICT_RSP &&
               dut.io.memRsp.valid &&
               !dut.io.memRsp.payload.error) {
            assert(dut.io.memRsp.ready)
          }
        }
      })
  }
}
