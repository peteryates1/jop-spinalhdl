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
 * Every unit's facts are here (item 173 moved the last of them off the board
 * designs on 2026-10-31). A design with no unit listed -- the MAX1000, the
 * generic EP4CE6 -- has no probe or console, so hw_verify refuses it, and its
 * images keep the shared MAC 02:00:00:00:00:01 with no hostname.
 *
 * Names are jop-<module>-<n>, unique in the lab, and each is also the unit's
 * DHCP hostname; MACs are numbered in the order units were listed.
 */
object Instances {

  /** The Colorlight i9+ on the first Atari-800 base board: every connector fitted.
    * Its HDMI is fitted but BROKEN; the base board design does not model its HDMI
    * connector yet, so it cannot be named in `broken`. */
  val jopI9Plus_1 = Instance(
    name = "jop-i9plus-1",
    assemblies = Seq(SystemAssembly.colorlightDbI9Plus),
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
    assemblies = Seq(SystemAssembly.colorlightDbI5),
    mac = MacAddress.jop(2),
    access = UnitAccess(
      probeAlias = Some("colorlight-db-i5"),
      consoleAlias = Some("colorlight-db-i5-0"),
      loaderCable = Some("dirtyJtag")),
    presets = Seq("colorlightDbI5Sdram", "colorlightDbI5Eth"),
    notFitted = Set("LED"))

  /** The QMTECH EP4CGX150 core board, built with either QMTECH daughter board.
    * Probe: the Terasic USB-Blaster since 2026-10-27 (Quartus); console: its
    * CP2102N. */
  val jopEp4cgx150_1 = Instance(
    name = "jop-ep4cgx150-1",
    assemblies = Seq(SystemAssembly.qmtechWithDb, SystemAssembly.qmtechWithDbV5),
    mac = MacAddress.jop(3),
    access = UnitAccess(
      probeAlias = Some("ep4cgx150"),
      consoleAlias = Some("ep4cgx150")))

  /** The QMTECH XC7A100T core board on DB_FPGA V5, whose RP2040 is both the
    * probe (dirtyJtag) and the console (its first CDC). */
  val jopXc7a100t_1 = Instance(
    name = "jop-xc7a100t-1",
    assemblies = Seq(SystemAssembly.xc7a100tWithDbV5),
    mac = MacAddress.jop(4),
    access = UnitAccess(
      probeAlias = Some("xc7a100t"),
      consoleAlias = Some("xc7a100t-pico-0"),
      loaderCable = Some("dirtyJtag")))

  /** The QMTECH Wukong, alone or with its J11 UART adapter (the dual-system
    * builds). Probe: its Pico 2 W running dirtyJtag; console: its CH340N. */
  val jopWukong_1 = Instance(
    name = "jop-wukong-1",
    assemblies = Seq(SystemAssembly.wukong, SystemAssembly.wukongWithJ11Uart),
    mac = MacAddress.jop(5),
    access = UnitAccess(
      probeAlias = Some("wukong"),
      consoleAlias = Some("wukong"),
      loaderCable = Some("dirtyJtag")))

  /** The A-E115FB. Probe: the pico-usb-debug-jtag carrier's JTAG0 since
    * 2026-10-27 (Quartus); console: its CH340. */
  val jopAe115fb_1 = Instance(
    name = "jop-ae115fb-1",
    assemblies = Seq(SystemAssembly.ae115fb),
    mac = MacAddress.jop(6),
    access = UnitAccess(
      probeAlias = Some("ae115fb"),
      consoleAlias = Some("ae115fb")))

  /** The Alchitry Au V2. Its probe was added 2026-09-11: without one hw_verify
    * refused the board outright, so it was the one attached board that could
    * not be hardware-verified at all. openFPGALoader (`-b alchitry_au`), not
    * the Vivado hw_server path its Makefile uses: it shares vid:pid 0403:6010
    * with the CYC5000's Arrow blaster, so its probe must be selected by bus:dev
    * like every other one here. */
  val jopAu_1 = Instance(
    name = "jop-au-1",
    assemblies = Seq(SystemAssembly.alchitryAuV2),
    mac = MacAddress.jop(7),
    access = UnitAccess(
      probeAlias = Some("alchitry"),
      consoleAlias = Some("alchitry"),
      loaderBoard = Some("alchitry_au")))

  /** The Arrow CYC5000. openFPGALoader, not quartus_pgm: Quartus never
    * enumerates its Arrow blaster on 18.1 or 25.1 ("Error (213013): Programming
    * hardware cable not detected"). */
  val jopCyc5000_1 = Instance(
    name = "jop-cyc5000-1",
    assemblies = Seq(SystemAssembly.cyc5000),
    mac = MacAddress.jop(8),
    access = UnitAccess(
      probeAlias = Some("cyc5000"),
      consoleAlias = Some("cyc5000"),
      loaderBoard = Some("cyc5000")))

  /** A Colorlight i5 on Colorlight's own i5/i9 ext board, whose DAPLink is
    * both probe and console. */
  val jopI5ext_1 = Instance(
    name = "jop-i5ext-1",
    assemblies = Seq(SystemAssembly.colorlightI5),
    mac = MacAddress.jop(9),
    access = UnitAccess(
      probeAlias = Some("i5"),
      consoleAlias = Some("i5"),
      loaderBoard = Some("colorlight-i5")))

  val all: Seq[Instance] = Seq(jopI9Plus_1, jopI5_1, jopEp4cgx150_1, jopXc7a100t_1,
    jopWukong_1, jopAe115fb_1, jopAu_1, jopCyc5000_1, jopI5ext_1)

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
