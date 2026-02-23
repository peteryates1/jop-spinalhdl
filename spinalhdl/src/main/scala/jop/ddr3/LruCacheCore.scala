package jop.ddr3

import spinal.core._
import spinal.lib._

object LruCacheCoreState extends SpinalEnum {
  val IDLE, CHECK_HIT, ISSUE_EVICT, WAIT_EVICT_RSP, ISSUE_REFILL, WAIT_REFILL_RSP = newElement()
}

class LruCacheCore(config: CacheConfig = CacheConfig()) extends Component {
  val addrWidth = config.addrWidth
  val dataWidth = config.dataWidth
  private val dataBytes = dataWidth / 8
  private val wayCount = config.wayCount
  private val setCount = config.setCount

  val io = new Bundle {
    val frontend = slave(CacheFrontend(addrWidth, dataWidth))
    val memCmd = master(Stream(CacheReq(addrWidth, dataWidth)))
    val memRsp = slave(Stream(CacheRsp(dataWidth)))
    val busy = out Bool()
    val debugState = out UInt(3 bits)
  }

  // --- Geometry ---
  val byteOffsetWidth = log2Up(dataBytes)
  val indexWidth = log2Up(setCount)
  val tagWidth = addrWidth - indexWidth - byteOffsetWidth
  require(tagWidth > 0, "addrWidth too small for selected cache geometry")

  val wayBits = if (wayCount > 1) log2Up(wayCount) else 1

  // --- FIFOs ---
  val cmdFifo = StreamFifo(CacheReq(addrWidth, dataWidth), 4)
  cmdFifo.io.push << io.frontend.req

  val rspFifo = StreamFifo(CacheRsp(dataWidth), 4)
  io.frontend.rsp << rspFifo.io.pop
  rspFifo.io.push.valid := False
  rspFifo.io.push.payload.data := B(0, dataWidth bits)
  rspFifo.io.push.payload.error := False

  // --- BRAM Arrays ---
  val dataMems = (0 until wayCount).map(_ => Mem(Bits(dataWidth bits), setCount))
  val tagMem = Mem(Bits(wayCount * tagWidth bits), setCount)
  val dirtyMem = Mem(Bits(wayCount * dataBytes bits), setCount)

  // --- Register Arrays ---
  val validFlat = Vec(Reg(Bool()) init (False), setCount * wayCount)
  val plruBits = if (wayCount == 4) 3 else if (wayCount == 2) 1 else 0
  val lruArray = if (plruBits > 0) Vec(Reg(Bits(plruBits bits)) init (0), setCount) else null

  // Valid array accessors
  private def validIdx(setIdx: UInt, way: Int): UInt = {
    if (wayCount == 1) setIdx.resize(log2Up(setCount * wayCount))
    else (setIdx * U(wayCount) + U(way)).resize(log2Up(setCount * wayCount))
  }
  def getValid(setIdx: UInt, way: Int): Bool = validFlat(validIdx(setIdx, way))
  def setValidStatic(setIdx: UInt, way: Int, value: Bool): Unit = {
    validFlat(validIdx(setIdx, way)) := value
  }
  def setValidForWay(setIdx: UInt, targetWay: UInt, value: Bool): Unit = {
    for (w <- 0 until wayCount) {
      when(targetWay === U(w, wayBits bits)) {
        setValidStatic(setIdx, w, value)
      }
    }
  }

  // --- State Machine ---
  val state = Reg(LruCacheCoreState()) init (LruCacheCoreState.IDLE)

  // --- Pending Registers ---
  val pendingReq = Reg(CacheReq(addrWidth, dataWidth)) init (CacheReq(addrWidth, dataWidth).getZero)
  val pendingNeedRefill = Reg(Bool()) init (False)
  val pendingIndex = Reg(UInt(indexWidth max 1 bits)) init (0)
  val pendingTag = Reg(Bits(tagWidth bits)) init (0)
  val pendingVictimWay = Reg(UInt(wayBits bits)) init (0)
  val pendingVictimData = Reg(Bits(dataWidth bits)) init (0)
  val pendingVictimTag = Reg(Bits(tagWidth bits)) init (0)
  val pendingVictimDirty = Reg(Bits(dataBytes bits)) init (0)
  val pendingTagWord = Reg(Bits(wayCount * tagWidth bits)) init (0)
  val pendingDirtyWord = Reg(Bits(wayCount * dataBytes bits)) init (0)

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
  when(state =/= LruCacheCoreState.IDLE) {
    bramReadAddr := pendingIndex
  }

  val dataReadVals = dataMems.map(_.readSync(bramReadAddr.resize(indexWidth max 1)))
  val tagReadVal = tagMem.readSync(bramReadAddr.resize(indexWidth max 1))
  val dirtyReadVal = dirtyMem.readSync(bramReadAddr.resize(indexWidth max 1))

  // --- BRAM Write Ports (single port per BRAM, muxed by state) ---
  // Each BRAM gets ONE write port with address/data/enable muxed across states.
  // This avoids the SpinalHDL issue where Mem.write(enable=...) doesn't include
  // enclosing when/is conditions.

  // Data BRAM write signals (per way)
  val dataWriteEnable = Vec(Bool(), wayCount)
  val dataWriteAddr = UInt(indexWidth max 1 bits)
  val dataWriteData = Bits(dataWidth bits)
  dataWriteEnable.foreach(_ := False)
  dataWriteAddr := pendingIndex.resize(indexWidth max 1)
  dataWriteData := B(0, dataWidth bits)

  for (w <- 0 until wayCount) {
    dataMems(w).write(dataWriteAddr, dataWriteData, enable = dataWriteEnable(w))
  }

  // Tag BRAM write signals
  val tagWriteEnable = Bool()
  val tagWriteData = Bits(wayCount * tagWidth bits)
  tagWriteEnable := False
  tagWriteData := B(0, wayCount * tagWidth bits)
  tagMem.write(pendingIndex.resize(indexWidth max 1), tagWriteData, enable = tagWriteEnable)

  // Dirty BRAM write signals
  val dirtyWriteEnable = Bool()
  val dirtyWriteData = Bits(wayCount * dataBytes bits)
  dirtyWriteEnable := False
  dirtyWriteData := B(0, wayCount * dataBytes bits)
  dirtyMem.write(pendingIndex.resize(indexWidth max 1), dirtyWriteData, enable = dirtyWriteEnable)

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

  // --- State Machine ---
  switch(state) {
    is(LruCacheCoreState.IDLE) {
      when(cmdFifo.io.pop.valid) {
        pendingReq := cmdFifo.io.pop.payload
        pendingIndex := reqIndex
        pendingTag := reqAddr(addrWidth - 1 downto byteOffsetWidth + indexWidth)
        cmdFifo.io.pop.ready := True
        state := LruCacheCoreState.CHECK_HIT
      }
    }

    is(LruCacheCoreState.CHECK_HIT) {
      val wayTags = (0 until wayCount).map(w => tagReadVal(w * tagWidth + tagWidth - 1 downto w * tagWidth))
      val wayDirtys = (0 until wayCount).map(w => dirtyReadVal(w * dataBytes + dataBytes - 1 downto w * dataBytes))
      val wayValids = (0 until wayCount).map(w => getValid(pendingIndex, w))

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
        victimWay := Mux(hasInvalid, firstInvalidWay, plruVictim(lruArray(pendingIndex)))
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
      val victimIsDirty = victimValid && (victimDirtyVal =/= 0)

      val reqIsFullLineWrite = pendingReq.mask === 0
      val reqWriteDirtyMask = (~pendingReq.mask).asBits

      when(anyHit) {
        when(pendingReq.write) {
          when(rspFifo.io.push.ready) {
            val merged = mergeData(hitData, pendingReq.data, pendingReq.mask)
            // Write data BRAM for hit way
            dataWriteData := merged
            for (w <- 0 until wayCount) {
              when(wayHits(w)) { dataWriteEnable(w) := True }
            }

            // Update dirty BRAM
            val newDirtyWord = Bits(wayCount * dataBytes bits)
            newDirtyWord := dirtyReadVal
            for (w <- 0 until wayCount) {
              when(wayHits(w)) {
                newDirtyWord(w * dataBytes + dataBytes - 1 downto w * dataBytes) := wayDirtys(w) | reqWriteDirtyMask
              }
            }
            dirtyWriteEnable := True
            dirtyWriteData := newDirtyWord

            if (plruBits > 0) {
              lruArray(pendingIndex) := plruUpdate(lruArray(pendingIndex), hitWay)
            }

            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := False
            state := LruCacheCoreState.IDLE
          }
        } otherwise {
          when(rspFifo.io.push.ready) {
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := hitData
            rspFifo.io.push.payload.error := False
            if (plruBits > 0) {
              lruArray(pendingIndex) := plruUpdate(lruArray(pendingIndex), hitWay)
            }
            state := LruCacheCoreState.IDLE
          }
        }
      } otherwise {
        pendingVictimWay := victimWay
        pendingVictimData := victimData
        pendingVictimTag := victimTagVal
        pendingVictimDirty := victimDirtyVal
        pendingTagWord := tagReadVal
        pendingDirtyWord := dirtyReadVal
        pendingNeedRefill := !(pendingReq.write && reqIsFullLineWrite)
        when(victimIsDirty) {
          state := LruCacheCoreState.ISSUE_EVICT
        } otherwise {
          state := LruCacheCoreState.ISSUE_REFILL
        }
      }
    }

    is(LruCacheCoreState.ISSUE_EVICT) {
      io.memCmd.valid := True
      io.memCmd.payload.addr := makeEvictAddr(pendingVictimTag, pendingIndex)
      io.memCmd.payload.write := True
      io.memCmd.payload.data := pendingVictimData
      io.memCmd.payload.mask := (~pendingVictimDirty).asBits
      when(io.memCmd.ready) {
        state := LruCacheCoreState.WAIT_EVICT_RSP
      }
    }

    is(LruCacheCoreState.WAIT_EVICT_RSP) {
      when(io.memRsp.valid) {
        when(io.memRsp.payload.error) {
          io.memRsp.ready := rspFifo.io.push.ready
          when(rspFifo.io.push.ready) {
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := True
            state := LruCacheCoreState.IDLE
          }
        } otherwise {
          io.memRsp.ready := True
          setValidForWay(pendingIndex, pendingVictimWay, False)
          val clearedDirty = Bits(wayCount * dataBytes bits)
          clearedDirty := pendingDirtyWord
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) {
              clearedDirty(w * dataBytes + dataBytes - 1 downto w * dataBytes) := B(0, dataBytes bits)
            }
          }
          pendingDirtyWord := clearedDirty
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
          val reqWriteDirtyMask = (~pendingReq.mask).asBits

          // Write data/tag/dirty BRAMs for victim way
          dataWriteData := pendingReq.data
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) { dataWriteEnable(w) := True }
          }

          val newTagWord = Bits(wayCount * tagWidth bits)
          newTagWord := pendingTagWord
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) {
              newTagWord(w * tagWidth + tagWidth - 1 downto w * tagWidth) := pendingTag
            }
          }
          tagWriteEnable := True
          tagWriteData := newTagWord

          val newDirtyWord = Bits(wayCount * dataBytes bits)
          newDirtyWord := pendingDirtyWord
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) {
              newDirtyWord(w * dataBytes + dataBytes - 1 downto w * dataBytes) := reqWriteDirtyMask
            }
          }
          dirtyWriteEnable := True
          dirtyWriteData := newDirtyWord

          setValidForWay(pendingIndex, pendingVictimWay, True)
          if (plruBits > 0) {
            lruArray(pendingIndex) := plruUpdate(lruArray(pendingIndex), pendingVictimWay)
          }

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
          val refillData = io.memRsp.payload.data
          val finalData = Bits(dataWidth bits)
          val finalDirty = Bits(dataBytes bits)

          when(pendingReq.write) {
            finalData := mergeData(refillData, pendingReq.data, pendingReq.mask)
            finalDirty := (~pendingReq.mask).asBits
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := B(0, dataWidth bits)
            rspFifo.io.push.payload.error := False
          } otherwise {
            finalData := refillData
            finalDirty := B(0, dataBytes bits)
            rspFifo.io.push.valid := True
            rspFifo.io.push.payload.data := refillData
            rspFifo.io.push.payload.error := False
          }

          // Write data/tag/dirty BRAMs for victim way
          dataWriteData := finalData
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) { dataWriteEnable(w) := True }
          }

          val newTagWord = Bits(wayCount * tagWidth bits)
          newTagWord := pendingTagWord
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) {
              newTagWord(w * tagWidth + tagWidth - 1 downto w * tagWidth) := pendingTag
            }
          }
          tagWriteEnable := True
          tagWriteData := newTagWord

          val newDirtyWord = Bits(wayCount * dataBytes bits)
          newDirtyWord := pendingDirtyWord
          for (w <- 0 until wayCount) {
            when(pendingVictimWay === U(w, wayBits bits)) {
              newDirtyWord(w * dataBytes + dataBytes - 1 downto w * dataBytes) := finalDirty
            }
          }
          dirtyWriteEnable := True
          dirtyWriteData := newDirtyWord

          setValidForWay(pendingIndex, pendingVictimWay, True)
          if (plruBits > 0) {
            lruArray(pendingIndex) := plruUpdate(lruArray(pendingIndex), pendingVictimWay)
          }

          state := LruCacheCoreState.IDLE
        }
      }
    }
  }

  io.busy := state =/= LruCacheCoreState.IDLE
  io.debugState := state.asBits.asUInt.resized
}
