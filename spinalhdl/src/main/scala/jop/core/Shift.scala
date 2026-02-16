package jop.core

import spinal.core._

/**
 * Barrel Shifter Component
 *
 * Translated from: shift.vhd
 * Original author: Martin Schoeberl (martin@jopdesign.com)
 * Original location: /home/peter/workspaces/ai/jop/original/vhdl/core/shift.vhd
 *
 * Algorithm Description:
 * ----------------------
 * This is a multi-stage barrel shifter that performs 32-bit shift operations.
 * The shifter uses a 64-bit internal working register and cascaded shift stages
 * (16, 8, 4, 2, 1 bits) to achieve any shift amount from 0-31.
 *
 * Shift Types:
 * - 00 (ushr): Unsigned shift right - zero fill from MSB
 * - 01 (shl):  Shift left - zero fill from LSB
 * - 10 (shr):  Arithmetic shift right - sign extension from MSB
 * - 11:        Not used (undefined behavior)
 *
 * Implementation Details:
 * ----------------------
 * Left shift is cleverly implemented using the right shift logic:
 * 1. Place input data at upper bits of 64-bit register (bits 63:31)
 * 2. Invert the shift count
 * 3. Apply right shift logic
 * 4. Extract lower 32 bits
 *
 * For example, shl by N becomes ushr by (32-N) with data positioned at top.
 *
 * Timing:
 * -------
 * - Latency: 0 cycles (purely combinational)
 * - No clock input required
 * - Output changes immediately with input changes
 *
 * Resource Usage (original VHDL on ACEX1K):
 * - 227 Logic Cells
 *
 * @param width Data width (default 32 bits)
 */
case class Shift(width: Int = 32) extends Component {
  require(width == 32, "Only 32-bit width currently supported (5-bit shift amount)")

  val io = new Bundle {
    val din   = in  UInt(width bits)      // Input data to be shifted
    val off   = in  UInt(5 bits)          // Shift amount (0-31 bits)
    val shtyp = in  Bits(2 bits)          // Shift type: 00=ushr, 01=shl, 10=shr
    val dout  = out UInt(width bits)      // Shifted output data
  }

  //--------------------------------------------------------------------------
  // Shift Type Constants (matching Java bytecode shift semantics)
  //--------------------------------------------------------------------------
  // Note: Using Bits for shtyp to match VHDL entity interface exactly
  val USHR = B"2'b00"  // Unsigned (logical) shift right - iushr
  val SHL  = B"2'b01"  // Shift left                     - ishl
  val SHR  = B"2'b10"  // Arithmetic (signed) shift right - ishr

  //--------------------------------------------------------------------------
  // Internal Signals
  //--------------------------------------------------------------------------

  // 64-bit working register - holds input positioned for shifting
  val shiftin = UInt(64 bits)

  // Shift count - may be inverted for left shift
  val shiftcnt = UInt(5 bits)

  // Zero constant for padding
  val zero32 = U(0, 32 bits)

  //--------------------------------------------------------------------------
  // Shift Setup Based on Type
  //--------------------------------------------------------------------------
  // The VHDL uses a process with if-elsif for setup. In SpinalHDL we use
  // switch/is for clarity and to ensure complete coverage.

  switch(io.shtyp) {
    is(SHL) {
      // Shift Left: Position data at upper bits, invert shift count
      // VHDL: shiftin(31 downto 0) := zero32;
      //       shiftin(63 downto 31) := '0' & din;
      // This places: bit 63 = 0, bits 62:31 = din(31:0), bits 30:0 = 0
      // So the 64-bit value is: 0 ## din ## zeros(30:0)
      val upperBits = (U(0, 1 bits) @@ io.din)  // 33 bits: 0 concatenated with din
      val lowerBits = U(0, 31 bits)              // 31 bits of zeros
      shiftin := upperBits @@ lowerBits          // Total: 64 bits
      shiftcnt := ~io.off
    }
    is(SHR) {
      // Arithmetic Shift Right: Sign-extend the upper 32 bits
      when(io.din(width - 1)) {
        // Negative number: fill upper bits with 1s
        shiftin := U"32'hFFFFFFFF" @@ io.din
      } otherwise {
        // Positive number: fill upper bits with 0s
        shiftin := zero32 @@ io.din
      }
      shiftcnt := io.off
    }
    default {
      // USHR (and undefined type 11): Zero-fill upper bits
      shiftin := zero32 @@ io.din
      shiftcnt := io.off
    }
  }

  //--------------------------------------------------------------------------
  // Multi-Stage Barrel Shifter
  //--------------------------------------------------------------------------
  // Each stage conditionally shifts by a power of 2 based on the
  // corresponding bit of shiftcnt. This implements any shift 0-31.
  //
  // VHDL uses sequential variable assignments in a process, updating only
  // certain bits of the 64-bit variable. In SpinalHDL we model this with
  // intermediate signals. The key insight is that only the lower 32 bits
  // matter for the final output, so we can zero-extend the shifted portions.

  // Stage 0: Shift by 16 if shiftcnt(4) = 1
  // VHDL: shiftin(47 downto 0) := shiftin(63 downto 16)
  val s0 = UInt(64 bits)
  when(shiftcnt(4)) {
    s0 := shiftin |>> 16
  } otherwise {
    s0 := shiftin
  }

  // Stage 1: Shift by 8 if shiftcnt(3) = 1
  // VHDL: shiftin(39 downto 0) := shiftin(47 downto 8)
  val s1 = UInt(64 bits)
  when(shiftcnt(3)) {
    s1 := s0 |>> 8
  } otherwise {
    s1 := s0
  }

  // Stage 2: Shift by 4 if shiftcnt(2) = 1
  // VHDL: shiftin(35 downto 0) := shiftin(39 downto 4)
  val s2 = UInt(64 bits)
  when(shiftcnt(2)) {
    s2 := s1 |>> 4
  } otherwise {
    s2 := s1
  }

  // Stage 3: Shift by 2 if shiftcnt(1) = 1
  // VHDL: shiftin(33 downto 0) := shiftin(35 downto 2)
  val s3 = UInt(64 bits)
  when(shiftcnt(1)) {
    s3 := s2 |>> 2
  } otherwise {
    s3 := s2
  }

  // Stage 4: Shift by 1 if shiftcnt(0) = 1
  // VHDL: shiftin(31 downto 0) := shiftin(32 downto 1)
  val s4 = UInt(64 bits)
  when(shiftcnt(0)) {
    s4 := s3 |>> 1
  } otherwise {
    s4 := s3
  }

  //--------------------------------------------------------------------------
  // Output Assignment
  //--------------------------------------------------------------------------
  io.dout := s4(31 downto 0)
}

/**
 * Shift Type Enumeration for external use
 *
 * Provides named constants for the shift types used by the Shift component.
 */
object ShiftType {
  def ushr: Bits = B"2'b00"  // Unsigned shift right (zero fill)
  def shl:  Bits = B"2'b01"  // Shift left (zero fill)
  def shr:  Bits = B"2'b10"  // Arithmetic shift right (sign extend)
}

/**
 * Verilog Generation Object
 *
 * Generates synthesizable Verilog from the SpinalHDL Shift component.
 */
object ShiftVerilog extends App {
  val config = SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated/verilog"
  )

  config.generate(Shift(32))
  println("Verilog generation complete: generated/verilog/Shift.v")
}

/**
 * VHDL Generation Object
 *
 * Generates synthesizable VHDL from the SpinalHDL Shift component.
 */
object ShiftVhdl extends App {
  val config = SpinalConfig(
    mode = VHDL,
    targetDirectory = "generated/vhdl"
  )

  config.generate(Shift(32))
  println("VHDL generation complete: generated/vhdl/Shift.vhd")
}
