package jop.generate

import jop.config._

/**
 * Generate a design's PLL IP from the definition the design gives it, with
 * its FPGA family's builder (status item 173).
 *
 *   sbt "runMain jop.generate.PllGenMain <preset> --module <name> --out <dir>"
 *
 * Only for designs that define their PLL, on a family that has a builder. The
 * rest still carry IP made by hand or by the board's flow, which is why this
 * refuses rather than inventing a default. `--module` is the file name the
 * flow synthesises; it must be the module the design's definition names.
 */
object PllGenMain extends App {
  import jop.system.JopTopVerilog

  private def opt(name: String): Option[String] = {
    val i = args.indexOf(name)
    if (i >= 0 && args.length > i + 1) Some(args(i + 1)) else None
  }

  val preset = args.headOption.getOrElse(
    sys.error("usage: PllGenMain <preset> --module <name> --out <dir>"))
  val module = opt("--module").getOrElse(sys.error("--module is required"))
  val outDir = opt("--out").getOrElse(sys.error("--out is required"))

  val cfgArgs = args.zipWithIndex.filterNot { case (a, i) =>
    Seq("--module", "--out").contains(a) ||
      (i > 0 && Seq("--module", "--out").contains(args(i - 1)))
  }.map(_._1)

  val config = JopTopVerilog.resolvePreset(preset, cfgArgs)
  val pll = config.system.pll.getOrElse(sys.error(
    s"$preset defines no PLL -- its board still declares one, with IP made by hand or by the board's flow"))
  require(module == pll.module,
    s"--module $module, but $preset's PLL is ${pll.module}: the flow would synthesise a file the design does not instantiate")
  val vendor = config.fpgaFamily.pll.getOrElse(sys.error(
    s"${config.fpgaFamily} has no PLL builder yet -- its IP is committed, " +
      "and PllSpecConsistencyTest checks it against the spec"))
  val spec = pll.spec(config.assembly)

  val path = vendor.generate(spec, pll.module, outDir)
  println(s"Wrote $path  (${pll.reference} ${spec.inputMhz} MHz in, " +
          spec.outputs.map(_.describe).mkString(", ") + ")")
}
