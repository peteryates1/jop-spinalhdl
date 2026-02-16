package jop.core

import spinal.core._

/**
 * Bit-Serial Radix-4 Multiplier
 *
 * Translated from: mul.vhd
 * Original authors: Martin Schoeberl, Wolfgang Puffitsch
 * Original location: /home/peter/workspaces/ai/jop/original/vhdl/core/mul.vhd
 *
 * Algorithm Description:
 * ----------------------
 * This is a radix-4 (processes 2 bits per cycle) bit-serial multiplier.
 * The algorithm processes the multiplier 'b' two bits at a time from LSB to MSB,
 * accumulating partial products into 'p'.
 *
 * Each cycle, depending on b(1:0):
 *   - If b(0)=1: Add 'a' to the product
 *   - If b(1)=1: Add 'a' shifted left by 1 bit to the product
 *
 * After processing each pair of bits:
 *   - 'a' shifts left by 2 positions (multiplied by 4)
 *   - 'b' shifts right by 2 positions (divided by 4)
 *
 * Timing:
 * -------
 * - Cycle 0: wr=1, operands latched, p cleared
 * - Cycles 1-17: Bit-serial multiplication (16 iterations for 32-bit operand)
 * - Cycle 18: Result available on dout
 *
 * Note: 32 bits / 2 bits per cycle = 16 iterations + 1 load cycle + 1 output cycle = 18 cycles
 * Result appears at cycle 18 after wr=1 at cycle 0.
 *
 * Port Interface:
 * ---------------
 * - clk: Clock input (directly wired to component's implicit clock)
 * - ain: 32-bit unsigned operand A
 * - bin: 32-bit unsigned operand B
 * - wr: Write enable - starts multiplication when asserted
 * - dout: 32-bit result (lower 32 bits of the 64-bit product)
 *
 * Resource Usage (original VHDL):
 * - 244 LCs (logic cells)
 *
 * @param width Data width (default 32 bits)
 */
case class Mul(width: Int = 32) extends Component {
  require(width > 0 && width % 2 == 0, "Width must be positive and even for radix-4 operation")

  val io = new Bundle {
    val ain  = in  UInt(width bits)
    val bin  = in  UInt(width bits)
    val wr   = in  Bool()
    val dout = out UInt(width bits)
  }

  //--------------------------------------------------------------------------
  // Internal Registers
  //--------------------------------------------------------------------------

  // Product accumulator - holds the running sum of partial products
  val p = Reg(UInt(width bits)) init(0)

  // Operand A - shifts left by 2 each cycle (radix-4)
  val a = Reg(UInt(width bits)) init(0)

  // Operand B - shifts right by 2 each cycle (radix-4)
  val b = Reg(UInt(width bits)) init(0)

  //--------------------------------------------------------------------------
  // Bit-Serial Multiplication Logic
  //--------------------------------------------------------------------------
  // Note: The VHDL uses a variable 'prod' for intermediate calculation.
  // In SpinalHDL, we model this with combinatorial logic feeding the register.

  when(io.wr) {
    // Write phase: Load operands and clear accumulator
    p := U(0, width bits)
    a := io.ain
    b := io.bin
  } otherwise {
    // Compute phase: Radix-4 bit-serial multiplication

    // Start with current product value
    val prod = UInt(width bits)
    prod := p

    // Add partial product based on b(0)
    // If b(0)=1: prod = prod + a
    val prod_after_b0 = UInt(width bits)
    when(b(0)) {
      prod_after_b0 := prod + a
    } otherwise {
      prod_after_b0 := prod
    }

    // Add partial product based on b(1)
    // If b(1)=1: prod = (prod(width-1 downto 1) + a(width-2 downto 0)) & prod(0)
    // This is equivalent to adding (a << 1) but preserving LSB
    val prod_final = UInt(width bits)
    when(b(1)) {
      // Upper (width-1) bits: prod_after_b0[width-1:1] + a[width-2:0]
      // LSB: prod_after_b0[0] (preserved)
      prod_final := (prod_after_b0(width - 1 downto 1) +^ a(width - 2 downto 0)).resize(width - 1) @@ prod_after_b0(0)
    } otherwise {
      prod_final := prod_after_b0
    }

    // Update product register
    p := prod_final

    // Shift a left by 2 (multiply by 4 for next radix-4 iteration)
    a := a(width - 3 downto 0) @@ U"2'b00"

    // Shift b right by 2 (expose next 2 bits of multiplier)
    b := U"2'b00" @@ b(width - 1 downto 2)
  }

  //--------------------------------------------------------------------------
  // Output Assignment
  //--------------------------------------------------------------------------
  io.dout := p
}

/**
 * Verilog/VHDL Generation Object
 *
 * Generates synthesizable HDL from the SpinalHDL Mul component.
 */
object MulVerilog extends App {
  val config = SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated/verilog",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = BOOT  // No reset signal, use BOOT initialization
    )
  )

  config.generate(Mul(32))
  println("Verilog generation complete: generated/verilog/Mul.v")
}

object MulVhdl extends App {
  val config = SpinalConfig(
    mode = VHDL,
    targetDirectory = "generated/vhdl",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = BOOT  // No reset signal, use BOOT initialization
    )
  )

  config.generate(Mul(32))
  println("VHDL generation complete: generated/vhdl/Mul.vhd")
}
