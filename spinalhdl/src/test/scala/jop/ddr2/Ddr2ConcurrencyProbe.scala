package jop.ddr2

import spinal.core._
import spinal.core.sim._
import jop.ddr3.{CacheReq, CacheRsp}
import scala.collection.mutable

/**
 * Does the DDR2 memory path go faster when requests OVERLAP?
 *
 * This exists to de-risk the MSHR work before rewriting three components.
 * `LruCacheCore` is blocking -- it accepts a request only in IDLE, so exactly
 * one miss is ever in flight, and the measured multicore ceiling (1.81x on
 * eight cores) is `1/miss_latency`. An MSHR is only worth building if the path
 * BELOW the cache actually services overlapping requests faster than serial
 * ones. If the adapter or controller is itself serial, an MSHR buys nothing and
 * the whole plan is wrong.
 *
 * So: drive `CacheToDdr2Adapter` directly, bypassing the cache, and compare
 * - k=1: issue, wait for the response, repeat  (exactly what the cache does now)
 * - k>1: keep k commands in flight
 * The k>1 / k=1 throughput ratio is an UPPER BOUND on what an MSHR can deliver
 * from this backend.
 *
 * Latency is swept rather than guessed: the conclusion should not depend on one
 * assumed number. `local_ready` is held high so the measurement isolates
 * concurrency from controller back-pressure.
 *
 * Note the adapter already supports 8 outstanding reads (`rspDepth`), so no
 * adapter change is needed -- this only measures whether that capability pays.
 */
object Ddr2ConcurrencyProbe extends App {
  val ADDR_W = 30
  val DATA_W = 256          // half-rate local interface, as on the A-E115FB
  val RSP_DEPTH = 8
  val N_READS = 400         // completions to measure over

  case class Point(latency: Int, k: Int, cycles: Long) {
    def perReq: Double = cycles.toDouble / N_READS
  }
  val results = mutable.ArrayBuffer[Point]()

  SimConfig.compile(new CacheToDdr2Adapter(ADDR_W, DATA_W, rspDepth = RSP_DEPTH)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.cmd.valid #= false
    dut.io.rsp.ready #= true
    dut.io.local_ready #= true
    dut.io.local_rdata #= 0
    dut.io.local_rdata_valid #= false
    dut.io.local_init_done #= true
    dut.clockDomain.waitSampling(5)

    // Behavioural ALTMEMPHY local interface: accepts commands back-to-back and
    // returns read data `latency` cycles later, strictly in order. That IS how
    // the real interface behaves -- it is queued, not request/response.
    var latency = 0
    val inflight = mutable.Queue[(Long, BigInt)]()   // (dueCycle, data)
    var now = 0L
    var completions = 0
    var nextData = BigInt(1)

    dut.clockDomain.onSamplings {
      now += 1
      // read requests accepted this cycle enter the pipe
      if (dut.io.local_read_req.toBoolean && dut.io.local_ready.toBoolean) {
        inflight.enqueue((now + latency, nextData))
        nextData += 1
      }
      val due = inflight.nonEmpty && inflight.head._1 <= now
      if (due) {
        val (_, d) = inflight.dequeue()
        dut.io.local_rdata #= d
        dut.io.local_rdata_valid #= true
      } else {
        dut.io.local_rdata_valid #= false
      }
      if (dut.io.rsp.valid.toBoolean && dut.io.rsp.ready.toBoolean) completions += 1
    }

    // Run N_READS reads with at most k outstanding, return elapsed cycles.
    def measure(lat: Int, k: Int): Long = {
      latency = lat
      inflight.clear()
      completions = 0
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(lat + 20)   // drain
      completions = 0

      var issued = 0
      val start = now
      var guard = 0L
      val limit = (N_READS.toLong * (lat + 40)) + 20000
      while (completions < N_READS && guard < limit) {
        val outstanding = issued - completions
        val want = issued < N_READS && outstanding < k
        dut.io.cmd.valid #= want
        dut.io.cmd.payload.addr #= (issued.toLong * 32) & ((1L << ADDR_W) - 1)
        dut.io.cmd.payload.write #= false
        dut.io.cmd.payload.data #= 0
        dut.io.cmd.payload.mask #= (BigInt(1) << (DATA_W / 8)) - 1
        dut.clockDomain.waitSampling()
        if (want && dut.io.cmd.ready.toBoolean) issued += 1
        guard += 1
      }
      dut.io.cmd.valid #= false
      if (completions < N_READS)
        println(f"  !! k=$k lat=$lat DID NOT COMPLETE: $completions/$N_READS after $guard cycles")
      now - start
    }

    for (lat <- Seq(10, 20, 40, 80); k <- Seq(1, 2, 4, 8)) {
      val c = measure(lat, k)
      results += Point(lat, k, c)
      println(f"  latency=$lat%3d  k=$k  ${c.toDouble / N_READS}%7.2f cycles/req")
    }
  }

  println()
  println("=== DDR2 path: throughput gain from overlapping requests ===")
  println("latency is the modelled controller read latency in local-interface cycles;")
  println("k is the number of requests kept in flight. k=1 is today's blocking cache.")
  println()
  println("  latency |    k=1 |    k=2 |    k=4 |    k=8 |  best gain")
  println("  --------|--------|--------|--------|--------|-----------")
  for (lat <- Seq(10, 20, 40, 80)) {
    val row = Seq(1, 2, 4, 8).map(k => results.find(p => p.latency == lat && p.k == k).get.perReq)
    val gain = row.head / row.min
    println(f"  $lat%7d | ${row(0)}%6.2f | ${row(1)}%6.2f | ${row(2)}%6.2f | ${row(3)}%6.2f | ${gain}%6.2fx")
  }
  println()
  println("cycles/req at k=1 should track (latency + fixed overhead); if k>1 does not")
  println("reduce it, the backend serialises and an MSHR CANNOT help. If it falls")
  println("towards a floor, that floor is the new ceiling an MSHR would expose, and")
  println("the gain column bounds the win from this backend.")
}
