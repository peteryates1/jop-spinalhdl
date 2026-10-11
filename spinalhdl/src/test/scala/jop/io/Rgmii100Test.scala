package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.TestVectorUtils
import scala.collection.mutable.ArrayBuffer

/**
 * Rgmii100 -- RGMII at 100 Mb/s -- in front of the JOP Ethernet MAC (jop.io.Mac),
 * wired as the Colorlight i9+ build wires them. Frames go in and out through the
 * MAC's register interface, the protocol EthDriver.java uses, and are checked at
 * the RGMII pins the way the B50612D samples them.
 *
 * TX_CTL is sampled on BOTH edges of TXC (TX_EN on the rising edge, TX_EN ^ TX_ER
 * on the falling one), so neither edge may sit near a TXD/TX_CTL change. On
 * hardware a TXC that did not respect that sent nothing the LAN accepted, and a
 * TXC shifted 90 degrees -- both edges 10 ns clear -- worked (2026-10-11,
 * fpga/bringup/colorlight-db-i9plus-eth).
 *
 * Clocks in sim time units; only the ratios matter: the 50 MHz Ethernet clock
 * 20, RXC 40 (25 MHz), and the system clock 16, asynchronous to both.
 */
class Rgmii100Test extends AnyFunSuite {

  val TxRawPeriod = 20
  val RxcPeriod = 40
  val SysPeriod = 16
  val oddrModel = "spinalhdl/src/test/resources/xilinx_oddr_model.v"

  def crc32(data: Seq[Int]): Long = {
    var crc = 0xFFFFFFFFL
    for (byte <- data; bit <- 0 until 8) {
      val b = ((byte >> bit) & 1) ^ (crc & 1).toInt
      crc = (crc >>> 1) ^ (if (b == 1) 0xEDB88320L else 0L)
    }
    crc ^ 0xFFFFFFFFL
  }
  def fcs(data: Seq[Int]): Seq[Int] = {
    val c = crc32(data)
    (0 until 4).map(i => ((c >> (8 * i)) & 0xFF).toInt)
  }
  def hex(bytes: Seq[Int]): String = bytes.map(b => f"$b%02x").mkString(" ")

  /** An ARP request from 02:4a:4f:50:00:01 (192.168.0.250 asks for .1), padded
    * to the 60-byte minimum as EthDriver.send pads it. */
  val frame: Seq[Int] = {
    val arp = Seq(
      0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02, 0x4A, 0x4F, 0x50, 0x00, 0x01, 0x08, 0x06,
      0x00, 0x01, 0x08, 0x00, 0x06, 0x04, 0x00, 0x01,
      0x02, 0x4A, 0x4F, 0x50, 0x00, 0x01, 0xC0, 0xA8, 0x00, 0xFA,
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0xA8, 0x00, 0x01)
    arp ++ Seq.fill(60 - arp.size)(0)
  }
  /** On the wire: preamble, SFD, the frame, its FCS. */
  val wire: Seq[Int] = Seq.fill(7)(0x55) ++ Seq(0xD5) ++ frame ++ fcs(frame)

  case class Harness() extends Component {
    val io = new Bundle {
      val addr   = in UInt(4 bits)
      val rd     = in Bool()
      val wr     = in Bool()
      val wrData = in Bits(32 bits)
      val rdData = out Bits(32 bits)
      val txc    = out Bool()
      val txd    = out Bits(4 bits)
      val txCtl  = out Bool()
      val rxd    = in Bits(4 bits)
      val rxCtl  = in Bool()
    }
    val txRaw = ClockDomain.external("txRaw")
    val rxc = ClockDomain.external("rxc")
    val txCd = Rgmii100.txDomain(txRaw)
    val mac = Mac(txCd, rxc)
    val rgmii = Rgmii100(txCd, rxc)
    mac.io.phy <> rgmii.io.phy
    mac.bus.addr := io.addr
    mac.bus.rd := io.rd
    mac.bus.wr := io.wr
    mac.bus.wrData := io.wrData
    io.rdData := mac.bus.rdData
    io.txc := rgmii.io.txc
    io.txd := rgmii.io.txd
    io.txCtl := rgmii.io.txCtl
    rgmii.io.rxd := io.rxd
    rgmii.io.rxCtl := io.rxCtl
  }

  lazy val compiled = TestVectorUtils.simWave(SimConfig).addRtl(oddrModel).compile(Harness())

  def start(dut: Harness): Unit = {
    dut.clockDomain.forkStimulus(SysPeriod)
    dut.txRaw.forkStimulus(TxRawPeriod)
    dut.rxc.forkStimulus(RxcPeriod)
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.rxd #= 0
    dut.io.rxCtl #= false
    dut.clockDomain.waitSampling(50)
    busWrite(dut, 0, 0) // un-flush TX and RX
    dut.clockDomain.waitSampling(50)
  }

  def busWrite(dut: Harness, addr: Int, data: Long): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.clockDomain.waitSampling()
    dut.io.wr #= false
  }

  /** Sampled in the rd cycle, as BmbMemoryController latches it; the edge
    * ending that cycle pops (addr 3) or clears (addr 4). */
  def busRead(dut: Harness, addr: Int): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    sleep(1)
    val v = dut.io.rdData.toLong & 0xFFFFFFFFL
    dut.clockDomain.waitSampling()
    dut.io.rd #= false
    v
  }

  /** A read as JOP's BmbMemoryController really does it: it latches the value
    * in the rd cycle, and then, for as long as the pipeline waits, keeps
    * re-latching from the same address WITHOUT rd (ioRdPending). The CPU gets
    * the last re-latched value. */
  def busReadAsJop(dut: Harness, addr: Int, waits: Int = 3): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    dut.clockDomain.waitSampling()
    dut.io.rd #= false
    dut.clockDomain.waitSampling(waits)
    dut.io.rdData.toLong & 0xFFFFFFFFL
  }

  /** Drive `bytes` onto the RGMII RX pins as the B50612D does: a new nibble
    * right after each RXC rising edge, low nibble first, RX_CTL high. */
  def driveRx(dut: Harness, bytes: Seq[Int]): Unit = {
    dut.rxc.waitRisingEdge(5)
    for (n <- bytes.flatMap(b => Seq(b & 0xF, b >> 4))) {
      dut.io.rxd #= n
      dut.io.rxCtl #= true
      dut.rxc.waitRisingEdge()
    }
    dut.io.rxCtl #= false
    dut.io.rxd #= 0
  }

  test("RX: the CPU reads each word once, although the memory controller re-reads I/O") {
    // On hardware every frame reached EthDriver one word late -- the length
    // read returned the first data word -- because the re-latch saw the RX
    // FIFO's NEXT word after the pop, and the stats read saw them cleared.
    // The stack dropped everything (status item 68).
    compiled.doSim { dut =>
      start(dut)
      busRead(dut, 4)
      driveRx(dut, wire)
      var tries = 0
      while ((busRead(dut, 0) & 0x20) == 0) { tries += 1; assert(tries < 2000, "no frame reached the MAC") }
      val bitLen = busReadAsJop(dut, 3)
      assert(bitLen == (frame.size + 4) * 8, s"the length read returned 0x${bitLen.toHexString}")
      val got = (0 until (frame.size + 4) / 4).map(_ => busReadAsJop(dut, 3))
        .flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 0xFF).toInt))
      assert(got == frame ++ fcs(frame), s"\n got  ${hex(got)}\n want ${hex(frame ++ fcs(frame))}")

      // A frame with a bad FCS counts one error; the clear-on-read must not
      // hand the CPU the cleared value.
      driveRx(dut, wire.dropRight(1) :+ (wire.last ^ 0xFF))
      dut.clockDomain.waitSampling(200)
      val stats = busReadAsJop(dut, 4)
      assert((stats & 0xFF) == 1, f"RX stats read as 0x$stats%04x, want one error")
    }
  }

  test("TX: a frame written to the MAC leaves on the RGMII pins as the PHY samples it") {
    compiled.doSim { dut =>
      start(dut)
      val samples = ArrayBuffer[(Long, Boolean, Int, Boolean)]()
      fork {
        while (true) {
          samples += ((simTime(), dut.io.txc.toBoolean, dut.io.txd.toInt, dut.io.txCtl.toBoolean))
          sleep(1)
        }
      }

      // EthDriver.send: the length in bits, then the words, first byte in [7:0]
      val words = frame.grouped(4).map(_.zipWithIndex.map { case (b, i) => b.toLong << (8 * i) }.sum).toSeq
      for (w <- (frame.size * 8L) +: words) {
        var tries = 0
        while ((busRead(dut, 0) & 0x2) == 0) { tries += 1; assert(tries < 1000, "TX never ready") }
        busWrite(dut, 2, w)
      }
      dut.clockDomain.waitSampling((2 * wire.size * 2 * TxRawPeriod + 4000) / SysPeriod)

      val up = (1 until samples.size).filter(i => samples(i)._2 && !samples(i - 1)._2)
      val down = (1 until samples.size).filter(i => !samples(i)._2 && samples(i - 1)._2)
      assert(up.size > 2 * wire.size, s"TXC must run: ${up.size} rising edges")
      val periods = up.sliding(2).map(p => samples(p(1))._1 - samples(p(0))._1).toSet
      assert(periods == Set(2L * TxRawPeriod), s"TXC periods $periods, want ${2 * TxRawPeriod} (25 MHz)")

      // No TXD/TX_CTL change may come near either TXC edge: 90 degrees = 10 ns.
      val edgeTimes = (up ++ down).map(samples(_)._1).sorted
      val changes = (1 until samples.size).filter(i =>
        samples(i)._3 != samples(i - 1)._3 || samples(i)._4 != samples(i - 1)._4)
      assert(changes.nonEmpty, "TXD/TX_CTL never changed")
      for (c <- changes) {
        val t = samples(c)._1
        val d = edgeTimes.map(e => math.abs(e - t)).min
        assert(d >= TxRawPeriod / 2 - 1, s"TXD/TX_CTL changed at t=$t, $d units from a TXC edge, want ${TxRawPeriod / 2}")
      }

      // The PHY: TXD and TX_EN at the rising edge, TX_EN ^ TX_ER at the falling one.
      val nibbles = ArrayBuffer[Int]()
      for (r <- up) {
        val en = samples(r)._4
        down.find(_ > r).foreach { f =>
          assert(samples(f)._4 == en, s"TX_CTL differs across the nibble at t=${samples(r)._1}: TX_ER")
        }
        if (en) nibbles += samples(r)._3
      }
      val ctlRises = (1 until samples.size).count(i => samples(i)._4 && !samples(i - 1)._4)
      assert(ctlRises == 1, s"TX_CTL rose $ctlRises times, want one frame")
      assert(nibbles.size % 2 == 0, s"odd nibble count ${nibbles.size}")
      val bytes = nibbles.grouped(2).map(p => p(0) | (p(1) << 4)).toSeq
      assert(bytes == wire, s"\n on the wire ${hex(bytes)}\n want        ${hex(wire)}")
    }
  }

  test("RX: a frame on the RGMII pins reaches the MAC's register interface intact") {
    compiled.doSim { dut =>
      start(dut)
      busRead(dut, 4) // clear the stats

      // As the B50612D drives it: a new nibble right after each RXC rising edge,
      // low nibble first, RX_CTL high (RX_DV, no RX_ER) for the whole frame.
      dut.rxc.waitRisingEdge(5)
      for (n <- wire.flatMap(b => Seq(b & 0xF, b >> 4))) {
        dut.io.rxd #= n
        dut.io.rxCtl #= true
        dut.rxc.waitRisingEdge()
      }
      dut.io.rxCtl #= false
      dut.io.rxd #= 0

      var tries = 0
      while ((busRead(dut, 0) & 0x20) == 0) { tries += 1; assert(tries < 2000, "no frame reached the MAC") }
      val bitLen = busRead(dut, 3)
      val nBytes = ((bitLen + 7) / 8).toInt
      val got = (0 until (nBytes + 3) / 4).map(_ => busRead(dut, 3))
        .flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 0xFF).toInt)).take(nBytes)
      // MacEth hands the FCS up with the frame: 60 + 4 bytes.
      assert(got == frame ++ fcs(frame), s"\n got  ${hex(got)} ($bitLen bits)\n want ${hex(frame ++ fcs(frame))}")
      val stats = busRead(dut, 4)
      assert(stats == 0, f"RX stats: errors ${stats & 0xFF}, drops ${(stats >> 8) & 0xFF}")
    }
  }
}
