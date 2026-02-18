package jop.ddr3

import spinal.core._
import spinal.lib._

object CacheToMigAdapterState extends SpinalEnum {
  val IDLE, ISSUE_WRITE, ISSUE_READ, WAIT_READ = newElement()
}

class CacheToMigAdapter extends Component {
  val io = new Bundle {
    val cmd = slave Stream(new Bundle {
      val addr = Bits(28 bits)
      val write = Bool()
      val wdata = Bits(128 bits)
      val wmask = Bits(16 bits)
    })

    // Read-response only in this phase.
    val rsp = master Stream(new Bundle {
      val rdata = Bits(128 bits)
      val error = Bool()
    })

    val busy = out Bool()

    // MIG UI side
    val app_rdy = in Bool()
    val app_wdf_rdy = in Bool()
    val app_rd_data = in Bits(128 bits)
    val app_rd_data_valid = in Bool()

    val app_addr = out Bits(28 bits)
    val app_cmd = out Bits(3 bits)
    val app_en = out Bool()
    val app_wdf_data = out Bits(128 bits)
    val app_wdf_mask = out Bits(16 bits)
    val app_wdf_wren = out Bool()
    val app_wdf_end = out Bool()
  }

  private val addrAlignBits = log2Up(128 / 8)
  val writeCmd = B"3'x0"
  val readCmd = B"3'x1"

  val cmdFifo = StreamFifo(io.cmd.payloadType, 2)
  cmdFifo.io.push << io.cmd

  val rspFifo = StreamFifo(io.rsp.payloadType, 2)
  io.rsp << rspFifo.io.pop
  rspFifo.io.push.valid := False
  rspFifo.io.push.payload.rdata := io.app_rd_data
  rspFifo.io.push.payload.error := False

  val activeCmd = Reg(io.cmd.payloadType)
  val state = Reg(CacheToMigAdapterState()) init(CacheToMigAdapterState.IDLE)
  val writeCmdSent = Reg(Bool()) init (False)
  val writeDataSent = Reg(Bool()) init (False)

  // MIG app_addr is byte-space addressed; low bits must be zero for 128-bit transactions.
  val appAddrAligned = UInt(io.app_addr.getWidth bits)
  appAddrAligned := activeCmd.addr.asUInt.resized
  appAddrAligned(addrAlignBits - 1 downto 0) := 0
  io.app_addr := appAddrAligned.asBits
  io.app_cmd := writeCmd
  io.app_en := False
  io.app_wdf_data := activeCmd.wdata
  io.app_wdf_mask := activeCmd.wmask
  io.app_wdf_wren := False
  io.app_wdf_end := False

  cmdFifo.io.pop.ready := False

  switch(state) {
    is(CacheToMigAdapterState.IDLE) {
      when(cmdFifo.io.pop.valid) {
        activeCmd := cmdFifo.io.pop.payload
        cmdFifo.io.pop.ready := True
        writeCmdSent := False
        writeDataSent := False
        when(cmdFifo.io.pop.payload.write) {
          state := CacheToMigAdapterState.ISSUE_WRITE
        } otherwise {
          state := CacheToMigAdapterState.ISSUE_READ
        }
      }
    }

    is(CacheToMigAdapterState.ISSUE_WRITE) {
      io.app_cmd := writeCmd
      val canSendCmd = !writeCmdSent && io.app_rdy
      when(canSendCmd) {
        io.app_en := True
        writeCmdSent := True
      }
      when(!writeDataSent && io.app_wdf_rdy && (writeCmdSent || canSendCmd)) {
        io.app_wdf_wren := True
        io.app_wdf_end := True
        writeDataSent := True
      }
      when(writeCmdSent && writeDataSent && rspFifo.io.push.ready) {
        rspFifo.io.push.valid := True
        rspFifo.io.push.payload.rdata := B(0, 128 bits)
        rspFifo.io.push.payload.error := False
        state := CacheToMigAdapterState.IDLE
      }
    }

    is(CacheToMigAdapterState.ISSUE_READ) {
      io.app_cmd := readCmd
      when(io.app_rdy) {
        io.app_en := True
        state := CacheToMigAdapterState.WAIT_READ
      }
    }

    is(CacheToMigAdapterState.WAIT_READ) {
      when(io.app_rd_data_valid && rspFifo.io.push.ready) {
        rspFifo.io.push.valid := True
        state := CacheToMigAdapterState.IDLE
      }
    }
  }

  io.busy := state =/= CacheToMigAdapterState.IDLE
}
