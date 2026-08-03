package jop.ddr2

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.ddr3.{CacheConfig, CacheFrontend, LruCacheCore}

import scala.collection.mutable
import scala.util.Random

/**
 * Integration check of LruCacheCore + CacheToDdr2Adapter against a behavioural
 * ALTMEMPHY local interface.
 *
 * CacheToDdr2AdapterSim drives the adapter directly and only ever expects a
 * response for a READ, because that is what the DDR2 interface itself does —
 * a write is fire-and-forget on `local_ready`. That assumption is wrong at the
 * next level up: LruCacheCore issues an evict as a memCmd WRITE and then sits
 * in WAIT_EVICT_RSP waiting for a memRsp. CacheToMigAdapter satisfies this by
 * pushing a dummy response for every accepted write; the DDR2 adapter did not,
 * so the first dirty eviction deadlocked.
 *
 * That is invisible until the cache is full of dirty lines, which is why it
 * only showed up on hardware, at exactly 8193 words of a serial download
 * (8192 words = 32 KB = 4 ways x 256 sets x 32 B). Here the geometry is shrunk
 * so the same eviction happens after 17 line writes instead of 1025.
 */
object CacheDdr2EvictSim extends App {

  val ADDR_W = 16          // 64 KB byte address space
  val DATA_W = 256         // 32-byte line, matching the half-rate local word
  val SETS = 4
  val WAYS = 4             // CacheConfig default; 4 x 4 x 32 B = 512 B = 16 lines
  val LINE_BYTES = DATA_W / 8
  val WORD_SHIFT = log2Up(LINE_BYTES)

  /** Cache and adapter wired exactly as createDdr2Path does. */
  class Dut extends Component {
    // hasFill = true to match createDdr2Path (JopConfig sets hasBackendFill).
    // FILL_DRAIN waits for one memRsp per write FILL_WRITE issues, so the fill
    // path deadlocks on a write-silent adapter exactly as an eviction does —
    // this config is what makes that reachable in simulation.
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = ADDR_W, dataWidth = DATA_W, setCount = SETS, wayCount = WAYS,
      hasFill = true, fillAddrWidth = ADDR_W - 2))
    val adapter = new CacheToDdr2Adapter(ADDR_W, DATA_W, rspDepth = 8)

    val io = new Bundle {
      val frontend = slave(CacheFrontend(ADDR_W, DATA_W))
      val fill = slave(jop.memory.MemFill(ADDR_W - 2))

      val local_ready       = in Bool()
      val local_rdata       = in Bits (DATA_W bits)
      val local_rdata_valid = in Bool()
      val local_init_done   = in Bool()

      val local_address   = out Bits (adapter.localAddrWidth bits)
      val local_write_req = out Bool()
      val local_read_req  = out Bool()
      val local_wdata     = out Bits (DATA_W bits)
      val local_be        = out Bits (LINE_BYTES bits)
    }

    cache.io.frontend.req << io.frontend.req
    io.frontend.rsp << cache.io.frontend.rsp
    cache.io.fill.get.cmd   := io.fill.cmd
    cache.io.fill.get.start := io.fill.start
    cache.io.fill.get.end   := io.fill.end
    cache.io.fill.get.value := io.fill.value
    io.fill.busy := cache.io.fill.get.busy
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

  SimConfig.compile(new Dut).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.frontend.req.valid #= false
    dut.io.frontend.rsp.ready #= true
    dut.io.local_ready #= false
    dut.io.local_rdata #= 0
    dut.io.local_rdata_valid #= false
    dut.io.fill.cmd #= false
    dut.io.fill.start #= 0
    dut.io.fill.end #= 0
    dut.io.fill.value #= 0
    dut.io.local_init_done #= false
    dut.clockDomain.waitSampling(5)
    dut.io.local_init_done #= true

    // ---- behavioural DDR2 model -----------------------------------------
    val mem = mutable.Map[BigInt, BigInt]()
    val readPipe = mutable.Queue[(Int, BigInt)]()
    val rnd = new Random(1)
    var writesSeen = 0
    var readsSeen = 0

    dut.clockDomain.onSamplings {
      // Decide acceptance from the ready the DUT actually saw at this edge.
      val ready = dut.io.local_ready.toBoolean
      val wr = dut.io.local_write_req.toBoolean
      val rd = dut.io.local_read_req.toBoolean
      if (ready && (wr || rd)) {
        val a = dut.io.local_address.toBigInt
        if (wr) {
          val be = dut.io.local_be.toBigInt
          val wdata = dut.io.local_wdata.toBigInt
          var merged = mem.getOrElse(a, BigInt(0))
          for (b <- 0 until LINE_BYTES) {
            if (((be >> b) & 1) == 1) {
              val m = BigInt(0xFF) << (b * 8)
              merged = (merged & ~m) | (wdata & m)
            }
          }
          mem(a) = merged
          writesSeen += 1
        } else {
          readPipe.enqueue((3 + rnd.nextInt(7), mem.getOrElse(a, BigInt(0))))
          readsSeen += 1
        }
      }

      var fire = false
      if (readPipe.nonEmpty) {
        val (c, d) = readPipe.head
        if (c <= 0) {
          readPipe.dequeue(); dut.io.local_rdata #= d; fire = true
        } else readPipe.update(0, (c - 1, d))
      }
      dut.io.local_rdata_valid #= fire
      dut.io.local_ready #= rnd.nextInt(100) < 70
    }

    // ---- stimulus --------------------------------------------------------
    // Sub-line writes (mask keeps the upper half) so the cache must REFILL
    // rather than take the full-line-write shortcut — the same shape as the
    // serial download, which writes one 32-bit word at a time.
    val keepUpper = ((BigInt(1) << LINE_BYTES) - 1) - ((BigInt(1) << (LINE_BYTES / 2)) - 1)

    // Count COMPLETIONS, not request acceptances. frontend.req.ready only
    // reflects the cache's 4-deep input FIFO, so a deadlocked cache still
    // accepts several requests — which is exactly how an earlier version of
    // this test reported PASS while the DDR2 side had gone quiet.
    var completed = 0
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean) {
          completed += 1
        }
      }
    }

    def issueWrite(line: Int, data: BigInt): Unit = {
      dut.io.frontend.req.valid #= true
      dut.io.frontend.req.payload.addr #= BigInt(line) << WORD_SHIFT
      dut.io.frontend.req.payload.write #= true
      dut.io.frontend.req.payload.data #= data
      dut.io.frontend.req.payload.mask #= keepUpper
      dut.clockDomain.waitSamplingWhere(dut.io.frontend.req.ready.toBoolean)
      dut.io.frontend.req.valid #= false
    }

    def data(i: Int): BigInt = {
      var v = BigInt(0)
      for (k <- 0 until LINE_BYTES) v |= BigInt((i * 13 + k * 7) & 0xFF) << (k * 8)
      v
    }

    val capacityLines = SETS * WAYS
    // Well past capacity, so most lines are evicted and later refilled from
    // DRAM — a write that never completes is only half the failure mode; a
    // writeback that loses data is the other.
    val totalLines = 200

    // The write keeps the upper half of the line, so each line ends up as the
    // refilled memory content (zero here) merged with the low half written.
    val lowHalf = (BigInt(1) << (DATA_W / 2)) - 1
    val golden = (0 until totalLines).map(i => data(i) & lowHalf)

    val issuer = fork {
      for (line <- 0 until totalLines) issueWrite(line, data(line))
    }

    var guard = 0
    while (completed < totalLines && guard < 200000) {
      dut.clockDomain.waitSampling(); guard += 1
    }

    println(s"cache capacity = $capacityLines lines ($SETS sets x $WAYS ways x $LINE_BYTES B)")
    println(s"completed $completed / $totalLines line writes")
    println(s"local writes seen = $writesSeen, local reads seen = $readsSeen")

    if (completed < totalLines) {
      println(s"FAIL: stalled after $completed completions — the first write needing")
      println("      a dirty eviction never retires. LruCacheCore sits in WAIT_EVICT_RSP:")
      println("      it issued the evict as a memCmd WRITE and is waiting for a memRsp")
      println("      that the DDR2 adapter never produces (it only responds to reads).")
      simFailure(s"eviction deadlock: $completed/$totalLines completed")
    }

    val evictions = writesSeen
    if (evictions == 0) simFailure("no evict write reached the DDR2 interface")

    // ---- read everything back -------------------------------------------
    // Most of these lines are no longer cached, so each read proves the
    // corresponding writeback actually reached DRAM with the right bytes.
    var mismatches = 0
    var readsDone = 0
    for (line <- 0 until totalLines) {
      dut.io.frontend.req.valid #= true
      dut.io.frontend.req.payload.addr #= BigInt(line) << WORD_SHIFT
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.frontend.req.ready.toBoolean)
      dut.io.frontend.req.valid #= false

      var w = 0
      while (!(dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean)
             && w < 5000) {
        dut.clockDomain.waitSampling(); w += 1
      }
      if (w >= 5000) simFailure(s"read of line $line never returned")
      val got = dut.io.frontend.rsp.payload.data.toBigInt
      if (got != golden(line)) {
        if (mismatches < 5)
          println(f"MISMATCH line $line: got 0x$got%x want 0x${golden(line)}%x")
        mismatches += 1
      }
      readsDone += 1
      dut.clockDomain.waitSampling()
    }

    println(s"evictions to DRAM = $evictions, lines read back = $readsDone, " +
            s"mismatches = $mismatches")
    if (mismatches != 0) simFailure(s"$mismatches lines came back wrong after eviction")

    // ---- backend fill ----------------------------------------------------
    // The GC's hardware zeroing. FILL_DRAIN retires only when fillRsp reaches
    // fillIssued, counting one memRsp per write FILL_WRITE issues — so a
    // write-silent adapter hangs here too, for the same reason an eviction did.
    val fillWords = 64                       // 8 lines at 8 words per line
    dut.io.fill.start #= 0
    dut.io.fill.end #= fillWords
    dut.io.fill.value #= 0
    dut.io.fill.cmd #= true
    dut.clockDomain.waitSamplingWhere(dut.io.fill.busy.toBoolean)
    dut.io.fill.cmd #= false

    var fw = 0
    while (dut.io.fill.busy.toBoolean && fw < 20000) {
      dut.clockDomain.waitSampling(); fw += 1
    }
    if (dut.io.fill.busy.toBoolean) {
      println("FAIL: backend fill never completed — LruCacheCore is stuck in")
      println("      FILL_DRAIN waiting for memRsp on the writes it issued.")
      simFailure("backend fill deadlock")
    }
    println(s"backend fill of $fillWords words retired in $fw cycles")

    // The filled range must now read back as the fill value, not the old data.
    var fillBad = 0
    for (line <- 0 until fillWords / (DATA_W / 32)) {
      dut.io.frontend.req.valid #= true
      dut.io.frontend.req.payload.addr #= BigInt(line) << WORD_SHIFT
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= 0
      dut.clockDomain.waitSamplingWhere(dut.io.frontend.req.ready.toBoolean)
      dut.io.frontend.req.valid #= false
      var w2 = 0
      while (!(dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean)
             && w2 < 5000) { dut.clockDomain.waitSampling(); w2 += 1 }
      if (w2 >= 5000) simFailure(s"read of filled line $line never returned")
      if (dut.io.frontend.rsp.payload.data.toBigInt != 0) {
        if (fillBad < 3) println(s"FILL MISMATCH line $line: expected zeroes")
        fillBad += 1
      }
      dut.clockDomain.waitSampling()
    }
    if (fillBad != 0) simFailure(s"$fillBad filled lines did not read back as zero")

    println(s"PASS: $totalLines line writes through $evictions evictions, all data " +
            s"verified after writeback, and a $fillWords-word backend fill retired and verified")
  }
}
