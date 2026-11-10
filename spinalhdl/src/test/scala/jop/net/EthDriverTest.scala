package jop.net

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ArrayBuffer

/**
 * EthDriver's side of the MAC's register contract (jop.io.Mac), on the host
 * (JavaNetStack): what it writes where.
 */
class EthDriverTest extends AnyFunSuite {
  import JavaNetStack._

  test("udpEcho writes the board's MAC, then the port with the enable bit, where the MAC takes them") {
    // jop.io.Mac with udpEcho: 5 = MAC bytes 2..5, 6 = bytes 0..1 in 15:0,
    // 7 = the port in 15:0 and enable in bit 16 -- UdpEchoTest drives the
    // hardware with the same writes.
    val s = load()
    val writes = ArrayBuffer[(Int, Int)]()
    s.install((_ => 0, (v, a) => writes += ((a, v))))
    s.static("EthDriver", "udpEcho", Int.box(7007))
    assert(writes.toSeq == Seq((IoEth + 5, MacLo), (IoEth + 6, MacHi), (IoEth + 7, (1 << 16) | 7007)),
      writes.map { case (a, v) => f"$a%#x <- $v%#x" })
  }

  test("the echo's counts are read from registers 5, 6 and 7") {
    val s = load()
    s.install((a => a * 3, (_, _) => ()))
    assert(s.static("EthDriver", "udpEchoReceived") == Int.box((IoEth + 5) * 3))
    assert(s.static("EthDriver", "udpEchoSent") == Int.box((IoEth + 6) * 3))
    assert(s.static("EthDriver", "udpEchoLost") == Int.box((IoEth + 7) * 3))
  }
}
