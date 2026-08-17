package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib.bus.bmb._

import jop.ddr3.BmbCacheBridge

/**
 * Formal verification for the BmbCacheBridge component.
 *
 * Source: jop/ddr3/BmbCacheBridge.scala
 *
 * The bridge is checked at `outstanding = 4`, because that is the configuration
 * where things can go wrong: slots are allocated and freed independently and
 * responses may come back in any order. One test pins the legacy
 * `outstanding = 1` behaviour so the single-outstanding path stays honest.
 *
 * Properties verified:
 * - a full slot table blocks new single-beat commands (never more than N in flight)
 * - a slot is only ever freed by a response carrying its own id
 * - a burst never overlaps single-beat work
 * - burst wordsDone never exceeds wordsTotal
 * - unsupported command returns ERROR opcode
 * - responsive cache drains the slots (no deadlock)
 * - write response has zero data
 * - at outstanding = 1, one in-flight command blocks the next
 */
class BmbCacheBridgeFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  val testBmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = 10,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 6,
      canRead = true,
      canWrite = true
    )),
    invalidation = BmbInvalidationParameter()
  )

  val cacheAddrWidth = 10
  val cacheDataWidth = 128 // 4 lanes of 32-bit
  val outstanding = 4

  def dutOf(n: Int = outstanding) = new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth, n)

  def setupDut(dut: BmbCacheBridge): Unit = {
    // BMB cmd inputs
    anyseq(dut.io.bmb.cmd.valid)
    anyseq(dut.io.bmb.cmd.payload.fragment.address)
    anyseq(dut.io.bmb.cmd.payload.fragment.opcode)
    anyseq(dut.io.bmb.cmd.payload.fragment.data)
    anyseq(dut.io.bmb.cmd.payload.fragment.mask)
    anyseq(dut.io.bmb.cmd.payload.fragment.length)
    anyseq(dut.io.bmb.cmd.payload.fragment.source)
    anyseq(dut.io.bmb.cmd.payload.fragment.context)
    anyseq(dut.io.bmb.cmd.payload.last)

    // Cache interface
    anyseq(dut.io.cache.req.ready)
    anyseq(dut.io.cache.rsp.valid)
    anyseq(dut.io.cache.rsp.payload.data)
    anyseq(dut.io.cache.rsp.payload.error)
    if (dut.io.cache.rsp.payload.id != null) anyseq(dut.io.cache.rsp.payload.id)

    // Response consumer
    anyseq(dut.io.bmb.rsp.ready)
  }

  /**
   * A real cache only ever answers a request it was given, so it never returns
   * an id whose slot is free. Without this the solver is free to invent stray
   * responses, which no amount of bridge logic can be expected to survive.
   */
  def assumeWellFormedCache(dut: BmbCacheBridge): Unit = {
    when(dut.io.cache.rsp.valid) {
      assume(dut.retSlotBusy)
    }
  }

  test("full slot table blocks new single-beat commands") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // Every slot busy: a supported single-beat command must not be taken,
          // or the bridge would lose track of an in-flight request.
          when(!dut.hasFreeSlot && !dut.burstActive &&
               dut.io.bmb.cmd.valid && dut.cmdSupported) {
            assert(!dut.io.bmb.cmd.ready)
          }
        }
      })
  }

  test("a slot is only freed by a response carrying its id") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedCache(dut)

        when(pastValidAfterReset()) {
          for (i <- 0 until outstanding) {
            when(past(dut.slotBusy(i)) && !dut.slotBusy(i)) {
              assert(past(dut.returnFire && dut.retSlotOh(i)))
            }
          }
        }
      })
  }

  test("a burst never overlaps single-beat work") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedCache(dut)

        when(pastValidAfterReset()) {
          // The burst path matches cache responses to beats by counting, so a
          // single-beat response arriving mid-burst would be consumed as a beat.
          assert(!(dut.burstActive && dut.anyOutstanding))
        }
      })
  }

  test("burst wordsDone never exceeds wordsTotal") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.burstActive) {
            assert(dut.burstWordsDone <= dut.burstWordsTotal)
          }
        }
      })
  }

  test("unsupported command returns ERROR opcode") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Ensure rspFifo can accept pushes
        assume(dut.rspFifo.io.push.ready)
        // No burst active, and no real response competing for the push port
        assume(!dut.burstActive)
        assume(!dut.io.cache.rsp.valid)

        when(pastValidAfterReset()) {
          // When a valid unsupported command is presented and accepted
          when(dut.io.bmb.cmd.valid && !dut.cmdSupported && !dut.cmdIsBurstRead) {
            // cmd is accepted
            assert(dut.io.bmb.cmd.ready)
            // Response pushed with ERROR opcode
            assert(dut.rspFifo.io.push.valid)
            assert(dut.rspFifo.io.push.payload.fragment.opcode === Bmb.Rsp.Opcode.ERROR)
          }
        }
      })
  }

  test("responsive cache drains the slots") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedCache(dut)

        // Assume responsive cache and consumer
        assume(dut.io.cache.req.ready)
        assume(dut.io.cache.rsp.valid)
        assume(dut.io.bmb.rsp.ready)

        // Stuck counter: count consecutive cycles with work outstanding. Each
        // cycle a response is available frees one slot, so N slots need N cycles.
        val stuckCounter = Reg(UInt(4 bits)) init (0)
        when(dut.anyOutstanding) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          assert(stuckCounter <= outstanding + 2)
        }
      })
  }

  test("write response has zero data") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedCache(dut)

        when(pastValidAfterReset()) {
          when(dut.returnFire && dut.retIsWrite) {
            assert(dut.rspFifo.io.push.payload.fragment.data === 0)
          }
        }
      })
  }

  test("single outstanding blocks the next command") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(dutOf(1))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // The legacy contract: one command in the cache, then wait for it.
          when(dut.anyOutstanding && !dut.burstActive) {
            assert(!dut.io.bmb.cmd.ready || !dut.cmdSupported)
          }
        }
      })
  }
}
