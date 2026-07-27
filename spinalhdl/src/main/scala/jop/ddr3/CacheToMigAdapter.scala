package jop.ddr3

import spinal.core._
import spinal.lib._

object CacheToMigAdapterState extends SpinalEnum {
  val IDLE, ISSUE_READ, WAIT_READ = newElement()
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

    // Debug
    val debugState = out UInt(3 bits)
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

  // Defensive: capture MIG read data when rspFifo is not ready.
  // MIG app_rd_data_valid is a one-cycle pulse — if missed, data is lost forever.
  val readDataCaptured = Reg(Bool()) init(False)
  val readDataReg = Reg(Bits(128 bits)) init(0)

  // MIG app_addr is byte-space addressed; low bits must be zero for 128-bit transactions.
  def alignedAddr(addr: Bits): Bits = {
    val a = UInt(io.app_addr.getWidth bits)
    a := addr.asUInt.resized
    a(addrAlignBits - 1 downto 0) := 0
    a.asBits
  }

  io.app_addr := alignedAddr(activeCmd.addr)
  io.app_cmd := writeCmd
  io.app_en := False
  io.app_wdf_data := activeCmd.wdata
  io.app_wdf_mask := activeCmd.wmask
  io.app_wdf_wren := False
  io.app_wdf_end := False

  cmdFifo.io.pop.ready := False

  switch(state) {
    is(CacheToMigAdapterState.IDLE) {
      readDataCaptured := False
      when(cmdFifo.io.pop.valid) {
        when(cmdFifo.io.pop.payload.write) {
          // Streaming write: issue command + data on the same cycle when the MIG
          // accepts both and a response slot is free, then stay in IDLE so the
          // next queued write can issue on the following cycle (1 write/cycle).
          when(io.app_rdy && io.app_wdf_rdy && rspFifo.io.push.ready) {
            io.app_addr     := alignedAddr(cmdFifo.io.pop.payload.addr)
            io.app_cmd      := writeCmd
            io.app_en       := True
            io.app_wdf_data := cmdFifo.io.pop.payload.wdata
            io.app_wdf_mask := cmdFifo.io.pop.payload.wmask
            io.app_wdf_wren := True
            io.app_wdf_end  := True
            cmdFifo.io.pop.ready := True
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.rdata := B(0, 128 bits)
            rspFifo.io.push.payload.error := False
          }
        } otherwise {
          // Read: latch and run through the round-trip state machine.
          activeCmd := cmdFifo.io.pop.payload
          cmdFifo.io.pop.ready := True
          state := CacheToMigAdapterState.ISSUE_READ
        }
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
      when(!readDataCaptured) {
        when(io.app_rd_data_valid) {
          when(rspFifo.io.push.ready) {
            // Normal path: push directly
            rspFifo.io.push.valid := True
            state := CacheToMigAdapterState.IDLE
          } otherwise {
            // rspFifo full: capture data to avoid losing MIG's one-cycle pulse
            readDataReg := io.app_rd_data
            readDataCaptured := True
          }
        }
      } otherwise {
        // Replay captured data when rspFifo becomes ready
        rspFifo.io.push.payload.rdata := readDataReg
        when(rspFifo.io.push.ready) {
          rspFifo.io.push.valid := True
          readDataCaptured := False
          state := CacheToMigAdapterState.IDLE
        }
      }
    }
  }

  io.busy := state =/= CacheToMigAdapterState.IDLE
  io.debugState := state.asBits.asUInt.resized
}
