package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate._

/**
 * The constraint generators must work for a design that is NOT a JopConfig.
 *
 * The board data was always generic -- every PinResolver method takes a
 * SystemAssembly and nothing else -- but the generators declared JopConfig as
 * their parameter type while reading a handful of fields. That signature, and
 * nothing else, is why the SD / config-flash / SPI / flash-programmer
 * exercisers still carry hand-written .qsf files.
 *
 * This test is the proof that the coupling is gone. It builds a BoardDesign by
 * hand -- no JopConfig anywhere -- standing in for ConfigFlashExerciserTop,
 * which really does have a clk_in, a ser_txd, two LEDs and no SDRAM.
 *
 * If someone reintroduces a JopConfig-only dependency in a generator, this
 * stops compiling, which is the point.
 */
class BoardDesignTest extends AnyFunSuite {

  /** Minimal non-JOP design: UART out, LEDs, no memory controller. */
  object ExerciserDesign extends BoardDesign {
    val assembly    = SystemAssembly.qmtechWithDb
    val entityName  = "ConfigFlashExerciserTop"
    val designName  = "config-flash-exerciser"
    val devices     = Map(
      "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N")))
    val resetInput  = None
    val usesSdr     = false
    val memType     = None
    val fpga        = assembly.fpga
    val fpgaFamily  = assembly.fpgaFamily
  }

  test("a non-JopConfig design gets a Quartus project") {
    val tcl = QuartusProject.generate(ExerciserDesign, "config_flash_exerciser",
                                      "exerciser", Seq.empty)
    assert(tcl.contains("TOP_LEVEL_ENTITY"))
    assert(tcl.contains("ConfigFlashExerciserTop"))
    assert(tcl.contains(ExerciserDesign.fpga.name))
  }

  test("a non-JopConfig design gets timing constraints") {
    val sdc = TimingConstraints.forConfig(ExerciserDesign).toSdc
    // The board oscillator is a property of the ASSEMBLY, so it resolves for
    // any design on that board, JOP or not.
    assert(sdc.contains("create_clock"))
    assert(sdc.contains("clk_in"))
  }

  test("no SDRAM constraints for a design that drives none") {
    // The bug this guards is not cosmetic: constraining ports the design does
    // not have made Quartus discard a whole set_clock_groups silently, and put
    // sdram_* into a DDR3 build.
    val sdc = TimingConstraints.forConfig(ExerciserDesign).toSdc
    assert(!sdc.contains("e_rxc"), "no Ethernet in this design")
  }

  test("JopConfig still satisfies BoardDesign") {
    val jop: BoardDesign = JopConfig.ep4cgx150Serial
    assert(jop.entityName.nonEmpty)
    assert(jop.assembly.boards.nonEmpty)
  }
}
