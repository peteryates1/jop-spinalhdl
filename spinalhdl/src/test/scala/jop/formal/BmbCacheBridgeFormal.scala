package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib.bus.bmb._

import jop.ddr3.BmbCacheBridge

/**
 * Formal verification for the BmbCacheBridge component.
 *
 * Source: jop/ddr3/BmbCacheBridge.scala (240 lines)
 *
 * Properties verified:
 * - pendingRsp blocks new single-beat commands
 * - burst wordsDone never exceeds wordsTotal
 * - unsupported command returns ERROR opcode
 * - responsive cache completes single-beat (no deadlock)
 * - write response has zero data
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

    // Response consumer
    anyseq(dut.io.bmb.rsp.ready)
  }

  test("pendingRsp blocks new single-beat commands") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // When a single-beat response is pending and no burst is active,
          // the bridge should not accept new commands (cmd.ready = False)
          when(dut.pendingRsp && !dut.burstActive) {
            assert(!dut.io.bmb.cmd.ready)
          }
        }
      })
  }

  test("burst wordsDone never exceeds wordsTotal") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth))
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
        val dut = FormalDut(new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Ensure rspFifo can accept pushes
        assume(dut.rspFifo.io.push.ready)
        // No burst active and no pending response
        assume(!dut.burstActive)
        assume(!dut.pendingRsp)

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

  test("responsive cache completes single-beat") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume responsive cache and consumer
        assume(dut.io.cache.req.ready)
        assume(dut.io.cache.rsp.valid)
        assume(dut.io.bmb.rsp.ready)

        // Stuck counter: count cycles where pendingRsp is True
        val stuckCounter = Reg(UInt(4 bits)) init(0)
        when(dut.pendingRsp) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          // With responsive cache and consumer, pendingRsp should clear quickly
          assert(stuckCounter < 4)
        }
      })
  }

  test("write response has zero data") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(new BmbCacheBridge(testBmbParam, cacheAddrWidth, cacheDataWidth))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // When completing a write (pendingIsWrite) and pushing response
          when(dut.pendingRsp && dut.pendingIsWrite &&
               dut.io.cache.rsp.valid && dut.rspFifo.io.push.ready) {
            assert(dut.rspFifo.io.push.payload.fragment.data === 0)
          }
        }
      })
  }
}
