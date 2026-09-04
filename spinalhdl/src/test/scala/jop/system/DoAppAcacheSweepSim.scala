package jop.system
import jop.config._

import spinal.core.sim._
import jop.utils.JopFileLoader

/**
 * Where does REAL code's memory traffic go, and does a wider array-cache line
 * earn the elements it fetches but never reads?
 *
 * `JbeScaleBramSim` measured 9.09 BMB transactions per array access on a
 * deliberately cache-hostile stride, of which 4 were an A$ line fill that used
 * one element and 4 were repeated handle dereferences. Both look like waste,
 * but `jbe.Scale` is the pessimal case by construction — a workload with
 * locality amortises the fill and may well prefer it WIDER. So the two levers
 * have to be judged on `jbe.DoApp` (Kfl + UdpIp + Lift), not on Scale.
 *
 * What matters here is the transaction MIX and the A$ hit rate, both of which
 * are properties of the access pattern and independent of memory latency — so
 * BRAM is the right backend and the numbers transfer to any board. Absolute
 * iterations/second belong on hardware (EP4CGX150 at 80 MHz, where the
 * published DoApp baselines were taken).
 *
 * Transactions are attributed by the memory-controller state that issued them,
 * so "handle dereference" and "line fill" are measured, not inferred:
 *
 *   HANDLE_READ/WAIT (5,6)          -> data_ptr dereference
 *   HANDLE_BOUND_READ/WAIT (10,11)  -> array bounds check
 *   HANDLE_ACCESS/DATA_WAIT (8,9)   -> the element itself
 *   AC_FILL_CMD/WAIT (18,19)        -> array-cache line fill
 *   BC_* (14..17)                   -> bytecode cache fill
 *   GS_READ/PS_WRITE (29,30)        -> statics
 *   READ_WAIT/WRITE_WAIT (1,2)      -> direct (non-handle) access
 *
 *   sbt "Test/runMain jop.system.DoAppAcacheSweepSim"
 */
object DoAppAcacheSweepSim extends App {

  val jopFilePath = jop.utils.SimApp.jop("JbeBench", "JbeBench")   // entry point is jbe.DoApp
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val bramSize = 2 * 1024 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  // DoApp calibrates each benchmark to ~1 simulated second, which is ~80 M
  // cycles apiece — far more than needed. A fixed window after boot samples the
  // steady-state mix, which is all a hit-rate measurement requires.
  val WARMUP = 3000000L
  val WINDOW = 12000000L

  val category = Map(
    5 -> "handle deref", 6 -> "handle deref",
    10 -> "bounds check", 11 -> "bounds check",
    8 -> "element", 9 -> "element",
    18 -> "A$ line fill", 19 -> "A$ line fill",
    14 -> "bytecode fill", 15 -> "bytecode fill", 16 -> "bytecode fill", 17 -> "bytecode fill",
    29 -> "statics", 30 -> "statics",
    1 -> "direct", 2 -> "direct",
    20 -> "GC copy", 21 -> "GC copy", 22 -> "GC copy", 23 -> "GC copy", 24 -> "GC copy",
    25 -> "zero fill", 26 -> "zero fill")

  case class Result(fieldBits: Int, total: Long, byCat: Map[String, Long], uart: String)

  def run(fieldBits: Int): Result = {
    var res: Result = null
    SimConfig
      .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize, fieldBits))
      .doSim(s"doapp_af$fieldBits", 42) { dut =>
        val uart = new StringBuilder
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        val counts = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
        var total = 0L
        var cycle = 0L

        while (cycle < WARMUP + WINDOW) {
          dut.clockDomain.waitSampling()
          cycle += 1
          if (dut.io.uartTxValid.toBoolean) {
            val c = dut.io.uartTxData.toInt
            uart.append(if (c >= 32 && c < 127) c.toChar else '.')
          }
          if (cycle > WARMUP &&
              dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean) {
            val st = dut.io.debugMemState.toInt
            counts(category.getOrElse(st, s"other(state $st)")) += 1
            total += 1
          }
        }
        res = Result(fieldBits, total, counts.toMap, uart.toString)
        println(f"  acacheFieldBits=$fieldBits (${1 << fieldBits}%d elem/line): " +
                f"$total%,d BMB transactions in ${WINDOW}%,d cycles")
      }
    res
  }

  println("=== jbe.DoApp memory traffic vs array-cache line width ===")
  println(f"BRAM backend, single core, ${WINDOW}%,d cycle window after ${WARMUP}%,d warmup\n")

  // ArrayCache requires fieldBits >= 1, so 2 elements per line is the narrowest
  // the hardware supports — the "one element, no speculation" case is not
  // reachable without changing ArrayCache itself.
  val results = Seq(1, 2, 3).map(run)

  println()
  val cats = results.flatMap(_.byCat.keys).distinct.sortBy(c => -results.map(_.byCat.getOrElse(c, 0L)).sum)
  println("category".padTo(18, ' ') + results.map(r => f"${1 << r.fieldBits}%9d el").mkString)
  println("-" * (18 + 12 * results.size))
  for (c <- cats) {
    println(f"$c%-18s" + results.map(r => f"${r.byCat.getOrElse(c, 0L)}%,12d").mkString)
  }
  println("-" * (18 + 12 * results.size))
  println("TOTAL".padTo(18, ' ') + results.map(r => f"${r.total}%,12d").mkString)
  println()
  val base = results.find(_.fieldBits == 2).get.total
  println("relative to the current default (4 elements/line), lower is better:")
  for (r <- results) {
    println(f"  ${1 << r.fieldBits}%2d elem/line: ${r.total.toDouble / base}%5.2f x")
  }
  println()
  val handle = results.find(_.fieldBits == 2).get
  val hb = handle.byCat.getOrElse("handle deref", 0L) + handle.byCat.getOrElse("bounds check", 0L)
  println(f"At the default: handle deref + bounds check = ${hb}%,d of ${handle.total}%,d " +
          f"= ${hb.toDouble / handle.total * 100}%.1f%% of all traffic")
  println("   -> that fraction is what handle caching would remove.")
}
