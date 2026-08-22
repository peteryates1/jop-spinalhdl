package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib._

import jop.ddr3.{LruCacheCore, LruCacheCoreState, CacheConfig}

/**
 * Formal properties for LruCacheCore's post-reset INIT state.
 *
 * WHY THIS EXISTS SEPARATELY. The valid bits moved from fabric registers into a
 * Mem (2026-08-22), and a Mem does not reset. Stale valid bits would make the
 * cache report hits on uninitialised tags -- returning garbage for reads that
 * never missed -- so INIT walks every set writing zero before IDLE is entered
 * for the first time.
 *
 * None of the twelve properties in LruCacheCoreFormal constrain that. They
 * cover protocol behaviour: which states may issue memCmd, that every issued
 * command is recorded in the order queue, that responses are not retired
 * without their entry. An off-by-one in the clear loop -- leaving one set
 * uninitialised, or leaving INIT a cycle early -- would pass all of them.
 *
 * The gap was not hypothetical. INIT's most consequential side effect was found
 * by accident: it holds the cache quiescent long enough to swallow a stale MIG
 * beat after an early reset release, which broke CacheMigResetSim's NEGATIVE
 * test ("released early corrupts"). That test exists to prove
 * ResetGenerator.DramResetCycles is load-bearing, and it started passing
 * because the cache had quietly acquired a second line of defence. A behaviour
 * nobody had specified was discovered by breaking a test that asserts the
 * opposite -- which is a good argument for stating the behaviour explicitly.
 */
class LruCacheCoreInitFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  /** Small but not degenerate: 4 sets means INIT is 4 cycles, so BMC can reach
    * past the end of it and see the transition to IDLE. */
  val cfg = CacheConfig(addrWidth = 8, dataWidth = 32, setCount = 4, wayCount = 2,
                        idWidth = 2, mshrCount = 2)

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

  test("INIT serves nothing and reports busy") {
    // The frontend must not be able to observe an uninitialised cache. Whatever
    // else INIT does, it must not answer.
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cfg))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === LruCacheCoreState.INIT) {
            assert(dut.io.busy)                     // callers must see it as busy
            assert(!dut.io.frontend.rsp.valid)      // and get no answer
            assert(!dut.io.memCmd.valid)            // and it must not touch memory
          }
        }
      })
  }

  test("INIT clears a set every cycle") {
    // The write port must actually be driving zeros throughout -- a loop that
    // advances its counter without writing would still reach IDLE on time.
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cfg))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === LruCacheCoreState.INIT) {
            assert(dut.validWriteEnable)
            assert(dut.validWriteData === B(0, cfg.wayCount bits))
          }
        }
      })
  }

  test("INIT is left only after the last set, and is never re-entered") {
    // Two failures this catches: leaving early (some set never cleared) and
    // leaving late or looping (the cache never becomes available).
    formalConfig
      .withBMC(14)
      .doVerify(new Component {
        val dut = FormalDut(new LruCacheCore(cfg))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // On the cycle after leaving INIT, the counter must have been at the
          // final set -- so every index 0..setCount-1 was written.
          when(past(dut.state === LruCacheCoreState.INIT) &&
               dut.state =/= LruCacheCoreState.INIT) {
            assert(past(dut.initIndex) === U(cfg.setCount - 1, dut.initIndex.getWidth bits))
          }
          // INIT is a power-on state only. Re-entering it mid-operation would
          // silently invalidate the whole cache.
          when(!past(dut.state === LruCacheCoreState.INIT)) {
            assert(dut.state =/= LruCacheCoreState.INIT)
          }
        }
      })
  }
}
