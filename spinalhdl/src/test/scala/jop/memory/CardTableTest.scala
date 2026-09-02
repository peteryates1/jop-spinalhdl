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

    // 6) A MARK ARRIVING DURING THE CLEAR-ALL SWEEP MUST SURVIVE — status item 131.
    //
    // Every case above waits the sweep out. `clrAll()` is defined at the top of
    // this file as "assert clrAll, then waitSampling(nWords + 4)", so the whole
    // suite has only ever marked while the table was idle. The software does
    // NOT do that: GC.java:2230 starts the sweep with `Native.wr(-1,
    // IO_CARD_CLEAR)` -- an I/O write that never leaves State.IDLE, so nothing
    // stalls -- and releases the other cores with `Native.wr(0, IO_GC_HALT)`
    // eight statements later. The sweep is cardWords32 cycles: 4096 on the
    // EP4CGX150 and the XC7A100T, 16384 on the A-E115FB. The mutators run for
    // essentially all of it.
    //
    // CardTable has ONE write port, and the sweep wins both MUXes
    // (CardTable.scala:107-112): a mark reaching stage 2 while clrAllActive has
    // its index AND its data replaced by the sweep's. No stall, no retry, no
    // backpressure. `io.clrBusy` is driven at :84 and read by nothing -- the
    // signal to gate on was built and never wired.
    //
    // This is the UNSAFE direction. GC.java:2228 states the safe one: "Leaving
    // cards dirty is always SAFE, only slower." A dropped mark leaves the card
    // CLEAN, so the next minor GC never scans the holder and a live object with
    // no other root is collected.
    //
    // The assertion is on the BIT, not on a cycle count, so it stays valid
    // whichever way the fix goes -- stall the mark pipeline, or have the GC poll
    // clrBusy before releasing IO_GC_HALT.
    clrAll()
    dut.io.clrAll #= true
    dut.clockDomain.waitSampling()
    dut.io.clrAll #= false
    // Mark card 7 in the middle of the sweep. nWords is 32 here, so cycle ~8 of
    // 32 is comfortably inside it and not at either boundary.
    dut.clockDomain.waitSampling(8)
    dut.io.markValid #= true
    dut.io.markAddr #= cardAddr(7)
    dut.clockDomain.waitSampling()
    dut.io.markValid #= false
    dut.clockDomain.waitSampling(nWords + 4)
    check(readWord(0) == (1L << 7),
      f"mark during clrAll was DROPPED: word0=0x${readWord(0)}%x expected 0x80. " +
      "One write port, and clrAllActive wins both MUXes (CardTable.scala:107-112). " +
      "io.clrBusy exists and is wired to nothing. Status item 131.")

    // 6b) THE CONTROL. The same mark, the same distance after the sweep has
    // FINISHED, must land -- otherwise 6 would fail for some unrelated reason
    // (a bad address, the readback timing, the test's own bookkeeping) and
    // would prove nothing about the sweep.
    clrAll()
    dut.clockDomain.waitSampling(8)
    markAddr(cardAddr(7))
    dut.clockDomain.waitSampling(nWords + 4)
    check(readWord(0) == (1L << 7),
      f"CONTROL: the same mark clear of the sweep should land; word0=0x${readWord(0)}%x " +
      "expected 0x80. If this fails, case 6 says nothing about clrAll.")

    println(if (fails == 0) "PASS: CardTable marks losslessly, gates, reads, clears" else s"FAILED ($fails)")
    if (fails != 0) simFailure(s"$fails checks failed")
  }
}
