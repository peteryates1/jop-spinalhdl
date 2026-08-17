package jop.ddr3

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._

import scala.collection.mutable
import scala.util.Random

/**
 * Does BmbCacheBridge actually overlap requests?
 *
 * The bridge is the gate on the whole non-blocking-cache effort: while it holds
 * a single `pendingRsp` it will not present the cache a second request, so an
 * MSHR file behind it would sit idle. This drives it against a cache stub that
 * is trivially fast and perfectly pipelined — every request completes after a
 * random 20-30 cycles, independently of every other — so the only thing that
 * can limit throughput is the bridge itself.
 *
 * What it measures is COMPLETIONS, not acceptances. CacheDdr2EvictSim's first
 * version reported PASS while deadlocked because it watched `req.ready`, and a
 * 4-deep input FIFO keeps accepting long after the pipeline behind it stops.
 * That trap gets worse, not better, once requests overlap.
 *
 * Correctness is checked on every response as well as throughput:
 *  - read data must be the addressed 32-bit lane of the line, which only works
 *    if the per-slot laneSelect was stored and recalled correctly
 *  - source and context must come back with the right response
 *  - writes must answer with zero data
 * The stub deliberately returns responses OUT OF ORDER (random per-request
 * latency), so any of these failing means ids are not being matched.
 *
 *   sbt "Test/runMain jop.ddr3.BmbCacheBridgeOutstandingSim"
 */
object BmbCacheBridgeOutstandingSim extends App {

  val CACHE_ADDR_W = 16
  val LINE_W = 128
  val BMB_DATA_W = 32
  val LANES = LINE_W / BMB_DATA_W
  val SOURCES = 8 // BmbMemoryController allows one transaction per core
  val CONTEXT_W = 4

  val LAT_MIN = 20
  val LAT_SPAN = 11 // 20..30 cycles, so completions reorder
  val TARGET = 400  // completions to time
  val LINES_PER_SOURCE = 64

  val bmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = CACHE_ADDR_W,
      dataWidth = BMB_DATA_W
    ).addSources(SOURCES, BmbSourceParameter(
      contextWidth = CONTEXT_W,
      lengthWidth = 6,
      canRead = true,
      canWrite = true
    )),
    invalidation = BmbInvalidationParameter()
  )

  /** One measurement run at a given slot count. Returns cycles per completion. */
  def run(outstanding: Int, seed: Int): Double = {
    var cyclesPerReq = 0.0

    SimConfig
      .compile(new BmbCacheBridge(bmbParam, CACHE_ADDR_W, LINE_W, outstanding))
      .doSim(s"outstanding_$outstanding", seed) { dut =>
        dut.clockDomain.forkStimulus(10)

        dut.io.bmb.cmd.valid #= false
        dut.io.bmb.cmd.payload.fragment.address #= 0
        dut.io.bmb.cmd.payload.fragment.opcode #= 0
        dut.io.bmb.cmd.payload.fragment.data #= 0
        dut.io.bmb.cmd.payload.fragment.mask #= 0
        dut.io.bmb.cmd.payload.fragment.length #= (BMB_DATA_W / 8) - 1
        dut.io.bmb.cmd.payload.fragment.source #= 0
        dut.io.bmb.cmd.payload.fragment.context #= 0
        dut.io.bmb.cmd.payload.last #= true
        dut.io.bmb.rsp.ready #= true
        dut.io.cache.req.ready #= true
        dut.io.cache.rsp.valid #= false
        dut.io.cache.rsp.payload.data #= 0
        dut.io.cache.rsp.payload.error #= false
        if (dut.io.cache.rsp.payload.id != null) dut.io.cache.rsp.payload.id #= 0

        dut.clockDomain.waitSampling(5)

        val rnd = new Random(seed)
        val lineBytes = LINE_W / 8

        // ---- cache stub: unbounded concurrency, random latency ------------
        // Line-addressed memory, seeded so a read of an untouched line still
        // has a known value.
        val mem = mutable.Map[BigInt, BigInt]()
        def lineOf(byteAddr: BigInt): BigInt = byteAddr / lineBytes
        def seedLine(line: BigInt): BigInt = {
          var v = BigInt(0)
          for (w <- 0 until LANES) v |= ((line * 4 + w) * 0x01010101L & 0xFFFFFFFFL) << (w * 32)
          v
        }
        def readLine(line: BigInt): BigInt = mem.getOrElseUpdate(line, seedLine(line))

        case class Inflight(due: Long, id: Int, data: BigInt)
        val inflight = mutable.ArrayBuffer[Inflight]()
        var offered: Option[Inflight] = None

        // ---- BMB side bookkeeping -----------------------------------------
        case class Expect(data: BigInt, context: Int, isWrite: Boolean)
        val expect = mutable.Map[Int, Expect]() // source -> what it is waiting for
        val nextLine = Array.fill(SOURCES)(0)

        var cycle = 0L
        var completions = 0
        var issued = 0
        var errors = 0
        var firstIssueCycle = -1L
        var lastCompletionCycle = 0L

        // Command currently presented; re-presented every cycle until taken.
        var presented: Option[(Int, BigInt, Boolean, BigInt, Int)] = None // src, addr, write, wdata, ctx

        def fail(msg: String): Unit = {
          if (errors < 10) println(s"  FAIL @$cycle: $msg")
          errors += 1
        }

        dut.clockDomain.onSamplings {
          // --- cache stub: accept ---
          if (dut.io.cache.req.valid.toBoolean && dut.io.cache.req.ready.toBoolean) {
            val addr = dut.io.cache.req.payload.addr.toBigInt
            val isWrite = dut.io.cache.req.payload.write.toBoolean
            val id = if (dut.io.cache.req.payload.id != null) dut.io.cache.req.payload.id.toInt else 0
            val line = lineOf(addr)
            var value = readLine(line)
            if (isWrite) {
              // Cache convention: mask bit 1 = KEEP the cached byte.
              val keep = dut.io.cache.req.payload.mask.toBigInt
              val wdata = dut.io.cache.req.payload.data.toBigInt
              for (b <- 0 until lineBytes) {
                if (((keep >> b) & 1) == 0) {
                  val m = BigInt(0xFF) << (b * 8)
                  value = (value & ~m) | (wdata & m)
                }
              }
              mem(line) = value
            }
            inflight += Inflight(cycle + LAT_MIN + rnd.nextInt(LAT_SPAN), id, value)
          }

          // --- cache stub: respond ---
          // A held offer stays put until it is taken; otherwise pick a random
          // due entry, which is what makes completions arrive out of order.
          if (offered.isDefined &&
              dut.io.cache.rsp.valid.toBoolean && dut.io.cache.rsp.ready.toBoolean) {
            inflight -= offered.get
            offered = None
          }
          if (offered.isEmpty) {
            val due = inflight.zipWithIndex.filter(_._1.due <= cycle)
            if (due.nonEmpty) offered = Some(due(rnd.nextInt(due.length))._1)
          }
          offered match {
            case Some(e) =>
              dut.io.cache.rsp.valid #= true
              dut.io.cache.rsp.payload.data #= e.data
              if (dut.io.cache.rsp.payload.id != null) dut.io.cache.rsp.payload.id #= e.id
            case None =>
              dut.io.cache.rsp.valid #= false
          }

          // --- BMB response checking ---
          if (dut.io.bmb.rsp.valid.toBoolean && dut.io.bmb.rsp.ready.toBoolean) {
            val src = dut.io.bmb.rsp.payload.fragment.source.toInt
            val ctx = dut.io.bmb.rsp.payload.fragment.context.toInt
            val data = dut.io.bmb.rsp.payload.fragment.data.toBigInt
            expect.remove(src) match {
              case None => fail(s"response for source $src which had nothing outstanding")
              case Some(e) =>
                if (ctx != e.context) fail(s"source $src context $ctx, expected ${e.context}")
                if (data != e.data) fail(f"source $src data 0x$data%x, expected 0x${e.data}%x")
            }
            completions += 1
            lastCompletionCycle = cycle
          }

          // --- BMB command issue: one transaction per source, as JOP does ---
          if (presented.isDefined && dut.io.bmb.cmd.valid.toBoolean && dut.io.bmb.cmd.ready.toBoolean) {
            val (src, addr, isWrite, wdata, ctx) = presented.get
            val line = lineOf(addr)
            val lane = ((addr % lineBytes) / (BMB_DATA_W / 8)).toInt
            // A write is answered with zero data; a read with the addressed lane.
            val want =
              if (isWrite) BigInt(0)
              else (readLine(line) >> (lane * BMB_DATA_W)) & ((BigInt(1) << BMB_DATA_W) - 1)
            expect(src) = Expect(want, ctx, isWrite)
            issued += 1
            if (firstIssueCycle < 0) firstIssueCycle = cycle
            presented = None
          }

          if (presented.isEmpty && issued < TARGET + SOURCES) {
            val free = (0 until SOURCES).filter(s => !expect.contains(s))
            if (free.nonEmpty) {
              val src = free(rnd.nextInt(free.length))
              val line = BigInt(src * LINES_PER_SOURCE + nextLine(src))
              nextLine(src) = (nextLine(src) + 1) % LINES_PER_SOURCE
              val lane = rnd.nextInt(LANES)
              val addr = line * lineBytes + lane * (BMB_DATA_W / 8)
              val isWrite = rnd.nextInt(4) == 0
              val wdata = BigInt(rnd.nextInt(1 << 30))
              val ctx = rnd.nextInt(1 << CONTEXT_W)
              presented = Some((src, addr, isWrite, wdata, ctx))
            }
          }

          presented match {
            case Some((src, addr, isWrite, wdata, ctx)) =>
              dut.io.bmb.cmd.valid #= true
              dut.io.bmb.cmd.payload.fragment.address #= addr
              dut.io.bmb.cmd.payload.fragment.opcode #= (if (isWrite) 1 else 0)
              dut.io.bmb.cmd.payload.fragment.data #= wdata
              dut.io.bmb.cmd.payload.fragment.mask #= 0xF
              dut.io.bmb.cmd.payload.fragment.length #= (BMB_DATA_W / 8) - 1
              dut.io.bmb.cmd.payload.fragment.source #= src
              dut.io.bmb.cmd.payload.fragment.context #= ctx
              dut.io.bmb.cmd.payload.last #= true
            case None =>
              dut.io.bmb.cmd.valid #= false
          }

          cycle += 1
        }

        val timeout = 200000
        while (completions < TARGET && cycle < timeout) dut.clockDomain.waitSampling(100)

        assert(completions >= TARGET,
          s"outstanding=$outstanding: only $completions of $TARGET completions in $cycle cycles " +
          "(measure completions, not acceptances — a stalled pipeline still accepts)")
        assert(errors == 0, s"outstanding=$outstanding: $errors response mismatches")

        cyclesPerReq = (lastCompletionCycle - firstIssueCycle).toDouble / completions
        println(f"  outstanding=$outstanding%-2d  ${cyclesPerReq}%6.2f cycles/completion  " +
                f"($completions completions, $cycle cycles)")
      }

    cyclesPerReq
  }

  println("=== BmbCacheBridge concurrency ===")
  println(s"cache stub: perfectly pipelined, $LAT_MIN-${LAT_MIN + LAT_SPAN - 1} cycle latency, " +
          s"$SOURCES BMB sources, one transaction each")

  val results = Seq(1, 2, 4, 8).map(n => n -> run(n, seed = 42))
  val base = results.head._2

  println()
  println("  slots   cycles/req   speedup")
  for ((n, c) <- results) println(f"  $n%5d   $c%10.2f   ${base / c}%6.2f x")

  // A single-slot bridge cannot beat one round trip per request; the whole
  // point of the change is that more slots do. The thresholds are deliberately
  // slack — this is a structural check, not a performance regression gate.
  val minLatency = LAT_MIN.toDouble
  assert(base > minLatency * 0.9,
    f"outstanding=1 should cost about a full round trip, measured $base%.2f cycles/req")
  val fourWay = results.toMap.apply(4)
  assert(base / fourWay > 2.5,
    f"4 slots should give well over 2x, measured ${base / fourWay}%.2f x " +
    f"($base%.2f -> $fourWay%.2f cycles/req)")
  val eightWay = results.toMap.apply(8)
  assert(eightWay < fourWay,
    f"8 slots should still beat 4, measured $eightWay%.2f vs $fourWay%.2f cycles/req")

  println()
  println("BRIDGE CONCURRENCY OK")
}
