package jop.ddr3

import spinal.core._
import spinal.lib._

object LruCacheCoreState extends SpinalEnum {
  val IDLE, ISSUE_EVICT, WAIT_EVICT_RSP, ISSUE_REFILL, WAIT_REFILL_RSP = newElement()
}

class LruCacheCore(config: CacheConfig = CacheConfig()) extends Component {
  val addrWidth = config.addrWidth
  val dataWidth = config.dataWidth
  private val dataBytes = dataWidth / 8

  val io = new Bundle {
    val frontend = slave(CacheFrontend(addrWidth, dataWidth))
    val memCmd = master(Stream(CacheReq(addrWidth, dataWidth)))
    val memRsp = slave(Stream(CacheRsp(dataWidth)))
    val busy = out Bool()
    val debugState = out UInt(3 bits)
  }

  val cmdFifo = StreamFifo(CacheReq(addrWidth, dataWidth), 4)
  cmdFifo.io.push << io.frontend.req

  val rspFifo = StreamFifo(CacheRsp(dataWidth), 4)
  io.frontend.rsp << rspFifo.io.pop
  rspFifo.io.push.valid := False
  rspFifo.io.push.payload.data := B(0, dataWidth bits)
  rspFifo.io.push.payload.error := False

  val state = Reg(LruCacheCoreState()) init (LruCacheCoreState.IDLE)
  val pendingReq = Reg(CacheReq(addrWidth, dataWidth)) init (CacheReq(addrWidth, dataWidth).getZero)
  val pendingNeedRefill = Reg(Bool()) init (False)
  val pendingIndex = Reg(UInt(log2Up(config.setCount max 2) bits)) init (0)
  val pendingTag = Reg(Bits((addrWidth - log2Up(config.setCount) - log2Up(dataBytes)) bits)) init (0)
  val victimTag = Reg(Bits((addrWidth - log2Up(config.setCount) - log2Up(dataBytes)) bits)) init (0)

  val byteOffsetWidth = log2Up(dataWidth / 8)
  val indexWidth = log2Up(config.setCount)
  val tagWidth = addrWidth - indexWidth - byteOffsetWidth
  require(tagWidth > 0, "addrWidth too small for selected cache geometry")

  val validArray = Vec(Reg(Bool()) init (False), config.setCount)
  val tagArray = Vec(Reg(Bits(tagWidth bits)) init (0), config.setCount)
  val dataArray = Vec(Reg(Bits(dataWidth bits)) init (0), config.setCount)
  val dirtyArray = Vec(Reg(Bits(dataBytes bits)) init (0), config.setCount) // 1 bit per byte: 1 means dirty

  io.memCmd.valid := False
  io.memCmd.payload.addr := pendingReq.addr
  io.memCmd.payload.write := False
  io.memCmd.payload.data := B(0, dataWidth bits)
  io.memCmd.payload.mask := B((BigInt(1) << dataBytes) - 1, dataBytes bits)
  cmdFifo.io.pop.ready := False
  io.memRsp.ready := False

  val reqAddr = cmdFifo.io.pop.payload.addr
  val reqIndex = if (indexWidth == 0) U(0, pendingIndex.getWidth bits) else reqAddr(byteOffsetWidth + indexWidth - 1 downto byteOffsetWidth).asUInt.resize(pendingIndex.getWidth)
  val reqTag = reqAddr(addrWidth - 1 downto byteOffsetWidth + indexWidth)
  val reqHit = validArray(reqIndex) && tagArray(reqIndex) === reqTag

  val reqDataMerged = Bits(dataWidth bits)
  for (byte <- 0 until dataBytes) {
    val hi = byte * 8 + 7
    val lo = byte * 8
    reqDataMerged(hi downto lo) := Mux(cmdFifo.io.pop.payload.mask(byte), dataArray(reqIndex)(hi downto lo), cmdFifo.io.pop.payload.data(hi downto lo))
  }
  val reqIsFullLineWrite = cmdFifo.io.pop.payload.mask === 0
  val reqWriteDirtyMask = (~cmdFifo.io.pop.payload.mask).asBits

  val pendingLineAddr = Bits(addrWidth bits)
  pendingLineAddr := pendingReq.addr
  if (byteOffsetWidth > 0) {
    pendingLineAddr(byteOffsetWidth - 1 downto 0) := 0
  }

  val evictLineAddr = Bits(addrWidth bits)
  evictLineAddr := B(0, addrWidth bits)
  if (byteOffsetWidth > 0) {
    evictLineAddr(byteOffsetWidth - 1 downto 0) := 0
  }
  if (indexWidth > 0) {
    evictLineAddr(byteOffsetWidth + indexWidth - 1 downto byteOffsetWidth) := pendingIndex(indexWidth - 1 downto 0).asBits
  }
  evictLineAddr(addrWidth - 1 downto byteOffsetWidth + indexWidth) := victimTag

  val refillMerged = Bits(dataWidth bits)
  for (byte <- 0 until dataBytes) {
    val hi = byte * 8 + 7
    val lo = byte * 8
    refillMerged(hi downto lo) := Mux(pendingReq.mask(byte), io.memRsp.payload.data(hi downto lo), pendingReq.data(hi downto lo))
  }
  val pendingWriteDirtyMask = (~pendingReq.mask).asBits

  switch(state) {
    is(LruCacheCoreState.IDLE) {
      when(cmdFifo.io.pop.valid) {
        when(cmdFifo.io.pop.payload.write) {
          when(reqHit && rspFifo.io.push.ready) {
            // Write hit: update line in cache and mark touched bytes dirty.
            when(reqHit) {
              dataArray(reqIndex) := reqDataMerged
              tagArray(reqIndex) := reqTag
              validArray(reqIndex) := True
              dirtyArray(reqIndex) := dirtyArray(reqIndex) | reqWriteDirtyMask
            }
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := False
            cmdFifo.io.pop.ready := True
          } elsewhen(!reqHit) {
            // Write miss: allocate line and keep write dirty in cache.
            pendingReq := cmdFifo.io.pop.payload
            pendingNeedRefill := !reqIsFullLineWrite
            pendingIndex := reqIndex
            pendingTag := reqTag
            victimTag := tagArray(reqIndex)
            cmdFifo.io.pop.ready := True

            val victimDirty = validArray(reqIndex) && (dirtyArray(reqIndex) =/= 0)
            when(victimDirty) {
              state := LruCacheCoreState.ISSUE_EVICT
            } otherwise {
              state := LruCacheCoreState.ISSUE_REFILL
            }
          }
        } otherwise {
          when(reqHit && rspFifo.io.push.ready) {
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := dataArray(reqIndex)
            rspFifo.io.push.payload.error := False
            cmdFifo.io.pop.ready := True
          } elsewhen(!reqHit) {
            // Read miss.
            pendingReq := cmdFifo.io.pop.payload
            pendingNeedRefill := True
            pendingIndex := reqIndex
            pendingTag := reqTag
            victimTag := tagArray(reqIndex)
            cmdFifo.io.pop.ready := True

            val victimDirty = validArray(reqIndex) && (dirtyArray(reqIndex) =/= 0)
            when(victimDirty) {
              state := LruCacheCoreState.ISSUE_EVICT
            } otherwise {
              state := LruCacheCoreState.ISSUE_REFILL
            }
          }
        }
      }
    }

    is(LruCacheCoreState.ISSUE_EVICT) {
      io.memCmd.valid := True
      io.memCmd.payload.addr := evictLineAddr
      io.memCmd.payload.write := True
      io.memCmd.payload.data := dataArray(pendingIndex)
      io.memCmd.payload.mask := (~dirtyArray(pendingIndex)).asBits
      when(io.memCmd.ready) {
        state := LruCacheCoreState.WAIT_EVICT_RSP
      }
    }

    is(LruCacheCoreState.WAIT_EVICT_RSP) {
      when(io.memRsp.valid) {
        when(io.memRsp.payload.error) {
          // Error: report to frontend via rspFifo (needs room).
          io.memRsp.ready := rspFifo.io.push.ready
          when(rspFifo.io.push.ready) {
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := True
            state := LruCacheCoreState.IDLE
          }
        } otherwise {
          // Eviction completed — accept unconditionally (no rspFifo push needed).
          io.memRsp.ready := True
          validArray(pendingIndex) := False
          dirtyArray(pendingIndex) := B(0, dataBytes bits)
          state := LruCacheCoreState.ISSUE_REFILL
        }
      }
    }

    is(LruCacheCoreState.ISSUE_REFILL) {
      when(pendingNeedRefill) {
        io.memCmd.valid := True
        io.memCmd.payload.addr := pendingLineAddr
        io.memCmd.payload.write := False
        when(io.memCmd.ready) {
          state := LruCacheCoreState.WAIT_REFILL_RSP
        }
      } otherwise {
        when(rspFifo.io.push.ready) {
          // Full-line write miss: allocate directly without reading DDR.
          validArray(pendingIndex) := True
          tagArray(pendingIndex) := pendingTag
          dataArray(pendingIndex) := pendingReq.data
          dirtyArray(pendingIndex) := pendingWriteDirtyMask
          rspFifo.io.push.valid := True
          rspFifo.io.push.payload.data := B(0, dataWidth bits)
          rspFifo.io.push.payload.error := False
          state := LruCacheCoreState.IDLE
        }
      }
    }

    is(LruCacheCoreState.WAIT_REFILL_RSP) {
      io.memRsp.ready := rspFifo.io.push.ready
      when(io.memRsp.valid && rspFifo.io.push.ready) {
        when(io.memRsp.payload.error) {
          rspFifo.io.push.valid := True
          rspFifo.io.push.payload.data := B(0, dataWidth bits)
          rspFifo.io.push.payload.error := True
          state := LruCacheCoreState.IDLE
        } otherwise {
          validArray(pendingIndex) := True
          tagArray(pendingIndex) := pendingTag
          when(pendingReq.write) {
            dataArray(pendingIndex) := refillMerged
            dirtyArray(pendingIndex) := pendingWriteDirtyMask
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := False
          } otherwise {
            dataArray(pendingIndex) := io.memRsp.payload.data
            dirtyArray(pendingIndex) := B(0, dataBytes bits)
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := io.memRsp.payload.data
            rspFifo.io.push.payload.error := False
          }
          state := LruCacheCoreState.IDLE
        }
      }
    }
  }

  io.busy := state =/= LruCacheCoreState.IDLE
  io.debugState := state.asBits.asUInt.resized
}
