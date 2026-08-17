package jop.ddr2

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.ddr3.{CacheConfig, CacheFrontend, LruCacheCore}

import scala.collection.mutable
import scala.util.Random

/**
 * The exact composition that goes to the A-E115FB: a non-blocking `LruCacheCore`
 * on top of the real `CacheToDdr2Adapter`, against a behavioural ALTMEMPHY.
 *
 * This exists because neither component's own sim can catch what happens
 * between them. `LruCacheCoreMshrSim` models the backend with ONE latency for
 * reads and writes, so its responses are always in command order. The real
 * controller is not like that: a write is acknowledged the cycle it is
 * accepted, a read comes back tens of cycles later. With one command in flight
 * that difference is invisible, which is why it survived until now — but the
 * moment two misses overlap, an eviction issued after an earlier refill has its
 * acknowledgement ready first, and `LruCacheCore` matches responses to commands
 * BY ORDER. One response out of place and a miss is filled with whatever was on
 * `local_rdata`. Same failure as the AlteraSdramAdapter bug (ef36d99).
 *
 * So the model here deliberately reproduces the asymmetry:
 *  - writes: accepted and finished, nothing returned by the controller
 *  - reads: `local_rdata_valid` some cycles later, in order, UNSTALLABLE
 *  - `local_ready` drops at random, covering command and write data together
 *
 * Every read is checked against a golden model, so a mis-ordered response shows
 * up as wrong data rather than as a hang.
 *
 *   sbt "Test/runMain jop.ddr2.CacheDdr2MshrSim"
 */
object CacheDdr2MshrSim extends App {

  val ADDR_W = 16
  val DATA_W = 256 // 32-byte line: the half-rate local word and the BL=4 burst
  val SETS = 16
  val WAYS = 2
  // The id space IS mshrCount: idWidth = log2Up(mshrCount), so at 1 MSHR the
  // frontend is untagged and the master must keep ONE request outstanding —
  // exactly the constraint LruCacheCore enforces for an id-less frontend.
  def idsFor(mshrCount: Int): Int = mshrCount
  val MAX_IDS = 8
  val LINE_BYTES = DATA_W / 8
  val WORD_SHIFT = log2Up(LINE_BYTES)
  val WALK_LINES = 128 // 4x the cache, so revisits miss

  val LAT_MIN = 18
  val LAT_SPAN = 9
  val TARGET = 500

  class Dut(mshrCount: Int) extends Component {
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = ADDR_W, dataWidth = DATA_W, setCount = SETS, wayCount = WAYS,
      idWidth = log2Up(mshrCount), mshrCount = mshrCount))
    // rspDepth 8 is the real part's setting, and it is exactly 2 * 4 MSHRs —
    // an eviction and a refill each.
    val adapter = new CacheToDdr2Adapter(ADDR_W, DATA_W, rspDepth = 8)

    val io = new Bundle {
      val frontend = slave(CacheFrontend(ADDR_W, DATA_W, log2Up(mshrCount)))
      val local_ready       = in Bool()
      val local_rdata       = in Bits(DATA_W bits)
      val local_rdata_valid = in Bool()
      val local_init_done   = in Bool()
      val local_address   = out Bits(adapter.localAddrWidth bits)
      val local_write_req = out Bool()
      val local_read_req  = out Bool()
      val local_wdata     = out Bits(DATA_W bits)
      val local_be        = out Bits(LINE_BYTES bits)
    }

    cache.io.frontend.req << io.frontend.req
    io.frontend.rsp << cache.io.frontend.rsp
    adapter.io.cmd << cache.io.memCmd
    cache.io.memRsp << adapter.io.rsp

    adapter.io.local_ready       := io.local_ready
    adapter.io.local_rdata       := io.local_rdata
    adapter.io.local_rdata_valid := io.local_rdata_valid
    adapter.io.local_init_done   := io.local_init_done
    io.local_address   := adapter.io.local_address
    io.local_write_req := adapter.io.local_write_req
    io.local_read_req  := adapter.io.local_read_req
    io.local_wdata     := adapter.io.local_wdata
    io.local_be        := adapter.io.local_be
  }

  def seedLine(line: Int): BigInt = {
    var v = BigInt(0)
    for (w <- 0 until DATA_W / 32) v |= BigInt((line * 8 + w) * 0x01010101L & 0xFFFFFFFFL) << (w * 32)
    v
  }
  val lineMask = (BigInt(1) << DATA_W) - 1

  def run(mshrCount: Int, seed: Int): (Double, Int) = {
    var cyclesPerReq = 0.0
    var errors = 0

    val IDS = idsFor(mshrCount)

    SimConfig.compile(new Dut(mshrCount)).doSim(s"ddr2_mshr_$mshrCount", seed) { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.frontend.req.valid #= false
      dut.io.frontend.req.payload.addr #= 0
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= (BigInt(1) << LINE_BYTES) - 1
      if (dut.io.frontend.req.payload.id != null) dut.io.frontend.req.payload.id #= 0
      dut.io.frontend.rsp.ready #= true
      dut.io.local_ready #= false
      dut.io.local_rdata #= 0
      dut.io.local_rdata_valid #= false
      dut.io.local_init_done #= false
      dut.clockDomain.waitSampling(5)
      dut.io.local_init_done #= true

      val rnd = new Random(seed)

      // DRAM contents, and what a frontend read of a line must return.
      val dram = mutable.Map[Int, BigInt]()
      val golden = mutable.Map[Int, BigInt]()
      def dramOf(l: Int): BigInt = dram.getOrElseUpdate(l, seedLine(l))
      def goldenOf(l: Int): BigInt = golden.getOrElseUpdate(l, seedLine(l))

      // Read data in flight. In order, and it CANNOT be back-pressured.
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
        // ---- ALTMEMPHY model ----
        // Use the local_ready the DUT ACTUALLY SAW at this edge, or the model
        // accepts commands the adapter never issued.
        val ready = dut.io.local_ready.toBoolean
        val wr = dut.io.local_write_req.toBoolean
        val rd = dut.io.local_read_req.toBoolean
        if (ready && (wr || rd)) {
          val a = dut.io.local_address.toBigInt.toInt
          if (wr) {
            // Finished on acceptance: the controller returns NOTHING for a
            // write. The adapter is what owes the cache an acknowledgement.
            val be = dut.io.local_be.toBigInt
            val wdata = dut.io.local_wdata.toBigInt
            var v = dramOf(a)
            for (b <- 0 until LINE_BYTES) {
              if (((be >> b) & 1) == 1) {
                val m = BigInt(0xFF) << (b * 8)
                v = (v & ~m) | (wdata & m)
              }
            }
            dram(a) = v & lineMask
          } else {
            readPipe.enqueue((cycle + LAT_MIN + rnd.nextInt(LAT_SPAN), dramOf(a)))
          }
        }

        // Read data returns in order and unstallably.
        var fire = false
        if (readPipe.nonEmpty && readPipe.head._1 <= cycle) {
          val (_, d) = readPipe.dequeue()
          dut.io.local_rdata #= d
          fire = true
        }
        dut.io.local_rdata_valid #= fire
        dut.io.local_ready #= rnd.nextInt(100) < 75

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

        // ---- frontend requests: one per id, so each id's own accesses order ----
        if (presented.isDefined &&
            dut.io.frontend.req.valid.toBoolean && dut.io.frontend.req.ready.toBoolean) {
          val (id, line, isWrite, wdata, keep) = presented.get
          if (isWrite) {
            var v = goldenOf(line)
            for (b <- 0 until LINE_BYTES) {
              if (((keep >> b) & 1) == 0) {
                val m = BigInt(0xFF) << (b * 8)
                v = (v & ~m) | (wdata & m)
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
            // Lines are owned per id, so no two ids race on one line. Writes are
            // frequent: every dirty eviction is what puts a write into the
            // backend alongside an outstanding read.
            val line = id + MAX_IDS * (n % (WALK_LINES / MAX_IDS))
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
      println(f"  mshr=$mshrCount%-2d  $cyclesPerReq%6.2f cycles/completion  " +
              f"($completions completions, $errors errors)")
    }

    (cyclesPerReq, errors)
  }

  println("=== LruCacheCore + CacheToDdr2Adapter, overlapping misses ===")
  println(s"ALTMEMPHY model: writes finish on acceptance, reads return in " +
          s"$LAT_MIN-${LAT_MIN + LAT_SPAN - 1} cycles and cannot be stalled")

  val results = Seq(1, 2, 4).map { k =>
    val (c, e) = run(k, seed = 5)
    assert(e == 0, s"mshr=$k: $e data errors — response ordering through the adapter")
    k -> c
  }
  val base = results.head._2
  println()
  println("  MSHRs   cycles/req   speedup")
  for ((k, c) <- results) println(f"  $k%5d   $c%10.2f   ${base / c}%6.2f x")

  val fourWay = results.toMap.apply(4)
  assert(base / fourWay > 1.8,
    f"4 MSHRs should clearly beat 1 through the real adapter, measured ${base / fourWay}%.2f x")

  println()
  println("DDR2 MSHR INTEGRATION OK")
}
