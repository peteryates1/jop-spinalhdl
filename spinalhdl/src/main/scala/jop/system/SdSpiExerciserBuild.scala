package jop.system

import spinal.core._
import jop.config._
import jop.generate._

/**
 * Emit everything Quartus needs for the SD SPI exerciser, into
 * `build/<config>/` like every other converted flow.
 *
 * The point of this main is to answer a question elaboration cannot: does the
 * GENERATED project actually build with the vendor tool? A design that
 * elaborates and a set of constraints that look right are not the same as a
 * bitstream.
 */
object SdSpiExerciserBuild extends App {
  val design   = SdSpiExerciserDesign
  val cfgName  = "sdSpiExerciser"
  val revision = "sd_spi_exerciser"
  val layout   = BuildLayout.default
  val cfgDir   = layout.configDir(cfgName, Seq.empty)
  val board    = design.assembly.fpgaBoard

  SpinalConfig(
    mode = Verilog,
    targetDirectory = layout.rtlDir(cfgName, Seq.empty),
    defaultClockDomainFrequency = FixedFrequency(HertzNumber(design.clkMhz * 1000000L))
  ).generate(SdSpiExerciserTop(board, design.clkMhz))

  // The PLL is generated for THIS configuration's clock, so the netlist, the
  // constraints and the silicon agree on one frequency -- the thing that was
  // wrong when the top said 80 MHz and the project built a 60 MHz PLL.
  DramPllGen.emit("fpga/qmtech-ep4cgx150-sdram", design.clkMhz, cfgDir)

  def write(path: String, body: String): Unit = {
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.print(body) finally w.close()
    println(s"Wrote $path")
  }

  write(s"$cfgDir/quartus/$revision.sdc",
        TimingConstraints.forConfig(design).toSdc)
  write(s"$cfgDir/quartus/setup_proj.tcl",
        QuartusProject.generate(design, revision, cfgName, Seq.empty))
}
