package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb._
import jop.config.{JopCoreConfig, MemoryStyle}
import jop.memory.{JopMemoryConfig, StackCacheDma}
import jop.utils.JopSimDefaults

/**
 * DOES THE STOP-THE-WORLD FLUSH ALWAYS TERMINATE? — status item 133.
 *
 * The flush walks the dirty banks one per pass:
 *
 *   IDLE --(gcFlushReq && anyDirty)--> FLUSH_START --> FLUSH_WAIT --(dmaDone)--> IDLE
 *
 * `FLUSH_START` asserts `dmaStart` for ONE cycle and moves on; `FLUSH_WAIT` is
 * what samples `dmaDone`, which `StackCacheDma` emits as a ONE-CYCLE PULSE. A
 * `dmaStart` that is not accepted, or a `dmaDone` that lands outside
 * `FLUSH_WAIT`, leaves `rotState` stuck — and `rotBusy` drives
 * `extStall := stackRotBusy` (JopPipeline.scala:204), which freezes the fetch
 * PC outright. A stuck flush therefore freezes the core FOREVER, not briefly.
 *
 * WHY THIS EXISTS. Two faults on the Wukong SMP + stack-cache build end with
 * exactly that signature, `ROTBUSY` high and the PC pinned:
 *
 *   - defect B, a collector waiting on a core that can never report flushed.
 *     FIXED by flushing only a HALTED core (`Sys.scala`), which removed the
 *     trigger of flushing a running lock owner.
 *   - the startup fault, where a GC while core 1 is still parked in the
 *     microcode `cpux_loop` stops it ever reaching `main()`. Measured at 37 %
 *     (p = 0.006), and shown to require the stack cache: an `ep4cgx150Smp`
 *     build with no cache and no flush fails 0 of 9 in the same arm
 *     (p = 0.002).
 *
 * A parked core owns no lock, so it IS halted and therefore IS flushed — the
 * case the fix treats as safe. If the flush FSM can hang, the fix removed one
 * trigger and left the mechanism, and these are one defect rather than two.
 *
 * THE POINT OF A UNIT TEST HERE. The whole-machine simulation does NOT
 * reproduce the startup fault — three configurations and nine randomised-X
 * seeds, all clean — so the hunt has been stuck behind a 37 %-of-the-time
 * hardware reproduction. This drives the FSM directly instead: dirty a chosen
 * set of banks, assert `gcFlushReq`, and require `rotState` to return to
 * `IDLE`. It answers in seconds and needs no board.
 */
class StackCacheFlushTest extends AnyFunSuite {

  /** StackStage + its DMA + a memory for the DMA to spill into. */
  case class FlushTb() extends Component {
    val coreCfg = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      useStackCache = true,
      // A dedicated spill area at 0 keeps the test independent of the
      // per-core region arithmetic, which is item 133's other half.
      spillBaseAddrOverride = Some(0),
      memoryStyle = Some(MemoryStyle.Generic))
    val stackCfg = coreCfg.stackConfig
    val cc = stackCfg.cacheConfig.get

    val io = new Bundle {
      val wrEna      = in Bool()
      val wrAddr     = in UInt(stackCfg.spWidth bits)
      // A DIRECT-ADDRESS WRITE CANNOT LEAVE BANK 0. `dirAddr` is ramWidth = 8
      // bits ("Direct RAM address from decode (scratch only)"), so it reaches
      // 0..255 and bank 0 is 64..255. Banks 1 and 2 need the AR path, which is
      // spWidth wide: din -> A (selLmux=4, selAmux=1, enaA), A -> AR (enaAr),
      // then write at AR (selWra=5).
      val din        = in Bits(32 bits)
      val selLmux    = in Bits(3 bits)
      val selAmux    = in Bool()
      val enaA       = in Bool()
      val enaAr      = in Bool()
      val selWra     = in Bits(3 bits)
      // MEMORY BACK-PRESSURE. The real stack DMA shares the cluster arbiter
      // with the other core and talks to SDRAM; this testbench gives it a
      // private single-cycle RAM, which is the easiest path there is. Starving
      // the command channel is what arbiter contention actually looks like.
      val memStall   = in Bool()
      val gcFlushReq = in Bool()
      val gcFlushDone = out Bool()
      val rotState   = out UInt(3 bits)
      val bankDirty  = out Bits(3 bits)
      val rotBusy    = out Bool()
      val dmaBusy    = out Bool()
      val dmaStart   = out Bool()
      val dmaDone    = out Bool()
    }

    val stackStg = StackStage(stackCfg)
    val dma = StackCacheDma(cc, coreCfg.memConfig.bmbParameter)
    val mem = BmbOnChipRam(p = coreCfg.memConfig.bmbParameter, size = 64 * 1024, hexInit = null)
    mem.io.bus.cmd.valid := dma.io.bmb.cmd.valid && !io.memStall
    mem.io.bus.cmd.payload := dma.io.bmb.cmd.payload
    dma.io.bmb.cmd.ready := mem.io.bus.cmd.ready && !io.memStall
    dma.io.bmb.rsp << mem.io.bus.rsp

    // Control inputs: everything off except a DIRECT-ADDRESS write.
    // selWra = 7 selects `io.dirAddr` as the write address
    // (StackStage.scala:1148), so the test dirties whichever bank it chooses.
    // The DATA is irrelevant here -- only `bankDirty` matters -- so it is left
    // as whatever A holds.
    stackStg.io.din := io.din
    stackStg.io.dirAddr := io.wrAddr.resized
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
    stackStg.io.selSmux := 0
    stackStg.io.selMmux := False
    stackStg.io.selRda := 0
    stackStg.io.selWra := io.selWra
    stackStg.io.wrEna := io.wrEna
    stackStg.io.enaB := False
    stackStg.io.enaVp := False
    stackStg.io.enaAr := io.enaAr
    stackStg.io.debugRamAddr := 0
    stackStg.io.debugRamWrAddr := 0
    stackStg.io.debugRamWrData := 0
    stackStg.io.debugRamWrEn := False

    stackStg.io.gcFlushReq := io.gcFlushReq

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
    stackStg.io.dmaBusy.get := dma.io.busy
    stackStg.io.dmaDone.get := dma.io.done

    io.gcFlushDone := stackStg.io.gcFlushDone
    io.rotState := stackStg.io.scDebugRotState.get.resized
    io.bankDirty := stackStg.io.scDebugBankDirty.get
    io.rotBusy := stackStg.io.rotationBusy.get
    io.dmaBusy := dma.io.busy
    io.dmaStart := stackStg.io.dmaStart.get
    io.dmaDone := dma.io.done
  }

  private lazy val compiled = JopSimDefaults.config.compile(FlushTb())

  /** Bank i covers [64 + i*192, 64 + (i+1)*192). */
  private def bankAddr(i: Int) = 64 + i * 192 + 5

  private def defaults(dut: FlushTb): Unit = {
    dut.io.wrEna #= false; dut.io.wrAddr #= 0; dut.io.gcFlushReq #= false
    dut.io.din #= 0; dut.io.selLmux #= 0; dut.io.selAmux #= false
    dut.io.enaA #= false; dut.io.enaAr #= false; dut.io.selWra #= 7
    dut.io.memStall #= false
  }

  /** Write one word at `addr` through the AR path, marking its bank dirty. */
  private def dirtyAt(dut: FlushTb, addr: Int): Unit = {
    dut.io.din #= addr                 // A <- addr
    dut.io.selLmux #= 4                // lmux := din
    dut.io.selAmux #= true             // amux := lmux
    dut.io.enaA #= true
    dut.clockDomain.waitRisingEdge()
    dut.io.enaA #= false
    dut.io.enaAr #= true               // AR <- A
    dut.clockDomain.waitRisingEdge()
    dut.io.enaAr #= false
    dut.clockDomain.waitRisingEdge()
    dut.io.selWra #= 5                 // write address := AR
    dut.io.wrEna #= true
    dut.clockDomain.waitRisingEdge()
    dut.io.wrEna #= false
    dut.io.selWra #= 7
    dut.clockDomain.waitRisingEdge(2)
  }

  test("the stop-the-world flush terminates for every dirty-bank combination") {
    compiled.doSim("flush-terminates") { dut =>
      dut.clockDomain.forkStimulus(10)
      defaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      // All seven non-empty subsets of the three banks. Every previous
      // validation of this loop -- simulation and hardware -- had ONE dirty
      // bank (spMin 64 spMax 90), so the multi-bank cases are the ones with no
      // prior coverage.
      for (mask <- 1 to 7) {
        for (b <- 0 until 3 if (mask & (1 << b)) != 0) dirtyAt(dut, bankAddr(b))
        if (dut.io.bankDirty.toInt != mask) {
          println(f"[diag] mask $mask: bankDirty=${dut.io.bankDirty.toInt}%03d " +
                  f"rotState=${dut.io.rotState.toInt} rotBusy=${dut.io.rotBusy.toBoolean} " +
                  f"dmaBusy=${dut.io.dmaBusy.toBoolean}")
        }
        assert(dut.io.bankDirty.toInt == mask,
          f"setup failed for mask $mask%03d: bankDirty=${dut.io.bankDirty.toInt}%03d")

        dut.io.gcFlushReq #= true
        var cycles = 0
        val limit = 20000
        while (!dut.io.gcFlushDone.toBoolean && cycles < limit) {
          dut.clockDomain.waitRisingEdge(); cycles += 1
        }
        assert(dut.io.gcFlushDone.toBoolean,
          f"FLUSH DID NOT TERMINATE for dirty mask $mask%03d after $limit%,d cycles: " +
          f"rotState=${dut.io.rotState.toInt} rotBusy=${dut.io.rotBusy.toBoolean} " +
          f"dmaBusy=${dut.io.dmaBusy.toBoolean} bankDirty=${dut.io.bankDirty.toInt}%03d")
        assert(dut.io.bankDirty.toInt == 0,
          f"flush reported done with banks still dirty: ${dut.io.bankDirty.toInt}%03d")
        dut.io.gcFlushReq #= false
        dut.clockDomain.waitRisingEdge(3)
      }
    }
  }

  /**
   * THE RACE THIS IS REALLY HUNTING. On hardware the request arrives at an
   * arbitrary moment relative to the DMA and the write stream, and the fault
   * is intermittent — so the phase is the variable. Sweep the cycle offset
   * between the last dirtying write and `gcFlushReq`, which is the alignment
   * a real `gcHalt` picks at random.
   */
  test("the flush terminates whatever the phase of the request") {
    compiled.doSim("flush-phase-sweep") { dut =>
      dut.clockDomain.forkStimulus(10)
      defaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      for (phase <- 0 until 40) {
        dut.io.gcFlushReq #= false
        for (b <- 0 until 3) dirtyAt(dut, bankAddr(b))
        dut.clockDomain.waitRisingEdge(phase)   // the variable under test

        dut.io.gcFlushReq #= true
        var cycles = 0
        while (!dut.io.gcFlushDone.toBoolean && cycles < 20000) {
          dut.clockDomain.waitRisingEdge(); cycles += 1
        }
        assert(dut.io.gcFlushDone.toBoolean,
          f"FLUSH HUNG at phase $phase: rotState=${dut.io.rotState.toInt} " +
          f"rotBusy=${dut.io.rotBusy.toBoolean} dmaBusy=${dut.io.dmaBusy.toBoolean} " +
          f"bankDirty=${dut.io.bankDirty.toInt}%03d")
        dut.io.gcFlushReq #= false
        dut.clockDomain.waitRisingEdge(3)
      }
    }
  }

  /**
   * A PARKED CORE IS STILL EXECUTING MICROCODE. The startup fault's victim sits
   * in `cpux_loop` polling `IO_SIGNAL`, so it keeps writing its stack while the
   * flush runs — it is `halted` in the lock unit's sense, but the microcode
   * loop is not stopped by that alone. This keeps the write stream going across
   * the request, which is the condition the flush's own comment assumes away:
   * "Nothing can dirty a bank meanwhile -- the core is halted."
   */
  test("the flush terminates even while the stack is still being written") {
    compiled.doSim("flush-with-writes") { dut =>
      dut.clockDomain.forkStimulus(10)
      defaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      // A write every few cycles, for the whole run.
      var writing = true
      fork {
        var i = 0
        while (writing) {
          dirtyAt(dut, bankAddr(i % 3))
          i += 1
        }
      }

      dut.clockDomain.waitRisingEdge(20)
      dut.io.gcFlushReq #= true
      var cycles = 0
      while (!dut.io.gcFlushDone.toBoolean && cycles < 50000) {
        dut.clockDomain.waitRisingEdge(); cycles += 1
      }
      val done = dut.io.gcFlushDone.toBoolean
      val st = dut.io.rotState.toInt
      val busy = dut.io.rotBusy.toBoolean
      writing = false
      dut.clockDomain.waitRisingEdge(10)

      // A flush that never completes while writes continue is a LIVELOCK, and
      // it is one of the two mechanisms defect B's diagnosis left undecided.
      // Recorded as the failure message rather than asserted away, because
      // which one it is decides the fix.
      assert(done,
        f"FLUSH DID NOT TERMINATE while the stack was being written: " +
        f"rotState=$st rotBusy=$busy — consistent with the LIVELOCK arm of " +
        "item 133's undecided question (IDLE -> FLUSH -> IDLE, re-dirtying " +
        "between passes) rather than a stuck FLUSH_WAIT")
    }
  }

  /**
   * STARVE THE DMA MID-FLUSH. `FLUSH_WAIT` waits for a `dmaDone` pulse that
   * only arrives once the DMA's BMB write completes. In the real cluster that
   * bus is shared with the other core and backed by SDRAM, so the transfer can
   * be held off for a long time — which this testbench's private single-cycle
   * RAM never does. If a stalled command can lose the handshake, the flush
   * never finishes and the core is frozen for good.
   */
  test("the flush terminates even when memory stalls mid-transfer") {
    compiled.doSim("flush-mem-stall") { dut =>
      dut.clockDomain.forkStimulus(10)
      defaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      for (stallAt <- Seq(0, 1, 2, 3, 5, 8, 13, 21)) {
        for (b <- 0 until 3) dirtyAt(dut, bankAddr(b))
        dut.io.gcFlushReq #= true
        dut.clockDomain.waitRisingEdge(stallAt)   // stall at varying depth
        dut.io.memStall #= true
        dut.clockDomain.waitRisingEdge(50)
        dut.io.memStall #= false

        var cycles = 0
        while (!dut.io.gcFlushDone.toBoolean && cycles < 50000) {
          dut.clockDomain.waitRisingEdge(); cycles += 1
        }
        assert(dut.io.gcFlushDone.toBoolean,
          f"FLUSH HUNG after a memory stall at offset $stallAt: " +
          f"rotState=${dut.io.rotState.toInt} rotBusy=${dut.io.rotBusy.toBoolean} " +
          f"dmaBusy=${dut.io.dmaBusy.toBoolean} bankDirty=${dut.io.bankDirty.toInt}%03d")
        dut.io.gcFlushReq #= false
        dut.clockDomain.waitRisingEdge(3)
      }
    }
  }
}
