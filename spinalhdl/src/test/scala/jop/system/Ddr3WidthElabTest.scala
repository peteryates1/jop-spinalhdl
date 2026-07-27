package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.memory.JopMemoryConfig
import jop.system.memory.MemoryControllerFactory

/**
 * Part A (address-width parameterization) elaboration check: build the full DDR3
 * path (BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter) at both 256 MB
 * (28-bit) and 1 GB (30-bit) and confirm it elaborates with no width mismatch.
 * The 1 GB point has no board/MIG IP yet — this is the code-side scaling check.
 */
object Ddr3WidthElabTest extends App {
  def gen(name: String, sizeBytes: BigInt, addrW: Int) = {
    SpinalVerilog(new Component {
      setDefinitionName(name)
      val mc = JopMemoryConfig(addressWidth = addrW, mainMemSize = sizeBytes, burstLen = 8,
                               hasBackendFill = true)
      val p = mc.bmbParameter
      val path = MemoryControllerFactory.createDdr3Path(p, hasFill = true)
      val caw = p.access.addressWidth - 2   // physical byte-address width

      val io = new Bundle {
        val bmb               = slave(Bmb(p.access, p.invalidation))
        val app_addr          = out Bits(caw bits)
        val app_en            = out Bool()
        val app_rdy           = in Bool()
        val app_wdf_rdy       = in Bool()
        val app_rd_data       = in Bits(128 bits)
        val app_rd_data_valid = in Bool()
      }
      path.bmbBridge.io.bmb <> io.bmb
      path.cache.io.fill.foreach { f => f.cmd := False; f.start := 0; f.end := 0; f.value := 0 }
      val a = path.adapter
      io.app_addr := a.io.app_addr
      io.app_en   := a.io.app_en
      a.io.app_rdy           := io.app_rdy
      a.io.app_wdf_rdy       := io.app_wdf_rdy
      a.io.app_rd_data       := io.app_rd_data
      a.io.app_rd_data_valid := io.app_rd_data_valid

      assert(a.io.app_addr.getWidth == caw, s"$name: adapter app_addr ${a.io.app_addr.getWidth} != $caw")
      assert(path.cache.io.fill.get.start.getWidth == mc.addressWidth,
        s"$name: fill width != memConfig.addressWidth")
    })
  }

  gen("Ddr3PathElab256", 256L * 1024 * 1024, 28)   // caw = 28
  gen("Ddr3PathElab1G", 1024L * 1024 * 1024, 30)    // caw = 30
  println("ELAB OK: DDR3 path elaborates at 28-bit (256MB) and 30-bit (1GB)")
}
