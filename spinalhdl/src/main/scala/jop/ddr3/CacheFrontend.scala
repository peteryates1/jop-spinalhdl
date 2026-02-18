package jop.ddr3

import spinal.core._
import spinal.lib._

case class CacheReq(addrWidth: Int, dataWidth: Int) extends Bundle {
  val addr = Bits(addrWidth bits)
  val write = Bool()
  val data = Bits(dataWidth bits)
  val mask = Bits((dataWidth / 8) bits)
}

case class CacheRsp(dataWidth: Int) extends Bundle {
  val data = Bits(dataWidth bits)
  val error = Bool()
}

case class CacheFrontend(addrWidth: Int, dataWidth: Int) extends Bundle with IMasterSlave {
  val req = Stream(CacheReq(addrWidth, dataWidth))
  val rsp = Stream(CacheRsp(dataWidth))

  override def asMaster(): Unit = {
    master(req)
    slave(rsp)
  }
}
