package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import jop.io.JopUartCtrl

/**
 * Out-of-band reset trigger on the UART receive line.
 *
 * WHY A BREAK AND NOT A MAGIC BYTE SEQUENCE. A break is a FRAMING violation,
 * not a data pattern, so no stream of bytes can forge one. At 8N1 with no
 * parity -- what `jop.io.Uart` configures -- the longest run of low a valid
 * frame can contain is the start bit plus eight zero data bits, nine bit-times,
 * and the stop bit is then forced high. Back-to-back 0x00 bytes give 9 low,
 * 1 high, 9 low. `UartCtrlRx` declares a break at
 * `rxSamplePerBit * (1+8+1+2+1)` = 13 bit-times, which is unreachable. So an
 * application reading arbitrary binary from the host can NEVER be reset by the
 * data it receives. That is a guarantee, where "this 4-byte sequence is
 * unlikely" would only have been a probability.
 *
 * WHAT A BREAK ALONE DOES NOT COVER, and why a confirmation byte follows it:
 *
 *   1. A floating or unpowered RX line reads LOW, which is an infinite break.
 *      Unplug the cable and the board would reset forever.
 *   2. Some USB-serial bridges pull the line low or emit a break when the host
 *      opens or closes the port -- the classic DTR-reset hazard. download.py
 *      opens the port on every invocation.
 *   3. At a mismatched baud a 0x00 can present ~18 bit-times of low to this
 *      receiver and read as a break.
 *
 * Requiring break -> line returns to idle -> next byte == MAGIC kills all
 * three: a dead line never delivers the byte, an open/close glitch has no byte
 * behind it, and at the wrong baud the byte does not decode as MAGIC.
 *
 * LIVES OUTSIDE THE RESET IT DRIVES. This is instantiated in the same
 * `resetKind = BOOT` domain as the reset generator, taps the `ser_rxd` PIN
 * directly, and is deliberately NOT the core's UART peripheral: it must keep
 * running while the thing it resets is held in reset, and it must not care
 * what state the core left its own UART in.
 *
 * Costs no pin. The button path (`JopTop`, boards that map a SWITCH "reset")
 * is separate and complementary -- it works when the serial link itself is
 * the thing that is wedged.
 *
 * SIZING THE CONFIRMATION WINDOW. It is wall-clock, not bit-times. The host
 * cannot place the byte hard against the break: `send_break()` returns, then
 * Python makes another write syscall, then the byte waits on a USB frame. On
 * CDC-ACM that is single-digit milliseconds -- thousands of bit-times at
 * 2 Mbaud. A window sized in bit-times looks generous and is in fact
 * unreachable, so the trigger would simply never fire on real hardware while
 * passing every simulation that drove the byte immediately. 100 ms is far
 * above the host's jitter and far below any plausible interval at which a
 * stray break happens to be followed by an unrelated 'R'.
 *
 * @param baudRate  must match the system UART, since this decodes the byte
 * @param clkFreq   clock feeding this detector
 * @param magic     confirmation byte, default 'R'
 * @param windowMs  how long after a break the magic byte is accepted
 */
case class UartResetEscape(baudRate: Int,
                           clkFreq: HertzNumber,
                           magic: Int = 0x52,
                           windowMs: Double = 100.0) extends Component {
  val io = new Bundle {
    val rxd          = in  Bool()
    /** One-cycle pulse: host asked for a reset. */
    val resetRequest = out Bool()
  }

  // The SAME controller and generics as jop.io.Uart, so the break threshold and
  // bit timing here are identical to the peripheral the host is already talking
  // to. If these ever diverge the escape decodes at a different rate from the
  // UART the host is tuned for, and the confirmation byte silently stops
  // matching -- so take the timing from one place, not two.
  val ctrl = JopUartCtrl(baudRate, clkFreq, UartCtrlGenerics(
    preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
  ))
  ctrl.io.writeBreak := False
  ctrl.io.write.valid := False
  ctrl.io.write.payload := 0
  ctrl.io.rxd := io.rxd
  ctrl.io.read.ready := True

  // Finite, so a stray break cannot leave the trigger armed indefinitely
  // waiting for an unrelated 'R' from a later application. See the note above
  // on why this is milliseconds and not bit-times.
  val windowCycles = (clkFreq.toBigDecimal * BigDecimal(windowMs) / 1000).toBigInt
  val timeout = Reg(UInt(log2Up(windowCycles + 1) bits)) init(0)

  val armed = Reg(Bool()) init(False)
  val sawBreak = Reg(Bool()) init(False)
  val fire = False
  io.resetRequest := fire

  // UartCtrlRx gates start-bit detection on !break, so no frames are decoded
  // while the line is held low; `read.valid` below is always a real byte.
  when(ctrl.io.readBreak) {
    sawBreak := True
    armed := False
  } elsewhen(sawBreak) {
    // Break released and the line is idle again -- now accept one byte.
    sawBreak := False
    armed := True
    timeout := windowCycles
  }

  when(armed) {
    when(timeout === 0) {
      armed := False
    } otherwise {
      timeout := timeout - 1
    }
    when(ctrl.io.read.valid) {
      armed := False
      when(ctrl.io.read.payload === magic) { fire := True }
    }
  }
}
