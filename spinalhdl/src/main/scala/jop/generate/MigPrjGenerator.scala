package jop.generate

import hw.MigProfile
import java.nio.file.{Files, Paths}

/**
 * The MIG configuration a preset's profile asks for, as this repository's
 * Vivado flow builds it: from the board directory's tracked mig.prj into
 * build/ip/<board>/generated/.
 *
 * The profiles are hardware facts and live with the hardware (hw.MigProfile);
 * where their files go is this flow's layout, so the writer is JOP's (status
 * item 173). It was MigProfile.emit until 2026-11-03.
 */
object MigPrjGenerator {

  /**
   * Write the MIG configuration for `profile` into the board's generated/
   * directory. `mig.prj` is used as the template so the rest of the
   * MIG configuration — memory part, pinout, all of it — stays byte-identical
   * to the version known to work.
   *
   * Returns a summary line. The caller still has to run `make ddr3-create-ip`;
   * this only emits the inputs to it.
   */
  def emit(boardDir: String, profile: MigProfile): String = {
    // The tracked mig.prj is an INPUT and stays with the board; what this
    // writes is GENERATED and goes under build/, board-scoped -- one board's IP
    // serves every configuration built from it (the Wukong's serves seven), so
    // build/<config>/ would be the wrong home. Same split as DramPllGen's
    // template had, which was deleted once for looking like a build product.
    val template = Paths.get(boardDir, "vivado/ip/mig.prj")
    val outDir   = Paths.get("build", "ip", Paths.get(boardDir).getFileName.toString, "generated")
    if (!Files.exists(template))
      return s"MIG:         template not found at $template — not generated"

    var text = new String(Files.readAllBytes(template), "UTF-8")
    def sub(tag: String, value: String): Unit = {
      val re = s"<$tag>[^<]*<"
      require(re.r.findAllIn(text).size == 1,
        s"expected exactly one <$tag> in mig.prj")
      text = re.r.replaceAllIn(text, s"<$tag>$value<")
    }
    sub("TimePeriod", profile.timePeriodPs.toString)
    sub("InputClkFreq", profile.migInputMhz.toString)

    Files.createDirectories(outDir)
    Files.write(outDir.resolve("mig.prj"), text.getBytes("UTF-8"))
    // The clock that feeds MIG is the design's PLL output now (JopConfig.migPll,
    // status item 173): a ddr3_clocks.tcl fragment for the board's clk_wiz
    // script carried it until 2026-11-02.

    f"MIG:         ${profile.name} — ${profile.timePeriodPs} ps, memory " +
    f"${profile.memoryMhz}%.1f MHz, ui_clk ${profile.uiClkMhz}%.2f MHz " +
    f"(${profile.note})"
  }
}
