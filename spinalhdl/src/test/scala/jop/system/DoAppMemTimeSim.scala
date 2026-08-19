package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.{JopFileLoader, JopSimDefaults}

/**
 * Where does DoApp's memory STALL TIME go?
 *
 * `DoAppAcacheSweepSim` answered a different question: which memory-controller
 * state issued each BMB transaction. It found bytecode fill at 62.3 %, and
 * item 37 has been sitting on that number with an explicit caveat attached --
 * *62 % of transactions is not 62 % of time*, because a method-cache fill is a
 * burst of cheap sequential accesses while a handle dereference is a lone
 * round trip. Optimising the wrong one wastes the effort.
 *
 * This measures time instead. `memBusy` is what stalls the pipeline (the
 * microcode `wait` waits on it), so every cycle it is high is a cycle the core
 * did not retire work, and `debugMemState` says which category owns that
 * cycle. Both are already brought out of `JopCoreLargeBramHarness`, so nothing
 * new is instrumented and nothing about the design changes.
 *
 * Reported side by side with the transaction counts, because the GAP between
 * the two columns is the actual result: a category with many transactions and
 * few stall cycles is cheap, and vice versa.
 *
 * Scaled by item 38's measured stall share (Kfl 53.8 %, UdpIp 54.8 %, Lift
 * 34.0 % of throughput lost to memory), the stall-cycle column bounds what any
 * given memory optimisation can be worth end to end.
 *
 *   sbt "Test/runMain jop.system.DoAppMemTimeSim"
 */
object DoAppMemTimeSim extends App {

  val jopFilePath = "java/apps/JbeBench/JbeBench.jop"   // entry point is jbe.DoApp
  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val bramSize = 2 * 1024 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, bramSize / 4)

  // clkMhz = 5 shrinks DoApp's one-simulated-second calibration 20x, so all
  // THREE benchmarks fit in one run instead of the window covering only Kfl.
  // Per-MHz results are unaffected (see JopCoreLargeBramHarness.clkMhz), and
  // this profile is ratios anyway.
  //
  // That matters for the record: DoAppAcacheSweepSim uses a 12 M-cycle window
  // at the default 100 MHz, which does NOT reach UdpIp or Lift, yet item 37
  // reported its 62.3 % as being "over Kfl + UdpIp + Lift". It was Kfl.
  val CLK_MHZ = 5
  val WARMUP = 2000000L
  val MAX_CYCLES = 90000000L

  // EVERY state named, in BmbMemoryController.State declaration order, so
  // nothing lands in an "other(state N)" bucket. The first cut of this left the
  // wait states unmapped and silently misattributed 13.9 % of the stall time --
  // including LAST at 11.7 %, which turned out to be the second half of every
  // statics access.
  val stateName = Vector(
    "IDLE", "READ_WAIT", "WRITE_WAIT", "IAST_WAIT", "PF_WAIT",
    "HANDLE_READ", "HANDLE_WAIT", "HANDLE_CALC", "HANDLE_ACCESS", "HANDLE_DATA_WAIT",
    "HANDLE_BOUND_READ", "HANDLE_BOUND_WAIT", "NP_EXC", "AB_EXC",
    "BC_CACHE_CHECK", "BC_FILL_R1", "BC_FILL_LOOP", "BC_FILL_CMD",
    "AC_FILL_CMD", "AC_FILL_WAIT",
    "CP_SETUP", "CP_READ", "CP_READ_WAIT", "CP_WRITE", "CP_STOP",
    "ZERO_RUN", "ZERO_WAIT", "FILL_REQ", "FILL_WAIT",
    "GS_READ", "PS_WRITE", "LAST")

  /** Group a state into the cost category it belongs to. */
  def group(st: Int): String = stateName.lift(st).getOrElse(s"?$st") match {
    case "IDLE"                                     => "idle/direct"
    case "READ_WAIT" | "WRITE_WAIT"                 => "idle/direct"
    case n if n startsWith "BC_"                    => "bytecode fill"
    case "GS_READ" | "PS_WRITE" | "LAST"            => "statics"
    case "HANDLE_BOUND_READ" | "HANDLE_BOUND_WAIT"  => "bounds check"
    case "HANDLE_READ" | "HANDLE_WAIT" | "HANDLE_CALC" | "PF_WAIT" => "handle deref"
    case "HANDLE_ACCESS" | "HANDLE_DATA_WAIT" | "IAST_WAIT"        => "element"
    case n if n startsWith "AC_"                    => "A$ line fill"
    case n if n startsWith "CP_"                    => "GC copy"
    case n if n startsWith "ZERO_"                  => "zero fill"
    case n if n startsWith "FILL_"                  => "backend fill"
    case n                                          => n
  }


  JopSimDefaults.config
    .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize,
      acacheFieldBits = 2, clkMhz = CLK_MHZ))
    .doSim("doapp_memtime", 42) { dut =>
      val uart = new StringBuilder
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      // Per-benchmark buckets. DoApp prints each name WITH ITS RESULT, i.e.
      // AFTER that benchmark has run ("Kfl 1048 1/s"), so a marker means "the
      // cycles accumulated since the last marker belong to THIS name". Treating
      // it as a start-of-benchmark switch shifts every profile by one and
      // reports UdpIp's mix as Kfl's -- which the first run of this did.
      val benches = Seq("Kfl", "UdpIp", "Lift")
      val txns  = scala.collection.mutable.Map[(String, String), Long]().withDefaultValue(0L)
      val stall = scala.collection.mutable.Map[(String, String), Long]().withDefaultValue(0L)
      val busyBy = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
      val cycBy  = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
      // Pending = cycles since the previous marker, flushed under the next name.
      val pTxn  = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
      val pStall= scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
      var pBusy = 0L
      var pCyc  = 0L
      def flush(name: String): Unit = {
        pTxn.foreach  { case (c, v) => txns((name, c)) += v }
        pStall.foreach{ case (c, v) => stall((name, c)) += v }
        busyBy(name) += pBusy; cycBy(name) += pCyc
        pTxn.clear(); pStall.clear(); pBusy = 0L; pCyc = 0L
      }
      var line = new StringBuilder
      var cycle = 0L
      var done = false

      while (cycle < MAX_CYCLES && !done) {
        dut.clockDomain.waitSampling()
        cycle += 1
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.')
          if (c == 10) {
            val l = line.toString.trim
            benches.find(b => l.startsWith(b)).foreach { b => flush(b) }
            if (l.contains("JVM exit")) done = true   // NOT "done": matches "GC done" in boot
            line = new StringBuilder
          } else if (c >= 32 && c < 127) line.append(c.toChar)
        }
        if (cycle > WARMUP) {
          val cat = group(dut.io.debugMemState.toInt)
          pCyc += 1
          if (dut.io.memBusy.toBoolean) { pStall(cat) += 1; pBusy += 1 }
          if (dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean) pTxn(cat) += 1
        }
      }

      println()
      println(f"DoApp memory profile, per benchmark (clkMhz=$CLK_MHZ, $cycle%,d cycles)")
      for (b <- benches if cycBy(b) > 0) {
        val tot = cycBy(b); val busy = busyBy(b)
        val tt = txns.filter(_._1._1 == b).values.sum
        println()
        println(f"  == $b%-6s  ${tot}%,d cycles, stalled ${busy}%,d = ${busy * 100.0 / tot}%.1f %%, $tt%,d txns")
        println("     %-14s %9s %7s %11s %8s %8s".format(
          "category", "txns", "txn%", "stall cyc", "stall%", "cyc/txn"))
        val keys = (txns.keys ++ stall.keys).filter(_._1 == b).map(_._2).toSeq.distinct
          .sortBy(k => -stall.getOrElse((b, k), 0L))
        for (k <- keys) {
          val t = txns.getOrElse((b, k), 0L); val st = stall.getOrElse((b, k), 0L)
          if (t > 0 || st > 0)
            println(f"     $k%-14s $t%9d ${if (tt > 0) t * 100.0 / tt else 0.0}%6.1f%% " +
                    f"$st%11d ${if (busy > 0) st * 100.0 / busy else 0.0}%7.1f%% " +
                    f"${if (t > 0) st.toDouble / t else 0.0}%8.2f")
        }
      }
      println()
      println("UART tail: " + uart.toString.takeRight(200))
    }
}
