package jop.memory

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import org.scalatest.funsuite.AnyFunSuite
import jop.config.MemoryDevice

/**
 * Test harness for BmbSdramCtrlWide with the SDRAM interface exposed.
 *
 * lengthWidth = 4 matches what JopMemoryConfig derives for a burst-enabled
 * SDRAM build (burstLen = 4 words = 16 bytes), so the burst tests exercise the
 * real shape rather than a synthetic one. The 16-bit bridge's test uses
 * lengthWidth = 2 and therefore never covers bursts at all.
 */
case class BmbSdramCtrlWideTestHarness(
  md: MemoryDevice = MemoryDevice.EM638325BK6H
) extends Component {

  val bmbParam = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = 23,   // 8 MB
      dataWidth = 32
    ).addSources(1, BmbSourceParameter(
      contextWidth = 4,
      lengthWidth = 4,
      canWrite = true,
      canRead = true,
      alignment = BmbParameter.BurstAlignement.WORD
    ))
  )

  val io = new Bundle {
    val bmb = slave(Bmb(bmbParam))
    val sdram = master(SdramInterface(SdramDeviceInfo.layoutFor(md)))
    val fill = slave(MemFill(bmbParam.access.addressWidth - 2))
  }

  val ctrl = BmbSdramCtrlWide(
    bmbParameter = bmbParam,
    layout = SdramDeviceInfo.layoutFor(md),
    timing = SdramDeviceInfo.timingFor(md),
    CAS = md.casLatency
  )

  io.bmb <> ctrl.io.bmb
  io.sdram <> ctrl.io.sdram
  ctrl.io.fill <> io.fill
}

/**
 * Unit tests for BmbSdramCtrlWide — the 32-bit BMB to 32-bit SDRAM bridge used
 * by the Colorlight i5 (EM638325BK-6H).
 */
class BmbSdramCtrlWideTest extends AnyFunSuite {

  val md = MemoryDevice.EM638325BK6H
  val layout = SdramDeviceInfo.layoutFor(md)

  // SDRAM power-up + init sequence. tPOW is 200 us, so at 100 MHz that is
  // 20000 cycles; 40000 leaves generous margin for the mode-register and
  // boot-refresh phase that follows.
  val initCycles = 40000

  def initBmb(dut: BmbSdramCtrlWideTestHarness): Unit = {
    dut.io.bmb.cmd.valid #= false
    dut.io.bmb.cmd.last #= true
    dut.io.bmb.cmd.fragment.opcode #= 0
    dut.io.bmb.cmd.fragment.address #= 0
    dut.io.bmb.cmd.fragment.length #= 3
    dut.io.bmb.cmd.fragment.source #= 0
    dut.io.bmb.cmd.fragment.context #= 0
    dut.io.bmb.cmd.fragment.data #= 0
    dut.io.bmb.cmd.fragment.mask #= 0xF
    dut.io.bmb.rsp.ready #= true
    dut.io.fill.cmd #= false
    dut.io.fill.start #= 0
    dut.io.fill.end #= 0
    dut.io.fill.value #= 0
  }

  def mkModel(dut: BmbSdramCtrlWideTestHarness) =
    SdramModel(io = dut.io.sdram, layout = layout, clockDomain = dut.clockDomain)

  /** Seed a 32-bit word into the model (little-endian bytes). */
  def poke(model: SdramModel, wordAddr: Int, word: Long): Unit = {
    val base = wordAddr * 4
    for (b <- 0 until 4) model.write(base + b, ((word >> (8 * b)) & 0xFF).toByte)
  }

  /**
   * Issue a single-word BMB read.
   *
   * BMB requires cmd.valid held until fire, then dropped immediately. This
   * bridge forwards cmd.valid straight to SdramCtrl on the single-word path, so
   * an extra cycle of valid would issue a spurious second SDRAM command.
   */
  def bmbRead(dut: BmbSdramCtrlWideTestHarness, wordAddr: Int): Long = {
    dut.io.bmb.cmd.valid #= true
    dut.io.bmb.cmd.fragment.opcode #= 0
    dut.io.bmb.cmd.fragment.address #= wordAddr * 4
    dut.io.bmb.cmd.fragment.length #= 3
    dut.io.bmb.cmd.fragment.mask #= 0xF

    dut.clockDomain.waitSampling()
    var t = 500
    while (!dut.io.bmb.cmd.ready.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, s"read cmd not accepted (word $wordAddr)")
    dut.io.bmb.cmd.valid #= false

    dut.clockDomain.waitSampling()
    t = 500
    while (!dut.io.bmb.rsp.valid.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, s"read rsp not received (word $wordAddr)")
    val d = dut.io.bmb.rsp.fragment.data.toLong & 0xFFFFFFFFL
    dut.clockDomain.waitSampling()
    d
  }

  def bmbWrite(dut: BmbSdramCtrlWideTestHarness, wordAddr: Int, data: Long, mask: Int = 0xF): Unit = {
    dut.io.bmb.cmd.valid #= true
    dut.io.bmb.cmd.fragment.opcode #= 1
    dut.io.bmb.cmd.fragment.address #= wordAddr * 4
    dut.io.bmb.cmd.fragment.length #= 3
    dut.io.bmb.cmd.fragment.data #= data
    dut.io.bmb.cmd.fragment.mask #= mask

    dut.clockDomain.waitSampling()
    var t = 500
    while (!dut.io.bmb.cmd.ready.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, s"write cmd not accepted (word $wordAddr)")
    dut.io.bmb.cmd.valid #= false

    dut.clockDomain.waitSampling()
    t = 500
    while (!dut.io.bmb.rsp.valid.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, s"write rsp not received (word $wordAddr)")
    dut.clockDomain.waitSampling()
  }

  /** Issue a burst read of `words` words and collect every response beat. */
  def bmbBurstRead(dut: BmbSdramCtrlWideTestHarness, wordAddr: Int, words: Int): Seq[Long] = {
    dut.io.bmb.cmd.valid #= true
    dut.io.bmb.cmd.fragment.opcode #= 0
    dut.io.bmb.cmd.fragment.address #= wordAddr * 4
    dut.io.bmb.cmd.fragment.length #= words * 4 - 1
    dut.io.bmb.cmd.fragment.mask #= 0xF

    dut.clockDomain.waitSampling()
    var t = 500
    while (!dut.io.bmb.cmd.ready.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, s"burst cmd not accepted (word $wordAddr)")
    dut.io.bmb.cmd.valid #= false

    val out = scala.collection.mutable.ArrayBuffer[Long]()
    var sawLast = false
    t = 5000
    while (!sawLast && t > 0) {
      dut.clockDomain.waitSampling()
      if (dut.io.bmb.rsp.valid.toBoolean && dut.io.bmb.rsp.ready.toBoolean) {
        out += (dut.io.bmb.rsp.fragment.data.toLong & 0xFFFFFFFFL)
        if (dut.io.bmb.rsp.last.toBoolean) sawLast = true
      }
      t -= 1
    }
    assert(t > 0, s"burst read did not complete (word $wordAddr, got ${out.length}/$words)")
    out.toSeq
  }

  def fillRange(dut: BmbSdramCtrlWideTestHarness, startWord: Int, endWord: Int, value: Long = 0): Unit = {
    dut.io.fill.start #= startWord
    dut.io.fill.end #= endWord
    dut.io.fill.value #= value
    dut.io.fill.cmd #= true
    var t = 10000
    while (!dut.io.fill.busy.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, "fill never became busy (command not latched)")
    dut.io.fill.cmd #= false
    t = 2000000
    while (dut.io.fill.busy.toBoolean && t > 0) { dut.clockDomain.waitSampling(); t -= 1 }
    assert(t > 0, "fill did not complete (busy stuck)")
  }

  def sim(body: (BmbSdramCtrlWideTestHarness, SdramModel) => Unit): Unit =
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .compile(BmbSdramCtrlWideTestHarness())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        initBmb(dut)
        val model = mkModel(dut)
        body(dut, model)
      }

  // ------------------------------------------------------------------------

  test("BmbSdramCtrlWide: layout is 32-bit and 8 MB") {
    assert(layout.dataWidth == 32, s"expected 32-bit SDRAM, got ${layout.dataWidth}")
    assert(layout.bytePerWord == 4, s"expected 4 bytes/word, got ${layout.bytePerWord}")
    val bytes = (BigInt(1) << (layout.bankWidth + layout.rowWidth + layout.columnWidth)) * layout.bytePerWord
    assert(bytes == BigInt(8) * 1024 * 1024, s"expected 8 MB, got $bytes bytes")
    // One SDRAM access per JOP word is the whole point of this bridge.
    assert(layout.wordAddressWidth == 21, s"expected 21-bit word address, got ${layout.wordAddressWidth}")
  }

  test("BmbSdramCtrlWide: read pre-initialised SDRAM data") {
    sim { (dut, model) =>
      val words = Seq(
        0xDEADBEEFL, 0x12345678L, 0xCAFEBABEL, 0x00000001L,
        0xFFFFFFFFL, 0x00000000L, 0x80000000L, 0x7FFFFFFFL)
      words.zipWithIndex.foreach { case (w, i) => poke(model, i, w) }
      dut.clockDomain.waitSampling(initCycles)

      words.zipWithIndex.foreach { case (expected, i) =>
        val got = bmbRead(dut, i)
        assert(got == expected, f"word $i: expected 0x$expected%08X, got 0x$got%08X")
      }
    }
  }

  test("BmbSdramCtrlWide: write then read back") {
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      val words = Seq(
        0xA5A5A5A5L, 0x5A5A5A5AL, 0x00FF00FFL, 0xFF00FF00L,
        0x01234567L, 0x89ABCDEFL, 0xFFFFFFFFL, 0x00000000L)

      words.zipWithIndex.foreach { case (w, i) => bmbWrite(dut, 100 + i, w) }
      words.zipWithIndex.foreach { case (expected, i) =>
        val got = bmbRead(dut, 100 + i)
        assert(got == expected, f"word ${100 + i}: expected 0x$expected%08X, got 0x$got%08X")
      }
    }
  }

  test("BmbSdramCtrlWide: address mapping is 1:1, not doubled") {
    // Guards the whole point of this bridge. BmbSdramCtrl32 shifts the BMB byte
    // address by 1 and issues SDRAM words [2w, 2w+1]; this one shifts by 2 and
    // issues [w]. A doubled mapping would still pass write-then-read, since the
    // error cancels — so anchor against the model's absolute addressing, which
    // `poke` uses directly, and place a decoy at exactly the address a doubled
    // mapping would hit.
    //
    // SdramModel has no read accessor (only `write`), hence the decoy rather
    // than a readback. Reads being proven 1:1 here plus write-then-read passing
    // in the test above is together sufficient: if writes were doubled and reads
    // were not, that pair would fail.
    sim { (dut, model) =>
      poke(model, 0x1234, 0xFEEDFACEL)
      poke(model, 0x2468, 0xBADD0000L)   // where a doubled address would land
      dut.clockDomain.waitSampling(initCycles)
      val got = bmbRead(dut, 0x1234)
      assert(got == 0xFEEDFACEL,
        f"expected 0xFEEDFACE at word 0x1234, got 0x$got%08X" +
        (if (got == 0xBADD0000L) " — address is being doubled" else ""))
    }
  }

  test("BmbSdramCtrlWide: burst read returns every word in order") {
    sim { (dut, model) =>
      val base = 0x400
      val words = (0 until 4).map(i => 0x11111111L * (i + 1))
      words.zipWithIndex.foreach { case (w, i) => poke(model, base + i, w) }
      dut.clockDomain.waitSampling(initCycles)

      val got = bmbBurstRead(dut, base, 4)
      assert(got.length == 4, s"expected 4 beats, got ${got.length}")
      words.zip(got).zipWithIndex.foreach { case ((exp, act), i) =>
        assert(exp == act, f"burst beat $i: expected 0x$exp%08X, got 0x$act%08X")
      }
    }
  }

  test("BmbSdramCtrlWide: single read still works after a burst") {
    // The isBurst context guard exists so a stale single-word response in the
    // CAS pipeline is not miscounted as a burst beat, and vice versa.
    sim { (dut, model) =>
      val base = 0x500
      (0 until 4).foreach(i => poke(model, base + i, 0xC0DE0000L + i))
      poke(model, 0x600, 0xABCDEF01L)
      dut.clockDomain.waitSampling(initCycles)

      val burst = bmbBurstRead(dut, base, 4)
      assert(burst.length == 4, s"expected 4 beats, got ${burst.length}")
      val single = bmbRead(dut, 0x600)
      assert(single == 0xABCDEF01L, f"single read after burst: got 0x$single%08X")
    }
  }

  test("BmbSdramCtrlWide: block fill zeroes a range and leaves neighbours intact") {
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      // Sentinels either side of the range, plus content inside it.
      bmbWrite(dut, 0x200 - 1, 0xDEADBEEFL)
      (0 until 8).foreach(i => bmbWrite(dut, 0x200 + i, 0x11111111L * (i + 1)))
      bmbWrite(dut, 0x200 + 8, 0xFEEDFACEL)

      fillRange(dut, 0x200, 0x200 + 8)

      assert(bmbRead(dut, 0x200 - 1) == 0xDEADBEEFL, "word below the range was cleared")
      (0 until 8).foreach { i =>
        val got = bmbRead(dut, 0x200 + i)
        assert(got == 0L, f"word ${0x200 + i} not zeroed: 0x$got%08X")
      }
      assert(bmbRead(dut, 0x200 + 8) == 0xFEEDFACEL, "word above the range was cleared")
    }
  }

  test("BmbSdramCtrlWide: block fill with a non-zero value") {
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      fillRange(dut, 0x300, 0x304, 0xA5A5A5A5L)
      (0 until 4).foreach { i =>
        val got = bmbRead(dut, 0x300 + i)
        assert(got == 0xA5A5A5A5L, f"word ${0x300 + i}: expected 0xA5A5A5A5, got 0x$got%08X")
      }
    }
  }

  test("BmbSdramCtrlWide: empty and inverted fill ranges terminate") {
    // Both must complete rather than hang: an empty range is legitimate, and an
    // inverted one would underflow the unsigned length and fill ~forever.
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      bmbWrite(dut, 0x700, 0x12345678L)
      fillRange(dut, 0x700, 0x700)        // empty
      assert(bmbRead(dut, 0x700) == 0x12345678L, "empty fill wrote something")
      fillRange(dut, 0x708, 0x700)        // inverted
      assert(bmbRead(dut, 0x700) == 0x12345678L, "inverted fill wrote something")
    }
  }

  test("BmbSdramCtrlWide: back-to-back adjacent single reads (64-bit field access)") {
    // A JOP long/double field is two consecutive 32-bit words, and JOP fetches
    // them as two *single-word* reads, not a burst (BmbMemoryController drives
    // length := 3 for ordinary access; bursts are only used for bytecode- and
    // array-cache line fills). The other tests here read one word and wait for
    // its response before issuing the next, so they never exercise a second
    // command arriving while the first is still in the CAS pipeline. This does.
    sim { (dut, model) =>
      val base = 0x900
      val expect = (0 until 8).map(i => 0x5A000000L + i)
      expect.zipWithIndex.foreach { case (w, i) => poke(model, base + i, w) }
      dut.clockDomain.waitSampling(initCycles)

      // Issue commands as fast as they are accepted, collecting responses
      // concurrently, so commands and responses overlap.
      val got = scala.collection.mutable.ArrayBuffer[Long]()
      val rxDone = fork {
        while (got.length < expect.length) {
          dut.clockDomain.waitSampling()
          if (dut.io.bmb.rsp.valid.toBoolean && dut.io.bmb.rsp.ready.toBoolean)
            got += (dut.io.bmb.rsp.fragment.data.toLong & 0xFFFFFFFFL)
        }
      }
      for (i <- expect.indices) {
        dut.io.bmb.cmd.valid #= true
        dut.io.bmb.cmd.fragment.opcode #= 0
        dut.io.bmb.cmd.fragment.address #= (base + i) * 4
        dut.io.bmb.cmd.fragment.length #= 3
        dut.io.bmb.cmd.fragment.mask #= 0xF
        dut.clockDomain.waitSamplingWhere(dut.io.bmb.cmd.ready.toBoolean)
      }
      dut.io.bmb.cmd.valid #= false
      rxDone.join()

      expect.zip(got).zipWithIndex.foreach { case ((e, a), i) =>
        assert(e == a, f"word ${base + i}: expected 0x$e%08X, got 0x$a%08X")
      }
      assert(got.length == expect.length, s"expected ${expect.length} responses, got ${got.length}")
    }
  }

  test("BmbSdramCtrlWide: back-to-back adjacent writes then reads") {
    // The write half of the same pattern (putfield on a long/double).
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      val base = 0xA00
      val vals = (0 until 8).map(i => 0xD0000000L + i * 0x1111)
      vals.zipWithIndex.foreach { case (v, i) => bmbWrite(dut, base + i, v) }
      vals.zipWithIndex.foreach { case (v, i) =>
        val got = bmbRead(dut, base + i)
        assert(got == v, f"word ${base + i}: expected 0x$v%08X, got 0x$got%08X")
      }
    }
  }

  test("BmbSdramCtrlWide: byte mask reaches DQM") {
    // JOP itself never issues a sub-word write, and on the Colorlight i5 the DQM
    // pins are strapped low so masking cannot work there at all. This checks the
    // bridge is nonetheless correct on a board that does wire DQM, so the
    // component stays reusable — and documents that the i5's safety rests on
    // JOP's behaviour, not on this logic.
    sim { (dut, model) =>
      dut.clockDomain.waitSampling(initCycles)
      bmbWrite(dut, 0x800, 0xFFFFFFFFL)
      bmbWrite(dut, 0x800, 0x00000000L, mask = 0x3)  // low two bytes only
      val got = bmbRead(dut, 0x800)
      assert(got == 0xFFFF0000L, f"expected 0xFFFF0000 after masked write, got 0x$got%08X")
    }
  }
}
