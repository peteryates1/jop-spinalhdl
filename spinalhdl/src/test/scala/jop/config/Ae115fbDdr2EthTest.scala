package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.QsfGenerator
import jop.system.JopTopVerilog

/**
 * The A-E115FB's Ethernet (`ae115fbDdr2Eth`): the bottom board's Marvell
 * 88E1111, GMII on the core board's JTAG-side connector.
 */
class Ae115fbDdr2EthTest extends AnyFunSuite {

  private val cfg = JopTopVerilog.resolvePreset("ae115fbDdr2Eth", Array("ae115fbDdr2Eth"))
  private val assignments = QsfGenerator.pinAssignments(cfg)
  private val pins = assignments.map(a => a.verilogPort -> a.fpgaPin.stripPrefix("PIN_")).toMap

  // docs/boards/ep4ce115-ddr2-board.md, JTAG-side DOWN connector, pins 3-32 --
  // this board's source of truth, from photos and tracing. Typed in again here
  // so that a slip in either copy shows.
  private val documented = Map(
    "e_resetn" -> "Y2", "e_mdc" -> "Y1", "e_mdio" -> "V4",
    "e_txd[7]" -> "T3", "e_txd[6]" -> "T4", "e_txd[5]" -> "T5", "e_txd[4]" -> "R4",
    "e_txd[3]" -> "R3", "e_txd[2]" -> "P4", "e_txd[1]" -> "P3", "e_txd[0]" -> "W2",
    "e_txen" -> "W1", "e_gtxc" -> "V2",
    "e_rxc" -> "P5", "e_rxdv" -> "N6",
    "e_rxd[0]" -> "N5", "e_rxd[1]" -> "M5", "e_rxd[2]" -> "U2", "e_rxd[3]" -> "U1",
    "e_rxd[4]" -> "R2", "e_rxd[5]" -> "R1", "e_rxd[6]" -> "R5", "e_rxd[7]" -> "D6")

  test("every Ethernet port lands on the ball the board document gives") {
    val eth = pins.filter(_._1.startsWith("e_"))
    assert(eth == documented,
      s"\n  not as documented: ${(eth.toSet -- documented.toSet).toSeq.sorted}" +
      s"\n  documented, missing: ${(documented.toSet -- eth.toSet).toSeq.sorted}")
  }

  // J2 sets banks 1 and 2, J1 banks 7 and 8; both read 2.5 V (2026-10-12).
  // The console, the PHY and the LEDs all sit on those banks, and Quartus
  // refuses a bank whose pins imply two VCCIOs -- RXD7 (D6) shares bank 8 with
  // the LEDs, which used to say 1.8 V, and RX_CLK bank 2 with the console's
  // receive line, which used to say 3.3-V LVTTL.
  test("the console, the PHY and the LEDs all say 2.5 V, as jumpers J1 and J2 do") {
    val io = QsfGenerator.toIoStandards(cfg, assignments)
    val jumpered = pins.keys.filter(p =>
      p.startsWith("e_") || p.startsWith("ser_") || p.startsWith("led"))
    assert(jumpered.size == documented.size + 2 + 4, jumpered.toSeq.sorted)
    for (p <- jumpered)
      assert(io.contains(s"""set_instance_assignment -name IO_STANDARD "2.5 V" -to $p"""),
        s"$p is not 2.5 V\n$io")
  }
}
