package jop.memory

import spinal.core._

/**
 * Array Cache
 *
 * Ports the VHDL acache entity (cache/acache.vhd) to SpinalHDL.
 * Fully associative cache with FIFO replacement for array element values.
 *
 * Each entry caches one aligned group of (1 << fieldBits) consecutive array
 * elements for one (handle, region) pair. Tags include both the handle address
 * and upper index bits (index[maxIndexBits-1:fieldBits]), so different regions
 * of the same array map to different cache lines.
 *
 * Single valid bit per line (unlike ObjectCache which has per-field valid bits).
 * Valid is set on the first wrIal of a fill and remains set for the entire line.
 *
 * Interface:
 *   - handle/index are wired combinationally from the controller
 *   - chkIal fires in IDLE on iaload (combinational hit detection)
 *   - chkIas fires in IAST_WAIT on iastore (registered hit detection)
 *   - wrIal fires during cache line fill (one pulse per element, 4 total)
 *   - wrIas fires when iastore completes (write-through if tag was hit)
 *   - idxReg auto-increments on wrIal for sequential fill
 *
 * Timing:
 *   - hit is combinational (available same cycle as chkIal)
 *   - dout is registered (available 1 cycle after chkIal, latched until next check)
 *
 * VHDL bug fix: The original VHDL uses FIELD_CNT (=4) instead of FIELD_BITS
 * (=2) for the idx_upper slice boundary, making the tag index coarser than
 * intended. This SpinalHDL version uses fieldBits correctly.
 *
 * VHDL bug fix: The original VHDL advances the FIFO nxt pointer on every
 * wrIal during a fill (advancing by fieldCnt instead of 1). This version
 * clears incNxtReg after the first wrIal so nxt advances by exactly 1.
 *
 * @param addrBits       Handle address width (matches config.addressWidth)
 * @param wayBits        log2(number of entries) — 4 = 16 entries
 * @param fieldBits      log2(elements per line) — 2 = 4 elements
 * @param maxIndexBits   Max array index width (24 = full address space)
 */
case class ArrayCache(
  addrBits: Int = 24,
  wayBits: Int = 4,
  fieldBits: Int = 2,
  maxIndexBits: Int = 24
) extends Component {

  require(fieldBits >= 1, "fieldBits must be >= 1 for multi-element cache lines")
  require(maxIndexBits > fieldBits, "maxIndexBits must be > fieldBits")

  val lineCnt = 1 << wayBits
  val fieldCnt = 1 << fieldBits
  val ramWords = lineCnt * fieldCnt
  val tagIdxBits = maxIndexBits - fieldBits  // upper index bits stored in tag

  val io = new Bundle {
    // Lookup (active in IDLE / IAST_WAIT)
    val handle   = in UInt(addrBits bits)       // Array handle address
    val index    = in UInt(maxIndexBits bits)    // Array index
    val chkIal   = in Bool()                     // Check for iaload
    val chkIas   = in Bool()                     // Check for iastore

    // Combinational result
    val hit      = out Bool()                    // Element hit (tag + idx_upper + valid)

    // Registered data output (1-cycle latency)
    val dout     = out Bits(32 bits)

    // Update (from memory controller)
    val wrIal    = in Bool()                     // Fill on iaload miss (one pulse per element)
    val wrIas    = in Bool()                     // Update on iastore (cache gates with hitTagReg)
    val ialVal   = in Bits(32 bits)              // Data for iaload fill (BMB response)
    val iasVal   = in Bits(32 bits)              // Data for iastore write-through

    // Control
    val inval    = in Bool()                     // Invalidate all entries

    // Snoop invalidation (from other cores' stores via snoop bus)
    val snoopValid  = in Bool()                  // Remote store event
    val snoopHandle = in UInt(addrBits bits)     // Handle of written array
    val snoopIndex  = in UInt(maxIndexBits bits) // Array index of written element
  }

  // ==========================================================================
  // Tag, Tag Index, and Valid Arrays
  // ==========================================================================

  val tag = Vec(Reg(UInt(addrBits bits)) init(0), lineCnt)
  val tagIdx = Vec(Reg(UInt(tagIdxBits bits)) init(0), lineCnt)
  val valid = Vec(Reg(Bool()) init(False), lineCnt)

  // FIFO replacement pointer
  val nxt = Reg(UInt(wayBits bits)) init(0)

  // ==========================================================================
  // Data RAM (synchronous read, synchronous write)
  // ==========================================================================

  val dataRam = Mem(Bits(32 bits), ramWords)

  // ==========================================================================
  // Combinational Hit Detection (matching VHDL ac_tag combinational process)
  // ==========================================================================

  val idxLower = io.index(fieldBits - 1 downto 0)
  val idxUpper = io.index(maxIndexBits - 1 downto fieldBits)

  val hitVec = Bits(lineCnt bits)
  val hitTagVec = Bits(lineCnt bits)

  for (i <- 0 until lineCnt) {
    val tagMatch = tag(i) === io.handle && valid(i)
    val idxMatch = tagIdx(i) === idxUpper
    hitTagVec(i) := tagMatch
    hitVec(i) := tagMatch && idxMatch
  }

  io.hit := hitVec.orR

  // Line encoder: priority-free OR-based encoder (matching VHDL ac_tag)
  val lineEnc = UInt(wayBits bits)
  for (i <- 0 until wayBits) {
    var bitOr = False
    for (j <- 0 until lineCnt) {
      val jVal = U(j, wayBits bits)
      when(jVal(i) && hitTagVec(j)) {
        bitOr \= True
      }
    }
    lineEnc(i) := bitOr
  }

  // ==========================================================================
  // Registered State (matching VHDL acache clocked process)
  // ==========================================================================

  val lineReg = Reg(UInt(wayBits bits)) init(0)
  val incNxtReg = Reg(Bool()) init(False)
  val hitTagReg = Reg(Bool()) init(False)
  val cacheableReg = Reg(Bool()) init(True)

  // Snoop-during-fill flag: set if a snoop invalidation fires while the
  // A$ is being filled (between chkIal and the last wrIal). When set,
  // subsequent wrIal pulses skip tag/valid updates to prevent re-validating
  // a line that was correctly invalidated by the snoop. The fill reads
  // still complete (SDRAM data captured in rdDataReg by the controller)
  // but the cache line stays invalid, forcing a refill on the next iaload.
  val snoopDuringFill = Reg(Bool()) init(False)

  // Registered copy of handle and index for write-back
  val handleReg = Reg(UInt(addrBits bits)) init(0)
  val indexReg = Reg(UInt(maxIndexBits bits)) init(0)

  // Internal fill index (reset to 0 on chkIal, auto-increments on wrIal)
  val idxReg = Reg(UInt(fieldBits bits)) init(0)

  // Latch lookup results on chkIal or chkIas
  when(io.chkIal || io.chkIas) {
    hitTagReg := hitTagVec.orR
    handleReg := io.handle
    indexReg := io.index
    cacheableReg := True  // Arrays are always cacheable (no HWO check)
    snoopDuringFill := False  // Reset at start of new lookup
  }

  // iaload check: decide line and reset fill index
  when(io.chkIal) {
    when(hitTagVec.orR) {
      lineReg := lineEnc
      incNxtReg := False
    }.otherwise {
      lineReg := nxt
      incNxtReg := True
    }
    idxReg := 0  // Fill starts from element 0 of aligned group
  }

  // iastore check: use hit line, capture element index
  when(io.chkIas) {
    lineReg := lineEnc
    incNxtReg := False
    idxReg := io.index(fieldBits - 1 downto 0)
  }

  // Auto-increment fill index on wrIal (matching VHDL idx_reg increment)
  when(io.wrIal) {
    idxReg := idxReg + 1
  }

  // ==========================================================================
  // Data RAM Read (synchronous — 1 cycle latency)
  // ==========================================================================

  // Read address: hit line + lower index bits (matching VHDL ram_dout read)
  val ramRdAddr = (lineEnc ## idxLower).asUInt.resize(wayBits + fieldBits)
  // Always-read (no enable) to avoid Quartus dual-clock RAM inference.
  val ramDout = dataRam.readSync(ramRdAddr)

  // Latch RAM output on cycle after chkIal (matching VHDL chk_gf_dly/ram_dout_store)
  val chkIalDly = RegNext(io.chkIal, init = False)
  val ramDoutStore = Reg(Bits(32 bits)) init(0)
  when(chkIalDly) {
    ramDoutStore := ramDout
  }

  io.dout := ramDoutStore

  // ==========================================================================
  // Cache Update Logic (matching VHDL update_cache)
  // ==========================================================================

  // Update on: iaload fill (wrIal), or iastore write-through with prior tag hit.
  // If a snoop fired during this fill (snoopDuringFill), skip wrIal updates
  // to prevent re-validating a line that was correctly invalidated. The fill
  // reads still complete (data captured in rdDataReg by the controller) but
  // the cache line stays invalid. iastore write-through is unaffected.
  val updateCache = (io.wrIal && !snoopDuringFill || (io.wrIas && hitTagReg)) && cacheableReg

  // RAM write data MUX (matching VHDL: wr_ial → ial_val, else ias_val)
  val ramDin = Mux(io.wrIal, io.ialVal, io.iasVal)

  // Write data RAM
  val ramWrAddr = (lineReg ## idxReg).asUInt.resize(wayBits + fieldBits)
  when(updateCache) {
    dataRam.write(ramWrAddr, ramDin)
  }

  // Update tag, tag_idx, and valid (matching VHDL ac_tag clocked process)
  when(updateCache) {
    tag(lineReg) := handleReg
    tagIdx(lineReg) := indexReg(maxIndexBits - 1 downto fieldBits)
    valid(lineReg) := True
  }

  // Advance FIFO pointer on first wrIal of a tag miss (VHDL bug fix:
  // original advances on every wrIal, advancing nxt by fieldCnt instead of 1)
  when(io.wrIal && cacheableReg && incNxtReg) {
    nxt := nxt + 1
    incNxtReg := False  // Only advance once per fill
  }

  // Invalidation: clear all valid bits and reset nxt (matching VHDL)
  when(io.inval) {
    nxt := 0
    valid.foreach(_ := False)
  }

  // Snoop invalidation: selectively invalidate lines matching a remote
  // core's iastore. Only the line with matching handle + upper index
  // (same cache-line region) is invalidated. Placed after updateCache
  // and inval so "last assignment wins" ensures snoop takes priority.
  for (i <- 0 until lineCnt) {
    val snoopTagMatch = tag(i) === io.snoopHandle && valid(i)
    val snoopIdxMatch = tagIdx(i) === io.snoopIndex(maxIndexBits - 1 downto fieldBits)
    when(io.snoopValid && snoopTagMatch && snoopIdxMatch) {
      valid(i) := False
      // If the invalidated line is the one currently being filled,
      // prevent subsequent wrIal from re-validating it with stale data.
      when(lineReg === U(i, wayBits bits)) {
        snoopDuringFill := True
      }
    }
  }
}
