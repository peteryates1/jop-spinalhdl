package jop.io

import spinal.core._
import jop.config.JopCoreConfig

/**
 * Descriptor for a pluggable JOP I/O device.
 *
 * Devices declare their size (addrBits) and optional fixed address.
 * The IoAddressAllocator assigns base addresses at generation time.
 *
 * @param name           Unique device name (used as key in ioDevices map)
 * @param addrBits       Sub-address width: device occupies 2^addrBits addresses (1-4)
 * @param interruptCount Number of interrupt outputs to Sys
 * @param fixedBase      Fixed base address in I/O space (e.g. Some(0x80) for Sys),
 *                       or None for auto-allocation
 * @param perCore        True = each core gets its own instance (default)
 * @param coreZeroOnly   True = only instantiated in core 0
 * @param shared         Reserved for future shared-bus devices
 * @param registerNames  (subAddr, name) pairs for Const.java generation
 * @param factory        Creates a device instance given the core config
 */
case class IoDeviceDescriptor(
  name:           String,
  addrBits:       Int,
  interruptCount: Int = 0,
  fixedBase:      Option[Int] = None,
  perCore:        Boolean = true,
  coreZeroOnly:   Boolean = false,
  shared:         Boolean = false,
  registerNames:  Seq[(Int, String)] = Seq.empty,
  factory:        JopCoreConfig => Component with HasBusIo
) {
  require(addrBits >= 1 && addrBits <= 4,
    s"addrBits must be 1-4, got $addrBits for device '$name'")
  require(fixedBase.forall(b => (b & ((1 << addrBits) - 1)) == 0),
    s"fixedBase must be aligned to 2^$addrBits for device '$name'")

  /** Number of addresses this device occupies */
  def size: Int = 1 << addrBits
}
