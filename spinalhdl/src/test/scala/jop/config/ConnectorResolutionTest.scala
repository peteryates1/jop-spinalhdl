package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.XdcGenerator

/**
 * REGRESSION TEST: connector-relative device pins must resolve to the FPGA pins
 * the board documentation gives, and that hardware has actually run.
 *
 * WHY THIS EXISTS. `Board.scala` maps daughter-board devices through connector
 * references like `"TXD" -> "J3:8"`. Until 2026-09-10 those labels were two
 * BELOW the physical pin: every QMTECH 64-pin map was keyed 5..58, while the
 * documented invariant is that pins 1/2/5/6/61/62 are GND, 3/4 are 3V3, 63/64
 * are VIN and only 7..60 are I/O. All 155 device mappings carried the same -2,
 * so the offsets cancelled and every board resolved correctly -- which is why
 * they all worked, and why `"J3:5"`, a ground pin, sat in the UART mapping
 * looking plausible.
 *
 * Both halves have now been shifted together, and the generated constraints for
 * all 15 presets came out byte-identical. Renumbering ONE half moves every
 * peripheral on both QMTECH core boards by two pins. That has been attempted
 * before.
 *
 * This test does not care which convention is used. It asserts the RESOLVED
 * FPGA pin, so any future renumbering has to prove itself: get it right and this
 * stays green; get half of it right and it fails naming the signal.
 *
 * Sources, all cross-checked against docs/boards/ and hardware:
 *   UART   docs/boards/qmtech-db-fpga-v5.md, "RP2040 GPIO -> FPGA Connector
 *          Mapping" -- GPIO0 (RP2040 TX) -> J3_IO7 -> pin 7 -> B5, which is the
 *          FPGA's ser_RXD; GPIO1 -> J3_IO8 -> pin 8 -> A5, the FPGA's ser_TXD.
 *          Verified by hardware loopback (10/10) and by DoAll 66/66 at 2 Mbaud.
 *   SD     docs/boards/qmtech-xc7a100t-board.md, "SD Card" table.
 *
 * The UART entries are the reason this file exists: the RP2040 mapping named
 * TXD/RXD from the BRIDGE's side rather than the FPGA's, so the generated XDC
 * crossed the console. Crossing TX and RX gives silence at every baud, which is
 * indistinguishable from a design that never boots.
 */
class ConnectorResolutionTest extends AnyFunSuite {

  private def pins(xdc: String): Map[String, String] =
    """set_property\s+PACKAGE_PIN\s+(\S+)\s+\[get_ports\s*\{?\s*([A-Za-z0-9_]+)""".r
      .findAllMatchIn(xdc).map(m => m.group(2) -> m.group(1)).toMap

  private def expect(label: String, xdc: String, want: (String, String)*): Unit = {
    val got = pins(xdc)
    val wrong = want.filter { case (port, pin) => got.get(port) != Some(pin) }
    assert(wrong.isEmpty,
      s"$label: connector pins resolved to the wrong FPGA pin — " +
        wrong.map { case (port, pin) =>
          s"$port expected $pin, got ${got.getOrElse(port, "<absent>")}"
        }.mkString("; ") +
        ". If you renumbered a connector map, every Jx:n device mapping on that " +
        "board must shift by the same amount in the same commit.")
  }

  test("XC7A100T + DB_FPGA V5 — console is not crossed") {
    // A5 is the FPGA's OUTPUT. Swapping these two is the defect this catches.
    expect("xc7a100tDbSerial", XdcGenerator.generate(JopConfig.xc7a100tDbSerial),
      "ser_txd" -> "A5",
      "ser_rxd" -> "B5",
      "resetn"  -> "P4")
  }

  test("no preset assigns two ports to the same FPGA pin") {
    // SCOPE: PORTS IN ONE GENERATED DESIGN, not pins on the board.
    //
    // The board legitimately shares pins -- on the DB_FPGA V5, PMOD J11 pin 1
    // and the RP2040's GPIO0 land on the same FPGA pin. That is the wiring, and
    // the board model must be able to express it. What cannot happen is two
    // PORTS OF ONE DESIGN driving that pin: Vivado refuses it, and a preset that
    // enabled both a J11 PMOD and the console would be asking for exactly that.
    // So this asserts per preset, over the ports actually instantiated -- it
    // says nothing about which pins the daughter board doubles up.
    //
    // It is exactly what a copied board mapping produces: the
    // DB_FPGA V5's SD_CARD block was duplicated from V4 including "CD" -> J3:6,
    // but V5 replaced the CP2102N on J2 with the RP2040 on J3 pins 7/8 -- so
    // sd_cd and ser_txd both landed on A5. sd_cd is NOT CONNECTED on V5 at all.
    //
    // It stayed invisible because the DB V5 flow read a TRACKED xdc carrying no
    // SD pins; converting that flow to generated constraints surfaced it. A
    // board file inherited by copy needs its differences re-derived, not its
    // similarities assumed.
    val presets: Seq[(String, JopConfig)] = Seq(
      "xc7a100tDbSerial" -> JopConfig.xc7a100tDbSerial,
      "xc7a100tDbFull"   -> JopConfig.xc7a100tDbFull,
      "ep4cgx150DbFull"  -> JopConfig.ep4cgx150DbFull,
      "wukongDdr3"       -> JopConfig.wukongDdr3,
      "wukongSdram"      -> JopConfig.wukongSdram,
      "wukongFull"       -> JopConfig.wukongFull)

    val clashes = presets.flatMap { case (name, cfg) =>
      val byPin = pins(XdcGenerator.generate(cfg)).groupBy(_._2)
      byPin.collect { case (pin, ports) if ports.size > 1 =>
        s"$name: ${ports.keys.toSeq.sorted.mkString(" and ")} both on $pin"
      }
    }
    assert(clashes.isEmpty, "two ports share an FPGA pin:\n  " + clashes.mkString("\n  "))
  }

  test("XC7A100T + DB_FPGA V5 — SD card resolves to the documented pins") {
    expect("xc7a100tDbFull", XdcGenerator.generate(JopConfig.xc7a100tDbFull),
      "sd_clk"   -> "A3",
      "sd_cmd"   -> "A2",
      "sd_dat_0" -> "A4",
      "sd_dat_1" -> "B4",
      "sd_dat_2" -> "C4",
      "sd_dat_3" -> "D4")
  }
}
