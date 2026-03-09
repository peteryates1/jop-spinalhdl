package jop.system.memory

import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr.SdramTimings
import jop.memory.BmbSdramCtrl32
import jop.ddr3.{BmbCacheBridge, LruCacheCore, CacheConfig, CacheToMigAdapter, MigBlackBox}
import jop.system.WukongMigBlackBox

/**
 * Memory controller creation result.
 *
 * Sealed trait with concrete cases for each memory type.
 * The caller uses pattern matching to wire up type-specific I/O.
 */
sealed trait MemCtrlResult

/** BRAM memory controller -- on-chip RAM with BMB interface */
case class BramMemCtrl(
  ram: BmbOnChipRam
) extends MemCtrlResult

/** SDR SDRAM memory controller -- BmbSdramCtrl32 wrapper */
case class SdrMemCtrl(
  ctrl: BmbSdramCtrl32
) extends MemCtrlResult

/** DDR3 memory controller -- cache + MIG adapter.
 *  MIG BlackBox is instantiated separately (needs top-level pin wiring).
 */
case class Ddr3MemCtrl(
  bmbBridge: BmbCacheBridge,
  cache: LruCacheCore,
  adapter: CacheToMigAdapter
) extends MemCtrlResult

/**
 * Memory controller factory -- creates the appropriate memory controller
 * based on memory type and board configuration.
 *
 * Each method creates and wires the internal pipeline but leaves external I/O
 * (SDRAM pins, DDR3 MIG, BRAM init) to the caller. The caller receives a
 * typed result and uses pattern matching to wire board-specific pins.
 *
 * Usage pattern in a unified JopTop:
 * {{{
 *   val memCtrl = memoryType match {
 *     case MemoryType.BRAM =>
 *       MemoryControllerFactory.createBram(cluster.bmbParameter, memSize, initData)
 *     case MemoryType.SDRAM_SDR =>
 *       MemoryControllerFactory.createSdr(cluster.bmbParameter, layout, timing, cas, isAltera, freqHz)
 *     case MemoryType.SDRAM_DDR3 =>
 *       MemoryControllerFactory.createDdr3Path(cluster.bmbParameter)
 *   }
 *
 *   memCtrl match {
 *     case BramMemCtrl(ram) =>
 *       ram.io.bus <> cluster.io.bmb
 *     case SdrMemCtrl(ctrl) =>
 *       ctrl.io.bmb <> cluster.io.bmb
 *       io.sdram <> ctrl.io.sdram
 *     case Ddr3MemCtrl(bridge, cache, adapter) =>
 *       bridge.io.bmb <> cluster.io.bmb
 *       MemoryControllerFactory.wireMig(adapter, mig)
 *   }
 * }}}
 */
object MemoryControllerFactory {

  /**
   * Create a BRAM (on-chip RAM) memory controller.
   *
   * @param bmbParameter BMB bus parameter from JopCluster
   * @param memSize      Memory size in bytes
   * @param initData     Optional initialization data (from .jop file)
   */
  def createBram(
    bmbParameter: BmbParameter,
    memSize: Int,
    initData: Option[Seq[BigInt]] = None
  ): BramMemCtrl = {
    val ram = BmbOnChipRam(
      p = bmbParameter,
      size = memSize,
      hexOffset = 0
    )
    initData.foreach { data =>
      ram.ram.initBigInt(data)
    }
    BramMemCtrl(ram)
  }

  /**
   * Create an SDR SDRAM controller.
   *
   * @param bmbParameter BMB bus parameter from JopCluster
   * @param layout       SDRAM layout from device (e.g. W9825G6JH6.layout)
   * @param timing       SDRAM timing from device speed grade (e.g. W9825G6JH6.timingGrade7)
   * @param cas          CAS latency (e.g. 3 for W9825G6JH6, 2 for W9864G6JT)
   * @param isAltera     Whether to use Altera IP (vs SpinalHDL SdramCtrlNoCke for sim/Xilinx)
   * @param clockFreqHz  System clock frequency in Hz for timing calculations
   */
  def createSdr(
    bmbParameter: BmbParameter,
    layout: SdramLayout,
    timing: SdramTimings,
    cas: Int,
    isAltera: Boolean,
    clockFreqHz: Long
  ): SdrMemCtrl = {
    val ctrl = BmbSdramCtrl32(
      bmbParameter = bmbParameter,
      layout = layout,
      timing = timing,
      CAS = cas,
      useAlteraCtrl = isAltera,
      clockFreqHz = clockFreqHz
    )
    SdrMemCtrl(ctrl)
  }

  /**
   * Create a DDR3 memory path (cache + adapter, no MIG -- MIG is board-specific).
   *
   * Wires the internal pipeline: BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter.
   * The caller must wire the adapter to a MIG BlackBox using wireMig() or wireWukongMig().
   *
   * @param bmbParameter   BMB bus parameter from JopCluster
   * @param cacheAddrWidth BMB byte address width (typically 28 for 256MB)
   * @param cacheDataWidth MIG native data width (typically 128)
   * @param cacheSetCount  Number of cache sets (typically 512 for 32KB L2)
   */
  def createDdr3Path(
    bmbParameter: BmbParameter,
    cacheAddrWidth: Int = 28,
    cacheDataWidth: Int = 128,
    cacheSetCount: Int = 512
  ): Ddr3MemCtrl = {
    val bmbBridge = new BmbCacheBridge(bmbParameter, cacheAddrWidth, cacheDataWidth)
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = cacheAddrWidth,
      dataWidth = cacheDataWidth,
      setCount = cacheSetCount
    ))
    val adapter = new CacheToMigAdapter

    // Wire BmbCacheBridge -> LruCacheCore
    cache.io.frontend.req << bmbBridge.io.cache.req
    bmbBridge.io.cache.rsp << cache.io.frontend.rsp

    // Wire LruCacheCore -> CacheToMigAdapter
    adapter.io.cmd.valid         := cache.io.memCmd.valid
    adapter.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
    adapter.io.cmd.payload.write := cache.io.memCmd.payload.write
    adapter.io.cmd.payload.wdata := cache.io.memCmd.payload.data
    adapter.io.cmd.payload.wmask := cache.io.memCmd.payload.mask
    cache.io.memCmd.ready        := adapter.io.cmd.ready

    cache.io.memRsp.valid         := adapter.io.rsp.valid
    cache.io.memRsp.payload.data  := adapter.io.rsp.payload.rdata
    cache.io.memRsp.payload.error := adapter.io.rsp.payload.error
    adapter.io.rsp.ready          := cache.io.memRsp.ready

    Ddr3MemCtrl(bmbBridge, cache, adapter)
  }

  /**
   * Wire a DDR3 memory path to a MIG BlackBox (Alchitry Au V2 variant with CS pin).
   *
   * Connects the CacheToMigAdapter's MIG-side signals to the MIG IP's application
   * interface. This handles the app_addr/cmd/en/wdf/rd signals in both directions.
   *
   * @param adapter CacheToMigAdapter from createDdr3Path()
   * @param mig     MIG BlackBox instance (with ddr3_cs_n port)
   */
  def wireMig(adapter: CacheToMigAdapter, mig: MigBlackBox): Unit = {
    // MIG -> Adapter (status/response)
    adapter.io.app_rdy           := mig.io.app_rdy
    adapter.io.app_wdf_rdy       := mig.io.app_wdf_rdy
    adapter.io.app_rd_data       := mig.io.app_rd_data
    adapter.io.app_rd_data_valid := mig.io.app_rd_data_valid

    // Adapter -> MIG (commands/write data)
    mig.io.app_addr     := adapter.io.app_addr
    mig.io.app_cmd      := adapter.io.app_cmd
    mig.io.app_en       := adapter.io.app_en
    mig.io.app_wdf_data := adapter.io.app_wdf_data
    mig.io.app_wdf_end  := adapter.io.app_wdf_end
    mig.io.app_wdf_mask := adapter.io.app_wdf_mask
    mig.io.app_wdf_wren := adapter.io.app_wdf_wren
  }

  /**
   * Wire a DDR3 memory path to a Wukong MIG BlackBox (no CS pin variant).
   *
   * Identical wiring to wireMig() but for the WukongMigBlackBox which lacks
   * the ddr3_cs_n port (Wukong MIG config has emrCSSelection=Disable).
   *
   * @param adapter CacheToMigAdapter from createDdr3Path()
   * @param mig     Wukong MIG BlackBox instance (no ddr3_cs_n port)
   */
  def wireWukongMig(adapter: CacheToMigAdapter, mig: WukongMigBlackBox): Unit = {
    // MIG -> Adapter (status/response)
    adapter.io.app_rdy           := mig.io.app_rdy
    adapter.io.app_wdf_rdy       := mig.io.app_wdf_rdy
    adapter.io.app_rd_data       := mig.io.app_rd_data
    adapter.io.app_rd_data_valid := mig.io.app_rd_data_valid

    // Adapter -> MIG (commands/write data)
    mig.io.app_addr     := adapter.io.app_addr
    mig.io.app_cmd      := adapter.io.app_cmd
    mig.io.app_en       := adapter.io.app_en
    mig.io.app_wdf_data := adapter.io.app_wdf_data
    mig.io.app_wdf_end  := adapter.io.app_wdf_end
    mig.io.app_wdf_mask := adapter.io.app_wdf_mask
    mig.io.app_wdf_wren := adapter.io.app_wdf_wren
  }
}
