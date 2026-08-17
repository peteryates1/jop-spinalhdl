package jop.ddr3

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

/**
 * The DDR3 twin of `CacheDdr2MshrSim`: a non-blocking `LruCacheCore` on the real
 * `CacheToMigAdapter`, against a behavioural MIG user interface.
 *
 * The adapter used to serialise reads — `IDLE -> ISSUE_READ -> WAIT_READ`, one
 * at a time — so it, not the cache, was the limit on the DDR3 boards. This
 * measures whether making it multi-outstanding actually moves that, and checks
 * the ordering discipline that makes it safe.
 *
 * The model reproduces the two MIG behaviours the adapter has to respect:
 *  - `app_rd_data_valid` is a ONE-CYCLE PULSE that cannot be back-pressured, so
 *    dropping it loses data permanently
 *  - `app_rdy` and `app_wdf_rdy` deassert independently and at random (refresh,
 *    full command FIFO), and a write needs both in the same cycle
 *
 * Ordering is STRICT on every board here, so read data comes back in issue
 * order and responses can be matched by position. What is NOT safe is letting a
 * write acknowledgement — manufactured locally, since the UI returns nothing for
 * a write — overtake a read that was issued earlier. Every read is checked
 * against a golden model, so that shows up as wrong data rather than a hang.
 *
 *   sbt "Test/runMain jop.ddr3.CacheMigMshrSim"
 */
object CacheMigMshrSim extends App {

  val ADDR_W = 28
  val DATA_W = 128 // the MIG app-interface width; the adapter hardcodes it
  val SETS = 16
  val WAYS = 2
  val LINE_BYTES = DATA_W / 8
  val WORD_SHIFT = log2Up(LINE_BYTES)
  val MAX_IDS = 8
  val WALK_LINES = 128 // 4x the cache, so revisits miss

  val LAT_MIN = 16
  val LAT_SPAN = 9
  val TARGET = 500

  /** The id space is mshrCount: idWidth = log2Up(mshrCount). */
  def idsFor(mshrCount: Int): Int = mshrCount

  class Dut(mshrCount: Int, adapterOutstanding: Int = 0) extends Component {
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = ADDR_W, dataWidth = DATA_W, setCount = SETS, wayCount = WAYS,
      idWidth = log2Up(mshrCount), mshrCount = mshrCount))
    // Two commands per miss — an eviction and a refill — so the adapter is sized
    // the way MemoryControllerFactory sizes it.
    val adapter = new CacheToMigAdapter(ADDR_W,
      maxOutstanding = if (adapterOutstanding > 0) adapterOutstanding else (2 * mshrCount) max 2)

    val io = new Bundle {
      val frontend = slave(CacheFrontend(ADDR_W, DATA_W, log2Up(mshrCount)))
      val app_rdy           = in Bool()
      val app_wdf_rdy       = in Bool()
      val app_rd_data       = in Bits(DATA_W bits)
      val app_rd_data_valid = in Bool()
      val app_addr     = out Bits(ADDR_W bits)
      val app_cmd      = out Bits(3 bits)
      val app_en       = out Bool()
      val app_wdf_data = out Bits(DATA_W bits)
      val app_wdf_mask = out Bits(LINE_BYTES bits)
      val app_wdf_wren = out Bool()
    }

    cache.io.frontend.req << io.frontend.req
    io.frontend.rsp << cache.io.frontend.rsp

    adapter.io.cmd.valid         := cache.io.memCmd.valid
    adapter.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
    adapter.io.cmd.payload.write := cache.io.memCmd.payload.write
    adapter.io.cmd.payload.wdata := cache.io.memCmd.payload.data
    adapter.io.cmd.payload.wmask := cache.io.memCmd.payload.mask
    cache.io.memCmd.ready        := adapter.io.cmd.ready

    cache.io.memRsp.valid         := adapter.io.rsp.valid
    cache.io.memRsp.payload.data  := adapter.io.rsp.payload.rdata
    cache.io.memRsp.payload.error := adapter.io.rsp.payload.error
    adapter.io.rsp.ready          := cache.io.memRsp.ready

    adapter.io.app_rdy           := io.app_rdy
    adapter.io.app_wdf_rdy       := io.app_wdf_rdy
    adapter.io.app_rd_data       := io.app_rd_data
    adapter.io.app_rd_data_valid := io.app_rd_data_valid
    io.app_addr     := adapter.io.app_addr
    io.app_cmd      := adapter.io.app_cmd
    io.app_en       := adapter.io.app_en
    io.app_wdf_data := adapter.io.app_wdf_data
    io.app_wdf_mask := adapter.io.app_wdf_mask
    io.app_wdf_wren := adapter.io.app_wdf_wren
  }

  def seedLine(line: Int): BigInt = {
    var v = BigInt(0)
    for (w <- 0 until DATA_W / 32) v |= BigInt((line * 4 + w) * 0x01010101L & 0xFFFFFFFFL) << (w * 32)
    v
  }
  val lineMask = (BigInt(1) << DATA_W) - 1

  def run(mshrCount: Int, seed: Int, adapterOutstanding: Int = 0, tag: String = ""): (Double, Int) = {
    var cyclesPerReq = 0.0
    var errors = 0
    val IDS = idsFor(mshrCount)
    val label = if (tag.nonEmpty) tag else s"mshr=$mshrCount"

    SimConfig.compile(new Dut(mshrCount, adapterOutstanding)).doSim(s"mig_mshr_${mshrCount}_$adapterOutstanding", seed) { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.frontend.req.valid #= false
      dut.io.frontend.req.payload.addr #= 0
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= (BigInt(1) << LINE_BYTES) - 1
      if (dut.io.frontend.req.payload.id != null) dut.io.frontend.req.payload.id #= 0
      dut.io.frontend.rsp.ready #= true
      dut.io.app_rdy #= false
      dut.io.app_wdf_rdy #= false
      dut.io.app_rd_data #= 0
      dut.io.app_rd_data_valid #= false
      dut.clockDomain.waitSampling(5)

      val rnd = new Random(seed)

      val dram = mutable.Map[Int, BigInt]()
      val golden = mutable.Map[Int, BigInt]()
      def dramOf(l: Int): BigInt = dram.getOrElseUpdate(l, seedLine(l))
      def goldenOf(l: Int): BigInt = golden.getOrElseUpdate(l, seedLine(l))

      val readPipe = mutable.Queue[(Long, BigInt)]()

      case class Pending(line: Int, isWrite: Boolean, expect: BigInt)
      val pending = mutable.Map[Int, Pending]()
      val issueCount = Array.fill(MAX_IDS)(0)

      var cycle = 0L
      var completions = 0
      var issued = 0
      var firstIssue = -1L
      var lastCompletion = 0L
      var presented: Option[(Int, Int, Boolean, BigInt, BigInt)] = None

      def fail(msg: String): Unit = {
        if (errors < 10) println(s"  FAIL @$cycle: $msg")
        errors += 1
      }

      dut.clockDomain.onSamplings {
        // ---- MIG user interface model ----
        // Read the ready signals the DUT actually saw at this edge.
        val rdy = dut.io.app_rdy.toBoolean
        val wdfRdy = dut.io.app_wdf_rdy.toBoolean
        if (dut.io.app_en.toBoolean && rdy) {
          val a = dut.io.app_addr.toBigInt
          val line = (a / LINE_BYTES).toInt
          val isWrite = dut.io.app_cmd.toBigInt == 0
          if (isWrite) {
            if (!dut.io.app_wdf_wren.toBoolean || !wdfRdy) {
              fail("write command accepted without its write data (app_wdf_wren/app_wdf_rdy)")
            } else {
              val m = dut.io.app_wdf_mask.toBigInt   // MIG mask: 1 = do NOT write
              val wdata = dut.io.app_wdf_data.toBigInt
              var v = dramOf(line)
              for (b <- 0 until LINE_BYTES) {
                if (((m >> b) & 1) == 0) {
                  val bm = BigInt(0xFF) << (b * 8)
                  v = (v & ~bm) | (wdata & bm)
                }
              }
              dram(line) = v & lineMask
            }
          } else {
            if (dut.io.app_wdf_wren.toBoolean) fail("read command asserted app_wdf_wren")
            readPipe.enqueue((cycle + LAT_MIN + rnd.nextInt(LAT_SPAN), dramOf(line)))
          }
        }

        // Read data: in order, one-cycle pulse, no back-pressure.
        var fire = false
        if (readPipe.nonEmpty && readPipe.head._1 <= cycle) {
          val (_, d) = readPipe.dequeue()
          dut.io.app_rd_data #= d
          fire = true
        }
        dut.io.app_rd_data_valid #= fire
        dut.io.app_rdy #= rnd.nextInt(100) < 80
        dut.io.app_wdf_rdy #= rnd.nextInt(100) < 85

        // ---- frontend responses ----
        if (dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean) {
          val id = if (dut.io.frontend.rsp.payload.id != null) dut.io.frontend.rsp.payload.id.toInt else 0
          val data = dut.io.frontend.rsp.payload.data.toBigInt
          if (dut.io.frontend.rsp.payload.error.toBoolean) fail(s"id $id returned an error")
          pending.remove(id) match {
            case None => fail(s"response for id $id which had nothing outstanding")
            case Some(p) =>
              if (!p.isWrite && data != p.expect) {
                fail(f"id $id line ${p.line} read 0x$data%x, expected 0x${p.expect}%x")
              }
          }
          completions += 1
          lastCompletion = cycle
        }

        // ---- frontend requests: one per id, lines owned per id ----
        if (presented.isDefined &&
            dut.io.frontend.req.valid.toBoolean && dut.io.frontend.req.ready.toBoolean) {
          val (id, line, isWrite, wdata, keep) = presented.get
          if (isWrite) {
            var v = goldenOf(line)
            for (b <- 0 until LINE_BYTES) {
              if (((keep >> b) & 1) == 0) {
                val bm = BigInt(0xFF) << (b * 8)
                v = (v & ~bm) | (wdata & bm)
              }
            }
            golden(line) = v & lineMask
            pending(id) = Pending(line, isWrite = true, BigInt(0))
          } else {
            pending(id) = Pending(line, isWrite = false, goldenOf(line))
          }
          issued += 1
          if (firstIssue < 0) firstIssue = cycle
          presented = None
        }

        if (presented.isEmpty && issued < TARGET + IDS) {
          val free = (0 until IDS).filter(i => !pending.contains(i))
          if (free.nonEmpty) {
            val id = free(rnd.nextInt(free.length))
            val n = issueCount(id)
            issueCount(id) = n + 1
            val line = id + MAX_IDS * (n % (WALK_LINES / MAX_IDS))
            // Writes are frequent so that dirty evictions keep putting a write
            // into the backend alongside an outstanding read.
            val isWrite = n % 3 != 0
            val keep: BigInt =
              if (!isWrite) (BigInt(1) << LINE_BYTES) - 1
              else if (n % 2 == 0) BigInt(0)
              else (BigInt(1) << (LINE_BYTES / 2)) - 1
            presented = Some((id, line, isWrite, BigInt(DATA_W, rnd), keep))
          }
        }

        presented match {
          case Some((id, line, isWrite, wdata, keep)) =>
            dut.io.frontend.req.valid #= true
            dut.io.frontend.req.payload.addr #= BigInt(line) << WORD_SHIFT
            dut.io.frontend.req.payload.write #= isWrite
            dut.io.frontend.req.payload.data #= wdata
            dut.io.frontend.req.payload.mask #= keep
            if (dut.io.frontend.req.payload.id != null) dut.io.frontend.req.payload.id #= id
          case None =>
            dut.io.frontend.req.valid #= false
        }

        cycle += 1
      }

      val limit = 400000
      while (completions < TARGET && cycle < limit) dut.clockDomain.waitSampling(100)

      assert(completions >= TARGET,
        s"mshr=$mshrCount: only $completions of $TARGET completions in $cycle cycles")
      cyclesPerReq = (lastCompletion - firstIssue).toDouble / completions
      println(f"  $label%-22s $cyclesPerReq%6.2f cycles/completion  " +
              f"($completions completions, $errors errors)")
    }

    (cyclesPerReq, errors)
  }

  println("=== LruCacheCore + CacheToMigAdapter, overlapping misses ===")
  println(s"MIG model: STRICT ordering, one-cycle unstallable read pulse, " +
          s"$LAT_MIN-${LAT_MIN + LAT_SPAN - 1} cycle latency, app_rdy/app_wdf_rdy drop at random")

  val results = Seq(1, 2, 4).map { k =>
    val (c, e) = run(k, seed = 9)
    assert(e == 0, s"mshr=$k: $e errors — response ordering through the MIG adapter")
    k -> c
  }
  val base = results.head._2
  println()
  println("  MSHRs   cycles/req   speedup")
  for ((k, c) <- results) println(f"  $k%5d   $c%10.2f   ${base / c}%6.2f x")

  val fourWay = results.toMap.apply(4)
  assert(base / fourWay > 1.8,
    f"4 MSHRs should clearly beat 1 through the MIG adapter, measured ${base / fourWay}%.2f x")

  // CONTROL: 4 MSHRs behind a deliberately 1-deep adapter — which is what the
  // old CacheToMigAdapter effectively was for reads. This separates the two
  // sources of the speedup above, and the split is worth knowing:
  //
  //   MSHRs alone, serial backend   -> some gain, because the cache no longer
  //                                    blocks on a miss and can serve hits under
  //                                    it, and evictions stop waiting
  //   MSHRs + a deep enough adapter -> the rest, and it is the larger part
  //
  // So "the adapter was the DDR3 limit" is measurable here rather than asserted.
  println()
  val (starved, starvedErr) = run(4, seed = 9, adapterOutstanding = 1,
    tag = "mshr=4, adapter=1")
  assert(starvedErr == 0, s"starved-adapter control: $starvedErr errors")
  assert(starved > fourWay * 2,
    f"a 1-deep adapter must lose most of the 4-MSHR gain, " +
    f"measured $starved%.2f vs $fourWay%.2f cycles/req")
  assert(starved < base,
    f"4 MSHRs should still beat 1 even on a serial backend (hits served under " +
    f"misses), measured $starved%.2f vs $base%.2f")
  println(f"  control: 4 MSHRs on a 1-DEEP adapter gives $starved%.2f cycles/req " +
          f"(${base / starved}%.2f x of 1 MSHR), against ${fourWay}%.2f " +
          f"(${base / fourWay}%.2f x) on a properly sized one.")
  println(f"  => the cache alone is worth ${base / starved}%.2f x; the adapter " +
          f"carries the remaining ${starved / fourWay}%.2f x.")

  println()
  println("MIG MSHR INTEGRATION OK")
}
