package jop.setup

import jop.config.{NetIdentity, SystemAssembly, UnitAccess}

/** A MAC address: six octets, written 02:4a:4f:50:00:01. */
case class MacAddress(octets: Seq[Int]) {
  require(octets.size == 6 && octets.forall(o => o >= 0 && o <= 0xff), s"not a MAC address: $octets")
  def isUnicast: Boolean = (octets.head & 0x01) == 0
  def isLocallyAdministered: Boolean = (octets.head & 0x02) != 0
  override def toString: String = octets.map(o => f"$o%02x").mkString(":")
}

object MacAddress {
  def apply(text: String): MacAddress = MacAddress(text.split(":").toSeq.map(Integer.parseInt(_, 16)))

  /** The JOP block: 02:4a:4f:50 ("JOP", locally administered unicast), then a
    * unit number. Assigned explicitly per instance, so it does not depend on the
    * FPGA: the ECP5 has no ID its fabric can read, and the Cyclone IV none at all. */
  def jop(unit: Int): MacAddress = {
    require(unit >= 1 && unit <= 0xffff, s"unit number $unit is outside 1..65535")
    MacAddress(Seq(0x02, 0x4a, 0x4f, 0x50, unit >> 8, unit & 0xff))
  }
}

/**
 * One physical unit: an assembly DESIGN as built and wired in this lab.
 *
 * The design says what the PCBs are; this says what this copy of them has --
 * status item 174, docs/architecture/board-instances.md. The fields:
 *
 *   - `name`: unique and stable, and also the unit's DHCP hostname.
 *   - `assemblies`: which module on which base board. Usually one; several
 *     where this lab builds one unit as more than one assembly -- a core board
 *     with either daughter board, or the Wukong with or without its J11
 *     adapter. Its access and MAC are the same in each.
 *   - `macs`: its own, from the JOP block (`MacAddress.jop`), one per
 *     Ethernet port in use, port 0 first; empty for a unit without a network
 *     connection, which gets no hostname either and whose images keep the
 *     shared fallback. Images carry port 0's: JOP drives one port today, and
 *     the Colorlight modules' second PHY may come later (user, 2026-10-31).
 *   - `access`: how it is reached -- the probe and console aliases, which
 *     `jtag_probe_map` and `usb_serial_map` resolve to serials, and the loader.
 *   - `presets`: the presets built for it, or empty for every preset on its
 *     assemblies.
 *   - `notFitted` and `broken`: board device parts this unit lacks, or has but
 *     cannot use. Everything else its boards declare is fitted. A preset may put
 *     a device only on a part that is usable here.
 */
case class Instance(
  name: String,
  assemblies: Seq[SystemAssembly],
  macs: Seq[MacAddress] = Seq.empty,
  access: UnitAccess,
  presets: Seq[String] = Seq.empty,
  notFitted: Set[String] = Set.empty,
  broken: Set[String] = Set.empty
) {
  require(assemblies.nonEmpty, s"instance $name has no assembly")
  require(macs.distinct.size == macs.size, s"instance $name repeats a MAC: ${macs.mkString(", ")}")
  private val parts: Set[String] =
    assemblies.flatMap(_.boards.flatMap(_.devices.map(_.part))).toSet
  require((notFitted ++ broken).subsetOf(parts),
    s"instance $name names parts its assemblies ${assemblies.map(_.name).mkString(", ")} do not have: " +
      s"${((notFitted ++ broken) -- parts).toSeq.sorted.mkString(", ")}")
  require((notFitted & broken).isEmpty,
    s"instance $name lists ${(notFitted & broken).mkString(", ")} as both not fitted and broken")

  /** Whether this unit is ever built as `a`. */
  def isA(a: SystemAssembly): Boolean = assemblies.exists(_.name == a.name)

  /** Fitted, and working. */
  def usable(part: String): Boolean = !notFitted(part) && !broken(part)

  /** Its MAC, and its name as its hostname: what its image's Const.java
    * carries. None for a unit off the LAN. */
  def netIdentity: Option[NetIdentity] = macs.headOption.map(m => NetIdentity(m.octets, name))
}
