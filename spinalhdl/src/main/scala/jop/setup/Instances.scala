package jop.setup

import jop.config.{SystemAssembly, UnitAccess}

/**
 * THIS LAB'S UNITS -- the setup layer (status items 173 and 174).
 *
 * Board designs, assemblies and presets are hardware support anyone with the
 * same boards can use. What follows is one lab's physical copies of them, so
 * nothing in the generic or hardware layers may depend on it; only entry points
 * read it (`HwVerifyDescriptorMain`).
 *
 * Boards not listed here still carry their unit facts on their design
 * (`probeAlias` and friends in Board.scala) until item 173 moves them.
 */
object Instances {

  /** The Colorlight i9+ on the first Atari-800 base board: every connector fitted.
    * Its HDMI is fitted but BROKEN; the base board design does not model its HDMI
    * connector yet, so it cannot be named in `broken`. */
  val jopI9Plus_1 = Instance(
    name = "jop-i9plus-1",
    assembly = SystemAssembly.colorlightDbI9Plus,
    mac = MacAddress.jop(1),
    access = UnitAccess(
      probeAlias = Some("colorlight-db"),
      consoleAlias = Some("colorlight-db-0"),
      loaderCable = Some("dirtyJtag")),
    presets = Seq("colorlightDbI9PlusSdram", "colorlightDbI9PlusEth"))

  /** The Colorlight i5 on the second Atari-800 base board (assembled
    * 2026-10-26). Only the RP2040-STAMP, the reset button and the Ethernet jack
    * are fitted (user, 2026-10-27): the LED pins it drives have no parts behind
    * them. Its stamp is its own, so it has its own probe and console. */
  val jopI5_1 = Instance(
    name = "jop-i5-1",
    assembly = SystemAssembly.colorlightDbI5,
    mac = MacAddress.jop(2),
    access = UnitAccess(
      probeAlias = Some("colorlight-db-i5"),
      consoleAlias = Some("colorlight-db-i5-0"),
      loaderCable = Some("dirtyJtag")),
    presets = Seq("colorlightDbI5Sdram", "colorlightDbI5Eth"),
    notFitted = Set("LED"))

  val all: Seq[Instance] = Seq(jopI9Plus_1, jopI5_1)

  /** The unit a design on this assembly is built for: the one NAMED (status
    * item 174, step 4: `--instance`, `JOP_INSTANCE=`), which must exist, sit on
    * this assembly and build this preset; or, unnamed, the assembly's only
    * unit. Several units of one assembly cannot be told apart without a name,
    * so that is an error listing them. None when no instance covers the
    * assembly -- its board then still carries its own unit facts (item 173). */
  def select(name: Option[String], assembly: SystemAssembly, preset: String,
             units: Seq[Instance] = all): Option[Instance] = name match {
    case Some(n) =>
      val unit = units.find(_.name == n).getOrElse(throw new IllegalArgumentException(
        s"no board instance named $n (there are: ${units.map(_.name).mkString(", ")})"))
      require(unit.assembly.name == assembly.name,
        s"instance $n is a ${unit.assembly.name}, but preset $preset builds a ${assembly.name}")
      require(unit.presets.contains(preset),
        s"instance $n does not build preset $preset (it builds ${unit.presets.mkString(", ")})")
      Some(unit)
    case None =>
      val on = units.filter(_.assembly.name == assembly.name)
      require(on.size <= 1,
        s"${on.size} units of ${assembly.name} -- ${on.map(_.name).mkString(", ")}: " +
          "name one (--instance, or JOP_INSTANCE= for the Java build)")
      on.headOption
  }

  /** The assembly's only unit, or None; `select` without a name. */
  def forAssembly(assembly: SystemAssembly): Option[Instance] = select(None, assembly, "")
}
