package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable

/**
 * Mac's RX capture (`rxCapture`): the PHY receive stream as it enters the MAC,
 * one entry per RX clock, around one frame -- a logic analyser for status item
 * 166, where the EP4CGX150 loses received frames depending on their content.
 *
 * Entry bits: 7:0 RXD, 8 RX_DV, 9 RX_ER, 10 last, 11 MAC commit, 12 MAC error,
 * 13 MAC drop. Registers (MAC sub-addresses): 5 write arms (bit 0 = mode: 0 the
 * next frame, 1 the next frame the MAC rejects or never commits), 5 read status
 * (bit 0 done, 2 commit seen, 3 error seen, 27:16 entries), 6 read first entry /
 * write read pointer, 7 read the entry at the pointer, which then advances.
 */
class MacRxCaptureTest extends AnyFunSuite {

  val simConfig = jop.utils.JopSimDefaults.config

  def compileDut() = simConfig.compile(
    Mac(
      txCd = ClockDomain.external("txCd", withReset = false),
      rxCd = ClockDomain.external("rxCd", withReset = false),
      phyTxDataWidth = 8,
      phyRxDataWidth = 8,
      rxBufferByteSize = 512,
      txBufferByteSize = 512,
      rxCapture = true
    )
  )

  /** Preamble, SFD, frame and FCS: what RXD carries while RX_DV is high. */
  def gmii(frame: Seq[Int]): Seq[Int] = {
    val crc = new java.util.zip.CRC32
    crc.update(frame.map(_.toByte).toArray)
    val fcs = crc.getValue
    Seq.fill(7)(0x55) ++ Seq(0xD5) ++ frame ++ (0 until 4).map(i => ((fcs >> (8 * i)) & 0xFF).toInt)
  }

  def testFrame(tag: Int): Seq[Int] =
    Seq(0x02, 0, 0, 0, 0, 1, 0x52, 0x54, 0, 9, 0x84, 0x6C, 0x88, 0xB5, tag) ++
      Seq.tabulate(60)(i => (i * 37 + tag) & 0xFF)

  /** Drives io.phy.rx as DeviceTopWiring does: valid = RX_DV, `last` on the
    * final byte, ready ignored. One element per RX clock. */
  class RxDriver(dut: Mac) {
    private val q = mutable.Queue[(Boolean, Int, Boolean)]()
    def idle(n: Int): Unit = for (_ <- 0 until n) q += ((false, 0, false))
    def burst(bytes: Seq[Int], errorAt: Int = -1): Unit =
      bytes.indices.foreach(i => q += ((true, bytes(i), i == errorAt)))
    def busy: Boolean = q.nonEmpty
    fork {
      while (true) {
        dut.rxCd.waitSampling()
        val (v, d, e) = if (q.nonEmpty) q.dequeue() else (false, 0, false)
        val last = v && (q.isEmpty || !q.head._1)
        dut.io.phy.rx.valid #= v
        dut.io.phy.rx.payload.fragment.data #= d
        dut.io.phy.rx.payload.fragment.error #= e
        dut.io.phy.rx.payload.last #= last
      }
    }
  }

  def ioWrite(dut: Mac, addr: Int, data: Long): Unit = {
    dut.bus.addr #= addr
    dut.bus.wrData #= data
    dut.bus.wr #= true
    dut.bus.rd #= false
    dut.clockDomain.waitSampling()
    dut.bus.wr #= false
  }

  /** JOP takes rdData in the cycle rd is high -- a device answers in its read
    * cycle -- so sample before the edge that commits the read's side effect. */
  def ioRead(dut: Mac, addr: Int): Long = {
    dut.bus.addr #= addr
    dut.bus.rd #= true
    dut.bus.wr #= false
    sleep(1)
    val v = dut.bus.rdData.toLong
    dut.clockDomain.waitSampling()
    dut.bus.rd #= false
    v
  }

  def setup(dut: Mac): RxDriver = {
    dut.clockDomain.forkStimulus(10)
    dut.txCd.forkStimulus(8)
    dut.rxCd.forkStimulus(8)
    SimTimeout(2000000)
    dut.bus.addr #= 0
    dut.bus.rd #= false
    dut.bus.wr #= false
    dut.bus.wrData #= 0
    dut.io.phy.colision #= false
    dut.io.phy.busy #= false
    dut.io.phy.rx.valid #= false
    dut.io.phy.rx.payload.fragment.data #= 0
    dut.io.phy.rx.payload.fragment.error #= false
    dut.io.phy.rx.payload.last #= false
    dut.io.phy.tx.ready #= true
    dut.clockDomain.waitSampling(20)
    ioWrite(dut, 0, 0x00) // out of flush: the MAC takes frames
    dut.clockDomain.waitSampling(50)
    new RxDriver(dut)
  }

  case class Entry(e: Int) {
    def data = e & 0xFF
    def dv = ((e >> 8) & 1) == 1
    def er = ((e >> 9) & 1) == 1
    def last = ((e >> 10) & 1) == 1
    def commit = ((e >> 11) & 1) == 1
    def macError = ((e >> 12) & 1) == 1
  }

  case class Capture(status: Long, entries: Seq[Entry]) {
    def bytes: Seq[Int] = entries.filter(_.dv).map(_.data)
    def firstDv: Int = entries.indexWhere(_.dv)
    def commits: Seq[(Entry, Int)] = entries.zipWithIndex.filter(_._1.commit)
  }

  def awaitCapture(dut: Mac): Capture = {
    var st = ioRead(dut, 5)
    var polls = 0
    while ((st & 1) == 0 && polls < 5000) {
      dut.clockDomain.waitSampling(10)
      st = ioRead(dut, 5)
      polls += 1
    }
    assert((st & 1) == 1, "the capture never completed")
    val n = ((st >> 16) & 0xFFF).toInt
    ioWrite(dut, 6, ioRead(dut, 6))
    Capture(st, (0 until n).map(_ => Entry(ioRead(dut, 7).toInt & 0xFFFF)))
  }

  def arm(dut: Mac, mode: Int): Unit = {
    ioWrite(dut, 5, mode)
    dut.clockDomain.waitSampling(10)
    assert((ioRead(dut, 5) & 1) == 0, "arming clears done")
  }

  test("mode 0 records the next frame, entry by entry, with the MAC's verdict") {
    compileDut().doSim(seed = 42) { dut =>
      val rx = setup(dut)
      arm(dut, 0)
      val f = gmii(testFrame(1))
      rx.idle(20); rx.burst(f); rx.idle(100)
      val c = awaitCapture(dut)

      assert(c.firstDv == 8, s"8 idle entries come before the frame, got ${c.firstDv}")
      assert(c.entries.size == 8 + f.size + 32,
        s"8 before, ${f.size} of frame, 32 idle after: ${c.entries.size}")
      assert(c.entries.slice(8, 8 + f.size).forall(_.dv) && c.entries.drop(8 + f.size).forall(!_.dv),
        "RX_DV is high exactly for the frame")
      assert(c.bytes == f, "RXD, in order, preamble to FCS")
      assert(c.entries.zipWithIndex.filter(_._1.last).map(_._2) == Seq(8 + f.size - 1), "`last` on the final byte")
      assert(!c.entries.exists(_.er), "no RX_ER")
      assert(c.commits.map(_._2) == Seq(8 + f.size + 1), "the MAC commits two clocks after `last`")
      assert(!c.commits.head._1.macError, "a good frame commits without error")
      assert((c.status & 0xC) == 0x4, f"status: commit seen, no error (0x${c.status}%x)")
    }
  }

  test("mode 1 passes over a good frame and keeps the first the MAC rejects") {
    compileDut().doSim(seed = 42) { dut =>
      val rx = setup(dut)
      arm(dut, 1)
      val good = gmii(testFrame(2))
      val bad = good.updated(30, good(30) ^ 0x10)
      rx.idle(20); rx.burst(good); rx.idle(60); rx.burst(bad); rx.idle(100)
      val c = awaitCapture(dut)

      assert(c.bytes == bad, "the capture holds the bad frame, not the good one before it")
      assert(c.commits.size == 1 && c.commits.head._1.macError, "committed with the MAC's error flag")
      assert((c.status & 0xC) == 0xC, f"status: commit seen, error seen (0x${c.status}%x)")
    }
  }

  test("mode 1 keeps a frame whose RX_ER the MAC counts against it") {
    compileDut().doSim(seed = 42) { dut =>
      val rx = setup(dut)
      arm(dut, 1)
      val good = gmii(testFrame(3))
      rx.idle(20); rx.burst(good); rx.idle(60); rx.burst(good, errorAt = 40); rx.idle(100)
      val c = awaitCapture(dut)

      assert(c.bytes == good, "the second copy, bytes intact")
      assert(c.entries.zipWithIndex.filter(_._1.er).map(_._2) == Seq(8 + 40), "RX_ER recorded on its byte")
      assert(c.commits.size == 1 && c.commits.head._1.macError, "the MAC flags the frame")
    }
  }

  test("mode 1 keeps a burst the MAC never commits: RX_DV high, no start delimiter") {
    compileDut().doSim(seed = 42) { dut =>
      val rx = setup(dut)
      arm(dut, 1)
      val good = gmii(testFrame(4))
      val noSfd = Seq.fill(7)(0x55) ++ Seq.tabulate(40)(i => if (i % 2 == 0) 0x00 else 0xFF)
      rx.idle(20); rx.burst(good); rx.idle(60); rx.burst(noSfd); rx.idle(100)
      val c = awaitCapture(dut)

      assert(c.bytes == noSfd, "the burst, as driven")
      assert(c.commits.isEmpty, "nothing committed")
      assert((c.status & 0x4) == 0, f"status: no commit seen (0x${c.status}%x)")
    }
  }

  test("re-arming starts a new capture") {
    compileDut().doSim(seed = 42) { dut =>
      val rx = setup(dut)
      arm(dut, 0)
      val a = gmii(testFrame(5))
      val b = gmii(testFrame(6))
      rx.idle(20); rx.burst(a); rx.idle(100)
      assert(awaitCapture(dut).bytes == a)
      while (rx.busy) dut.clockDomain.waitSampling()
      arm(dut, 0)
      rx.idle(20); rx.burst(b); rx.idle(100)
      assert(awaitCapture(dut).bytes == b, "the second capture holds the second frame")
    }
  }
}
