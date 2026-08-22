package jop.ddr3

import spinal.core._
import spinal.lib._
import jop.memory.MemFill

object LruCacheCoreState extends SpinalEnum {
  val INIT, IDLE, TAG_COMPARE, CHECK_HIT, WRITE_HIT, ISSUE_EVICT, ISSUE_REFILL, RSP_FILL,
      FILL_TAG, FILL_WRITE, FILL_DRAIN = newElement()
}

/**
 * One entry per outstanding memory command, pushed in issue order.
 *
 * Evictions consume a response too, so the queue has to record BOTH kinds — the
 * responses come back in issue order and there is nothing in the payload to
 * tell an eviction acknowledgement from returning refill data. Assuming reads
 * only is exactly the AlteraSdramAdapter bug (ef36d99), where locally-made
 * write responses overtook outstanding reads that were matched by order.
 */
case class MemOrderEntry(mshrIdxWidth: Int) extends Bundle {
  val isRefill = Bool()
  val mshr = UInt(mshrIdxWidth bits)
}

/**
 * Write-back, PLRU L2 cache with non-blocking miss handling.
 *
 * The miss FSM never waits on memory. A miss allocates an MSHR, issues the
 * eviction and the refill, and goes straight back to IDLE; the returning data
 * is applied later in RSP_FILL. What binds throughput is therefore how many
 * misses may be in flight (`mshrCount`), not the DRAM round trip.
 *
 * Everything funnels through IDLE — response handling included — which is what
 * makes the design tractable:
 *
 *   IDLE -> TAG_COMPARE -> CHECK_HIT -> hit:  respond (or WRITE_HIT)
 *                                    -> miss: ISSUE_EVICT? -> ISSUE_REFILL -> IDLE
 *   IDLE -> RSP_FILL (apply a returning line, answer its waiter) -> IDLE
 *
 * Because a lookup is only ever in flight in TAG_COMPARE/CHECK_HIT, and RSP_FILL
 * is only entered from IDLE (or from a stalled ISSUE_*, where the lookup is
 * already finished), a fill can never corrupt a tag comparison that is mid-air.
 * That removes a whole class of hazard rather than papering over it.
 *
 * Three rules keep it correct:
 *
 *  - **One in-flight miss per set.** A request whose index matches a live MSHR
 *    is replayed rather than served, hit or miss. Two misses to one index could
 *    otherwise choose the same victim way; worse, a write that hits the way an
 *    outstanding fill is about to overwrite would be silently lost. The stall
 *    also means an MSHR's saved tag/dirty words cannot go stale, and it subsumes
 *    secondary-hit merging (which is then an optimisation, not a correctness
 *    requirement).
 *  - **Evictions are fire-and-forget.** An eviction and its own refill are
 *    different addresses — the tags differ, or it would not have been a miss —
 *    so there is no read-after-write hazard between them. A later request for
 *    the evicted address does depend on the backend not floating a read past a
 *    queued write to the same address, but that was equally true before, when
 *    the write response only meant the controller had accepted it.
 *  - **Ids are the licence to reorder.** With `idWidth = 0` the cache refuses a
 *    new request while any memory work is outstanding, so an untagged master
 *    still gets one-at-a-time, in-order responses exactly as it always did.
 */
class LruCacheCore(config: CacheConfig = CacheConfig()) extends Component {
  val addrWidth = config.addrWidth
  val dataWidth = config.dataWidth
  private val dataBytes = dataWidth / 8
  private val wayCount = config.wayCount
  private val setCount = config.setCount
  private val mshrCount = config.mshrCount
  private val mshrIdxWidth = log2Up(mshrCount) max 1

  val io = new Bundle {
    val frontend = slave(CacheFrontend(addrWidth, dataWidth, config.idWidth))
    // The backend is strictly in-order and single-tagged, so memCmd/memRsp stay
    // id-less however wide the frontend tag is; the order queue below is what
    // matches a returning response to the miss that asked for it.
    val memCmd = master(Stream(CacheReq(addrWidth, dataWidth)))
    val memRsp = slave(Stream(CacheRsp(dataWidth)))
    // Optional block-fill (GC zeroing) sideband. Streams write-through zero
    // writes for [start,end) straight to memory, invalidating any cached copy.
    val fill = if (config.hasFill) Some(slave(MemFill(config.fillAddrWidth))) else None
    val busy = out Bool()
    val debugState = out UInt(4 bits)
    /** Live MSHR count — how much of the miss concurrency is actually used. */
    val debugMshrUsed = out UInt(log2Up(mshrCount + 1) bits)
  }

  // --- Geometry ---
  val byteOffsetWidth = log2Up(dataBytes)
  val indexWidth = log2Up(setCount)
  val tagWidth = addrWidth - indexWidth - byteOffsetWidth
  require(tagWidth > 0, "addrWidth too small for selected cache geometry")

  val wayBits = if (wayCount > 1) log2Up(wayCount) else 1

  // --- FIFOs ---
  val cmdFifo = StreamFifo(CacheReq(addrWidth, dataWidth, config.idWidth), 4)
  cmdFifo.io.push << io.frontend.req

  // Frontend responses are queued, not held in a single register: a hit served
  // under an outstanding miss and the miss's own completion are produced by
  // different states and must not fight over one output slot.
  val rspFifo = StreamFifo(CacheRsp(dataWidth, config.idWidth), mshrCount + 3)
  io.frontend.rsp << rspFifo.io.pop
  rspFifo.io.push.valid := False
  rspFifo.io.push.payload.data := B(0, dataWidth bits)
  rspFifo.io.push.payload.error := False
  rspFifo.io.push.payload.driveId(U(0))
  val rspReady = rspFifo.io.push.ready

  /** Answer the frontend. Every producer state goes through here. */
  def pushRsp(data: Bits, error: Bool, id: UInt): Unit = {
    rspFifo.io.push.valid := True
    rspFifo.io.push.payload.data := data
    rspFifo.io.push.payload.error := error
    rspFifo.io.push.payload.driveId(id)
  }

  // --- Memory-response order queue ---
  // Two entries per MSHR (an eviction and a refill), which also caps how many
  // commands can be outstanding at the backend.
  val orderFifo = StreamFifo(MemOrderEntry(mshrIdxWidth), 2 * mshrCount)
  orderFifo.io.push.valid := False
  orderFifo.io.push.payload.isRefill := False
  orderFifo.io.push.payload.mshr := U(0, mshrIdxWidth bits)
  orderFifo.io.pop.ready := False

  // --- BRAM Arrays ---
  val dataMems = (0 until wayCount).map(_ => Mem(Bits(dataWidth bits), setCount))
  val tagMem = Mem(Bits(wayCount * tagWidth bits), setCount)
  val dirtyMem = Mem(Bits(wayCount * dataBytes bits), setCount)

  // --- Register Arrays ---
  //
  /**
   * Valid bits, in BRAM rather than fabric registers -- the other half of the
   * work started with lruMem.
   *
   * This was `Vec(Reg(Bool()), setCount * wayCount)`: setCount x wayCount flops
   * plus a read mux and a write decoder, and read PER WAY during tag compare,
   * so its access logic was wider than the PLRU tree's.
   *
   * A Mem does not reset, and these MUST read False out of reset or the cache
   * would report hits on uninitialised tags. Hence the INIT state, which walks
   * every set writing zero before IDLE is entered for the first time. `io.busy`
   * is already `state =/= IDLE`, so the frontend sees the cache as busy for
   * setCount cycles at power-on and nothing else has to know about it.
   */
  val validMem = Mem(Bits(wayCount bits), setCount)
  val plruBits = if (wayCount == 4) 3 else if (wayCount == 2) 1 else 0

  /**
   * PLRU state, in BRAM rather than fabric registers.
   *
   * It used to be `Vec(Reg(Bits(plruBits bits)), setCount)`, which cost
   * setCount x plruBits flip-flops AND -- far more expensive -- a set-wide read
   * mux plus a setCount-way write decoder with per-register enables. Together
   * with validFlat that was most of why 64 -> 512 sets cost ~9,500 LUTs while
   * BRAM stayed flat at 10 RAMB36: tags, data and dirty bits were in Mem, these
   * two were not.
   *
   * Safe to move because PLRU needs NO RESET: every bit pattern is a legal tree
   * state, so an uninitialised Mem merely starts with an arbitrary victim
   * order. That is what makes this the easy half; validFlat has real reset
   * semantics and cannot follow the same route.
   *
   * The read is now synchronous, so it arrives in TAG_COMPARE alongside
   * tagReadVal -- which is exactly where the victim is chosen -- and is
   * registered into compLru for the CHECK_HIT/WRITE_HIT updates that follow a
   * cycle or two later.
   */
  val lruMem = if (plruBits > 0) Mem(Bits(plruBits bits), setCount) else null

  // Valid array accessors
  private def validIdx(setIdx: UInt, way: Int): UInt = {
    if (wayCount == 1) setIdx.resize(log2Up(setCount * wayCount))
    else (setIdx * U(wayCount) + U(way)).resize(log2Up(setCount * wayCount))
  }
  /** Valid bit for a way, from the word the synchronous read produced. The
    * index argument is gone: the read is issued on bramReadAddr a cycle
    * earlier, so the caller cannot choose a different set here. */
  def getValid(way: Int): Bool = validReadVal(way)
  /** Splice one way's valid bit into `word` and drive the write port with it.
    * The caller supplies the current word because where it comes from differs:
    * the lookup path has it in pendingValidWord, RSP_FILL in the MSHR's copy,
    * and the fill path straight off validReadVal. */
  def writeValidForWay(word: Bits, targetWay: UInt, value: Bool): Unit = {
    validWriteData := spliceWay(word, targetWay, 1, value.asBits)
    validWriteEnable := True
  }


  // --- State Machine ---
  val state = Reg(LruCacheCoreState()) init (LruCacheCoreState.INIT)
  /** Set being cleared during INIT. Sized to hold setCount so the final
    * increment can pass the last index without wrapping. */
  val initIndex = Reg(UInt(log2Up(setCount + 1) max 1 bits)) init (0)
  // Where RSP_FILL hands control back. Response handling normally happens from
  // IDLE, but a stalled ISSUE_* has to be able to service a response too, or a
  // backend whose queue is full deadlocks against a cache that will not drain it.
  val resumeState = Reg(LruCacheCoreState()) init (LruCacheCoreState.IDLE)
  // A request that could not be served yet (set conflict, or no MSHR free) is
  // held here and retried from IDLE, so that everything still passes through
  // IDLE and response handling can never be starved by a spinning retry.
  val replayValid = RegInit(False)

  // --- Pending Registers (the ONE miss currently being issued) ---
  val pendingReq = Reg(CacheReq(addrWidth, dataWidth, config.idWidth)) init (CacheReq(addrWidth, dataWidth, config.idWidth).getZero)
  val pendingNeedRefill = Reg(Bool()) init (False)
  val pendingIndex = Reg(UInt(indexWidth max 1 bits)) init (0)
  val pendingTag = Reg(Bits(tagWidth bits)) init (0)
  val pendingVictimWay = Reg(UInt(wayBits bits)) init (0)
  val pendingVictimData = Reg(Bits(dataWidth bits)) init (0)
  val pendingVictimTag = Reg(Bits(tagWidth bits)) init (0)
  val pendingVictimDirty = Reg(Bits(dataBytes bits)) init (0)
  val pendingTagWord = Reg(Bits(wayCount * tagWidth bits)) init (0)
  /** Valid word for the set under comparison, captured in TAG_COMPARE. Same
    * role as pendingTagWord: the install a few states later needs the word it
    * is splicing into, and the Mem output has moved on by then. */
  val pendingValidWord = Reg(Bits(wayCount bits)) init (0)
  val pendingDirtyWord = Reg(Bits(wayCount * dataBytes bits)) init (0)
  val pendingMshrId = Reg(UInt(mshrIdxWidth bits)) init (0)

  // Tag comparison pipeline registers (break readSync → state decision path)
  val compAnyHit = Reg(Bool()) init (False)
  val compHitWay = Reg(UInt(wayBits bits)) init (0)
  val compHitData = Reg(Bits(dataWidth bits)) init (0)
  val compVictimWay = Reg(UInt(wayBits bits)) init (0)
  val compVictimData = Reg(Bits(dataWidth bits)) init (0)
  val compVictimTag = Reg(Bits(tagWidth bits)) init (0)
  val compVictimDirty = Reg(Bits(dataBytes bits)) init (0)
  val compVictimIsDirty = Reg(Bool()) init (False)
  val compReqIsFullLineWrite = Reg(Bool()) init (False)
  val compReqWriteDirtyMask = Reg(Bits(dataBytes bits)) init (0)
  val compWayHits = Vec(Reg(Bool()) init (False), wayCount)
  val compWayDirtys = Vec(Reg(Bits(dataBytes bits)) init (0), wayCount)

  /** PLRU word for the set under comparison, captured in TAG_COMPARE so the
    * updates in CHECK_HIT and WRITE_HIT do not need the Mem output to still be
    * valid by then. */
  val compLru = if (plruBits > 0) Reg(Bits(plruBits bits)) init (0) else null

  // Write-hit pipeline registers (break tag comparison → data-write path)
  val pendingHitWay = Reg(UInt(wayBits bits)) init (0)
  val pendingMergedData = Reg(Bits(dataWidth bits)) init (0)
  val pendingNewDirtyWord = Reg(Bits(wayCount * dataBytes bits)) init (0)

  // --- MSHR file ---
  // Everything RSP_FILL needs to install a line and answer its waiter, captured
  // when the miss was allocated. Safe to capture that early precisely because
  // no other request to this index can run in the meantime.
  val mshrValid = Vec(RegInit(False), mshrCount)
  val mshrIndex = Vec(Reg(UInt(indexWidth max 1 bits)) init (0), mshrCount)
  val mshrTag = Vec(Reg(Bits(tagWidth bits)) init (0), mshrCount)
  val mshrVictimWay = Vec(Reg(UInt(wayBits bits)) init (0), mshrCount)
  val mshrTagWord = Vec(Reg(Bits(wayCount * tagWidth bits)) init (0), mshrCount)
  val mshrValidWord = Vec(Reg(Bits(wayCount bits)) init (0), mshrCount)
  val mshrDirtyWord = Vec(Reg(Bits(wayCount * dataBytes bits)) init (0), mshrCount)
  val mshrIsWrite = Vec(RegInit(False), mshrCount)
  val mshrWrData = Vec(Reg(Bits(dataWidth bits)) init (0), mshrCount)
  val mshrWrMask = Vec(Reg(Bits(dataBytes bits)) init (0), mshrCount)
  val mshrReqId = Vec(Reg(UInt((config.idWidth max 1) bits)) init (0), mshrCount)

  val mshrFreeMask = Bits(mshrCount bits)
  for (i <- 0 until mshrCount) mshrFreeMask(i) := !mshrValid(i)
  val mshrHasFree = mshrFreeMask.orR
  val mshrAllocOh = OHMasking.first(mshrFreeMask)
  val mshrAllocId = if (mshrCount == 1) U(0, mshrIdxWidth bits) else OHToUInt(mshrAllocOh)
  val mshrAnyValid = mshrValid.reduce(_ || _)
  io.debugMshrUsed := CountOne(mshrValid.asBits).resized

  /** Read an MSHR field by index, as an explicit mux — mshrCount is tiny. */
  private def mshrSel[T <: Data](vec: Vec[T], sel: UInt): T = {
    val out = cloneOf(vec(0))
    out := vec(0)
    for (i <- 1 until mshrCount) {
      when(sel === U(i, mshrIdxWidth bits)) { out := vec(i) }
    }
    out
  }

  // The set a request is about to touch, when a fill for that set is already in
  // flight. Compared against pendingIndex rather than the FIFO head so the
  // comparator sits register-to-register, off the cmdFifo command path that
  // already terminates the 4/8-core critical path.
  val mshrIndexConflict =
    (0 until mshrCount).map(i => mshrValid(i) && mshrIndex(i) === pendingIndex).reduce(_ || _)

  // --- Block-fill (GC zeroing) state ---
  // Streams write-through zero writes for [start,end) straight to memory,
  // invalidating any cached copy — no allocate, so no per-line eviction cascade.
  // Interior (fully-in-range) lines: write-all zeros + invalidate. Partial edge
  // lines: masked write (miss) or merge cached out-of-range words (hit) so live
  // data adjacent to the free region is preserved. One memRsp per issued line.
  private val fillW = config.fillAddrWidth max 1
  private val wordBytes = 4                        // 32-bit BMB word
  private val wordsPerLine = dataBytes / wordBytes // 4 for a 128-bit line
  private val lineWordShift = log2Up(wordsPerLine) // 2
  val fillActive    = Reg(Bool()) init (False)
  val fillWord      = Reg(UInt(fillW bits)) init (0)  // current line's first word (line-aligned)
  val fillStartWord = Reg(UInt(fillW bits)) init (0)
  val fillEndWord   = Reg(UInt(fillW bits)) init (0)
  val fillIssued    = Reg(UInt(fillW bits)) init (0)
  val fillRsp       = Reg(UInt(fillW bits)) init (0)

  def fillByteAddr(word: UInt): UInt = (word << 2).resize(addrWidth)
  def fillIndexOf(word: UInt): UInt = {
    val b = fillByteAddr(word)
    if (indexWidth == 0) U(0, 1 bits)
    else b(byteOffsetWidth + indexWidth - 1 downto byteOffsetWidth).resize(indexWidth max 1)
  }
  def fillTagOf(word: UInt): Bits = fillByteAddr(word)(addrWidth - 1 downto byteOffsetWidth + indexWidth).asBits

  // Memory work the cache still owes itself: live MSHRs, or commands whose
  // responses have not come back. Gates both the block fill (which takes over
  // memRsp wholesale) and, for an untagged frontend, accepting anything new.
  val outstandingWork = mshrAnyValid || orderFifo.io.occupancy =/= 0

  // --- Default Outputs ---
  io.memCmd.valid := False
  io.memCmd.payload.addr := pendingReq.addr
  io.memCmd.payload.write := False
  io.memCmd.payload.data := B(0, dataWidth bits)
  io.memCmd.payload.mask := B((BigInt(1) << dataBytes) - 1, dataBytes bits)
  cmdFifo.io.pop.ready := False
  io.memRsp.ready := False

  // --- BRAM Read Ports ---
  val bramReadAddr = UInt(indexWidth max 1 bits)
  val reqAddr = cmdFifo.io.pop.payload.addr
  val reqIndex = if (indexWidth == 0) U(0, 1 bits) else reqAddr(byteOffsetWidth + indexWidth - 1 downto byteOffsetWidth).asUInt.resize(indexWidth max 1)
  bramReadAddr := reqIndex
  // A replay re-reads the held request's set, not the FIFO head's.
  when(state =/= LruCacheCoreState.IDLE || replayValid) {
    bramReadAddr := pendingIndex
  }
  // During a fill, drive the read port with the current fill line's set index so
  // FILL_WRITE sees this line's tags/data (for hit detection + invalidation).
  if (config.hasFill) {
    when(fillActive) { bramReadAddr := fillIndexOf(fillWord) }
  }

  val dataReadVals = dataMems.map(_.readSync(bramReadAddr.resize(indexWidth max 1)))
  val tagReadVal = tagMem.readSync(bramReadAddr.resize(indexWidth max 1))
  val dirtyReadVal = dirtyMem.readSync(bramReadAddr.resize(indexWidth max 1))
  val lruReadVal = if (plruBits > 0) lruMem.readSync(bramReadAddr.resize(indexWidth max 1))
                   else null
  val validReadVal = validMem.readSync(bramReadAddr.resize(indexWidth max 1))

  // --- BRAM Write Ports (single port per BRAM, muxed by state) ---
  // Each BRAM gets ONE write port with address/data/enable muxed across states.
  // This avoids the SpinalHDL issue where Mem.write(enable=...) doesn't include
  // enclosing when/is conditions.
  //
  // The write address is a signal rather than pendingIndex directly, because
  // RSP_FILL installs a line for an MSHR whose set is not the one the lookup
  // pipeline is looking at.
  val bramWriteAddr = UInt(indexWidth max 1 bits)
  bramWriteAddr := pendingIndex.resize(indexWidth max 1)

  // Data BRAM write signals (per way)
  val dataWriteEnable = Vec(Bool(), wayCount)
  val dataWriteData = Bits(dataWidth bits)
  dataWriteEnable.foreach(_ := False)
  dataWriteData := B(0, dataWidth bits)

  for (w <- 0 until wayCount) {
    dataMems(w).write(bramWriteAddr, dataWriteData, enable = dataWriteEnable(w))
  }

  // Tag BRAM write signals
  val tagWriteEnable = Bool()
  val tagWriteData = Bits(wayCount * tagWidth bits)
  tagWriteEnable := False
  tagWriteData := B(0, wayCount * tagWidth bits)
  tagMem.write(bramWriteAddr, tagWriteData, enable = tagWriteEnable)

  // Valid-bit BRAM write signals, same single-muxed-port shape as the rest.
  val validWriteEnable = Bool()
  val validWriteData = Bits(wayCount bits)
  validWriteEnable := False
  validWriteData := B(0, wayCount bits)
  validMem.write(bramWriteAddr, validWriteData, enable = validWriteEnable)

  // PLRU BRAM write signals. Same single-muxed-port shape as the others; the
  // address follows bramWriteAddr so an RSP_FILL install and a lookup update
  // cannot disagree about which set they are touching.
  val lruWriteEnable = Bool()
  val lruWriteData = if (plruBits > 0) Bits(plruBits bits) else null
  lruWriteEnable := False
  if (plruBits > 0) {
    lruWriteData := B(0, plruBits bits)
    lruMem.write(bramWriteAddr, lruWriteData, enable = lruWriteEnable)
  }

  // Dirty BRAM write signals
  val dirtyWriteEnable = Bool()
  val dirtyWriteData = Bits(wayCount * dataBytes bits)
  dirtyWriteEnable := False
  dirtyWriteData := B(0, wayCount * dataBytes bits)
  dirtyMem.write(bramWriteAddr, dirtyWriteData, enable = dirtyWriteEnable)

  /** Splice `value` into `word` at the slot belonging to `way`. */
  private def spliceWay(word: Bits, way: UInt, slotBits: Int, value: Bits): Bits = {
    val out = cloneOf(word)
    out := word
    for (w <- 0 until wayCount) {
      when(way === U(w, wayBits bits)) {
        out(w * slotBits + slotBits - 1 downto w * slotBits) := value
      }
    }
    out
  }

  // --- Address Construction ---
  val pendingLineAddr = Bits(addrWidth bits)
  pendingLineAddr := pendingReq.addr
  if (byteOffsetWidth > 0) {
    pendingLineAddr(byteOffsetWidth - 1 downto 0) := 0
  }

  def makeEvictAddr(tag: Bits, index: UInt): Bits = {
    val addr = Bits(addrWidth bits)
    addr := B(0, addrWidth bits)
    if (byteOffsetWidth > 0) addr(byteOffsetWidth - 1 downto 0) := 0
    if (indexWidth > 0) addr(byteOffsetWidth + indexWidth - 1 downto byteOffsetWidth) := index(indexWidth - 1 downto 0).asBits
    addr(addrWidth - 1 downto byteOffsetWidth + indexWidth) := tag
    addr
  }

  // --- PLRU Logic ---
  def plruVictim(lruBits: Bits): UInt = {
    val victim = UInt(wayBits bits)
    if (wayCount == 4) {
      when(lruBits(2)) {
        victim := Mux(lruBits(1), U(0, 2 bits), U(1, 2 bits))
      } otherwise {
        victim := Mux(lruBits(0), U(2, 2 bits), U(3, 2 bits))
      }
    } else if (wayCount == 2) {
      victim := Mux(lruBits(0), U(0, 1 bits), U(1, 1 bits))
    } else {
      victim := U(0, 1 bits)
    }
    victim
  }

  def plruUpdate(lruBits: Bits, accessedWay: UInt): Bits = {
    val updated = Bits(plruBits bits)
    if (wayCount == 4) {
      updated := lruBits
      switch(accessedWay) {
        is(0) { updated(2) := False; updated(1) := False }
        is(1) { updated(2) := False; updated(1) := True }
        is(2) { updated(2) := True; updated(0) := False }
        is(3) { updated(2) := True; updated(0) := True }
      }
    } else if (wayCount == 2) {
      // Single bit: after accessing wayN, point tree toward the OTHER way as victim.
      // bit=1 → victim=w0, bit=0 → victim=w1.
      // Access w0 → victim should be w1 → bit=0 → bit := accessedWay(0)
      // Access w1 → victim should be w0 → bit=1 → bit := accessedWay(0)
      updated(0) := accessedWay(0)
    } else {
      updated := B(0, plruBits bits)
    }
    updated
  }

  def mergeData(cacheData: Bits, writeData: Bits, writeMask: Bits): Bits = {
    val result = Bits(dataWidth bits)
    for (byte <- 0 until dataBytes) {
      val hi = byte * 8 + 7
      val lo = byte * 8
      result(hi downto lo) := Mux(writeMask(byte), cacheData(hi downto lo), writeData(hi downto lo))
    }
    result
  }

  // --- Refill completion view of the order-queue head ---
  val ordHead = orderFifo.io.pop.payload
  val refillOh = Bits(mshrCount bits)
  for (i <- 0 until mshrCount) {
    refillOh(i) := (if (mshrCount == 1) True else ordHead.mshr === U(i, mshrIdxWidth bits))
  }
  val refillIndex = mshrSel(mshrIndex, ordHead.mshr)
  val refillTag = mshrSel(mshrTag, ordHead.mshr)
  val refillVictimWay = mshrSel(mshrVictimWay, ordHead.mshr)
  val refillTagWord = mshrSel(mshrTagWord, ordHead.mshr)
  val refillValidWord = mshrSel(mshrValidWord, ordHead.mshr)
  val refillDirtyWord = mshrSel(mshrDirtyWord, ordHead.mshr)
  val refillIsWrite = mshrSel(mshrIsWrite, ordHead.mshr)
  val refillWrData = mshrSel(mshrWrData, ordHead.mshr)
  val refillWrMask = mshrSel(mshrWrMask, ordHead.mshr)
  val refillReqId = mshrSel(mshrReqId, ordHead.mshr)

  // An untagged frontend cannot tell responses apart, so it gets the old
  // contract: one request at a time, answered in order.
  val canAcceptNew = if (config.idWidth > 0) True else !outstandingWork

  // Only divert to RSP_FILL for a response the order queue can explain. The
  // queue has push-to-pop latency, so a response CAN arrive a cycle or two
  // before its entry surfaces; waiting in IDLE rather than in RSP_FILL means
  // hits keep being served meanwhile, and — more importantly — a response with
  // no entry at all can never wedge the machine.
  val rspRetirable = io.memRsp.valid && orderFifo.io.pop.valid

  // --- State Machine ---
  switch(state) {
    // INIT: clear every valid bit before serving anything. validMem is a Mem
    // and does not reset, and stale valid bits would make the cache report hits
    // on uninitialised tags -- returning garbage for reads that never missed.
    // Costs setCount cycles once at power-on, during which io.busy is already
    // True because it is `state =/= IDLE`.
    is(LruCacheCoreState.INIT) {
      bramWriteAddr := initIndex.resize(indexWidth max 1)
      validWriteData := B(0, wayCount bits)
      validWriteEnable := True
      initIndex := initIndex + 1
      when(initIndex === U(setCount - 1, initIndex.getWidth bits)) {
        state := LruCacheCoreState.IDLE
      }
    }

    is(LruCacheCoreState.IDLE) {
      // Draining the memory pipeline comes first: it is what frees MSHRs, and
      // each visit costs one cycle, so new work is never starved for long.
      when(rspRetirable) {
        resumeState := LruCacheCoreState.IDLE
        state := LruCacheCoreState.RSP_FILL
      } elsewhen(replayValid) {
        replayValid := False
        state := LruCacheCoreState.TAG_COMPARE
      } elsewhen(cmdFifo.io.pop.valid && canAcceptNew) {
        pendingReq := cmdFifo.io.pop.payload
        pendingIndex := reqIndex
        pendingTag := reqAddr(addrWidth - 1 downto byteOffsetWidth + indexWidth)
        cmdFifo.io.pop.ready := True
        state := LruCacheCoreState.TAG_COMPARE
      } otherwise {
        // No frontend work pending: service a block-fill request if one is held.
        // It takes over memRsp wholesale, so nothing else may be outstanding.
        if (config.hasFill) {
          when(io.fill.get.cmd && io.fill.get.end > io.fill.get.start && !outstandingWork) {
            fillActive    := True
            fillStartWord := io.fill.get.start
            fillEndWord   := io.fill.get.end
            fillWord      := (io.fill.get.start >> lineWordShift) << lineWordShift
            fillIssued    := 0
            fillRsp       := 0
            state         := LruCacheCoreState.FILL_TAG
          }
        }
      }
    }

    // TAG_COMPARE: readSync outputs available. Compute and register all
    // comparison results. This breaks the BRAM-read → state-decision path.
    is(LruCacheCoreState.TAG_COMPARE) {
      val wayTags = (0 until wayCount).map(w => tagReadVal(w * tagWidth + tagWidth - 1 downto w * tagWidth))
      val wayDirtys = (0 until wayCount).map(w => dirtyReadVal(w * dataBytes + dataBytes - 1 downto w * dataBytes))
      val wayValids = (0 until wayCount).map(w => getValid(w))

      val wayHits = (0 until wayCount).map(w => wayValids(w) && wayTags(w) === pendingTag)
      val anyHit = wayHits.reduce(_ || _)

      val hitWay = UInt(wayBits bits)
      hitWay := 0
      for (w <- 0 until wayCount) {
        when(wayHits(w)) { hitWay := U(w, wayBits bits) }
      }

      val hitData = Bits(dataWidth bits)
      hitData := dataReadVals(0)
      for (w <- 0 until wayCount) {
        when(wayHits(w)) { hitData := dataReadVals(w) }
      }

      // Victim selection
      val victimWay = UInt(wayBits bits)
      val hasInvalid = !wayValids.reduce(_ && _)
      val firstInvalidWay = UInt(wayBits bits)
      firstInvalidWay := 0
      for (w <- wayCount - 1 to 0 by -1) {
        when(!wayValids(w)) { firstInvalidWay := U(w, wayBits bits) }
      }
      if (plruBits > 0) {
        compLru := lruReadVal          // capture for the CHECK_HIT/WRITE_HIT updates
        victimWay := Mux(hasInvalid, firstInvalidWay, plruVictim(lruReadVal))
      } else {
        victimWay := firstInvalidWay
      }

      val victimData = Bits(dataWidth bits)
      victimData := dataReadVals(0)
      for (w <- 0 until wayCount) {
        when(victimWay === U(w, wayBits bits)) { victimData := dataReadVals(w) }
      }
      val victimTagVal = Bits(tagWidth bits)
      victimTagVal := wayTags(0)
      for (w <- 0 until wayCount) {
        when(victimWay === U(w, wayBits bits)) { victimTagVal := wayTags(w) }
      }
      val victimDirtyVal = Bits(dataBytes bits)
      victimDirtyVal := wayDirtys(0)
      for (w <- 0 until wayCount) {
        when(victimWay === U(w, wayBits bits)) { victimDirtyVal := wayDirtys(w) }
      }
      val victimValid = Bool()
      victimValid := wayValids(0)
      for (w <- 0 until wayCount) {
        when(victimWay === U(w, wayBits bits)) { victimValid := wayValids(w) }
      }

      // Register all comparison results
      compAnyHit := anyHit
      compHitWay := hitWay
      compHitData := hitData
      for (w <- 0 until wayCount) {
        compWayHits(w) := wayHits(w)
        compWayDirtys(w) := wayDirtys(w)
      }
      compVictimWay := victimWay
      compVictimData := victimData
      compVictimTag := victimTagVal
      compVictimDirty := victimDirtyVal
      compVictimIsDirty := victimValid && (victimDirtyVal =/= 0)
      compReqIsFullLineWrite := pendingReq.mask === 0
      compReqWriteDirtyMask := (~pendingReq.mask).asBits
      pendingTagWord := tagReadVal
      pendingValidWord := validReadVal
      pendingDirtyWord := dirtyReadVal

      state := LruCacheCoreState.CHECK_HIT
    }

    // CHECK_HIT: use registered comparison results to make hit/miss decisions.
    is(LruCacheCoreState.CHECK_HIT) {
      // A full-line write needs no refill: every byte is being replaced.
      val needRefill = !(pendingReq.write && compReqIsFullLineWrite)
      // Room for an eviction AND a refill, so ISSUE_* can never stall on the
      // order queue — which would wedge the FSM out of the state that drains it.
      val roomForMiss = (orderFifo.io.availability >= 2) && (!needRefill || mshrHasFree)

      when(mshrIndexConflict || (!compAnyHit && !roomForMiss)) {
        // Hit or miss, a set with a fill in flight has to wait: the victim way
        // is already spoken for, and a write hitting it would be overwritten.
        replayValid := True
        state := LruCacheCoreState.IDLE
      } elsewhen(compAnyHit) {
        when(pendingReq.write) {
          // Hit-write: register merged data, apply in WRITE_HIT state.
          pendingHitWay := compHitWay
          pendingMergedData := mergeData(compHitData, pendingReq.data, pendingReq.mask)

          val newDirtyWord = Bits(wayCount * dataBytes bits)
          newDirtyWord := pendingDirtyWord
          for (w <- 0 until wayCount) {
            when(compWayHits(w)) {
              newDirtyWord(w * dataBytes + dataBytes - 1 downto w * dataBytes) := compWayDirtys(w) | compReqWriteDirtyMask
            }
          }
          pendingNewDirtyWord := newDirtyWord

          state := LruCacheCoreState.WRITE_HIT
        } otherwise {
          when(rspReady) {
            pushRsp(compHitData, False, pendingReq.idValue)
            if (plruBits > 0) {
              lruWriteData := plruUpdate(compLru, compHitWay)
              lruWriteEnable := True
            }
            state := LruCacheCoreState.IDLE
          }
        }
      } otherwise {
        pendingVictimWay := compVictimWay
        pendingVictimData := compVictimData
        pendingVictimTag := compVictimTag
        pendingVictimDirty := compVictimDirty
        pendingNeedRefill := needRefill

        // Claim the victim way now. A refill needs an MSHR to remember all this
        // until the data comes back; a full-line write installs it directly in
        // ISSUE_REFILL and needs none.
        if (plruBits > 0) {
          lruWriteData := plruUpdate(compLru, compVictimWay)
          lruWriteEnable := True
        }
        when(needRefill) {
          pendingMshrId := mshrAllocId
          for (i <- 0 until mshrCount) {
            when(mshrAllocOh(i)) {
              mshrValid(i) := True
              mshrIndex(i) := pendingIndex
              mshrTag(i) := pendingTag
              mshrVictimWay(i) := compVictimWay
              mshrTagWord(i) := pendingTagWord
              mshrValidWord(i) := pendingValidWord
              mshrDirtyWord(i) := pendingDirtyWord
              mshrIsWrite(i) := pendingReq.write
              mshrWrData(i) := pendingReq.data
              mshrWrMask(i) := pendingReq.mask
              mshrReqId(i) := pendingReq.idValue
            }
          }
        }

        when(compVictimIsDirty) {
          state := LruCacheCoreState.ISSUE_EVICT
        } otherwise {
          state := LruCacheCoreState.ISSUE_REFILL
        }
      }
    }

    is(LruCacheCoreState.WRITE_HIT) {
      // Apply registered hit-write results to BRAMs (pipelined from CHECK_HIT)
      when(rspReady) {
        dataWriteData := pendingMergedData
        for (w <- 0 until wayCount) {
          when(pendingHitWay === U(w, wayBits bits)) { dataWriteEnable(w) := True }
        }

        dirtyWriteEnable := True
        dirtyWriteData := pendingNewDirtyWord

        if (plruBits > 0) {
          lruWriteData := plruUpdate(compLru, pendingHitWay)
          lruWriteEnable := True
        }

        pushRsp(B(0, dataWidth bits), False, pendingReq.idValue)
        state := LruCacheCoreState.IDLE
      }
    }

    is(LruCacheCoreState.ISSUE_EVICT) {
      // Fire-and-forget: the write-back carries its data by value, and its
      // address differs from the refill's, so nothing has to wait for it.
      io.memCmd.valid := True
      io.memCmd.payload.addr := makeEvictAddr(pendingVictimTag, pendingIndex)
      io.memCmd.payload.write := True
      io.memCmd.payload.data := pendingVictimData
      io.memCmd.payload.mask := (~pendingVictimDirty).asBits
      when(io.memCmd.ready) {
        orderFifo.io.push.valid := True
        orderFifo.io.push.payload.isRefill := False
        state := LruCacheCoreState.ISSUE_REFILL
      } elsewhen(rspRetirable) {
        // The backend is not taking commands. It may be waiting for us to take
        // a response; service that rather than deadlock against it.
        resumeState := LruCacheCoreState.ISSUE_EVICT
        state := LruCacheCoreState.RSP_FILL
      }
    }

    is(LruCacheCoreState.ISSUE_REFILL) {
      when(pendingNeedRefill) {
        io.memCmd.valid := True
        io.memCmd.payload.addr := pendingLineAddr
        io.memCmd.payload.write := False
        when(io.memCmd.ready) {
          orderFifo.io.push.valid := True
          orderFifo.io.push.payload.isRefill := True
          orderFifo.io.push.payload.mshr := pendingMshrId
          state := LruCacheCoreState.IDLE
        } elsewhen(rspRetirable) {
          resumeState := LruCacheCoreState.ISSUE_REFILL
          state := LruCacheCoreState.RSP_FILL
        }
      } otherwise {
        // Full-line write miss: nothing to read, install it now.
        when(rspReady) {
          val reqWriteDirtyMask = (~pendingReq.mask).asBits

          dataWriteData := pendingReq.data
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) { dataWriteEnable(w) := True }
          }

          tagWriteEnable := True
          tagWriteData := spliceWay(pendingTagWord, pendingVictimWay, tagWidth, pendingTag)

          dirtyWriteEnable := True
          dirtyWriteData := spliceWay(pendingDirtyWord, pendingVictimWay, dataBytes, reqWriteDirtyMask)

          writeValidForWay(pendingValidWord, pendingVictimWay, True)

          pushRsp(B(0, dataWidth bits), False, pendingReq.idValue)
          state := LruCacheCoreState.IDLE
        } elsewhen(rspRetirable) {
          resumeState := LruCacheCoreState.ISSUE_REFILL
          state := LruCacheCoreState.RSP_FILL
        }
      }
    }

    // RSP_FILL: retire one backend response. The order queue says whether it is
    // an eviction acknowledgement (discard) or refill data (install + answer).
    is(LruCacheCoreState.RSP_FILL) {
      bramWriteAddr := refillIndex

      // Readiness must not depend on io.memRsp.valid. Making ready a function
      // of valid is a Stream anti-pattern, and here it also hid the handshake
      // from a one-cycle-wide observer: the old refill path drove memRsp.ready
      // unconditionally within its wait state, so nothing downstream had to
      // cope with a combinational valid -> ready path until this rewrite.
      val canRetire = orderFifo.io.pop.valid && (!ordHead.isRefill || rspReady)
      io.memRsp.ready := canRetire

      when(io.memRsp.valid && canRetire) {
        orderFifo.io.pop.ready := True
        when(ordHead.isRefill) {
          for (i <- 0 until mshrCount) {
            when(refillOh(i)) { mshrValid(i) := False }
          }

          when(io.memRsp.payload.error) {
            // Leave the line as it was: the victim's data was written back and
            // its tag still describes it, so the set stays coherent.
            pushRsp(B(0, dataWidth bits), True, refillReqId)
          } otherwise {
            val refillData = io.memRsp.payload.data
            val finalData = Bits(dataWidth bits)
            val finalDirty = Bits(dataBytes bits)
            when(refillIsWrite) {
              finalData := mergeData(refillData, refillWrData, refillWrMask)
              finalDirty := (~refillWrMask).asBits
            } otherwise {
              finalData := refillData
              finalDirty := B(0, dataBytes bits)
            }

            dataWriteData := finalData
            for (w <- 0 until wayCount) {
              when(refillVictimWay === U(w, wayBits bits)) { dataWriteEnable(w) := True }
            }

            tagWriteEnable := True
            tagWriteData := spliceWay(refillTagWord, refillVictimWay, tagWidth, refillTag)

            dirtyWriteEnable := True
            dirtyWriteData := spliceWay(refillDirtyWord, refillVictimWay, dataBytes, finalDirty)

            writeValidForWay(refillValidWord, refillVictimWay, True)

            pushRsp(Mux(refillIsWrite, B(0, dataWidth bits), refillData), False, refillReqId)
          }
        }
        // An eviction acknowledgement needs nothing but retiring.
        state := resumeState
      }
    }

    // FILL_TAG: read port is driving this line's set index (see bramReadAddr
    // override). Advance to FILL_WRITE, or finish if the range is exhausted.
    is(LruCacheCoreState.FILL_TAG) {
      when(fillWord >= fillEndWord) {
        state := LruCacheCoreState.FILL_DRAIN
      } otherwise {
        state := LruCacheCoreState.FILL_WRITE
      }
    }

    // FILL_WRITE: tags/data for this line are available. Detect a hit, issue one
    // write-through zero write, and invalidate any cached copy.
    is(LruCacheCoreState.FILL_WRITE) {
      if (config.hasFill) {
        val idx = fillIndexOf(fillWord)
        val tg  = fillTagOf(fillWord)
        val wayTags   = (0 until wayCount).map(w => tagReadVal(w * tagWidth + tagWidth - 1 downto w * tagWidth))
        val wayValids = (0 until wayCount).map(w => getValid(w))
        val wayHits   = (0 until wayCount).map(w => wayValids(w) && wayTags(w) === tg)
        val anyHit    = wayHits.reduce(_ || _)
        val hitWay    = UInt(wayBits bits); hitWay := 0
        for (w <- 0 until wayCount) when(wayHits(w)) { hitWay := U(w, wayBits bits) }
        val hitData = Bits(dataWidth bits); hitData := dataReadVals(0)
        for (w <- 0 until wayCount) when(wayHits(w)) { hitData := dataReadVals(w) }

        // keepMask: 1 = keep (out-of-range word), 0 = write zero (in-range word)
        val keepMask = Bits(dataBytes bits)
        val inRange  = Vec(Bool(), wordsPerLine)
        for (j <- 0 until wordsPerLine) {
          val wa = fillWord + j
          inRange(j) := (wa >= fillStartWord) && (wa < fillEndWord)
          for (b <- 0 until wordBytes) keepMask(j * wordBytes + b) := !inRange(j)
        }
        val fullLine = inRange.reduce(_ && _)

        val fillData = Bits(dataWidth bits)
        for (j <- 0 until wordsPerLine) fillData(j * 32 + 31 downto j * 32) := io.fill.get.value
        // merge: keep? cached : fill  -> out-of-range stays cached, in-range zeroed
        val merged = mergeData(hitData, fillData, keepMask)

        io.memCmd.valid         := True
        io.memCmd.payload.addr  := fillByteAddr(fillWord).asBits
        io.memCmd.payload.write := True
        when(fullLine) {                       // interior: overwrite whole line
          io.memCmd.payload.data := fillData
          io.memCmd.payload.mask := B(0, dataBytes bits)
        } elsewhen (anyHit) {                  // partial hit: write merged whole line
          io.memCmd.payload.data := merged
          io.memCmd.payload.mask := B(0, dataBytes bits)
        } otherwise {                          // partial miss: masked write (preserve DRAM)
          io.memCmd.payload.data := fillData
          io.memCmd.payload.mask := keepMask
        }

        // Invalidate any cached copy so future reads refill zeros from memory.
        // The write port is otherwise unused in FILL_WRITE, so point it at the
        // line being zeroed rather than the lookup pipeline's set.
        when(anyHit) {
          bramWriteAddr := idx.resize(indexWidth max 1)
          writeValidForWay(validReadVal, hitWay, False)
        }

        when(io.memCmd.ready) {
          fillIssued := fillIssued + 1
          fillWord   := fillWord + wordsPerLine
          state      := LruCacheCoreState.FILL_TAG
        }
      }
    }

    // FILL_DRAIN: wait for all issued writes to be acknowledged, then finish.
    is(LruCacheCoreState.FILL_DRAIN) {
      when(fillRsp === fillIssued) {
        fillActive := False
        state      := LruCacheCoreState.IDLE
      }
    }
  }

  // Fill response accounting + busy (outside the switch: responses can arrive a
  // cycle after FILL_WRITE hands off to FILL_TAG/FILL_DRAIN). Safe to take
  // memRsp unconditionally because a fill only starts with nothing outstanding.
  if (config.hasFill) {
    io.fill.get.busy := fillActive
    when(fillActive) {
      io.memRsp.ready := True
      when(io.memRsp.valid) { fillRsp := fillRsp + 1 }
    }
  }

  io.busy := state =/= LruCacheCoreState.IDLE || outstandingWork
  io.debugState := state.asBits.asUInt.resized
}
