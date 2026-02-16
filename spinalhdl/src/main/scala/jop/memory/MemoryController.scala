package jop.memory

import spinal.core._

/**
 * Memory Controller Configuration
 */
case class MemoryControllerConfig(
  mainMemAddrWidth: Int = 18,  // 256KB address space
  mainMemSize: Int = 64 * 1024,  // 64K words
  cacheConfig: MethodCacheConfig = MethodCacheConfig(),
  jbcConfig: JbcRamConfig = JbcRamConfig()
) {
  require(mainMemAddrWidth >= 16, "Main memory address width must be >= 16 bits")
}

/**
 * Memory Controller States
 *
 * Matches the VHDL mem_sc.vhd bytecode cache states:
 * - IDLE: Ready for operations
 * - CACHE_CHECK: Wait for cache lookup to complete
 * - BC_R1: Start first memory read (on cache miss)
 * - BC_W: Wait for memory read
 * - BC_RN: Start read 2 to n
 * - BC_WR: Write to JBC RAM
 * - BC_WL: Wait for last write
 */
object MemControllerState extends SpinalEnum {
  val IDLE, CACHE_CHECK, BC_R1, BC_W, BC_RN, BC_WR, BC_WL = newElement()
}

/**
 * Memory Controller
 *
 * Integrates MainMemorySim, MethodCache, and JbcRam with automatic
 * cache miss handling. When a cache lookup results in a miss, the
 * controller automatically loads the method from main memory.
 *
 * This matches the behavior of the VHDL mem_sc.vhd component.
 *
 * Interface:
 * - find: Start cache lookup (method call)
 * - bcAddr: Method address in main memory
 * - bcLen: Method length in words
 * - rdy: Operation complete (cache hit or method loaded)
 * - inCache: True if hit, false if miss (but method now loaded)
 * - bcstart: Start address in JBC RAM
 *
 * @param config Memory controller configuration
 * @param mainMemInit Optional main memory initialization data
 */
case class MemoryController(
  config: MemoryControllerConfig = MemoryControllerConfig(),
  mainMemInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Method cache lookup interface (matches mem_in in VHDL)
    val find    = in Bool()                                    // Start cache lookup
    val bcAddr  = in UInt(config.cacheConfig.tagWidth bits)    // Method address
    val bcLen   = in UInt(10 bits)                             // Method length (words)

    // Results (matches mem_out in VHDL)
    val rdy     = out Bool()                                   // Ready for new operation
    val inCache = out Bool()                                   // Method in cache (or just loaded)
    val bcstart = out UInt(config.cacheConfig.cacheWordAddrWidth bits)  // JBC address

    // Bytecode read interface
    val jpc      = in UInt(config.jbcConfig.jpcWidth bits)
    val bytecode = out Bits(8 bits)

    // Debug outputs
    val state    = out Bits(3 bits)
    val memAddr  = out UInt(config.mainMemAddrWidth bits)
    val memData  = out Bits(32 bits)
    val loadProgress = out UInt(10 bits)
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
  // Registers
  // ==========================================================================

  val state = Reg(MemControllerState()) init(MemControllerState.IDLE)

  // Method loading registers
  val bcAddrReg = Reg(UInt(config.cacheConfig.tagWidth bits)) init(0)
  val bcLenReg = Reg(UInt(10 bits)) init(0)
  val loadCounter = Reg(UInt(10 bits)) init(0)
  val jbcWriteAddr = Reg(UInt(config.cacheConfig.cacheWordAddrWidth bits)) init(0)
  val allocBlockReg = Reg(UInt(config.cacheConfig.blockBits bits)) init(0)

  // Result registers
  val inCacheReg = Reg(Bool()) init(False)
  val bcstartReg = Reg(UInt(config.cacheConfig.cacheWordAddrWidth bits)) init(0)

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

  cache.io.find := False  // Controlled by state machine
  cache.io.bcAddr := bcAddrReg
  cache.io.bcLen := bcLenReg

  // External JBC write interface
  cache.io.extWrAddr := jbcWriteAddr
  cache.io.extWrData := mainMem.io.dout
  cache.io.extWrEn := False
  cache.io.loadDone := False

  // ==========================================================================
  // JBC RAM Interface
  // ==========================================================================

  jbcRam.io.wrAddr := cache.io.jbcWrAddr
  jbcRam.io.wrData := cache.io.jbcWrData
  jbcRam.io.wrEn := cache.io.jbcWrEn

  jbcRam.io.rdAddr := io.jpc
  io.bytecode := jbcRam.io.rdData

  // ==========================================================================
  // State Machine (matches mem_sc.vhd bytecode cache states)
  // ==========================================================================

  // Ready when idle
  io.rdy := (state === MemControllerState.IDLE)

  switch(state) {
    // ========================================================================
    // IDLE: Wait for cache lookup request
    // ========================================================================
    is(MemControllerState.IDLE) {
      when(io.find) {
        // Latch input values
        bcAddrReg := io.bcAddr
        bcLenReg := io.bcLen

        // Start cache lookup
        cache.io.find := True
        cache.io.bcAddr := io.bcAddr
        cache.io.bcLen := io.bcLen

        state := MemControllerState.CACHE_CHECK
      }
    }

    // ========================================================================
    // CACHE_CHECK: Wait for cache lookup to complete (bc_cc in VHDL)
    // ========================================================================
    is(MemControllerState.CACHE_CHECK) {
      when(cache.io.rdy) {
        // Cache lookup complete
        inCacheReg := cache.io.inCache
        bcstartReg := cache.io.bcstart
        allocBlockReg := cache.io.allocBlock

        when(cache.io.inCache) {
          // Hit: return to idle
          state := MemControllerState.IDLE
        }.otherwise {
          // Miss: start loading method
          loadCounter := 0

          // Use bcstart directly as the JBC write address (word address)
          // bcstart is based on blockAddr which is the actual allocated block
          jbcWriteAddr := cache.io.bcstart

          state := MemControllerState.BC_R1
        }
      }
    }

    // ========================================================================
    // BC_R1: Start first memory read (bc_r1 in VHDL)
    // ========================================================================
    is(MemControllerState.BC_R1) {
      // Issue first memory read
      mainMem.io.addr := bcAddrReg.resize(config.mainMemAddrWidth) + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      state := MemControllerState.BC_W
    }

    // ========================================================================
    // BC_W: Wait for memory read (bc_w in VHDL)
    // ========================================================================
    is(MemControllerState.BC_W) {
      // Keep memory read active
      mainMem.io.addr := bcAddrReg.resize(config.mainMemAddrWidth) + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      // Memory has 1-cycle latency, data ready next cycle
      state := MemControllerState.BC_RN
    }

    // ========================================================================
    // BC_RN: Read data available, prepare next read (bc_rn in VHDL)
    // ========================================================================
    is(MemControllerState.BC_RN) {
      // Keep memory active
      mainMem.io.addr := bcAddrReg.resize(config.mainMemAddrWidth) + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      state := MemControllerState.BC_WR
    }

    // ========================================================================
    // BC_WR: Write to JBC RAM (bc_wr in VHDL)
    // ========================================================================
    is(MemControllerState.BC_WR) {
      // Keep memory active for continuous reading
      mainMem.io.addr := bcAddrReg.resize(config.mainMemAddrWidth) + loadCounter
      mainMem.io.ncs := False
      mainMem.io.noe := False
      mainMem.io.nwr := True

      // Write to JBC RAM
      cache.io.extWrAddr := jbcWriteAddr
      cache.io.extWrData := mainMem.io.dout
      cache.io.extWrEn := True

      // Advance counters
      loadCounter := loadCounter + 1
      jbcWriteAddr := jbcWriteAddr + 1

      // Check if done
      when(loadCounter >= bcLenReg - 1) {
        state := MemControllerState.BC_WL
      }.otherwise {
        // Continue with next read (pipelined)
        state := MemControllerState.BC_RN
      }
    }

    // ========================================================================
    // BC_WL: Wait for last write, signal completion (bc_wl in VHDL)
    // ========================================================================
    is(MemControllerState.BC_WL) {
      // Signal that loading is complete
      cache.io.loadDone := True

      // bcstartReg is already set correctly from CACHE_CHECK
      // Method is now "in cache"
      inCacheReg := True

      state := MemControllerState.IDLE
    }
  }

  // ==========================================================================
  // Outputs
  // ==========================================================================

  io.inCache := inCacheReg
  io.bcstart := bcstartReg

  // Debug outputs
  io.state := state.asBits.resize(3)
  io.memAddr := bcAddrReg.resize(config.mainMemAddrWidth) + loadCounter
  io.memData := mainMem.io.dout
  io.loadProgress := loadCounter
}

/**
 * MemoryController Companion Object
 */
object MemoryController {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "generated"
    ).generate(MemoryController())
  }
}
