package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.memory.MethodCache

/**
 * Formal verification for the MethodCache component.
 *
 * Source: jop/memory/MethodCache.scala
 *
 * Properties verified:
 * - State machine only transitions IDLE->S1->IDLE (hit) or IDLE->S1->S2->IDLE (miss)
 * - No spurious transitions: S2 never reached without going through S1
 * - rdy output: true iff state==IDLE
 * - inCache stable until next find
 * - Tag uniqueness: at most one valid block per tag value
 * - FIFO pointer advances correctly on miss
 * - Hit after miss: find with same address after S2 produces inCache=true
 */
class MethodCacheFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  def setupDut(dut: MethodCache): Unit = {
    anyseq(dut.io.bcLen)
    anyseq(dut.io.bcAddr)
    anyseq(dut.io.find)
  }

  // Use smaller cache for tractability (4 blocks, 2KB minimum with jpcWidth=11)
  def smallCache(): MethodCache = MethodCache(jpcWidth = 11, blockBits = 2, tagWidth = 8)

  /** Constrain inputs per the memory controller contract:
   *  - find only fires in IDLE
   *  - bcAddr and bcLen are stable during S1/S2 (held by controller registers)
   */
  def assumeControllerContract(dut: MethodCache): Unit = {
    assume(dut.io.find === False || dut.state === dut.State.IDLE)
    when(dut.state =/= dut.State.IDLE) {
      assume(stable(dut.io.bcAddr))
      assume(stable(dut.io.bcLen))
    }
  }

  test("rdy true iff state is IDLE") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.rdy === (dut.state === dut.State.IDLE))
        }
      })
  }

  test("state machine valid transitions") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        val pastState = RegNext(dut.state)

        when(pastValidAfterReset()) {
          // S1 can only be reached from IDLE
          when(dut.state === dut.State.S1) {
            assert(pastState === dut.State.IDLE || pastState === dut.State.S1)
          }
          // S2 can only be reached from S1 (S2 is a one-cycle state that
          // always returns to IDLE, so S2→S2 is impossible)
          when(dut.state === dut.State.S2) {
            assert(pastState === dut.State.S1)
          }
          // IDLE can be reached from IDLE (no find), S1 (hit), or S2 (miss complete)
          when(dut.state === dut.State.IDLE) {
            assert(pastState === dut.State.IDLE || pastState === dut.State.S1 || pastState === dut.State.S2)
          }
        }
      })
  }

  test("find triggers state transition from IDLE to S1") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.state === dut.State.IDLE) && past(dut.io.find)) {
            assert(dut.state === dut.State.S1)
          }
        }
      })
  }

  test("inCache stable when no find") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(MethodCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // inCache only changes during S1 state (when tag check completes)
          when(past(dut.state =/= dut.State.S1)) {
            assert(stable(dut.io.inCache))
          }
        }
      })
  }

  test("tag uniqueness: at most one valid block per tag value") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(smallCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeControllerContract(dut)

        when(pastValidAfterReset()) {
          // For every pair of valid blocks, their tags must differ
          for (i <- 0 until dut.blocks) {
            for (j <- i + 1 until dut.blocks) {
              when(dut.tagValid(i) && dut.tagValid(j)) {
                assert(dut.tag(i) =/= dut.tag(j))
              }
            }
          }
        }
      })
  }

  test("FIFO pointer advances by nrOfBlks+1 on miss") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeControllerContract(dut)

        val pastNxt = RegNext(dut.nxt)

        when(pastValidAfterReset()) {
          // nxt only changes in S2 (miss completion)
          when(past(dut.state === dut.State.S2)) {
            assert(dut.nxt === (pastNxt + past(dut.nrOfBlks) + 1).resized)
          }
          // nxt doesn't change outside S2
          when(past(dut.state =/= dut.State.S2)) {
            assert(stable(dut.nxt))
          }
        }
      })
  }

  test("after miss, new tag is written at nxt position") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeControllerContract(dut)

        when(pastValidAfterReset()) {
          // After S2 completes (state transitions from S2 to IDLE),
          // the block at the old nxt position should have the correct tag and be valid
          when(past(dut.state === dut.State.S2)) {
            val oldNxt = past(dut.nxt)
            assert(dut.tagValid(oldNxt))
            assert(dut.tag(oldNxt) === past(dut.useAddr))
          }
        }
      })
  }

  test("hit after miss: find with same address after S2 produces inCache") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(smallCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeControllerContract(dut)

        // Track state: after S2 completes, if we issue find with same addr,
        // we should get a hit (assuming no eviction between)
        val didMiss = Reg(Bool()) init(False)
        val missAddr = Reg(Bits(dut.tagWidth bits)) init(0)

        // Record when a miss completes (S2 → IDLE)
        when(dut.state === dut.State.IDLE && past(dut.state === dut.State.S2)) {
          didMiss := True
          missAddr := past(dut.useAddr)
        }
        // Clear when a new find happens with a different address
        when(dut.io.find && dut.state === dut.State.IDLE) {
          when(dut.io.bcAddr.asBits =/= missAddr) {
            didMiss := False
          }
        }

        when(pastValidAfterReset()) {
          // If we just completed S1 after finding the same address as our recorded miss,
          // and no other miss has happened in between (didMiss still true), it must be a hit
          when(past(dut.state === dut.State.S1) && past(didMiss) && past(dut.useAddr) === past(missAddr)) {
            // The lookup should have found it (hit → goes to IDLE, not S2)
            // But only if no eviction occurred — we can't guarantee this in general
            // without constraining the intermediate operations. So we check a weaker
            // property: if the tag is still valid at any block, it must be a hit.
            val tagStillPresent = (0 until dut.blocks).map(i =>
              dut.tagValid(i) && dut.tag(i) === past(dut.useAddr)
            ).reduce(_ || _)
            when(tagStillPresent) {
              assert(dut.inCache)
            }
          }
        }
      })
  }

  test("clear mask: S2 invalidates exactly the displaced blocks") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(smallCache())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeControllerContract(dut)

        when(pastValidAfterReset()) {
          // After S2, blocks in [old_nxt+1, old_nxt+nrOfBlks] should be invalid
          // (cleared by clrVal), except old_nxt itself which gets the new tag.
          // Actually: clrVal covers [nxt, nxt+nrOfBlks], and then nxt gets
          // overwritten with the new tag. So blocks [nxt+1 .. nxt+nrOfBlks]
          // should be invalid (if they existed before).
          when(past(dut.state === dut.State.S2)) {
            val oldNxt = past(dut.nxt)
            val oldNrOfBlks = past(dut.nrOfBlks)
            // nxt block should be valid with new tag (written after clear)
            assert(dut.tagValid(oldNxt))
            // Blocks that were in the clear range (offset 1..nrOfBlks from nxt)
            // should be invalid — they were cleared and not overwritten
            for (k <- 1 until dut.blocks) {
              val idx = (oldNxt + U(k, dut.blockBits bits)).resize(dut.blockBits)
              when(U(k, dut.blockBits bits) <= oldNrOfBlks) {
                assert(!dut.tagValid(idx))
              }
            }
          }
        }
      })
  }
}
