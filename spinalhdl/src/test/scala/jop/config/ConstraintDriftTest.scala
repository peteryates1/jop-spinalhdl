package jop.config

import scala.io.Source
import org.scalatest.funsuite.AnyFunSuite

import jop.generate.{XdcGenerator, QsfGenerator}

/**
 * Compare the constraints the GENERATORS produce against the hand-written files
 * the board builds actually read.
 *
 * WHY THIS EXISTS. `XdcGenerator` and `QsfGenerator` both take a `JopConfig`
 * and resolve pins through `PinResolver`, and **nothing invokes either of
 * them** — no Makefile, no TCL, no test (item 57). So the config is not the
 * source of truth for pins, and the two copies drift with nothing to say so.
 *
 * That is not hypothetical. On 2026-08-23 a Wukong DDR3 build was silent on the
 * console for an hour because the config says the UART is the on-board CH340N
 * while `wukong_ddr3_base.xdc` puts it on the J11 header. Both were "right";
 * they simply disagreed, and no build failed and no test went red.
 *
 * WHAT IT ASSERTS, and why the asymmetry:
 *
 *   - a port in BOTH, assigned DIFFERENT pins            -> failure
 *   - a port in the HAND file but MISSING from generated -> failure
 *   - a port GENERATED but absent from the hand file     -> ignored
 *
 * The third is ignored because `QsfGeneratorMain` deliberately emits
 * daughter-board Ethernet and SD pins for RESERVATION even on presets that do
 * not instantiate them. The first two are the ones that put a signal on the
 * wrong ball or leave the tool free to place it anywhere.
 *
 * THIS IS A RATCHET, NOT A PASS/FAIL ON PERFECTION. Three genuine gaps are
 * known today and are listed per board below. The test fails if a NEW
 * difference appears, and equally if a listed gap DISAPPEARS — so fixing one
 * forces the list to be updated rather than leaving a stale excuse behind.
 * As the gaps are fixed the lists empty, and when a board's list is empty its
 * build can switch to the generated file (item 57 step 3).
 */
class ConstraintDriftTest extends AnyFunSuite {

  /** port -> (pin the generator emits, pin the hand-written file assigns).
    * `None` for the generated side means the generator omits the port. */
  type Gaps = Map[String, (Option[String], String)]

  private def readFile(path: String): String = {
    val f = new java.io.File(path)
    assert(f.exists(), s"constraint file not found: $path (test expects to run from the repo root)")
    val src = Source.fromFile(f)
    try src.mkString finally src.close()
  }

  /** `set_property PACKAGE_PIN <pin> [get_ports {<port>}]` */
  private val xdcPin = """set_property\s+PACKAGE_PIN\s+(\S+)\s+\[get_ports\s*\{([^}]+)\}\]""".r
  /** `set_location_assignment PIN_<pin> -to <port>` */
  private val qsfPin = """set_location_assignment\s+PIN_(\S+)\s+-to\s+(\S+)""".r

  private def parse(text: String, isQsf: Boolean): Map[String, String] = {
    val rx = if (isQsf) qsfPin else xdcPin
    // Later assignment wins, matching how both tools read a file top to bottom.
    rx.findAllMatchIn(text).foldLeft(Map.empty[String, String]) { (acc, m) =>
      acc + (m.group(2).trim -> m.group(1).trim)
    }
  }

  private def check(label: String, generated: String, handPath: String,
                    isQsf: Boolean, knownGaps: Gaps): Unit = {
    val gen  = parse(generated, isQsf)
    val hand = parse(readFile(handPath), isQsf)

    val mismatched = hand.toSeq.sortBy(_._1).flatMap { case (port, handPin) =>
      gen.get(port) match {
        case Some(genPin) if genPin != handPin => Some(port -> (Some(genPin), handPin))
        case None                              => Some(port -> (None, handPin))
        case _                                 => None
      }
    }.toMap

    assert(hand.nonEmpty, s"$label: parsed no pins from $handPath — parser or file format changed")

    val unexpected = mismatched.filterNot { case (p, v) => knownGaps.get(p).contains(v) }
    val disappeared = knownGaps.keySet -- mismatched.keySet

    def show(m: Gaps) = m.toSeq.sortBy(_._1).map { case (port, (g, h)) =>
      s"  $port: generated=${g.getOrElse("<omitted>")} hand=$h"
    }.mkString("\n")

    assert(unexpected.isEmpty,
      s"$label: NEW constraint drift against $handPath.\n" +
      s"The config and the hand-written file disagree about pins nobody declared as known:\n" +
      show(unexpected) +
      "\nEither fix the config/generator, or add these to knownGaps with a reason.")

    assert(disappeared.isEmpty,
      s"$label: a known gap is GONE — ${disappeared.mkString(", ")}.\n" +
      "That is good news: remove it from knownGaps so the list keeps meaning something.")
  }

  // ---------------------------------------------------------------- Vivado

  test("wukongSdram XDC matches the hand-written constraints") {
    // Measured 2026-08-23: pin-identical, 45 pins each. This board is the one
    // ready to switch to the generated file.
    check("wukongSdram", XdcGenerator.generate(JopConfig.wukongSdram),
      "fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_jop_sdram.xdc",
      isQsf = false, knownGaps = Map.empty)
  }

  test("wukongDdr3 XDC matches the hand-written constraints") {
    // GAP CLOSED 2026-08-23. The preset said devicePart = CH340N (E3/F3) while
    // every DDR3 build reads wukong_ddr3_base.xdc, which routes the UART to
    // J11 -> Pico uart0 (A5/A4). Fixed by pointing the DDR3 presets at
    // PICO_UART0, which the Wukong board already declared, and by mapping that
    // device THROUGH the J11 connector rather than at raw balls. This test
    // caught the closure itself -- it failed with "a known gap is GONE" until
    // the entries below were removed.
    check("wukongDdr3", XdcGenerator.generate(JopConfig.wukongDdr3),
      "fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_ddr3_base.xdc",
      isQsf = false, knownGaps = Map.empty)
  }

  // --------------------------------------------------------------- Quartus

  test("ep4cgx150 QSF matches the hand-written constraints") {
    // Mirrors QsfGeneratorMain: daughter-board peripherals are added for pin
    // reservation, which is why generated-only ports are not a failure.
    val base = JopConfig.ep4cgx150Serial
    val withDb = base.copy(systems = Seq(base.system.copy(
      devices = base.system.devices ++ Map(
        "eth" -> DeviceInstance(DeviceType.Ethernet,
          params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative,
          devicePart = Some("SD_CARD"))))))

    check("ep4cgx150Serial", QsfGenerator.generate(withDb),
      "fpga/qmtech-ep4cgx150-sdram/jop_sdram.qsf",
      // GAP CLOSED 2026-08-23. QsfGenerator had no reset handling at all, so
      // the SW1 button was left for Quartus to place wherever it liked. Fixed
      // by lifting the predicate into JopConfig.resetInput, which JopTop,
      // XdcGenerator and QsfGenerator now all read -- rather than each
      // re-deciding whether the port exists and what it is called.
      isQsf = true, knownGaps = Map.empty)
  }
}
