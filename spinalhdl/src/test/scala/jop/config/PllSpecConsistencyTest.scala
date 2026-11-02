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

  /** Read back what the generated altpll declares for output `n`. */
  private def declaredRatio(v: String, n: Int): (Int, Int) = {
    def find(key: String): Int =
      s"\\.clk${n}_${key}_by\\((\\d+)\\)".r.findFirstMatchIn(v).map(_.group(1).toInt)
        .getOrElse(fail(s"no clk${n}_${key}_by in the generated PLL"))
    (find("multiply"), find("divide"))
  }

  /** The PLL the Verilog generator writes for `cfg` (DesignPlls). */
  private def generated(cfg: JopConfig): String = {
    val dir = Files.createTempDirectory("pllspec").toString
    val written = jop.generate.DesignPlls.emit(cfg, dir)
    assert(written.size == 1, s"expected one PLL, wrote $written")
    new String(Files.readAllBytes(Paths.get(written.head)), "UTF-8")
  }

  // The EP4CGX150 at the clocks it runs: 80 MHz single-core, and 60, 50 and
  // 36 at 4, 8 and 12 cores. Until 2026-11-02 this read DramPllGen's patched
  // template; the design's own PLL is held to the same thing.
  for ((mhz, cfg) <- Seq(80 -> JopConfig.ep4cgx150Serial, 60 -> JopConfig.ep4cgx150Smp(4, 60),
                         50 -> JopConfig.ep4cgx150Smp(8, 50), 36 -> JopConfig.ep4cgx150Smp(12, 36))) {
    test(s"generated altpll implements $mhz MHz as declared") {
      val in = (cfg.inputClock.freq.toBigDecimal / 1000000).toIntExact
      // c0 is the SYSTEM clock: the definition's first output.
      val (mul, div) = declaredRatio(generated(cfg), 0)
      assert(in * mul % div == 0, s"$mhz MHz is not exactly reachable from $in MHz")
      assert(in * mul / div == mhz, s"asked for $mhz MHz, the IP declares $in * $mul / $div")
    }
  }

  test("the SDRAM output tracks the system output") {
    // They must be the same frequency: the SDRAM clock is the system clock with
    // a phase shift, and a divergence here would be invisible until hardware.
    val v = generated(JopConfig.ep4cgx150Smp(4, 60))
    assert(declaredRatio(v, 0) == declaredRatio(v, 1),
      "system (c0) and SDRAM (c1) clocks must have the same ratio")
  }
}
