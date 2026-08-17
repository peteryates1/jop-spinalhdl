package jop.ddr3

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * BMB-to-Cache bridge for JOP DDR3.
 *
 * Accepts JOP's 32-bit BMB transactions and maps them into the 128-bit
 * cache frontend interface.
 *
 * Supported commands:
 *   - Single-word read/write (length=3, i.e. one 4-byte word)
 *   - Burst read (length > 3): decomposed into sequential cache lookups,
 *     returns multi-beat BMB response with last=True on final beat.
 *
 * Mask convention translation:
 *   BMB:          mask bit = 1 means "WRITE this byte"
 *   LruCacheCore: mask bit = 1 means "KEEP cached byte" (same as MIG app_wdf_mask)
 * So the BMB mask must be INVERTED before passing to the cache.
 * Non-lane bytes default to mask=1 (keep cached).
 *
 * `outstanding` sets how many single-beat commands may be in the cache at once.
 * At 1 the bridge behaves exactly as it always has — one command, then wait.
 * Above 1 each accepted command takes a free slot, whose index is sent to the
 * cache as the request id and comes back on the response, so completions need
 * not be in issue order. That is legal on BMB because ordering is only required
 * *per source*, and BmbMemoryController keeps a single transaction outstanding
 * per core; the out-of-order completions are therefore always across sources.
 *
 * Note this is the component that decides whether concurrency is possible at
 * all: an MSHR file inside LruCacheCore buys nothing while the bridge refuses
 * to present it a second request.
 */
class BmbCacheBridge(p: BmbParameter, cacheAddrWidth: Int, cacheDataWidth: Int,
                     outstanding: Int = 1) extends Component {
  private val bmbDataWidth = p.access.dataWidth
  private val bmbDataBytes = bmbDataWidth / 8
  private val cacheDataBytes = cacheDataWidth / 8
  private val cacheByteOffsetWidth = log2Up(cacheDataBytes)
  private val bmbByteOffsetWidth = log2Up(bmbDataBytes)
  private val laneCount = cacheDataWidth / bmbDataWidth
  private val laneSelWidth = log2Up(laneCount)
  private val burstCountWidth = log2Up((1 << p.access.lengthWidth) / bmbDataBytes + 1)

  require(Set(8, 16, 32, 64, 128).contains(bmbDataWidth), s"unsupported BMB data width $bmbDataWidth")
  require(bmbDataWidth % 8 == 0, "BMB data width must be byte aligned")
  require(cacheDataWidth % 8 == 0, "cache data width must be byte aligned")
  require(cacheDataWidth >= bmbDataWidth, "cache data width must be >= BMB data width")
  require((cacheDataWidth % bmbDataWidth) == 0, "cache data width must be an integer multiple of BMB data width")
  require(outstanding >= 1, "outstanding must be at least 1")

  /** Slot index width; 0 at outstanding = 1, which drops the id field entirely. */
  val idWidth = log2Up(outstanding)

  val io = new Bundle {
    val bmb = slave(Bmb(p.access, p.invalidation))
    val cache = master(CacheFrontend(cacheAddrWidth, cacheDataWidth, idWidth))
  }

  val cmdAddrByteOffset = if (cacheByteOffsetWidth == 0) {
    U(0, 1 bits)
  } else {
    io.bmb.cmd.payload.fragment.address(cacheByteOffsetWidth - 1 downto 0)
  }
  val cmdAlignedOnWord = if (bmbByteOffsetWidth == 0) {
    True
  } else {
    cmdAddrByteOffset(bmbByteOffsetWidth - 1 downto 0) === 0
  }
  val cmdLaneSelect = if (laneSelWidth == 0) {
    U(0, 1 bits)
  } else {
    cmdAddrByteOffset(cacheByteOffsetWidth - 1 downto bmbByteOffsetWidth)
  }

  val cmdDataExpanded = Bits(cacheDataWidth bits)
  val cmdMaskExpanded = Bits(cacheDataBytes bits)
  // Invert BMB mask for cache convention: BMB mask=1 means "write", cache mask=1 means "keep cached".
  if (laneCount == 1) {
    cmdDataExpanded := io.bmb.cmd.payload.fragment.data.resized
    cmdMaskExpanded := (~io.bmb.cmd.payload.fragment.mask).resized
  } else {
    cmdDataExpanded := B(0, cacheDataWidth bits)
    cmdMaskExpanded := B((BigInt(1) << cacheDataBytes) - 1, cacheDataBytes bits) // All 1s = keep cached
    for (lane <- 0 until laneCount) {
      val dataLo = lane * bmbDataWidth
      val dataHi = dataLo + bmbDataWidth - 1
      val maskLo = lane * bmbDataBytes
      val maskHi = maskLo + bmbDataBytes - 1
      when(cmdLaneSelect === U(lane, cmdLaneSelect.getWidth bits)) {
        cmdDataExpanded(dataHi downto dataLo) := io.bmb.cmd.payload.fragment.data
        cmdMaskExpanded(maskHi downto maskLo) := ~io.bmb.cmd.payload.fragment.mask
      }
    }
  }

  val rspFifo = StreamFifo(Fragment(BmbRsp(p)), 4 max outstanding)
  io.bmb.rsp << rspFifo.io.pop
  rspFifo.io.push.valid := False
  rspFifo.io.push.payload.fragment.source := io.bmb.cmd.payload.fragment.source
  rspFifo.io.push.payload.fragment.context := io.bmb.cmd.payload.fragment.context
  rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
  rspFifo.io.push.payload.fragment.data := B(0, p.access.dataWidth bits)
  if (p.access.canExclusive) {
    rspFifo.io.push.payload.fragment.exclusive := False
  }
  rspFifo.io.push.payload.last := True

  io.cache.req.valid := False
  io.cache.req.payload.addr := io.bmb.cmd.payload.fragment.address(cacheAddrWidth - 1 downto 0).asBits
  io.cache.req.payload.write := io.bmb.cmd.payload.fragment.isWrite
  io.cache.req.payload.data := cmdDataExpanded
  io.cache.req.payload.mask := cmdMaskExpanded
  io.cache.req.payload.driveId(U(0))
  io.cache.rsp.ready := False

  // --- Outstanding single-beat slots ---
  // One slot per request the cache may be working on. The slot index travels
  // with the request as its id and comes back on the response, which is what
  // lets completions arrive in an order other than the issue order.
  val slotBusy = Vec(RegInit(False), outstanding)
  val slotIsWrite = Vec(Reg(Bool()), outstanding)
  val slotSource = Vec(Reg(io.bmb.cmd.payload.fragment.source.clone), outstanding)
  val slotContext = Vec(Reg(io.bmb.cmd.payload.fragment.context.clone), outstanding)
  val slotLaneSelect = Vec(Reg(cloneOf(cmdLaneSelect)) init (0), outstanding)

  val anyOutstanding = slotBusy.reduce(_ || _)

  val slotFree = Bits(outstanding bits)
  for (i <- 0 until outstanding) slotFree(i) := !slotBusy(i)
  val hasFreeSlot = slotFree.orR
  val allocOh = OHMasking.first(slotFree)
  val allocId = if (outstanding == 1) U(0, 1 bits) else OHToUInt(allocOh)

  /** Does the returning response belong to slot i? Constant at outstanding = 1. */
  private def slotMatch(i: Int): Bool =
    if (outstanding == 1) True else io.cache.rsp.payload.idValue === U(i, idWidth bits)

  // One-hot: which outstanding slot the response on io.cache.rsp belongs to.
  val retSlotOh = Bits(outstanding bits)
  for (i <- 0 until outstanding) retSlotOh(i) := slotMatch(i) && slotBusy(i)
  val retSlotBusy = retSlotOh.orR

  // Slot lookup for the response currently on io.cache.rsp.
  val retIsWrite = Bool()
  val retSource = cloneOf(io.bmb.cmd.payload.fragment.source)
  val retContext = cloneOf(io.bmb.cmd.payload.fragment.context)
  val retLaneSelect = cloneOf(cmdLaneSelect)
  retIsWrite := slotIsWrite(0)
  retSource := slotSource(0)
  retContext := slotContext(0)
  retLaneSelect := slotLaneSelect(0)
  for (i <- 1 until outstanding) {
    when(slotMatch(i)) {
      retIsWrite := slotIsWrite(i)
      retSource := slotSource(i)
      retContext := slotContext(i)
      retLaneSelect := slotLaneSelect(i)
    }
  }

  // Burst read state
  val burstActive = Reg(Bool()) init(False)
  val burstAddr = Reg(UInt(cacheAddrWidth bits)) init(0)
  val burstWordsTotal = Reg(UInt(burstCountWidth bits)) init(0)
  val burstWordsDone = Reg(UInt(burstCountWidth bits)) init(0)
  val burstSource = Reg(io.bmb.cmd.payload.fragment.source.clone)
  val burstContext = Reg(io.bmb.cmd.payload.fragment.context.clone)
  val burstCacheReqSent = Reg(Bool()) init(False)

  val cmdIsReadOrWrite = io.bmb.cmd.payload.fragment.isRead || io.bmb.cmd.payload.fragment.isWrite
  // JOP sends length=3 for a single 32-bit word (bmbDataBytes-1 = 4-1 = 3).
  // Accept any single-beat command whose length matches one BMB data word.
  val cmdIsSingleBeat = io.bmb.cmd.payload.last && (io.bmb.cmd.payload.fragment.length === (bmbDataBytes - 1))
  val cmdSupported = cmdIsReadOrWrite && cmdIsSingleBeat && cmdAlignedOnWord
  val cmdIsBurstRead = io.bmb.cmd.payload.fragment.isRead &&
                       io.bmb.cmd.payload.last &&
                       (io.bmb.cmd.payload.fragment.length > (bmbDataBytes - 1)) &&
                       cmdAlignedOnWord

  io.bmb.cmd.ready := False

  /** Extract the addressed 32-bit lane from a cache line response. */
  private def narrowRsp(laneSelect: UInt): Bits = {
    val narrow = Bits(bmbDataWidth bits)
    narrow := io.cache.rsp.payload.data(bmbDataWidth - 1 downto 0)
    if (laneCount > 1) {
      for (lane <- 0 until laneCount) {
        val dataLo = lane * bmbDataWidth
        val dataHi = dataLo + bmbDataWidth - 1
        when(laneSelect === U(lane, laneSelect.getWidth bits)) {
          narrow := io.cache.rsp.payload.data(dataHi downto dataLo)
        }
      }
    }
    narrow
  }

  // --- Single-beat response return ---
  // Deliberately outside the command-acceptance tree below: returning a
  // response and accepting the next command in the same cycle is the whole
  // point of having more than one slot. A burst owns io.cache.rsp while it
  // runs, so this is inhibited then.
  val returnPending = !burstActive && io.cache.rsp.valid
  val returnFire = returnPending && rspFifo.io.push.ready
  when(!burstActive) {
    io.cache.rsp.ready := rspFifo.io.push.ready
    when(returnFire) {
      rspFifo.io.push.valid := True
      rspFifo.io.push.payload.fragment.source := retSource
      rspFifo.io.push.payload.fragment.context := retContext
      rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
      when(io.cache.rsp.payload.error) {
        rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.ERROR
      }
      rspFifo.io.push.payload.fragment.data := narrowRsp(retLaneSelect)
      when(retIsWrite) {
        rspFifo.io.push.payload.fragment.data := B(0, bmbDataWidth bits)
      }
      rspFifo.io.push.payload.last := True

      for (i <- 0 until outstanding) {
        when(retSlotOh(i)) { slotBusy(i) := False }
      }
    }
  }

  when(burstActive) {
    // ---- Burst read processing ----
    // Two sub-states: issue cache read (!burstCacheReqSent), await response (burstCacheReqSent)
    when(!burstCacheReqSent) {
      io.cache.req.valid := True
      io.cache.req.payload.addr := burstAddr(cacheAddrWidth - 1 downto 0).asBits
      io.cache.req.payload.write := False
      when(io.cache.req.fire) {
        burstCacheReqSent := True
      }
    } otherwise {
      io.cache.rsp.ready := rspFifo.io.push.ready
      when(io.cache.rsp.valid && rspFifo.io.push.ready) {
        // Extract 32-bit lane from 128-bit cache response
        val burstLaneSelect = if (laneSelWidth == 0) {
          U(0, 1 bits)
        } else {
          burstAddr(cacheByteOffsetWidth - 1 downto bmbByteOffsetWidth)
        }
        val rspDataNarrow = narrowRsp(burstLaneSelect)

        rspFifo.io.push.valid := True
        rspFifo.io.push.payload.fragment.source := burstSource
        rspFifo.io.push.payload.fragment.context := burstContext
        rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
        rspFifo.io.push.payload.fragment.data := rspDataNarrow
        when(io.cache.rsp.payload.error) {
          rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.ERROR
        }

        val nextDone = burstWordsDone + 1
        val isLastBeat = nextDone >= burstWordsTotal
        rspFifo.io.push.payload.last := isLastBeat

        burstWordsDone := nextDone
        burstAddr := burstAddr + bmbDataBytes
        burstCacheReqSent := False

        when(isLastBeat) {
          burstActive := False
        }
      }
    }
  } otherwise {
    when(io.bmb.cmd.valid && cmdIsBurstRead) {
      // A burst takes over io.cache.rsp wholesale — it matches responses to
      // beats by counting, not by id — so it may only start once the
      // single-beat slots have drained.
      when(!anyOutstanding) {
        io.bmb.cmd.ready := True
        burstActive := True
        burstAddr := io.bmb.cmd.payload.fragment.address(cacheAddrWidth - 1 downto 0)
        burstWordsTotal := ((io.bmb.cmd.payload.fragment.length +^ U(1)) >> 2).resized
        burstWordsDone := 0
        burstSource := io.bmb.cmd.payload.fragment.source
        burstContext := io.bmb.cmd.payload.fragment.context
        burstCacheReqSent := False
      }
    } elsewhen(io.bmb.cmd.valid && !cmdSupported) {
      // The error reply is pushed straight into the response FIFO, so it has to
      // stand aside for a real response arriving on the same cycle.
      when(!returnPending) {
        io.bmb.cmd.ready := rspFifo.io.push.ready
        rspFifo.io.push.valid := rspFifo.io.push.ready
        rspFifo.io.push.payload.fragment.source := io.bmb.cmd.payload.fragment.source
        rspFifo.io.push.payload.fragment.context := io.bmb.cmd.payload.fragment.context
        rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.ERROR
        rspFifo.io.push.payload.fragment.data := B(0, p.access.dataWidth bits)
        rspFifo.io.push.payload.last := True
      }
    } elsewhen(io.bmb.cmd.valid && cmdSupported && hasFreeSlot) {
      io.cache.req.valid := True
      io.cache.req.payload.driveId(allocId)
      io.bmb.cmd.ready := io.cache.req.ready

      when(io.cache.req.fire) {
        for (i <- 0 until outstanding) {
          when(allocOh(i)) {
            slotBusy(i) := True
            slotIsWrite(i) := io.bmb.cmd.payload.fragment.isWrite
            slotSource(i) := io.bmb.cmd.payload.fragment.source
            slotContext(i) := io.bmb.cmd.payload.fragment.context
            slotLaneSelect(i) := cmdLaneSelect
          }
        }
      }
    }
  }
}
