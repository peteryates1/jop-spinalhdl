package jop.ddr3

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.utils.JopSimDefaults
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

/**
 * DDR3 runtime reset (item 48) resets the core, L2 cache and CacheToMigAdapter
 * while the MIG keeps running underneath, so its calibration survives and a
 * reset costs microseconds instead of the seconds a retrain would.
 *
 * THE HAZARD THIS TESTS. The MIG does not know a reset happened. Read data for
 * commands issued before it will still arrive, `app_rd_data_valid` cannot be
 * back-pressured, and this path matches responses BY POSITION. A stale beat
 * landing in a freshly reset FIFO would be silently attributed to the next
 * read -- the same shape as the DDR2 write-ack bug and the AlteraSdramAdapter
 * corruption, both of which produced wrong data rather than a hang.
 *
 * The argument that it is safe has two halves, and both are checked here:
 *
 *   1. Writes cannot be stranded. The adapter drives app_en, app_wdf_wren and
 *      app_wdf_end in the SAME cycle, gated on app_rdy && app_wdf_rdy, so the
 *      MIG is never left waiting for a write burst it will not get.
 *   2. Reads in flight are dropped. While the adapter is in reset its FIFOs
 *      stay empty, so late data is not captured; the hold is far longer than
 *      any MIG read latency, so nothing is outstanding on release.
 *
 * If the hold were ever shortened below the MIG read latency, `reset released
 * EARLY` below is the test that fails.
 */
class CacheMigResetSim extends AnyFunSuite {
  // Deliberately NOT `import CacheMigMshrSim._`. That object extends App, so
  // its body runs only from main() and every val reads 0 from outside -- which
  // presented here as "setCount must be a power of two" during elaboration.
  val ADDR_W = 28
  val DATA_W = 128                      // MIG app-interface width
  val SETS = 16
  val WAYS = 2
  val LINE_BYTES = DATA_W / 8
  val WORD_SHIFT = log2Up(LINE_BYTES)
  val MSHRS = 4
  val LATENCY = 20                      // MIG read latency, cycles

  /** Wrap the harness in a clock domain whose reset the testbench drives. */
  class ResettableDut extends Component {
    val io = new Bundle {
      val coreReset = in Bool()
      val frontend = slave(CacheFrontend(ADDR_W, DATA_W, log2Up(MSHRS)))
      val app_rdy = in Bool(); val app_wdf_rdy = in Bool()
      val app_rd_data = in Bits(DATA_W bits); val app_rd_data_valid = in Bool()
      val app_addr = out Bits(ADDR_W bits); val app_cmd = out Bits(3 bits)
      val app_en = out Bool(); val app_wdf_wren = out Bool()
    }
    // Mirrors JopTop: the core/cache/adapter domain reset is
    // `ui_clk_sync_rst || runtimeHold`, while the MIG's sys_rst is untouched.
    val coreCd = ClockDomain(
      clock = ClockDomain.current.readClockWire,
      reset = ClockDomain.current.isResetActive || io.coreReset,
      config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
    val area = new ClockingArea(coreCd) {
      val inner = new Component {
        val cache = new LruCacheCore(CacheConfig(
          addrWidth = ADDR_W, dataWidth = DATA_W, setCount = SETS, wayCount = WAYS,
          idWidth = log2Up(MSHRS), mshrCount = MSHRS))
        val adapter = new CacheToMigAdapter(ADDR_W, maxOutstanding = 2 * MSHRS)
        val io = new Bundle {
          val frontend = slave(CacheFrontend(ADDR_W, DATA_W, log2Up(MSHRS)))
          val app_rdy = in Bool(); val app_wdf_rdy = in Bool()
          val app_rd_data = in Bits(DATA_W bits); val app_rd_data_valid = in Bool()
          val app_addr = out Bits(ADDR_W bits); val app_cmd = out Bits(3 bits)
          val app_en = out Bool(); val app_wdf_wren = out Bool()
        }
        cache.io.frontend.req << io.frontend.req
        io.frontend.rsp << cache.io.frontend.rsp
        adapter.io.cmd.valid        := cache.io.memCmd.valid
        adapter.io.cmd.payload.addr := cache.io.memCmd.payload.addr
        adapter.io.cmd.payload.write:= cache.io.memCmd.payload.write
        adapter.io.cmd.payload.wdata:= cache.io.memCmd.payload.data
        adapter.io.cmd.payload.wmask:= cache.io.memCmd.payload.mask
        cache.io.memCmd.ready       := adapter.io.cmd.ready
        cache.io.memRsp.valid        := adapter.io.rsp.valid
        cache.io.memRsp.payload.data := adapter.io.rsp.payload.rdata
        cache.io.memRsp.payload.error:= adapter.io.rsp.payload.error
        adapter.io.rsp.ready         := cache.io.memRsp.ready
        adapter.io.app_rdy           := io.app_rdy
        adapter.io.app_wdf_rdy       := io.app_wdf_rdy
        adapter.io.app_rd_data       := io.app_rd_data
        adapter.io.app_rd_data_valid := io.app_rd_data_valid
        io.app_addr := adapter.io.app_addr
        io.app_cmd  := adapter.io.app_cmd
        io.app_en   := adapter.io.app_en
        io.app_wdf_wren := adapter.io.app_wdf_wren
      }
    }
    io.frontend.req >> area.inner.io.frontend.req
    area.inner.io.frontend.rsp >> io.frontend.rsp
    area.inner.io.app_rdy := io.app_rdy
    area.inner.io.app_wdf_rdy := io.app_wdf_rdy
    area.inner.io.app_rd_data := io.app_rd_data
    area.inner.io.app_rd_data_valid := io.app_rd_data_valid
    io.app_addr := area.inner.io.app_addr
    io.app_cmd := area.inner.io.app_cmd
    io.app_en := area.inner.io.app_en
    io.app_wdf_wren := area.inner.io.app_wdf_wren
  }

  def expected(line: BigInt): BigInt = (line * 0x9E3779B1L) & ((BigInt(1) << DATA_W) - 1)

  /** Run a body with a MIG model that always answers `LATENCY` cycles later,
    * regardless of resets -- which is the whole point. */
  def withMig(holdCycles: Int)(body: (ResettableDut, () => Unit, mutable.ArrayBuffer[String]) => Unit): mutable.ArrayBuffer[String] = {
    val errs = mutable.ArrayBuffer[String]()
    JopSimDefaults.config.compile(new ResettableDut).doSim { dut =>
      dut.io.coreReset #= false
      dut.io.app_rdy #= true; dut.io.app_wdf_rdy #= true
      dut.io.app_rd_data_valid #= false; dut.io.app_rd_data #= 0
      dut.io.frontend.req.valid #= false; dut.io.frontend.rsp.ready #= true
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      // MIG model: a read accepted now produces its beat LATENCY cycles later,
      // and keeps that promise across a core reset.
      val pending = mutable.Queue[(Int, BigInt)]()
      var now = 0
      dut.clockDomain.onSamplings {
        now += 1
        dut.io.app_rd_data_valid #= false
        if (dut.io.app_en.toBoolean && dut.io.app_cmd.toInt == 1) {
          val line = (dut.io.app_addr.toBigInt >> WORD_SHIFT)
          pending.enqueue((now + LATENCY, expected(line)))
        }
        if (pending.nonEmpty && pending.head._1 <= now) {
          val (_, d) = pending.dequeue()
          dut.io.app_rd_data #= d
          dut.io.app_rd_data_valid #= true
        }
      }

      def pulseReset(): Unit = {
        dut.io.coreReset #= true
        dut.clockDomain.waitSampling(holdCycles)
        dut.io.coreReset #= false
        dut.clockDomain.waitSampling(5)
      }
      body(dut, pulseReset, errs)
    }
    errs
  }

  /** Wait for the cache to accept a request, but NEVER unboundedly.
    *
    * `waitSamplingWhere` spins forever if the condition never comes true, and
    * under JOP_SIM_XINIT=random it does not: LruCacheCore has registers with no
    * init(), so a randomised power-up can leave it in a state where `req.ready`
    * never asserts. The first version of this test used the unbounded form and
    * burned 8.4 hours of CPU in one `doSim` before anyone looked. A test that
    * hangs tells you nothing; a test that fails names the problem. */
  def acceptOrFail(dut: ResettableDut, what: String, errs: mutable.ArrayBuffer[String],
                   limit: Int = 5000): Boolean = {
    var n = 0
    while (!dut.io.frontend.req.ready.toBoolean && n < limit) {
      dut.clockDomain.waitSampling(); n += 1
    }
    if (n >= limit) {
      errs += s"$what: cache never asserted req.ready within $limit cycles"
      dut.io.frontend.req.valid #= false
      false
    } else { dut.clockDomain.waitSampling(); true }
  }

  /** Issue a read and check the data, driving the frontend directly. */
  def readCheck(dut: ResettableDut, line: Int, errs: mutable.ArrayBuffer[String]): Unit = {
    dut.io.frontend.req.valid #= true
    dut.io.frontend.req.payload.addr #= BigInt(line) << WORD_SHIFT
    dut.io.frontend.req.payload.write #= false
    dut.io.frontend.req.payload.data #= 0
    dut.io.frontend.req.payload.mask #= 0
    dut.io.frontend.req.payload.driveId(U(0))
    if (!acceptOrFail(dut, s"line $line", errs)) return
    dut.io.frontend.req.valid #= false
    var guard = 0
    while (!dut.io.frontend.rsp.valid.toBoolean && guard < 4000) {
      dut.clockDomain.waitSampling(); guard += 1
    }
    if (!dut.io.frontend.rsp.valid.toBoolean) { errs += s"line $line: no response"; return }
    val got = dut.io.frontend.rsp.payload.data.toBigInt
    if (got != expected(line)) errs += f"line $line: got 0x$got%x want 0x${expected(line)}%x"
    dut.clockDomain.waitSampling()
  }

  test("reset with reads in flight — later reads still return the right data") {
    val errs = withMig(holdCycles = 4 * LATENCY) { (dut, pulseReset, errs) =>
      // Start misses, then reset while the MIG still owes us their data.
      var warmed = true
      for (l <- 0 until MSHRS if warmed) {
        dut.io.frontend.req.valid #= true
        dut.io.frontend.req.payload.addr #= BigInt(l) << WORD_SHIFT
        dut.io.frontend.req.payload.write #= false
        dut.io.frontend.req.payload.data #= 0
        dut.io.frontend.req.payload.mask #= 0
        dut.io.frontend.req.payload.driveId(U(l % MSHRS))
        warmed = acceptOrFail(dut, s"warm miss $l", errs)
      }
      dut.io.frontend.req.valid #= false
      dut.clockDomain.waitSampling(LATENCY / 2)   // beats still owed
      pulseReset()
      // Everything from here must be correct, and must not inherit a stale beat.
      for (l <- 100 until 110) readCheck(dut, l, errs)
    }
    assert(errs.isEmpty, errs.take(6).mkString("\n"))
  }

  test("reset released EARLY corrupts — proves the hold length is load-bearing") {
    // ONLY MEANINGFUL WITH X-STATE ZEROED. This test asserts that something
    // goes WRONG, which needs a deterministic baseline: under
    // JOP_SIM_XINIT=random the pre-reset state varies, the early release
    // sometimes happens to land clean, and the test then reports a failure that
    // says nothing about the hold length. Seen on seed 20260818. Skip rather
    // than let an item 45 sweep collect a false positive from it.
    assume(!JopSimDefaults.randomiseXState,
      "needs --x-initial 0: a negative-result test cannot run on a random baseline")
    // Deliberately release before the MIG has answered. This is what a shorter
    // ResetGenerator.DramResetCycles would do. If this ever stops failing, the
    // safety argument above has changed and the comment in ResetGenerator is
    // stale -- do not simply delete the test.
    val errs = withMig(holdCycles = 1) { (dut, pulseReset, e) =>
        var warmed = true
        for (l <- 0 until MSHRS if warmed) {
          dut.io.frontend.req.valid #= true
          dut.io.frontend.req.payload.addr #= BigInt(l) << WORD_SHIFT
          dut.io.frontend.req.payload.write #= false
          dut.io.frontend.req.payload.data #= 0
          dut.io.frontend.req.payload.mask #= 0
          dut.io.frontend.req.payload.driveId(U(l % MSHRS))
          warmed = acceptOrFail(dut, s"warm miss $l", e)
        }
        dut.io.frontend.req.valid #= false
        dut.clockDomain.waitSampling(LATENCY / 2)   // same as the test above
        pulseReset()
      for (l <- 100 until 110) readCheck(dut, l, e)
    }
    // Must FAIL. Asserting on the specific symptom, not on "some exception was
    // thrown" -- a broad catch here passed while elaboration was broken, which
    // is worse than no test at all.
    assert(errs.nonEmpty,
      "releasing the reset before the MIG answered produced no corruption; " +
      "the safety argument in ResetGenerator.DramResetCycles may have changed")
  }
}
