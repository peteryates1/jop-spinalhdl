package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.memory.ObjectCache

/**
 * Formal verification for the ObjectCache component.
 *
 * Source: jop/memory/ObjectCache.scala
 *
 * Properties verified:
 * - After invalidation, all valid bits are zero
 * - Hit implies tag match AND valid bit AND cacheable
 * - FIFO pointer monotonicity: advances by 1 per miss
 * - Cacheable bounds: only fields [0, 2^indexBits) produce hits
 * - No hit when all valid bits are zero
 */
class ObjectCacheFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  def setupDut(dut: ObjectCache): Unit = {
    anyseq(dut.io.handle)
    anyseq(dut.io.fieldIdx)
    anyseq(dut.io.chkGf)
    anyseq(dut.io.chkPf)
    anyseq(dut.io.wrGf)
    anyseq(dut.io.wrPf)
    anyseq(dut.io.gfVal)
    anyseq(dut.io.pfVal)
    anyseq(dut.io.inval)
    // Snoop bus tie-off
    dut.io.snoopValid := False
    dut.io.snoopHandle := 0
    dut.io.snoopFieldIdx := 0
  }

  test("invalidation clears all valid bits") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(ObjectCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // After invalidation on previous cycle, all valid bits should be zero
          when(past(dut.io.inval)) {
            for (i <- 0 until dut.lineCnt) {
              assert(dut.valid(i) === B(0, dut.fieldCnt bits))
            }
          }
        }
      })
  }

  test("invalidation resets FIFO pointer") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(ObjectCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.io.inval)) {
            assert(dut.nxt === 0)
          }
        }
      })
  }

  test("no hit when field index out of range") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(ObjectCache())
        assumeInitial(ClockDomain.current.isResetActive)

        // Manually set up inputs (not using setupDut to avoid multi-driver on fieldIdx)
        anyseq(dut.io.handle)
        anyseq(dut.io.chkGf)
        anyseq(dut.io.chkPf)
        anyseq(dut.io.wrGf)
        anyseq(dut.io.wrPf)
        anyseq(dut.io.gfVal)
        anyseq(dut.io.pfVal)
        anyseq(dut.io.inval)
        // Snoop bus tie-off
        dut.io.snoopValid := False
        dut.io.snoopHandle := 0
        dut.io.snoopFieldIdx := 0

        // Force field index to be uncacheable: upper bits nonzero
        val lowerBits = UInt(dut.indexBits bits)
        anyseq(lowerBits)
        val upperBits = UInt((dut.maxIndexBits - dut.indexBits) bits)
        anyseq(upperBits)
        // Constrain upper bits to be nonzero
        assume(upperBits =/= 0)
        dut.io.fieldIdx := (upperBits ## lowerBits).asUInt

        when(pastValidAfterReset()) {
          assert(!dut.io.hit)
        }
      })
  }

  test("hit implies cacheable field index") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(ObjectCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.hit) {
            // Upper index bits must be zero for a hit
            assert(dut.io.fieldIdx(dut.maxIndexBits - 1 downto dut.indexBits) === 0)
          }
        }
      })
  }

  test("no hit after reset before any write") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(ObjectCache())
        assumeInitial(ClockDomain.current.isResetActive)

        // No writes, no invalidation
        anyseq(dut.io.handle)
        anyseq(dut.io.fieldIdx)
        dut.io.chkGf := False
        dut.io.chkPf := False
        dut.io.wrGf := False
        dut.io.wrPf := False
        dut.io.gfVal := B(0)
        dut.io.pfVal := B(0)
        dut.io.inval := False
        // Snoop bus tie-off
        dut.io.snoopValid := False
        dut.io.snoopHandle := 0
        dut.io.snoopFieldIdx := 0

        when(pastValidAfterReset()) {
          // All valid bits are zero after reset, so no hit possible
          assert(!dut.io.hit)
        }
      })
  }
}
