package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.io.InOutWrapper
import java.nio.file.{Files, Paths}
import hw._
import jop.generate.{ConstGenerator, XdcGenerator}
import jop.system.{JopSpinalConfig, JopTop, JopTopVerilog}
import jop.utils.JopFileLoader

/**
 * JOP networking on the Colorlight i9+ at 1000 Mb/s (`colorlightDbI9PlusEth1000`):
 * the module's B50612D U9 -- MDIO address 1, RJ1 on the fpga-db-colorlight-i5
 * base board -- over RGMII at 1000 (jop.io.Rgmii1000, which Rgmii1000Test checks
 * at the pins). The 100 Mb/s preset, colorlightDbI9PlusEth, keeps its own test.
 */
class ColorlightDbI9PlusEth1000Test extends AnyFunSuite {

  private lazy val cfg = JopConfig.colorlightDbI9PlusEth1000
  private lazy val xdc = XdcGenerator.generate(cfg)

  /** port -> ball, from every PACKAGE_PIN line. */
  private lazy val pins: Seq[(String, String)] = {
    val re = """set_property PACKAGE_PIN (\S+) \[get_ports \{([^}]+)\}\]""".r
    re.findAllMatchIn(xdc).map(m => m.group(2) -> m.group(1)).toSeq
  }
  private def ballOf(port: String): Option[String] = pins.find(_._1 == port).map(_._2)

  /** The elaborated top: its port names and its Verilog. */
  private lazy val top: (Set[String], String) = {
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))
    val dir = jop.generate.BuildLayout.default.standaloneDir("ColorlightDbI9PlusEth1000Test")
    val report = JopSpinalConfig(cfg, dir)
      .generate(InOutWrapper(JopTop(config = cfg, romInit = rom, ramInit = ram,
        mainMemInit = None, mainMemSize = 64 * 1024)))
    val ports = report.toplevel.getAllIo.map(_.getName()).toSet
    (ports, new String(Files.readAllBytes(Paths.get(dir.toString, s"${report.toplevelName}.v")), "UTF-8"))
  }

  test("the RGMII and MDIO ports land on U9's balls, as at 100 Mb/s") {
    val want = Map(
      "e_txc" -> "M6", "e_txctl" -> "N5",
      "e_txd[0]" -> "M5", "e_txd[1]" -> "M2", "e_txd[2]" -> "N4", "e_txd[3]" -> "P4",
      "e_rxc" -> "L3", "e_rxctl" -> "R1",
      "e_rxd[0]" -> "N2", "e_rxd[1]" -> "N3", "e_rxd[2]" -> "P1", "e_rxd[3]" -> "P2",
      "e_mdc" -> "G1", "e_mdio" -> "G2")
    for ((port, ball) <- want)
      assert(ballOf(port).contains(ball), s"$port should be on $ball, got ${ballOf(port)}\n$xdc")
    assert(!pins.exists(_._2 == "H2"), s"a port is on H2, the PHYs' shared reset: ${pins.filter(_._2 == "H2")}")
  }

  test("the driver is told the PHY's address and that the MAC does gigabit") {
    val c = ConstGenerator.generate(cfg)
    assert(c.contains("public static final int ETH_PHY_ADDR = 1;"), "ETH_PHY_ADDR")
    assert(c.contains("public static final boolean ETH_GIGABIT = true;"), "ETH_GIGABIT")
  }

  test("the PLL makes the system clock, its SDRAM copy, 125 MHz to transmit on, and IDELAYCTRL's 200 MHz") {
    assert(cfg.system.plls.map(_.outputs) == Seq(Seq(
      PllOutput(PllRole.System, 50), PllOutput(PllRole.Sdram, 50, phasePs = -3000),
      PllOutput(PllRole.Eth, 125), PllOutput(PllRole.IdelayRef, 200, tolerancePpm = 50000))))
  }

  test("RX is timed against an RGMII-ID PHY: RXC at 125 MHz, the data valid 1.2 ns either side of each edge") {
    // RGMII v2.0 with the delay in the PHY: TsetupT and TholdT at least 1.2 ns
    // at its pins. Centre-aligned DDR, as Vivado's template writes it: -max is
    // half the period less the setup, -min the hold, for each edge.
    val rx = "[get_ports {e_rxd[*] e_rxctl}]"
    for (line <- Seq(
      "create_clock -period 8.000 -name e_rxc [get_ports {e_rxc}]",
      s"set_input_delay -clock [get_clocks e_rxc] -max 2.800 $rx",
      s"set_input_delay -clock [get_clocks e_rxc] -min 1.200 $rx",
      s"set_input_delay -clock [get_clocks e_rxc] -clock_fall -max 2.800 -add_delay $rx",
      s"set_input_delay -clock [get_clocks e_rxc] -clock_fall -min 1.200 -add_delay $rx"))
      assert(xdc.contains(line), s"missing: $line\n$xdc")
    // Their timing is the point: nothing may false-path them.
    assert(!"""set_false_path[^\n]*e_rx""".r.findFirstIn(xdc).isDefined, xdc)
  }

  test("RXC and the transmit clock are each asynchronous to everything else") {
    // The MAC crosses both into the system clock with CC FIFOs. Timed as
    // synchronous, 125 against 50 MHz from one VCO would be judged at 4 ns.
    val module = cfg.system.plls.head.module
    assert(xdc.contains("set_clock_groups -asynchronous -group [get_clocks e_rxc]"), xdc)
    assert(xdc.contains(s"set_clock_groups -asynchronous -group [get_clocks clk_eth_$module]"), xdc)
  }

  test("the build summary asks the clock IP for 125 MHz on its Ethernet output") {
    val s = JopTopVerilog.configSummary("colorlightDbI9PlusEth1000", cfg)
    assert(s.contains("Eth clock:   125 MHz"), s)
  }

  test("the top captures RX through IDELAYE2 at the preset's taps on a BUFIO, sends TX from ODDRs, and has its IDELAYCTRL") {
    val (ports, v) = top
    val ethPorts = ports.filter(_.startsWith("e_"))
    assert(ethPorts == Set("e_txc", "e_txd", "e_txctl", "e_rxc", "e_rxd", "e_rxctl", "e_mdc", "e_mdio"),
      s"Ethernet ports: $ethPorts")

    def count(cell: String) = s"(?m)^\\s*$cell\\b".r.findAllIn(v).size
    assert(count("IDDR") == 5, s"IDDR: ${count("IDDR")}")
    assert(count("IDELAYE2") == 5, s"IDELAYE2: ${count("IDELAYE2")}")
    assert(count("ODDR") == 6, s"ODDR: ${count("ODDR")}")
    assert(count("IDELAYCTRL") == 1, s"IDELAYCTRL: ${count("IDELAYCTRL")}")
    val taps = cfg.system.effectiveDevices("eth").params("rxDelayTaps")
    assert(s"""\\.IDELAY_VALUE\\s*\\(\\s*$taps\\s*\\)""".r.findAllIn(v).size == 5, s"IDELAY_VALUE is not $taps")

    // RXC from its clock-capable pin (L3, SRCC) through a BUFIO to the IDDRs
    // and a BUFR to the receive logic. Through a BUFG it reached the IDDRs
    // about 2 ns after the data: 28 taps to make up, and the taps' own
    // uncertainty left setup + hold 0.2 to 0.7 ns short at every count
    // (2026-11-10, the tap sweep of the first build).
    assert(count("BUFIO") == 1, s"BUFIO: ${count("BUFIO")}")
    assert(count("BUFR") == 1, s"BUFR: ${count("BUFR")}")
    assert("""\.BUFR_DIVIDE\s*\(\s*"BYPASS"\s*\)""".r.findFirstIn(v).isDefined, "the BUFR must not divide RXC")
  }

  test("the MAC holds 16 KB of frames as they arrive, not one") {
    // A 1518-byte frame lasts 12 us at 1000 Mb/s; the CPU takes milliseconds
    // to read one out. With the default 2 KB -- one full-size frame -- the
    // second of two that arrive back to back was dropped: the host's TCP sends
    // segments in pairs, and lost ones come back only after a retransmit
    // timeout of 200 ms or more (2026-11-10: 25 of 96 segments of a 100 KB
    // echo retransmitted, nearly all of its 4.9 s).
    val (_, v) = top
    val rx = """(?s)module MacRxBuffer \(.*?endmodule""".r.findFirstIn(v).getOrElse(fail("no MacRxBuffer"))
    val words = """reg \[31:0\] ram \[0:(\d+)\]""".r.findFirstMatchIn(rx).map(_.group(1).toInt + 1)
    assert(words.contains(16384 / 4), s"the RX buffer has ${words.map(_ * 4)} bytes")
  }

  test("the MAC carries the hardware UDP echo, and the driver is told it is there") {
    // JOP cannot touch a gigabit link's worth of frames; jop.io.UdpEcho sends
    // them back itself, so the link can be run at line rate (UdpEchoTest).
    val (_, v) = top
    assert("""(?m)^module UdpEcho \(""".r.findFirstIn(v).isDefined, "no UdpEcho in the top")
    val c = ConstGenerator.generate(cfg)
    assert(c.contains("public static final boolean ETH_UDP_ECHO = true;"), "ETH_UDP_ECHO")
  }
}
