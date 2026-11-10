package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.com.eth.{PhyIo, PhyParameter}
import jop.TestVectorUtils
import scala.collection.mutable.ArrayBuffer

/** The JOP MAC with its UDP echo, the PHY side as streams: what Rgmii1000
  * hands it and takes from it, a byte a clock at 1000 Mb/s. */
case class UdpEchoHarness() extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val phy    = master(PhyIo(PhyParameter(8, 8)))
  }
  val txClk = ClockDomain.external("txClk")
  val rxc = ClockDomain.external("rxc")
  val mac = Mac(txClk, rxc, phyTxDataWidth = 8, phyRxDataWidth = 8,
    rxBufferByteSize = 2048, txBufferByteSize = 2048, rxCapture = false, udpEcho = true)
  mac.io.phy <> io.phy
  mac.bus.addr := io.addr
  mac.bus.rd := io.rd
  mac.bus.wr := io.wr
  mac.bus.wrData := io.wrData
  io.rdData := mac.bus.rdData
}

/**
 * jop.io.UdpEcho, in the MAC: UDP frames to the board's MAC and the port JOP
 * set go straight back out -- MAC addresses, IP addresses and ports swapped,
 * a new FCS -- at line rate, without the CPU; everything else reaches JOP as
 * before. Swapping leaves the IP and UDP checksums valid: each is a sum, and
 * the pseudo-header holds both addresses.
 *
 * Clocks in sim time units: TX and RXC 16 each (125 MHz, independent), the
 * system clock 40 (50 MHz).
 */
class UdpEchoTest extends AnyFunSuite {

  val TxPeriod = 16
  val RxcPeriod = 16
  val SysPeriod = 40

  val Port = 7007
  val JopMac = Seq(0x02, 0x4A, 0x4F, 0x50, 0x00, 0x01)
  val HostMac = Seq(0x52, 0x54, 0x00, 0x09, 0x84, 0x6C)
  val JopIp = Seq(192, 168, 0, 227)
  val HostIp = Seq(192, 168, 0, 219)

  def crc32(data: Seq[Int]): Long = {
    var crc = 0xFFFFFFFFL
    for (byte <- data; bit <- 0 until 8) {
      val b = ((byte >> bit) & 1) ^ (crc & 1).toInt
      crc = (crc >>> 1) ^ (if (b == 1) 0xEDB88320L else 0L)
    }
    crc ^ 0xFFFFFFFFL
  }
  def fcs(data: Seq[Int]): Seq[Int] = (0 until 4).map(i => ((crc32(data) >> (8 * i)) & 0xFF).toInt)
  def onWire(f: Seq[Int]): Seq[Int] = Seq.fill(7)(0x55) ++ Seq(0xD5) ++ f ++ fcs(f)
  def hex(bytes: Seq[Int]): String = bytes.map(b => f"$b%02x").mkString(" ")

  def sum16(b: Seq[Int], init: Long = 0): Int = {
    var s = init
    for (i <- b.indices by 2) s += (b(i) << 8) | (if (i + 1 < b.size) b(i + 1) else 0)
    while ((s >>> 16) != 0) s = (s & 0xFFFF) + (s >>> 16)
    s.toInt
  }
  def be16(v: Int) = Seq((v >> 8) & 0xFF, v & 0xFF)

  /** An Ethernet frame holding an IPv4 datagram, checksums right, padded to 60. */
  def ipFrame(dstMac: Seq[Int] = JopMac, srcMac: Seq[Int] = HostMac, proto: Int = 17,
              srcPort: Int = 40000, dstPort: Int = Port, payload: Seq[Int] = (0 until 32),
              flagsFrag: Int = 0x4000, srcIp: Seq[Int] = HostIp, dstIp: Seq[Int] = JopIp): Seq[Int] = {
    val l4Len = 8 + payload.size
    val l4NoSum = be16(srcPort) ++ be16(dstPort) ++ be16(l4Len) ++ be16(0) ++ payload
    val pseudo = sum16(srcIp ++ dstIp ++ Seq(0, proto) ++ be16(l4Len))
    val l4 = l4NoSum.patch(6, be16(~sum16(l4NoSum, pseudo) & 0xFFFF), 2)
    val ipNoSum = Seq(0x45, 0) ++ be16(20 + l4Len) ++ be16(0x1234) ++ be16(flagsFrag) ++
      Seq(64, proto) ++ be16(0) ++ srcIp ++ dstIp
    val ip = ipNoSum.patch(10, be16(~sum16(ipNoSum) & 0xFFFF), 2)
    val f = dstMac ++ srcMac ++ be16(0x0800) ++ ip ++ l4
    f ++ Seq.fill(scala.math.max(0, 60 - f.size))(0)
  }

  /** What the echo sends for a frame: the two MACs, the two IPs and the two
    * ports exchanged. */
  def swapped(f: Seq[Int]): Seq[Int] =
    f.slice(6, 12) ++ f.slice(0, 6) ++ f.slice(12, 26) ++ f.slice(30, 34) ++ f.slice(26, 30) ++
      f.slice(36, 38) ++ f.slice(34, 36) ++ f.drop(38)

  def arp: Seq[Int] = {
    val a = Seq.fill(6)(0xFF) ++ HostMac ++ Seq(0x08, 0x06, 0x00, 0x01, 0x08, 0x00, 0x06, 0x04, 0x00, 0x01) ++
      HostMac ++ HostIp ++ Seq.fill(6)(0) ++ JopIp
    a ++ Seq.fill(60 - a.size)(0)
  }

  lazy val compiled = TestVectorUtils.simWave(SimConfig).workspaceName("UdpEchoTest").compile(UdpEchoHarness())

  // ---- the bus, as EthDriver drives it ----

  def busWrite(dut: UdpEchoHarness, addr: Int, data: Long): Unit = {
    dut.io.addr #= addr
    dut.io.wrData #= data
    dut.io.wr #= true
    dut.clockDomain.waitSampling()
    dut.io.wr #= false
  }
  def busRead(dut: UdpEchoHarness, addr: Int): Long = {
    dut.io.addr #= addr
    dut.io.rd #= true
    sleep(1)
    val v = dut.io.rdData.toLong & 0xFFFFFFFFL
    dut.clockDomain.waitSampling()
    dut.io.rd #= false
    v
  }
  def readFrame(dut: UdpEchoHarness): Seq[Int] = {
    var tries = 0
    while ((busRead(dut, 0) & 0x20) == 0) { tries += 1; assert(tries < 4000, "no frame reached JOP") }
    val nBytes = ((busRead(dut, 3) + 7) / 8).toInt
    (0 until (nBytes + 3) / 4).map(_ => busRead(dut, 3))
      .flatMap(w => (0 until 4).map(i => ((w >> (8 * i)) & 0xFF).toInt)).take(nBytes)
  }
  def jopHasFrame(dut: UdpEchoHarness): Boolean = (busRead(dut, 0) & 0x20) != 0
  /** EthDriver.send: the length in bits, then the words, first byte in [7:0]. */
  def jopSend(dut: UdpEchoHarness, f: Seq[Int]): Unit =
    for (w <- (f.size * 8L) +: f.grouped(4).map(_.zipWithIndex.map { case (b, i) => b.toLong << (8 * i) }.sum).toSeq) {
      var tries = 0
      while ((busRead(dut, 0) & 0x2) == 0) { tries += 1; assert(tries < 1000, "TX never ready") }
      busWrite(dut, 2, w)
    }
  /** What EthDriver.udpEcho writes: the board's MAC, then the port and enable. */
  def enableEcho(dut: UdpEchoHarness, port: Int = Port, on: Boolean = true): Unit = {
    busWrite(dut, 5, JopMac.drop(2).foldLeft(0L)((a, b) => (a << 8) | b))
    busWrite(dut, 6, (JopMac(0) << 8) | JopMac(1))
    busWrite(dut, 7, (if (on) 1L << 16 else 0L) | port)
  }
  def received(dut: UdpEchoHarness) = busRead(dut, 5)
  def echoed(dut: UdpEchoHarness) = busRead(dut, 6)
  def crcErrors(dut: UdpEchoHarness) = busRead(dut, 7) & 0xFFFF
  def drops(dut: UdpEchoHarness) = busRead(dut, 7) >> 16

  // ---- the PHY side ----

  /** Bytes out of the MAC, split at `last`. */
  class TxMonitor(dut: UdpEchoHarness) {
    val frames = ArrayBuffer[Seq[Int]]()
    private val cur = ArrayBuffer[Int]()
    fork {
      while (true) {
        dut.txClk.waitSampling()
        if (dut.io.phy.tx.valid.toBoolean && dut.io.phy.tx.ready.toBoolean) {
          cur += dut.io.phy.tx.payload.fragment.data.toInt
          if (dut.io.phy.tx.payload.last.toBoolean) { frames += cur.toSeq; cur.clear() }
        }
      }
    }
  }

  /** A frame from the PHY as Rgmii1000 hands it on: preamble and SFD, the
    * frame, its FCS, a byte a clock; `idle` clocks before it, 12 the minimum gap. */
  def phyRx(dut: UdpEchoHarness, wire: Seq[Int], idle: Int = 12): Unit = {
    dut.rxc.waitSampling(idle)
    for ((b, i) <- wire.zipWithIndex) {
      dut.io.phy.rx.valid #= true
      dut.io.phy.rx.payload.fragment.data #= b
      dut.io.phy.rx.payload.fragment.error #= false
      dut.io.phy.rx.payload.last #= i == wire.size - 1
      dut.rxc.waitSampling()
    }
    dut.io.phy.rx.valid #= false
    dut.io.phy.rx.payload.last #= false
  }

  def start(dut: UdpEchoHarness): TxMonitor = {
    dut.clockDomain.forkStimulus(SysPeriod)
    dut.txClk.forkStimulus(TxPeriod)
    dut.rxc.forkStimulus(RxcPeriod)
    dut.io.addr #= 0
    dut.io.rd #= false
    dut.io.wr #= false
    dut.io.wrData #= 0
    dut.io.phy.rx.valid #= false
    dut.io.phy.rx.payload.last #= false
    dut.io.phy.rx.payload.fragment.data #= 0
    dut.io.phy.rx.payload.fragment.error #= false
    dut.io.phy.colision #= false
    dut.io.phy.busy #= false
    dut.io.phy.tx.ready #= true
    dut.clockDomain.waitSampling(50)
    busWrite(dut, 0, 0) // un-flush TX and RX
    dut.clockDomain.waitSampling(50)
    new TxMonitor(dut)
  }

  def settle(dut: UdpEchoHarness, bytes: Int = 0): Unit =
    dut.clockDomain.waitSampling((bytes * TxPeriod * 2 + 4000) / SysPeriod)

  def assertEcho(got: Seq[Int], sent: Seq[Int], what: String): Unit = {
    val want = onWire(swapped(sent))
    assert(got == want, s"$what\n got  ${hex(got)}\n want ${hex(want)}")
  }

  test("a UDP frame to the board's MAC and the port comes back swapped, with a good FCS, and JOP never sees it") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      val f = ipFrame(payload = (0 until 100).map(_ * 7 & 0xFF))
      phyRx(dut, onWire(f))
      settle(dut, f.size)
      assert(tx.frames.size == 1, s"${tx.frames.size} frames sent")
      assertEcho(tx.frames.head, f, "the echo")
      // Both checksums still hold after the swap
      val e = swapped(f)
      assert(sum16(e.slice(14, 34)) == 0xFFFF, "IP header checksum")
      val l4Len = (e(38) << 8) | e(39)
      assert(sum16(e.slice(34, 34 + l4Len), sum16(e.slice(26, 34) ++ Seq(0, 17) ++ be16(l4Len))) == 0xFFFF,
        "UDP checksum")
      assert(!jopHasFrame(dut), "JOP received the echoed frame")
      assert(busRead(dut, 4) == 0, "the MAC counted an error or a drop")
      assert((received(dut), echoed(dut), crcErrors(dut), drops(dut)) == (1, 1, 0, 0))
    }
  }

  test("everything else reaches JOP unchanged and is not echoed") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      val others = Seq(
        "UDP to another port" -> ipFrame(dstPort = 7),
        "UDP to another MAC" -> ipFrame(dstMac = Seq(0x02, 0x4A, 0x4F, 0x50, 0x00, 0x02)),
        "UDP to the port, broadcast" -> ipFrame(dstMac = Seq.fill(6)(0xFF)),
        "TCP to the port" -> ipFrame(proto = 6),
        "a later fragment" -> ipFrame(flagsFrag = 0x0010),
        "a first fragment, more to come" -> ipFrame(flagsFrag = 0x2000),
        "ARP" -> arp)
      for ((what, f) <- others) {
        phyRx(dut, onWire(f))
        val got = readFrame(dut)
        assert(got == f ++ fcs(f), s"$what\n got  ${hex(got)}\n want ${hex(f ++ fcs(f))}")
      }
      enableEcho(dut, on = false)
      val f = ipFrame()
      phyRx(dut, onWire(f))
      assert(readFrame(dut) == f ++ fcs(f), "the echo port with the echo off")
      settle(dut)
      assert(tx.frames.isEmpty, s"sent ${tx.frames.size} frames")
      assert(received(dut) == 0 && echoed(dut) == 0)
    }
  }

  test("frames back to back at the minimum gap are all echoed, in order: line rate") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      val sizes = Seq(18, 1472, 100, 1472, 1472, 501, 1472, 64, 1472, 999, 1472, 1472, 333, 1472, 7, 1472)
      val sent = sizes.zipWithIndex.map { case (n, i) => ipFrame(srcPort = 40000 + i, payload = (0 until n).map(j => (i * 31 + j) & 0xFF)) }
      for (f <- sent) phyRx(dut, onWire(f), idle = 12)
      settle(dut, sent.map(_.size).sum)
      assert(tx.frames.size == sent.size, s"${tx.frames.size} of ${sent.size} echoed")
      for (((g, f), i) <- tx.frames.zip(sent).zipWithIndex) assertEcho(g, f, s"frame $i")
      assert(!jopHasFrame(dut))
      assert((received(dut), echoed(dut), crcErrors(dut), drops(dut)) == (sent.size, sent.size, 0, 0))
    }
  }

  test("a frame whose FCS is wrong is not echoed but counted, and the next one is") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      val f = ipFrame()
      val bad = onWire(f)
      phyRx(dut, bad.updated(bad.size - 1, bad.last ^ 0x40))
      settle(dut, f.size)
      assert(tx.frames.isEmpty, "a frame with a bad FCS was echoed")
      assert(crcErrors(dut) == 1 && received(dut) == 0)
      phyRx(dut, onWire(f))
      settle(dut, f.size)
      assert(tx.frames.size == 1)
      assertEcho(tx.frames.head, f, "the good frame after it")
      assert(!jopHasFrame(dut), "JOP received a frame for the echo")
    }
  }

  test("JOP's own frames still go out between echoes, whole") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      val sent = (0 until 8).map(i => ipFrame(srcPort = 41000 + i, payload = (0 until 1472).map(j => (i + j) & 0xFF)))
      val rx = fork { for (f <- sent) phyRx(dut, onWire(f)) }
      dut.clockDomain.waitSampling(3 * 1500 * TxPeriod / SysPeriod)
      jopSend(dut, arp)
      rx.join()
      settle(dut, sent.map(_.size).sum)
      val own = tx.frames.filter(_.slice(8 + 12, 8 + 14) == Seq(0x08, 0x06))
      assert(own == Seq(onWire(arp)), s"JOP's frame: ${own.map(hex)}")
      val echoes = tx.frames.filterNot(own.contains)
      assert(echoes.size == sent.size, s"${echoes.size} echoes")
      for ((g, f) <- echoes.zip(sent)) assertEcho(g, f, "an echo")
    }
  }

  test("with the line stalled, the buffer fills and drops whole frames, counted; what it held goes out after") {
    compiled.doSim { dut =>
      val tx = start(dut)
      enableEcho(dut)
      dut.io.phy.tx.ready #= false
      val sent = (0 until 16).map(i => ipFrame(srcPort = 42000 + i, payload = (0 until 1472).map(j => (3 * i + j) & 0xFF)))
      for (f <- sent) phyRx(dut, onWire(f))
      settle(dut)
      val (r, d) = (received(dut), drops(dut))
      assert(r + d == sent.size && d > 0 && r >= 8, s"received $r, dropped $d of ${sent.size} 1514-byte frames into 16 KB")
      dut.io.phy.tx.ready #= true
      settle(dut, sent.map(_.size).sum)
      assert(tx.frames.size == r, s"${tx.frames.size} echoed of $r held")
      for ((g, f) <- tx.frames.zip(sent)) assertEcho(g, f, "an echo after the stall")
      assert(echoed(dut) == r)
    }
  }
}
