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

  test("every preset an instance builds is on its assemblies and uses only parts fitted there") {
    for (i <- Instances.all; p <- i.presets) {
      val cfg = JopTopVerilog.resolvePreset(p, Array(p))
      assert(i.isA(cfg.assembly), s"${i.name}: preset $p is on ${cfg.assembly.name}")
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

  // ITEM 173: EVERY unit's facts move off the board designs. Before the move,
  // these presets reached their units through the designs' `probeAlias` and
  // friends; after it, through instances. This table is what the descriptor
  // said BEFORE (dumped 2026-10-31 at 5fecd67), so the move must keep it green:
  // a unit that lost its probe, console or loader would show here, not on the
  // bench. The loader decides the tool -- openFPGALoader with one, else Quartus.
  // Not here: wukongDualIndependent and wukongDualSmp, two-system presets the
  // descriptor cannot describe at all (its CORES= line asks for one system).
  private case class Reach(probe: String, console: String, loader: Option[String], host: String)
  private object Reach { val none = Reach("", "", None, "") }
  private val reach: Seq[(String, Reach)] = Seq(
    "ep4cgx150Bram" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150BramGc" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150BramSerial" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150BramSmp" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150DbFull" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150DbVgaDma" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150HwFloat" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150HwMath" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150McFallback" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150NoCache" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150Serial" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150Smp" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "minimum" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150DbV5Eth" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "ep4cgx150DbV5Mii" -> Reach("ep4cgx150", "ep4cgx150", None, "jop-ep4cgx150-1"),
    "xc7a100tDbEth" -> Reach("xc7a100t", "xc7a100t-pico-0", Some("dirtyJtag"), "jop-xc7a100t-1"),
    "xc7a100tDbFull" -> Reach("xc7a100t", "xc7a100t-pico-0", Some("dirtyJtag"), "jop-xc7a100t-1"),
    "xc7a100tDbMii" -> Reach("xc7a100t", "xc7a100t-pico-0", Some("dirtyJtag"), "jop-xc7a100t-1"),
    "xc7a100tDbSerial" -> Reach("xc7a100t", "xc7a100t-pico-0", Some("dirtyJtag"), "jop-xc7a100t-1"),
    "xc7a100tDbSmp" -> Reach("xc7a100t", "xc7a100t-pico-0", Some("dirtyJtag"), "jop-xc7a100t-1"),
    "wukongAuMatch" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongBram" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3AllCu" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3DspMul" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3Fcu" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3Lcu" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3Smp" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongDdr3SmpMshr" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongFull" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongFullSmp" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongNoDcu" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSdrAllCu" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSdrFull" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSdrSmp" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSdrSmpSim" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSdram" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSmp" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "wukongSmpMinimal" -> Reach("wukong", "wukong", Some("dirtyJtag"), "jop-wukong-1"),
    "ae115fbDdr2" -> Reach("ae115fb", "ae115fb", None, "jop-ae115fb-1"),
    "ae115fbDdr2Eth" -> Reach("ae115fb", "ae115fb", None, "jop-ae115fb-1"),
    "ae115fbDdr2Smp" -> Reach("ae115fb", "ae115fb", None, "jop-ae115fb-1"),
    "ae115fbDdr2SmpMshr" -> Reach("ae115fb", "ae115fb", None, "jop-ae115fb-1"),
    "auSerial" -> Reach("alchitry", "alchitry", Some("alchitry_au"), "jop-au-1"),
    "cyc5000Serial" -> Reach("cyc5000", "cyc5000", Some("cyc5000"), "jop-cyc5000-1"),
    "cyc5000Smp" -> Reach("cyc5000", "cyc5000", Some("cyc5000"), "jop-cyc5000-1"),
    "colorlightI5Bram" -> Reach("i5", "i5", Some("colorlight-i5"), "jop-i5ext-1"),
    "colorlightI5Sdram" -> Reach("i5", "i5", Some("colorlight-i5"), "jop-i5ext-1"),
    "colorlightDbI5Eth" -> Reach("colorlight-db-i5", "colorlight-db-i5-0", Some("dirtyJtag"), "jop-i5-1"),
    "colorlightDbI5Sdram" -> Reach("colorlight-db-i5", "colorlight-db-i5-0", Some("dirtyJtag"), "jop-i5-1"),
    "colorlightDbI9PlusEth" -> Reach("colorlight-db", "colorlight-db-0", Some("dirtyJtag"), "jop-i9plus-1"),
    "colorlightDbI9PlusSdram" -> Reach("colorlight-db", "colorlight-db-0", Some("dirtyJtag"), "jop-i9plus-1"),
    "max1000Sdram" -> Reach.none,
    "ep4ce6Sdram" -> Reach.none)

  test("every preset reaches the same unit it reached before the move") {
    for ((preset, r) <- reach) {
      val d = descriptorFor(preset)
      def want(key: String, v: String) =
        assert(d.get(key) == Option(v).filter(_.nonEmpty), s"$preset: $key=${d.get(key)}, want '$v'")
      want("PROBE_ALIAS", r.probe)
      want("CONSOLE_ALIAS", r.console)
      assert(Seq(d.get("LOADER_CABLE"), d.get("LOADER_BOARD")).flatten == r.loader.toSeq,
        s"$preset: loader ${d.get("LOADER_CABLE")} / ${d.get("LOADER_BOARD")}, want ${r.loader}")
      if (r.probe.nonEmpty)
        assert(d.get("PROGRAM_TOOL").contains(if (r.loader.isDefined) "openfpgaloader" else "quartus"), s"$preset: $d")
    }
  }

  test("no board design carries a unit fact: every unit is an instance") {
    val fields = classOf[Board].getDeclaredFields.map(_.getName).toSet
    val unitFacts = Set("probeAlias", "consoleAlias", "loaderCable", "loaderBoard")
    assert((fields & unitFacts).isEmpty, s"Board still declares ${(fields & unitFacts).toSeq.sorted}")
  }

  test("every unit's image carries its own MAC and its name as hostname") {
    val units = reach.filter(_._2.host.nonEmpty)
    for ((preset, r) <- units) {
      val c = constFor(preset)
      assert(c.contains(s"""public static final String ETH_HOSTNAME = "${r.host}";"""), s"$preset: not ${r.host}")
      assert(c.contains("public static final int ETH_MAC_HI = 0x024a;"), s"$preset: MAC outside the JOP block")
    }
    // One unit, one MAC, across all its presets; no two units share one.
    val macOf = units.map { case (p, r) =>
      r.host -> constFor(p).linesIterator.find(_.contains("ETH_MAC_LO =")).get }.distinct
    assert(macOf.map(_._1).distinct.size == macOf.size, s"a unit's presets disagree on its MAC: $macOf")
    assert(macOf.map(_._2).distinct.size == macOf.size, s"units share a MAC: $macOf")
  }

  test("a board without a unit keeps the shared MAC and sends no hostname") {
    val c = constFor("max1000Sdram")
    assert(c.contains("public static final int ETH_MAC_HI = 0x0200;") &&
           c.contains("public static final int ETH_MAC_LO = 0x00000001;"), "max1000Sdram's MAC moved")
    assert(c.contains("""public static final String ETH_HOSTNAME = "";"""), "max1000Sdram names a host")
  }

  test("one unit can be built as several assemblies: a module on either base board") {
    for (a <- Seq(SystemAssembly.qmtechWithDb, SystemAssembly.qmtechWithDbV5))
      assert(Instances.forAssembly(a).map(_.name).contains("jop-ep4cgx150-1"), a.name)
    for (a <- Seq(SystemAssembly.wukong, SystemAssembly.wukongWithJ11Uart))
      assert(Instances.forAssembly(a).map(_.name).contains("jop-wukong-1"), a.name)
  }

  // STEP 4: a unit is named on the command line. Several units of one assembly
  // (more i9+ modules are coming) need it; one unit per assembly does not.
  test("selecting a unit: by name, or the assembly's only one") {
    val i5 = Instances.forAssembly(SystemAssembly.colorlightDbI5).get
    val i5b = i5.copy(name = "jop-i5-2", mac = MacAddress.jop(99))
    val two = Seq(i5, i5b)
    val a = SystemAssembly.colorlightDbI5
    val e = intercept[IllegalArgumentException](Instances.select(None, a, "colorlightDbI5Eth", two))
    assert(e.getMessage.contains("jop-i5-1") && e.getMessage.contains("jop-i5-2"), e.getMessage)
    assert(Instances.select(Some("jop-i5-2"), a, "colorlightDbI5Eth", two).map(_.name).contains("jop-i5-2"))
    assert(Instances.select(None, a, "colorlightDbI5Eth").map(_.name).contains("jop-i5-1"))
    intercept[IllegalArgumentException](Instances.select(Some("jop-i9plus-1"), a, "colorlightDbI5Eth"))
    intercept[IllegalArgumentException](Instances.select(Some("jop-i5-1"), a, "colorlightDbI5Fancy"))
    intercept[IllegalArgumentException](Instances.select(Some("no-such-unit"), a, "colorlightDbI5Eth"))
  }

  private def descriptorFor(args: String*): Map[String, String] = {
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(HwVerifyDescriptorMain.main(args.toArray))
    out.toString.linesIterator.filter(_.contains("=")).map { l =>
      val i = l.indexOf('='); l.take(i) -> l.drop(i + 1) }.toMap
  }

  test("a named unit's descriptor carries its name, hostname and own Java tree") {
    val d = descriptorFor("colorlightDbI5Eth", "--instance", "jop-i5-1")
    assert(d.get("INSTANCE").contains("jop-i5-1") && d.get("HOSTNAME").contains("jop-i5-1"), d)
    assert(d.get("JAVA_DIR").contains("build/colorlightDbI5Eth/java/jop-i5-1"), d)
    assert(d.get("CONFIG_DIR").contains("build/colorlightDbI5Eth"), "the bitstream stays per preset")
    // Unnamed, the image stays where every flow already looks for it.
    assert(descriptorFor("colorlightDbI5Eth").get("JAVA_DIR").contains("build/colorlightDbI5Eth/java"))
    intercept[IllegalArgumentException](descriptorFor("colorlightDbI5Eth", "--instance", "jop-i9plus-1"))
  }
}
