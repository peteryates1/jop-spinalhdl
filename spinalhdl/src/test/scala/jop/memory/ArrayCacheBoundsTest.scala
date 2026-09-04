package jop.memory

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import jop.TestVectorUtils

/**
 * The array bounds check must survive the array cache.
 *
 * THE DEFECT (status item 128). On an `iaload` that HITS in the array cache the
 * controller stays in IDLE and returns `arrayCache.io.dout` from the output MUX.
 * It never enters HANDLE_READ, so it never reaches HANDLE_BOUND_READ, so it
 * never reads `handle[1]` -- the array length. `ArrayCache` had no notion of a
 * length at all, so a hit could not have checked one.
 *
 * The fill made that reachable rather than theoretical: it is bounded by the
 * LINE, not by the length. `alignedIndex = (index >> fieldBits) << fieldBits`,
 * then fieldCnt consecutive words. So on a 3-element array with a 4-word line,
 * an entirely legal `ia[0]` fetches indices 0,1,2 AND 3, and caches the word
 * past the end. The next `ia[3]` then hits on it:
 *
 *     ia[0]  miss -> bound check passes (0 < 3) -> fill caches 0,1,2,3
 *     ia[3]  HIT  -> returns the cached word, no check, no exception
 *
 * `iastore` was unaffected -- it always enters IAST_WAIT and always checks --
 * which is exactly the asymmetry seen on hardware: `wukongFull` reported
 * `MISS: iaload-upper` while both `iastore` bound assertions passed.
 *
 * WHY THE CONTROL CASE IS HERE AND NOT ASSUMED. "No exception fired" and "this
 * harness cannot see an exception fire" produce identical output, and a bounds
 * test that cannot observe a bounds fault passes against any RTL whatsoever.
 * `an out-of-bounds iaload that MISSES faults` runs the same read on a cold
 * cache, where the miss path has always been correct. It must pass BOTH before
 * and after the fix; the hit case must fail before and pass after. Neither test
 * means anything without the other.
 *
 * DISCIPLINE: docs/testing-discipline.md. The control case is not optional
 * decoration -- "no exception fired" and "this harness cannot see an exception
 * fire" are the same output. PROVED RED against the unfixed RTL: the hit case
 * returned the literal 0xDEADDEAD planted past the end of the array, while the
 * cold-cache control passed. Re-prove both if you change either.
 */
class ArrayCacheBoundsTest extends AnyFunSuite {

  // 4 KB of memory, array cache on at its defaults (16 lines x 4 elements).
  // useAcache defaults to true, which is what every board ships.
  lazy val compiled = TestVectorUtils.simWave(SimConfig).compile(
    BmbMemoryTestHarness(JopMemoryConfig(mainMemSize = 4096)))

  // Word addresses. The handle is two words; the data lives elsewhere.
  private val HANDLE   = 0x20   // handle[0] = data pointer, handle[1] = length
  private val DATA     = 0x40   // array elements start here (JOP: at data_ptr[0])
  private val LENGTH   = 3      // one SHORT of the 4-word cache line -- the point
  private val PAST_END = 0xDEADDEADL

  private def init(dut: BmbMemoryTestHarness): Unit = {
    dut.io.memIn.rd #= false
    dut.io.memIn.rdc #= false
    dut.io.memIn.rdf #= false
    dut.io.memIn.wr #= false
    dut.io.memIn.wrf #= false
    dut.io.memIn.addrWr #= false
    dut.io.memIn.bcRd #= false
    dut.io.memIn.stidx #= false
    dut.io.memIn.iaload #= false
    dut.io.memIn.iastore #= false
    dut.io.memIn.getfield #= false
    dut.io.memIn.putfield #= false
    dut.io.memIn.putref #= false
    dut.io.memIn.getstatic #= false
    dut.io.memIn.putstatic #= false
    dut.io.memIn.copy #= false
    dut.io.memIn.cinval #= false
    dut.io.memIn.bcopd #= 0
    dut.io.aout #= 0
    dut.io.bout #= 0
    dut.io.bcopd #= 0
    dut.io.ioRdData #= 0
  }

  private def settle(dut: BmbMemoryTestHarness, limit: Int = 200): Unit = {
    var n = 0
    while (dut.io.debug.busy.toBoolean && n < limit) {
      dut.clockDomain.waitSampling(); n += 1
    }
    assert(n < limit, s"controller never left busy (state ${dut.io.debug.state.toInt})")
  }

  /** stmwa + stmwd — the plain write path, used only to lay out the heap. */
  private def writeWord(dut: BmbMemoryTestHarness, addr: Int, data: Long): Unit = {
    dut.io.aout #= addr
    dut.io.memIn.addrWr #= true
    dut.clockDomain.waitSampling()
    dut.io.memIn.addrWr #= false
    dut.clockDomain.waitSampling()

    dut.io.aout #= data
    dut.io.memIn.wr #= true
    dut.clockDomain.waitSampling()
    dut.io.memIn.wr #= false
    dut.clockDomain.waitSampling()
    settle(dut)
    dut.clockDomain.waitSampling()
  }

  /**
   * Latches `abFire` at every clock edge for the whole simulation.
   *
   * Sampling at EVERY edge, rather than polling while busy, is deliberate: on a
   * cache hit the controller never goes busy at all, so a busy-gated poll would
   * inspect nothing and report "no fault" for the broken and the fixed design
   * alike -- a test that cannot fail.
   */
  private class AbWatch(dut: BmbMemoryTestHarness) {
    private var seen = false
    dut.clockDomain.onSamplings { if (dut.io.debug.abFire.toBoolean) seen = true }
    def arm(): Unit = seen = false
    def fired: Boolean = seen
  }

  /**
   * One `iaload`: NOS (bout) = array handle, TOS (aout) = index.
   * Returns the value read and whether the bounds fault fired at any point.
   */
  private def iaload(dut: BmbMemoryTestHarness, ab: AbWatch,
                     handle: Int, index: Int): (Long, Boolean) = {
    ab.arm()

    dut.io.bout #= handle
    dut.io.aout #= index
    dut.io.memIn.iaload #= true
    dut.clockDomain.waitSampling()
    dut.io.memIn.iaload #= false
    dut.clockDomain.waitSampling()
    settle(dut)
    dut.clockDomain.waitSampling(2)

    (dut.io.memOut.rdData.toLong & 0xFFFFFFFFL, ab.fired)
  }

  /** Lay out one 3-element int[] with a recognisable word just past its end. */
  private def buildArray(dut: BmbMemoryTestHarness): AbWatch = {
    dut.clockDomain.forkStimulus(10)
    init(dut)
    dut.clockDomain.waitSampling(5)

    writeWord(dut, HANDLE + 0, DATA)      // OFF_PTR
    writeWord(dut, HANDLE + 1, LENGTH)    // OFF_MTAB_ALEN
    writeWord(dut, DATA + 0, 0xA0)
    writeWord(dut, DATA + 1, 0xA1)
    writeWord(dut, DATA + 2, 0xA2)
    writeWord(dut, DATA + 3, PAST_END)    // NOT part of the array
    new AbWatch(dut)
  }

  test("an in-bounds iaload still reads the right element") {
    // The thing the fix must not break. A full line still hits.
    compiled.doSim { dut =>
      val w = buildArray(dut)
      for ((idx, exp) <- Seq(0 -> 0xA0L, 1 -> 0xA1L, 2 -> 0xA2L)) {
        val (v, ab) = iaload(dut, w, HANDLE, idx)
        assert(!ab, s"ia[$idx] on a 3-element array must not fault")
        assert(v == exp, f"ia[$idx]: expected 0x$exp%X, got 0x$v%X")
      }
      // And again, now that the line is cached — these are hits.
      for ((idx, exp) <- Seq(0 -> 0xA0L, 1 -> 0xA1L, 2 -> 0xA2L)) {
        val (v, ab) = iaload(dut, w, HANDLE, idx)
        assert(!ab, s"ia[$idx] (cached) must not fault")
        assert(v == exp, f"ia[$idx] (cached): expected 0x$exp%X, got 0x$v%X")
      }
    }
  }

  test("an out-of-bounds iaload that MISSES faults") {
    // THE CONTROL. Cold cache, so this takes the HANDLE_READ path, which has
    // always been correct. If this ever fails, the test below proves nothing.
    compiled.doSim { dut =>
      val w = buildArray(dut)
      val (_, ab) = iaload(dut, w, HANDLE, LENGTH)
      assert(ab, "ia[3] on a 3-element array must raise EXC_AB on a cache MISS")
    }
  }

  test("an out-of-bounds iaload that HITS faults too") {
    // THE DEFECT. ia[0] is legal and fills the line — including index 3, which
    // is past the end. ia[3] then hits on that word.
    compiled.doSim { dut =>
      val w = buildArray(dut)
      val (v0, ab0) = iaload(dut, w, HANDLE, 0)
      assert(!ab0 && v0 == 0xA0L, f"setup: ia[0] should read 0xA0, got 0x$v0%X")

      val (v, ab) = iaload(dut, w, HANDLE, LENGTH)
      assert(ab,
        f"ia[3] on a 3-element array returned 0x$v%X with no bounds fault. The " +
        "array cache hit on a word the line fill fetched from past the end of " +
        "the array, and the hit path never reads handle[1]. Status item 128.")
    }
  }

  test("the element past the end is never returned") {
    // States the consequence separately from the mechanism: whatever the cache
    // does internally, PAST_END must not reach the pipeline.
    compiled.doSim { dut =>
      val w = buildArray(dut)
      iaload(dut, w, HANDLE, 0)
      val (v, _) = iaload(dut, w, HANDLE, LENGTH)
      assert(v != PAST_END,
        f"ia[3] returned the word past the end of the array (0x$v%X)")
    }
  }
}
