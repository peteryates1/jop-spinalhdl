package jop.generate

import jop.config._

/**
 * Generate a design's PLL IP from the spec its board declares.
 *
 *   sbt "runMain jop.generate.PllGenMain <preset> --module <name> --out <dir>"
 *
 * Only for boards whose PllType declares a spec. The rest still carry
 * hand-written IP and say so by declaring none, which is why this refuses
 * rather than inventing a default.
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
  val board  = config.assembly.fpgaBoard
  val spec = board.pllType.flatMap(_.spec).getOrElse(sys.error(
    s"board '${board.name}' declares no PLL spec -- its IP is still hand-written"))

  val vendor: PllVendor = config.fpgaFamily.manufacturer match {
    case Manufacturer.Lattice => LatticeEcpPll
    case m => sys.error(s"no PLL generator for $m yet -- its IP is committed, " +
                        s"and PllSpecConsistencyTest checks it against the spec")
  }

  val path = vendor.generate(spec, module, outDir)
  println(s"Wrote $path  (${spec.inputMhz} MHz in, " +
          spec.outputs.map(o => s"${o.role} ${o.mhz}MHz@${o.phaseDeg}deg").mkString(", ") + ")")
}
