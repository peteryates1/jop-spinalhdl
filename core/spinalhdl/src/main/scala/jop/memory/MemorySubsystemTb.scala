package jop.memory

import spinal.core._
import jop.utils.JopFileLoader

/**
 * Memory Subsystem Testbench
 *
 * Wraps MemorySubsystem with external clock/reset for CocoTB testing.
 * Pre-loads main memory with smallest.jop data for testing.
 */
case class MemorySubsystemTb(
  config: MemorySubsystemConfig = MemorySubsystemConfig(
    mainMemSize = 4096  // 4K words for testing
  )
) extends Component {

  val io = new Bundle {
    // Clock and reset
    val clk = in Bool()
    val reset = in Bool()

    // Method cache lookup interface
    val find = in Bool()
    val bcAddr = in UInt(config.cacheConfig.tagWidth bits)
    val bcLen = in UInt(10 bits)
    val rdy = out Bool()
    val inCache = out Bool()
    val bcstart = out UInt(config.cacheConfig.cacheWordAddrWidth bits)

    // Bytecode read interface
    val jpc = in UInt(config.jbcConfig.jpcWidth bits)
    val bytecode = out Bits(8 bits)

    // Method loading control
    val loadStart = in Bool()
    val loadAddr = in UInt(config.mainMemAddrWidth bits)
    val loadLen = in UInt(10 bits)
    val loadBlock = in UInt(config.cacheConfig.blockBits bits)
    val loadDone = out Bool()

    // Debug outputs
    val memAddr = out UInt(config.mainMemAddrWidth bits)
    val memData = out Bits(32 bits)
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

  // Memory subsystem under test
  val memArea = new ClockingArea(tbClockDomain) {
    // Load smallest.jop data for initialization
    val jopData = JopFileLoader.jopFileToMemoryInit(
      "/home/peter/workspaces/jop/Smallest/smallest.jop",
      config.mainMemSize
    )

    val mem = MemorySubsystem(
      config = config,
      mainMemInit = Some(jopData)
    )

    // Connect IO
    mem.io.find := io.find
    mem.io.bcAddr := io.bcAddr
    mem.io.bcLen := io.bcLen
    io.rdy := mem.io.rdy
    io.inCache := mem.io.inCache
    io.bcstart := mem.io.bcstart

    mem.io.jpc := io.jpc
    io.bytecode := mem.io.bytecode

    mem.io.loadStart := io.loadStart
    mem.io.loadAddr := io.loadAddr
    mem.io.loadLen := io.loadLen
    mem.io.loadBlock := io.loadBlock
    io.loadDone := mem.io.loadDone

    io.memAddr := mem.io.memAddr
    io.memData := mem.io.memData
  }
}

/**
 * MemorySubsystemTb Companion Object
 */
object MemorySubsystemTb {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = VHDL,
      targetDirectory = "generated"
    ).generate(MemorySubsystemTb())
  }
}
