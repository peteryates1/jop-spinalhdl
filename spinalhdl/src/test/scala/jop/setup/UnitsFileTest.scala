package jop.setup

import org.scalatest.funsuite.AnyFunSuite
import jop.config.{SystemAssembly, UnitAccess}

/**
 * READING THE UNITS -- status item 173, step 2.
 *
 * This lab's units are setup/units.toml, data the generators read, rather than
 * Scala they compile (they were `jop.setup.Instances` until 2026-11-01). What a
 * reader can get wrong there is silent: a misspelt key just drops the fact --
 * `loader_cable` would make the unit a quartus unit, the CYC5000 class (items
 * 156/157) -- so the reader refuses unknown keys, wrong types, and assemblies
 * and parts the designs do not have. fpga/scripts/jop_setup.py reads the same
 * file for the bash tools; the last test holds the two readers to one answer.
 */
class UnitsFileTest extends AnyFunSuite {

  private val two =
    """|[jop-y-1]
       |assemblies = ["colorlight-db-i5"]
       |macs = ["02:12:34:56:78:01"]
       |probe = "p-y"
       |console = "c-y"
       |loader-cable = "dirtyJtag"
       |presets = ["colorlightDbI5Eth"]
       |not-fitted = ["LED"]
       |
       |[jop-x-1]
       |assemblies = ["qmtech-ep4cgx150-db-v4", "qmtech-ep4cgx150-db-v5"]
       |probe = "p-x"
       |console = "c-x"
       |""".stripMargin

  private def parse(text: String) = UnitsFile.parse(text, "test.toml")

  private def refused(text: String, words: String*): Unit = {
    val e = intercept[IllegalArgumentException](parse(text))
    for (w <- words) assert(e.getMessage.contains(w), s"refusal does not mention '$w': ${e.getMessage}")
  }

  test("units keep the file's order and every field") {
    val u = parse(two)
    assert(u.map(_.name) == Seq("jop-y-1", "jop-x-1"))
    val y = u.head
    assert(y.assemblies.map(_.name) == Seq("colorlight-db-i5"))
    assert(y.macs == Seq(MacAddress("02:12:34:56:78:01")))
    assert(y.access == UnitAccess(Some("p-y"), Some("c-y"), Some("dirtyJtag"), None))
    assert(y.presets == Seq("colorlightDbI5Eth") && y.notFitted == Set("LED") && y.broken.isEmpty)
    val x = u(1)
    assert(x.assemblies.map(_.name) == Seq("qmtech-ep4cgx150-db-v4", "qmtech-ep4cgx150-db-v5"))
    assert(x.macs.isEmpty && x.presets.isEmpty && x.access.loaderCable.isEmpty)
  }

  test("a misspelt key is refused: a unit naming no loader silently becomes a quartus unit") {
    refused(two.replace("loader-cable", "loader_cable"), "jop-y-1", "loader_cable")
  }

  test("an assembly the designs do not have is refused") {
    refused(two.replace("\"colorlight-db-i5\"", "\"colorlight-db-i6\""), "jop-y-1", "colorlight-db-i6")
  }

  test("a part the unit's assemblies do not have is refused") {
    refused(two.replace("[\"LED\"]", "[\"LEDS\"]"), "jop-y-1", "LEDS")
  }

  test("a malformed MAC is refused") {
    refused(two.replace("02:12:34:56:78:01", "02:12:34:56:78"), "jop-y-1", "02:12:34:56:78")
  }

  test("a list written as a string is refused") {
    refused(two.replace("presets = [\"colorlightDbI5Eth\"]", "presets = \"colorlightDbI5Eth\""), "jop-y-1", "presets")
  }

  test("a unit with no assembly is refused") {
    refused(two.replace("assemblies = [\"colorlight-db-i5\"]\n", ""), "jop-y-1", "assemblies")
  }

  test("two units sharing a MAC are refused") {
    refused(two.replace("probe = \"p-x\"", "macs = [\"02:12:34:56:78:01\"]\nprobe = \"p-x\""),
      "02:12:34:56:78:01", "jop-y-1", "jop-x-1")
  }

  test("a TOML syntax error names the file") {
    refused("[jop-x-1\nassemblies = [", "test.toml")
  }

  test("the setup directory: JOP_SETUP if given, which must exist; else ./setup, which may not") {
    assert(SetupDir.resolve(None, "no-such-setup-dir").isEmpty)
    intercept[IllegalArgumentException](SetupDir.resolve(Some("/no/such/setup"), "setup"))
    assert(SetupDir.resolve(None, "setup").exists(_.getName == "setup"))
  }

  test("every assembly name is unique, so a unit's assembly is unambiguous") {
    val names = SystemAssembly.all.map(_.name)
    assert(names.distinct.size == names.size, names.diff(names.distinct))
    for (n <- Seq("colorlight-db-i5", "qmtech-ep4cgx150-db-v4", "generic-ep4ce6"))
      assert(names.contains(n), s"$n is not among $names")
  }

  test("Scala and jop_setup.py read this lab's units alike") {
    import scala.sys.process._
    val rows = Seq("python3", "fpga/scripts/jop_setup.py", "table", "units").!!.linesIterator.toSeq
    assert(rows.nonEmpty && rows.size == Instances.all.size, s"${rows.size} rows, ${Instances.all.size} units")
    for ((row, i) <- rows.zip(Instances.all)) {
      val f = row.split("\\|", -1)
      def list(s: String) = s.split(",").toSeq.filter(_.nonEmpty)
      def opt(s: String) = Option(s).filter(_.nonEmpty)
      assert(f(0) == i.name)
      assert(list(f(1)) == i.assemblies.map(_.name), i.name)
      assert(list(f(2)) == i.macs.map(_.toString), i.name)
      assert(i.access == UnitAccess(opt(f(3)), opt(f(4)), opt(f(5)), opt(f(6))), i.name)
      assert(list(f(7)) == i.presets, i.name)
      assert(list(f(8)).toSet == i.notFitted && list(f(9)).toSet == i.broken, i.name)
    }
  }
}
