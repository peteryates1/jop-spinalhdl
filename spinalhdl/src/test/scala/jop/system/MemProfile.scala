package jop.system

/**
 * Shared instrumentation for "where does DoApp's memory stall time go".
 *
 * `memBusy` is what stalls the pipeline, so every cycle it is high is a cycle
 * the core did not retire work, and `debugMemState` says which category owns
 * that cycle. Both live in `BmbMemoryController`, ABOVE the backend, so the
 * identical measurement runs against BRAM, SDR or DDR3 and the results are
 * directly comparable. That is the whole point of factoring this out: the only
 * variable between runs should be the memory system.
 *
 * DDR2 cannot be included. Its controller is `Ddr2BlackBox`, Altera ALTMEMPHY
 * vendor IP with no simulation model, so the A-E115FB can only be profiled on
 * hardware.
 */
object MemProfile {

  /** Every state named, in BmbMemoryController.State declaration order, so
    * nothing lands in an unattributed bucket. Leaving the wait states out lost
    * 13.9 % of the stall time on the first attempt — including LAST at 11.7 %,
    * which is the second half of every statics access. */
  val stateName = Vector(
    "IDLE", "READ_WAIT", "WRITE_WAIT", "IAST_WAIT", "PF_WAIT",
    "HANDLE_READ", "HANDLE_WAIT", "HANDLE_CALC", "HANDLE_ACCESS", "HANDLE_DATA_WAIT",
    "HANDLE_BOUND_READ", "HANDLE_BOUND_WAIT", "NP_EXC", "AB_EXC",
    "BC_CACHE_CHECK", "BC_FILL_R1", "BC_FILL_LOOP", "BC_FILL_CMD",
    "AC_FILL_CMD", "AC_FILL_WAIT",
    "CP_SETUP", "CP_READ", "CP_READ_WAIT", "CP_WRITE", "CP_STOP",
    "ZERO_RUN", "ZERO_WAIT", "FILL_REQ", "FILL_WAIT",
    "GS_READ", "PS_WRITE", "LAST")

  def group(st: Int): String = stateName.lift(st).getOrElse(s"?$st") match {
    case "IDLE" | "READ_WAIT" | "WRITE_WAIT"        => "idle/direct"
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

  /**
   * Per-benchmark accumulator.
   *
   * DoApp prints each benchmark's name WITH ITS RESULT ("Kfl 1048 1/s"), i.e.
   * AFTER that benchmark has run. So a marker means "everything since the last
   * marker belongs to THIS name" — treating it as a start-of-benchmark switch
   * shifts every profile by one and reports UdpIp's mix as Kfl's, which the
   * first version of this did.
   */
  class Buckets(val benches: Seq[String] = Seq("Kfl", "UdpIp", "Lift")) {
    private val txns  = scala.collection.mutable.Map[(String, String), Long]().withDefaultValue(0L)
    private val stall = scala.collection.mutable.Map[(String, String), Long]().withDefaultValue(0L)
    private val busyBy = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
    private val cycBy  = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
    private val pTxn  = scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
    private val pStall= scala.collection.mutable.Map[String, Long]().withDefaultValue(0L)
    private var pBusy = 0L
    private var pCyc  = 0L

    /** One clock. `busy` = memBusy, `fire` = a BMB command handshake. */
    def tick(state: Int, busy: Boolean, fire: Boolean): Unit = {
      val cat = group(state)
      pCyc += 1
      if (busy) { pStall(cat) += 1; pBusy += 1 }
      if (fire) pTxn(cat) += 1
    }

    /** A benchmark result line was seen: attribute everything since the last. */
    def flush(name: String): Unit = {
      pTxn.foreach  { case (c, v) => txns((name, c)) += v }
      pStall.foreach{ case (c, v) => stall((name, c)) += v }
      busyBy(name) += pBusy; cycBy(name) += pCyc
      pTxn.clear(); pStall.clear(); pBusy = 0L; pCyc = 0L
    }

    /** Feed each completed UART line; returns true once the run is finished. */
    def onLine(l: String): Boolean = {
      benches.find(b => l.startsWith(b)).foreach(flush)
      l.contains("JVM exit")   // NOT "done": that matches "GC done" during boot
    }

    def report(backend: String, cycles: Long): Unit = {
      println()
      println(f"DoApp memory stall profile — backend: $backend%s ($cycles%,d cycles)")
      for (b <- benches if cycBy(b) > 0) {
        val tot = cycBy(b); val busy = busyBy(b)
        val tt = txns.filter(_._1._1 == b).values.sum
        println()
        println(f"  == $b%-6s ${tot}%,d cycles, stalled ${busy}%,d = ${busy * 100.0 / tot}%.1f %%, $tt%,d txns")
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
    }
  }
}
