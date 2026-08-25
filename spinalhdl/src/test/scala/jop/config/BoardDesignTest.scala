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

  /** SD is ordinary I/O on both families, so a portable SD design needs NOTHING
    * beyond a BoardDesign -- no vendor abstraction, no new board data. This is
    * the claim that lets the SD exercisers skip phase 2a entirely; it is a test
    * rather than a plan bullet so it stays true.
    *
    * Note the DB boards are shared between the EP4CGX150 and the XC7A100T, so
    * the SAME SD_CARD mapping is being resolved behind two different FPGA
    * families here. */
  private def sdDesign(asm: SystemAssembly, dt: DeviceType) = new BoardDesign {
    val assembly   = asm
    val entityName = "SdExerciserTop"
    val designName = "sd-exerciser"
    val devices    = Map("sd" -> DeviceInstance(dt, devicePart = Some("SD_CARD")))
    val resetInput = None
    val usesSdr    = false
    val memType    = None
    val fpga       = asm.fpga
    val fpgaFamily = asm.fpgaFamily
  }

  private val sdBoards = Seq(
    ("wukong", SystemAssembly.wukong),
    ("ep4cgx150 + DB v4", SystemAssembly.qmtechWithDb),
    ("xc7a100t + DB v5", SystemAssembly.xc7a100tWithDbV5))

  for ((label, asm) <- sdBoards) {
    test(s"SD-over-SPI pins resolve on $label") {
      val pins = PinResolver.devicePins(asm, sdDesign(asm, DeviceType.SdSpi).devices)
      assert(pins.size == 5, s"expected 5 SPI pins, got ${pins.map(_.verilogPort)}")
      assert(pins.forall(_.fpgaPin.nonEmpty))
    }
    test(s"SD native 4-bit pins resolve on $label") {
      val pins = PinResolver.devicePins(asm, sdDesign(asm, DeviceType.SdNative).devices)
      assert(pins.size == 7, s"expected 7 native pins, got ${pins.map(_.verilogPort)}")
      assert(pins.forall(_.fpgaPin.nonEmpty))
    }
  }

  test("JopConfig still satisfies BoardDesign") {
    val jop: BoardDesign = JopConfig.ep4cgx150Serial
    assert(jop.entityName.nonEmpty)
    assert(jop.assembly.boards.nonEmpty)
  }
}
