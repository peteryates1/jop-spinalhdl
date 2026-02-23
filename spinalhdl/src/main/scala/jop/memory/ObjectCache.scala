package jop.memory

import spinal.core._

/**
 * Object Cache
 *
 * Ports the VHDL ocache entity (cache/ocache.vhd) to SpinalHDL.
 * Fully associative cache with FIFO replacement for object field values.
 *
 * Each entry caches up to (1 << indexBits) fields for one object handle.
 * Per-field valid bits allow partial caching of an object's fields.
 *
 * Interface matches VHDL ocache_in_type / ocache_out_type:
 *   - handle/fieldIdx are wired combinationally from the controller
 *   - chkGf fires in IDLE on getfield (combinational hit detection)
 *   - chkPf fires in HANDLE_READ on putfield (registered hit detection)
 *   - wrGf fires when getfield miss completes (fill cache with memory data)
 *   - wrPf fires when putfield completes (write-through if tag was hit)
 *   - Cache internally manages lineReg, incNxtReg, hitTagReg for updates
 *
 * Timing:
 *   - hit is combinational (available same cycle as chkGf)
 *   - dout is registered (available 1 cycle after chkGf, latched until next check)
 *
 * @param addrBits       Handle address width (matches config.addressWidth)
 * @param wayBits        log2(number of entries) — 4 = 16 entries
 * @param indexBits      log2(fields per entry) — 3 = 8 fields
 * @param maxIndexBits   Max field index width (8 = 256 addressable)
 */
case class ObjectCache(
  addrBits: Int = 24,
  wayBits: Int = 4,
  indexBits: Int = 3,
  maxIndexBits: Int = 8
) extends Component {

  val lineCnt = 1 << wayBits
  val fieldCnt = 1 << indexBits
  val ramWords = lineCnt * fieldCnt

  val io = new Bundle {
    // Lookup (active in IDLE / HANDLE_READ)
    val handle   = in UInt(addrBits bits)       // Object handle address
    val fieldIdx = in UInt(maxIndexBits bits)    // Field index from bcopd
    val chkGf    = in Bool()                     // Check for getfield
    val chkPf    = in Bool()                     // Check for putfield

    // Combinational result
    val hit      = out Bool()                    // Field hit (tag + valid + cacheable)

    // Registered data output (1-cycle latency)
    val dout     = out Bits(32 bits)

    // Update (from memory controller)
    val wrGf     = in Bool()                     // Fill on getfield miss
    val wrPf     = in Bool()                     // Update on putfield (cache gates with hitTagReg)
    val gfVal    = in Bits(32 bits)              // Data for getfield fill (BMB response)
    val pfVal    = in Bits(32 bits)              // Data for putfield write-through (value register)

    // Control
    val inval    = in Bool()                     // Invalidate all entries

    // Snoop invalidation (from other cores' stores via snoop bus)
    val snoopValid    = in Bool()                    // Remote store event
    val snoopHandle   = in UInt(addrBits bits)       // Handle of written object
    val snoopFieldIdx = in UInt(maxIndexBits bits)   // Field index of written field
  }

  // ==========================================================================
  // Tag and Valid Arrays
  // ==========================================================================

  val tag = Vec(Reg(UInt(addrBits bits)) init(0), lineCnt)
  val valid = Vec(Reg(Bits(fieldCnt bits)) init(0), lineCnt)

  // FIFO replacement pointer
  val nxt = Reg(UInt(wayBits bits)) init(0)

  // ==========================================================================
  // Data RAM (synchronous read, synchronous write)
  // ==========================================================================

  val dataRam = Mem(Bits(32 bits), ramWords)

  // ==========================================================================
  // Combinational Hit Detection (matching VHDL oc_tag combinational process)
  // ==========================================================================

  val idx = io.fieldIdx(indexBits - 1 downto 0)

  val hitVec = Bits(lineCnt bits)
  val hitTagVec = Bits(lineCnt bits)

  for (i <- 0 until lineCnt) {
    val tagMatch = tag(i) === io.handle
    val fieldValid = valid(i)(idx)
    val anyValid = valid(i).orR
    hitVec(i) := tagMatch && fieldValid
    hitTagVec(i) := tagMatch && anyValid
  }

  // Cacheable: upper index bits must be zero (field < 2^indexBits)
  val cacheable = (io.fieldIdx(maxIndexBits - 1 downto indexBits) === 0)

  // Hit output: field hit AND cacheable (matching VHDL ocout.hit)
  io.hit := hitVec.orR && cacheable

  // Line encoder: priority-free OR-based encoder (matching VHDL oc_tag)
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
  // Registered State (matching VHDL ocache clocked process)
  // ==========================================================================

  val lineReg = Reg(UInt(wayBits bits)) init(0)
  val incNxtReg = Reg(Bool()) init(False)
  val hitTagReg = Reg(Bool()) init(False)
  val cacheableReg = Reg(Bool()) init(False)

  // Registered copy of handle and index for write-back
  val handleReg = Reg(UInt(addrBits bits)) init(0)
  val indexReg = Reg(UInt(maxIndexBits bits)) init(0)

  // Latch lookup results on chkGf or chkPf
  when(io.chkGf || io.chkPf) {
    hitTagReg := hitTagVec.orR && cacheable
    handleReg := io.handle
    indexReg := io.fieldIdx
    cacheableReg := cacheable
  }

  // Decide line address on chkGf: tag hit → reuse line, miss → use nxt
  when(io.chkGf) {
    when(hitTagVec.orR) {
      lineReg := lineEnc
      incNxtReg := False
    }.otherwise {
      lineReg := nxt
      incNxtReg := True
    }
  }

  // Putfield: always use hit line (no write-allocate)
  when(io.chkPf) {
    lineReg := lineEnc
    incNxtReg := False
  }

  // ==========================================================================
  // Data RAM Read (synchronous — 1 cycle latency)
  // ==========================================================================

  val ramRdAddr = (Mux(hitTagVec.orR, lineEnc, nxt) ## idx).asUInt.resize(wayBits + indexBits)
  // Always-read (no enable) to avoid Quartus dual-clock RAM inference.
  // With enable, Quartus infers ADDRESS_REG_B=CLOCK1 causing undefined
  // read-during-write behavior on FPGA.
  val ramDout = dataRam.readSync(ramRdAddr)

  // Latch RAM output on cycle after chkGf (matching VHDL chk_gf_dly/ram_dout_store)
  val chkGfDly = RegNext(io.chkGf, init = False)
  val ramDoutStore = Reg(Bits(32 bits)) init(0)
  when(chkGfDly) {
    ramDoutStore := ramDout
  }

  io.dout := ramDoutStore

  // ==========================================================================
  // Cache Update Logic (matching VHDL update_cache / tag write)
  // ==========================================================================

  // Update only when wr_gf, or wr_pf with prior tag hit, AND cacheable
  val updateCache = ((io.wrGf) || (io.wrPf && hitTagReg)) && cacheableReg

  // RAM write data MUX (matching VHDL: wr_gf → gf_val, else pf_val)
  val ramDin = Mux(io.wrGf, io.gfVal, io.pfVal)

  // Write data RAM
  val ramWrAddr = (lineReg ## indexReg(indexBits - 1 downto 0)).asUInt.resize(wayBits + indexBits)
  when(updateCache) {
    dataRam.write(ramWrAddr, ramDin)
  }

  // Update tag and valid (matching VHDL oc_tag clocked process)
  when(updateCache) {
    tag(lineReg) := handleReg  // Tag from registered handle at chk time
    val v = Bits(fieldCnt bits)
    v := valid(lineReg)
    // On new tag allocation (nxt increment), clear other valid bits
    when(incNxtReg) {
      v := B(0, fieldCnt bits)
    }
    v(indexReg(indexBits - 1 downto 0)) := True
    valid(lineReg) := v
  }

  // Advance FIFO pointer on getfield miss (matching VHDL inc_nxt logic)
  when(io.wrGf && cacheableReg && incNxtReg) {
    nxt := nxt + 1
  }

  // Invalidation: clear all valid bits and reset nxt (matching VHDL)
  when(io.inval) {
    nxt := 0
    valid.foreach(_ := B(0, fieldCnt bits))
  }

  // Snoop invalidation: selectively clear matching field valid bits from
  // a remote core's putfield. Only the specific field bit is cleared,
  // not the whole line — other cached fields remain valid. Placed after
  // updateCache and inval so "last assignment wins" ensures snoop priority.
  val snoopIdx = io.snoopFieldIdx(indexBits - 1 downto 0)
  for (i <- 0 until lineCnt) {
    when(io.snoopValid && tag(i) === io.snoopHandle) {
      val v = Bits(fieldCnt bits)
      v := valid(i)
      v(snoopIdx) := False
      valid(i) := v
    }
  }
}
