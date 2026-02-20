package jop.types

import spinal.core._

/**
 * JOP Constants
 *
 * Translated from: jop_types.vhd
 * Location: /srv/git/jop/vhdl/core/jop_types.vhd
 *
 * This object contains all constant values defined in the original
 * jop_types.vhd VHDL package.
 */
object JopConstants {

  /**
   * Memory Management Unit (MMU) Instruction Constants
   *
   * These 3-bit instruction codes control memory operations:
   * - Load/store multiple words
   * - Write/read allocation
   * - Get/put field operations
   * - Static field operations
   * - Array field operations
   */

  /** Load/store multiple words */
  val STMUL = B"3'b000"

  /** Write allocate - allocate cache line on write */
  val STMWA = B"3'b001"

  /** Read allocate - allocate cache line on read */
  val STMRA = B"3'b010"

  /** Get field - read object field */
  val STGF = B"3'b011"

  /** Put field - write object field */
  val STPF = B"3'b100"

  /** Get field static - read static field */
  val STGFS = B"3'b101"

  /** Put field static - write static field */
  val STPFS = B"3'b110"

  /** Get field array - read array element */
  val STGFA = B"3'b111"

  /**
   * Method Cache Constants
   */

  /** Maximum method size in bits (2^10 = 1024 words) */
  val METHOD_SIZE_BITS = 10

  /**
   * Helper function to decode MMU instruction to string (for debugging in simulation)
   *
   * Note: This uses Int parameter which should be obtained from Bits.toInt during simulation.
   * Use only in testbenches and simulation code.
   */
  def mmuInstrToString(instr: Int): String = {
    instr match {
      case 0 => "STMUL (load/store multiple)"
      case 1 => "STMWA (write allocate)"
      case 2 => "STMRA (read allocate)"
      case 3 => "STGF (get field)"
      case 4 => "STPF (put field)"
      case 5 => "STGFS (get field static)"
      case 6 => "STPFS (put field static)"
      case 7 => "STGFA (get field array)"
      case _ => "UNKNOWN"
    }
  }
}
