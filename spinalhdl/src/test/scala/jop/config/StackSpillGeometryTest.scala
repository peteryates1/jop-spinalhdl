package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.ConstGenerator

/**
 * The collector and the stack cache must agree on WHERE a spilled stack is.
 *
 * With a stack cache, a halted core's stack lives in its spill region in main
 * memory and `GC.scanOtherCoreRoots` reads it with ordinary loads at
 * `Const.STACK_SPILL_BASE - c*STACK_SPILL_WORDS` (item 133). The address the
 * hardware SPILLS to comes from `StackCacheConfig.spillBaseAddr`, computed from
 * the as-built `memConfig`. Two derivations of one address, in two languages,
 * with nothing between them.
 *
 * THE DEFECT THIS WAS WRITTEN AGAINST. `ConstGenerator` asked
 * `effectiveMemWords`, which took the size from the memory DEVICE whenever one
 * resolved; `JopTop` takes it from the device on the DDR paths only, so an SDR
 * system keeps the preset's `mainMemSize`. The Wukong carries a 32 MB chip and
 * JOP is built for 8 MB, so on `wukongSdrSmp 2`:
 *
 *   Const.java  STACK_SPILL_BASE = 8,380,416   (32 MB - 8192)
 *   RTL         spillBaseAddr    = 2,088,960   (22'h1fe000, 8 MB - 8192)
 *
 * — a factor of four apart, and 8,380,416 does not even fit the 22-bit word
 * address the design carries, so the collector could not have reached it. Every
 * root on another core would have been read from words nothing ever wrote:
 * silent, and in the direction that COLLECTS LIVE OBJECTS.
 *
 * It is checked here rather than in a simulation because a sim can only find it
 * on a config that has both SMP and a stack cache, and it was wrong on the
 * single-core SDR builds too.
 */
class StackSpillGeometryTest extends AnyFunSuite {

  /**
   * Presets to check. There is no registry of presets to enumerate (status
   * item 112 is the same gap for constraints), so this list is hand-kept.
   * It deliberately includes configs WITHOUT a stack cache: the check is
   * "Const.java describes the hardware", which has content either way.
   */
  private val presets: Seq[(String, JopConfig)] = Seq(
    "wukongSdram"      -> JopConfig.wukongSdram,      // SDR, stack cache, 1 core
    "wukongSdrSmp 2"   -> JopConfig.wukongSdrSmp(2),  // SDR, stack cache, SMP
    "wukongSdrSmp 4"   -> JopConfig.wukongSdrSmp(4),
    "wukongSdrSmpSim 2" -> JopConfig.wukongSdrSmpSim(2),  // the sim vehicle, small heap
    "wukongSdrSmpSim 4" -> JopConfig.wukongSdrSmpSim(4),
    "wukongSdrFull"    -> JopConfig.wukongSdrFull,
    "wukongDdr3"       -> JopConfig.wukongDdr3,       // DDR3 1 core -> stack cache
    // DDR3 SMP: effectiveUseStackCache is false past one core, so this config
    // must reserve NOTHING. It reserved 8192 words per core anyway -- the DDR
    // branch set the reservation unconditionally while the enable was gated,
    // so wukongDdr3Smp-6 lost 192 KB of heap and an 8-core build 256 KB. The
    // SDR branch gates both on one predicate and cannot drift; this is the
    // testable twin of the DDR2 waste, which has no reachable hardware.
    "wukongDdr3Smp 4"  -> JopConfig.wukongDdr3Smp(4),
    "wukongDdr3Smp 8"  -> JopConfig.wukongDdr3Smp(8),
    "wukongFull"       -> JopConfig.wukongFull,
    "xc7a100tDbSerial" -> JopConfig.xc7a100tDbSerial, // DDR3, the item 133 board
    "ep4cgx150Serial"  -> JopConfig.ep4cgx150Serial,  // SDR, board flag off
    "ep4cgx150Smp 4"   -> JopConfig.ep4cgx150Smp(4),
    "auSerial"         -> JopConfig.auSerial)

  /** The three stack-cache constants as `Const.java` states them. */
  private def constants(config: JopConfig): (Int, Int, Int) = {
    val src = ConstGenerator.generate(config)
    def intConst(name: String): Int = {
      val re = raw"""public static final int $name = (-?\d+)""".r
      re.findFirstMatchIn(src)
        .getOrElse(fail(s"$name is not emitted into Const.java at all"))
        .group(1).toInt
    }
    (intConst("STACK_CACHE"), intConst("STACK_SPILL_BASE"), intConst("STACK_SPILL_WORDS"))
  }

  test("Const.java's spill base is the address the stack cache spills to") {
    for ((name, config) <- presets) {
      val sys = config.system
      val built = config.builtCoreConfig(sys, sys.coreConfig)
      val (cacheFlag, base, words) = constants(config)

      assert((cacheFlag != 0) == built.useStackCache,
        s"$name: Const says STACK_CACHE=$cacheFlag, the build says useStackCache=${built.useStackCache}")

      built.stackConfig.cacheConfig match {
        case None =>
          assert(base == 0 && words == 0,
            s"$name has no stack cache, but Const carries base=$base words=$words")
          // RESERVATION AND ENABLE MUST SHARE A PREDICATE. A config with no
          // stack cache that still reserves a spill region silently loses
          // stackRegionWordsPerCore * cpuCnt words of heap -- usableMemWords
          // subtracts it either way.
          assert(built.memConfig.stackRegionWordsPerCore == 0,
            s"$name has no stack cache but reserves " +
            f"${built.memConfig.stackRegionWordsPerCore}%,d words per core — " +
            f"${built.memConfig.stackRegionWordsPerCore * sys.cpuCnt * 4L / 1024}%,d KB of heap lost")
        case Some(sc) =>
          assert(base == sc.spillBaseAddr,
            s"$name: Const.STACK_SPILL_BASE = $base, the RTL spills core 0 to " +
            s"${sc.spillBaseAddr} (0x${sc.spillBaseAddr.toHexString}) — the collector " +
            "would read words the core never wrote")
          assert(words == built.memConfig.stackRegionWordsPerCore,
            s"$name: Const.STACK_SPILL_WORDS = $words, the build reserves " +
            s"${built.memConfig.stackRegionWordsPerCore}")
      }
    }
  }

  /**
   * `Const.STACK_SIZE` IS THE STACK A THREAD CAN ACTUALLY HAVE — status item 160.
   *
   * Its only consumer is `RtThreadImpl`, which gives every thread a save area of
   * `STACK_SIZE - STACK_OFF` words: the context switch copies the whole stack,
   * `[STACK_OFF, SP]`, into it. It was a hardcoded 65536 for every config -- the
   * 16-bit VIRTUAL SP range, not any physical stack -- so one thread cost
   * 261,888 bytes and could not be constructed on a BRAM build (234,188 free,
   * measured by `jvm.ThreadAll`).
   *
   * What SP can actually reach:
   *   - no stack cache: the one stack RAM, `1 << ramWidth` words (scratch
   *     included);
   *   - a stack cache: scratch plus this core's spill region, beyond which the
   *     hardware faults (EXC_SPOV for SP, and for an AR access since item 133).
   * Too small would let the save overrun the array; too large is the bug.
   */
  test("Const.STACK_SIZE is the stack a thread can actually have") {
    for ((name, config) <- presets) {
      val sys = config.system
      val built = config.builtCoreConfig(sys, sys.coreConfig)
      val src = ConstGenerator.generate(config)
      val re = raw"""public static final int STACK_SIZE = (-?\d+)""".r
      val got = re.findFirstMatchIn(src)
        .getOrElse(fail(s"$name: STACK_SIZE is not emitted into Const.java")).group(1).toInt
      val want = built.stackConfig.cacheConfig match {
        case None     => 1 << built.ramWidth
        case Some(sc) => sc.scratchSize + built.memConfig.stackRegionWordsPerCore
      }
      assert(got == want,
        s"$name: Const.STACK_SIZE = $got, but SP can reach at most ${want - 1} " +
        s"(${if (built.useStackCache) "scratch + spill region" else "1 << ramWidth"}), " +
        f"so each thread's save area is ${(got - 64) * 4}%,d bytes instead of ${(want - 64) * 4}%,d")
    }
  }

  /**
   * The base must be REACHABLE. `wordAddrWidth = addressWidth - 2` bits address
   * main memory, and the mismatch above produced a base one bit wider than that
   * — which truncates rather than faulting, so the read lands somewhere real.
   */
  test("every core's spill region is inside the addressable range") {
    for ((name, config) <- presets) {
      val sys = config.system
      val built = config.builtCoreConfig(sys, sys.coreConfig)
      built.stackConfig.cacheConfig.foreach { sc =>
        val memWords = (built.memConfig.mainMemSize / 4).toInt
        val limit = BigInt(1) << (built.memConfig.addressWidth - 2)
        // Lowest core's region starts lowest; the top of core 0's is the highest.
        val lowest = sc.spillBaseAddr - (sys.cpuCnt - 1) * built.memConfig.stackRegionWordsPerCore
        val highest = sc.spillBaseAddr + built.memConfig.stackRegionWordsPerCore - 1
        assert(lowest >= 0, s"$name: ${sys.cpuCnt} cores' spill regions run below word 0 ($lowest)")
        assert(BigInt(highest) < limit,
          s"$name: spill region ends at word $highest, past the " +
          s"${built.memConfig.addressWidth - 2}-bit word address limit $limit")
        assert(highest < memWords,
          s"$name: spill region ends at word $highest, past the $memWords words of memory")
      }
    }
  }
}
