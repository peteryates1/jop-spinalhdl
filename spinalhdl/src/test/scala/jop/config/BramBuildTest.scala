package jop.config

import org.scalatest.funsuite.AnyFunSuite

/**
 * A BRAM system is BUILT as BRAM: its main memory is the BRAM `JopTop`
 * instantiates, and all of it is usable, because a BRAM system has no stack
 * cache to reserve a spill region for.
 *
 * THE DEFECT THIS WAS WRITTEN AGAINST (status item 176). `JopTop` calls a
 * system with no memory device BRAM -- `memory = "bram"` is the only way to
 * have none -- while `builtCoreConfig` asked whether the device's TYPE was
 * BRAM, and no device's is. So every BRAM system took the SDR branch: it kept
 * the preset's `mainMemSize` instead of the BRAM's, and on a board whose SDR
 * builds have a stack cache it reserved an 8192-word spill region as well.
 * `JopTopBramSim` builds `wukongBram` with 256 KB of BRAM, and the core read
 * `IO_MEM_SIZE` = 8192 words (the 64 KB preset less 8192). Its 12,523-word
 * image is past that, so `Startup.boot()` fell back to a 262,144-word heap and
 * `GC.init` wrote its handle table round the end of the 65,536-word BRAM, over
 * the program. The sim printed `GC init...` and nothing after it.
 *
 * `ep4cgx150Bram` regenerated identical RTL across the commit that moved the
 * override block here (04a32f5), because on that board the two branches agree:
 * the EP4CGX150 has no stack cache, and its FPGA flow passes the preset's own
 * size. Only a BRAM system on a stack-cache board, or a BRAM of another size,
 * tells them apart -- so every preset is checked, not a sample.
 */
class BramBuildTest extends AnyFunSuite {

  /** Every preset, found as BoardClockTest finds them: there is no registry. */
  private val presets: Seq[(String, JopConfig)] = JopConfig.getClass.getMethods.toSeq
    .filter(m => m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig])
    .map(m => m.getName -> m.invoke(JopConfig).asInstanceOf[JopConfig])

  private val bramSystems: Seq[(String, JopConfig, JopSystem)] = for {
    (name, config) <- presets
    sys <- config.systems if sys.memory == "bram"
  } yield (name, config, sys)

  test("the presets checked include a BRAM system on a stack-cache board") {
    // The case that broke. Without it the next test can pass on boards where
    // the BRAM and SDR branches happen to agree.
    val names = bramSystems.map(_._1).toSet
    for (n <- Seq("wukongBram", "wukongBramFull", "ep4cgx150Bram", "colorlightI5Bram"))
      assert(names.contains(n), s"$n is not among the BRAM presets found: $names")
  }

  test("a BRAM system's memory is the BRAM it is built with, all of it usable") {
    // Not any preset's own size: JopTopBramSim builds 64 KB presets with 256 KB.
    val bramBytes = 256 * 1024
    // Every preset's answer, not the first wrong one: which presets are wrong,
    // and how, is the diagnosis.
    val wrong = for {
      (name, config, sys) <- bramSystems
      built = config.builtCoreConfig(sys, sys.coreConfig, bramMemSize = Some(bramBytes))
      // What the core reports as IO_MEM_SIZE, and the runtime takes as the heap end.
      usable = built.memConfig.usableMemWords(sys.cpuCnt)
      if built.memConfig.mainMemSize != bramBytes || usable != bramBytes / 4 || built.useStackCache
    } yield s"$name: built for ${built.memConfig.mainMemSize} bytes of a $bramBytes-byte BRAM, " +
      s"IO_MEM_SIZE $usable of ${bramBytes / 4} words " +
      s"(${built.memConfig.stackRegionWordsPerCore} reserved per core), stack cache ${built.useStackCache}"
    assert(wrong.isEmpty, wrong.mkString("\n", "\n", ""))
  }
}
