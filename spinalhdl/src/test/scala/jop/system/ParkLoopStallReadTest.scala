package jop.system

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._
import jop.config.{BootMode, JopCoreConfig, MemoryStyle, MicrocodePaths}
import jop.io.SyncOut
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, JopSimDefaults}
import scala.collection.mutable

/**
 * A STALL MUST NOT CHANGE WHAT THE PARK LOOP READS — status item 161.
 *
 * THE FAULT. On the Wukong SMP + stack-cache build, a GC halt landing while core 1
 * is still parked in the microcode `cpux_loop` sometimes made it leave the spin
 * while `io_signal` was still zero (~21 % of gc-then-release runs, 0 of 103
 * release-then-gc). Item 161 eliminated five hypotheses by measurement and left
 * the cause unknown, with "only a cluster-level simulation" as the remaining
 * vehicle.
 *
 * THE CAUSE, found by item 160 and proved here. DecodeStage registers each instruction's
 * commands and applies them the next cycle, and during a stack-cache stall it
 * used to HOLD them. A held `stmra` keeps `rd` high, and `BmbMemoryController`
 * acts on `rd` in every cycle it sees it, at the address in A in THAT cycle:
 * an I/O read in IDLE is combinational and simply happens again, and a memory
 * read is re-issued each time the controller returns to IDLE. By the second
 * cycle `stmra` has popped its address, so A is the word UNDER it. In the park
 * loop,
 *
 *   cpux_loop:  ldi io_signal;  stmra;  wait;  wait;  ldmrd;  nop;  bz cpux_loop
 *
 * a stall that starts in the one cycle after `stmra` therefore re-reads that
 * word instead of `io_signal`. `ldmrd` takes the last read, and if it is not zero
 * the backward `bz` falls through: the item 161 symptom, with no corrupted data
 * anywhere. It needs exactly what item 161's measurements say it needs:
 *
 *   - a STACK-CACHE stall, because `decode.io.stall` is `stackRotBusy` and
 *     nothing else -- and for a parked core only the GC flush raises it (item 133
 *     found "the FLUSH of a parked core, not the HALT of one"). A halt alone only
 *     freezes the fetch stage at a `wait`;
 *   - a flush that begins in one particular cycle of the ten-cycle loop, so a
 *     rate per flush rather than a deterministic failure.
 *
 * WHY THE EARLIER VEHICLES COULD NOT SEE IT. `MicrocodeParkLoopFreezeTest`
 * serves `memRdData` itself and returns 0 for every read during the flush, so a
 * read at the wrong address reads as a correct zero. A `JopCore`-level attempt
 * (2026-09-29, reverted) never proved it was armed and failed its teeth check.
 *
 * WHAT THIS DRIVES. The real `JopCore` as core 1 of 2 -- pipeline, real serial
 * microcode, `BmbMemoryController`, `Sys` (so `io_cpu_id` and `io_signal` come
 * from the design), the stack cache and its DMA spilling to core 1's region at
 * the top of memory -- on a memory whose word 0 is non-zero. On the board word 0
 * is the downloaded image's LENGTH (`JOPizer` writes it first; `Startup` and
 * `GC` read it with `rdMem(0)`), so it is never zero once core 0 has loaded the
 * program the collector runs in. `s_out` stays false, so a correct core spins
 * for the whole test. Then the halt and the GC halt are raised together, as
 * CmpSync delivers them (`gcFlushReq := gcHaltActive && halted`), at every
 * cycle offset across several turns of the loop, held until the flush has
 * finished and for a while after, and released.
 *
 * THE PROPERTY names no addresses. A few clean iterations teach it the PCs the
 * loop visits and the reads it makes; afterwards neither set may grow -- not
 * during the halt, not after it. A failure reports the address the pc escaped
 * to and the reads it should never have made.
 *
 * ARMED, checked rather than assumed (the lesson of the reverted attempt):
 * every trial must actually stall the decode stage, and at least one trial of
 * the sweep must start that stall while `rd` is high -- the one cycle this
 * defect lives in. Without the second the sweep could be green and blind.
 *
 * RED, then GREEN (2026-10-06). With only `memRdReg := False` removed from
 * DecodeStage's stall clear -- the held `rd` and nothing else -- the window
 * phases fail, one per turn of the loop:
 *
 *   phase 2: left the park loop for 0x017; read mem byte 0x0, which the loop never reads
 *   (likewise phases 12 and 22; A during the held read: 0xfffffff7, then 0x0)
 *
 * -- `io_signal`'s address in the first cycle, then the word under it, memory
 * word 0, which reads non-zero, so `bz cpux_loop` falls through to the second
 * `io_signal` read at 0x017. Under the old ROM word 0x1d that read was
 * `bz cpu0_load`: the downloader, the hang item 133 measured. With the clear
 * restored the same three phases still start their stall while `rd` is high,
 * but `rd` is gone after that first cycle and nothing escapes.
 *
 * THE STALL LENGTHS IT PRINTS are ~770 cycles, or ~1465 at about half the
 * phases: a halted core keeps executing until its next `wait`, so when the halt
 * lands past the loop's `wait`s it pushes again after the first flush and a
 * second flush runs. `gcFlushDone` -- and with it the collector's
 * `othersHalted` -- reads true for the 2-6 cycles in between.
 */
class ParkLoopStallReadTest extends AnyFunSuite {

  /** Word 0 of memory: an image length. Any non-zero value. */
  private val ImageLength = 0x3c06

  /** Several turns of the ten-cycle loop, so every alignment is hit at least twice. */
  private val Phases = 24

  case class ParkCoreTb() extends Component {
    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024, stackRegionWordsPerCore = 8192),
      cpuId = 1,
      cpuCnt = 2,
      useStackCache = true,
      memoryStyle = Some(MemoryStyle.Generic))
    require(!config.memConfig.hasCardTable && !config.memConfig.hasBackendFill,
      "this bench wires neither a card table nor a backend fill")

    // THE MICROCODE THAT SHIPS, the serial variant, which is what the failing
    // board runs. A hand-written loop would test a park loop of this file's design.
    val jopCore = JopCore(
      config  = config,
      romInit = Some(JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))),
      ramInit = Some(JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))),
      jbcInit = Some(Seq.fill(config.jbcDepth)(BigInt(0))))
    require(jopCore.io.dmaBmb.isEmpty, "this bench wires no device DMA")

    val io = new Bundle {
      val halted       = in Bool()
      val gcHaltActive = in Bool()
      val pc         = out UInt(config.pcWidth bits)
      val aout       = out Bits(32 bits)
      val rotState   = out UInt(3 bits)
      val bankDirty  = out Bits(3 bits)
      val coreRd     = out Bool()
      val coreRdAddr = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    }

    // The lock manager, reduced to the two lines a stop-the-world drives. The
    // rest stay tied off by the shared helper, so a new SyncOut field cannot
    // leave this bench undriven.
    SyncOut.tieOff(jopCore.io.syncIn)
    jopCore.io.syncIn.halted.allowOverride()       := io.halted
    jopCore.io.syncIn.gcHaltActive.allowOverride() := io.gcHaltActive
    // A second core's port for reading another core's GC roots. Core 1 is not
    // the collector here and never reads it.
    jopCore.io.rootData := 0

    // The core's port and the stack DMA's share one memory, as on the board.
    val inputParam = config.memConfig.bmbParameter
    val source = inputParam.access.sources.values.head
    val outputParam = BmbParameter(
      access = BmbAccessParameter(
        addressWidth = inputParam.access.addressWidth,
        dataWidth    = inputParam.access.dataWidth
      ).addSources(2, BmbSourceParameter(
        contextWidth = source.contextWidth,
        lengthWidth  = source.lengthWidth,
        canWrite     = true,
        canRead      = true,
        alignment    = BmbParameter.BurstAlignement.WORD)),
      invalidation = BmbInvalidationParameter())
    val arbiter = BmbArbiter(
      inputsParameter    = Seq.fill(2)(inputParam),
      outputParameter    = outputParam,
      lowerFirstPriority = false)
    arbiter.io.inputs(0) << jopCore.io.bmb
    arbiter.io.inputs(1) << jopCore.io.stackDmaBmb.get
    val ram = BmbOnChipRam(p = outputParam, size = config.memConfig.mainMemSize, hexInit = null)
    val words = (config.memConfig.mainMemSize / 4).toInt
    ram.ram.init(Seq.tabulate(words)(i => B(if (i == 0) BigInt(ImageLength) else BigInt(0), 32 bits)))
    ram.io.bus << arbiter.io.output

    if (jopCore.devicePins.contains("uart")) jopCore.devicePin[Bool]("uart", "rxd") := True
    jopCore.io.debugRamAddr := 0
    jopCore.io.debugHalt := False
    jopCore.io.snoopIn.foreach { si =>
      si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
    }

    io.pc        := jopCore.io.pc
    io.aout      := jopCore.io.aout
    io.rotState  := jopCore.io.scDebugRotState.get
    io.bankDirty := jopCore.io.scDebugBankDirty.get
    // The core's own reads, not the DMA's.
    io.coreRd     := jopCore.io.bmb.cmd.fire && jopCore.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.READ
    io.coreRdAddr := jopCore.io.bmb.cmd.fragment.address

    // Grandchildren are observed, not wired out: a port driven from two levels
    // down is a hierarchy violation (JopCore.scala:118-123).
    jopCore.memCtrl.io.ioRd.simPublic()
    jopCore.memCtrl.io.ioAddr.simPublic()
    jopCore.memCtrl.io.memIn.rd.simPublic()
    jopCore.pipeline.decode.io.stall.simPublic()
  }

  /** One build serves every trial: a compile per trial is ~145 MB of model each. */
  private lazy val compiled = JopSimDefaults.config.compile(ParkCoreTb())

  private case class Trial(phase: Int, stallCycles: Int, windowHit: Boolean, aoutInWindow: Set[Long],
                           escapedTo: Option[Int], badReads: Seq[String])

  /** What the memory system is asked for this cycle, as comparable tokens. */
  private def reads(dut: ParkCoreTb): Seq[String] = {
    val io  = if (dut.jopCore.memCtrl.io.ioRd.toBoolean) Seq(f"io 0x${dut.jopCore.memCtrl.io.ioAddr.toInt}%02x") else Nil
    val mem = if (dut.io.coreRd.toBoolean) Seq(f"mem byte 0x${dut.io.coreRdAddr.toLong}%x") else Nil
    io ++ mem
  }

  private def trial(phase: Int): Trial = {
    var result: Trial = null
    compiled.doSim(s"park_read_p$phase") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.halted       #= false
      dut.io.gcHaltActive #= false
      dut.clockDomain.waitRisingEdge(5)

      // Reach the loop, then LEARN it. 400 cycles is far more than the boot
      // prologue needs and several loop turns beyond it.
      dut.clockDomain.waitRisingEdge(400)
      val loopPcs   = mutable.Set[Int]()
      val loopReads = mutable.Set[String]()
      for (_ <- 0 until 200) {
        loopPcs += dut.io.pc.toInt
        loopReads ++= reads(dut)
        dut.clockDomain.waitRisingEdge()
      }
      assert(loopPcs.size <= 16, s"phase $phase: not parked -- ${loopPcs.size} distinct PCs: " +
        loopPcs.toSeq.sorted.map(p => f"0x$p%03x").mkString(" "))
      assert(loopReads.nonEmpty, s"phase $phase: the park loop made no read the bench can see")
      // The flush only runs with a dirty bank (`gcFlushReq && anyDirty`); without
      // one nothing stalls and every trial passes for nothing.
      assert(dut.io.bankDirty.toInt != 0, s"phase $phase: no bank is dirty, so no flush would run")

      dut.clockDomain.waitRisingEdge(phase)

      var stallCycles = 0
      var windowHit = false
      val aoutInWindow = mutable.Set[Long]()
      val bad = mutable.LinkedHashSet[String]()
      var escapedTo: Option[Int] = None
      def observe(): Unit = {
        val stall = dut.jopCore.pipeline.decode.io.stall.toBoolean
        if (stall) stallCycles += 1
        if (stall && dut.jopCore.memCtrl.io.memIn.rd.toBoolean) {
          windowHit = true
          aoutInWindow += dut.io.aout.toLong
        }
        bad ++= reads(dut).filterNot(loopReads.contains)
        val pc = dut.io.pc.toInt
        if (escapedTo.isEmpty && !loopPcs.contains(pc)) escapedTo = Some(pc)
        dut.clockDomain.waitRisingEdge()
      }

      // THE STOP-THE-WORLD: halt and GC halt together. Held until the flush has
      // finished and the cache is idle, then as long again -- a collector keeps
      // the world stopped well past the flush.
      dut.io.halted       #= true
      dut.io.gcHaltActive #= true
      var guard = 0
      while ((dut.io.bankDirty.toInt != 0 || dut.io.rotState.toInt != 0) && guard < 5000) {
        observe()
        guard += 1
      }
      assert(guard < 5000, s"phase $phase: the flush never finished")
      for (_ <- 0 until 100) observe()
      dut.io.halted       #= false
      dut.io.gcHaltActive #= false

      // io_signal is still zero, so a correct core is still spinning.
      for (_ <- 0 until 600) observe()

      result = Trial(phase, stallCycles, windowHit, aoutInWindow.toSet, escapedTo, bad.toSeq)
    }
    result
  }

  test("a flush stall at any point of the park loop changes neither its reads nor its path") {
    val trials = (0 until Phases).map(trial)
    for (t <- trials)
      println(f"  phase ${t.phase}%2d: stall ${t.stallCycles}%4d cycles" +
        (if (t.windowHit) f", began while rd was high (A = ${t.aoutInWindow.toSeq.sorted.map(a => f"0x$a%x").mkString("/")})" else "") +
        t.escapedTo.map(pc => f", ESCAPED to 0x$pc%03x").getOrElse("") +
        (if (t.badReads.nonEmpty) s", READ ${t.badReads.mkString(", ")}" else ""))

    // A TEST THAT CANNOT FAIL IS WORSE THAN NO TEST.
    val unarmed = trials.filter(_.stallCycles == 0).map(_.phase)
    assert(unarmed.isEmpty, s"phases ${unarmed.mkString(" ")} never stalled the decode stage -- nothing was tested")
    assert(trials.exists(_.windowHit),
      "no trial started its stall while a read command was high, so this sweep cannot see a held read")

    val failed = trials.filter(t => t.escapedTo.isDefined || t.badReads.nonEmpty)
    assert(failed.isEmpty, failed.map { t =>
      f"phase ${t.phase}: " +
        t.escapedTo.map(pc => f"left the park loop for 0x$pc%03x; ").getOrElse("") +
        (if (t.badReads.nonEmpty) s"read ${t.badReads.mkString(", ")}, which the loop never reads" else "")
    }.mkString("\n  ", "\n  ", ""))
  }
}
