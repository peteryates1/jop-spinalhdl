package jop.config

import spinal.core._
import jop.io._

/**
 * I/O device configuration for JOP.
 *
 * Centralises all device presence flags, parameters, and interrupt routing.
 * Board-specific peripherals are added via extensionDevices.
 *
 * Constraints:
 *   - SD SPI and SD Native are mutually exclusive (share pins)
 *   - VGA DMA and VGA Text are mutually exclusive (share pins)
 *
 * @param hasUart           Whether to instantiate Uart
 * @param hasEth            Whether to instantiate Eth + Mdio
 * @param hasSdSpi          Whether to instantiate SdSpi
 * @param hasSdNative       Whether to instantiate SdNative
 * @param hasVgaDma         Whether to instantiate VgaBmbDma
 * @param hasVgaText        Whether to instantiate VgaText
 * @param hasConfigFlash    Whether to instantiate ConfigFlash
 * @param uartBaudRate      UART baud rate in Hz (only used when hasUart = true)
 * @param mdioClkDivider    MDIO clock divider (only used when hasEth = true)
 * @param sdSpiClkDivInit   SD SPI initial clock divider (~200 kHz at 80 MHz)
 * @param sdNativeClkDivInit SD Native initial clock divider (~400 kHz at 80 MHz)
 * @param vgaDmaFifoDepth   VGA DMA CDC FIFO depth in 32-bit words
 * @param extensionDevices  Board-specific I/O device descriptors
 */
case class IoConfig(
  // Device presence
  hasUart:      Boolean = true,
  hasEth:       Boolean = false,
  ethGmii:      Boolean = false,
  hasSdSpi:     Boolean = false,
  hasSdNative:  Boolean = false,
  hasVgaDma:       Boolean = false,
  hasVgaText:      Boolean = false,
  hasConfigFlash:  Boolean = false,

  // UART
  uartBaudRate: Int = 2000000,

  // MDIO
  mdioClkDivider: Int = 40,

  // SD card
  sdSpiClkDivInit:    Int = 199,   // ~200 kHz at 80 MHz
  sdNativeClkDivInit: Int = 99,    // ~400 kHz at 80 MHz

  // VGA DMA
  vgaDmaFifoDepth: Int = 512,

  // Config flash
  cfgFlashClkDivInit: Int = 3,   // ~10 MHz at 80 MHz

  // Board-specific extension devices
  extensionDevices: Seq[IoDeviceDescriptor] = Seq.empty
) {
  require(!ethGmii || hasEth, "ethGmii requires hasEth")

  /** PHY data width: 8 for GMII (1Gbps), 4 for MII (100Mbps) */
  def phyDataWidth: Int = if (ethGmii) 8 else 4
  require(!(hasSdSpi && hasSdNative), "SD SPI and SD Native are mutually exclusive (share pins)")
  require(!(hasVgaDma && hasVgaText), "VGA DMA and VGA Text are mutually exclusive (share pins)")

  /** Build the complete list of device descriptors for this configuration.
   *
   *  Sys is always added by JopCore itself (not listed here).
   *  Uart is fixed at 0xE0. All others are auto-allocated.
   *
   *  @param cfg     Core configuration
   *  @param vgaCd   VGA pixel clock domain (needed for VgaText/VgaDma)
   *  @param ethTxCd Ethernet TX clock domain (needed for Eth)
   *  @param ethRxCd Ethernet RX clock domain (needed for Eth)
   */
  def allDevices(cfg: JopCoreConfig,
                 vgaCd: Option[ClockDomain] = None,
                 ethTxCd: Option[ClockDomain] = None,
                 ethRxCd: Option[ClockDomain] = None): Seq[IoDeviceDescriptor] = {
    val builtIn = Seq.empty[IoDeviceDescriptor] ++
      (if (hasUart) Seq(IoDeviceDescriptor(
        name = "uart", addrBits = 4, interruptCount = 2,
        fixedBase = Some(0xE0),
        registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "INTCTRL")),
        factory = c => Uart(c.uartBaudRate, c.clkFreq)
      )) else Seq.empty) ++
      (if (hasEth && ethTxCd.isDefined && ethRxCd.isDefined) Seq(
        IoDeviceDescriptor(
          name = "eth", addrBits = 4, interruptCount = 1,
          coreZeroOnly = true,
          factory = c => Eth(ethTxCd.get, ethRxCd.get,
            phyTxDataWidth = c.ioConfig.phyDataWidth,
            phyRxDataWidth = c.ioConfig.phyDataWidth,
            mdioClkDivider = c.ioConfig.mdioClkDivider)
        )
      ) else Seq.empty) ++
      (if (hasSdSpi) Seq(IoDeviceDescriptor(
        name = "sdSpi", addrBits = 2, interruptCount = 1,
        coreZeroOnly = true,
        registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "CLK_DIV")),
        factory = c => SdSpi(c.ioConfig.sdSpiClkDivInit)
      )) else Seq.empty) ++
      (if (hasSdNative) Seq(IoDeviceDescriptor(
        name = "sdNative", addrBits = 4, interruptCount = 1,
        coreZeroOnly = true,
        factory = c => SdNative(c.ioConfig.sdNativeClkDivInit)
      )) else Seq.empty) ++
      (if (hasVgaDma && vgaCd.isDefined) Seq(IoDeviceDescriptor(
        name = "vgaDma", addrBits = 2, interruptCount = 1,
        coreZeroOnly = true,
        factory = c => VgaBmbDma(c.memConfig.bmbParameter, vgaCd.get, c.ioConfig.vgaDmaFifoDepth)
      )) else Seq.empty) ++
      (if (hasVgaText && vgaCd.isDefined) Seq(IoDeviceDescriptor(
        name = "vgaText", addrBits = 4, interruptCount = 1,
        coreZeroOnly = true,
        factory = _ => VgaText(vgaCd.get)
      )) else Seq.empty) ++
      (if (hasConfigFlash) Seq(IoDeviceDescriptor(
        name = "cfgFlash", addrBits = 2,
        coreZeroOnly = true,
        registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "CLK_DIV")),
        factory = c => ConfigFlash(c.ioConfig.cfgFlashClkDivInit)
      )) else Seq.empty)

    builtIn ++ extensionDevices
  }

  /** Number of interrupt sources wired to Sys (computed from descriptors) */
  def numIoInt: Int = numIoIntFromDevices

  /** Compute interrupt count from built-in flags (for backward compat) */
  private def numIoIntFromDevices: Int = {
    var n = 0
    if (hasUart)     n += 2  // RX, TX
    if (hasEth)      n += 1  // combined (MDIO aggregates MAC RX/TX + MDIO done)
    if (hasSdSpi)    n += 1
    if (hasSdNative) n += 1
    if (hasVgaDma)   n += 1
    if (hasVgaText)  n += 1
    n += extensionDevices.map(_.interruptCount).sum
    n.max(2)  // Sys requires at least 2
  }

  /** Descriptors for address allocation only (no clock domains, dummy factories).
   *  Used by ConstGenerator to compute I/O addresses without hardware context. */
  def allDescriptorsForAllocation(): Seq[IoDeviceDescriptor] = {
    val noop: JopCoreConfig => Component with HasBusIo = _ => ???
    Seq.empty[IoDeviceDescriptor] ++
      (if (hasUart) Seq(IoDeviceDescriptor(
        name = "uart", addrBits = 4, interruptCount = 2,
        fixedBase = Some(0xE0), factory = noop
      )) else Seq.empty) ++
      (if (hasEth) Seq(
        IoDeviceDescriptor(name = "eth", addrBits = 4, interruptCount = 1, coreZeroOnly = true, factory = noop)
      ) else Seq.empty) ++
      (if (hasSdSpi) Seq(IoDeviceDescriptor(
        name = "sdSpi", addrBits = 2, interruptCount = 1, coreZeroOnly = true, factory = noop
      )) else Seq.empty) ++
      (if (hasSdNative) Seq(IoDeviceDescriptor(
        name = "sdNative", addrBits = 4, interruptCount = 1, coreZeroOnly = true, factory = noop
      )) else Seq.empty) ++
      (if (hasVgaDma) Seq(IoDeviceDescriptor(
        name = "vgaDma", addrBits = 2, interruptCount = 1, coreZeroOnly = true, factory = noop
      )) else Seq.empty) ++
      (if (hasVgaText) Seq(IoDeviceDescriptor(
        name = "vgaText", addrBits = 4, interruptCount = 1, coreZeroOnly = true, factory = noop
      )) else Seq.empty) ++
      (if (hasConfigFlash) Seq(IoDeviceDescriptor(
        name = "cfgFlash", addrBits = 2, coreZeroOnly = true, factory = noop
      )) else Seq.empty) ++
      extensionDevices.map(d => d.copy(factory = noop))
  }

  /** True if any VGA device is present (for pin exposure) */
  def hasVga: Boolean = hasVgaDma || hasVgaText

  /** True if any SD device is present (for pin exposure) */
  def hasSd: Boolean = hasSdSpi || hasSdNative
}

object IoConfig {
  /** Minimal: UART only (simulation, basic boards) */
  def minimal: IoConfig = IoConfig()

  /** QMTECH EP4CGX150 + SDRAM: UART + Ethernet + Config Flash */
  def qmtechSdram: IoConfig = IoConfig(hasEth = true, hasConfigFlash = true)

  /** QMTECH EP4CGX150 + DB_FPGA: full I/O */
  def qmtechDbFpga: IoConfig = IoConfig(
    hasEth = true,
    ethGmii = true,
    hasSdNative = true,
    hasVgaText = true
  )

  /** QMTECH EP4CGX150 + DB_FPGA: VGA DMA instead of VGA Text */
  def qmtechDbFpgaVgaDma: IoConfig = IoConfig(
    hasEth = true,
    ethGmii = true,
    hasSdNative = true,
    hasVgaDma = true
  )

  /** Wukong full: UART + Ethernet (GMII 1Gbps) + SD Native 4-bit */
  def wukongFull: IoConfig = IoConfig(
    hasEth = true,
    ethGmii = true,
    hasSdNative = true
  )
}
