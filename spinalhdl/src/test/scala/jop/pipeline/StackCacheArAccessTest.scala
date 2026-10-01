package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._
import jop.config.{JopCoreConfig, MemoryStyle}
import jop.memory.{JopMemoryConfig, StackCacheDma}
import jop.utils.JopSimDefaults

import scala.collection.mutable.ArrayBuffer

/**
 * AN AR-ADDRESSED STACK ACCESS OUTSIDE THE RESIDENT WINDOW — status item 133.
 *
 * `Native.rdIntMem`/`wrIntMem` are `star / nop / ldmi|stmi`: the address goes
 * through AR, and AR reaches no part of the rotation controller, which follows
 * SP (and VP). So a word the cache has evicted reads as ZERO — the read MUX
 * default — and a write to it is DROPPED, with no fault. Every whole-stack
 * walker is built on that primitive: `f_athrow`, the collector's own-stack root
 * scans, the RT context switch, `JVMHelp.trace`.
 *
 * Rotation cannot serve such an access — two attempts to drive it from AR were
 * measured worse and reverted. The rotation target is computed from the ACTIVE
 * bank (`activeBase - bankSize`), not from the missing address, so a word
 * several banks down is unreachable; and an overflow ZERO-fills, because above
 * the active bank is assumed dead, which stops being true once the window has
 * been dragged below SP.
 *
 * So the access is served where the word actually lives, without moving the
 * window: one DMA word to or from the spill region. And an AR address past this
 * core's region is a fault, because a write-around there would land in the next
 * core's stack.
 *
 * WHAT THIS DRIVES. StackStage + the real StackCacheDma + a RAM, with the
 * decode-stage stall emulated: an instruction's COMBINATIONAL controls (read and
 * write address selects, SP mux) are held while `rotationBusy` is high, exactly
 * as `DecodeStage` freezes `ir`, and its REGISTERED controls (enaA, selLmux,
 * enaAr) are applied in the cycle after the decode cycle that finally executed.
 * Instructions are issued one at a time, i.e. with a nop in every other decode
 * slot, which is a legal schedule of the real pipeline.
 *
 * The whole-machine version of the same question is `jvm.DeepIntMem` (and
 * `jvm.DeepGc`, `jvm.DeepThrow`) on `JopJvmTestsStackCacheBramSim`.
 */
class StackCacheArAccessTest extends AnyFunSuite {

  /** A whole number of banks, so the region edge never falls inside a bank. */
  val RegionWords: Int = 6 * 192
  /** First virtual address past this core's region. */
  val StackEnd: Int = 64 + RegionWords

  case class ArTb(regionWords: Int) extends Component {
    val coreCfg = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      useStackCache = true,
      spillBaseAddrOverride = Some(0),
      memoryStyle = Some(MemoryStyle.Generic))
    // The override leaves `spillWords` at 0, "unbounded". The edge fault needs
    // a known edge, so the extent is set here; the base stays 0, so virtual
    // word j lives at RAM word j - 64.
    val stackCfg = coreCfg.stackConfig.copy(
      cacheConfig = coreCfg.stackConfig.cacheConfig.map(_.copy(spillWords = regionWords)))
    val cc = stackCfg.cacheConfig.get

    val io = new Bundle {
      val din      = in Bits(32 bits)
      val selLmux  = in Bits(3 bits)
      val selAmux  = in Bool()
      val enaA     = in Bool()
      val enaAr    = in Bool()
      val selRda   = in Bits(3 bits)
      val selWra   = in Bits(3 bits)
      val wrEna    = in Bool()
      val selSmux  = in Bits(2 bits)

      val aout     = out Bits(32 bits)
      val sp       = out UInt(stackCfg.spWidth bits)
      val rotBusy  = out Bool()
      val spOv     = out Bool()
      val cmdFire  = out Bool()
      val cmdWrite = out Bool()
      val cmdAddr  = out UInt(coreCfg.memConfig.bmbParameter.access.addressWidth bits)
    }

    val stackStg = StackStage(stackCfg)
    val dma = StackCacheDma(cc, coreCfg.memConfig.bmbParameter)
    val mem = BmbOnChipRam(p = coreCfg.memConfig.bmbParameter, size = 64 * 1024, hexInit = null)
    mem.ram.simPublic()
    mem.io.bus.cmd << dma.io.bmb.cmd
    dma.io.bmb.rsp << mem.io.bus.rsp

    stackStg.io.din := io.din
    stackStg.io.dirAddr := 0
    stackStg.io.opd := 0
    stackStg.io.jpc := 0
    stackStg.io.selSub := False
    stackStg.io.selAmux := io.selAmux
    stackStg.io.enaA := io.enaA
    stackStg.io.selBmux := False
    stackStg.io.selLog := 0
    stackStg.io.selShf := 0
    stackStg.io.selLmux := io.selLmux
    stackStg.io.selImux := 0
    stackStg.io.selRmux := 0
    stackStg.io.selSmux := io.selSmux
    stackStg.io.selMmux := False          // write data = A, as for stmi (a pop)
    stackStg.io.selRda := io.selRda
    stackStg.io.selWra := io.selWra
    stackStg.io.wrEna := io.wrEna
    stackStg.io.enaB := False
    stackStg.io.enaVp := False
    stackStg.io.enaAr := io.enaAr
    stackStg.io.debugRamAddr := 0
    stackStg.io.debugRamWrAddr := 0
    stackStg.io.debugRamWrData := 0
    stackStg.io.debugRamWrEn := False
    stackStg.io.gcFlushReq := False

    // DMA wiring, as JopCore does it.
    stackStg.io.dmaBankRdAddr.get := dma.io.bankRdAddr
    dma.io.bankRdData := stackStg.io.dmaBankRdData.get
    stackStg.io.dmaBankWrAddr.get := dma.io.bankWrAddr
    stackStg.io.dmaBankWrData.get := dma.io.bankWrData
    stackStg.io.dmaBankWrEn.get := dma.io.bankWrEn
    stackStg.io.dmaBankSelect.get := dma.io.bankSelect
    dma.io.start := stackStg.io.dmaStart.get
    dma.io.isSpill := stackStg.io.dmaIsSpill.get
    dma.io.extAddr := stackStg.io.dmaExtAddr.get.resized
    dma.io.wordCount := stackStg.io.dmaWordCount.get
    dma.io.bank := stackStg.io.dmaBank.get
    dma.io.single := stackStg.io.dmaSingle.get
    dma.io.singleWrData := stackStg.io.dmaSingleWrData.get
    stackStg.io.dmaSingleRdData.get := dma.io.singleRdData
    stackStg.io.dmaBusy.get := dma.io.busy
    stackStg.io.dmaDone.get := dma.io.done

    io.aout := stackStg.io.aout
    io.sp := stackStg.io.debugSp
    io.rotBusy := stackStg.io.rotationBusy.get
    io.spOv := stackStg.io.spOv
    io.cmdFire := dma.io.bmb.cmd.fire
    io.cmdWrite := dma.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
    io.cmdAddr := dma.io.bmb.cmd.fragment.address
  }

  private lazy val compiled = JopSimDefaults.config.compile(ArTb(RegionWords))

  /**
   * NOT a whole number of banks: 1024 = 5 x 192 + 64, so the bank at
   * [1024, 1216) straddles the region's end at 1088. That is the shape of every
   * hardware config: banks sit at 64 + 192k and the region is 8192 words, which
   * 192 does not divide, so the bank at [8128, 8320) straddles the end at 8256.
   */
  val StraddleWords: Int = 1024
  private lazy val compiledStraddle = JopSimDefaults.config.compile(ArTb(StraddleWords))

  // ---------------------------------------------------------------------------
  // The decode-stage emulation
  // ---------------------------------------------------------------------------

  private def idleComb(dut: ArTb): Unit = {
    dut.io.selRda #= 6          // DecodeStage default: SP
    dut.io.selWra #= 6          // DecodeStage default: SPP
    dut.io.wrEna #= false
    dut.io.selSmux #= 0         // SP unchanged
  }

  private def idleReg(dut: ArTb): Unit = {
    dut.io.din #= 0
    dut.io.selLmux #= 0
    dut.io.selAmux #= false
    dut.io.enaA #= false
    dut.io.enaAr #= false
  }

  /**
   * One instruction. DECODE: the combinational controls, held while the stall
   * holds `ir`; the decode cycle that executes is the first one with
   * `rotationBusy` low. EXECUTE: the registered controls, one cycle later, with
   * a nop in the decode slot.
   */
  private def instr(dut: ArTb)(comb: => Unit)(reg: => Unit): Unit = {
    comb
    sleep(1)
    var held = 0
    while (dut.io.rotBusy.toBoolean) {
      dut.clockDomain.waitRisingEdge()
      sleep(1)
      held += 1
      assert(held < 200000, "the stall never released")
    }
    dut.clockDomain.waitRisingEdge()   // ends the decode cycle that executed
    idleComb(dut)
    reg
    dut.clockDomain.waitRisingEdge()   // ends the execute cycle
    idleReg(dut)
  }

  private def loadA(dut: ArTb, v: Long): Unit =
    instr(dut)(()) { dut.io.din #= v; dut.io.selLmux #= 4; dut.io.selAmux #= true; dut.io.enaA #= true }

  /** star: AR <- A. */
  private def star(dut: ArTb): Unit =
    instr(dut)(()) { dut.io.enaAr #= true }

  /** stsp: SP <- A, through smux, so the rotation controller sees it. */
  private def stsp(dut: ArTb, target: Int): Unit = {
    loadA(dut, target)
    instr(dut) { dut.io.selSmux #= 3 } (())
  }

  /** ldmi: A <- stack[AR]. */
  private def ldmi(dut: ArTb): Unit =
    instr(dut) { dut.io.selRda #= 5 } { dut.io.selLmux #= 2; dut.io.selAmux #= true; dut.io.enaA #= true }

  /** stmi: stack[AR] <- A. The write itself happens in the execute cycle. */
  private def stmi(dut: ArTb): Unit =
    instr(dut) { dut.io.selWra #= 5; dut.io.wrEna #= true } (())

  /** Let any stall the last instruction raised drain, as the next decode would. */
  private def drain(dut: ArTb): Unit =
    instr(dut)(())(())

  private def setAr(dut: ArTb, addr: Int): Unit = { loadA(dut, addr); star(dut) }

  private def readAr(dut: ArTb, addr: Int): Long = {
    setAr(dut, addr); loadA(dut, 0xDEAD0000L); ldmi(dut); drain(dut)
    dut.io.aout.toLong
  }

  private def writeAr(dut: ArTb, addr: Int, v: Long): Unit = {
    setAr(dut, addr); loadA(dut, v); stmi(dut); drain(dut)
  }

  /** The spill region's copy of virtual stack word j (base 0, so word j - 64). */
  private def spillWord(dut: ArTb, j: Int): Long =
    dut.mem.ram.getBigInt(j - 64).toLong

  /** Every DMA command and every spOv-high cycle, for the whole run. */
  private class Monitor(dut: ArTb) {
    val cmds = ArrayBuffer[(Boolean, Long)]()
    var spOvCycles = 0
    fork {
      while (true) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmdFire.toBoolean) cmds += ((dut.io.cmdWrite.toBoolean, dut.io.cmdAddr.toLong))
        if (dut.io.spOv.toBoolean) spOvCycles += 1
      }
    }
  }

  private def start(dut: ArTb): Monitor = {
    dut.clockDomain.forkStimulus(10)
    idleComb(dut); idleReg(dut)
    dut.clockDomain.waitRisingEdge(5)
    new Monitor(dut)
  }

  // ---------------------------------------------------------------------------

  test("an AR read and write outside the resident window are served from the spill region") {
    compiled.doSim("ar-around") { dut =>
      val mon = start(dut)
      val failures = ArrayBuffer[String]()
      def check(ok: Boolean, what: => String): Unit = if (!ok) failures += what

      val X = 100        // in bank 0 at reset: [64, 256)
      val Y = 120
      val Magic1 = 0x11223344L
      val Magic2 = 0x55667788L

      // CONTROL: both directions through AR while X is resident. If this
      // fails the emulation is wrong and nothing below means anything.
      writeAr(dut, X, Magic1)
      val r0 = readAr(dut, X)
      assert(r0 == Magic1, f"CONTROL: resident AR write/read returned 0x$r0%08X")

      // Drag the window up. Banks start at 64/256/448 -> [64, 640); SP 900
      // leaves it at [448, 1024), so bank 0 -- dirty with Magic1 -- is spilled.
      stsp(dut, 900)
      assert(dut.io.sp.toInt == 900, s"stsp did not land: sp=${dut.io.sp.toInt}")
      check(spillWord(dut, X) == Magic1,
        f"setup: X was not spilled (spill copy 0x${spillWord(dut, X)}%08X)")

      // READ a word the cache no longer holds.
      val r1 = readAr(dut, X)
      check(r1 == Magic1, f"READ-AROUND: rdIntMem of an evicted word returned 0x$r1%08X, not 0x$Magic1%08X")

      // WRITE one. It must reach the spill region -- that is where the next
      // fill of this range will read it from.
      writeAr(dut, Y, Magic2)
      check(spillWord(dut, Y) == Magic2,
        f"WRITE-AROUND: wrIntMem of an evicted word left the spill copy at 0x${spillWord(dut, Y)}%08X")
      val r2 = readAr(dut, Y)
      check(r2 == Magic2, f"read-back after write-around returned 0x$r2%08X")

      // And through the ordinary path: bring the window back down, so this
      // range is FILLED from memory, and read it while resident.
      stsp(dut, 150)
      val r3 = readAr(dut, Y)
      check(r3 == Magic2, f"after the window came back (fill), Y read 0x$r3%08X")
      val r4 = readAr(dut, X)
      check(r4 == Magic1, f"after the window came back (fill), X read 0x$r4%08X")

      // An in-region access, resident or not, is not a fault.
      check(mon.spOvCycles == 0, s"spOv was raised for ${mon.spOvCycles} cycles by in-region accesses")

      assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
    }
  }

  test("an AR access past this core's region faults and reaches no memory") {
    compiled.doSim("ar-edge") { dut =>
      val mon = start(dut)
      val failures = ArrayBuffer[String]()
      def check(ok: Boolean, what: => String): Unit = if (!ok) failures += what
      val regionBytes = RegionWords.toLong * 4

      // CONTROL: the region's last word is legal, so touching it is no fault.
      // (Whether it reaches memory is the other test's question.)
      val last = StackEnd - 1
      writeAr(dut, last, 0x0BADF00DL)
      readAr(dut, last)
      check(mon.spOvCycles == 0, "the region's last word raised spOv")

      // One word past it: read, then write.
      val before = mon.cmds.size
      readAr(dut, StackEnd)
      val afterRead = mon.spOvCycles
      check(afterRead > 0, s"an AR READ at $StackEnd (one past the region) raised no fault")

      writeAr(dut, StackEnd, 0x5A5A5A5AL)
      check(mon.spOvCycles > afterRead, s"an AR WRITE at $StackEnd raised no fault")

      val stray = mon.cmds.drop(before).filter { case (_, a) => a >= regionBytes }
      check(stray.isEmpty,
        "DMA reached past the region: " + stray.map { case (w, a) => f"${if (w) "WR" else "RD"}@0x$a%X" }.mkString(" "))

      assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
    }
  }

  /**
   * THE BANKS HAVE TO RESPECT THE EDGE TOO. The region is enforced for SP
   * (`spOv`) and for AR (the fault above), but a spill moves a WHOLE bank: one
   * that straddles the region's end writes the part past it into whatever is
   * there -- the base of the next core's stack, or, for core 0 at the top of
   * memory, an address that wraps to the bottom.
   *
   * And no overflow is needed: SP 1070 is inside the region and below the spOv
   * limit (1088 - 16). On hardware that is any stack between 8128 and 8240
   * words deep -- legal, no exception, and silent.
   */
  test("no spill or fill leaves the region, even when its last bank straddles the edge") {
    compiledStraddle.doSim("straddle") { dut =>
      val mon = start(dut)
      val failures = ArrayBuffer[String]()
      def check(ok: Boolean, what: => String): Unit = if (!ok) failures += what
      val regionBytes = StraddleWords.toLong * 4

      stsp(dut, 1070)                               // legal SP, in the straddling bank
      assert(dut.io.sp.toInt == 1070, s"stsp did not land: sp=${dut.io.sp.toInt}")
      writeAr(dut, 1060, 0x12345678L)               // dirty that bank, as a push would
      stsp(dut, 150)                                // evict it: a dirty spill

      check(mon.spOvCycles == 0, "a legal SP raised spOv")
      val stray = mon.cmds.filter { case (_, a) => a >= regionBytes }
      check(stray.isEmpty,
        s"${stray.size} DMA transfers past the region's end (byte ${regionBytes}): " +
        stray.take(4).map { case (w, a) => f"${if (w) "WR" else "RD"}@0x$a%X" }.mkString(" ") +
        (if (stray.size > 4) " ..." else ""))
      // The part inside the region must still have been written.
      check(spillWord(dut, 1060) == 0x12345678L,
        f"the in-region part of the bank was not spilled: 0x${spillWord(dut, 1060)}%08X")

      assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
    }
  }
}
