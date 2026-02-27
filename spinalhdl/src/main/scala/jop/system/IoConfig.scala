package jop.system

/**
 * I/O device configuration for JOP.
 *
 * Centralises all device presence flags, parameters, and interrupt routing
 * that were previously scattered across JopCoreConfig boolean fields.
 *
 * Constraints:
 *   - SD SPI and SD Native are mutually exclusive (share pins)
 *   - VGA DMA and VGA Text are mutually exclusive (share pins)
 *
 * @param hasUart           Whether to instantiate BmbUart
 * @param hasEth            Whether to instantiate BmbEth + BmbMdio
 * @param hasSdSpi          Whether to instantiate BmbSdSpi
 * @param hasSdNative       Whether to instantiate BmbSdNative
 * @param hasVgaDma         Whether to instantiate BmbVgaDma
 * @param hasVgaText        Whether to instantiate BmbVgaText
 * @param uartBaudRate      UART baud rate in Hz (only used when hasUart = true)
 * @param mdioClkDivider    MDIO clock divider (only used when hasEth = true)
 * @param sdSpiClkDivInit   SD SPI initial clock divider (~200 kHz at 80 MHz)
 * @param sdNativeClkDivInit SD Native initial clock divider (~400 kHz at 80 MHz)
 * @param vgaDmaFifoDepth   VGA DMA CDC FIFO depth in 32-bit words
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
  uartBaudRate: Int = 1000000,

  // MDIO
  mdioClkDivider: Int = 40,

  // SD card
  sdSpiClkDivInit:    Int = 199,   // ~200 kHz at 80 MHz
  sdNativeClkDivInit: Int = 99,    // ~400 kHz at 80 MHz

  // VGA DMA
  vgaDmaFifoDepth: Int = 512,

  // Config flash
  cfgFlashClkDivInit: Int = 3   // ~10 MHz at 80 MHz
) {
  require(!ethGmii || hasEth, "ethGmii requires hasEth")

  /** PHY data width: 8 for GMII (1Gbps), 4 for MII (100Mbps) */
  def phyDataWidth: Int = if (ethGmii) 8 else 4
  require(!(hasSdSpi && hasSdNative), "SD SPI and SD Native are mutually exclusive (share pins)")
  require(!(hasVgaDma && hasVgaText), "VGA DMA and VGA Text are mutually exclusive (share pins)")

  /** Number of interrupt sources wired to BmbSys */
  def numIoInt: Int = {
    var n = 0
    if (hasUart)     n += 2  // RX, TX
    if (hasEth)      n += 3  // ETH RX, ETH TX, MDIO combined
    if (hasSdSpi)    n += 1
    if (hasSdNative) n += 1
    if (hasVgaDma)   n += 1
    if (hasVgaText)  n += 1
    n.max(2)  // BmbSys requires at least 2
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
}
