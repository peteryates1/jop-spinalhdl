package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.XdcGenerator

/**
 * The generated XDC for the Colorlight i9+ on the fpga-db-colorlight-i5 base
 * board must say the things THIS board needs that no earlier Vivado board did.
 *
 * 1. PARKED PINS. The module straps the SDRAM's CKE, CS and all four DQM, but
 *    the SdramInterface bundle still has those ports. The LPF generator parks
 *    them for the i5; the XDC generator ignored `Board.parkedPins`, which
 *    leaves Vivado to place six outputs on any free ball it likes -- including
 *    one the RP2040 drives.
 * 2. FLOATING UNUSED PINS. Sixteen RP2040 GPIOs land on FPGA balls. Vivado's
 *    default pulls unused pins down, against whatever the stamp drives.
 * 3. NO BALL ON A STAMP NET except the console pair. Pins in hand, this is
 *    what keeps a future device mapping from landing an FPGA output on a line
 *    the RP2040 is also driving.
 *
 * Balls are from Netlist_fpga-db-colorlight-i5_2026-10-10.enet.
 */
class ColorlightDbI9PlusXdcTest extends AnyFunSuite {

  private lazy val xdc = XdcGenerator.generate(JopConfig.colorlightDbI9PlusSdram())

  /** port -> ball, from every PACKAGE_PIN line. */
  private lazy val pins: Seq[(String, String)] = {
    val re = """set_property PACKAGE_PIN (\S+) \[get_ports \{([^}]+)\}\]""".r
    re.findAllMatchIn(xdc).map(m => m.group(2) -> m.group(1)).toSeq
  }
  private def ballOf(port: String): Option[String] = pins.find(_._1 == port).map(_._2)

  test("the strapped SDRAM ports are parked on SODIMM 57-67, which carry no net") {
    val want = Map(
      "sdram_CKE" -> "U2", "sdram_CSn" -> "U3",
      "sdram_DQM[0]" -> "V2", "sdram_DQM[1]" -> "V3",
      "sdram_DQM[2]" -> "W1", "sdram_DQM[3]" -> "Y1")
    for ((port, ball) <- want)
      assert(ballOf(port).contains(ball), s"$port should be parked on $ball, got ${ballOf(port)}\n$xdc")
  }

  test("unused pins float") {
    assert(xdc.contains("set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]"), xdc)
  }

  test("the console is the stamp's CDC0 pair and the clock is the module's 25 MHz") {
    assert(ballOf("ser_txd").contains("AB21"), s"ser_txd: ${ballOf("ser_txd")}")
    assert(ballOf("ser_rxd").contains("Y21"), s"ser_rxd: ${ballOf("ser_rxd")}")
    assert(ballOf("clk").contains("K4"), s"clk: ${ballOf("clk")}")
    assert(xdc.contains("create_clock -period 40.000 -name sys_clk [get_ports {clk}]"), xdc)
  }

  test("resetn is the SW_RESET button on P5, with an internal pull-up") {
    // The button pulls to GND and the base board has NO pull-up on it: left
    // floating, resetn would reset JOP whenever the pin drifted low.
    assert(ballOf("resetn").contains("P5"), s"resetn: ${ballOf("resetn")}")
    assert(xdc.contains("set_property PULLTYPE PULLUP [get_ports {resetn}]"), xdc)
  }

  test("no ball is assigned twice") {
    val dup = pins.groupBy(_._2).filter(_._2.size > 1)
    assert(dup.isEmpty, s"balls used more than once: $dup")
  }

  test("no ball lands on a net the RP2040 drives, except the console pair") {
    // RP2040-STAMP GPIO -> i9+ ball (netlist 2026-10-10).
    val stampBalls = Map(
      "GPIO4" -> "Y21", "GPIO5" -> "AB21", "GPIO6" -> "AA21", "GPIO7" -> "AA20",
      "GPIO8" -> "AB20", "GPIO9" -> "V19", "GPIO10" -> "V18", "GPIO11" -> "AA19",
      "GPIO12" -> "Y19", "GPIO13" -> "AB18", "GPIO14" -> "AA18", "GPIO15" -> "W17",
      "GPIO16" -> "P14", "GPIO17" -> "R14", "GPIO24" -> "Y18", "GPIO25" -> "W19")
    val allowed = Map("ser_rxd" -> "Y21", "ser_txd" -> "AB21")
    val clashes = pins.filter { case (port, ball) =>
      stampBalls.values.exists(_ == ball) && !allowed.get(port).contains(ball)
    }
    assert(clashes.isEmpty, s"FPGA ports on RP2040 nets: $clashes")
  }
}
