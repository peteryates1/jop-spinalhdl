package jop.setup

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters._
import org.tomlj.Toml
import jop.config.{SystemAssembly, UnitAccess}

/** Where this lab's setup is (status item 173): $JOP_SETUP when set, which
  * must exist; otherwise `setup` under the working directory -- the
  * repository root, as for every build path (BuildLayout) -- which may be
  * absent: a lab with no setup has no units, and its images keep the shared
  * MAC. fpga/scripts/jop_setup.py looks in the same places. */
object SetupDir {
  def resolve(env: Option[String], default: String = "setup"): Option[File] =
    env.filter(_.nonEmpty) match {
      case Some(d) =>
        val f = new File(d)
        require(f.isDirectory, s"JOP_SETUP=$d is not a directory")
        Some(f)
      case None => Some(new File(default)).filter(_.isDirectory)
    }

  def current: Option[File] = resolve(sys.env.get("JOP_SETUP"))
}

/**
 * Reads setup/units.toml into board instances (status item 173, step 2).
 *
 * The file's keys are listed in its own header. The reader REFUSES what would
 * otherwise vanish without a sound -- an unknown key (a misspelt
 * `loader-cable` makes the unit a quartus unit), a value of the wrong type, an
 * assembly no design has, a part its assemblies lack, a MAC two units share --
 * and keeps the file's order. fpga/scripts/jop_setup.py applies the same
 * rules to the same file for the bash tools; UnitsFileTest holds the two to
 * one answer.
 */
object UnitsFile {
  private val stringKeys = Seq("probe", "console", "loader-cable", "loader-board")
  private val listKeys = Seq("assemblies", "macs", "presets", "not-fitted", "broken")
  private val keys = listKeys ++ stringKeys
  private val macText = "[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"

  def load(file: File): Seq[Instance] =
    parse(new String(Files.readAllBytes(file.toPath), "UTF-8"), file.getPath)

  def parse(text: String, origin: String): Seq[Instance] = {
    val toml = Toml.parse(text)
    require(!toml.hasErrors, s"$origin: ${toml.errors.asScala.map(_.toString).mkString("; ")}")
    def key(k: String) = java.util.List.of(k)
    val names = toml.keySet.asScala.toSeq.sortBy(n => toml.inputPositionOf(key(n)).line)

    val units = names.map { name =>
      val where = s"$origin: [$name]"
      require(toml.isTable(key(name)), s"$where is not a table")
      val t = toml.getTable(key(name))
      val unknown = t.keySet.asScala.toSeq.filterNot(keys.contains).sorted
      require(unknown.isEmpty,
        s"$where has unknown key(s) ${unknown.mkString(", ")} (known: ${keys.mkString(", ")})")

      def str(k: String): Option[String] =
        if (!t.contains(key(k))) None
        else { require(t.isString(key(k)), s"$where $k must be a string"); Some(t.getString(key(k))) }
      def list(k: String): Seq[String] =
        if (!t.contains(key(k))) Seq.empty
        else {
          require(t.isArray(key(k)), s"$where $k must be a list of strings")
          // TOML 1.0 arrays may mix types, so each element is checked
          val items = t.getArray(key(k)).toList.asScala.toSeq
          require(items.forall(_.isInstanceOf[String]), s"$where $k must be a list of strings")
          items.map(_.asInstanceOf[String])
        }

      val assemblyNames = list("assemblies")
      require(assemblyNames.nonEmpty, s"$where has no assemblies")
      val assemblies = assemblyNames.map(n => SystemAssembly.named(n).getOrElse(
        throw new IllegalArgumentException(s"$where assembly '$n' is not a design " +
          s"(designs: ${SystemAssembly.all.map(_.name).mkString(", ")})")))
      val macs = list("macs").map { m =>
        require(m.matches(macText), s"$where '$m' is not a MAC address")
        MacAddress(m)
      }
      try Instance(name, assemblies, macs,
        UnitAccess(str("probe"), str("console"), str("loader-cable"), str("loader-board")),
        list("presets"), list("not-fitted").toSet, list("broken").toSet)
      catch { case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"$origin: ${e.getMessage}", e) }
    }

    for ((mac, owners) <- units.flatMap(u => u.macs.map(_ -> u.name)).groupBy(_._1) if owners.size > 1)
      throw new IllegalArgumentException(s"$origin: $mac is ${owners.map(_._2).mkString(" and ")}'s")
    units
  }
}
