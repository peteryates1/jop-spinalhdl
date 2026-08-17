package jop.ddr3

import spinal.core._

// BlackBox for Vivado-generated clock wizard. Default `clk_wiz_0` is the
// Alchitry-Au / DB_FPGA-V5 name; the Wukong passes a function-derived name
// (`ddr3_clk`) from Board.scala, where three incompatible variants coexist.
class ClkWizBlackBox(instanceName: String = "clk_wiz_0") extends BlackBox {
  val io = new Bundle {
    val resetn = in Bool()
    val clk_in = in Bool()
    val clk_100 = out Bool()
    val clk_200 = out Bool()
    val clk_125 = out Bool()
    val locked = out Bool()
  }

  setBlackBoxName(instanceName)
  noIoPrefix()
}
