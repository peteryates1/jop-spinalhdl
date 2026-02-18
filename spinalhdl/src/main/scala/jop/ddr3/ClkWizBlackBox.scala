package jop.ddr3

import spinal.core._

// BlackBox for Vivado-generated clock wizard.
class ClkWizBlackBox extends BlackBox {
  val io = new Bundle {
    val resetn = in Bool()
    val clk_in = in Bool()
    val clk_100 = out Bool()
    val clk_200 = out Bool()
    val locked = out Bool()
  }

  setBlackBoxName("clk_wiz_0")
  noIoPrefix()
}
