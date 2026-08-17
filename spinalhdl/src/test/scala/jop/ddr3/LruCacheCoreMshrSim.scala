package jop.ddr3

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable
import scala.util.Random

/**
 * Does the MSHR file actually overlap misses?
 *
 * `JbeScale` showed multicore DRAM throughput flattening at ~1.8x because
 * LruCacheCore held its FSM for a whole DRAM round trip per miss. This drives
 * the cache directly with a tagged frontend and a perfectly pipelined memory
 * model, on a working set far larger than the cache so that nearly every access
 * misses, and measures what one more MSHR is worth.
 *
 * Three phases:
 *  - throughput: read-only line walk, mshrCount swept 1/2/4/8
 *  - correctness: mixed reads and partial/full-line writes, each id owning its
 *    own lines, so write-back through eviction is verified under overlap
 *  - conflict: every id aimed at ONE set with different tags, which is the case
 *    the design refuses to serve concurrently — it must replay, not lose or
 *    duplicate a response
 *
 * Completions are counted, never acceptances: the cache has a 4-deep input FIFO
 * that keeps taking requests after the pipeline behind it has stopped, which is
 * how CacheDdr2EvictSim once reported PASS while deadlocked.
 *
 *   sbt "Test/runMain jop.ddr3.LruCacheCoreMshrSim"
 */
object LruCacheCoreMshrSim extends App {

  val ADDR_W = 16
  val LINE_W = 128
  val SETS = 16
  val WAYS = 2
  val ID_W = 3
  val IDS = 1 << ID_W // 8 requests may be in flight; mshrCount is the limiter

  val LINE_BYTES = LINE_W / 8
  val CAPACITY_LINES = SETS * WAYS
  val WALK_LINES = 256 // 8x the cache, so a revisit always misses
  val LATENCY = 40

  def cfg(mshrCount: Int) = CacheConfig(
    addrWidth = ADDR_W, dataWidth = LINE_W, setCount = SETS, wayCount = WAYS,
    idWidth = ID_W, mshrCount = mshrCount)

  def seedLine(line: Int): BigInt = {
    var v = BigInt(0)
    for (w <- 0 until LINE_W / 32) v |= BigInt((line * 4 + w) * 0x01010101L & 0xFFFFFFFFL) << (w * 32)
    v
  }
  val lineMask = (BigInt(1) << LINE_W) - 1

  /** One request the testbench is waiting on. */
  case class Pending(line: Int, isWrite: Boolean, expect: BigInt)

  /**
   * @param addrOf   which line the n-th request from id `i` should touch
   * @param writeAt  whether that request is a write
   * @return (cycles per completion, error count)
   */
  def run(mshrCount: Int, target: Int, name: String,
          addrOf: (Int, Int) => Int,
          writeAt: (Int, Int) => Boolean,
          seed: Int): (Double, Int) = {
    var cyclesPerReq = 0.0
    var errorCount = 0

    SimConfig.compile(new LruCacheCore(cfg(mshrCount))).doSim(s"${name}_$mshrCount", seed) { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.frontend.req.valid #= false
      dut.io.frontend.req.payload.addr #= 0
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= lineMask >> (LINE_W - LINE_BYTES) // all keep
      dut.io.frontend.req.payload.id #= 0
      dut.io.frontend.rsp.ready #= true
      dut.io.memCmd.ready #= true
      dut.io.memRsp.valid #= false
      dut.io.memRsp.payload.data #= 0
      dut.io.memRsp.payload.error #= false
      dut.clockDomain.waitSampling(5)

      val rnd = new Random(seed)

      // Golden contents: what a frontend read of a line must return. Updated
      // when a write is ISSUED, which is exact because each id keeps one
      // request outstanding and (except in the conflict phase) owns its lines.
      val golden = mutable.Map[Int, BigInt]()
      // Backend DRAM, seeded identically. Written only by evictions, so a
      // refill returning the right value is itself the write-back check.
      val dram = mutable.Map[Int, BigInt]()
      def goldenOf(l: Int): BigInt = golden.getOrElseUpdate(l, seedLine(l))
      def dramOf(l: Int): BigInt = dram.getOrElseUpdate(l, seedLine(l))

      // Backend: accepts every cycle, fixed latency, in-order — like the real
      // adapters, which return one response per command including writes.
      val memPipe = mutable.Queue[(Long, BigInt)]()

      val pending = mutable.Map[Int, Pending]()
      val issueCount = Array.fill(IDS)(0)

      var cycle = 0L
      var completions = 0
      var issued = 0
      var firstIssue = -1L
      var lastCompletion = 0L
      var offered: Option[(Long, BigInt)] = None

      // Request currently presented, held until accepted.
      var presented: Option[(Int, Int, Boolean, BigInt, BigInt, BigInt)] = None

      def fail(msg: String): Unit = {
        if (errorCount < 10) println(s"  FAIL @$cycle: $msg")
        errorCount += 1
      }

      dut.clockDomain.onSamplings {
        // ---- backend ----
        if (dut.io.memCmd.valid.toBoolean && dut.io.memCmd.ready.toBoolean) {
          val addr = dut.io.memCmd.payload.addr.toBigInt
          val line = (addr / LINE_BYTES).toInt
          if (dut.io.memCmd.payload.write.toBoolean) {
            val keep = dut.io.memCmd.payload.mask.toBigInt
            val wdata = dut.io.memCmd.payload.data.toBigInt
            var v = dramOf(line)
            for (b <- 0 until LINE_BYTES) {
              if (((keep >> b) & 1) == 0) {
                val m = BigInt(0xFF) << (b * 8)
                v = (v & ~m) | (wdata & m)
              }
            }
            dram(line) = v & lineMask
            memPipe.enqueue((cycle + LATENCY, BigInt(0))) // eviction ack
          } else {
            memPipe.enqueue((cycle + LATENCY, dramOf(line)))
          }
        }

        if (offered.isDefined && dut.io.memRsp.valid.toBoolean && dut.io.memRsp.ready.toBoolean) {
          offered = None
        }
        if (offered.isEmpty && memPipe.nonEmpty && memPipe.head._1 <= cycle) {
          offered = Some(memPipe.dequeue())
        }
        offered match {
          case Some((_, d)) =>
            dut.io.memRsp.valid #= true
            dut.io.memRsp.payload.data #= d
          case None =>
            dut.io.memRsp.valid #= false
        }

        // ---- frontend responses ----
        if (dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean) {
          val id = dut.io.frontend.rsp.payload.id.toInt
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

        // ---- frontend requests: one outstanding per id ----
        if (presented.isDefined &&
            dut.io.frontend.req.valid.toBoolean && dut.io.frontend.req.ready.toBoolean) {
          val (id, line, isWrite, wdata, keep, _) = presented.get
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

        if (presented.isEmpty && issued < target + IDS) {
          val free = (0 until IDS).filter(i => !pending.contains(i))
          if (free.nonEmpty) {
            val id = free(rnd.nextInt(free.length))
            val n = issueCount(id)
            issueCount(id) = n + 1
            val line = addrOf(id, n)
            val isWrite = writeAt(id, n)
            // Alternate full-line and partial writes: a partial write miss has
            // to refill and merge, a full-line one installs without reading.
            val keep: BigInt =
              if (!isWrite) (BigInt(1) << LINE_BYTES) - 1
              else if (n % 2 == 0) BigInt(0)
              else (BigInt(1) << (LINE_BYTES / 2)) - 1
            val wdata = BigInt(LINE_W, rnd)
            presented = Some((id, line, isWrite, wdata, keep, BigInt(0)))
          }
        }

        presented match {
          case Some((id, line, isWrite, wdata, keep, _)) =>
            dut.io.frontend.req.valid #= true
            dut.io.frontend.req.payload.addr #= BigInt(line) * LINE_BYTES
            dut.io.frontend.req.payload.write #= isWrite
            dut.io.frontend.req.payload.data #= wdata
            dut.io.frontend.req.payload.mask #= keep
            dut.io.frontend.req.payload.id #= id
          case None =>
            dut.io.frontend.req.valid #= false
        }

        cycle += 1
      }

      val limit = 400000
      while (completions < target && cycle < limit) dut.clockDomain.waitSampling(100)

      assert(completions >= target,
        s"$name mshr=$mshrCount: only $completions of $target completions in $cycle cycles " +
        "(completions, not acceptances — the 4-deep cmdFifo keeps accepting past a stall)")

      cyclesPerReq = (lastCompletion - firstIssue).toDouble / completions
      println(f"  $name%-10s mshr=$mshrCount%-2d  $cyclesPerReq%6.2f cycles/completion  " +
              f"($completions completions, $errorCount errors)")
    }

    (cyclesPerReq, errorCount)
  }

  println("=== LruCacheCore non-blocking misses ===")
  println(s"cache ${SETS}x$WAYS lines of $LINE_BYTES B = ${CAPACITY_LINES * LINE_BYTES} B; " +
          s"walking $WALK_LINES lines defeats it, backend latency $LATENCY cycles pipelined")

  // --- Phase 1: throughput on an all-miss read walk ---
  // Each id walks its own quarter of the space so the requests in flight are
  // to different sets: this measures overlap, not the conflict replay.
  val walkAddr = (id: Int, n: Int) => ((n * IDS + id) % WALK_LINES)
  val results = Seq(1, 2, 4, 8).map { k =>
    val (c, e) = run(k, target = 400, name = "walk", walkAddr, (_, _) => false, seed = 7)
    assert(e == 0, s"walk mshr=$k: $e errors")
    k -> c
  }
  val base = results.head._2

  println()
  println("  MSHRs   cycles/req   speedup")
  for ((k, c) <- results) println(f"  $k%5d   $c%10.2f   ${base / c}%6.2f x")

  // --- Phase 2: mixed read/write correctness under overlap ---
  // Lines are partitioned by id, so each id's own accesses are ordered and no
  // two ids touch the same line. Writes here must survive eviction to DRAM and
  // come back correct on the next miss to that line.
  val ownedAddr = (id: Int, n: Int) => id + IDS * (n % (WALK_LINES / IDS))
  val (_, mixErrors) = run(4, target = 600, name = "mixed", ownedAddr,
    (_, n) => n % 3 != 0, seed = 11)
  assert(mixErrors == 0, s"mixed read/write: $mixErrors errors")

  // --- Phase 3: every id aimed at one set ---
  // Same index, different tags: exactly what CHECK_HIT refuses to serve while a
  // fill for that set is in flight. It must replay each one, and every request
  // must still get exactly one correct response.
  val oneSetAddr = (id: Int, n: Int) => SETS * ((n * IDS + id) % (WALK_LINES / SETS))
  val (conflictCycles, conflictErrors) =
    run(4, target = 300, name = "one-set", oneSetAddr, (_, n) => n % 4 == 0, seed = 13)
  assert(conflictErrors == 0, s"single-set conflict: $conflictErrors errors")

  println()
  val fourWay = results.toMap.apply(4)
  assert(base / fourWay > 2.5,
    f"4 MSHRs should give well over 2x, measured ${base / fourWay}%.2f x " +
    f"($base%.2f -> $fourWay%.2f cycles/req)")
  val eightWay = results.toMap.apply(8)
  assert(eightWay <= fourWay,
    f"8 MSHRs should not be worse than 4, measured $eightWay%.2f vs $fourWay%.2f")
  // Serialising every miss into one set must cost throughput, or the conflict
  // replay is not actually happening and the set-conflict rule is a no-op.
  assert(conflictCycles > fourWay,
    f"single-set traffic should serialise, measured $conflictCycles%.2f vs $fourWay%.2f cycles/req")

  println("MSHR CONCURRENCY OK")
}
