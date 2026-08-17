package jop.system.memory

import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr.SdramTimings
import jop.memory.{BmbSdramCtrl32, BmbSdramCtrlWide, SdramBridge}
import jop.ddr3.{BmbCacheBridge, LruCacheCore, CacheConfig, CacheToMigAdapter, MigBlackBox}

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

/** SDR SDRAM memory controller.
 *
 *  Holds whichever bridge suits the device's data width -- BmbSdramCtrl32 for a
 *  16-bit SDRAM, BmbSdramCtrlWide for a 32-bit one. Both expose the same three
 *  ports through SdramBridge, so the top level wires them identically.
 */
case class SdrMemCtrl(
  ctrl: SdramBridge
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
 * DDR2 path for the A-E115FB: BmbCacheBridge -> LruCacheCore ->
 * CacheToDdr2Adapter. The Ddr2BlackBox is instantiated separately because it
 * needs top-level pin wiring and, unlike the MIG, it also SOURCES the clock the
 * whole path runs on.
 */
case class Ddr2MemCtrl(
  bmbBridge: BmbCacheBridge,
  cache: LruCacheCore,
  adapter: jop.ddr2.CacheToDdr2Adapter
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
   * @param useAlteraCtrl Whether to use Altera IP (vs SpinalHDL SdramCtrlNoCke for sim/Xilinx)
   * @param clockFreqHz  System clock frequency in Hz for timing calculations
   *
   * The bridge is chosen by the device's data width, not by the board:
   *   - 16-bit SDRAM -> BmbSdramCtrl32, two SDRAM ops per BMB beat
   *   - 32-bit SDRAM -> BmbSdramCtrlWide, one op per beat (Colorlight i5)
   *
   * `useAlteraCtrl` only applies to the 16-bit path. The Altera SDRAM IP is a
   * Quartus-only blackbox and the sole 32-bit board here is a Lattice part on
   * the open-source toolchain, so asking for it in the wide path is a
   * configuration error rather than something to silently ignore.
   */
  def createSdr(
    bmbParameter: BmbParameter,
    layout: SdramLayout,
    timing: SdramTimings,
    cas: Int,
    useAlteraCtrl: Boolean,
    clockFreqHz: Long
  ): SdrMemCtrl = {
    val ctrl = layout.dataWidth match {
      case 16 =>
        BmbSdramCtrl32(
          bmbParameter = bmbParameter,
          layout = layout,
          timing = timing,
          CAS = cas,
          useAlteraCtrl = useAlteraCtrl,
          clockFreqHz = clockFreqHz
        )
      case 32 =>
        require(!useAlteraCtrl,
          "createSdr: useAlteraCtrl is not supported for a 32-bit SDRAM -- the " +
          "Altera SDRAM IP blackbox is Quartus-only and BmbSdramCtrlWide always " +
          "uses SdramCtrlNoCke")
        BmbSdramCtrlWide(
          bmbParameter = bmbParameter,
          layout = layout,
          timing = timing,
          CAS = cas
        )
      case other =>
        throw new RuntimeException(
          s"createSdr: unsupported SDRAM data width $other (expected 16 or 32)")
    }
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
    cacheAddrWidth: Int = -1,   // -1 = derive from the device (byte addr = bmb access − 2)
    cacheDataWidth: Int = 128,
    cacheSetCount: Int = 512,
    hasFill: Boolean = false,
    mshrCount: Int = 1
  ): Ddr3MemCtrl = {
    // Physical byte-address width the cache/adapter/MIG see (strips the 2 type
    // bits). Derived from the memory device so 256 MB (28) and 1 GB (30) both work.
    val caw = if (cacheAddrWidth > 0) cacheAddrWidth else bmbParameter.access.addressWidth - 2
    val bmbBridge = new BmbCacheBridge(bmbParameter, caw, cacheDataWidth, mshrCount)
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = caw,
      dataWidth = cacheDataWidth,
      setCount = cacheSetCount,
      hasFill = hasFill,
      fillAddrWidth = if (hasFill) bmbParameter.access.addressWidth - 2 else 0,
      idWidth = spinal.core.log2Up(mshrCount),
      mshrCount = mshrCount
    ))
    // Each miss can put an eviction AND a refill in flight, so the adapter must
    // be able to hold two commands per MSHR or it becomes the limit instead.
    val adapter = new CacheToMigAdapter(caw, maxOutstanding = (2 * mshrCount) max 2)

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
   * @param mig     MIG BlackBox instance
   */
  /**
   * Build the DDR2 memory path for the A-E115FB (EP4CE115 + 1 GB DDR2 SODIMM).
   *
   * Mirrors createDdr3Path, with the line width defaulting to 256 bits rather
   * than 128: that is the half-rate ALTMEMPHY local word AND the DDR2 BL=4 burst
   * (32 bytes), so a narrower line would waste half of every burst. Verified at
   * that width by LruCacheCoreUnitSim and CacheToDdr2AdapterSim.
   */
  def createDdr2Path(
    bmbParameter: BmbParameter,
    cacheAddrWidth: Int = -1,   // -1 = derive from the device (byte addr = bmb access - 2)
    cacheDataWidth: Int = 256,
    cacheSetCount: Int = 256,
    hasFill: Boolean = false,
    mshrCount: Int = 1
  ): Ddr2MemCtrl = {
    val caw = if (cacheAddrWidth > 0) cacheAddrWidth else bmbParameter.access.addressWidth - 2
    val bmbBridge = new BmbCacheBridge(bmbParameter, caw, cacheDataWidth, mshrCount)
    val cache = new LruCacheCore(CacheConfig(
      addrWidth = caw,
      dataWidth = cacheDataWidth,
      setCount = cacheSetCount,
      hasFill = hasFill,
      fillAddrWidth = if (hasFill) bmbParameter.access.addressWidth - 2 else 0,
      idWidth = spinal.core.log2Up(mshrCount),
      mshrCount = mshrCount
    ))
    // rspDepth caps reads in flight at the backend; the cache can have up to
    // 2 * mshrCount commands outstanding (an eviction and a refill each).
    require(2 * mshrCount <= 8,
      s"mshrCount $mshrCount exceeds CacheToDdr2Adapter's 8-deep response FIFO")
    val adapter = new jop.ddr2.CacheToDdr2Adapter(caw, cacheDataWidth)

    cache.io.frontend.req << bmbBridge.io.cache.req
    bmbBridge.io.cache.rsp << cache.io.frontend.rsp

    // The adapter takes CacheReq/CacheRsp directly, so this is a straight
    // Stream connection rather than the field-by-field copy the MIG needs.
    adapter.io.cmd << cache.io.memCmd
    cache.io.memRsp << adapter.io.rsp

    Ddr2MemCtrl(bmbBridge, cache, adapter)
  }

  /** Connect the DDR2 adapter to the ALTMEMPHY controller's local interface. */
  def wireDdr2(adapter: jop.ddr2.CacheToDdr2Adapter, ddr2: jop.ddr2.Ddr2BlackBox): Unit = {
    adapter.io.local_ready       := ddr2.io.local_ready
    adapter.io.local_rdata       := ddr2.io.local_rdata
    adapter.io.local_rdata_valid := ddr2.io.local_rdata_valid
    adapter.io.local_init_done   := ddr2.io.local_init_done

    ddr2.io.local_address    := adapter.io.local_address
    ddr2.io.local_write_req  := adapter.io.local_write_req
    ddr2.io.local_read_req   := adapter.io.local_read_req
    ddr2.io.local_burstbegin := adapter.io.local_burstbegin
    ddr2.io.local_wdata      := adapter.io.local_wdata
    ddr2.io.local_be         := adapter.io.local_be
    ddr2.io.local_size       := adapter.io.local_size
  }

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
}
