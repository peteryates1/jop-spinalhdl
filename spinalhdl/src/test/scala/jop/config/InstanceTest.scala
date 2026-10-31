package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.generate.{ConstGeneratorMain, HwVerifyDescriptorMain}
import jop.setup.{Instance, Instances, MacAddress}
import jop.system.JopTopVerilog

/**
 * BOARD INSTANCES -- status item 174, step 1 of docs/architecture/board-instances.md.
 *
 * A board DESIGN says what a PCB is. An INSTANCE says what one physical unit of
 * it is in this lab: its MAC and hostname, which parts are fitted, and how it is
 * reached (probe, console, loader). Unit facts used to sit on the designs, so the
 * second Atari-800 base board was a copied board, `ColorlightAtari800BaseV1Unit2`,
 * whose fitted parts lived in a comment. Instances are the first piece of the
 * setup layer (item 173), outside the board designs.
 */
class InstanceTest extends AnyFunSuite {

  test("the Atari-800 base board's two units are instances") {
    val names = Instances.all.map(_.name).toSet
    assert(Set("jop-i9plus-1", "jop-i5-1").subsetOf(names), names)
    assert(Instances.forAssembly(SystemAssembly.colorlightDbI9Plus).map(_.name).contains("jop-i9plus-1"))
    assert(Instances.forAssembly(SystemAssembly.colorlightDbI5).map(_.name).contains("jop-i5-1"))
  }

  test("every instance has its own unicast, locally administered MAC in the JOP block") {
    val macs = Instances.all.map(_.mac)
    assert(macs.distinct.size == macs.size, s"shared MACs: ${macs.diff(macs.distinct)}")
    for (i <- Instances.all) {
      assert(i.mac.isUnicast && i.mac.isLocallyAdministered, s"${i.name}: ${i.mac}")
      assert(i.mac.toString.startsWith("02:4a:4f:50:"), s"${i.name}: ${i.mac} is outside the JOP block")
    }
  }

  test("every instance name is unique and a valid hostname") {
    val label = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$".r
    val names = Instances.all.map(_.name)
    assert(names.distinct.size == names.size, names)
    for (n <- names) assert(label.findFirstIn(n).isDefined, s"$n is not an RFC 1123 host label")
  }

  test("a board design carries no unit facts once an instance covers it") {
    for (i <- Instances.all; b <- i.assembly.boards) {
      val facts = Seq(b.probeAlias, b.consoleAlias, b.loaderCable, b.loaderBoard).flatten
      assert(facts.isEmpty, s"${b.name} is a design shared by every unit of it, but still says $facts " +
        s"-- those are ${i.name}'s, and belong to the instance")
    }
  }

  test("every preset an instance builds is on its assembly and uses only parts fitted there") {
    for (i <- Instances.all; p <- i.presets) {
      val cfg = JopTopVerilog.resolvePreset(p, Array(p))
      assert(cfg.assembly.name == i.assembly.name, s"${i.name}: preset $p is on ${cfg.assembly.name}")
      for ((dev, d) <- cfg.system.devices; part <- d.devicePart)
        assert(i.usable(part), s"${i.name}: preset $p puts $dev on $part, which is " +
          (if (i.broken(part)) "broken" else "not fitted") + " on this unit")
    }
  }

  test("an instance can name only parts its assembly has, and a MAC must be one") {
    val i = Instances.forAssembly(SystemAssembly.colorlightDbI5).get
    intercept[IllegalArgumentException](i.copy(notFitted = Set("NO_SUCH_PART")))
    intercept[IllegalArgumentException](MacAddress("02:4a:4f:50:00"))
    assert(MacAddress.jop(2).toString == "02:4a:4f:50:00:02")
  }

  // The descriptor hw_verify reads is what reaches a unit: the same aliases as
  // before the move, now from the instance -- through the real entry point.
  test("hw_verify's descriptor reaches each unit through its instance") {
    val expected = Seq(
      "colorlightDbI5Eth"     -> ("colorlight-db-i5", "colorlight-db-i5-0"),
      "colorlightDbI9PlusEth" -> ("colorlight-db", "colorlight-db-0"))
    for ((preset, (probe, console)) <- expected) {
      val out = new java.io.ByteArrayOutputStream
      Console.withOut(out)(HwVerifyDescriptorMain.main(Array(preset)))
      val d = out.toString
      for (line <- Seq(s"PROBE_ALIAS=$probe", s"CONSOLE_ALIAS=$console",
                       "LOADER_CABLE=dirtyJtag", "PROGRAM_TOOL=openfpgaloader"))
        assert(d.linesIterator.contains(line), s"$preset: no $line in\n$d")
    }
  }

  // STEP 2: each unit's image carries its own network identity. Const.java is
  // what the Java build compiles against, and javac inlines its constants, so
  // this is where a unit's MAC and hostname must land -- through the real
  // entry point, which is what `make -C java` runs.
  private def constFor(preset: String): String = {
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(ConstGeneratorMain.main(Array(preset)))
    out.toString
  }

  test("each unit's Const.java carries its own MAC and hostname") {
    val expected = Seq(
      "colorlightDbI5Eth"     -> ("0x024a", "0x4f500002", "jop-i5-1"),
      "colorlightDbI9PlusEth" -> ("0x024a", "0x4f500001", "jop-i9plus-1"))
    for ((preset, (hi, lo, host)) <- expected) {
      val c = constFor(preset)
      for (line <- Seq(s"public static final int ETH_MAC_HI = $hi;",
                       s"public static final int ETH_MAC_LO = $lo;",
                       s"""public static final String ETH_HOSTNAME = "$host";"""))
        assert(c.contains(line), s"$preset: no `$line` in Const.java")
    }
  }

  test("a board no instance covers keeps the shared MAC and sends no hostname") {
    // Until item 173 moves the other boards' unit facts, nothing changes there.
    val c = constFor("wukongFull")
    assert(c.contains("public static final int ETH_MAC_HI = 0x0200;") &&
           c.contains("public static final int ETH_MAC_LO = 0x00000001;"), "wukongFull's MAC moved")
    assert(c.contains("""public static final String ETH_HOSTNAME = "";"""), "wukongFull names a host")
  }
}
