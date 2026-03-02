package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib.bus.bmb._

import jop.memory.StackCacheDma
import jop.pipeline.StackCacheConfig

/**
 * Formal verification for the StackCacheDma component.
 *
 * Source: jop/memory/StackCacheDma.scala
 *
 * Properties verified:
 * - busy reflects non-IDLE state
 * - done pulse only in DONE state
 * - wordsDone never exceeds totalWords
 * - spill issues WRITE opcode
 * - fill issues READ opcode
 * - responsive slave completes transfer (no deadlock)
 */
class StackCacheDmaFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  // Small config for tractability: single-word mode, small bank
  val testCacheConfig = StackCacheConfig(
    burstLen = 0,
    bankSize = 8,
    spillBaseAddr = 0,
    wordAddrWidth = 8
  )

  val testBmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = 10,
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 0,
      lengthWidth = 2,
      canRead = true,
      canWrite = true
    )),
    invalidation = BmbInvalidationParameter()
  )

  def setupDut(dut: StackCacheDma): Unit = {
    anyseq(dut.io.start)
    anyseq(dut.io.isSpill)
    anyseq(dut.io.extAddr)
    anyseq(dut.io.wordCount)
    anyseq(dut.io.bank)
    anyseq(dut.io.bankRdData)

    // BMB slave responses
    anyseq(dut.io.bmb.cmd.ready)
    anyseq(dut.io.bmb.rsp.valid)
    anyseq(dut.io.bmb.rsp.fragment.data)
    dut.io.bmb.rsp.last := True
    dut.io.bmb.rsp.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
    dut.io.bmb.rsp.fragment.source := 0
    dut.io.bmb.rsp.fragment.context := 0
  }

  test("busy reflects non-IDLE state") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.busy === (dut.state =/= dut.State.IDLE))
        }
      })
  }

  test("done pulse only in DONE state") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.done) {
            assert(dut.state === dut.State.DONE)
          }
        }
      })
  }

  test("wordsDone never exceeds totalWords") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Constrain wordCount on start to small positive values
        when(dut.io.start && dut.state === dut.State.IDLE) {
          assume(dut.io.wordCount > 0)
          assume(dut.io.wordCount <= 8)
        }
        // Don't start when not idle
        when(dut.state =/= dut.State.IDLE) {
          assume(!dut.io.start)
        }

        when(pastValidAfterReset()) {
          when(dut.state =/= dut.State.IDLE) {
            assert(dut.wordsDone <= dut.totalWords)
          }
        }
      })
  }

  test("spill issues WRITE opcode") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === dut.State.SPILL_CMD && dut.io.bmb.cmd.valid) {
            assert(dut.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE)
          }
        }
      })
  }

  test("fill issues READ opcode") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.state === dut.State.FILL_CMD && dut.io.bmb.cmd.valid) {
            assert(dut.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.READ)
          }
        }
      })
  }

  test("responsive slave completes transfer (no deadlock)") {
    formalConfig
      .withBMC(15)
      .doVerify(new Component {
        val dut = FormalDut(StackCacheDma(testCacheConfig, testBmbParam))
        assumeInitial(ClockDomain.current.isResetActive)

        // Manually set up inputs (responsive slave)
        anyseq(dut.io.start)
        anyseq(dut.io.isSpill)
        anyseq(dut.io.extAddr)
        anyseq(dut.io.wordCount)
        anyseq(dut.io.bank)
        anyseq(dut.io.bankRdData)

        // Responsive slave: always ready, always responds
        dut.io.bmb.cmd.ready := True
        anyseq(dut.io.bmb.rsp.fragment.data)
        dut.io.bmb.rsp.valid := True
        dut.io.bmb.rsp.last := True
        dut.io.bmb.rsp.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
        dut.io.bmb.rsp.fragment.source := 0
        dut.io.bmb.rsp.fragment.context := 0

        // Constrain: small wordCount, only start from IDLE
        when(dut.io.start && dut.state === dut.State.IDLE) {
          assume(dut.io.wordCount > 0)
          assume(dut.io.wordCount <= 4)
        }
        when(dut.state =/= dut.State.IDLE) {
          assume(!dut.io.start)
        }

        // Stuck counter: counts cycles spent outside IDLE
        val stuckCounter = Reg(UInt(5 bits)) init(0)
        when(dut.state =/= dut.State.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          // With responsive slave and max 4 words, should complete within
          // 4 * 3 cycles (SPILL_READ+SPILL_CMD+SPILL_WAIT per word) + 1 (DONE) = 13
          assert(stuckCounter < 14)
        }
      })
  }
}
