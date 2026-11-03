package jop.setup

import java.io.File
import hw.SystemAssembly

/**
 * THIS LAB'S UNITS -- the setup layer (status items 173 and 174).
 *
 * Board designs, assemblies and presets are hardware support anyone with the
 * same boards can use; the units are one lab's physical copies of them, so
 * nothing in the generic or hardware layers may depend on them, and only
 * entry points read them (`HwVerifyDescriptorMain`, `ConstGeneratorMain`).
 *
 * The units are DATA, not code, since 2026-11-01: setup/units.toml (or
 * $JOP_SETUP/units.toml), read by `UnitsFile`. Until then they were defined
 * here. A lab with no setup has no units: every design is then unit-less, so
 * hw_verify refuses it and its images keep the shared MAC 02:00:00:00:00:01.
 */
object Instances {

  /** The units, in the file's order; empty without a setup. */
  lazy val all: Seq[Instance] =
    SetupDir.current.map(new File(_, "units.toml")).filter(_.isFile).map(UnitsFile.load).getOrElse(Seq.empty)

  /** The unit with this name. */
  def named(name: String): Instance = all.find(_.name == name).getOrElse(throw new IllegalArgumentException(
    s"no board instance named $name (there are: ${all.map(_.name).mkString(", ")})"))

  /** The unit a design on this assembly is built for: the one NAMED (status
    * item 174, step 4: `--instance`, `JOP_INSTANCE=`), which must exist, be
    * built as this assembly and build this preset; or, unnamed, the assembly's
    * only unit. Several units of one assembly cannot be told apart without a
    * name, so that is an error listing them. None when no unit is listed for
    * the assembly. */
  def select(name: Option[String], assembly: SystemAssembly, preset: String,
             units: Seq[Instance] = all): Option[Instance] = name match {
    case Some(n) =>
      val unit = units.find(_.name == n).getOrElse(throw new IllegalArgumentException(
        s"no board instance named $n (there are: ${units.map(_.name).mkString(", ")})"))
      require(unit.isA(assembly),
        s"instance $n is a ${unit.assemblies.map(_.name).mkString(" or ")}, but preset $preset builds a ${assembly.name}")
      require(unit.presets.isEmpty || unit.presets.contains(preset),
        s"instance $n does not build preset $preset (it builds ${unit.presets.mkString(", ")})")
      Some(unit)
    case None =>
      val on = units.filter(_.isA(assembly))
      require(on.size <= 1,
        s"${on.size} units of ${assembly.name} -- ${on.map(_.name).mkString(", ")}: " +
          "name one (--instance, or JOP_INSTANCE= for the Java build)")
      on.headOption
  }

  /** The assembly's only unit, or None; `select` without a name. */
  def forAssembly(assembly: SystemAssembly): Option[Instance] = select(None, assembly, "")
}
