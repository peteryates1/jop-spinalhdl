package jop.memory

import spinal.core._
import spinal.core.sim._
import jop.utils.JopSimDefaults

/**
 * Unit sim for CardTable: correct card marking, tenure-window gating, lossless
 * back-to-back same-word marking (the RMW forwarding path), readback, and clear.
 *
 * DISCIPLINE: docs/testing-discipline.md. Case 6 asserts the clrBusy INTERFACE
 * property rather than a cycle count, and 6b is its control. PROVED RED against
 * the pre-fix `clrBusy = clrAllActive`, which is low in the request cycle.
 */
object CardTableTest extends App {
  // Small geometry: 1024 cards (32 words), 4 words/card → covers 4096 words.
  val cardCount = 1024
  val cardShift = 2
  val wordAddrWidth = 20
  val nWords = cardCount / 32

  JopSimDefaults.config.compile(new CardTable(cardCount, cardShift, wordAddrWidth)).doSim { dut =>
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

    // 6c) THE DRAIN IS PART OF THE GUARANTEE, AND NOTHING ASSERTED IT — the
    // review of item 131 found that deleting `&& !markInFlight` from
    // CardTable.scala shortens the stall by exactly one cycle and every
    // existing assertion still passes (case 6 wants `gap >= nWords`, and the
    // sweep alone supplies that).
    //
    // Why the drain exists: the sweep must not begin while a mark is still in
    // the two register stages between the arbiter and the table. If it does,
    // that mark lands in a word the sweep has already passed and survives the
    // clear — a card marked in a table the collector believes it just emptied.
    //
    // The assertion is therefore RELATIVE, not absolute: request the clear with
    // a mark in flight and the busy window must be strictly longer than the
    // same request made with the pipeline quiet. A cycle count would be a
    // second copy of the geometry; a comparison is not.
    def busyWindow(withMarkInFlight: Boolean): Int = {
      while (dut.io.clrBusy.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling(3)
      if (withMarkInFlight) {
        // Issue the mark and request the clear on the very next edge, so the
        // mark is still in the pipeline when clrAll is sampled.
        markAddr(cardAddr(11))
        dut.io.clrAll #= true
        dut.clockDomain.waitSampling()
        dut.io.clrAll #= false
      } else {
        dut.io.clrAll #= true
        dut.clockDomain.waitSampling()
        dut.io.clrAll #= false
      }
      var n = 0
      while (dut.io.clrBusy.toBoolean && n < nWords * 3) { dut.clockDomain.waitSampling(); n += 1 }
      n
    }
    val quiet   = busyWindow(false)
    val inFlight = busyWindow(true)
    check(inFlight > quiet,
      s"the clear stalled for $inFlight cycles with a mark in flight and $quiet " +
      "with the pipeline quiet — they should differ, because the sweep must " +
      "wait for the mark to drain. Equal windows mean `!markInFlight` is not " +
      "gating the start. Status item 131.")

    // 6b) THE CONTROL, and the contract the fix relies on: a consumer that
    // honours clrBusy loses nothing. Wait for busy to fall, then mark. This
    // must pass BOTH before and after the fix — if it ever fails, case 6 says
    // nothing, because "the mark was lost" would have a second explanation.
    while (dut.io.clrBusy.toBoolean) dut.clockDomain.waitSampling()
    markAddr(cardAddr(7))
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == (1L << 7),
      f"CONTROL: a mark issued after clrBusy fell was lost; word0=0x${readWord(0)}%x expected 0x80")

    // 7) AN OUT-OF-RANGE WRITE MUST NOT STEAL THE GC READBACK PORT — item 132.
    //
    // `readAddr = Mux(mValid, wIdx, io.rdIdx)` gates the steal on mValid, which
    // is ANY BMB write anywhere in memory — not on inRange. A write outside the
    // tenure window has no RMW to perform (s1valid is RegNext(inRange), so no
    // write port activity follows) and yet it diverts the read port for that
    // cycle, so the collector's IO_CARD_DATA read returns a DIFFERENT word than
    // it asked for.
    //
    // The aliasing makes it concrete: with cardCount=1024 the index is 10 bits,
    // so word address 8192 — well past topWord=4096 — has cardIdx 2048, which
    // resizes to 0 and reads table word 0. A readback of word 3 comes back
    // holding word 0's contents.
    //
    // Why it matters on real hardware: a core owning a lock is EXEMPT from
    // gcHalt by design (Ihlu.scala:371, CmpSync.scala:143 — the owner must
    // finish its critical section or the cluster deadlocks), so the "collector
    // only reads with every core halted" comment on that Mux does not hold on
    // SMP. scanCardRange does one write+read pair per table word — 4096 per
    // minor GC on the EP4CGX150 — so with an exempt core running, a collision
    // is near-certain, and one collision silently skips 32 cards' worth of
    // tenure->nursery references.
    clrAll()
    markAddr(cardAddr(5))                       // word 0, bit 5  -> 0x20
    markAddr(cardAddr(100))                     // word 3, bit 4  -> 0x10
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == (1L << 5), f"setup: word0=0x${readWord(0)}%x expected 0x20")
    check(readWord(3) == (1L << 4), f"setup: word3=0x${readWord(3)}%x expected 0x10")

    // Present rdIdx=3 and collide with an OUT-OF-RANGE write. The two words
    // differ, so a stolen read is visible rather than coincidentally equal.
    def readWordDuring(idx: Int, wrAddr: Long): Long = {
      dut.io.rdIdx     #= idx
      dut.io.markValid #= true
      dut.io.markAddr  #= wrAddr
      dut.clockDomain.waitSampling()   // edge A: mValid <= true; read issued for idx
      dut.io.markValid #= false
      dut.clockDomain.waitSampling()   // edge B: mValid HIGH this cycle -> steal window
      dut.clockDomain.waitSampling()   // edge C: rdData = whatever edge B read
      dut.io.rdData.toLong
    }

    val stolen = readWordDuring(3, 8192)        // 8192 >= topWord (4096)
    check(stolen == (1L << 4),
      f"out-of-range write stole the readback: asked for word3 (0x10), got 0x$stolen%x " +
      "— 0x20 is word0, which is where word address 8192 aliases. Status item 132.")

    // 7b) AND NEITHER MAY AN IN-RANGE WRITE — the other half of item 132.
    //
    // Gating the steal on inRange fixed the out-of-range case but left this
    // one: an in-range write genuinely needs an RMW read, so with one memory
    // it must take the port and the collector gets the marked word instead of
    // the one it asked for. The comment that made that acceptable — "the
    // collector only reads with every core halted" — is false whenever a core
    // is exempt from gcHalt, which is by design for a lock owner.
    //
    // It cannot be arbitrated away: the RMW read is real work. But it does not
    // need the SAME COPY. Two mirrored tables, written together, let the mark
    // read one and the collector read the other, and neither ever waits.
    markAddr(cardAddr(5))                       // re-set word 0 bit 5
    dut.clockDomain.waitSampling(3)
    val duringInRange = readWordDuring(3, cardAddr(0))   // word address 0: IN range
    check(duringInRange == (1L << 4),
      f"in-range write stole the readback: asked for word3 (0x10), got 0x$duringInRange%x " +
      "— 0x20 is word0, the word that write was marking. Status item 132.")

    // 7c) THE CONTROL, and it is case 2 restated at this point in the run: the
    // RMW read must still work. If mirroring were done by simply deleting the
    // mark's read, back-to-back marks into one word would lose all but the
    // last, and case 7b above would pass for exactly the wrong reason.
    clrAll()
    dut.io.markValid #= true
    dut.io.markAddr #= cardAddr(8);  dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(9);  dut.clockDomain.waitSampling()
    dut.io.markAddr #= cardAddr(10); dut.clockDomain.waitSampling()
    dut.io.markValid #= false
    dut.clockDomain.waitSampling(3)
    check(readWord(0) == ((1L << 8) | (1L << 9) | (1L << 10)),
      f"CONTROL: back-to-back marks lost the RMW read; word0=0x${readWord(0)}%x expected 0x700")

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
    JopSimDefaults.config.compile(new CardTable(cardCnt, shift, 30)).doSim { dut =>
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
