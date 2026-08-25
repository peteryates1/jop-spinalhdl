package jop.config

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}

/**
 * Vendor IP must stay in step with the spec it claims to implement -- checked
 * WITHOUT the vendor tool, because CI has none.
 *
 * CI skips IP generation for the closed toolchains (Quartus, Vivado): the tools
 * are licensed and enormous. But "CI cannot build it" is exactly how the flash
 * microcode sat sixteen days stale against a changed source, so skipping
 * generation must not mean skipping VERIFICATION.
 *
 * It does not have to. The generated altpll declares its own multiply and
 * divide, so the file can be read back and checked against what the spec
 * computes -- catching a stale IP, a hand-edited one, or a spec that has moved
 * on, none of which need Quartus to detect.
 *
 * The open-source arm needs no such trick: ecppll is a 200 KB apt package
 * (fpga-trellis), so CI can regenerate the ECP5 PLL and diff it outright.
 */
class PllSpecConsistencyTest extends AnyFunSuite {

  private val boardDir = "fpga/qmtech-ep4cgx150-sdram"

  /** Read back what the emitted VHDL actually declares. */
  private def declaredRatio(vhdl: String, output: Int): (Int, Int) = {
    def find(key: String): Int =
      s"clk${output}_${key}_by => (\\d+)".r.findFirstMatchIn(vhdl)
        .map(_.group(1).toInt)
        .getOrElse(fail(s"no clk${output}_${key}_by in the generated PLL"))
    (find("multiply"), find("divide"))
  }

  for (mhz <- Seq(36, 50, 60, 80)) {
    test(s"generated altpll implements $mhz MHz as declared") {
      assume(Files.exists(Paths.get(boardDir, "dram_pll.vhd")),
             "PLL template not present")
      val tmp = Files.createTempDirectory("pllspec").toString
      DramPllGen.emit(boardDir, mhz, tmp)

      val out = Paths.get(tmp, "ip", "dram_pll.vhd")
      assert(Files.exists(out), s"DramPllGen wrote nothing for $mhz MHz")
      val vhdl = new String(Files.readAllBytes(out), "UTF-8")

      // c1 is the SYSTEM clock -- despite the file being called dram_pll.
      val (mul, div) = declaredRatio(vhdl, 1)
      val actual = DramPllGen.inputMhz * mul / div
      assert(actual == mhz,
        s"asked for $mhz MHz, the IP declares ${DramPllGen.inputMhz} * $mul / $div = $actual MHz")
      assert(DramPllGen.inputMhz * mul % div == 0,
        s"$mhz MHz is not exactly reachable from ${DramPllGen.inputMhz} MHz")
    }
  }

  test("the SDRAM output tracks the system output") {
    // They must be the same frequency: the SDRAM clock is the system clock with
    // a phase shift, and a divergence here would be invisible until hardware.
    val tmp = Files.createTempDirectory("pllspec").toString
    DramPllGen.emit(boardDir, 60, tmp)
    val vhdl = new String(Files.readAllBytes(Paths.get(tmp, "ip", "dram_pll.vhd")), "UTF-8")
    assert(declaredRatio(vhdl, 1) == declaredRatio(vhdl, 2),
      "system (c1) and SDRAM (c2) clocks must have the same ratio")
  }
}
