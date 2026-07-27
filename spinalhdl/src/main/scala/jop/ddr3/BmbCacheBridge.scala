package jop.ddr3

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.memory.MemFill

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
 */
class BmbCacheBridge(p: BmbParameter, cacheAddrWidth: Int, cacheDataWidth: Int) extends Component {
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

  val io = new Bundle {
    val bmb = slave(Bmb(p.access, p.invalidation))
    val cache = master(CacheFrontend(cacheAddrWidth, cacheDataWidth))
    // Block-fill sideband: zero a word range at full speed by writing whole
    // 128-bit cache lines (mask=0 => full-line write, cache skips the refill).
    val fill = slave(MemFill(p.access.addressWidth - 2))
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

  val rspFifo = StreamFifo(Fragment(BmbRsp(p)), 4)
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
  io.cache.rsp.ready := False

  val pendingRsp = Reg(Bool()) init (False)
  val pendingIsWrite = Reg(Bool()) init (False)
  val pendingSource = Reg(io.bmb.cmd.payload.fragment.source.clone)
  val pendingContext = Reg(io.bmb.cmd.payload.fragment.context.clone)
  val pendingLaneSelect = Reg(cloneOf(cmdLaneSelect)) init (0)

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

  // ---- Block-fill state ----
  // Zero [start,end) (word addresses) by writing whole 128-bit cache lines.
  // Interior lines use mask=0 (full-line write => cache skips the refill); the
  // <=2 edge lines mask out-of-range words. One cache response per line issued;
  // count req vs rsp for completion.
  val fw = p.access.addressWidth - 2                 // word-address width
  val wordsPerLine = cacheDataBytes / bmbDataBytes   // 4 words per 128-bit line
  val lineWordShift = log2Up(wordsPerLine)           // 2
  val fillActive    = Reg(Bool()) init(False)
  val fillLineWord  = Reg(UInt(fw bits)) init(0)     // first word of current line (aligned)
  val fillStartWord = Reg(UInt(fw bits)) init(0)
  val fillEndWord   = Reg(UInt(fw bits)) init(0)
  val fillReqCnt    = Reg(UInt(fw bits)) init(0)
  val fillRspCnt    = Reg(UInt(fw bits)) init(0)

  io.fill.busy := fillActive

  when(!fillActive && io.fill.cmd && io.fill.end > io.fill.start) {
    fillActive    := True
    fillStartWord := io.fill.start
    fillEndWord   := io.fill.end
    fillLineWord  := (io.fill.start >> lineWordShift) << lineWordShift  // line-align
    fillReqCnt    := 0
    fillRspCnt    := 0
  }
  when(fillActive && (fillLineWord >= fillEndWord) && (fillRspCnt === fillReqCnt)) {
    fillActive := False
  }

  when(fillActive) {
    io.bmb.cmd.ready := False
    // Issue one full/partial line write per accepted request.
    when(fillLineWord < fillEndWord) {
      io.cache.req.valid := True
      io.cache.req.payload.addr := ((fillLineWord << 2)(cacheAddrWidth - 1 downto 0)).asBits
      io.cache.req.payload.write := True
      io.cache.req.payload.data := B(0, cacheDataWidth bits)
      val m = Bits(cacheDataBytes bits)
      for (j <- 0 until wordsPerLine) {
        val inRange = (fillLineWord + j >= fillStartWord) && (fillLineWord + j < fillEndWord)
        val lo = j * bmbDataBytes
        m(lo + bmbDataBytes - 1 downto lo) :=
          Mux(inRange, B(0, bmbDataBytes bits), B((BigInt(1) << bmbDataBytes) - 1, bmbDataBytes bits))
      }
      io.cache.req.payload.mask := m
      when(io.cache.req.fire) {
        fillLineWord := fillLineWord + wordsPerLine
        fillReqCnt := fillReqCnt + 1
      }
    }
    // Swallow + count the write responses (not BMB responses).
    io.cache.rsp.ready := True
    when(io.cache.rsp.fire) { fillRspCnt := fillRspCnt + 1 }

  }.elsewhen(burstActive) {
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
        val rspDataNarrow = Bits(bmbDataWidth bits)
        rspDataNarrow := io.cache.rsp.payload.data(bmbDataWidth - 1 downto 0)
        if (laneCount > 1) {
          for (lane <- 0 until laneCount) {
            val dataLo = lane * bmbDataWidth
            val dataHi = dataLo + bmbDataWidth - 1
            when(burstLaneSelect === U(lane, burstLaneSelect.getWidth bits)) {
              rspDataNarrow := io.cache.rsp.payload.data(dataHi downto dataLo)
            }
          }
        }

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
  } elsewhen(!pendingRsp) {
    when(io.bmb.cmd.valid && cmdIsBurstRead) {
      // Accept burst read command, latch parameters
      io.bmb.cmd.ready := True
      burstActive := True
      burstAddr := io.bmb.cmd.payload.fragment.address(cacheAddrWidth - 1 downto 0)
      burstWordsTotal := ((io.bmb.cmd.payload.fragment.length +^ U(1)) >> 2).resized
      burstWordsDone := 0
      burstSource := io.bmb.cmd.payload.fragment.source
      burstContext := io.bmb.cmd.payload.fragment.context
      burstCacheReqSent := False
    } elsewhen(io.bmb.cmd.valid && !cmdSupported && !cmdIsBurstRead) {
      io.bmb.cmd.ready := rspFifo.io.push.ready
      rspFifo.io.push.valid := rspFifo.io.push.ready
      rspFifo.io.push.payload.fragment.source := io.bmb.cmd.payload.fragment.source
      rspFifo.io.push.payload.fragment.context := io.bmb.cmd.payload.fragment.context
      rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.ERROR
      rspFifo.io.push.payload.fragment.data := B(0, p.access.dataWidth bits)
    } elsewhen(io.bmb.cmd.valid && cmdSupported) {
      io.cache.req.valid := True
      io.bmb.cmd.ready := io.cache.req.ready

      when(io.cache.req.fire) {
        pendingRsp := True
        pendingIsWrite := io.bmb.cmd.payload.fragment.isWrite
        pendingSource := io.bmb.cmd.payload.fragment.source
        pendingContext := io.bmb.cmd.payload.fragment.context
        pendingLaneSelect := cmdLaneSelect
      }
    }
  } otherwise {
    io.cache.rsp.ready := rspFifo.io.push.ready
    when(io.cache.rsp.valid && rspFifo.io.push.ready) {
      rspFifo.io.push.valid := True
      rspFifo.io.push.payload.fragment.source := pendingSource
      rspFifo.io.push.payload.fragment.context := pendingContext
      rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.SUCCESS
      when(io.cache.rsp.payload.error) {
        rspFifo.io.push.payload.fragment.opcode := Bmb.Rsp.Opcode.ERROR
      }
      val rspDataNarrow = Bits(bmbDataWidth bits)
      rspDataNarrow := io.cache.rsp.payload.data(bmbDataWidth - 1 downto 0)
      if (laneCount > 1) {
        for (lane <- 0 until laneCount) {
          val dataLo = lane * bmbDataWidth
          val dataHi = dataLo + bmbDataWidth - 1
          when(pendingLaneSelect === U(lane, pendingLaneSelect.getWidth bits)) {
            rspDataNarrow := io.cache.rsp.payload.data(dataHi downto dataLo)
          }
        }
      }
      rspFifo.io.push.payload.fragment.data := rspDataNarrow
      when(pendingIsWrite) {
        rspFifo.io.push.payload.fragment.data := B(0, bmbDataWidth bits)
      }
      pendingRsp := False
    }
  }
}
