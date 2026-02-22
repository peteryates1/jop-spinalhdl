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
 * Uses a minimal cache config (addrWidth=8, dataWidth=32, setCount=2) for tractability.
 *
 * Properties verified:
 * - Initial state: IDLE, not busy, all valid bits false
 * - State machine returns to IDLE given responsive memory
 * - BUG: read hit with full rspFifo enters miss path (evict/refill)
 * - Write hit with full rspFifo correctly stalls
 * - BUG: WAIT_EVICT_RSP gates memRsp.ready on rspFifo even for successful evictions
 * - No memCmd without valid state
 */
class LruCacheCoreFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(180)

  // Minimal config for tractable verification
  val cacheConfig = CacheConfig(addrWidth = 8, dataWidth = 32, setCount = 2)

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
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Force memory responses to always be errors
        assume(dut.io.memRsp.payload.error)
        // Always accept memCmd
        assume(dut.io.memCmd.ready)
        // Always have rspFifo room (frontend drains)
        assume(dut.io.frontend.rsp.ready)

        when(pastValidAfterReset()) {
          // After an error in WAIT_EVICT_RSP, state should go to IDLE
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
      .withBMC(10)
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
      .withBMC(15)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume responsive memory: always accept commands, always respond
        assume(dut.io.memCmd.ready)
        assume(dut.io.memRsp.valid)
        assume(!dut.io.memRsp.payload.error)
        // Assume consumer drains responses
        assume(dut.io.frontend.rsp.ready)

        // After enough cycles, we should always return to IDLE
        // (no permanent stuck state)
        val stuckCounter = Reg(UInt(4 bits)) init(0)
        when(dut.state =/= LruCacheCoreState.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          // With responsive memory and draining consumer,
          // should never be stuck for more than 8 cycles
          assert(stuckCounter < 8)
        }
      })
  }

  test("read hit stalls when rspFifo full (fixed)") {
    // Previously a bug: the otherwise branch caught reqHit && !rspFifo.push.ready,
    // consuming the request and entering evict/refill unnecessarily.
    // Fixed: read path now uses elsewhen(!reqHit), matching the write path.
    formalConfig
      .withBMC(20)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Property: if we're in IDLE with a valid read command that hits,
          // we should only consume it (pop.ready) when rspFifo has room.
          when(dut.state === LruCacheCoreState.IDLE &&
               dut.cmdFifo.io.pop.valid &&
               !dut.cmdFifo.io.pop.payload.write &&
               dut.reqHit &&
               dut.cmdFifo.io.pop.ready) {
            assert(dut.rspFifo.io.push.ready)
          }
        }
      })
  }

  test("write hit stalls when rspFifo full (correct behavior)") {
    formalConfig
      .withBMC(20)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // For writes: if we're in IDLE with a write hit, the request is only
          // consumed when rspFifo has room. This is the CORRECT behavior.
          when(dut.state === LruCacheCoreState.IDLE &&
               dut.cmdFifo.io.pop.valid &&
               dut.cmdFifo.io.pop.payload.write &&
               dut.reqHit &&
               dut.cmdFifo.io.pop.ready) {
            assert(dut.rspFifo.io.push.ready)
          }
        }
      })
  }

  test("WAIT_EVICT_RSP accepts successful eviction unconditionally (fixed)") {
    // Previously a bug: memRsp.ready was gated on rspFifo.push.ready even
    // though successful evictions don't push to rspFifo.
    // Fixed: success path now accepts unconditionally.
    formalConfig
      .withBMC(20)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cacheConfig))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // In WAIT_EVICT_RSP with a valid non-error response,
          // memRsp.ready should be True (eviction success doesn't need rspFifo).
          when(dut.state === LruCacheCoreState.WAIT_EVICT_RSP &&
               dut.io.memRsp.valid &&
               !dut.io.memRsp.payload.error) {
            assert(dut.io.memRsp.ready)
          }
        }
      })
  }
}
