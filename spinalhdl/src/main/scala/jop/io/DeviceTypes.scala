package jop.io

import spinal.core._
import spinal.lib.bus.bmb._
import jop.config.{DeviceInstance, JopCoreConfig}

/**
 * Device bus interface metadata.
 *
 * Describes what a device type needs: I/O register address bits, interrupt count,
 * DMA capability, and a factory function to create the SpinalHDL component.
 *
 * @param addrBits       Sub-address width: device occupies 2^addrBits I/O addresses
 * @param interruptCount Number of interrupt lines to Sys
 * @param hasDma         Device has a BMB master port for memory DMA
 * @param registerNames  (subAddr, name) pairs for Const.java generation
 * @param verilogPins    Maps instance params to verilogPort→deviceSignal pin map.
 *                       Used by PinResolver for constraint file generation (QSF/XDC).
 * @param factory        Creates a device instance given (coreConfig, params, clockDomainContext)
 */
case class DeviceTypeInfo(
  addrBits: Int,
  interruptCount: Int,
  hasDma: Boolean = false,
  registerNames: Seq[(Int, String)] = Seq.empty,
  verilogPins: Map[String, Any] => Map[String, String] = _ => Map.empty,
  factory: (JopCoreConfig, Map[String, Any], DeviceContext) => Component with HasBusIo
)

/**
 * External clock domains needed by some device factories.
 *
 * These are FPGA-specific resources created at the top level (from clock pins or PLLs)
 * and cannot be derived from JopCoreConfig. Threaded from top-level through cluster to core.
 *
 * @param vgaCd   VGA pixel clock domain (for VgaText, VgaBmbDma)
 * @param ethTxCd Ethernet TX clock domain (for Eth)
 * @param ethRxCd Ethernet RX clock domain (for Eth)
 */
case class DeviceContext(
  vgaCd: Option[ClockDomain] = None,
  ethTxCd: Option[ClockDomain] = None,
  ethRxCd: Option[ClockDomain] = None
)

/**
 * Registry of device types to factory metadata.
 *
 * Each entry maps a device type string to its DeviceTypeInfo. New device types
 * are registered here; instances are created by looking up the type string.
 *
 * Also provides conversion from DeviceInstance maps to IoDeviceDescriptor sequences.
 */
object DeviceTypes {

  /**
   * Convert a named device map to IoDeviceDescriptor sequence.
   *
   * @param devices       Named device instances (e.g., "uart0" -> DeviceInstance("uart"))
   * @param bootDeviceName Name of the boot device (gets fixedBase=0xEE), or None
   * @param cfg           Core config (passed to factory lambdas at elaboration time)
   * @param ctx           Clock domain context (eth, vga)
   */
  def toDescriptors(
    devices: Map[String, DeviceInstance],
    bootDeviceName: Option[String],
    cfg: JopCoreConfig,
    ctx: DeviceContext
  ): Seq[IoDeviceDescriptor] = {
    devices.toSeq.map { case (name, inst) =>
      val info = registry.getOrElse(inst.deviceType,
        throw new NoSuchElementException(
          s"Unknown device type '${inst.deviceType}' for device '$name'. " +
          s"Available: ${registry.keys.mkString(", ")}"))
      IoDeviceDescriptor(
        name = name,
        addrBits = info.addrBits,
        interruptCount = info.interruptCount,
        fixedBase = if (bootDeviceName.contains(name)) Some(0xEE) else None,
        registerNames = info.registerNames,
        factory = c => info.factory(c, inst.params, ctx)
      )
    }
  }

  /** Compute interrupt count from a device map */
  def interruptCount(devices: Map[String, DeviceInstance]): Int = {
    val n = devices.values.map { inst =>
      registry.get(inst.deviceType).map(_.interruptCount).getOrElse(0)
    }.sum
    n.max(2) // Sys requires at least 2
  }

  /** Descriptors for address allocation only (no clock domains, dummy factories).
   *  Used by ConstGenerator to compute I/O addresses without hardware context. */
  def toDescriptorsForAllocation(
    devices: Map[String, DeviceInstance],
    bootDeviceName: Option[String]
  ): Seq[IoDeviceDescriptor] = {
    val noop: JopCoreConfig => Component with HasBusIo = _ => ???
    devices.toSeq.map { case (name, inst) =>
      val info = registry.getOrElse(inst.deviceType,
        throw new NoSuchElementException(
          s"Unknown device type '${inst.deviceType}' for device '$name'. " +
          s"Available: ${registry.keys.mkString(", ")}"))
      IoDeviceDescriptor(
        name = name,
        addrBits = info.addrBits,
        interruptCount = info.interruptCount,
        fixedBase = if (bootDeviceName.contains(name)) Some(0xEE) else None,
        registerNames = info.registerNames,
        factory = noop
      )
    }
  }

  /** Determine boot device name from a device map.
   *  cfgFlash takes priority; otherwise falls back to first uart instance. */
  def bootDeviceName(devices: Map[String, DeviceInstance]): Option[String] =
    if (devices.contains("cfgFlash")) Some("cfgFlash")
    else devices.keys.find(k => devices(k).deviceType == "uart")

  /** Count DMA-capable devices in a device map */
  def dmaCount(devices: Map[String, DeviceInstance]): Int = {
    devices.values.count { inst =>
      registry.get(inst.deviceType).exists(_.hasDma)
    }
  }
  // Shared VGA pin map (both DMA and text use same ports)
  private val vgaVerilogPins = Map(
    "vga_hs" -> "HS", "vga_vs" -> "VS",
    "vga_r[0]" -> "R0", "vga_r[1]" -> "R1", "vga_r[2]" -> "R2",
    "vga_r[3]" -> "R3", "vga_r[4]" -> "R4",
    "vga_g[0]" -> "G0", "vga_g[1]" -> "G1", "vga_g[2]" -> "G2",
    "vga_g[3]" -> "G3", "vga_g[4]" -> "G4", "vga_g[5]" -> "G5",
    "vga_b[0]" -> "B0", "vga_b[1]" -> "B1", "vga_b[2]" -> "B2",
    "vga_b[3]" -> "B3", "vga_b[4]" -> "B4")

  // Ethernet MII (4-bit) base pins, shared with GMII
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

  val registry: Map[String, DeviceTypeInfo] = Map(
    "uart" -> DeviceTypeInfo(
      addrBits = 1, interruptCount = 2,
      registerNames = Seq((0, "STATUS"), (1, "DATA")),
      verilogPins = _ => Map("ser_txd" -> "TXD", "ser_rxd" -> "RXD"),
      factory = (c, p, _) => Uart(
        baudRate = p.getOrElse("baudRate", c.uartBaudRate).asInstanceOf[Int],
        clkFreq = c.clkFreq)),

    "ethernet" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      verilogPins = p => {
        val gmii = p.getOrElse("gmii", false).asInstanceOf[Boolean]
        ethBasePins ++ (if (gmii) ethGmiiExtra else ethMiiExtra)
      },
      factory = (c, p, ctx) => Eth(
        txCd = ctx.ethTxCd.get,
        rxCd = ctx.ethRxCd.get,
        phyTxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        phyRxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        mdioClkDivider = p.getOrElse("mdioClkDivider", 40).asInstanceOf[Int])),

    "sdspi" -> DeviceTypeInfo(
      addrBits = 2, interruptCount = 1,
      registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "CLK_DIV")),
      verilogPins = _ => Map(
        "sd_spi_clk" -> "CLK", "sd_spi_mosi" -> "CMD",
        "sd_spi_miso" -> "DAT0", "sd_spi_cs" -> "DAT3",
        "sd_spi_cd" -> "CD"),
      factory = (_, p, _) => SdSpi(
        clkDivInit = p.getOrElse("clkDivInit", 199).asInstanceOf[Int])),

    "sdnative" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      verilogPins = _ => Map(
        "sd_clk" -> "CLK", "sd_cmd" -> "CMD",
        "sd_dat_0" -> "DAT0", "sd_dat_1" -> "DAT1",
        "sd_dat_2" -> "DAT2", "sd_dat_3" -> "DAT3",
        "sd_cd" -> "CD"),
      factory = (_, p, _) => SdNative(
        clkDivInit = p.getOrElse("clkDivInit", 99).asInstanceOf[Int])),

    "vgadma" -> DeviceTypeInfo(
      addrBits = 2, interruptCount = 1, hasDma = true,
      verilogPins = _ => vgaVerilogPins,
      factory = (c, p, ctx) => VgaBmbDma(
        bmbParam = c.memConfig.bmbParameter,
        vgaCd = ctx.vgaCd.get,
        fifoDepth = p.getOrElse("fifoDepth", 512).asInstanceOf[Int])),

    "vgatext" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      verilogPins = _ => vgaVerilogPins,
      factory = (_, _, ctx) => VgaText(
        vgaCd = ctx.vgaCd.get)),

    "cfgflash" -> DeviceTypeInfo(
      addrBits = 1, interruptCount = 0,
      registerNames = Seq((0, "STATUS"), (1, "DATA")),
      verilogPins = _ => Map(
        "cf_dclk" -> "DCLK", "cf_ncs" -> "NCS",
        "cf_asdo" -> "ASDO", "cf_data0" -> "DATA0"),
      factory = (_, p, _) => ConfigFlash(
        clkDivInit = p.getOrElse("clkDivInit", 3).asInstanceOf[Int]))
  )
}
