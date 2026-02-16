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

    // Cache configuration
    val cacheConfig = MethodCacheConfig()

    // Flattened I/O for CocoTB
    // Cache lookup interface
    val find = in Bool()
    val bc_addr = in UInt(cacheConfig.tagWidth bits)
    val bc_len = in UInt(10 bits)

    // Cache results
    val rdy = out Bool()
    val in_cache = out Bool()
    val bcstart = out UInt(cacheConfig.cacheWordAddrWidth bits)
    val alloc_block = out UInt(cacheConfig.blockBits bits)

    // External write interface
    val ext_wr_addr = in UInt(cacheConfig.cacheWordAddrWidth bits)
    val ext_wr_data = in Bits(32 bits)
    val ext_wr_en = in Bool()

    // Loading complete signal
    val load_done = in Bool()

    // JBC write outputs (directly from cache)
    val jbc_wr_addr = out UInt(cacheConfig.cacheWordAddrWidth bits)
    val jbc_wr_data = out Bits(32 bits)
    val jbc_wr_en = out Bool()

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
      val cache = MethodCache(cacheConfig)

      // Connect inputs
      cache.io.find := find
      cache.io.bcAddr := bc_addr
      cache.io.bcLen := bc_len
      cache.io.extWrAddr := ext_wr_addr
      cache.io.extWrData := ext_wr_data
      cache.io.extWrEn := ext_wr_en
      cache.io.loadDone := load_done

      // Connect outputs
      rdy := cache.io.rdy
      in_cache := cache.io.inCache
      bcstart := cache.io.bcstart
      alloc_block := cache.io.allocBlock
      jbc_wr_addr := cache.io.jbcWrAddr
      jbc_wr_data := cache.io.jbcWrData
      jbc_wr_en := cache.io.jbcWrEn
    }
  }

  config.generateVhdl(new MethodCacheTb)
  println("MethodCacheTb VHDL generated in generated/MethodCacheTb.vhd")
}
