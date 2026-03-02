package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.memory.ArrayCache

/**
 * Formal verification for the ArrayCache component.
 *
 * Source: jop/memory/ArrayCache.scala
 *
 * Properties verified:
 * - Hit implies valid and tag match for some line
 * - FIFO pointer advances by exactly 1 per miss
 * - Snoop invalidates matching line
 * - wrIal with snoopDuringFill does not update cache
 * - Fill index auto-increments on wrIal
 */
class ArrayCacheFormal extends SpinalFormalFunSuite {

  // Small config for tractability: 4 lines, 4 elements/line, 8-bit addresses
  def smallConfig = ArrayCache(addrBits = 8, wayBits = 2, fieldBits = 2, maxIndexBits = 8)

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  def setupDut(dut: ArrayCache): Unit = {
    anyseq(dut.io.handle)
    anyseq(dut.io.index)
    anyseq(dut.io.chkIal)
    anyseq(dut.io.chkIas)
    anyseq(dut.io.wrIal)
    anyseq(dut.io.wrIas)
    anyseq(dut.io.ialVal)
    anyseq(dut.io.iasVal)
    anyseq(dut.io.inval)
    // Snoop bus tie-off
    dut.io.snoopValid := False
    dut.io.snoopHandle := 0
    dut.io.snoopIndex := 0
  }

  test("hit implies valid and tag match") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.hit) {
            // At least one line must be valid with matching tag and index
            val anyMatch = (0 until dut.lineCnt).map { i =>
              dut.valid(i) && dut.tag(i) === dut.io.handle &&
                dut.tagIdx(i) === dut.io.index(dut.maxIndexBits - 1 downto dut.fieldBits)
            }.reduce(_ || _)
            assert(anyMatch)
          }
        }
      })
  }

  test("FIFO pointer advances by exactly 1 per miss") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval so it doesn't reset nxt concurrently
        assume(!dut.io.inval)

        when(pastValidAfterReset()) {
          // When wrIal fires with incNxtReg set (first wrIal of a miss fill),
          // nxt advances by exactly 1
          when(past(dut.io.wrIal) && past(dut.cacheableReg) && past(dut.incNxtReg)) {
            assert(dut.nxt === past(dut.nxt) + 1)
            // incNxtReg must be cleared after first advance
            assert(!dut.incNxtReg)
          }
        }
      })
  }

  test("snoop invalidates matching line") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)

        // Drive all inputs with anyseq including snoop
        anyseq(dut.io.handle)
        anyseq(dut.io.index)
        anyseq(dut.io.chkIal)
        anyseq(dut.io.chkIas)
        anyseq(dut.io.wrIal)
        anyseq(dut.io.wrIas)
        anyseq(dut.io.ialVal)
        anyseq(dut.io.iasVal)
        anyseq(dut.io.inval)
        anyseq(dut.io.snoopValid)
        anyseq(dut.io.snoopHandle)
        anyseq(dut.io.snoopIndex)

        // Disable inval and cache updates to isolate snoop behavior
        assume(!dut.io.inval)
        assume(!dut.io.wrIal)
        assume(!dut.io.wrIas)
        assume(!dut.io.chkIal)
        assume(!dut.io.chkIas)

        when(pastValidAfterReset()) {
          for (i <- 0 until dut.lineCnt) {
            // If the line was valid and matched the snoop, it must now be invalid
            when(past(dut.io.snoopValid) &&
                 past(dut.valid(i)) &&
                 past(dut.tag(i) === dut.io.snoopHandle) &&
                 past(dut.tagIdx(i) === dut.io.snoopIndex(dut.maxIndexBits - 1 downto dut.fieldBits))) {
              assert(!dut.valid(i))
            }
          }
        }
      })
  }

  test("wrIal with snoopDuringFill does not update cache") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // updateCache must be False when wrIal fires but snoopDuringFill is set
          when(dut.io.wrIal && dut.snoopDuringFill) {
            assert(!dut.updateCache)
          }
        }
      })
  }

  test("fill index auto-increments on wrIal") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable chkIal/chkIas so they don't reset idxReg
        assume(!dut.io.chkIal)
        assume(!dut.io.chkIas)

        when(pastValidAfterReset()) {
          when(past(dut.io.wrIal)) {
            assert(dut.idxReg === past(dut.idxReg) + 1)
          }
        }
      })
  }
}
