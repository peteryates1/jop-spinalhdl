package jop.memory

import spinal.core._

/**
 * Method Cache Configuration
 *
 * @param jpcWidth   Cache size = 2^jpcWidth bytes (default: 11 = 2KB)
 * @param blockBits  Number of blocks = 2^blockBits (default: 3 = 8 blocks)
 * @param tagWidth   Tag width for address matching (default: 18 = 256KB address space)
 */
case class MethodCacheConfig(
  jpcWidth: Int = 11,
  blockBits: Int = 3,
  tagWidth: Int = 18
) {
  require(jpcWidth >= 11, "Min 2KB cache (JOP file format limit)")
  require(jpcWidth <= 12, "Max 4KB cache (JOP file format limit)")
  require(blockBits >= 2 && blockBits <= 4, "Block bits must be 2-4 (4-16 blocks)")
  require(tagWidth >= 16 && tagWidth <= 24, "Tag width must be 16-24 bits")

  /** Number of cache blocks */
  def blocks: Int = 1 << blockBits

  /** Block size in bytes */
  def blockSize: Int = (1 << jpcWidth) / blocks

  /** Block size in words (32-bit) */
  def blockSizeWords: Int = blockSize / 4

  /** Word address width within cache */
  def cacheWordAddrWidth: Int = jpcWidth - 2
}

/**
 * Method Cache States (matching VHDL: idle, s1, s2)
 */
object MethodCacheState extends SpinalEnum {
  val IDLE, S1, S2 = newElement()
}

/**
 * Method Cache Component
 *
 * Implements a direct-mapped method cache for JOP bytecode storage.
 * The cache maps method addresses to JBC RAM locations using a tag array.
 *
 * Matches the original VHDL cache.vhd behavior:
 * - IDLE: Ready for lookup (rdy=1)
 * - S1: Check tags, determine hit/miss (rdy=0)
 * - S2: Update tags on miss, advance next pointer
 *
 * Usage flow:
 * 1. Assert 'find' with method address (bcAddr) and length (bcLen)
 * 2. Wait for state to return to IDLE (rdy=1)
 * 3. Check 'inCache': true = hit, false = miss
 * 4. If hit: use 'bcstart' as JBC address for bytecode fetch
 * 5. If miss: load method data via external interface, then retry
 *
 * @param config Method cache configuration
 */
case class MethodCache(config: MethodCacheConfig = MethodCacheConfig()) extends Component {

  val io = new Bundle {
    // Cache lookup interface
    val find    = in Bool()                               // Start lookup
    val bcAddr  = in UInt(config.tagWidth bits)           // Method address in main memory
    val bcLen   = in UInt(10 bits)                        // Method length in words

    // Cache lookup results
    val rdy     = out Bool()                              // Lookup complete (idle state)
    val inCache = out Bool()                              // Hit (true) or miss (false)
    val bcstart = out UInt(config.cacheWordAddrWidth bits) // Cache word address

    // Block allocation for cache miss handling
    val allocBlock = out UInt(config.blockBits bits)      // Block allocated for miss

    // JBC write interface (directly connected to JBC RAM)
    val jbcWrAddr = out UInt(config.cacheWordAddrWidth bits)
    val jbcWrData = out Bits(32 bits)
    val jbcWrEn   = out Bool()

    // External JBC write input (for method loading from memory)
    val extWrAddr = in UInt(config.cacheWordAddrWidth bits)
    val extWrData = in Bits(32 bits)
    val extWrEn   = in Bool()

    // Loading complete signal (external controller signals when method is loaded)
    val loadDone = in Bool()
  }

  // ==========================================================================
  // State Machine (matching VHDL)
  // ==========================================================================

  val state = Reg(MethodCacheState()) init(MethodCacheState.IDLE)

  // ==========================================================================
  // Tag Array (one tag per block)
  // ==========================================================================

  val tags = Vec(Reg(UInt(config.tagWidth bits)) init(0), config.blocks)

  // Next block pointer (round-robin replacement)
  val nxt = Reg(UInt(config.blockBits bits)) init(0)

  // Block address register
  val blockAddr = Reg(UInt(config.blockBits bits)) init(0)

  // In-cache register
  val inCacheReg = Reg(Bool()) init(False)

  // ==========================================================================
  // Tag Comparison (parallel, like VHDL)
  // ==========================================================================

  // Use address for comparison (may be narrower than full address)
  val useAddr = io.bcAddr

  // Parallel tag comparison
  val hits = Vec(Bool(), config.blocks)
  for (i <- 0 until config.blocks) {
    hits(i) := (tags(i) === useAddr)
  }

  // Any hit?
  val hitAny = hits.reduce(_ || _)

  // Find hit index (priority encoder - first match)
  val hitIndex = UInt(config.blockBits bits)
  hitIndex := 0
  for (i <- config.blocks - 1 downto 0) {
    when(hits(i)) {
      hitIndex := U(i, config.blockBits bits)
    }
  }

  // Number of blocks needed for this method
  // (length-1) in blocks, calculated from bc_len
  val blockSizeBits = log2Up(config.blockSizeWords)
  val nrOfBlks = (io.bcLen >> blockSizeBits).resize(config.blockBits bits)

  // ==========================================================================
  // State Machine (matching VHDL idle, s1, s2)
  // ==========================================================================

  // Pass through external writes to JBC RAM
  io.jbcWrAddr := io.extWrAddr
  io.jbcWrData := io.extWrData
  io.jbcWrEn := io.extWrEn

  // rdy is combinational based on state (like VHDL)
  io.rdy := (state === MethodCacheState.IDLE)

  switch(state) {
    is(MethodCacheState.IDLE) {
      when(io.find) {
        state := MethodCacheState.S1
      }
    }

    is(MethodCacheState.S1) {
      // Check for hit
      inCacheReg := False
      blockAddr := nxt  // Default: use next block on miss

      // Check all tags in parallel (like VHDL loop)
      when(hitAny) {
        blockAddr := hitIndex
        inCacheReg := True
        state := MethodCacheState.IDLE  // Hit: return to idle
      }.otherwise {
        state := MethodCacheState.S2  // Miss: update tags
      }
    }

    is(MethodCacheState.S2) {
      // Update tag for allocated block
      tags(nxt) := useAddr

      // Advance next pointer (round-robin)
      nxt := nxt + nrOfBlks + 1

      state := MethodCacheState.IDLE
    }
  }

  // ==========================================================================
  // Outputs
  // ==========================================================================

  io.inCache := inCacheReg
  io.allocBlock := nxt

  // bcstart: block address shifted to word address
  // Block i starts at word address (i * blockSizeWords)
  io.bcstart := (blockAddr << blockSizeBits).resized
}

/**
 * MethodCache Companion Object
 */
object MethodCache {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "core/spinalhdl/generated"
    ).generate(MethodCache())
  }
}
