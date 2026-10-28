package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.generate.{ConstGenerator, LpfGenerator}
import jop.system.{JopSpinalConfig, JopTop, JopTopVerilog}
import jop.utils.JopFileLoader

/**
 * JOP networking on the Colorlight i5 (`colorlightDbI5Eth`), on the SECOND
 * fpga-db-colorlight-i5 base board: the module's B50612D U28 -- MDIO address
 * 1, RJ1 -- over RGMII at 100 Mb/s (jop.io.Rgmii100 with ECP5 output cells,
 * which Rgmii100Test checks at the pins).
 *
 * Balls: wuxx Colorlight-FPGA-Projects "ETH-PHY0 (U28)" = LiteX colorlight_i5.py
 * `eth` 1. RJ1 = U28 was measured, not assumed: the RGMII bring-up received the
 * LAN's frames byte-exact on these pins and its TX reached the LAN
 * (fpga/bringup/colorlight-db-i5-eth, 2026-10-27).
 *
 * The preset is looked up by NAME so that, before it existed, this failed at
 * run time ("Unknown preset") rather than not compiling.
 */
class ColorlightDbI5EthTest extends AnyFunSuite {

  private lazy val cfg = JopTopVerilog.resolvePreset("colorlightDbI5Eth")
  private lazy val lpf = LpfGenerator.generate(cfg)

  private lazy val pins: Seq[(String, String)] = {
    val re = """LOCATE COMP "([^"]+)" SITE "([^"]+)";""".r
    re.findAllMatchIn(lpf).map(m => m.group(1) -> m.group(2)).toSeq
  }
  private def ballOf(port: String): Option[String] = pins.find(_._1 == port).map(_._2)
  private def iobuf(port: String): Option[String] =
    lpf.linesIterator.find(_.matches(s"""IOBUF\\s+PORT "\\Q$port\\E" .*"""))

  test("the RGMII and MDIO ports land on U28's balls") {
    val want = Map(
      "e_txc" -> "U19", "e_txctl" -> "P19",
      "e_txd[0]" -> "U20", "e_txd[1]" -> "T19", "e_txd[2]" -> "T20", "e_txd[3]" -> "R20",
      "e_rxc" -> "L19", "e_rxctl" -> "M20",
      "e_rxd[0]" -> "P20", "e_rxd[1]" -> "N19", "e_rxd[2]" -> "N20", "e_rxd[3]" -> "M19",
      "e_mdc" -> "N5", "e_mdio" -> "P5")
    for ((port, ball) <- want)
      assert(ballOf(port).contains(ball), s"$port should be on $ball, got ${ballOf(port)}\n$lpf")
  }

  test("P4, the shared PHY reset, is not driven") {
    // As H2 on the i9+: the PHYs leave reset on their own (the bring-up's PHYs
    // answered with P4 undriven), and driving the shared reset was never shown
    // safe on these modules.
    assert(!pins.exists(_._2 == "P4"), s"a port is on P4: ${pins.filter(_._2 == "P4")}")
  }

  test("MDIO and the reset button's input get the pull-ups their boards ask for") {
    // Board.portPulls: e_mdio (module), the reset input (base board: SW_RESET
    // to GND, no resistor). Without the pull it floats and can hold JOP in
    // reset. Its port is `reset_n` on an ECP5 top, `resetn` on a Xilinx one
    // (JopConfig.resetInput), so ask the config rather than spell it.
    val resetPort = cfg.resetInput.map(_.port).getOrElse(fail("no reset input"))
    assert(resetPort == "reset_n", resetPort)
    for (port <- Seq("e_mdio", resetPort))
      assert(iobuf(port).exists(_.contains("PULLMODE=UP")), s"$port: ${iobuf(port)}\n$lpf")
  }

  test("RXC is constrained at the 25 MHz it runs at whenever the MAC is listening") {
    // The PHY does run RXC at 125 MHz while it is still at 1000BASE-T (measured
    // on the i5: U29, never written, stays there), but only before
    // EthDriver.init drops it to 100 Mb/s, and all that time MacEth holds its
    // whole receive domain in reset: rx.flush asynchronously asserts it, the
    // MAC starts flushed, and the driver releases it after the link is up at
    // 100. The ECP5's MAC receive path reaches ~100 MHz (2026-10-27), so a
    // 125 MHz constraint cannot pass here, and nothing needs it to.
    assert(lpf.contains("FREQUENCY PORT \"e_rxc\" 25 MHZ;"), lpf)
    // The Xilinx RGMII build is unchanged: it meets 8 ns and was validated so.
    val i9 = jop.generate.TimingConstraints.forConfig(JopConfig.colorlightDbI9PlusEth)
    assert(i9.clocks.exists(c => c.port == "e_rxc" && c.freqHz == BigDecimal("125000000")), i9.clocks)
  }

  test("no ball is assigned twice, and none lands on an RP2040 net but the console's") {
    val dup = pins.groupBy(_._2).filter(_._2.size > 1)
    assert(dup.isEmpty, s"balls used more than once: $dup")
    // The stamp's GPIO4-17, 24, 25 = SODIMM 155..125 (odd), as i5 balls
    // (ColorlightSodimm.SodimmI5; the netlist's U5 nets checked 2026-10-27).
    val stampBalls = Set("C17", "A18", "D3", "C4", "B4", "C3", "E3", "A3",
      "C2", "B1", "C1", "D2", "D1", "E2", "E1", "F2")
    val consoleOk = Map("ser_rxd" -> "F2", "ser_txd" -> "E1")
    val clashes = pins.filter { case (port, ball) => stampBalls(ball) && !consoleOk.get(port).contains(ball) }
    assert(clashes.isEmpty, s"FPGA ports on RP2040 nets: $clashes")
  }

  test("the driver is told the PHY's address and that the MAC cannot do gigabit") {
    val c = ConstGenerator.generate(cfg)
    assert(c.contains("public static final boolean HAS_ETHERNET = true;"), "HAS_ETHERNET")
    assert(c.contains("public static final int ETH_PHY_ADDR = 1;"), "ETH_PHY_ADDR")
    assert(c.contains("public static final boolean ETH_GIGABIT = false;"), "ETH_GIGABIT")
  }

  test("the i5's PLL gives RGMII its 50 MHz") {
    val spec = cfg.assembly.fpgaBoard.pllType.flatMap(_.spec).get
    assert(spec.of(PllRole.Eth).map(_.mhz).contains(jop.io.Rgmii100.TxClockMhz), spec)
    assert(JopTopVerilog.configSummary("colorlightDbI5Eth", cfg).contains("Eth clock:   50 MHz"))
  }

  test("this base board is reached through its own stamp, not the i9+ board's") {
    // Both units are board instances of one base board design (item 174).
    val i5 = jop.setup.Instances.forAssembly(cfg.assembly).map(_.access).get
    assert(i5.probeAlias.contains("colorlight-db-i5") && i5.consoleAlias.contains("colorlight-db-i5-0"),
      s"${i5.probeAlias} / ${i5.consoleAlias}")
    assert(i5.loaderCable.contains("dirtyJtag"))
    val i9 = jop.setup.Instances.forAssembly(SystemAssembly.colorlightDbI9Plus).map(_.access).get
    assert(i9.probeAlias.contains("colorlight-db") && i9.consoleAlias.contains("colorlight-db-0"))
  }

  test("the top elaborates with the RGMII ports, through ECP5 output cells") {
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))
    val dir = jop.generate.BuildLayout.default.standaloneDir("ColorlightDbI5EthTest")
    val report = JopSpinalConfig(cfg, dir)
      .generate(InOutWrapper(JopTop(config = cfg, romInit = rom, ramInit = ram,
        mainMemInit = None, mainMemSize = 64 * 1024)))
    val ports = report.toplevel.getAllIo.map(_.getName()).toSet
    val ethPorts = ports.filter(_.startsWith("e_"))
    assert(ethPorts == Set("e_txc", "e_txd", "e_txctl", "e_rxc", "e_rxd", "e_rxctl", "e_mdc", "e_mdio"),
      s"Ethernet ports: $ethPorts")
    val v = report.generatedSourcesPaths.filter(_.endsWith(".v"))
      .map(p => new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), "UTF-8")).mkString
    assert("(?m)^\\s*ODDRX1F\\b".r.findAllIn(v).size == 6, "want six ODDRX1F cells")
    assert("(?m)^\\s*ODDR\\b".r.findFirstIn(v).isEmpty, "an ECP5 build instantiates the Xilinx ODDR")
  }
}
