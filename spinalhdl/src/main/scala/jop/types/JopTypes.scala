package jop.types

import spinal.core._
import spinal.lib._
import jop.JopConfig

/**
 * JOP Type Definitions
 *
 * Translated from: jop_types.vhd
 * Location: /srv/git/jop/vhdl/core/jop_types.vhd
 *
 * This package defines all the record types (Bundles) used for
 * communication between JOP processor modules.
 *
 * All record types from VHDL are translated to SpinalHDL Bundle classes.
 */

/**
 * Memory Management Unit Interface - Input
 *
 * Controls memory operations from the processor core to the MMU.
 *
 * @see mem_in_type in jop_types.vhd
 */
case class MemIn() extends Bundle {
  val bcRd      = Bool()        // Bytecode read request
  val bcWr      = Bool()        // Bytecode write request
  val bcAddrWr  = Bool()        // Bytecode address write
  val cinval    = Bool()        // Cache invalidate
  val instr     = Bits(3 bits)  // MMU instruction (STMUL, STMWA, etc.)
}

/**
 * Memory Management Unit Interface - Output
 *
 * Returns data and status from the MMU to the processor core.
 *
 * @see mem_out_type in jop_types.vhd
 */
case class MemOut() extends Bundle {
  val bcOut = Bits(32 bits)  // Bytecode output data
  val bsy   = Bool()         // Busy signal
}

/**
 * Exception Information
 *
 * Tracks which exception has occurred in the processor.
 *
 * @see exception_type in jop_types.vhd
 */
case class Exception() extends Bundle {
  val np = Bool() // Null pointer exception
  val ab = Bool() // Array bounds exception
  val ii = Bool() // Invalid instruction exception
}

/**
 * Bytecode Fetch IRQ
 *
 * Interrupt request from the bytecode fetch stage.
 *
 * @see irq_bcf_type in jop_types.vhd
 */
case class IrqBcf() extends Bundle {
  val irq = Bool() // Interrupt request
  val ena = Bool() // Interrupt enable
}

/**
 * IRQ Acknowledge
 *
 * Acknowledgment signal for interrupt handling.
 *
 * @see irq_ack_type in jop_types.vhd
 */
case class IrqAck() extends Bundle {
  val ack = Bool() // Acknowledge signal
}

/**
 * Serial Port Input
 *
 * Input signals from serial port interface.
 *
 * @see ser_in_type in jop_types.vhd
 */
case class SerIn() extends Bundle {
  val rxd  = Bool() // Receive data
  val ncts = Bool() // Not clear to send (active low)
  val ndsr = Bool() // Not data set ready (active low)
}

/**
 * Serial Port Output
 *
 * Output signals to serial port interface.
 *
 * @see ser_out_type in jop_types.vhd
 */
case class SerOut() extends Bundle {
  val txd  = Bool() // Transmit data
  val nrts = Bool() // Not ready to send (active low)
}

/**
 * Object Cache Input
 *
 * Input interface to the object field cache.
 * The object cache caches frequently accessed object fields.
 *
 * @param config JOP configuration with cache parameters
 * @see ocache_in_type in jop_types.vhd
 */
case class OCacheIn(config: JopConfig) extends Bundle {
  val address = Bits(config.ocacheAddrBits bits)      // Memory address (23 bits default)
  val index   = Bits(config.ocacheMaxIndexBits bits)  // Field index (8 bits default)
  val wrdata  = Bits(32 bits)                         // Write data
  val wren    = Bool()                                // Write enable
  val rden    = Bool()                                // Read enable
}

/**
 * Array Cache Input
 *
 * Input interface to the array element cache.
 * The array cache caches frequently accessed array elements.
 *
 * @param config JOP configuration with cache parameters
 * @see acache_in_type in jop_types.vhd
 */
case class ACacheIn(config: JopConfig) extends Bundle {
  val address = Bits(config.acacheAddrBits bits)      // Memory address (23 bits default)
  val index   = Bits(config.acacheMaxIndexBits bits)  // Array index (23 bits default)
  val wrdata  = Bits(32 bits)                         // Write data
  val wren    = Bool()                                // Write enable
  val rden    = Bool()                                // Read enable
}

/**
 * Companion object with helper methods
 */
object JopTypes {

  /**
   * Create a MemIn bundle with all signals initialized to default (safe) values
   */
  def initMemIn(): MemIn = {
    val m = MemIn()
    m.bcRd      := False
    m.bcWr      := False
    m.bcAddrWr  := False
    m.cinval    := False
    m.instr     := B"3'b000" // STMUL
    m
  }

  /**
   * Create an Exception bundle with all exceptions cleared
   */
  def initException(): Exception = {
    val e = Exception()
    e.np := False
    e.ab := False
    e.ii := False
    e
  }
}
