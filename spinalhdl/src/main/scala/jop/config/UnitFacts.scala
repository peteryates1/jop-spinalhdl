package jop.config

/** How one physical unit is reached: its JTAG probe's alias in
  * `fpga/scripts/jtag_probe_map`, its console's in `usb_serial_map`, and, for
  * openFPGALoader, the cable (`-c`) or board profile (`-b`). Without either,
  * the unit is programmed by quartus_pgm, through the cable NAME jtag_probe_map
  * reports. The two alias namespaces differ on purpose: JTAG has one alias per
  * unit, serial one per CDC ENDPOINT (a Pico presents two, so the XC7A100T's
  * console is `xc7a100t-pico-0` while its probe is `xc7a100t`).
  *
  * A fact about a UNIT, not a design, so only board instances carry it
  * (`jop.setup`, status items 173 and 174); a design no unit is listed for has
  * none, and hw_verify refuses it -- guessing an alias from a board name
  * silently yields nothing, which has twice been mistaken for a broken board. */
case class UnitAccess(
  probeAlias: Option[String] = None,
  consoleAlias: Option[String] = None,
  loaderCable: Option[String] = None,
  loaderBoard: Option[String] = None)

/** A unit's network identity: its MAC as six octets, and its hostname ("" for
  * none). Another unit fact: a board instance gives it (`jop.setup`, status
  * item 174), and `ConstGenerator` writes it into the unit's `Const.java`. */
case class NetIdentity(mac: Seq[Int], hostname: String) {
  require(mac.size == 6 && mac.forall(o => o >= 0 && o <= 0xff), s"not a MAC address: $mac")
  /** The top 16 bits, as `NetConfig.macHi` holds them. */
  def macHi: Int = (mac(0) << 8) | mac(1)
  /** The low 32 bits, as `NetConfig.macLo` holds them. */
  def macLo: Long = (mac(2).toLong << 24) | (mac(3) << 16) | (mac(4) << 8) | mac(5)
}

object NetIdentity {
  /** What every image had before board instances, and what a board no instance
    * covers still gets: 02:00:00:00:00:01, shared by all of them, and no hostname. */
  val shared = NetIdentity(Seq(0x02, 0x00, 0x00, 0x00, 0x00, 0x01), "")
}
