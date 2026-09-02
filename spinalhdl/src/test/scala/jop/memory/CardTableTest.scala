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
}
