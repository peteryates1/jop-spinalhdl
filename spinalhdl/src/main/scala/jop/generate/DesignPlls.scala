package jop.generate

import jop.config._
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/**
 * A design's PLLs, as the generators need them (status item 173): the ones
 * the Verilog generator writes beside the RTL, and the files a project lists.
 *
 * Written here when the family's builder only writes text -- ALTPLL, whose
 * counters Quartus picks when it compiles the design. A builder that runs a
 * vendor tool (ecppll) is run by its board flow instead, through PllGenMain:
 * not every host that elaborates a design has the tool.
 */
object DesignPlls {

  /** The design's PLLs whose IP is written with its Verilog, and the builder
    * that writes each. */
  def withVerilog(config: BoardDesign): Seq[(PllDefinition, PllVendor)] =
    config.fpgaFamily.pll.filter(_.generatedWithVerilog).toSeq.flatMap(v => config.plls.map(_ -> v))

  /** Write them into `ipDir`; the files written, in definition order.
    *
    * Also removes the builder's files there that this generation did not
    * write. The Vivado flow builds every clk_wiz definition it finds in the
    * directory (`JOP_PLL_TCL`), so one left by an earlier generation -- a PLL
    * since renamed or dropped -- would be built into the design's run too. */
  def emit(config: BoardDesign, ipDir: String): Seq[String] = {
    val written = withVerilog(config).map { case (d, v) => v.generate(d.spec(config.assembly), d.module, ipDir) }
    val dir = Paths.get(ipDir)
    for (v <- config.fpgaFamily.pll.filter(_.generatedWithVerilog) if Files.isDirectory(dir)) {
      val ext = v.fileName("")
      val stale = Files.list(dir).iterator.asScala.toSeq
        .filter(p => p.getFileName.toString.endsWith(ext) && !written.contains(p.toString))
      stale.foreach(Files.delete)
    }
    written
  }

  /** The file each is written to, by name, in `ipDir`. */
  def files(config: BoardDesign): Seq[String] = withVerilog(config).map { case (d, v) => v.fileName(d.module) }
}
