package jop.ddr3

import spinal.core._
import spinal.lib._

/**
 * Cache request/response pair.
 *
 * `idWidth` is what makes several requests able to be in flight at once: a
 * response carries back the id of the request it belongs to, so the master can
 * match them without relying on arrival order. At `idWidth = 0` the field is
 * absent entirely, so the single-outstanding users — both backend adapters,
 * which are strictly in-order — keep exactly the bundle they had.
 *
 * Use `idValue` / `driveId` rather than touching `id` directly; they are the
 * only forms that also compile at `idWidth = 0`.
 */
case class CacheReq(addrWidth: Int, dataWidth: Int, idWidth: Int = 0) extends Bundle {
  val addr = Bits(addrWidth bits)
  val write = Bool()
  val data = Bits(dataWidth bits)
  val mask = Bits((dataWidth / 8) bits)
  val id = (idWidth > 0) generate UInt(idWidth bits)

  /** Request id, or a constant 0 when this bundle carries no id field. */
  def idValue: UInt = if (id != null) id else U(0, 1 bits)

  /** Drive the id if there is one; a no-op otherwise. */
  def driveId(value: UInt): Unit = if (id != null) id := value.resized
}

case class CacheRsp(dataWidth: Int, idWidth: Int = 0) extends Bundle {
  val data = Bits(dataWidth bits)
  val error = Bool()
  val id = (idWidth > 0) generate UInt(idWidth bits)

  /** Response id, or a constant 0 when this bundle carries no id field. */
  def idValue: UInt = if (id != null) id else U(0, 1 bits)

  /** Drive the id if there is one; a no-op otherwise. */
  def driveId(value: UInt): Unit = if (id != null) id := value.resized
}

case class CacheFrontend(addrWidth: Int, dataWidth: Int, idWidth: Int = 0) extends Bundle with IMasterSlave {
  val req = Stream(CacheReq(addrWidth, dataWidth, idWidth))
  val rsp = Stream(CacheRsp(dataWidth, idWidth))

  override def asMaster(): Unit = {
    master(req)
    slave(rsp)
  }
}
