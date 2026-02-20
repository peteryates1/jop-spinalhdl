package jop.system

import spinal.core._

/**
 * Diagnostic UART TX for DDR3 hang detection.
 *
 * When triggered, latches debug state and sends a formatted message
 * over UART, repeating every ~200ms:
 *
 *   \r\nHANG ms=XX pc=XXX jpc=XXXX cs=X as=X\r\n
 *
 * Fields:
 *   ms  = BmbMemoryController state (5 bits, 2 hex chars)
 *   pc  = Microcode PC (11 bits, 3 hex chars)
 *   jpc = Java bytecode PC (12 bits, 4 hex chars)
 *   cs  = LruCacheCore state (3 bits, 1 hex char)
 *   as  = CacheToMigAdapter state (3 bits, 1 hex char)
 *
 * State decode table for ms field:
 *   0=IDLE, 1=READ_WAIT, 2=WRITE_WAIT, 3=IAST_WAIT,
 *   4=HANDLE_READ, 5=HANDLE_WAIT, 6=HANDLE_CALC,
 *   7=HANDLE_ACCESS, 8=HANDLE_DATA_WAIT,
 *   9=HANDLE_BOUND_READ, A=HANDLE_BOUND_WAIT,
 *   B=NP_EXC, C=AB_EXC,
 *   D=BC_CACHE_CHECK, E=BC_FILL_R1, F=BC_FILL_LOOP, 10=BC_FILL_CMD,
 *   11=CP_SETUP, 12=CP_READ, 13=CP_READ_WAIT, 14=CP_WRITE, 15=CP_STOP,
 *   16=GS_READ, 17=PS_WRITE, 18=LAST
 *
 * State decode for cs (LruCacheCore):
 *   0=IDLE, 1=ISSUE_EVICT, 2=WAIT_EVICT_RSP, 3=ISSUE_REFILL, 4=WAIT_REFILL_RSP
 *
 * State decode for as (CacheToMigAdapter):
 *   0=IDLE, 1=ISSUE_WRITE, 2=ISSUE_READ, 3=WAIT_READ
 */
case class DiagUart(clockFreqHz: Int = 100000000, baudRate: Int = 1000000) extends Component {

  val io = new Bundle {
    val trigger      = in Bool()
    val memState     = in UInt(5 bits)
    val pc           = in UInt(11 bits)
    val jpc          = in UInt(12 bits)
    val cacheState   = in UInt(3 bits)
    val adapterState = in UInt(3 bits)
    val txd          = out Bool()
  }

  // ========================================================================
  // Baud Rate Generator
  // ========================================================================

  val baudDiv = clockFreqHz / baudRate
  val baudCounter = Reg(UInt(log2Up(baudDiv) bits)) init(0)
  val baudTick = baudCounter === (baudDiv - 1)

  when(baudTick) {
    baudCounter := 0
  } otherwise {
    baudCounter := baudCounter + 1
  }

  // ========================================================================
  // Latched Debug Values (frozen at hang detection)
  // ========================================================================

  val latchMs  = Reg(UInt(5 bits)) init(0)
  val latchPc  = Reg(UInt(11 bits)) init(0)
  val latchJpc = Reg(UInt(12 bits)) init(0)
  val latchCs  = Reg(UInt(3 bits)) init(0)
  val latchAs  = Reg(UInt(3 bits)) init(0)

  // ========================================================================
  // UART TX Shift Register
  // ========================================================================
  // Frame: {stop(1), data[7:0], start(0)} — shifted out LSB first

  val txShift = Reg(Bits(10 bits)) init(B"1111111111")
  val bitCounter = Reg(UInt(4 bits)) init(0)

  io.txd := txShift(0)

  // ========================================================================
  // Message State
  // ========================================================================

  val msgLen = 40
  val byteIdx = Reg(UInt(6 bits)) init(0)

  // Delay counter (~200ms between message repeats)
  val delayMax = clockFreqHz / 5
  val delayCounter = Reg(UInt(log2Up(delayMax) bits)) init(0)

  // ========================================================================
  // State Machine
  // ========================================================================

  object State extends SpinalEnum {
    val WAIT_TRIGGER, LATCH, LOAD_BYTE, SHIFTING, DELAY = newElement()
  }

  val state = Reg(State()) init(State.WAIT_TRIGGER)

  // ========================================================================
  // Hex Nibble to ASCII
  // ========================================================================

  def hexAscii(nibble: UInt): Bits = {
    val n = nibble.resize(8 bits)
    val base = Mux(nibble.resize(4) < 10, U(0x30, 8 bits), U(0x37, 8 bits))
    (n + base).asBits
  }

  // ========================================================================
  // Message Byte Lookup
  // ========================================================================
  //
  // Message: \r\nHANG ms=XX pc=XXX jpc=XXXX cs=X as=X\r\n
  //
  // Position map:
  //  0:\r  1:\n  2:H  3:A  4:N  5:G  6:' '
  //  7:m   8:s   9:=  10:ms[4]  11:ms[3:0]  12:' '
  // 13:p  14:c  15:=  16:pc[10:8]  17:pc[7:4]  18:pc[3:0]  19:' '
  // 20:j  21:p  22:c  23:=  24:'0'  25:jpc[11:8]  26:jpc[7:4]  27:jpc[3:0]  28:' '
  // 29:c  30:s  31:=  32:cs[2:0]  33:' '
  // 34:a  35:s  36:=  37:as[2:0]  38:\r  39:\n

  val msgByte = Bits(8 bits)
  msgByte := B(0x20, 8 bits)  // default: space

  switch(byteIdx) {
    is(0)  { msgByte := B(0x0D, 8 bits) }  // \r
    is(1)  { msgByte := B(0x0A, 8 bits) }  // \n
    is(2)  { msgByte := B(0x48, 8 bits) }  // H
    is(3)  { msgByte := B(0x41, 8 bits) }  // A
    is(4)  { msgByte := B(0x4E, 8 bits) }  // N
    is(5)  { msgByte := B(0x47, 8 bits) }  // G
    // 6: space (default)
    is(7)  { msgByte := B(0x6D, 8 bits) }  // m
    is(8)  { msgByte := B(0x73, 8 bits) }  // s
    is(9)  { msgByte := B(0x3D, 8 bits) }  // =
    is(10) { msgByte := hexAscii(latchMs(4 downto 4).resize(4)) }
    is(11) { msgByte := hexAscii(latchMs(3 downto 0)) }
    // 12: space (default)
    is(13) { msgByte := B(0x70, 8 bits) }  // p
    is(14) { msgByte := B(0x63, 8 bits) }  // c
    is(15) { msgByte := B(0x3D, 8 bits) }  // =
    is(16) { msgByte := hexAscii(latchPc(10 downto 8).resize(4)) }
    is(17) { msgByte := hexAscii(latchPc(7 downto 4)) }
    is(18) { msgByte := hexAscii(latchPc(3 downto 0)) }
    // 19: space (default)
    is(20) { msgByte := B(0x6A, 8 bits) }  // j
    is(21) { msgByte := B(0x70, 8 bits) }  // p
    is(22) { msgByte := B(0x63, 8 bits) }  // c
    is(23) { msgByte := B(0x3D, 8 bits) }  // =
    is(24) { msgByte := B(0x30, 8 bits) }  // '0' (jpc top nibble, always 0 for 12-bit)
    is(25) { msgByte := hexAscii(latchJpc(11 downto 8)) }
    is(26) { msgByte := hexAscii(latchJpc(7 downto 4)) }
    is(27) { msgByte := hexAscii(latchJpc(3 downto 0)) }
    // 28: space (default)
    is(29) { msgByte := B(0x63, 8 bits) }  // c
    is(30) { msgByte := B(0x73, 8 bits) }  // s
    is(31) { msgByte := B(0x3D, 8 bits) }  // =
    is(32) { msgByte := hexAscii(latchCs.resize(4)) }
    // 33: space (default)
    is(34) { msgByte := B(0x61, 8 bits) }  // a
    is(35) { msgByte := B(0x73, 8 bits) }  // s
    is(36) { msgByte := B(0x3D, 8 bits) }  // =
    is(37) { msgByte := hexAscii(latchAs.resize(4)) }
    is(38) { msgByte := B(0x0D, 8 bits) }  // \r
    is(39) { msgByte := B(0x0A, 8 bits) }  // \n
  }

  // ========================================================================
  // State Machine Logic
  // ========================================================================

  switch(state) {

    is(State.WAIT_TRIGGER) {
      when(io.trigger) {
        state := State.LATCH
      }
    }

    is(State.LATCH) {
      latchMs  := io.memState
      latchPc  := io.pc
      latchJpc := io.jpc
      latchCs  := io.cacheState
      latchAs  := io.adapterState
      byteIdx  := 0
      state    := State.LOAD_BYTE
    }

    is(State.LOAD_BYTE) {
      // Build UART frame: bit[0]=start(0), bit[8:1]=data, bit[9]=stop(1)
      txShift := B"1" ## msgByte ## B"0"
      bitCounter := 0
      baudCounter := 0  // Reset baud timing for precise bit edges
      state := State.SHIFTING
    }

    is(State.SHIFTING) {
      when(baudTick) {
        when(bitCounter === 9) {
          // Stop bit held for full baud period — byte complete
          byteIdx := byteIdx + 1
          when(byteIdx === (msgLen - 1)) {
            state := State.DELAY
            delayCounter := 0
          } otherwise {
            state := State.LOAD_BYTE
          }
        } otherwise {
          // Shift right, fill MSB with 1 (idle level)
          txShift := B"1" ## txShift(9 downto 1)
          bitCounter := bitCounter + 1
        }
      }
    }

    is(State.DELAY) {
      delayCounter := delayCounter + 1
      when(delayCounter === (delayMax - 1)) {
        byteIdx := 0
        state := State.LOAD_BYTE
      }
    }
  }
}
