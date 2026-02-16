package jop.memory

import spinal.core._
import jop.utils.JopFileLoader

/**
 * Memory Controller Testbench
 *
 * Wraps MemoryController with external clock/reset for CocoTB testing.
 * Pre-loads main memory with smallest.jop data for testing.
 *
 * This testbench verifies the automatic cache miss handling:
 * - Cache lookup returns hit/miss
 * - On miss, controller automatically loads method from main memory
 * - Bytecodes can be read from JBC RAM
 */
case class MemoryControllerTb(
  config: MemoryControllerConfig = MemoryControllerConfig(
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

    // Results
    val rdy = out Bool()
    val inCache = out Bool()
    val bcstart = out UInt(config.cacheConfig.cacheWordAddrWidth bits)

    // Bytecode read interface
    val jpc = in UInt(config.jbcConfig.jpcWidth bits)
    val bytecode = out Bits(8 bits)

    // Debug outputs
    val state = out Bits(3 bits)
    val memAddr = out UInt(config.mainMemAddrWidth bits)
    val memData = out Bits(32 bits)
    val loadProgress = out UInt(10 bits)
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

  // Memory controller under test
  val memArea = new ClockingArea(tbClockDomain) {
    // Load smallest.jop data for initialization
    val jopData = JopFileLoader.jopFileToMemoryInit(
      "/home/peter/workspaces/jop/Smallest/smallest.jop",
      config.mainMemSize
    )

    val ctrl = MemoryController(
      config = config,
      mainMemInit = Some(jopData)
    )

    // Connect IO
    ctrl.io.find := io.find
    ctrl.io.bcAddr := io.bcAddr
    ctrl.io.bcLen := io.bcLen

    io.rdy := ctrl.io.rdy
    io.inCache := ctrl.io.inCache
    io.bcstart := ctrl.io.bcstart

    ctrl.io.jpc := io.jpc
    io.bytecode := ctrl.io.bytecode

    io.state := ctrl.io.state
    io.memAddr := ctrl.io.memAddr
    io.memData := ctrl.io.memData
    io.loadProgress := ctrl.io.loadProgress
  }
}

/**
 * MemoryControllerTb Companion Object
 */
object MemoryControllerTb {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = VHDL,
      targetDirectory = "generated"
    ).generate(MemoryControllerTb())
  }
}
