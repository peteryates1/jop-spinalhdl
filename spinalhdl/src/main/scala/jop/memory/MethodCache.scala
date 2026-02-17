package jop.memory

import spinal.core._

/**
 * Method Cache Tag Lookup
 *
 * Ports the VHDL `mcache` entity from cache.vhd to SpinalHDL.
 * Manages method cache tags only — JBC RAM IS the data storage.
 *
 * The cache divides JBC RAM into blocks and maintains a tag per block.
 * On a lookup (find pulse), it performs a parallel tag check:
 *   - Hit (2 cycles):  find → S1(match) → IDLE with rdy=true, inCache=true
 *   - Miss (3 cycles): find → S1(no match) → S2(update tags) → IDLE with rdy=true, inCache=false
 *
 * FIFO replacement: on miss, the next available block(s) are allocated
 * and displaced tags are cleared.
 *
 * @param jpcWidth   Java PC width (11 = 2KB cache)
 * @param blockBits  log2(number of blocks) (4 = 16 blocks, each 32 words)
 * @param tagWidth   Tag width for method address comparison (18 bits)
 */
case class MethodCache(
  jpcWidth: Int = 11,
  blockBits: Int = 4,
  tagWidth: Int = 18
) extends Component {

  assert(jpcWidth >= 11, "Minimum method cache size is 2KB")

  val methodSizeBits = 10  // METHOD_SIZE_BITS from VHDL jop_types.vhd
  val blocks = 1 << blockBits
  val blockWordBits = jpcWidth - 2 - blockBits  // word offset bits within a block

  val io = new Bundle {
    val bcLen   = in UInt(methodSizeBits bits)   // method length in words
    val bcAddr  = in UInt(tagWidth bits)          // method memory address tag
    val find    = in Bool()                       // trigger lookup (one-cycle pulse)
    val bcStart = out UInt((jpcWidth - 2) bits)   // word address in JBC RAM
    val rdy     = out Bool()                      // combinational: true when idle
    val inCache = out Bool()                      // registered: true if last lookup hit
  }

  // ==========================================================================
  // State Machine
  // ==========================================================================

  object State extends SpinalEnum {
    val IDLE, S1, S2 = newElement()
  }
  val state = Reg(State()) init(State.IDLE)

  // ==========================================================================
  // Internal State
  // ==========================================================================

  // Tag array: one tag per block (tag=0 means invalid, matching VHDL)
  val tag = Vec(Reg(Bits(tagWidth bits)), blocks)
  tag.foreach(_.init(B(0, tagWidth bits)))

  // FIFO replacement pointer (next block to allocate on miss)
  val nxt = Reg(UInt(blockBits bits)) init(0)

  // Block address: which block was hit (or assigned on miss)
  val blockAddr = Reg(UInt(blockBits bits)) init(0)

  // In-cache flag (registered result of last lookup)
  val inCache = Reg(Bool()) init(False)

  // Number of blocks this method spans minus 1 (0 = one block)
  // Matches VHDL: resize(bc_len(METHOD_SIZE_BITS-1 downto jpc_width-2-block_bits), block_bits)
  val nrOfBlks = UInt(blockBits bits)
  nrOfBlks := io.bcLen(methodSizeBits - 1 downto blockWordBits).resized

  // Tag comparison value
  val useAddr = io.bcAddr.asBits

  // Pre-computed clear mask (registered, updated every cycle)
  // For each block position j, set clrVal(j) if j is within [nxt, nxt+nrOfBlks]
  // (wrapping). Equivalent to the VHDL loop in the separate clocked process:
  //   for i in 0 to blocks-1: clr_val(to_integer(nxt+i)) = (i <= nr_of_blks)
  val clrVal = Reg(Bits(blocks bits)) init(0)
  for (j <- 0 until blocks) {
    val offset = U(j, blockBits bits) - nxt  // wraps naturally in blockBits width
    clrVal(j) := (offset <= nrOfBlks)
  }

  // ==========================================================================
  // Outputs
  // ==========================================================================

  // bcStart: word address of the block in JBC RAM (combinational from blockAddr)
  io.bcStart := (blockAddr ## U(0, blockWordBits bits)).asUInt

  // rdy: combinational, true when state is idle
  io.rdy := (state === State.IDLE)

  // inCache: registered result
  io.inCache := inCache

  // ==========================================================================
  // State Machine Logic
  // ==========================================================================

  switch(state) {

    is(State.IDLE) {
      when(io.find) {
        state := State.S1
      }
    }

    is(State.S1) {
      // Default: miss — use nxt pointer for new allocation
      inCache := False
      state := State.S2
      blockAddr := nxt

      // Parallel tag check across all blocks (last match wins, like VHDL)
      for (i <- 0 until blocks) {
        when(tag(i) === useAddr) {
          blockAddr := U(i, blockBits bits)
          inCache := True
          state := State.IDLE
        }
      }
    }

    is(State.S2) {
      // Clear displaced tags via pre-computed mask
      for (i <- 0 until blocks) {
        when(clrVal(i)) {
          tag(i) := B(0, tagWidth bits)
        }
      }
      // Write new tag to nxt position (overrides clear for same position)
      tag(nxt) := useAddr

      // Advance FIFO pointer
      nxt := nxt + nrOfBlks + 1

      state := State.IDLE
    }
  }
}
