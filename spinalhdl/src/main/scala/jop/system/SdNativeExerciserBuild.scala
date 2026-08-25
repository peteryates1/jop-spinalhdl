package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.config._
import jop.generate._

/** Emit everything Quartus needs for the SD native (4-bit) exerciser.
  * Sibling of SdSpiExerciserBuild -- same board, same card, wider bus. */
object SdNativeExerciserBuild extends App {
  val design   = SdNativeExerciserDesign
  /** Clock override, for bisecting a hardware failure against the frequency the
    * hand-written project used. `sbt "runMain ... 60"` */
  val clkMhz   = args.headOption.map(_.toInt).getOrElse(design.clkMhz)
  val cfgName  = "sdNativeExerciser"
  val revision = "sd_native_exerciser"
  val layout   = BuildLayout.default
  val cfgDir   = layout.configDir(cfgName, Seq.empty)
  val board    = design.assembly.fpgaBoard

  SpinalConfig(
    mode = Verilog,
    targetDirectory = layout.rtlDir(cfgName, Seq.empty),
    defaultClockDomainFrequency = FixedFrequency(HertzNumber(clkMhz * 1000000L))
  ).generate(InOutWrapper(SdNativeExerciserTop(board, clkMhz)))

  DramPllGen.emit("fpga/qmtech-ep4cgx150-sdram", clkMhz, cfgDir)

  def write(path: String, body: String): Unit = {
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.print(body) finally w.close()
    println(s"Wrote $path")
  }

  write(s"$cfgDir/quartus/$revision.sdc", TimingConstraints.forConfig(design).toSdc)
  write(s"$cfgDir/quartus/setup_proj.tcl",
        QuartusProject.generate(design, revision, cfgName, Seq.empty))
}
