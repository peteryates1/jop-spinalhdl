package jop.memory

import spinal.core._

/**
 * Memory Subsystem Configuration
 *
 * Combines MainMemory, MethodCache, and JbcRam configurations
 */
case class MemorySubsystemConfig(
  mainMemAddrWidth: Int = 18,  // 256KB address space
  mainMemSize: Int = 64 * 1024,  // 64K words
  cacheConfig: MethodCacheConfig = MethodCacheConfig(),
  jbcConfig: JbcRamConfig = JbcRamConfig()
) {
  require(mainMemAddrWidth >= 16, "Main memory address width must be >= 16 bits")
}

/**
 * Memory Subsystem
 *
 * Integrates MainMemorySim, MethodCache, and JbcRam for simulation/testing.
 * Provides a unified interface for:
 * - Loading methods from main memory into cache
 * - Reading bytecodes from JBC RAM
 *
 * This component is designed for simulation and testing of the bytecode
 * fetch pipeline with real JOP program data.
 */
case class MemorySubsystem(
  config: MemorySubsystemConfig = MemorySubsystemConfig(),
  mainMemInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Method cache lookup interface
    val find = in Bool()
    val bcAddr = in UInt(config.cacheConfig.tagWidth bits)
    val bcLen = in UInt(10 bits)
    val rdy = out Bool()
    val inCache = out Bool()
    val bcstart = out UInt(config.cacheConfig.cacheWordAddrWidth bits)

    // Bytecode read interface (from JBC RAM)
    val jpc = in UInt(config.jbcConfig.jpcWidth bits)
    val bytecode = out Bits(8 bits)

    // Method loading control
    val loadStart = in Bool()          // Start loading a method
    val loadAddr = in UInt(config.mainMemAddrWidth bits)  // Main memory address
    val loadLen = in UInt(10 bits)     // Length in words
    val loadBlock = in UInt(config.cacheConfig.blockBits bits)  // Target cache block
    val loadDone = out Bool()          // Loading complete

    // Debug: main memory access
    val memAddr = out UInt(config.mainMemAddrWidth bits)
    val memData = out Bits(32 bits)
  }

  // ==========================================================================
  // Subcomponents
  // ==========================================================================

  val mainMem = MainMemorySim(
    config = MainMemorySimConfig(
      addrWidth = config.mainMemAddrWidth,
      memSize = config.mainMemSize
    ),
    init = mainMemInit
  )

  val cache = MethodCache(config.cacheConfig)

  val jbcRam = JbcRam(config.jbcConfig)

  // ==========================================================================
  // Method Loading State Machine
  // ==========================================================================

  object LoadState extends SpinalEnum {
    val IDLE, READ_REQ, WRITE_DATA, DONE = newElement()
  }

  val loadState = Reg(LoadState()) init(LoadState.IDLE)
  val loadCounter = Reg(UInt(10 bits)) init(0)
  val loadAddrReg = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
  val loadLenReg = Reg(UInt(10 bits)) init(0)
  val loadBlockReg = Reg(UInt(config.cacheConfig.blockBits bits)) init(0)

  // JBC write address within block
  val jbcWriteAddr = Reg(UInt(config.cacheConfig.cacheWordAddrWidth bits)) init(0)

  // Latched data from main memory
  val memDataLatched = Reg(Bits(32 bits)) init(0)

  // ==========================================================================
  // Main Memory Interface
  // ==========================================================================

  // Default: not accessing memory
  mainMem.io.addr := 0
  mainMem.io.din := 0
  mainMem.io.ncs := True
  mainMem.io.noe := True
  mainMem.io.nwr := True

  // ==========================================================================
  // Method Cache Interface
  // ==========================================================================

  cache.io.find := io.find
  cache.io.bcAddr := io.bcAddr
  cache.io.bcLen := io.bcLen

  io.rdy := cache.io.rdy
  io.inCache := cache.io.inCache
  io.bcstart := cache.io.bcstart

  // External JBC write interface (from loader)
  cache.io.extWrAddr := jbcWriteAddr
  cache.io.extWrData := mainMem.io.dout
  cache.io.extWrEn := False  // Default off
  cache.io.loadDone := False

  // ==========================================================================
  // JBC RAM Interface
  // ==========================================================================

  // Connect cache JBC write to JBC RAM
  jbcRam.io.wrAddr := cache.io.jbcWrAddr
  jbcRam.io.wrData := cache.io.jbcWrData
  jbcRam.io.wrEn := cache.io.jbcWrEn

  // Bytecode read
  jbcRam.io.rdAddr := io.jpc
  io.bytecode := jbcRam.io.rdData

  // ==========================================================================
  // Loading State Machine
  //
  // Uses a 2-phase approach to handle main memory read latency:
  // 1. READ_REQ: Issue memory read request
  // 2. WRITE_DATA: Read data is available, write to JBC RAM
  // ==========================================================================

  io.loadDone := (loadState === LoadState.DONE)

  switch(loadState) {
    is(LoadState.IDLE) {
      when(io.loadStart) {
        loadAddrReg := io.loadAddr
        loadLenReg := io.loadLen
        loadBlockReg := io.loadBlock
        loadCounter := 0

        // Calculate JBC write address from block number
        val blockSizeBits = log2Up(config.cacheConfig.blockSizeWords)
        jbcWriteAddr := (io.loadBlock << blockSizeBits).resized

        loadState := LoadState.READ_REQ
      }
    }

    is(LoadState.READ_REQ) {
      // Issue memory read request
      mainMem.io.addr := loadAddrReg + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      // Next cycle: data will be available
      loadState := LoadState.WRITE_DATA
    }

    is(LoadState.WRITE_DATA) {
      // Keep memory enabled so dout is valid
      mainMem.io.addr := loadAddrReg + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      // Memory data is now available - latch it and write to JBC
      memDataLatched := mainMem.io.dout

      // Write to JBC RAM
      cache.io.extWrAddr := jbcWriteAddr
      cache.io.extWrData := mainMem.io.dout
      cache.io.extWrEn := True

      // Advance counters
      loadCounter := loadCounter + 1
      jbcWriteAddr := jbcWriteAddr + 1

      // Check if done
      when(loadCounter >= loadLenReg - 1) {
        cache.io.loadDone := True
        loadState := LoadState.DONE
      }.otherwise {
        // Continue with next read
        loadState := LoadState.READ_REQ
      }
    }

    is(LoadState.DONE) {
      // Wait for acknowledgment (loadStart to go low)
      when(!io.loadStart) {
        loadState := LoadState.IDLE
      }
    }
  }

  // ==========================================================================
  // Debug Outputs
  // ==========================================================================

  io.memAddr := loadAddrReg + loadCounter
  io.memData := mainMem.io.dout
}

/**
 * MemorySubsystem Companion Object
 */
object MemorySubsystem {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "generated"
    ).generate(MemorySubsystem())
  }
}
