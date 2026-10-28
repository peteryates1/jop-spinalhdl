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
 *   - `assembly`: which module on which base board.
 *   - `mac`: its own, from the JOP block (`MacAddress.jop`).
 *   - `access`: how it is reached -- the probe and console aliases, which
 *     `jtag_probe_map` and `usb_serial_map` resolve to serials, and the loader.
 *   - `presets`: the presets built for it.
 *   - `notFitted` and `broken`: board device parts this unit lacks, or has but
 *     cannot use. Everything else its boards declare is fitted. A preset may put
 *     a device only on a part that is usable here.
 */
case class Instance(
  name: String,
  assembly: SystemAssembly,
  mac: MacAddress,
  access: UnitAccess,
  presets: Seq[String],
  notFitted: Set[String] = Set.empty,
  broken: Set[String] = Set.empty
) {
  private val parts: Set[String] = assembly.boards.flatMap(_.devices.map(_.part)).toSet
  require((notFitted ++ broken).subsetOf(parts),
    s"instance $name names parts its assembly ${assembly.name} does not have: " +
      s"${((notFitted ++ broken) -- parts).toSeq.sorted.mkString(", ")}")
  require((notFitted & broken).isEmpty,
    s"instance $name lists ${(notFitted & broken).mkString(", ")} as both not fitted and broken")

  /** Fitted, and working. */
  def usable(part: String): Boolean = !notFitted(part) && !broken(part)

  /** Its MAC, and its name as its hostname: what its image's Const.java carries. */
  def netIdentity: NetIdentity = NetIdentity(mac.octets, name)
}
