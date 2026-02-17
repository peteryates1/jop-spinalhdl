package jop.memory

import spinal.core._

/**
 * MethodCache Testbench for CocoTB
 *
 * Exposes MethodCache signals with explicit clock/reset for testing.
 */
object MethodCacheTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class MethodCacheTb extends Component {
    noIoPrefix()

    // Explicit clock and reset for CocoTB
    val clk = in Bool()
    val reset = in Bool()

    // Cache parameters
    val jpcWidth = 11
    val blockBits = 4
    val tagWidth = 18

    // Flattened I/O for CocoTB
    val find = in Bool()
    val bc_addr = in UInt(tagWidth bits)
    val bc_len = in UInt(10 bits)

    val rdy = out Bool()
    val in_cache = out Bool()
    val bcstart = out UInt((jpcWidth - 2) bits)

    // Create explicit clock domain
    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val cache = MethodCache(jpcWidth, blockBits, tagWidth)

      cache.io.find := find
      cache.io.bcAddr := bc_addr
      cache.io.bcLen := bc_len

      rdy := cache.io.rdy
      in_cache := cache.io.inCache
      bcstart := cache.io.bcStart
    }
  }

  config.generateVhdl(new MethodCacheTb)
  println("MethodCacheTb VHDL generated in generated/MethodCacheTb.vhd")
}
