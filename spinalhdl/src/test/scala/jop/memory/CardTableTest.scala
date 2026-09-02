package jop.memory

import spinal.core._
import spinal.core.sim._

/**
 * Unit sim for CardTable: correct card marking, tenure-window gating, lossless
 * back-to-back same-word marking (the RMW forwarding path), readback, and clear.
 */
object CardTableTest extends App {
  // Small geometry: 1024 cards (32 words), 4 words/card → covers 4096 words.
  val cardCount = 1024
  val cardShift = 2
  val wordAddrWidth = 20
  val nWords = cardCount / 32

  SimConfig.compile(new CardTable(cardCount, cardShift, wordAddrWidth)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    var fails = 0
    def check(cond: Boolean, msg: String): Unit = { if (!cond) { println(s"FAIL: $msg"); fails += 1 } }

    // defaults
    dut.io.markValid #= false
    dut.io.markAddr  #= 0
    dut.io.baseWord  #= 0
    dut.io.topWord   #= 4096
    dut.io.rdIdx     #= 0
    dut.io.clrEn     #= false
    dut.io.clrIdx    #= 0
    dut.io.clrAll    #= false
    dut.clockDomain.waitSampling(2)

    // card c is covered by word (c>>5), bit (c&31); its first word address = c<<cardShift
    def cardAddr(c: Int): Long = (c.toLong << cardShift)
    def markAddr(addr: Long): Unit = {
      dut.io.markValid #= true; dut.io.markAddr #= addr
      dut.clockDomain.waitSampling()
      dut.io.markValid #= false
    }
    def readWord(idx: Int): Long = {
      dut.io.markValid #= false; dut.io.rdIdx #= idx
      dut.clockDomain.waitSampling()          // present idx
      dut.clockDomain.waitSampling()          // rdData valid (readSync)
      dut.io.rdData.toLong
    }
    def clrAll(): Unit = {
      dut.io.clrAll #= true; dut.clockDomain.waitSampling(); dut.io.clrAll #= false
      dut.clockDomain.waitSampling(nWords + 4)
    }

    // 1) single mark sets exactly one bit
    clrAll()
    markAddr(cardAddr(5))                       // card 5 -> word 0, bit 5
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == (1L << 5), f"single mark: word0=0x${readWord(0)}%x expected 0x20")
    check(readWord(1) == 0, "single mark: word1 should be 0")

    // 2) LOSSLESS back-to-back same-word: cards 0,1,2 all in word 0, consecutive cycles
    clrAll()
    dut.io.markValid #= true
    dut.io.markAddr #= cardAddr(0); dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(1); dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(2); dut.clockDomain.waitSampling()
    dut.io.markValid #= false
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == 0x7, f"back-to-back same word: word0=0x${readWord(0)}%x expected 0x7")

    // 2b) interleaved X,Y,X consecutive (card 3=word0, card 40=word1, card 3 again)
    clrAll()
    dut.io.markValid #= true
    dut.io.markAddr #= cardAddr(3);  dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(40); dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(3);  dut.clockDomain.waitSampling()  // re-touch word0
    dut.io.markValid #= false
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == (1L << 3), f"interleaved: word0=0x${readWord(0)}%x expected 0x8")
    check(readWord(1) == (1L << (40 - 32)), f"interleaved: word1=0x${readWord(1)}%x expected 0x100")

    // 3) tenure-window gating: writes outside [base,top) do not mark
    clrAll()
    dut.io.baseWord #= 100; dut.io.topWord #= 200
    markAddr(cardAddr(0))     // word addr 0 < base -> ignored
    markAddr(150 << 0)        // 150 in range -> marks card (150>>2)=37 -> word1 bit5
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == 0, f"gating: below-base marked word0=0x${readWord(0)}%x")
    check(readWord(1) == (1L << (37 - 32)), f"gating: in-range word1=0x${readWord(1)}%x expected 0x20")
    dut.io.baseWord #= 0; dut.io.topWord #= 4096

    // 4) single-word clear
    clrAll()
    markAddr(cardAddr(5)); markAddr(cardAddr(33))
    dut.clockDomain.waitSampling(3)
    check(readWord(0) != 0 && readWord(1) != 0, "pre-clear both words set")
    dut.io.clrEn #= true; dut.io.clrIdx #= 0; dut.clockDomain.waitSampling(); dut.io.clrEn #= false
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == 0, f"clear word0: got 0x${readWord(0)}%x")
    check(readWord(1) == (1L << 1), f"clear word0 left word1: got 0x${readWord(1)}%x expected 0x2")

    // 5) clrAll zeroes everything
    markAddr(cardAddr(300))
    dut.clockDomain.waitSampling(3)
    clrAll()
    var anySet = false
    for (i <- 0 until nWords) if (readWord(i) != 0) anySet = true
    check(!anySet, "clrAll: some word non-zero after clear")

    // 6) clrBusy MUST COVER THE WHOLE CLEAR, WITH NO GAP — status item 131.
    //
    // The table has ONE write port and the sweep wins both MUXes
    // (CardTable.scala), so a mark arriving mid-sweep is silently dropped. That
    // cannot be fixed inside the component: giving the mark priority still
    // loses any mark landing on a word the sweep has not yet reached, and
    // buffering marks for replay is unbounded. The overlap has to be made
    // impossible instead, which means the PRODUCER must stall — so the whole
    // guarantee rests on clrBusy being trustworthy.
    //
    // "Trustworthy" means high from the cycle the request is seen. Before the
    // fix clrBusy was just clrAllActive, a register that sets on the NEXT edge,
    // so a core sampling it in the cycle it issues `Native.wr(-1,
    // IO_CARD_CLEAR)` read 0, did not stall, and returned with the entire
    // 4096-cycle sweep still ahead of it. GC.java:2239 then releases
    // IO_GC_HALT and every mark for the rest of the sweep is lost.
    //
    // This asserts the interface property rather than a cycle count, so it
    // holds whichever way the stall is plumbed.
    clrAll()
    dut.io.clrAll #= true
    dut.clockDomain.waitSampling()
    val busyAtRequest = dut.io.clrBusy.toBoolean
    dut.io.clrAll #= false
    check(busyAtRequest,
      "clrBusy was LOW in the cycle the clear was requested. A core sampling it " +
      "then does not stall, returns from the I/O write, and the mutators mark " +
      "into a sweep that drops them. Status item 131.")
    var gap = -1
    var swept = false
    var c = 0
    while (!swept && c < nWords * 3) {
      if (!dut.io.clrBusy.toBoolean) { if (gap < 0) gap = c; swept = true }
      dut.clockDomain.waitSampling(); c += 1
    }
    check(swept, "clrBusy never fell — the sweep did not finish")
    check(gap >= nWords,
      s"clrBusy fell after $gap cycles but the sweep needs at least $nWords. " +
      "Busy must span the drain and the whole sweep, not part of it.")

    // 6b) THE CONTROL, and the contract the fix relies on: a consumer that
    // honours clrBusy loses nothing. Wait for busy to fall, then mark. This
    // must pass BOTH before and after the fix — if it ever fails, case 6 says
    // nothing, because "the mark was lost" would have a second explanation.
    while (dut.io.clrBusy.toBoolean) dut.clockDomain.waitSampling()
    markAddr(cardAddr(7))
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == (1L << 7),
      f"CONTROL: a mark issued after clrBusy fell was lost; word0=0x${readWord(0)}%x expected 0x80")

    println(if (fails == 0) "PASS: CardTable marks losslessly, gates, reads, clears" else s"FAILED ($fails)")
    if (fails != 0) simFailure(s"$fails checks failed")
  }

  // ==========================================================================
  // THE CLEAR-BUSY CONTRACT AT EVERY GEOMETRY A REAL BOARD USES — item 131.
  //
  // The cases above run one small geometry (32 table words) because they are
  // tuned to cardShift = 2. But the sweep length is `cardWords32`, which varies
  // 16x across the boards, and it is the ONLY thing about this fix that differs
  // between them: the stall is a parameter, not a property of the memory system
  // (CardTable's sole bus contact is `bmb.cmd.fire && isWrite`, above any
  // backend).
  //
  // That matters because the A-E115FB — the board with by far the longest sweep
  // at 16384 words, ~218 us at 75 MHz — could not be programmed on 2026-09-02
  // (JTAG chain dead, `Captured DR = ()`). Its geometry is covered here
  // instead. This is not a substitute for running on DDR2, and the item says
  // so; it is a substitute for running on DDR2 *to check the sweep length*,
  // which is what that board would actually have contributed.
  //
  // idxWidth is log2Up(cardCount/32), so each geometry exercises a different
  // counter width and a different terminal-count comparison.
  // ==========================================================================
  val geometries = Seq(
    ("cyc5000Serial      4 KB budget",  32768, 6),
    ("colorlightI5Sdram  8 KB budget",  65536, 5),
    ("ep4cgx150Serial   16 KB budget", 131072, 4),
    ("wukongFull        16 KB budget", 131072, 9),
    ("ae115fbDdr2       64 KB budget", 524288, 9))

  var geoFails = 0
  for ((name, cardCnt, shift) <- geometries) {
    val n = cardCnt / 32
    SimConfig.compile(new CardTable(cardCnt, shift, 30)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.markValid #= false; dut.io.markAddr #= 0
      dut.io.baseWord #= 0; dut.io.topWord #= (BigInt(1) << 29)
      dut.io.rdIdx #= 0; dut.io.clrEn #= false; dut.io.clrIdx #= 0; dut.io.clrAll #= false
      dut.clockDomain.waitSampling(2)

      // Busy must be high in the request cycle and stay high for the whole
      // sweep. A gap anywhere is a window in which the core does not stall.
      dut.io.clrAll #= true
      dut.clockDomain.waitSampling()
      val atRequest = dut.io.clrBusy.toBoolean
      dut.io.clrAll #= false
      var held = 0
      while (dut.io.clrBusy.toBoolean && held < n * 3) { dut.clockDomain.waitSampling(); held += 1 }

      // And a mark issued once busy has fallen must land.
      dut.io.markValid #= true; dut.io.markAddr #= (BigInt(7) << shift)
      dut.clockDomain.waitSampling()
      dut.io.markValid #= false
      dut.clockDomain.waitSampling(4)
      dut.io.rdIdx #= 0
      dut.clockDomain.waitSampling(2)
      val w0 = dut.io.rdData.toLong

      val ok = atRequest && held >= n && w0 == (1L << 7)
      if (!ok) geoFails += 1
      println(f"  ${if (ok) "ok  " else "FAIL"} $name%-32s nWords=$n%6d " +
              f"busyAtRequest=$atRequest held=$held word0=0x$w0%x")
    }
  }
  println(if (geoFails == 0) s"PASS: clrBusy covers the sweep at all ${geometries.size} board geometries"
          else s"FAILED ($geoFails geometries)")
  if (geoFails != 0) simFailure(s"$geoFails geometries failed")
}
