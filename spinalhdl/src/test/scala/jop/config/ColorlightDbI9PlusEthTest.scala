package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.generate.{ConstGenerator, XdcGenerator}
import jop.system.{JopSpinalConfig, JopTop, JopTopVerilog}
import jop.utils.JopFileLoader

/**
 * JOP networking on the Colorlight i9+ (`colorlightDbI9PlusEth`): the module's
 * B50612D U9 -- MDIO address 1, RJ1 on the fpga-db-colorlight-i5 base board --
 * over RGMII at 100 Mb/s (jop.io.Rgmii100, which Rgmii100Test checks at the
 * pins).
 *
 * Balls: LiteX colorlight_i9plus.py `eth` 1 = wuxx colorlight_i9plus_v6.1.md,
 * the set RX was verified byte-exact on and TX reached the LAN from
 * (fpga/bringup/colorlight-db-i9plus-eth, 2026-10-11).
 */
class ColorlightDbI9PlusEthTest extends AnyFunSuite {

  private lazy val cfg = JopConfig.colorlightDbI9PlusEth
  private lazy val xdc = XdcGenerator.generate(cfg)

  /** port -> ball, from every PACKAGE_PIN line. */
  private lazy val pins: Seq[(String, String)] = {
    val re = """set_property PACKAGE_PIN (\S+) \[get_ports \{([^}]+)\}\]""".r
    re.findAllMatchIn(xdc).map(m => m.group(2) -> m.group(1)).toSeq
  }
  private def ballOf(port: String): Option[String] = pins.find(_._1 == port).map(_._2)

  test("the RGMII and MDIO ports land on U9's balls") {
    val want = Map(
      "e_txc" -> "M6", "e_txctl" -> "N5",
      "e_txd[0]" -> "M5", "e_txd[1]" -> "M2", "e_txd[2]" -> "N4", "e_txd[3]" -> "P4",
      "e_rxc" -> "L3", "e_rxctl" -> "R1",
      "e_rxd[0]" -> "N2", "e_rxd[1]" -> "N3", "e_rxd[2]" -> "P1", "e_rxd[3]" -> "P2",
      "e_mdc" -> "G1", "e_mdio" -> "G2")
    for ((port, ball) <- want)
      assert(ballOf(port).contains(ball), s"$port should be on $ball, got ${ballOf(port)}\n$xdc")
  }

  test("H2, the shared PHY reset, is not driven") {
    // Driving it was never shown safe on this module (bring-up README, H2), and
    // the PHYs leave reset on their own.
    assert(!pins.exists(_._2 == "H2"), s"a port is on H2: ${pins.filter(_._2 == "H2")}")
  }

  test("RXC is a clock of its own, and the RGMII flops sit in the IOBs") {
    // 8 ns, not 40: with no link, or before autonegotiation settles, the PHY
    // runs RXC at 125 MHz (measured on PHY0, 2026-10-11).
    assert(xdc.contains("create_clock -period 8.000 -name e_rxc [get_ports {e_rxc}]"), xdc)
    assert(xdc.contains("set_clock_groups -asynchronous -group [get_clocks e_rxc]"), xdc)
    for (p <- Seq("e_txd[*]", "e_txctl", "e_rxd[*]", "e_rxctl"))
      assert(xdc.contains(s"set_property IOB TRUE [get_ports {$p}]"), s"$p\n$xdc")
  }

  test("MDIO has a pull-up") {
    assert(xdc.contains("set_property PULLTYPE PULLUP [get_ports {e_mdio}]"), xdc)
  }

  test("no ball is assigned twice, and none lands on an RP2040 net") {
    val dup = pins.groupBy(_._2).filter(_._2.size > 1)
    assert(dup.isEmpty, s"balls used more than once: $dup")
    val stampBalls = Set("Y21", "AB21", "AA21", "AA20", "AB20", "V19", "V18", "AA19",
      "Y19", "AB18", "AA18", "W17", "P14", "R14", "Y18", "W19")
    val consoleOk = Map("ser_rxd" -> "Y21", "ser_txd" -> "AB21")
    val clashes = pins.filter { case (port, ball) => stampBalls(ball) && !consoleOk.get(port).contains(ball) }
    assert(clashes.isEmpty, s"FPGA ports on RP2040 nets: $clashes")
  }

  test("the driver is told the PHY's address and that the MAC cannot do gigabit") {
    val c = ConstGenerator.generate(cfg)
    assert(c.contains("public static final boolean HAS_ETHERNET = true;"), "HAS_ETHERNET")
    assert(c.contains("public static final int ETH_PHY_ADDR = 1;"), "ETH_PHY_ADDR")
    assert(c.contains("public static final boolean ETH_GIGABIT = false;"), "ETH_GIGABIT")
  }

  test("a GMII board still scans for its PHY and advertises gigabit") {
    val c = ConstGenerator.generate(JopConfig.ep4cgx150DbFull)
    assert(c.contains("public static final int ETH_PHY_ADDR = -1;"), "ETH_PHY_ADDR")
    assert(c.contains("public static final boolean ETH_GIGABIT = true;"), "ETH_GIGABIT")
  }

  test("the build summary asks the clock IP for 50 MHz on its third output") {
    assert(JopTopVerilog.configSummary("colorlightDbI9PlusEth", cfg).contains("Eth clock:   50 MHz"))
    val plain = JopTopVerilog.configSummary("colorlightDbI9PlusSdram", JopConfig.colorlightDbI9PlusSdram())
    assert(!plain.contains("Eth clock"), plain)
  }

  test("the top elaborates with the RGMII ports and none of GMII's or MII's") {
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))
    val report = JopSpinalConfig(cfg, jop.generate.BuildLayout.default.standaloneDir("ColorlightDbI9PlusEthTest"))
      .generate(InOutWrapper(JopTop(config = cfg, romInit = rom, ramInit = ram,
        mainMemInit = None, mainMemSize = 64 * 1024)))
    val ports = report.toplevel.getAllIo.map(_.getName()).toSet
    val ethPorts = ports.filter(_.startsWith("e_"))
    assert(ethPorts == Set("e_txc", "e_txd", "e_txctl", "e_rxc", "e_rxd", "e_rxctl", "e_mdc", "e_mdio"),
      s"Ethernet ports: $ethPorts")
  }
}
