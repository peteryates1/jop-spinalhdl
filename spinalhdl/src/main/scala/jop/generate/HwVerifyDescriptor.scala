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

  def generate(config: BoardDesign, preset: String, args: Seq[String]): String = {
    val assembly = config.assembly
    val board = assembly.boards.head
    val layout = BuildLayout.default
    val cfgDir = layout.configDir(preset, args)

    val baud = config.devices.values
      .find(_.deviceType.key == "uart")
      .flatMap(_.params.get("baudRate").map(_.asInstanceOf[Int]))
      // The exercisers declare no baudRate and run at 1 Mbaud, which is what
      // their Makefiles have always used.
      .getOrElse(if (config.isInstanceOf[JopConfig]) 2000000 else 1000000)

    val family = board.fpga.map(_.family.toString).getOrElse("unknown")
    val tool = if (board.loaderCable.isDefined || board.loaderBoard.isDefined)
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
    board.probeAlias.foreach(a => sb.append(s"PROBE_ALIAS=$a\n"))
    board.consoleAlias.foreach(a => sb.append(s"CONSOLE_ALIAS=$a\n"))
    board.loaderCable.foreach(c => sb.append(s"LOADER_CABLE=$c\n"))
    board.loaderBoard.foreach(b => sb.append(s"LOADER_BOARD=$b\n"))
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
  val text = HwVerifyDescriptor.generate(design, cfgKey, jopArgs)
  outPath match {
    case Some(p) =>
      new java.io.File(p).getParentFile.mkdirs()
      val w = new java.io.PrintWriter(p); w.print(text); w.close()
      println(s"Wrote $p")
    case None => print(text)
  }
}
