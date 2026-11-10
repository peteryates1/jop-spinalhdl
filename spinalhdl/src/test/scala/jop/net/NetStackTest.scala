package jop.net

import org.scalatest.funsuite.AnyFunSuite
import java.lang.reflect.InvocationTargetException
import scala.util.Random

/**
 * The network stack's data paths, on the host (JavaNetStack): java/net's
 * Packet and TCP stream buffers, checked against byte-at-a-time references.
 *
 * On JOP every call costs about a thousand cycles on a byte accessor --
 * the object and array caches are invalidated at each invoke and return --
 * so a 1460-byte segment that crossed getByte/write/read once per byte per
 * pass took about 100 ms to acknowledge (NetBench, 2026-11-10). The bulk
 * paths do each pass in one call; these tests hold them to what the per-byte
 * paths did. TcpHostTest drives the whole stack.
 */
class NetStackTest extends AnyFunSuite {

  private lazy val loader: ClassLoader = JavaNetStack.load().loader

  private def cls(n: String) = loader.loadClass(s"com.jopdesign.net.$n")

  /** Calls a public method by name and argument types; unwraps its exception. */
  private def call(target: AnyRef, c: Class[_], name: String, args: (Class[_], AnyRef)*): AnyRef =
    try c.getMethod(name, args.map(_._1): _*).invoke(target, args.map(_._2): _*)
    catch { case e: InvocationTargetException => throw e.getCause }

  private def I(v: Int): (Class[_], AnyRef) = (classOf[Int], Int.box(v))
  private def int(r: AnyRef) = r.asInstanceOf[Integer].intValue

  // ---- Packet ----

  private def newPacket(rng: Random): AnyRef = {
    val p = cls("Packet").getConstructor().newInstance().asInstanceOf[AnyRef]
    val buf = words(p)
    for (i <- buf.indices) buf(i) = rng.nextInt()
    p
  }
  private def words(p: AnyRef) = cls("Packet").getField("buf").get(p).asInstanceOf[Array[Int]]
  /** Byte i as the hardware stores it: little-endian within each word. */
  private def byteAt(p: AnyRef, i: Int) = (words(p)(i >> 2) >>> ((i & 3) * 8)) & 0xFF
  private def P(p: AnyRef): (Class[_], AnyRef) = (cls("Packet"), p)

  private def fold(s0: Long): Int = {
    var s = s0
    while ((s >>> 16) != 0) s = (s & 0xFFFF) + (s >>> 16)
    s.toInt
  }

  /** What TCP/UDP/ICMP.checksum and IP.checksumIpHeader each summed: getShort
    * over the range, a trailing odd byte as the high half of a word. */
  private def refSum(p: AnyRef, off: Int, len: Int): Long = {
    var s = 0L
    var i = 0
    while (i + 1 < len) { s += (byteAt(p, off + i) << 8) | byteAt(p, off + i + 1); i += 2 }
    if (i < len) s += byteAt(p, off + i) << 8
    s
  }

  test("Packet.sum16 adds up to what the getShort loops did, at every alignment and length") {
    val rng = new Random(1)
    val pkt = newPacket(rng)
    val cases = (for (off <- 0 until 12; len <- 0 to 80) yield (off, len)) ++
      (for (off <- Seq(14, 34, 54, 55, 66); len <- Seq(1458, 1459, 1460, 1461, 1480)
            if off + len <= 1536) yield (off, len))  // Packet.MAX_BYTES
    for ((off, len) <- cases) {
      val got = int(call(pkt, cls("Packet"), "sum16", I(off), I(len)))
      // The callers add the pseudo-header and fold, so the sum need only fold
      // to the same value with anything added -- nothing added included.
      for (extra <- Seq(0L, 1L, 0xFFFFL, 0x12345L, rng.nextInt(1 << 20).toLong))
        assert(fold(got.toLong + extra) == fold(refSum(pkt, off, len) + extra),
          s"off $off len $len extra $extra: sum16 $got, reference ${refSum(pkt, off, len)}")
    }
  }

  test("Packet.sum16 keeps zero apart from 0xFFFF") {
    // ~0 and ~0xFFFF both fold to an all-ones checksum field, but a sum of
    // all-zero bytes must stay 0 so that a checksum of 0xFFFF results.
    val pkt = cls("Packet").getConstructor().newInstance().asInstanceOf[AnyRef]
    for (off <- 0 until 8; len <- 0 to 24)
      assert(int(call(pkt, cls("Packet"), "sum16", I(off), I(len))) == 0, s"off $off len $len")
    val buf = words(pkt)
    buf(0) = 0x0000FFFF // bytes FF FF: one word of 0xFFFF
    assert(fold(int(call(pkt, cls("Packet"), "sum16", I(0), I(4))).toLong) == 0xFFFF)
  }

  /** Bytes of p that differ from a copy of its words taken before. */
  private def changedBytes(before: Array[Int], p: AnyRef): Seq[Int] =
    (0 until before.length * 4).filter(i => ((before(i >> 2) >>> ((i & 3) * 8)) & 0xFF) != byteAt(p, i))

  test("Packet.copyTo and copyFrom move what getByte and setByte did, and touch no other byte") {
    val rng = new Random(8)
    for (trial <- 0 until 300) {
      val pkt = newPacket(rng)
      val off = rng.nextInt(80)
      val count = rng.nextInt(120)
      val arr = Array.fill[Byte](count + 8)(rng.nextInt(256).toByte)
      val at = rng.nextInt(8)
      val before = words(pkt).clone()
      call(pkt, cls("Packet"), "copyFrom", B(arr), I(at), I(off), I(count))
      assert((0 until count).map(i => byteAt(pkt, off + i)) == arr.slice(at, at + count).map(_ & 0xFF).toSeq,
        s"trial $trial: copyFrom bytes")
      assert(changedBytes(before, pkt).forall(i => i >= off && i < off + count), s"trial $trial: copyFrom outside the range")
      val out = Array.fill[Byte](count + 8)(0x5A)
      call(pkt, cls("Packet"), "copyTo", I(off), B(out), I(at), I(count))
      assert(out.slice(at, at + count).toSeq == arr.slice(at, at + count).toSeq, s"trial $trial: copyTo bytes")
      assert(out.take(at).forall(_ == 0x5A) && out.drop(at + count).forall(_ == 0x5A), s"trial $trial: copyTo outside the range")
    }
  }

  test("Packet.copy moves bytes between packets at any alignment, and touches no other byte") {
    val rng = new Random(9)
    for (trial <- 0 until 300) {
      val src = newPacket(rng)
      val dst = newPacket(rng)
      val srcOff = rng.nextInt(80)
      val dstOff = rng.nextInt(80)
      val count = rng.nextInt(200)
      val before = words(dst).clone()
      call(dst, cls("Packet"), "copy", P(src), I(srcOff), I(dstOff), I(count))
      assert((0 until count).map(i => byteAt(dst, dstOff + i)) == (0 until count).map(i => byteAt(src, srcOff + i)),
        s"trial $trial: bytes")
      assert(changedBytes(before, dst).forall(i => i >= dstOff && i < dstOff + count), s"trial $trial: outside the range")
    }
  }

  // ---- TCPInputStream ----

  private def newIn(size: Int) =
    cls("TCPInputStream").getConstructor(classOf[Int]).newInstance(Int.box(size)).asInstanceOf[AnyRef]
  private def newOut(size: Int) =
    cls("TCPOutputStream").getConstructor(classOf[Int]).newInstance(Int.box(size)).asInstanceOf[AnyRef]
  private def inCall(s: AnyRef, name: String, args: (Class[_], AnyRef)*) = call(s, cls("TCPInputStream"), name, args: _*)
  private def outCall(s: AnyRef, name: String, args: (Class[_], AnyRef)*) = call(s, cls("TCPOutputStream"), name, args: _*)
  private def B(a: Array[Byte]): (Class[_], AnyRef) = (classOf[Array[Byte]], a)

  /** Everything a per-byte reader would get, emptying the stream. */
  private def drainIn(s: AnyRef): Seq[Int] =
    Iterator.continually(int(inCall(s, "read"))).takeWhile(_ != -1).toSeq

  test("TCPInputStream.write(Packet) appends what per-byte writes did, stopping when full") {
    val rng = new Random(2)
    for (trial <- 0 until 300) {
      val size = Seq(16, 17, 64)(trial % 3)
      val bulk = newIn(size)
      val ref = newIn(size)
      val pkt = newPacket(rng)
      // Move the pointers somewhere first, so writes wrap
      val pre = rng.nextInt(size + 1)
      for (k <- 0 until pre; s <- Seq(bulk, ref)) inCall(s, "write", I(k))
      val take = rng.nextInt(pre + 1)
      for (_ <- 0 until take; s <- Seq(bulk, ref)) inCall(s, "read")
      val off = rng.nextInt(64)
      val len = rng.nextInt(2 * size + 2)
      val n = int(inCall(bulk, "write", P(pkt), I(off), I(len)))
      var want = 0
      while (want < len && int(inCall(ref, "write", I(byteAt(pkt, off + want)))) == 0) want += 1
      assert(n == want, s"trial $trial: wrote $n, per-byte wrote $want")
      assert(inCall(bulk, "isBufferFull") == inCall(ref, "isBufferFull"), s"trial $trial: full")
      assert(int(inCall(bulk, "available")) == int(inCall(ref, "available")), s"trial $trial: available")
      assert(drainIn(bulk) == drainIn(ref), s"trial $trial: contents")
    }
  }

  test("TCPInputStream.write(Packet) takes nothing once the stream is closed") {
    val s = newIn(16)
    inCall(s, "close")
    assert(int(inCall(s, "write", P(newPacket(new Random(3))), I(0), I(8))) == 0)
    assert(int(inCall(s, "available")) == 0)
  }

  test("TCPInputStream.read(byte[]) hands out what per-byte reads did") {
    val rng = new Random(4)
    for (trial <- 0 until 300) {
      val size = Seq(16, 17, 64)(trial % 3)
      val bulk = newIn(size)
      val ref = newIn(size)
      val pre = rng.nextInt(size + 1)
      for (k <- 0 until pre; s <- Seq(bulk, ref)) inCall(s, "write", I(k))
      for (_ <- 0 until rng.nextInt(pre + 1); s <- Seq(bulk, ref)) inCall(s, "read")
      for (k <- 0 until rng.nextInt(size + 1); s <- Seq(bulk, ref)) inCall(s, "write", I(100 + k))
      val dst = Array.fill[Byte](2 * size + 8)(0x5A)
      val off = rng.nextInt(4)
      val len = rng.nextInt(2 * size + 2)
      val n = int(inCall(bulk, "read", B(dst), I(off), I(len)))
      val want = Iterator.continually(int(inCall(ref, "read"))).take(len).takeWhile(_ != -1).toSeq
      assert(n == (if (want.isEmpty) -1 else want.size), s"trial $trial: read $n, per-byte ${want.size}")
      assert(dst.slice(off, off + want.size).map(_ & 0xFF).toSeq == want, s"trial $trial: bytes")
      assert(dst.take(off).forall(_ == 0x5A) && dst.drop(off + want.size).forall(_ == 0x5A),
        s"trial $trial: wrote outside the range")
      assert(drainIn(bulk) == drainIn(ref), s"trial $trial: what is left")
    }
  }

  // ---- TCPOutputStream ----

  /** The stream's unsent bytes, read per byte. */
  private def drainOut(s: AnyRef): Seq[Int] =
    Iterator.continually(int(outCall(s, "read"))).takeWhile(_ != -1).toSeq

  /** Bulk and reference streams through the same history: writes, sends, ACKs. */
  private def twoOuts(rng: Random, size: Int): (AnyRef, AnyRef) = {
    val bulk = newOut(size)
    val ref = newOut(size)
    for (round <- 0 until 3) {
      val w = rng.nextInt(size + 1)
      for (k <- 0 until w; s <- Seq(bulk, ref)) outCall(s, "write", I(round * 50 + k))
      val r = rng.nextInt(w + 1)
      for (_ <- 0 until r; s <- Seq(bulk, ref)) outCall(s, "read")
      val a = rng.nextInt(r + 1)
      for (s <- Seq(bulk, ref)) outCall(s, "ackData", I(a))
    }
    (bulk, ref)
  }

  test("TCPOutputStream.read(Packet) sends what per-byte reads did, and touches no other byte") {
    val rng = new Random(5)
    for (trial <- 0 until 300) {
      val size = Seq(16, 17, 64)(trial % 3)
      val (bulk, ref) = twoOuts(rng, size)
      if (trial % 7 == 0) for (s <- Seq(bulk, ref)) outCall(s, "setPtrForRetransmit")
      val pkt = newPacket(rng)
      val before = words(pkt).clone()
      val off = rng.nextInt(64)
      val max = rng.nextInt(2 * size + 2)
      val n = int(outCall(bulk, "read", P(pkt), I(off), I(max)))
      val want = Iterator.continually(int(outCall(ref, "read"))).take(max).takeWhile(_ != -1).toSeq
      assert(n == want.size, s"trial $trial: read $n, per-byte ${want.size}")
      assert((0 until n).map(i => byteAt(pkt, off + i)) == want, s"trial $trial: bytes")
      val changed = changedBytes(before, pkt)
      assert(changed.forall(i => i >= off && i < off + n), s"trial $trial: bytes outside the range changed: $changed")
      assert(outCall(bulk, "isNoMoreDataToRead") == outCall(ref, "isNoMoreDataToRead"), s"trial $trial")
      assert(drainOut(bulk) == drainOut(ref), s"trial $trial: what is left to send")
    }
  }

  test("TCPOutputStream.read(Packet) sends a buffer that is full and all unsent") {
    val s = newOut(16)
    for (k <- 0 until 16) outCall(s, "write", I(k))
    val pkt = newPacket(new Random(6))
    assert(int(outCall(s, "read", P(pkt), I(54), I(1460))) == 16)
    assert((0 until 16).map(i => byteAt(pkt, 54 + i)) == (0 until 16))
    assert(outCall(s, "isNoMoreDataToRead") == java.lang.Boolean.TRUE)
  }

  test("TCPOutputStream.write(byte[]) queues what per-byte writes did") {
    val rng = new Random(7)
    for (trial <- 0 until 300) {
      val size = Seq(16, 17, 64)(trial % 3)
      val (bulk, ref) = twoOuts(rng, size)
      val data = Array.fill[Byte](2 * size + 4)(rng.nextInt(256).toByte)
      val off = rng.nextInt(4)
      val len = rng.nextInt(data.length - off + 1)
      val n = int(outCall(bulk, "write", B(data), I(off), I(len)))
      var want = 0
      while (want < len && int(outCall(ref, "write", I(data(off + want) & 0xFF))) == 0) want += 1
      assert(n == want, s"trial $trial: wrote $n, per-byte wrote $want")
      assert(int(outCall(bulk, "getFreeBufferSpace")) == int(outCall(ref, "getFreeBufferSpace")), s"trial $trial")
      assert(drainOut(bulk) == drainOut(ref), s"trial $trial: what is queued")
    }
  }
}
