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
 * - Snoop preserves non-matching lines
 * - wrIal with snoopDuringFill does not update cache
 * - Fill index auto-increments on wrIal
 * - Tag+index uniqueness: at most one entry per (handle, tagIdx) pair
 * - Fill sets valid bit for filled line
 * - No write-allocate: chkIas does not set incNxtReg
 * - Invalidation clears all valid bits and resets nxt
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

  test("snoop preserves non-matching lines") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)

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

        // Isolate snoop: no other cache mutations
        assume(!dut.io.inval)
        assume(!dut.io.wrIal)
        assume(!dut.io.wrIas)
        assume(!dut.io.chkIal)
        assume(!dut.io.chkIas)

        when(pastValidAfterReset()) {
          for (i <- 0 until dut.lineCnt) {
            // If snoop doesn't match this line, valid bit must be unchanged
            when(past(dut.io.snoopValid) &&
                 !(past(dut.valid(i)) &&
                   past(dut.tag(i) === dut.io.snoopHandle) &&
                   past(dut.tagIdx(i) === dut.io.snoopIndex(dut.maxIndexBits - 1 downto dut.fieldBits)))) {
              assert(dut.valid(i) === past(dut.valid(i)))
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

  test("tag+index uniqueness: at most one entry per (handle, tagIdx) pair") {
    // The array cache allows multiple entries for the same handle (different
    // regions), but no two entries should share the same (handle, tagIdx) pair.
    // If they did, lineEnc would OR their line numbers, corrupting the RAM address.
    // Protocol constraint: chkIal → wrIal sequence (no interleaving).
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // --- Protocol state machine ---
        // Models the real BmbMemoryController: one chkIal→wrIal fill at a time.
        // 0=IDLE: can accept chkIal or chkIas (but not wrIal/wrIas)
        // 1=FILLING: chkIal fired (miss), expecting fieldCnt wrIal pulses
        // 2=PENDING_IAS: chkIas fired, waiting for wrIas
        val proto = Reg(UInt(2 bits)) init(0)
        val fillCnt = Reg(UInt(dut.fieldBits + 1 bits)) init(0)

        when(proto === 0) {
          assume(!dut.io.wrIal && !dut.io.wrIas)
          assume(!(dut.io.chkIal && dut.io.chkIas))
          when(dut.io.chkIal) {
            proto := 1
            fillCnt := 0
          }
          when(dut.io.chkIas) { proto := 2 }
        }
        when(proto === 1) {
          assume(!dut.io.chkIal && !dut.io.chkIas && !dut.io.wrIas)
          when(dut.io.wrIal) {
            fillCnt := fillCnt + 1
            when(fillCnt === dut.fieldCnt - 1) { proto := 0 }
          }
        }
        when(proto === 2) {
          assume(!dut.io.chkIal && !dut.io.chkIas && !dut.io.wrIal)
          when(dut.io.wrIas) { proto := 0 }
        }

        when(pastValidAfterReset()) {
          // For any pair of valid entries, their (tag, tagIdx) must differ
          for (i <- 0 until dut.lineCnt) {
            for (j <- i + 1 until dut.lineCnt) {
              when(dut.valid(i) && dut.valid(j)) {
                assert(dut.tag(i) =/= dut.tag(j) || dut.tagIdx(i) =/= dut.tagIdx(j))
              }
            }
          }
        }
      })
  }

  test("fill sets valid bit for filled line") {
    // After wrIal completes on a miss (line 0), valid(0) must be True.
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval and snoop to isolate fill behavior
        assume(!dut.io.inval)

        when(pastValidAfterReset()) {
          // When wrIal wrote to line 0 (lineReg=0), that line's valid must be set
          when(past(dut.io.wrIal) && past(dut.cacheableReg) && !past(dut.snoopDuringFill) &&
               past(dut.lineReg === U(0, dut.wayBits bits))) {
            assert(dut.valid(0))
          }
        }
      })
  }

  test("no write-allocate: chkIas does not set incNxtReg") {
    // iastore should never allocate a new cache entry — incNxtReg is only
    // set by chkIal miss (hitVec miss). chkIas uses lineEnc (hit line only).
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval so nxt isn't reset
        assume(!dut.io.inval)
        // Only allow chkIas, no chkIal (isolate iastore behavior)
        assume(!dut.io.chkIal)
        assume(!dut.io.wrIal)

        when(pastValidAfterReset()) {
          // After chkIas, incNxtReg must be False (no allocation)
          when(past(dut.io.chkIas)) {
            assert(!dut.incNxtReg)
          }
          // nxt must never advance from wrIas alone
          when(past(dut.io.wrIas)) {
            assert(dut.nxt === past(dut.nxt))
          }
        }
      })
  }

  test("invalidation clears all valid bits and resets nxt") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.io.inval)) {
            // All valid bits must be zero
            for (i <- 0 until dut.lineCnt) {
              assert(!dut.valid(i))
            }
            // FIFO pointer must be reset
            assert(dut.nxt === 0)
          }
        }
      })
  }
}
