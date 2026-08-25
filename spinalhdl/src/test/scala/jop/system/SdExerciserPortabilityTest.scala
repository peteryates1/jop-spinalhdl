package jop.system

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import jop.config._

/**
 * The SD exerciser must elaborate on every FPGA family, from one source.
 *
 * It could not before: `SdSpiExerciserTop` instantiated `DramPll()` directly --
 * an Altera altpll blackbox -- so the design was nailed to one vendor by its
 * CLOCK, not by anything to do with SD. Its SD pins already resolved on all
 * three boards (see BoardDesignTest); it was the clock that could not move.
 *
 * Taking the PLL from `Board.pllType` fixes that, and this test is what keeps
 * it fixed. A future top that reaches for a vendor primitive directly will pass
 * on its home board and fail here.
 */
class SdExerciserPortabilityTest extends AnyFunSuite {

  private val boards: Seq[(String, Board, Int, String)] = Seq(
    ("EP4CGX150 (Altera Cyclone IV GX)", Board.QmtechEP4CGX150, 80,  "dram_pll"),
    ("Wukong (Xilinx Artix-7)",          Board.WukongXC7A100T, 100,  "bram_clk"),
    ("Colorlight i5 (Lattice ECP5)",     Board.ColorlightI5,    40,  "pll_jop_i5"))

  for ((label, board, mhz, primitive) <- boards) {
    test(s"SD SPI exerciser elaborates on $label") {
      val dir = java.nio.file.Files.createTempDirectory("sdport").toString
      SpinalConfig(
        mode = Verilog,
        targetDirectory = dir,
        defaultClockDomainFrequency = FixedFrequency(HertzNumber(mhz * 1000000L))
      ).generate(SdSpiExerciserTop(board, mhz))

      val v = scala.io.Source.fromFile(s"$dir/SdSpiExerciserTop.v").mkString
      // Not merely "it elaborated" -- it must instantiate THIS family's PLL.
      assert(v.contains(primitive),
        s"expected the $label PLL primitive '$primitive' in the netlist")
    }
  }
}
