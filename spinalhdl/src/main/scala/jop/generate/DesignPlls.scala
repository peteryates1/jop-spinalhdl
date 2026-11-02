package jop.generate

import jop.config._

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

  /** Write them into `ipDir`; the files written, in definition order. */
  def emit(config: BoardDesign, ipDir: String): Seq[String] =
    withVerilog(config).map { case (d, v) => v.generate(d.spec(config.assembly), d.module, ipDir) }

  /** The file each is written to, by name, in `ipDir`. */
  def files(config: BoardDesign): Seq[String] = withVerilog(config).map(_._1.module + ".v")
}
