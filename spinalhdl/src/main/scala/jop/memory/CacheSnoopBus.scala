package jop.memory

import spinal.core._

/**
 * Cache Snoop Output Bundle
 *
 * Broadcast from each core's memory controller when it completes a store
 * (iastore or putfield) to shared memory. Other cores use this to
 * selectively invalidate matching cache lines.
 *
 * Fires in HANDLE_DATA_WAIT when io.bmb.rsp.fire && handleIsWrite.
 *
 * @param addressWidth Handle address width (matches config.addressWidth)
 * @param maxIndexBits Max index width (covers both array index and field index)
 */
case class CacheSnoopOut(addressWidth: Int, maxIndexBits: Int) extends Bundle {
  val valid   = Bool()                      // Snoop event this cycle
  val isArray = Bool()                      // True = iastore, False = putfield
  val handle  = UInt(addressWidth bits)     // Object/array handle
  val index   = UInt(maxIndexBits bits)     // Array index (iastore) or field index (putfield)
}

/**
 * Cache Snoop Input Bundle
 *
 * Per-core input combining all other cores' snoop outputs (OR'd valid,
 * MuxOH for data fields since only one core writes per cycle due to
 * BMB arbiter serialization).
 *
 * @param addressWidth Handle address width (matches config.addressWidth)
 * @param maxIndexBits Max index width (covers both array index and field index)
 */
case class CacheSnoopIn(addressWidth: Int, maxIndexBits: Int) extends Bundle {
  val valid   = Bool()
  val isArray = Bool()
  val handle  = UInt(addressWidth bits)
  val index   = UInt(maxIndexBits bits)
}
