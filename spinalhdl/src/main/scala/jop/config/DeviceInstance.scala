package jop.config

import spinal.core._
import jop.io._

/**
 * Known I/O device types — sealed enum carrying factory and bus metadata.
 *
 * Each case object defines everything needed to instantiate and wire a device:
 * address space, interrupts, pin mapping, and a factory that creates the
 * SpinalHDL component.  Adding a new device type = adding one case object here.
 */
sealed trait DeviceType {
  def key: String
  def addrBits: Int
  def interruptCount: Int
  def hasDma: Boolean = false
  def registerNames: Seq[(Int, String)] = Seq.empty
  def verilogPins(params: Map[String, Any]): Map[String, String] = Map.empty
  def create(cfg: JopCoreConfig, params: Map[String, Any], ctx: DeviceContext): Component with HasBusIo
  override def toString: String = key
}

object DeviceType {

  case object Uart extends DeviceType {
    val key = "uart"
    val addrBits = 1
    val interruptCount = 2
    override val registerNames = Seq((0, "STATUS"), (1, "DATA"))
    override def verilogPins(p: Map[String, Any]) =
      Map("ser_txd" -> "TXD", "ser_rxd" -> "RXD")
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      jop.io.Uart(
        baudRate = p.getOrElse("baudRate", cfg.uartBaudRate).asInstanceOf[Int],
        clkFreq = cfg.clkFreq)
  }

  case object Ethernet extends DeviceType {
    val key = "ethernet"
    val addrBits = 4
    val interruptCount = 1
    override def verilogPins(p: Map[String, Any]) = {
      val gmii = p.getOrElse("gmii", false).asInstanceOf[Boolean]
      ethBasePins ++ (if (gmii) ethGmiiExtra else ethMiiExtra)
    }
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      Eth(
        txCd = ctx.ethTxCd.get,
        rxCd = ctx.ethRxCd.get,
        phyTxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        phyRxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        mdioClkDivider = p.getOrElse("mdioClkDivider", 40).asInstanceOf[Int])
  }

  case object SdSpi extends DeviceType {
    val key = "sdspi"
    val addrBits = 2
    val interruptCount = 1
    override val registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "CLK_DIV"))
    override def verilogPins(p: Map[String, Any]) = Map(
      "sd_spi_clk" -> "CLK", "sd_spi_mosi" -> "CMD",
      "sd_spi_miso" -> "DAT0", "sd_spi_cs" -> "DAT3",
      "sd_spi_cd" -> "CD")
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      jop.io.SdSpi(clkDivInit = p.getOrElse("clkDivInit", 199).asInstanceOf[Int])
  }

  case object SdNative extends DeviceType {
    val key = "sdnative"
    val addrBits = 4
    val interruptCount = 1
    override def verilogPins(p: Map[String, Any]) = Map(
      "sd_clk" -> "CLK", "sd_cmd" -> "CMD",
      "sd_dat_0" -> "DAT0", "sd_dat_1" -> "DAT1",
      "sd_dat_2" -> "DAT2", "sd_dat_3" -> "DAT3",
      "sd_cd" -> "CD")
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      jop.io.SdNative(clkDivInit = p.getOrElse("clkDivInit", 99).asInstanceOf[Int])
  }

  case object VgaDma extends DeviceType {
    val key = "vgadma"
    val addrBits = 2
    val interruptCount = 1
    override val hasDma = true
    override def verilogPins(p: Map[String, Any]) = vgaVerilogPins
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      VgaBmbDma(
        bmbParam = cfg.memConfig.bmbParameter,
        vgaCd = ctx.vgaCd.get,
        fifoDepth = p.getOrElse("fifoDepth", 512).asInstanceOf[Int])
  }

  case object VgaText extends DeviceType {
    val key = "vgatext"
    val addrBits = 4
    val interruptCount = 1
    override def verilogPins(p: Map[String, Any]) = vgaVerilogPins
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      jop.io.VgaText(vgaCd = ctx.vgaCd.get)
  }

  case object CfgFlash extends DeviceType {
    val key = "cfgflash"
    val addrBits = 1
    val interruptCount = 0
    override val registerNames = Seq((0, "STATUS"), (1, "DATA"))
    override def verilogPins(p: Map[String, Any]) = Map(
      "cf_dclk" -> "DCLK", "cf_ncs" -> "NCS",
      "cf_asdo" -> "ASDO", "cf_data0" -> "DATA0")
    def create(cfg: JopCoreConfig, p: Map[String, Any], ctx: DeviceContext) =
      ConfigFlash(clkDivInit = p.getOrElse("clkDivInit", 3).asInstanceOf[Int])
  }

  val all: Seq[DeviceType] = Seq(Uart, Ethernet, SdNative, SdSpi, VgaDma, VgaText, CfgFlash)

  def byKey(key: String): Option[DeviceType] = all.find(_.key == key)

  // --- Shared pin maps (used by Ethernet and VGA case objects) ---

  private val vgaVerilogPins = Map(
    "vga_hs" -> "HS", "vga_vs" -> "VS",
    "vga_r[0]" -> "R0", "vga_r[1]" -> "R1", "vga_r[2]" -> "R2",
    "vga_r[3]" -> "R3", "vga_r[4]" -> "R4",
    "vga_g[0]" -> "G0", "vga_g[1]" -> "G1", "vga_g[2]" -> "G2",
    "vga_g[3]" -> "G3", "vga_g[4]" -> "G4", "vga_g[5]" -> "G5",
    "vga_b[0]" -> "B0", "vga_b[1]" -> "B1", "vga_b[2]" -> "B2",
    "vga_b[3]" -> "B3", "vga_b[4]" -> "B4")

  private val ethBasePins = Map(
    "e_mdc" -> "MDC", "e_mdio" -> "MDIO", "e_resetn" -> "RESET",
    "e_txen" -> "TX_EN", "e_txer" -> "TX_ER",
    "e_txd[0]" -> "TXD0", "e_txd[1]" -> "TXD1",
    "e_txd[2]" -> "TXD2", "e_txd[3]" -> "TXD3",
    "e_rxc" -> "RX_CLK", "e_rxdv" -> "RX_DV", "e_rxer" -> "RX_ER",
    "e_rxd[0]" -> "RXD0", "e_rxd[1]" -> "RXD1",
    "e_rxd[2]" -> "RXD2", "e_rxd[3]" -> "RXD3")

  private val ethMiiExtra = Map("e_txc" -> "TX_CLK")

  private val ethGmiiExtra = Map(
    "e_gtxc" -> "GTX_CLK",
    "e_txd[4]" -> "TXD4", "e_txd[5]" -> "TXD5",
    "e_txd[6]" -> "TXD6", "e_txd[7]" -> "TXD7",
    "e_rxd[4]" -> "RXD4", "e_rxd[5]" -> "RXD5",
    "e_rxd[6]" -> "RXD6", "e_rxd[7]" -> "RXD7")
}

/**
 * A named device instance within a core or cluster.
 *
 * @param deviceType  I/O device type (carries factory, address space, pin map)
 * @param mapping     Signal → board connector pin (e.g., "txd" -> "j10.1")
 * @param params      Device-specific parameters (baud rate, clock divider, etc.)
 * @param devicePart  Physical device part name on the board (e.g., "CP2102N", "RTL8211EG").
 *                    Used by PinResolver to look up pin mappings from BoardDevice.
 */
case class DeviceInstance(
  deviceType: DeviceType,
  mapping: Map[String, String] = Map.empty,
  params: Map[String, Any] = Map.empty,
  devicePart: Option[String] = None
)
