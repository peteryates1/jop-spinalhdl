package jop.pipeline

import spinal.core._
import jop.JumpTableData

/**
 * Interrupt/Exception Test Testbench
 *
 * Wraps BytecodeFetchStage with external clock/reset for CocoTB testing.
 * Pre-loads JBC RAM with test bytecodes.
 *
 * This testbench verifies interrupt and exception handling:
 * - Interrupt pending latch (int_pend)
 * - Exception pending latch (exc_pend)
 * - Priority muxing: Exception > Interrupt > Normal bytecode
 * - Acknowledge signals (ack_irq, ack_exc)
 *
 * Test bytecode sequence:
 * - 0x03 (iconst_0), 0x04 (iconst_1), 0x05 (iconst_2), ...
 */
case class InterruptTestTb(
  config: BytecodeFetchConfig = BytecodeFetchConfig()
) extends Component {

  val io = new Bundle {
    // Clock and reset
    val clk = in Bool()
    val reset = in Bool()

    // Control inputs
    val jpc_wr = in Bool()
    val din = in Bits(32 bits)
    val jfetch = in Bool()
    val jopdfetch = in Bool()
    val jbr = in Bool()

    // Condition flags
    val zf = in Bool()
    val nf = in Bool()
    val eq = in Bool()
    val lt = in Bool()

    // Interrupt/exception inputs
    val irq = in Bool()
    val exc = in Bool()
    val ena = in Bool()

    // Outputs
    val jpaddr = out UInt(config.pcWidth bits)
    val opd = out Bits(config.opdWidth bits)
    val jpc_out = out UInt(config.jpcWidth + 1 bits)

    // Interrupt/exception outputs
    val ack_irq = out Bool()
    val ack_exc = out Bool()

    // Debug: Expected handler addresses (for test verification)
    val sysIntAddr = out UInt(config.pcWidth bits)
    val sysExcAddr = out UInt(config.pcWidth bits)
  }

  // Create clock domain from external signals
  val tbClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // BytecodeFetchStage under test
  val bcfArea = new ClockingArea(tbClockDomain) {
    // Initialize JBC RAM with test bytecodes (2KB = 2048 bytes for jpcWidth=11)
    // Simple sequence: 0x03, 0x04, 0x05, 0x06, ... (iconst_0, iconst_1, iconst_2, ...)
    val testBytecodes = (0 until config.jbcDepth).map(i => BigInt((i + 3) & 0xFF))

    val bcf = BytecodeFetchStage(
      config = config,
      jbcInit = Some(testBytecodes)
    )

    // Connect control inputs
    bcf.io.jpc_wr := io.jpc_wr
    bcf.io.din := io.din
    bcf.io.jfetch := io.jfetch
    bcf.io.jopdfetch := io.jopdfetch
    bcf.io.jbr := io.jbr

    // Connect condition flags
    bcf.io.zf := io.zf
    bcf.io.nf := io.nf
    bcf.io.eq := io.eq
    bcf.io.lt := io.lt

    // No JBC write during interrupt testing
    bcf.io.jbcWrAddr := 0
    bcf.io.jbcWrData := 0
    bcf.io.jbcWrEn := False
    bcf.io.stall := False

    // Connect interrupt/exception inputs
    bcf.io.irq := io.irq
    bcf.io.exc := io.exc
    bcf.io.ena := io.ena

    // Connect outputs
    io.jpaddr := bcf.io.jpaddr
    io.opd := bcf.io.opd
    io.jpc_out := bcf.io.jpc_out
    io.ack_irq := bcf.io.ack_irq
    io.ack_exc := bcf.io.ack_exc
  }

  // Output expected handler addresses for test verification
  io.sysIntAddr := U(JumpTableData.sysIntAddr.toInt, config.pcWidth bits)
  io.sysExcAddr := U(JumpTableData.sysExcAddr.toInt, config.pcWidth bits)
}

/**
 * InterruptTestTb Companion Object
 */
object InterruptTestTb {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = VHDL,
      targetDirectory = "generated"
    ).generate(InterruptTestTb())
  }
}
