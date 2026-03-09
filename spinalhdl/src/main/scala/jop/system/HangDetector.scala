package jop.system

import spinal.core._

/**
 * Hang detector — monitors memory controller activity and dumps debug state on hang.
 *
 * Runs in the board clock domain. All system-clock debug signals are synchronized
 * via BufferCC by the caller before being passed in.
 *
 * When memBusy stays asserted for ~335ms (at 50 MHz) or ~167ms (at 100 MHz),
 * the hang detector takes over the UART TX and sends periodic state dumps
 * via DiagUart.
 *
 * @param boardClkFreqHz Board clock frequency in Hz (for DiagUart baud rate calculation)
 * @param baudRate       Diagnostic UART baud rate (default 1 Mbaud)
 * @param hasCacheState  Whether to include cache state in diagnostics (DDR3 boards)
 * @param hasAdapterState Whether to include adapter state (DDR3 boards)
 */
case class HangDetector(
  boardClkFreqHz: Long,
  baudRate: Int = 1000000,
  hasCacheState: Boolean = false,
  hasAdapterState: Boolean = false
) extends Component {

  val io = new Bundle {
    // Inputs — already synchronized to board clock domain by caller
    val memBusy      = in Bool()
    val memState     = in UInt(5 bits)
    val pc           = in UInt(11 bits)
    val jpc          = in UInt(12 bits)
    val cacheState   = hasCacheState generate (in UInt(3 bits))
    val adapterState = hasAdapterState generate (in UInt(3 bits))

    // UART TX lines
    val jopTxd       = in Bool()       // Normal JOP UART TX (already synced)
    val muxedTxd     = out Bool()      // Output: muxed UART TX

    // Status
    val hangDetected = out Bool()
  }

  // Hang counter: count cycles while memBusy stays True
  val hangCounter = Reg(UInt(25 bits)) init(0)
  val hangDetected = Reg(Bool()) init(False)
  when(io.memBusy) {
    when(!hangCounter.msb) {
      hangCounter := hangCounter + 1
    } otherwise {
      hangDetected := True
    }
  } otherwise {
    hangCounter := 0
  }

  // Latch memState at hang detection (frozen for UART dump)
  val hangMemState = Reg(UInt(5 bits)) init(0)
  when(!hangDetected) {
    hangMemState := io.memState
  }

  // Diagnostic UART
  val diagUart = DiagUart(clockFreqHz = boardClkFreqHz.toInt, baudRate = baudRate)
  diagUart.io.trigger      := hangDetected
  diagUart.io.memState     := io.memState
  diagUart.io.pc           := io.pc
  diagUart.io.jpc          := io.jpc
  diagUart.io.cacheState   := (if (hasCacheState) io.cacheState else U(0, 3 bits))
  diagUart.io.adapterState := (if (hasAdapterState) io.adapterState else U(0, 3 bits))

  // UART TX MUX: JOP's UART during normal operation, DiagUart when hung
  io.muxedTxd := Mux(hangDetected, diagUart.io.txd, io.jopTxd)
  io.hangDetected := hangDetected
}
