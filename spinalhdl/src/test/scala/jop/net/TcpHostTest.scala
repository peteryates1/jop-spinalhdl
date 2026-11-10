package jop.net

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

/**
 * The stack's TCP, on the host (JavaNetStack) behind a fake MAC that speaks
 * jop.io.Mac's register protocol: frames a test builds go in through
 * EthDriver.receive as NetLoop.poll takes them; what EthDriver.send writes
 * comes out parsed. The receive side's answers to a sender: what it ACKs,
 * when, and with what window.
 */
class TcpHostTest extends AnyFunSuite {
  import JavaNetStack._

  private val HostIp = 0xC0A800DB // 192.168.0.219
  private val JopIp = 0xC0A800E3  // 192.168.0.227
  private val HostMac = Array(0x02, 0x00, 0x00, 0x00, 0x00, 0x99).map(_.toByte)
  private val JopMac = Array(MacHi >> 8, MacHi, MacLo >> 24, MacLo >> 16, MacLo >> 8, MacLo).map(_.toByte)

  /** jop.io.Mac as EthDriver drives it, and the microsecond counter. */
  class FakeMac {
    val rx = mutable.Queue[Array[Byte]]()
    val tx = mutable.ArrayBuffer[Array[Byte]]()
    var nowUs = 0
    private var rxFrame: Array[Byte] = null
    private var rxWord = 0
    private var txBits = -1
    private val txWords = mutable.ArrayBuffer[Int]()

    def rd(addr: Int): Int =
      if (addr == IoEth) (1 << 1) | (if (rx.nonEmpty || rxFrame != null) 1 << 5 else 0)
      else if (addr == IoEth + 3) {
        if (rxFrame == null) { rxFrame = rx.dequeue(); rxWord = 0; rxFrame.length * 8 }
        else {
          val w = (0 until 4).map(i => rxWord * 4 + i).map(i =>
            (if (i < rxFrame.length) rxFrame(i) & 0xFF else 0) << (8 * (i % 4))).sum
          rxWord += 1
          if (rxWord * 4 >= rxFrame.length) rxFrame = null
          w
        }
      }
      else if (addr == IoUsCnt) nowUs
      else 0

    def wr(v: Int, addr: Int): Unit =
      if (addr == IoEth + 2) {
        if (txBits < 0) { txBits = v; txWords.clear() }
        else {
          txWords += v
          val bytes = txBits / 8
          if (txWords.size == (bytes + 3) / 4) {
            tx += Array.tabulate(bytes)(i => (txWords(i / 4) >>> (8 * (i % 4))).toByte)
            txBits = -1
          }
        }
      }
  }

  // ---- frames ----

  private def be16(a: Array[Byte], o: Int, v: Int): Unit = { a(o) = (v >> 8).toByte; a(o + 1) = v.toByte }
  private def be32(a: Array[Byte], o: Int, v: Int): Unit = { be16(a, o, v >>> 16); be16(a, o + 2, v) }
  private def u16(a: Array[Byte], o: Int) = ((a(o) & 0xFF) << 8) | (a(o + 1) & 0xFF)
  private def u32(a: Array[Byte], o: Int) = (u16(a, o) << 16) | u16(a, o + 2)
  private def sum(a: Array[Byte], o: Int, n: Int, init: Long = 0): Int = {
    var s = init
    for (i <- 0 until n by 2) s += ((a(o + i) & 0xFF) << 8) | (if (i + 1 < n) a(o + i + 1) & 0xFF else 0)
    while ((s >>> 16) != 0) s = (s & 0xFFFF) + (s >>> 16)
    (~s & 0xFFFF).toInt
  }

  val Fin = 0x01; val Syn = 0x02; val Rst = 0x04; val Psh = 0x08; val Ack = 0x10

  /** An Ethernet frame from the host to JOP holding one TCP segment. */
  def segment(seq: Int, ack: Int, flags: Int, data: Array[Byte] = Array(),
              window: Int = 65535, srcPort: Int = 40000, mss: Boolean = false): Array[Byte] = {
    val tcpLen = 20 + (if (mss) 4 else 0) + data.length
    val f = new Array[Byte](14 + 20 + tcpLen)
    System.arraycopy(JopMac, 0, f, 0, 6); System.arraycopy(HostMac, 0, f, 6, 6); be16(f, 12, 0x0800)
    f(14) = 0x45; be16(f, 16, 20 + tcpLen); be16(f, 20, 0x4000); f(22) = 64; f(23) = 6
    be32(f, 26, HostIp); be32(f, 30, JopIp); be16(f, 24, sum(f, 14, 20))
    val t = 34
    be16(f, t, srcPort); be16(f, t + 2, 7); be32(f, t + 4, seq); be32(f, t + 8, ack)
    be16(f, t + 12, (((20 + (if (mss) 4 else 0)) / 4) << 12) | flags); be16(f, t + 14, window)
    if (mss) { f(t + 20) = 2; f(t + 21) = 4; be16(f, t + 22, 1460) }
    System.arraycopy(data, 0, f, t + tcpLen - data.length, data.length)
    val pseudo = (HostIp >>> 16).toLong + (HostIp & 0xFFFF) + (JopIp >>> 16) + (JopIp & 0xFFFF) + 6 + tcpLen
    be16(f, t + 16, sum(f, t, tcpLen, pseudo))
    f
  }

  case class Seg(flags: Int, seq: Int, ack: Int, window: Int, data: Array[Byte]) {
    override def toString = f"Seg(flags=0x$flags%02x seq=$seq ack=$ack win=$window len=${data.length})"
  }

  def parse(f: Array[Byte]): Option[Seg] =
    if (u16(f, 12) != 0x0800 || f(23) != 6) None
    else {
      val t = 14 + (f(14) & 0x0F) * 4
      val end = 14 + u16(f, 16)
      val dataOff = t + ((f(t + 12) & 0xF0) >> 4) * 4
      Some(Seg(f(t + 13) & 0x3F, u32(f, t + 4), u32(f, t + 8), u16(f, t + 14), f.slice(dataOff, end)))
    }

  // ---- the stack ----

  class Stack {
    val s = JavaNetStack.load()
    val mac = new FakeMac
    s.install((mac.rd _, mac.wr _))
    s.setStatic("NetConfig", "ip", Int.box(JopIp))
    for (c <- Seq("Packet" -> "initPool", "Arp" -> "init", "UDPConnection" -> "init", "TCPConnection" -> "init"))
      s.static(c._1, c._2)
    val conn: AnyRef = s.static("TCP", "listen", Int.box(7))

    /** What NetLoop.poll does, until the fake MAC is empty: receive and
      * dispatch a frame, poll the TCP connections. Then what JOP sent. */
    def step(frames: Array[Byte]*): Seq[Seg] = {
      mac.rx ++= frames
      mac.tx.clear()
      do {
        if (mac.rx.nonEmpty) {
          val p = s.cls("Packet").getConstructor().newInstance().asInstanceOf[AnyRef]
          s.static("EthDriver", "receive", p)
          if (s.static("EthDriver", "isForUs", p) == java.lang.Boolean.TRUE) s.static("IP", "receive", p)
        }
        for (i <- 0 until 4) {
          val c = s.static("TCPConnection", "getByIndex", Int.box(i))
          if (c != null && s.field(c, "TCPConnection", "inUse") == java.lang.Boolean.TRUE)
            s.call(c, s.cls("TCPConnection"), "poll")
        }
      } while (mac.rx.nonEmpty)
      mac.tx.toSeq.flatMap(parse)
    }

    def state: Int = s.field(conn, "TCPConnection", "state").asInstanceOf[Integer]
    def available: Int = s.call(s.field(conn, "TCPConnection", "iStream"), s.cls("TCPInputStream"), "available")
      .asInstanceOf[Integer]
    def appRead(n: Int): Array[Byte] = {
      val b = new Array[Byte](n)
      val got = s.call(s.field(conn, "TCPConnection", "iStream"), s.cls("TCPInputStream"), "read",
        b, Int.box(0), Int.box(n)).asInstanceOf[Integer].intValue
      b.take(math.max(got, 0))
    }

    /** SYN, SYN-ACK, ACK: established. Returns JOP's next sequence number. */
    def handshake(hostIss: Int): Int = {
      val synAck = step(segment(hostIss, 0, Syn, mss = true))
      assert(synAck.size == 1 && synAck.head.flags == (Syn | Ack) && synAck.head.ack == hostIss + 1, synAck)
      val jopNext = synAck.head.seq + 1
      assert(step(segment(hostIss + 1, jopNext, Ack)).isEmpty)
      assert(state == 4, s"state $state, not ESTABLISHED")
      jopNext
    }
  }

  private def bytes(n: Int, from: Int = 0) = Array.tabulate(n)(i => (from + i).toByte)

  test("the harness: a handshake, and data in order is taken and ACKed") {
    val st = new Stack
    val jop = st.handshake(1000)
    val out = st.step(segment(1001, jop, Ack | Psh, bytes(100)))
    assert(out.map(_.ack) == Seq(1101), out)
    assert(out.head.window == 4096 - 100, out)
    assert(st.appRead(100).toSeq == bytes(100).toSeq)
  }

  test("a segment past a missing one is answered at once with a duplicate ACK") {
    // RFC 5681 4.2: the receiver SHOULD send an immediate duplicate ACK when
    // an out-of-order segment arrives -- the sender's fast retransmit counts
    // them. Silence leaves it to the sender's retransmit timer, 200 ms or
    // more, and the segment is dropped either way (this stack keeps none).
    val st = new Stack
    val jop = st.handshake(1000)
    assert(st.step(segment(1001, jop, Ack | Psh, bytes(100))).map(_.ack) == Seq(1101))
    val out = st.step(segment(1201, jop, Ack | Psh, bytes(100, 200)))
    assert(out.size == 1, s"JOP sent $out for a segment after a gap")
    assert(out.head.ack == 1101 && out.head.data.isEmpty && out.head.flags == Ack, out)
    assert(st.available == 100, "the out-of-order bytes must not be taken")
  }

  test("a retransmission that overlaps what was taken delivers its new bytes") {
    // A sender may resend from before what this end has: the part already
    // taken is skipped, the rest taken and ACKed. It was dropped whole, and
    // nothing ACKed: the sender waited out its timer again.
    val st = new Stack
    val jop = st.handshake(1000)
    assert(st.step(segment(1001, jop, Ack | Psh, bytes(100))).map(_.ack) == Seq(1101))
    val out = st.step(segment(1051, jop, Ack | Psh, bytes(100, 50)))
    assert(out.map(_.ack) == Seq(1151), out)
    assert(st.appRead(200).toSeq == bytes(150).toSeq)
  }

  test("room the application makes is advertised once it is worth a segment, and only once") {
    // The receive window closes as data waits for the application; when it
    // reads, the sender must hear of the room (RFC 1122 4.2.3.3: once it is
    // a full segment or half the buffer). Without it, a sender held to a
    // closed or sub-segment window waits for its persist timer.
    val st = new Stack
    val jop = st.handshake(1000)
    var seq = 1001
    for (n <- Seq(1460, 1460, 1176)) {
      val out = st.step(segment(seq, jop, Ack | Psh, bytes(n, seq)))
      seq += n
      assert(out.map(_.ack) == Seq(seq), out)
    }
    assert(st.available == 4096)
    assert(st.step().isEmpty, "nothing to say while the application has read nothing")
    st.appRead(1000)
    assert(st.step().isEmpty, "1000 bytes of room is less than a segment: not yet worth a window update")
    st.appRead(2000)
    val upd = st.step()
    assert(upd.size == 1 && upd.head.flags == Ack && upd.head.data.isEmpty, s"no window update: $upd")
    assert(upd.head.ack == seq && upd.head.window == 3000, upd)
    assert(st.step().isEmpty, "the window update was sent again with nothing new")
  }
}
