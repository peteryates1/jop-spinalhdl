package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.config._
import jop.generate._

/** Emit everything Quartus needs for the SDRAM exerciser.
  * The first converted top that drives memory -- so unlike the SD exercisers it
  * gets the sdram_* pins and the phase-shifted memory clock. */
object SdramExerciserBuild extends App {
  val design   = SdramExerciserDesign
  val cfgName  = "sdramExerciser"
  val revision = "sdram_test"
  val layout   = BuildLayout.default
  val cfgDir   = layout.configDir(cfgName, Seq.empty)
  val board    = design.assembly.fpgaBoard

  SpinalConfig(
    mode = Verilog,
    targetDirectory = layout.rtlDir(cfgName, Seq.empty),
    defaultClockDomainFrequency = FixedFrequency(HertzNumber(design.clkMhz * 1000000L))
  ).generate(InOutWrapper(SdramExerciserTop(design.memoryDevice, board, design.clkMhz)))

  DramPllGen.emit(design.clkMhz, cfgDir)

  def write(path: String, body: String): Unit = {
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.print(body) finally w.close()
    println(s"Wrote $path")
  }

  // The build summary console.mk reads to find the download rate. Without it
  // BAUD resolves to empty and `make monitor` runs with no rate -- the JOP
  // flows have emitted this all along, the exercisers did not.
  write(s"${layout.rtlDir(cfgName, Seq.empty)}/${design.entityName}.summary.txt",
        s"""=== SDRAM Exerciser Build Configuration ===
           |  Entity:        ${design.entityName}
           |  Board:         ${board.name}
           |  FPGA:          ${design.fpga.name}
           |  Clock:         ${design.clkMhz} MHz
           |  UART baud:   ${design.uartBaud}
           |""".stripMargin)

  write(s"$cfgDir/quartus/$revision.sdc", TimingConstraints.forConfig(design).toSdc)
  write(s"$cfgDir/quartus/setup_proj.tcl",
        QuartusProject.generate(design, revision, cfgName, Seq.empty))
}
