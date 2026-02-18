package jop.ddr3

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * BMB-to-Cache bridge for JOP DDR3.
 *
 * Accepts JOP's 32-bit BMB transactions (length=3, i.e. one 4-byte word)
 * and maps them into the 128-bit cache frontend interface.
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

  require(Set(8, 16, 32, 64, 128).contains(bmbDataWidth), s"unsupported BMB data width $bmbDataWidth")
  require(bmbDataWidth % 8 == 0, "BMB data width must be byte aligned")
  require(cacheDataWidth % 8 == 0, "cache data width must be byte aligned")
  require(cacheDataWidth >= bmbDataWidth, "cache data width must be >= BMB data width")
  require((cacheDataWidth % bmbDataWidth) == 0, "cache data width must be an integer multiple of BMB data width")

  val io = new Bundle {
    val bmb = slave(Bmb(p.access, p.invalidation))
    val cache = master(CacheFrontend(cacheAddrWidth, cacheDataWidth))
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

  val cmdIsReadOrWrite = io.bmb.cmd.payload.fragment.isRead || io.bmb.cmd.payload.fragment.isWrite
  // JOP sends length=3 for a single 32-bit word (bmbDataBytes-1 = 4-1 = 3).
  // Accept any single-beat command whose length matches one BMB data word.
  val cmdIsSingleBeat = io.bmb.cmd.payload.last && (io.bmb.cmd.payload.fragment.length === (bmbDataBytes - 1))
  val cmdSupported = cmdIsReadOrWrite && cmdIsSingleBeat && cmdAlignedOnWord

  io.bmb.cmd.ready := False

  when(!pendingRsp) {
    when(io.bmb.cmd.valid && !cmdSupported) {
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
