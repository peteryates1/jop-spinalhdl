package jop.ddr3

import spinal.core._
import spinal.lib._

/**
 * Cache line-width scaling check for the DDR2 bring-up.
 *
 * LruCacheCore takes its line width from CacheConfig.dataWidth and derives every
 * register, memory and mask from it, so it should elaborate at any byte-aligned
 * width. This confirms 128, 256 and 512-bit lines build cleanly, with the fill
 * path enabled (the FILL_* states do their own line/word arithmetic).
 *
 * Why it matters: the A-E115FB's DDR2 has a 128-bit local interface with a
 * 4-beat minimum burst (32 bytes = 256 bits), so a 128-bit line would use half
 * of each burst. A 256-bit line matches the burst exactly.
 *
 * Note CacheToMigAdapter is NOT in this check — it hardcodes 128 bits because
 * that is the Xilinx MIG app-interface width. The DDR2 adapter is new code and
 * can be written natively for whatever width we pick.
 */
object CacheWidthElabTest extends App {

  def gen(name: String, dataWidth: Int, addrWidth: Int, mshrCount: Int = 1) = {
    val idWidth = log2Up(mshrCount)
    SpinalVerilog(new Component {
      setDefinitionName(name)
      val cfg = CacheConfig(
        addrWidth = addrWidth,
        dataWidth = dataWidth,
        hasFill = true,
        fillAddrWidth = addrWidth,
        idWidth = idWidth,
        mshrCount = mshrCount)
      val cache = new LruCacheCore(cfg)
      val bridge = new BmbCacheBridge(
        jop.memory.JopMemoryConfig(addressWidth = addrWidth - 2,
          mainMemSize = BigInt(1) << addrWidth).bmbParameter,
        addrWidth, dataWidth, mshrCount)

      val io = new Bundle {
        val frontend = slave(CacheFrontend(addrWidth, dataWidth, idWidth))
        val memCmd   = master(Stream(CacheReq(addrWidth, dataWidth)))
        val memRsp   = slave(Stream(CacheRsp(dataWidth)))
        val fill     = slave(jop.memory.MemFill(addrWidth))
        val busy     = out Bool()
      }
      cache.io.frontend <> io.frontend
      cache.io.memCmd   <> io.memCmd
      cache.io.memRsp   <> io.memRsp
      cache.io.fill.get <> io.fill
      io.busy := cache.io.busy
      bridge.setName("unusedBridge")
    })
    println(s"[ok] $name elaborated: dataWidth=$dataWidth addrWidth=$addrWidth mshr=$mshrCount")
  }

  // 1 GB (30-bit byte address) is the A-E115FB DDR2 target.
  gen("CacheW128", 128, 30)
  gen("CacheW256", 256, 30)
  gen("CacheW512", 512, 30)
  // The MSHR file scales the per-entry line-width registers, and the id width
  // has to agree across the bridge, the frontend bundle and the cache — a
  // mismatch is an elaboration error that `compile` alone will never surface.
  gen("CacheW256Mshr2", 256, 30, mshrCount = 2)
  gen("CacheW256Mshr4", 256, 30, mshrCount = 4)
  gen("CacheW128Mshr8", 128, 30, mshrCount = 8)
  println("PASS: LruCacheCore elaborates at 128/256/512-bit line widths, 1/2/4/8 MSHRs")
}
