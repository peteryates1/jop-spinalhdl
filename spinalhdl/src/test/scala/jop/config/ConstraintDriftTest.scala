package jop.config

import scala.io.Source
import org.scalatest.funsuite.AnyFunSuite

import jop.generate.{XdcGenerator, QsfGenerator}

/**
 * Compare the constraints the GENERATORS produce against the tracked files that
 * serve as their ORACLES.
 *
 * WHY THIS EXISTS. `XdcGenerator` and `QsfGenerator` both take a `JopConfig`
 * and resolve pins through `PinResolver`. This file used to say **nothing
 * invokes either of them** — true when it was written, false since: the Wukong
 * and i5 Makefiles call `XdcGeneratorMain` and `LpfGeneratorMain`, the
 * EP4CGX150 takes a generated `pins.tcl`, and as of 2026-08-30 the Wukong's SMP
 * SDR flow generates its own XDC too, so no board build reads a tracked
 * constraint file as an INPUT any more.
 *
 * That changes what these files are for, and this test with it. They are no
 * longer what the build reads; they are the known-good reference the generator
 * is checked against. Keep them for that reason — deleting them as "unused"
 * would remove the only thing that would notice the generator drifting.
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

  // ---------------------------------------------------------------- retired
  //
  // The EP4CGX150 QSF case was here, and is gone because the board now takes
  // GENERATED pins: jop_sdram.qsf carries
  //   set_global_assignment -name SOURCE_TCL_SCRIPT_FILE generated/pins.tcl
  // and no longer lists set_location_assignment lines at all. There is no
  // hand-written pin list left to drift against, so the comparison has nothing
  // to compare -- this test failed with "parsed no pins" the moment the switch
  // was made, which is the guard working rather than breaking.
  //
  // It closed its gap first: QsfGenerator had no reset handling, so the SW1
  // button was omitted. Fixed by lifting the predicate into
  // JopConfig.resetInput, after which the generated pins matched the hand file
  // exactly, and a control build proved it -- 11,076 LE and +9.714 ns slack
  // either way, with 45 assignments reported by Quartus as source "User".
  //
  // What guards it now is that there is only one description: the pins come
  // from JopConfig, the same place the RTL does. The remaining case below
  // guards a board whose constraints are STILL hand-written.

}
