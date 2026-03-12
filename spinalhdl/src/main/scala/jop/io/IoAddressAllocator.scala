package jop.io

import spinal.core._

/**
 * Result of address allocation for one device.
 *
 * @param descriptor The original device descriptor
 * @param baseAddr   Assigned base address in I/O space (0x80-0xFF)
 */
case class AllocatedDevice(
  descriptor: IoDeviceDescriptor,
  baseAddr:   Int
) {
  /** Address-match predicate for this device (operates on 8-bit ioAddr) */
  def isSelected(ioAddr: UInt): Bool =
    ioAddr(7 downto descriptor.addrBits) === (baseAddr >> descriptor.addrBits)

  /** Extract sub-address for this device (always 4-bit output) */
  def subAddr(ioAddr: UInt): UInt =
    if (descriptor.addrBits >= 4) ioAddr(3 downto 0)
    else ioAddr(descriptor.addrBits - 1 downto 0).resize(4)
}

/**
 * I/O address allocator for JOP's pluggable device system.
 *
 * JOP I/O addresses are the low byte of negative word addresses,
 * giving range 0x80-0xFF (128 addresses, accessed via bipush -128..-1).
 *
 * Fixed devices (Sys, Uart) are placed first. Remaining devices
 * are packed downward from 0xDF, largest first, respecting alignment.
 */
object IoAddressAllocator {

  /** Allocate I/O addresses for all descriptors.
   *
   *  @param descriptors All device descriptors (fixed + auto)
   *  @return Allocated devices with assigned base addresses
   *  @throws IllegalArgumentException if allocation fails (overlap or out of space)
   */
  def allocate(descriptors: Seq[IoDeviceDescriptor]): Seq[AllocatedDevice] = {
    val (fixed, auto) = descriptors.partition(_.fixedBase.isDefined)

    // Track occupied addresses (128 slots: 0x80-0xFF)
    val occupied = Array.fill(128)(false)

    // Mark a range as occupied
    def markRange(base: Int, size: Int): Unit = {
      for (i <- 0 until size) {
        val idx = base - 0x80 + i
        require(idx >= 0 && idx < 128,
          s"Address ${base + i} (0x${(base + i).toHexString}) out of I/O range 0x80-0xFF")
        require(!occupied(idx),
          s"Address ${base + i} (0x${(base + i).toHexString}) already occupied")
        occupied(idx) = true
      }
    }

    // Place fixed devices
    val fixedAllocated = fixed.map { d =>
      val base = d.fixedBase.get
      markRange(base, d.size)
      AllocatedDevice(d, base)
    }

    // Sort auto devices by size (largest first for best packing)
    val sortedAuto = auto.sortBy(-_.size)

    // Allocate auto devices packing downward from 0xDF
    val autoAllocated = sortedAuto.map { d =>
      val alignment = d.size  // 2^addrBits alignment
      // Scan downward from 0xDF to find first aligned slot that fits
      val candidates = (0xDF to 0x80 by -1).filter(addr =>
        (addr & (alignment - 1)) == 0 &&  // aligned
        addr + d.size - 1 <= 0xDF &&       // fits below fixed region
        (0 until d.size).forall(i => !occupied(addr - 0x80 + i))  // not occupied
      )
      require(candidates.nonEmpty,
        s"Cannot allocate ${d.size} addresses for device '${d.name}' — I/O space full")
      val base = candidates.head
      markRange(base, d.size)
      AllocatedDevice(d, base)
    }

    fixedAllocated ++ autoAllocated
  }

  /** Print allocation map for debugging */
  def printAllocation(devices: Seq[AllocatedDevice]): Unit = {
    println("=== JOP I/O Address Allocation ===")
    devices.sortBy(_.baseAddr).foreach { ad =>
      val end = ad.baseAddr + ad.descriptor.size - 1
      val fixed = if (ad.descriptor.fixedBase.isDefined) " (fixed)" else " (auto)"
      println(f"  0x${ad.baseAddr}%02X-0x${end}%02X  ${ad.descriptor.name}%-16s  ${ad.descriptor.size}%2d addrs$fixed")
    }
    println("==================================")
  }
}
