package jop.config

import java.util.regex.Pattern
import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.io.InOutWrapper
import jop.system.{JopSpinalConfig, JopTop}
import jop.utils.JopFileLoader

/**
 * Every GMII receive pin feeds ONE register -- its capture flop, which the
 * generated project puts in the I/O element (QsfGenerator.ethRxCapture) -- and
 * nothing else. MII shares the receive adapter, so it is held to the same.
 *
 * `rxFlow.last` was computed from the raw RX_DV pin, so the pin also fanned out
 * through the fabric to the preamble detector, the CRC checker and the RX
 * buffer's cleanup. Re-timed on the EP4CGX150 with RX_DV changing on the clock
 * edge, those loads sat between -0.91 ns and +0.90 ns of hold slack: some would
 * take the new value and some the old, so they could disagree about where a
 * frame ends. Removing the fan-out did not change that board's 3-5 % CRC loss
 * (status item 166): this guards the structure, not a measured symptom.
 *
 * A zero-delay simulation cannot see any of it. The structure can, so this
 * reads it off the Verilog the build hands to Quartus.
 */
class GmiiRxCaptureTest extends AnyFunSuite {

  private val Assign      = raw"assign (\S+) = (.+);".r
  private val NonBlocking = raw"(\S+) <= (.+);".r

  private def isDeclaration(l: String): Boolean =
    Seq("input ", "output ", "inout ", "wire ", "reg ").exists(l.startsWith)

  /** Does this line READ `net`? An assignment reads only its right-hand side. */
  private def reads(l: String, net: String): Boolean = {
    val word = raw"(?<![\w$$])${Pattern.quote(net)}(?![\w$$])".r
    l match {
      case Assign(_, rhs)      => word.findFirstIn(rhs).isDefined
      case NonBlocking(_, rhs) => word.findFirstIn(rhs).isDefined
      case _                   => word.findFirstIn(l).isDefined && !isDeclaration(l)
    }
  }

  /** Every line that reads `net`, following pure aliases (`assign w = net;`). */
  private def loads(lines: Seq[String], net: String): Seq[String] =
    lines.filter(reads(_, net)).flatMap {
      case Assign(w, rhs) if rhs == net => loads(lines, w)
      case l                            => Seq(l)
    }

  private def receivePinLoads(cfg: JopConfig, name: String): Unit = {
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))
    val dir = jop.generate.BuildLayout.default.standaloneDir(s"GmiiRxCaptureTest_$name")
    val report = JopSpinalConfig(cfg, dir).generate(InOutWrapper(JopTop(config = cfg,
      romInit = rom, ramInit = ram, mainMemInit = None, mainMemSize = 64 * 1024)))
    val src = scala.io.Source.fromFile(s"$dir/${report.toplevelName}.v")
    val lines = try src.getLines().map(_.trim).toVector finally src.close()

    for (pin <- Seq("e_rxdv", "e_rxer", "e_rxd")) {
      val l = loads(lines, pin)
      assert(l.size == 1 && l.head.matches(raw"\S+ <= \S+;"),
        s"$name: $pin reaches ${l.size} load(s); it must reach exactly one register:\n" +
        l.mkString("\n"))
    }
  }

  test("every GMII receive pin feeds one register and nothing else") {
    receivePinLoads(JopConfig.ep4cgx150DbV5Eth, "gmii")
  }

  test("every MII receive pin feeds one register and nothing else") {
    receivePinLoads(JopConfig.ep4cgx150DbFull, "mii")
  }
}
