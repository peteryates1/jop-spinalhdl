package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.TestVectorUtils
import scala.collection.mutable.ArrayBuffer

/**
 * Rgmii1000 -- RGMII at 1000 Mb/s -- in front of the JOP Ethernet MAC (jop.io.Mac)
 * in its 8-bit mode, wired as the Colorlight i9+ gigabit build wires them. Frames
 * go in and out through the MAC's register interface, the protocol EthDriver.java
 * uses, and are checked at the RGMII pins the way the B50612D drives and samples
 * them.
 *
 * At 1000 Mb/s RGMII moves a byte per clock on four wires: bits 3:0 on the
 * rising edge, 7:4 on the falling one. CTL carries the enable on the rising edge
 * and enable XOR error on the falling one.
 *
 * The i9+'s B50612Ds run RGMII-ID: the PHY delays both clocks itself (PhyRegs,
 * 2026-11-10). So TXC must leave ALIGNED with TXD/TX_CTL -- the PHY's own delay
 * puts its sampling mid-nibble, and an FPGA that shifted TXC as well would put it
 * back on the transitions -- and RXC arrives centred in each nibble. The tests
 * hold both contracts.
 *
 * Clocks in sim time units; only the ratios matter: TX and RXC 16 each (125 MHz,
 * independent of each other), the system clock 40 (50 MHz).
 */
class Rgmii1000Test extends AnyFunSuite {

  val TxPeriod = 16
  val RxcPeriod = 16
  val SysPeriod = 40
  val models = Seq("xilinx_oddr_model.v", "xilinx_iddr_model.v", "xilinx_idelaye2_model.v")
    .map(f => s"spinalhdl/src/test/resources/$f")

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
  def onWire(f: Seq[Int]): Seq[Int] = Seq.fill(7)(0x55) ++ Seq(0xD5) ++ f ++ fcs(f)
  val wire: Seq[Int] = onWire(frame)

  // No default arguments: `Harness(x)` compiles to `new Harness(x)`, but a
  // defaulted call reads the companion object, whose initialisation locks this
  // suite -- which `compiled`'s lazy initialiser already holds while SpinalHDL
  // elaborates on another thread. That deadlocked the first run of this suite.
  case class Harness(rxDelayTaps: Int) extends Component {
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
    val txClk = ClockDomain.external("txClk")
    val rxc = ClockDomain.external("rxc")
    val mac = Mac(txClk, rxc, phyTxDataWidth = 8, phyRxDataWidth = 8)
    val rgmii = Rgmii1000(txClk, rxc, rxDelayTaps)
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

  lazy val compiled = models.foldLeft(TestVectorUtils.simWave(SimConfig).workspaceName("Rgmii1000Test"))(
    (cfg, m) => cfg.addRtl(m)).compile(Harness(rxDelayTaps = 0))

  /** The Verilog a harness elaborates to, for structural checks. */
  def verilogOf(h: => Harness): String = {
    val dir = java.nio.file.Files.createTempDirectory("rgmii1000").toString
    SpinalConfig(targetDirectory = dir).generateVerilog(h)
    new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, "Harness.v")), "UTF-8")
  }

  def start(dut: Harness): Unit = {
    dut.clockDomain.forkStimulus(SysPeriod)
    dut.txClk.forkStimulus(TxPeriod)
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

  /** Drive `bytes` onto the RX pins as an RGMII-ID PHY does, RXC centred in
    * every nibble: each low nibble is set a quarter period before the rising
    * edge that samples it, each high nibble a quarter period before the falling
    * edge. RX_CTL is RX_DV on the rising edge and RX_DV ^ RX_ER on the falling
    * one, so a byte in `errAt` carries RX_ER as RX_CTL low there. `idle` RXC
    * cycles go first: 12 is the minimum inter-frame gap. */
  def driveRx(dut: Harness, bytes: Seq[Int], errAt: Set[Int] = Set(), idle: Int = 12): Unit = {
    val q = RxcPeriod / 4
    dut.rxc.waitRisingEdge(idle)
    for ((b, i) <- bytes.zipWithIndex) {
      dut.rxc.waitFallingEdge()
      sleep(q)
      dut.io.rxd #= b & 0xF
      dut.io.rxCtl #= true
      dut.rxc.waitRisingEdge()
      sleep(q)
      dut.io.rxd #= b >> 4
      dut.io.rxCtl #= !errAt(i)
    }
    dut.rxc.waitFallingEdge()
    sleep(q)
    dut.io.rxCtl #= false
    dut.io.rxd #= 0
  }

  /** The next frame the MAC holds, as EthDriver.receive reads it. */
  def readFrame(dut: Harness): Seq[Int] = {
    var tries = 0
    while ((busRead(dut, 0) & 0x20) == 0) { tries += 1; assert(tries < 2000, "no frame reached the MAC") }
    val bitLen = busRead(dut, 3)
    val nBytes = ((bitLen + 7) / 8).toInt
    (0 until (nBytes + 3) / 4).map(_ => busRead(dut, 3))
      .flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 0xFF).toInt)).take(nBytes)
  }

  test("RX: a frame on the RGMII pins reaches the MAC's register interface intact") {
    compiled.doSim { dut =>
      start(dut)
      busRead(dut, 4) // clear the stats
      driveRx(dut, wire)
      val got = readFrame(dut)
      // MacEth hands the FCS up with the frame: 60 + 4 bytes.
      assert(got == frame ++ fcs(frame), s"\n got  ${hex(got)}\n want ${hex(frame ++ fcs(frame))}")
      val stats = busRead(dut, 4)
      assert(stats == 0, f"RX stats: errors ${stats & 0xFF}, drops ${(stats >> 8) & 0xFF}")
    }
  }

  test("RX: two frames at the minimum inter-frame gap both arrive, each intact") {
    compiled.doSim { dut =>
      start(dut)
      busRead(dut, 4)
      val second = frame.updated(frame.size - 19, 0x02) // ARP for .2: the target IP's last byte
      driveRx(dut, wire)
      driveRx(dut, onWire(second))
      val a = readFrame(dut)
      val b = readFrame(dut)
      assert(a == frame ++ fcs(frame), s"\n first  ${hex(a)}\n want   ${hex(frame ++ fcs(frame))}")
      assert(b == second ++ fcs(second), s"\n second ${hex(b)}\n want   ${hex(second ++ fcs(second))}")
      val stats = busRead(dut, 4)
      assert(stats == 0, f"RX stats: errors ${stats & 0xFF}, drops ${(stats >> 8) & 0xFF}")
    }
  }

  test("RX: RX_ER inside a frame (RX_CTL low on a falling edge) fails that frame only") {
    compiled.doSim { dut =>
      start(dut)
      busRead(dut, 4)
      driveRx(dut, wire, errAt = Set(30))
      dut.clockDomain.waitSampling(200)
      assert((busRead(dut, 0) & 0x20) == 0, "the frame with RX_ER was delivered")
      val stats = busRead(dut, 4)
      assert((stats & 0xFF) == 1, f"RX stats read as 0x$stats%04x, want one error")
      driveRx(dut, wire)
      val got = readFrame(dut)
      assert(got == frame ++ fcs(frame), s"\n the next frame ${hex(got)}\n want           ${hex(frame ++ fcs(frame))}")
    }
  }

  test("TX: a frame written to the MAC leaves a nibble an edge, with TXC aligned to the data") {
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
      dut.clockDomain.waitSampling((2 * wire.size * TxPeriod + 4000) / SysPeriod)

      val up = (1 until samples.size).filter(i => samples(i)._2 && !samples(i - 1)._2)
      val down = (1 until samples.size).filter(i => !samples(i)._2 && samples(i - 1)._2)
      assert(up.size > wire.size, s"TXC must run: ${up.size} rising edges")
      val periods = up.sliding(2).map(p => samples(p(1))._1 - samples(p(0))._1).toSet
      assert(periods == Set(TxPeriod.toLong), s"TXC periods $periods, want $TxPeriod (125 MHz)")

      // RGMII-ID: the PHY delays TXC, so every TXD/TX_CTL change must come WITH
      // a TXC edge -- in the same sample -- never between edges.
      val changes = (1 until samples.size).filter(i =>
        samples(i)._3 != samples(i - 1)._3 || samples(i)._4 != samples(i - 1)._4)
      assert(changes.nonEmpty, "TXD/TX_CTL never changed")
      for (c <- changes)
        assert(samples(c)._2 != samples(c - 1)._2,
          s"TXD/TX_CTL changed at t=${samples(c)._1} with no TXC edge: the PHY would sample it mid-change")

      // The PHY: TXD[3:0] and TX_EN at the rising edge, TXD[7:4] and TX_EN ^ TX_ER at the falling one.
      val bytes = ArrayBuffer[Int]()
      for (r <- up; f <- down.find(_ > r)) {
        val en = samples(r)._4
        assert(samples(f)._4 == en, s"TX_CTL differs across the byte at t=${samples(r)._1}: TX_ER")
        if (en) bytes += samples(r)._3 | (samples(f)._3 << 4)
      }
      val ctlRises = (1 until samples.size).count(i => samples(i)._4 && !samples(i - 1)._4)
      assert(ctlRises == 1, s"TX_CTL rose $ctlRises times, want one frame")
      assert(bytes == wire, s"\n on the wire ${hex(bytes.toSeq)}\n want        ${hex(wire)}")
    }
  }

  test("RX is captured in IDDR behind IDELAYE2 at the taps asked for, and every TX pin leaves an ODDR") {
    val v = verilogOf(Harness(rxDelayTaps = 13))
    def count(cell: String) = s"(?m)^\\s*$cell\\b".r.findAllIn(v).size
    assert(count("IDDR") == 5, s"want 5 IDDR (RXD[3:0], RX_CTL), got ${count("IDDR")}")
    assert(count("IDELAYE2") == 5, s"want 5 IDELAYE2, got ${count("IDELAYE2")}")
    assert(count("ODDR") == 6, s"want 6 ODDR (TXD[3:0], TX_CTL, TXC), got ${count("ODDR")}")
    // SpinalHDL pads generics into columns: `.IDELAY_VALUE          (13       )`
    assert("""\.IDELAY_VALUE\s*\(\s*13\s*\)""".r.findAllIn(v).size == 5,
      "every IDELAYE2 must take the 13 taps asked for")
  }
}
