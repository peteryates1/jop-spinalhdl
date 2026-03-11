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
 * - Cacheable bounds: only fields [0, 2^indexBits) produce hits
 * - No hit when field index out of range (uncacheable)
 * - Tag uniqueness: at most one entry has any valid bits for a given handle
 * - Fill correctness: after wrGf, the filled field's valid bit is set
 * - FIFO pointer advances by exactly 1 per getfield miss
 * - Snoop selective invalidation: only the targeted field is cleared
 * - No write-allocate: wrPf with tag miss does not allocate a new entry
 * - After invalidation, all valid bits are zero
 */
class ObjectCacheFormal extends SpinalFormalFunSuite {

  // Small config for tractability: 4 entries, 4 fields, 8-bit addresses
  def smallConfig = ObjectCache(addrBits = 8, wayBits = 2, indexBits = 2, maxIndexBits = 4)

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

  test("tag uniqueness: at most one entry per handle") {
    // If a handle matches two or more entries (both have valid bits),
    // the lineEnc OR-encoder would produce a corrupted line index.
    // This verifies the FIFO allocation never creates duplicates under
    // the real protocol: chkGf → wrGf (or chkPf → wrPf), with no
    // overlapping operations.
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // --- Protocol state machine ---
        // Models the real BmbMemoryController: only one chk→wr pair at a time.
        // 0=IDLE: can accept chkGf or chkPf (but not wr)
        // 1=PENDING_GF: chkGf fired, waiting for wrGf
        // 2=PENDING_PF: chkPf fired, waiting for wrPf
        val proto = Reg(UInt(2 bits)) init(0)

        when(proto === 0) {
          assume(!dut.io.wrGf && !dut.io.wrPf)
          assume(!(dut.io.chkGf && dut.io.chkPf))
          when(dut.io.chkGf) { proto := 1 }
          when(dut.io.chkPf) { proto := 2 }
        }
        when(proto === 1) {
          assume(!dut.io.chkGf && !dut.io.chkPf && !dut.io.wrPf)
          when(dut.io.wrGf) { proto := 0 }
        }
        when(proto === 2) {
          assume(!dut.io.chkGf && !dut.io.chkPf && !dut.io.wrGf)
          when(dut.io.wrPf) { proto := 0 }
        }

        when(pastValidAfterReset()) {
          // For any handle value, at most one entry should have valid bits
          for (i <- 0 until dut.lineCnt) {
            for (j <- i + 1 until dut.lineCnt) {
              when(dut.valid(i).orR && dut.valid(j).orR) {
                assert(dut.tag(i) =/= dut.tag(j))
              }
            }
          }
        }
      })
  }

  test("fill sets valid bit for filled field") {
    // After wrGf completes on a cacheable field, that field's valid bit must be set.
    // Track a specific line (0) and field (0) to avoid dynamic indexing.
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval and snoop to isolate fill behavior
        assume(!dut.io.inval)

        when(pastValidAfterReset()) {
          // When wrGf wrote to line 0, field 0, that valid bit must be set
          when(past(dut.io.wrGf) && past(dut.cacheableReg) &&
               past(dut.lineReg === U(0, dut.wayBits bits)) &&
               past(dut.indexReg(dut.indexBits - 1 downto 0) === U(0, dut.indexBits bits))) {
            assert(dut.valid(0)(0))
          }
        }
      })
  }

  test("FIFO pointer advances by exactly 1 per getfield miss") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval so it doesn't reset nxt concurrently
        assume(!dut.io.inval)

        when(pastValidAfterReset()) {
          // When wrGf fires with incNxtReg (new allocation), nxt advances by 1
          when(past(dut.io.wrGf) && past(dut.cacheableReg) && past(dut.incNxtReg)) {
            assert(dut.nxt === past(dut.nxt) + 1)
          }
        }
      })
  }

  test("snoop selectively invalidates only the targeted field") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)

        // Drive inputs manually to control snoop
        anyseq(dut.io.handle)
        anyseq(dut.io.fieldIdx)
        anyseq(dut.io.chkGf)
        anyseq(dut.io.chkPf)
        anyseq(dut.io.wrGf)
        anyseq(dut.io.wrPf)
        anyseq(dut.io.gfVal)
        anyseq(dut.io.pfVal)
        anyseq(dut.io.inval)
        anyseq(dut.io.snoopValid)
        anyseq(dut.io.snoopHandle)
        anyseq(dut.io.snoopFieldIdx)

        // Disable inval and cache updates to isolate snoop
        assume(!dut.io.inval)
        assume(!dut.io.wrGf)
        assume(!dut.io.wrPf)
        assume(!dut.io.chkGf)
        assume(!dut.io.chkPf)

        when(pastValidAfterReset()) {
          for (i <- 0 until dut.lineCnt) {
            // If snoop matches this entry's tag...
            when(past(dut.io.snoopValid) && past(dut.tag(i) === dut.io.snoopHandle)) {
              val snoopField = past(dut.io.snoopFieldIdx)(dut.indexBits - 1 downto 0)
              // The snooped field must be cleared
              assert(!dut.valid(i)(snoopField))
              // Other fields must be unchanged
              for (f <- 0 until dut.fieldCnt) {
                when(U(f, dut.indexBits bits) =/= snoopField) {
                  assert(dut.valid(i)(f) === past(dut.valid(i)(f)))
                }
              }
            }
            // If snoop doesn't match this entry, all valid bits unchanged
            when(past(dut.io.snoopValid) && past(dut.tag(i) =/= dut.io.snoopHandle)) {
              for (f <- 0 until dut.fieldCnt) {
                assert(dut.valid(i)(f) === past(dut.valid(i)(f)))
              }
            }
          }
        }
      })
  }

  test("no write-allocate: wrPf with tag miss does not change nxt") {
    // putfield should never allocate a new entry — incNxtReg is only set by chkGf miss
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallConfig)
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Disable inval so nxt isn't reset
        assume(!dut.io.inval)
        // Only allow chkPf, no chkGf (isolate putfield behavior)
        assume(!dut.io.chkGf)
        assume(!dut.io.wrGf)

        when(pastValidAfterReset()) {
          // After chkPf, incNxtReg must be False (no allocation)
          when(past(dut.io.chkPf)) {
            assert(!dut.incNxtReg)
          }
          // nxt must never advance from wrPf alone
          when(past(dut.io.wrPf)) {
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
              assert(dut.valid(i) === B(0, dut.fieldCnt bits))
            }
            // FIFO pointer must be reset
            assert(dut.nxt === 0)
          }
        }
      })
  }
}
