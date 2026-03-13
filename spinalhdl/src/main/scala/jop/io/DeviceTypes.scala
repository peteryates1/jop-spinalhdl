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
 * @param factory        Creates a device instance given (coreConfig, params, clockDomainContext)
 */
case class DeviceTypeInfo(
  addrBits: Int,
  interruptCount: Int,
  hasDma: Boolean = false,
  registerNames: Seq[(Int, String)] = Seq.empty,
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

  /** Count DMA-capable devices in a device map */
  def dmaCount(devices: Map[String, DeviceInstance]): Int = {
    devices.values.count { inst =>
      registry.get(inst.deviceType).exists(_.hasDma)
    }
  }
  val registry: Map[String, DeviceTypeInfo] = Map(
    "uart" -> DeviceTypeInfo(
      addrBits = 1, interruptCount = 2,
      registerNames = Seq((0, "STATUS"), (1, "DATA")),
      factory = (c, p, _) => Uart(
        baudRate = p.getOrElse("baudRate", c.uartBaudRate).asInstanceOf[Int],
        clkFreq = c.clkFreq)),

    "ethernet" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      factory = (c, p, ctx) => Eth(
        txCd = ctx.ethTxCd.get,
        rxCd = ctx.ethRxCd.get,
        phyTxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        phyRxDataWidth = p.getOrElse("phyDataWidth", 4).asInstanceOf[Int],
        mdioClkDivider = p.getOrElse("mdioClkDivider", 40).asInstanceOf[Int])),

    "sdspi" -> DeviceTypeInfo(
      addrBits = 2, interruptCount = 1,
      registerNames = Seq((0, "STATUS"), (1, "DATA"), (2, "CLK_DIV")),
      factory = (_, p, _) => SdSpi(
        clkDivInit = p.getOrElse("clkDivInit", 199).asInstanceOf[Int])),

    "sdnative" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      factory = (_, p, _) => SdNative(
        clkDivInit = p.getOrElse("clkDivInit", 99).asInstanceOf[Int])),

    "vgadma" -> DeviceTypeInfo(
      addrBits = 2, interruptCount = 1, hasDma = true,
      factory = (c, p, ctx) => VgaBmbDma(
        bmbParam = c.memConfig.bmbParameter,
        vgaCd = ctx.vgaCd.get,
        fifoDepth = p.getOrElse("fifoDepth", 512).asInstanceOf[Int])),

    "vgatext" -> DeviceTypeInfo(
      addrBits = 4, interruptCount = 1,
      factory = (_, _, ctx) => VgaText(
        vgaCd = ctx.vgaCd.get)),

    "cfgflash" -> DeviceTypeInfo(
      addrBits = 1, interruptCount = 0,
      registerNames = Seq((0, "STATUS"), (1, "DATA")),
      factory = (_, p, _) => ConfigFlash(
        clkDivInit = p.getOrElse("clkDivInit", 3).asInstanceOf[Int]))
  )
}
