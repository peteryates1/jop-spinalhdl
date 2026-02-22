package jop.debug

import spinal.core._
import spinal.lib._

/**
 * Debug protocol message types (host -> target requests).
 */
object DebugMsgType {
  // Requests (host -> target)
  def HALT            = 0x01
  def RESUME          = 0x02
  def STEP_MICRO      = 0x03
  def STEP_BYTECODE   = 0x04
  def RESET           = 0x05
  def QUERY_STATUS    = 0x06
  def READ_REGISTERS  = 0x10
  def READ_STACK      = 0x11
  def READ_MEMORY     = 0x12
  def WRITE_REGISTER  = 0x13
  def WRITE_MEMORY    = 0x14
  def WRITE_MEMORY_BLOCK = 0x15
  def SET_BREAKPOINT  = 0x20
  def CLEAR_BREAKPOINT = 0x21
  def QUERY_BREAKPOINTS = 0x22
  def PING            = 0xF0
  def QUERY_INFO      = 0xF1

  // Responses (target -> host)
  def ACK             = 0x80
  def NAK             = 0x81
  def REGISTERS       = 0x82
  def STACK_DATA      = 0x83
  def MEMORY_DATA     = 0x84
  def STATUS          = 0x85
  def BREAKPOINT_LIST = 0x86
  def TARGET_INFO     = 0x87
  def PONG            = 0x88

  // Async notifications
  def HALTED          = 0xC0

  // Framing
  def SYNC            = 0xA5
}

/**
 * NAK error codes.
 */
object DebugNakCode {
  def NOT_HALTED      = 0x01
  def NO_FREE_SLOTS   = 0x02
  def INVALID_REG     = 0x03
  def INVALID_ADDR    = 0x04
  def INVALID_SLOT    = 0x05
  def UNKNOWN         = 0xFF
}

/**
 * Halt reason codes (used in STATUS and HALTED responses).
 */
object DebugHaltReason {
  def MANUAL          = 0x00
  def BREAKPOINT      = 0x01
  def STEP            = 0x02
  def RESET           = 0x03
  def FAULT           = 0x04
}

/**
 * Parsed command from host.
 */
case class DebugCommand(maxPayloadBytes: Int = 1032) extends Bundle {
  val valid    = Bool()
  val msgType  = Bits(8 bits)
  val coreId   = UInt(8 bits)
  val payloadLen = UInt(16 bits)
  val payload  = Vec(Bits(8 bits), maxPayloadBytes.min(8))  // First 8 bytes of payload
}

/**
 * Debug Protocol Layer
 *
 * Parses incoming byte stream into commands and builds framed response messages.
 *
 * RX (host -> target): Detects SYNC, parses TYPE+LEN+CORE, buffers payload (up to 8 bytes
 * for header fields; streaming for WRITE_MEMORY_BLOCK), validates CRC, outputs parsed command.
 *
 * TX (target -> host): Accepts response fields, builds framed message with CRC, streams bytes.
 *
 * Streaming payload: For WRITE_MEMORY_BLOCK, payload bytes beyond the 8-byte header
 * are streamed directly to DebugController via streamByte/streamValid, 1 byte per clock.
 */
case class DebugProtocol() extends Component {
  val io = new Bundle {
    // Byte transport
    val transport = slave(DebugTransport())

    // Parsed command output
    val cmdValid   = out Bool()
    val cmdType    = out Bits(8 bits)
    val cmdCore    = out UInt(8 bits)
    val cmdPayload = out Vec(Bits(8 bits), 8)  // First 8 bytes
    val cmdPayloadLen = out UInt(16 bits)
    val cmdReady   = in Bool()  // Controller consumed the command

    // Streaming payload (for WRITE_MEMORY_BLOCK)
    val streamByte  = out Bits(8 bits)
    val streamValid = out Bool()
    val streamReady = in Bool()

    // Response output: controller fills these, protocol builds and sends the frame
    val rspValid    = in Bool()
    val rspType     = in Bits(8 bits)
    val rspCore     = in UInt(8 bits)
    val rspPayload  = in Vec(Bits(8 bits), 64)  // Up to 64 bytes payload
    val rspPayloadLen = in UInt(16 bits)
    val rspReady    = out Bool()  // Frame fully sent

    // Busy: protocol layer is sending a response (don't issue new one)
    val txBusy      = out Bool()
  }

  // ==========================================================================
  // RX State Machine: Parse incoming bytes
  // ==========================================================================

  object RxState extends SpinalEnum {
    val WAIT_SYNC, GOT_TYPE, GOT_LEN_HI, GOT_LEN_LO, GOT_CORE,
        PAYLOAD, PAYLOAD_STREAM, CHECK_CRC = newElement()
  }

  val rxState = Reg(RxState()) init(RxState.WAIT_SYNC)

  val rxType = Reg(Bits(8 bits)) init(0)
  val rxLenHi = Reg(Bits(8 bits)) init(0)
  val rxLenLo = Reg(Bits(8 bits)) init(0)
  val rxLen = (rxLenHi ## rxLenLo).asUInt
  val rxCore = Reg(UInt(8 bits)) init(0)
  val rxPayload = Vec(Reg(Bits(8 bits)) init(0), 8)
  val rxPayloadIdx = Reg(UInt(16 bits)) init(0)
  val rxCmdValid = Reg(Bool()) init(False)

  // CRC over TYPE+LEN+CORE+PAYLOAD
  val rxCrc = Crc8Maxim()
  rxCrc.io.clear := False
  rxCrc.io.enable := False
  rxCrc.io.data := 0

  // Stream payload
  val streamByteReg = Reg(Bits(8 bits)) init(0)
  val streamValidReg = Reg(Bool()) init(False)
  io.streamByte := streamByteReg
  io.streamValid := streamValidReg

  // Command output
  io.cmdValid := rxCmdValid
  io.cmdType := rxType
  io.cmdCore := rxCore
  io.cmdPayloadLen := rxLen
  for (i <- 0 until 8) {
    io.cmdPayload(i) := rxPayload(i)
  }

  // RX: always ready to accept bytes (backpressure only during stream)
  val rxReady = Bool()
  io.transport.rxByte.ready := rxReady
  rxReady := True  // Default, overridden in PAYLOAD_STREAM

  // Clear command valid when controller consumes it
  when(io.cmdReady && rxCmdValid) {
    rxCmdValid := False
  }

  // Clear stream valid when controller consumes it
  when(io.streamReady && streamValidReg) {
    streamValidReg := False
  }

  when(io.transport.rxByte.fire) {
    val byte = io.transport.rxByte.payload

    switch(rxState) {
      is(RxState.WAIT_SYNC) {
        when(byte === DebugMsgType.SYNC) {
          rxCrc.io.clear := True
          rxPayloadIdx := 0
          rxState := RxState.GOT_TYPE
        }
      }

      is(RxState.GOT_TYPE) {
        rxType := byte
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        rxState := RxState.GOT_LEN_HI
      }

      is(RxState.GOT_LEN_HI) {
        rxLenHi := byte
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        rxState := RxState.GOT_LEN_LO
      }

      is(RxState.GOT_LEN_LO) {
        rxLenLo := byte
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        rxState := RxState.GOT_CORE
      }

      is(RxState.GOT_CORE) {
        rxCore := byte.asUInt
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        // rxLen reads from rxLenHi/rxLenLo registers (updated in prior states)
        when(rxLen === 0) {
          rxState := RxState.CHECK_CRC
        }.otherwise {
          // First 8 bytes always go to header buffer (PAYLOAD state).
          // WRITE_MEMORY_BLOCK switches to streaming after byte 7 (in PAYLOAD).
          rxState := RxState.PAYLOAD
        }
      }

      is(RxState.PAYLOAD) {
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        when(rxPayloadIdx < 8) {
          rxPayload(rxPayloadIdx.resized) := byte
        }
        rxPayloadIdx := rxPayloadIdx + 1

        when(rxPayloadIdx + 1 >= rxLen) {
          // All payload received
          rxState := RxState.CHECK_CRC
        }.elsewhen(rxType === DebugMsgType.WRITE_MEMORY_BLOCK && rxPayloadIdx >= 7) {
          // Switch to streaming mode after 8-byte header
          rxState := RxState.PAYLOAD_STREAM
        }
      }

      is(RxState.PAYLOAD_STREAM) {
        // Stream payload bytes to controller (with backpressure)
        rxCrc.io.enable := True
        rxCrc.io.data := byte
        streamByteReg := byte
        streamValidReg := True
        rxPayloadIdx := rxPayloadIdx + 1

        when(rxPayloadIdx + 1 >= rxLen) {
          rxState := RxState.CHECK_CRC
        }
      }

      is(RxState.CHECK_CRC) {
        // byte is the CRC byte from host
        when(rxCrc.io.crc === byte) {
          // CRC valid - command ready
          rxCmdValid := True
        }
        // Always clear stream valid (may be stale from PAYLOAD_STREAM)
        streamValidReg := False
        // Always return to waiting for next sync
        rxState := RxState.WAIT_SYNC
      }
    }
  }

  // Backpressure during stream: only accept next byte when stream byte consumed
  when(rxState === RxState.PAYLOAD_STREAM && streamValidReg && !io.streamReady) {
    rxReady := False
  }

  // ==========================================================================
  // TX State Machine: Build and send response frames
  // ==========================================================================

  object TxState extends SpinalEnum {
    val IDLE, SEND_SYNC, SEND_TYPE, SEND_LEN_HI, SEND_LEN_LO,
        SEND_CORE, SEND_PAYLOAD, SEND_CRC = newElement()
  }

  val txState = Reg(TxState()) init(TxState.IDLE)
  val txPayloadIdx = Reg(UInt(16 bits)) init(0)

  // Latch response parameters
  val txType = Reg(Bits(8 bits)) init(0)
  val txCore = Reg(UInt(8 bits)) init(0)
  val txPayloadLen = Reg(UInt(16 bits)) init(0)
  val txPayload = Vec(Reg(Bits(8 bits)) init(0), 64)

  // TX CRC
  val txCrc = Crc8Maxim()
  txCrc.io.clear := False
  txCrc.io.enable := False
  txCrc.io.data := 0

  io.transport.txByte.valid := False
  io.transport.txByte.payload := 0
  io.rspReady := False
  io.txBusy := (txState =/= TxState.IDLE)

  switch(txState) {
    is(TxState.IDLE) {
      when(io.rspValid) {
        // Latch response parameters
        txType := io.rspType
        txCore := io.rspCore
        txPayloadLen := io.rspPayloadLen
        for (i <- 0 until 64) {
          txPayload(i) := io.rspPayload(i)
        }
        txPayloadIdx := 0
        txCrc.io.clear := True
        txState := TxState.SEND_SYNC
      }
    }

    is(TxState.SEND_SYNC) {
      io.transport.txByte.valid := True
      io.transport.txByte.payload := DebugMsgType.SYNC
      when(io.transport.txByte.fire) {
        txState := TxState.SEND_TYPE
      }
    }

    is(TxState.SEND_TYPE) {
      io.transport.txByte.valid := True
      io.transport.txByte.payload := txType
      txCrc.io.enable := io.transport.txByte.fire
      txCrc.io.data := txType
      when(io.transport.txByte.fire) {
        txState := TxState.SEND_LEN_HI
      }
    }

    is(TxState.SEND_LEN_HI) {
      val lenHi = txPayloadLen(15 downto 8).asBits
      io.transport.txByte.valid := True
      io.transport.txByte.payload := lenHi
      txCrc.io.enable := io.transport.txByte.fire
      txCrc.io.data := lenHi
      when(io.transport.txByte.fire) {
        txState := TxState.SEND_LEN_LO
      }
    }

    is(TxState.SEND_LEN_LO) {
      val lenLo = txPayloadLen(7 downto 0).asBits
      io.transport.txByte.valid := True
      io.transport.txByte.payload := lenLo
      txCrc.io.enable := io.transport.txByte.fire
      txCrc.io.data := lenLo
      when(io.transport.txByte.fire) {
        txState := TxState.SEND_CORE
      }
    }

    is(TxState.SEND_CORE) {
      val coreByte = txCore.asBits.resized
      io.transport.txByte.valid := True
      io.transport.txByte.payload := coreByte
      txCrc.io.enable := io.transport.txByte.fire
      txCrc.io.data := coreByte
      when(io.transport.txByte.fire) {
        when(txPayloadLen === 0) {
          txState := TxState.SEND_CRC
        }.otherwise {
          txState := TxState.SEND_PAYLOAD
        }
      }
    }

    is(TxState.SEND_PAYLOAD) {
      io.transport.txByte.valid := True
      io.transport.txByte.payload := txPayload(txPayloadIdx.resized)
      txCrc.io.enable := io.transport.txByte.fire
      txCrc.io.data := txPayload(txPayloadIdx.resized)
      when(io.transport.txByte.fire) {
        txPayloadIdx := txPayloadIdx + 1
        when(txPayloadIdx + 1 >= txPayloadLen) {
          txState := TxState.SEND_CRC
        }
      }
    }

    is(TxState.SEND_CRC) {
      io.transport.txByte.valid := True
      io.transport.txByte.payload := txCrc.io.crc
      when(io.transport.txByte.fire) {
        io.rspReady := True
        txState := TxState.IDLE
      }
    }
  }
}
