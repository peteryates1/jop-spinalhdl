package jop.generate

import jop.config._

/**
 * Everything `fpga/scripts/hw_verify.py` needs to put a build on real hardware,
 * emitted from the config so the script itself knows nothing about boards.
 *
 * WHY IT EXISTS. Hardware verification was done by hand three times in one
 * session and got a different incantation each time -- wrong bitstream path on
 * one board, wrong probe selection on another (two dirtyJtag probes are
 * attached, and a bare `-c dirtyJtag` takes whichever enumerated first), and a
 * guessed console alias on a third, which resolved to nothing and was reported
 * as a broken board. All three are the same defect: board facts restated at the
 * point of use instead of derived.
 *
 * The aliases are deliberately NOT resolved here. `jtag_probe_map` and
 * `usb_serial_map` own that, they resolve by SERIAL because port paths move on
 * every replug, and duplicating their tables in Scala would be a second copy
 * that goes stale. This emits the alias; the script resolves it, and refuses to
 * run if it does not resolve.
 */
object HwVerifyDescriptor {

  /** `access` is how the unit is reached, from its board instance if one covers
    * it; without one, the assembly's boards say (status items 173, 174). */
  def generate(config: BoardDesign, preset: String, args: Seq[String],
               access: Option[UnitAccess] = None): String = {
    val assembly = config.assembly
    val reach = access.getOrElse(assembly.access)
    // The FPGA's board for its name and family; probe, console and loader from
    // the ASSEMBLY, which on a module + base board are the base board's.
    val board = assembly.fpgaBoard
    val layout = BuildLayout.default
    val cfgDir = layout.configDir(preset, args)

    val baud = config.devices.values
      .find(_.deviceType.key == "uart")
      .flatMap(_.params.get("baudRate").map(_.asInstanceOf[Int]))
      // The exercisers declare no baudRate and run at 1 Mbaud, which is what
      // their Makefiles have always used.
      .getOrElse(if (config.isInstanceOf[JopConfig]) 2000000 else 1000000)

    val family = board.fpga.map(_.family.toString).getOrElse("unknown")
    val tool = if (reach.loaderCable.isDefined || reach.loaderBoard.isDefined)
      "openfpgaloader" else "quartus"

    val sb = new StringBuilder
    sb.append(s"PRESET=$preset\n")
    sb.append(s"CONFIG_DIR=$cfgDir\n")
    sb.append(s"ENTITY=${config.entityName}\n")
    sb.append(s"BOARD=${board.name}\n")
    sb.append(s"FAMILY=$family\n")
    // Cores is a JopConfig notion; an exerciser has none.
    config match {
      case j: JopConfig => sb.append(s"CORES=${j.system.cpuCnt}\n")
      case _            => ()
    }
    sb.append(s"BAUD=$baud\n")
    sb.append(s"PROGRAM_TOOL=$tool\n")
    reach.probeAlias.foreach(a => sb.append(s"PROBE_ALIAS=$a\n"))
    reach.consoleAlias.foreach(a => sb.append(s"CONSOLE_ALIAS=$a\n"))
    reach.loaderCable.foreach(c => sb.append(s"LOADER_CABLE=$c\n"))
    reach.loaderBoard.foreach(b => sb.append(s"LOADER_BOARD=$b\n"))
    sb.toString
  }
}

object HwVerifyDescriptorMain extends App {
  import jop.system.JopTopVerilog

  /** A design may be named either way:
    *
    *   a PRESET      "ep4cgx150Serial"                -> a JopConfig
    *   a DESIGN      "jop.system.SdramExerciserDesign" -> a BoardDesign object
    *
    * The exercisers are not presets -- they are standalone tops with no JOP
    * core and no .jop to download -- but they are still designs on a board, so
    * step 5 should reach them. Resolved by reflection because the alternative
    * is a registry that every new design must remember to join.
    */
  private def asObject(name: String): Option[(BoardDesign, String)] =
    if (!name.contains('.')) None
    else scala.util.Try {
      val cls = Class.forName(name + "$")
      val obj = cls.getField("MODULE$").get(null).asInstanceOf[BoardDesign]
      (obj, name.split('.').last.stripSuffix("Design"))
    }.toOption

  val preset = args.headOption.getOrElse(
  sys.error(
    "no preset given. Pass the preset name as the first argument.\n" +
    "There is deliberately no default: this main writes a constraint or\n" +
    "project file, and a default silently produces a WELL-FORMED file for\n" +
    "the wrong board at the path --write names, which then builds."))
  val writeIdx = args.indexOf("--write")
  val outPath = if (writeIdx >= 0 && args.length > writeIdx + 1) Some(args(writeIdx + 1)) else None
  val cfgArgs = args.zipWithIndex.filterNot { case (a, i) =>
    a == "--write" || (writeIdx >= 0 && i == writeIdx + 1)
  }.map(_._1)
  val (design, cfgKey, jopArgs) = asObject(preset) match {
    case Some((d, shortName)) =>
      // build/<shortName with a lowercase initial>/ -- the same key the design's
      // own Build main uses.
      val key = shortName.head.toLower + shortName.tail
      (d, key, Seq.empty[String])
    case None =>
      (JopTopVerilog.resolvePreset(preset, cfgArgs): BoardDesign,
       preset, cfgArgs.drop(1).toSeq)
  }
  // An entry point, so it may read the setup layer: the unit this design is
  // built for, if an instance covers its assembly.
  val unit = jop.setup.Instances.forAssembly(design.assembly)
  val text = HwVerifyDescriptor.generate(design, cfgKey, jopArgs, unit.map(_.access))
  outPath match {
    case Some(p) =>
      new java.io.File(p).getParentFile.mkdirs()
      val w = new java.io.PrintWriter(p); w.print(text); w.close()
      println(s"Wrote $p")
    case None => print(text)
  }
}
