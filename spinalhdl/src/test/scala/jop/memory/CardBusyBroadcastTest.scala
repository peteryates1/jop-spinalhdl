package jop.memory

import spinal.core._
import jop.system.JopCluster
import jop.config.JopCoreConfig

/**
 * Every core stalls for the card-table sweep, not just the one that asked.
 *
 * WHY THIS EXISTS. `JopCluster` broadcasts the sweep busy with
 * `ports.foreach(_.busy := ct.io.clrBusy)`, and the review of status item 131
 * found that NOTHING asserted it: replacing that line with
 * `ports.foreach(_.busy := False)` failed no test in the tree. Every hardware
 * validation of item 131 used a single-core preset, and JopCardClearStallSim
 * re-implements the wiring in its own harness rather than instantiating
 * JopCluster, so neither touches the broadcast.
 *
 * It is load-bearing rather than defensive. `CmpSync` exempts the lock OWNER
 * from `gcHalt` entirely, so a core holding the global lock is genuinely
 * executing while the collector sweeps, and this broadcast is the only thing
 * standing between it and a mark dropped into the sweep -- which is the exact
 * defect item 131 fixed.
 *
 * STRUCTURAL, not behavioural, and deliberately so. Proving it by simulation
 * means booting a multi-core cluster for tens of millions of cycles and
 * contriving the lock-owner window; asserting the CONNECTION is what actually
 * breaks if the line is deleted, and it costs one elaboration. The check is
 * that the sweep-busy net reaches as many consumers as there are cores.
 */
object CardBusyBroadcastTest extends App {

  val cpuCnt = 3

  val cfg = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 64 * 1024, hasCardTable = true,
      cardTableBudgetBytes = 1024)
  )

  val report = SpinalConfig(
    mode = Verilog,
    targetDirectory = jop.generate.BuildLayout.default.standaloneDir("CardBusyBroadcastTest")
  ).generate(JopCluster(cpuCnt = cpuCnt, baseConfig = cfg))

  val v = scala.io.Source
    .fromFile(s"${jop.generate.BuildLayout.default.standaloneDir("CardBusyBroadcastTest")}/${report.toplevelName}.v")
    .mkString

  // The busy net feeding each core's memory controller. Named from the
  // CardTable output rather than guessed: if SpinalHDL renames it the test
  // fails loudly here rather than passing on a net that no longer exists.
  val busyNet = """(\w*clrBusy\w*)""".r.findFirstIn(v)
  assert(busyNet.isDefined,
    "no clrBusy net in the elaborated cluster -- the card table is not present, " +
    "or the signal was renamed. This test cannot assert anything; fix it rather " +
    "than deleting it.")

  val net = busyNet.get
  val consumers = s"""(?<![\\w])${java.util.regex.Pattern.quote(net)}(?![\\w])""".r
    .findAllIn(v).length

  println(s"CardBusyBroadcastTest: $cpuCnt cores, busy net '$net' appears $consumers times")

  // One driver plus one consumer per core is the minimum; a broadcast removed
  // collapses this to the driver and at most one reader.
  assert(consumers >= cpuCnt + 1,
    s"the sweep-busy net '$net' appears only $consumers times in a $cpuCnt-core " +
    s"cluster. Every core must stall for the sweep -- see JopCluster's " +
    s"`ports.foreach(_.busy := ct.io.clrBusy)`. With the broadcast removed a " +
    s"lock-owning core keeps running (CmpSync exempts it from gcHalt) and marks " +
    s"into a sweep that drops them. Status item 131.")

  println("PASS: the card-clear busy reaches every core")
}
