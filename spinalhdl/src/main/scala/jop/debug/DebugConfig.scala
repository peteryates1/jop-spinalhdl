package jop.debug

/**
 * Debug subsystem configuration.
 *
 * @param numBreakpoints Hardware breakpoint slots per core (0 = no breakpoints)
 * @param baudRate       Debug UART baud rate in Hz
 * @param hasMemAccess   Enable BMB master for READ/WRITE_MEMORY commands
 */
case class DebugConfig(
  numBreakpoints: Int = 4,
  baudRate: Int = 1000000,
  hasMemAccess: Boolean = true
) {
  require(numBreakpoints >= 0 && numBreakpoints <= 8, "numBreakpoints must be 0-8")
  require(baudRate > 0, "baudRate must be positive")
}
