package jop.config

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.system.{JopTop, JopSpinalConfig}
import jop.utils.JopFileLoader
import org.scalatest.funsuite.AnyFunSuite

/**
 * Elaborate presets at a NON-DEFAULT method cache geometry.
 *
 * WHY THIS EXISTS. `jpcWidth` was pinned by `require(jpcWidth == 11)` for years.
 * Nothing else was then obliged to respect it, so NINE places quietly hardcoded
 * its consequences — `12` (= jpcWidth + 1) and `2048` (= 1 << jpcWidth) — and
 * the pin guaranteed nobody ever hit them. Most came straight from the VHDL,
 * where they were constants rather than parameters.
 *
 * Removing the pin was worth +34.4 % on Kfl (item 51), so the geometry is now
 * something people will change. This test is what stops the hardcoding growing
 * back: it simply elaborates at a width nothing defaults to, which catches
 *
 *   - WIDTH MISMATCH  (HangDetector.io_jpc, DiagUart.latchJpc, BufferCC inits)
 *   - array-size assertions (jbcInit was Seq.fill(2048) in three places)
 *
 * It does NOT catch the third and nastiest kind: a silent `.resized`
 * truncation, which is what `MemCtrlOutput.bcStart` at 12 bits was. That one
 * elaborates cleanly, boots, and only then throws uncaught exceptions once a
 * method lands above 4 KB. `MethodCacheSweepSim` is what catches those, by
 * running real code at several geometries — keep both.
 *
 * PRESET CHOICE MATTERS. `ae115fbDdr2` elaborated fine at 32 KB while the
 * Wukong dual did not, because only the dual instantiates the monitors that
 * carried three of the nine. So cover both a plain single-system preset and one
 * WITH monitors, or the coverage is illusory.
 */
class NonDefaultGeometryElabTest extends AnyFunSuite {

  // MUST NOT MATCH THE DEFAULT, or this proves nothing. The default became
  // 13/6 (8 KB, 64 x 32w) on 2026-08-19, so probe either side of it: 11/4 is
  // the old 2 KB geometry (jpc 12 bits -- catches anything that now hardcodes
  // 14), and 14/7 is 16 KB / 128 blocks (jpc 15 bits -- catches anything still
  // hardcoding 12 or 14).
  private val geometries = Seq((11, 4), (14, 7))

  private lazy val romData = JopFileLoader.loadMicrocodeRom("asm/generated/serial/mem_rom.dat")
  private lazy val ramData = JopFileLoader.loadStackRam("asm/generated/serial/mem_ram.dat")

  private def elaborates(name: String, cfg: JopConfig): Unit =
    JopSpinalConfig(cfg).copy(targetDirectory = "spinalhdl/generated/test")
      .generate(InOutWrapper(JopTop(
        config = cfg, romInit = romData, ramInit = ramData,
        mainMemInit = None, mainMemSize = 64 * 1024)))

  for ((jw, bb) <- geometries) {
    test(s"single-system preset elaborates at jpcWidth=$jw blockBits=$bb") {
      elaborates("ae115fbDdr2", MCacheOverride(JopConfig.ae115fbDdr2, s"$jw/$bb"))
    }
    // The dual carries HangDetector/DiagUart, which is where three of the nine
    // hardcoded widths lived. Without this arm the test passes and proves less.
    test(s"multi-system preset WITH MONITORS elaborates at jpcWidth=$jw blockBits=$bb") {
      elaborates("wukongDualIndependent",
        MCacheOverride(JopConfig.wukongDualIndependent, s"$jw/$bb"))
    }
  }
}
