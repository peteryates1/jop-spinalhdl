package jop.memory

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import jop.config.MemoryDevice
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/**
 * Unit tests for AlteraSdramAdapter — current-status item 35.
 *
 * WHY THIS EXISTS. `useAlteraCtrl = manufacturer == Manufacturer.Altera`, so
 * every Altera board runs this adapter plus the Altera controller BlackBox,
 * while every JOP simulation substitutes `SdramCtrlNoCke` instead — Verilator
 * cannot build a BlackBox. The adapter was therefore unreachable by the test
 * suite on exactly the hardware that runs it, and it hid two response-path bugs
 * behind the >2-core SMP failure for weeks:
 *
 *   1. Avalon read data DROPPED when the consumer stalled. `avs_readdatavalid`
 *      is a one-cycle pulse and cannot be back-pressured (`avs_waitrequest`
 *      gates commands only), but it was wired straight to `io.bus.rsp`, a
 *      Stream whose consumer does deassert `ready`.
 *   2. Write responses OVERTAKING outstanding reads. A write response is
 *      manufactured locally and available immediately; a read response waits for
 *      SDRAM. The adapter emitted whichever was ready, and the consumer matches
 *      responses to commands BY ORDER — so a write could answer a read, with
 *      the `data := 0` the write branch hardcodes.
 *
 * Both were fixed in `ef36d99`. These tests were written against the PRE-FIX
 * adapter first and both fail on it (see the header of each test for the exact
 * failure), which is the acceptance criterion item 35 sets: a test that has
 * never failed has not been shown to be able to.
 *
 * WHAT IS CHECKED is one invariant, because it is the one the consumer relies
 * on and the one both bugs break:
 *
 *   Responses arrive IN COMMAND ORDER, each carrying ITS OWN context, and a
 *   read carries the memory contents of the address it asked for.
 *
 * The context is a plain counter here rather than the real `SdramContext`, so a
 * mispaired response is unambiguous — with a structured context it is easy to
 * write a check that passes because two different commands happened to share a
 * field. Note also what is deliberately NOT used as the check: a count of
 * outstanding transactions. The substituted write response kept commands and
 * responses BALANCED, so counting cannot see either bug — only pairing can.
 */
class AlteraSdramAdapterTest extends AnyFunSuite {

  val md     = MemoryDevice.W9825G6JH6
  val layout = SdramDeviceInfo.layoutFor(md)

  val cfg = AlteraSdramConfig(
    numChipSelects = 1,
    sdramBankWidth = layout.bankWidth,
    sdramRowWidth  = layout.rowWidth,
    sdramColWidth  = layout.columnWidth,
    sdramDataWidth = layout.dataWidth,
    casLatency     = md.casLatency
  )

  val dataMask = (1L << layout.dataWidth) - 1
  /** Mirrors the stub's memory initialisation. */
  def initialAt(addr: Int): Long = (addr.toLong ^ 0xA5A5L) & dataMask

  /** Address space the stub models (its `mem` is 1024 words). */
  val memWords = 1024

  case class Harness() extends Component {
    val io = new Bundle {
      val bus = slave(SdramCtrlBus(layout, UInt(16 bits)))
    }
    val adapter = AlteraSdramAdapter(layout, cfg, UInt(16 bits))
    adapter.io.bus <> io.bus
    // The BlackBox gets a body only here, in test scope.
    adapter.altera.addRTLPath("spinalhdl/src/test/resources/altera_sdram_tri_controller_stub.v")
    // The stub models no SDRAM, so the pad side is inert.
    adapter.io.sdram.DQ.read := 0
  }

  def simConfig = SimConfig.withConfig(SpinalConfig()).allOptimisation

  /** One command the testbench has issued and not yet seen answered. */
  case class Expected(ctx: Int, isWrite: Boolean, addr: Int, data: Long)

  /**
   * Drive `cmds` at the adapter and check every response against the queue of
   * outstanding commands. `readyGaps` decides how brutally `rsp.ready` is
   * withheld — the whole point of the first test.
   */
  def runSequence(dut: Harness,
                  cmds: Seq[Expected],
                  readyPattern: Int => Boolean): Unit = {
    dut.clockDomain.forkStimulus(10)
    dut.io.bus.cmd.valid #= false
    dut.io.bus.rsp.ready #= false
    dut.clockDomain.waitSampling(10)

    val outstanding = mutable.Queue[Expected]()
    // A software mirror of the stub's memory, so a read that follows a write to
    // the same address is checked against what was actually written.
    val model = mutable.Map[Int, Long]()
    def expectedData(addr: Int): Long = model.getOrElse(addr, initialAt(addr))

    var received = 0
    var cycle    = 0
    var failure: Option[String] = None

    // Consumer: withhold `ready` per the pattern, and check each response as it
    // fires against the head of the outstanding queue.
    val consumer = fork {
      while (received < cmds.length && failure.isEmpty) {
        dut.io.bus.rsp.ready #= readyPattern(cycle)
        dut.clockDomain.waitSampling()
        cycle += 1
        if (dut.io.bus.rsp.valid.toBoolean && dut.io.bus.rsp.ready.toBoolean) {
          val gotCtx  = dut.io.bus.rsp.context.toInt
          val gotData = dut.io.bus.rsp.data.toLong & dataMask
          if (outstanding.isEmpty) {
            failure = Some(s"response ctx=$gotCtx with no command outstanding")
          } else {
            val exp = outstanding.dequeue()
            if (gotCtx != exp.ctx) {
              failure = Some(
                s"OUT OF ORDER or MISPAIRED response: expected ctx=${exp.ctx} " +
                s"(${if (exp.isWrite) "write" else "read"} @0x${exp.addr.toHexString}), got ctx=$gotCtx")
            } else if (!exp.isWrite && gotData != exp.data) {
              failure = Some(
                f"WRONG READ DATA for ctx=${exp.ctx} @0x${exp.addr}%x: " +
                f"expected 0x${exp.data}%04x, got 0x$gotData%04x")
            }
            received += 1
          }
        }
      }
    }

    // Producer. NOTE THE LOOP SHAPE: `for (c <- cmds) { ... if (failure.isDefined)
    // return }` was the first version, and it made every test in this file
    // USELESS — a non-local `return` leaves runSequence before the
    // `failure.foreach(fail)` below, so a detected fault was swallowed and the
    // test passed. It passed against the known-broken adapter for that reason
    // alone. Stop the loop, do not leave the method.
    val it = cmds.iterator
    while (it.hasNext && failure.isEmpty) {
      val c = it.next()
      val resolved =
        if (c.isWrite) c else c.copy(data = expectedData(c.addr))
      if (c.isWrite) model(c.addr) = c.data
      outstanding.enqueue(resolved)

      dut.io.bus.cmd.valid   #= true
      dut.io.bus.cmd.address #= c.addr
      dut.io.bus.cmd.write   #= c.isWrite
      dut.io.bus.cmd.data    #= (if (c.isWrite) c.data else 0L)
      dut.io.bus.cmd.mask    #= (if (c.isWrite) (1 << layout.bytePerWord) - 1 else 0)
      dut.io.bus.cmd.context #= c.ctx
      dut.clockDomain.waitSamplingWhere(dut.io.bus.cmd.ready.toBoolean)
      dut.io.bus.cmd.valid #= false
    }
    dut.io.bus.cmd.valid #= false

    // Bounded wait — a lost response must fail as a timeout, not hang CI.
    val deadline = 200000
    var waited   = 0
    while (received < cmds.length && failure.isEmpty && waited < deadline) {
      dut.clockDomain.waitSampling()
      waited += 1
    }
    consumer.terminate()

    failure.foreach(f => fail(f))
    assert(received == cmds.length,
      s"only $received of ${cmds.length} responses came back " +
      s"(${outstanding.size} still outstanding) — read data was dropped")
  }

  /**
   * BUG 1 — read data dropped when the consumer stalls. Observed failure
   * against the pre-`ef36d99` adapter:
   *
   *   WRONG READ DATA for ctx=0 @0x0: expected 0xa5a5, got 0xa5f1
   *
   * Note the shape, because it is instructive: the response COUNT is right and
   * the contexts are in order — what is wrong is the DATA, shifted forward from
   * a later read. The old adapter popped its context FIFO on `rsp.ready` but
   * took data from a pulse that had already gone, so contexts kept marching in
   * order while data slid. Any check based on counting responses, or on
   * outstanding-transaction arithmetic, sails straight past this.
   *
   * The long `ready` gaps are the whole mechanism. With `ready` permanently
   * high the old adapter passes — see the control test at the end, which is why
   * nothing caught this before.
   */
  test("read data survives a consumer that stalls for long stretches") {
    simConfig.compile(Harness()).doSim(seed = 42) { dut =>
      val cmds = (0 until 64).map(i => Expected(ctx = i, isWrite = false, addr = i * 3, data = 0))
      // Accept for 2 cycles then refuse for 30 — far longer than the stub's
      // read latency, so pulses are guaranteed to arrive while stalled.
      runSequence(dut, cmds, cycle => (cycle % 32) < 2)
    }
  }

  /**
   * BUG 2 — a write response overtaking an outstanding read. Observed failure
   * against the pre-`ef36d99` adapter:
   *
   *   MISPAIRED response: expected ctx=2 (read @0x2), got ctx=3
   *
   * ctx=3 is the WRITE issued after that read: its response is manufactured
   * locally and available at once, while the read is still waiting on the stub,
   * so it arrives first and the consumer — which pairs by ORDER — hands the
   * write's `data := 0` to the read.
   *
   * Reads and writes must ALTERNATE for this; a run of one kind cannot expose
   * it.
   */
  test("a write response never answers an outstanding read") {
    simConfig.compile(Harness()).doSim(seed = 7) { dut =>
      val cmds = (0 until 64).map { i =>
        if (i % 2 == 0) Expected(ctx = i, isWrite = false, addr = i, data = 0)
        else            Expected(ctx = i, isWrite = true,  addr = i, data = (0x1234 + i) & dataMask)
      }
      // Mild stalling only — this bug does not need the consumer to be slow,
      // just for a write to be behind a read.
      runSequence(dut, cmds, cycle => (cycle % 4) != 0)
    }
  }

  /**
   * Writes must be visible to later reads, and read data must belong to the
   * address requested. Guards against "fixing" the ordering by dropping writes
   * on the floor, and against a response carrying a neighbour's data — the
   * stub seeds memory with a per-address formula precisely so that is visible.
   *
   * Also fails on the pre-`ef36d99` adapter (`expected ctx=15, got ctx=16`),
   * by the same overtaking mechanism as bug 2, since it interleaves writes and
   * reads by construction.
   */
  test("a read returns the value most recently written to that address") {
    simConfig.compile(Harness()).doSim(seed = 3) { dut =>
      val cmds = mutable.ArrayBuffer[Expected]()
      var ctx  = 0
      for (i <- 0 until 24) {
        val addr = (i * 37) % memWords
        cmds += Expected(ctx, isWrite = true, addr, (0xBEE0 + i) & dataMask); ctx += 1
        cmds += Expected(ctx, isWrite = false, addr, 0);                      ctx += 1
      }
      runSequence(dut, cmds.toSeq, _ => true)
    }
  }

  /**
   * The consumer never stalls and the commands are back to back. This is the
   * shape the adapter was presumably developed against, and it passes on the
   * BROKEN adapter too — kept as the control, so a future change that breaks
   * the easy case is still caught, and as a reminder of why the other two tests
   * have to be as awkward as they are.
   */
  test("back-to-back reads with a never-stalling consumer") {
    simConfig.compile(Harness()).doSim(seed = 11) { dut =>
      val cmds = (0 until 48).map(i => Expected(ctx = i, isWrite = false, addr = i + 100, data = 0))
      runSequence(dut, cmds, _ => true)
    }
  }
}
