package jop.io

import spinal.core._
import jop.config.{DeviceInstance, DeviceType, JopCoreConfig}

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
 * Utility methods for converting DeviceInstance maps to IoDeviceDescriptors.
 *
 * All metadata (addrBits, interruptCount, factory, pins) lives on DeviceType.
 * This object provides conversion helpers used by JopCore and ConstGenerator.
 */
object DeviceTypes {

  /**
   * Convert a named device map to IoDeviceDescriptor sequence.
   *
   * @param devices       Named device instances (e.g., "uart0" -> DeviceInstance(DeviceType.Uart))
   * @param bootDeviceName Name of the boot device (gets fixedBase=0xEE), or None
   * @param cfg           Core config (passed to factory at elaboration time)
   * @param ctx           Clock domain context (eth, vga)
   */
  def toDescriptors(
    devices: Map[String, DeviceInstance],
    bootDeviceName: Option[String],
    cfg: JopCoreConfig,
    ctx: DeviceContext
  ): Seq[IoDeviceDescriptor] = {
    devices.toSeq.map { case (name, inst) =>
      val dt = inst.deviceType
      IoDeviceDescriptor(
        name = name,
        addrBits = dt.addrBits,
        interruptCount = dt.interruptCount,
        fixedBase = if (bootDeviceName.contains(name)) Some(0xEE) else None,
        registerNames = dt.registerNames,
        factory = c => dt.create(c, inst.params, ctx)
      )
    }
  }

  /** Compute interrupt count from a device map */
  def interruptCount(devices: Map[String, DeviceInstance]): Int = {
    val n = devices.values.map(_.deviceType.interruptCount).sum
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
      val dt = inst.deviceType
      IoDeviceDescriptor(
        name = name,
        addrBits = dt.addrBits,
        interruptCount = dt.interruptCount,
        fixedBase = if (bootDeviceName.contains(name)) Some(0xEE) else None,
        registerNames = dt.registerNames,
        factory = noop
      )
    }
  }

  /** Determine boot device name from a device map.
   *  cfgFlash takes priority; otherwise falls back to first uart instance. */
  def bootDeviceName(devices: Map[String, DeviceInstance]): Option[String] =
    devices.keys.find(k => devices(k).deviceType == DeviceType.CfgFlash)
      .orElse(devices.keys.find(k => devices(k).deviceType == DeviceType.Uart))

  /** Count DMA-capable devices in a device map */
  def dmaCount(devices: Map[String, DeviceInstance]): Int =
    devices.values.count(_.deviceType.hasDma)
}
