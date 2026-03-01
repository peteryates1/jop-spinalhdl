package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.pipeline._
import jop.memory._
import jop.io.{BmbSys, BmbUart, BmbEth, BmbMdio, BmbSdSpi, BmbSdNative, BmbVgaDma, BmbVgaText, BmbConfigFlash, SyncIn, SyncOut}
import spinal.lib.com.eth._
import jop.{JopPipeline, JumpTableData}

/**
 * JOP Core Configuration
 *
 * Unified configuration for the complete JOP core.
 *
 * @param dataWidth    Data path width (32 bits)
 * @param pcWidth      Microcode PC width (11 bits = 2K ROM)
 * @param instrWidth   Microcode instruction width (10 bits)
 * @param jpcWidth     Java PC width (11 bits = 2KB bytecode cache)
 * @param ramWidth     Stack RAM address width (8 bits = 256 entries)
 * @param blockBits    Method cache block bits (4 = 16 blocks in JBC RAM)
 * @param memConfig    Memory subsystem configuration
 * @param cpuId        CPU identifier (for multi-core; 0 for single-core)
 * @param cpuCnt       Total number of CPUs (1 for single-core)
 * @param ioConfig     I/O device configuration (device presence, parameters, interrupts)
 * @param clkFreqHz    System clock frequency in Hz (for BmbSys microsecond prescaler)
 */
case class JopCoreConfig(
  dataWidth:    Int              = 32,
  pcWidth:      Int              = 11,
  instrWidth:   Int              = 10,
  jpcWidth:     Int              = 11,
  ramWidth:     Int              = 8,
  blockBits:    Int              = 4,
  memConfig:    JopMemoryConfig  = JopMemoryConfig(),
  jumpTable:    JumpTableInitData = JumpTableInitData.simulation,
  cpuId:        Int              = 0,
  cpuCnt:       Int              = 1,
  ioConfig:     IoConfig         = IoConfig(),
  clkFreqHz:    Long             = 100000000L,
  useIhlu:      Boolean          = false,  // Use IHLU (per-object lock) instead of CmpSync (global lock)
  useStackCache: Boolean         = false,  // Use 3-bank rotating stack cache with DMA spill/fill
  spillBaseAddrOverride: Option[Int] = None // Override spillBaseAddr (e.g., 0 for dedicated spill BRAM)
) {
  // Convenience accessors (avoid changing every reference site)
  def hasUart: Boolean = ioConfig.hasUart
  def hasEth: Boolean = ioConfig.hasEth
  def uartBaudRate: Int = ioConfig.uartBaudRate
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth,
    cacheConfig = if (useStackCache) Some(StackCacheConfig(
      burstLen = memConfig.burstLen,
      wordAddrWidth = memConfig.addressWidth - 2,
      spillBaseAddr = spillBaseAddrOverride.getOrElse {
        val memWords = (memConfig.mainMemSize / 4).toInt
        if (memConfig.stackRegionWordsPerCore > 0) {
          // Per-core stack region at top of memory (core 0 highest)
          memWords - (cpuId + 1) * memConfig.stackRegionWordsPerCore
        } else if (memConfig.burstLen == 0) {
          // Legacy BRAM: spill area in top 25% of memory (word address)
          (memWords * 3 / 4) + cpuId * 4096
        } else {
          // Legacy SDRAM/DDR3: dedicated spill area beyond program/heap
          0x780000 + cpuId * 0x8000
        }
      }
    )) else None
  )
  def bcfetchConfig = BytecodeFetchConfig(jpcWidth, pcWidth, jumpTable)
}

/**
 * JOP Core - Complete JOP Processor with BMB Memory Interface and Internal I/O
 *
 * Integrates:
 * - JopPipeline: All pipeline stages (BytecodeFetch, Fetch, Decode, Stack, Mul)
 * - Memory controller with BMB controller interface
 * - BmbSys: System I/O (clock counter, watchdog, CmpSync lock, etc.)
 * - BmbUart (optional): UART TX/RX with FIFOs
 *
 * The BMB controller interface connects to external memory (BmbOnChipRam, BmbSdramCtrl, etc.)
 *
 * @param config System configuration
 * @param romInit Optional microcode ROM initialization
 * @param ramInit Optional stack RAM initialization
 * @param jbcInit Optional JBC RAM initialization
 */
case class JopCore(
  config: JopCoreConfig = JopCoreConfig(),
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None,
  ethTxCd: Option[ClockDomain] = None,
  ethRxCd: Option[ClockDomain] = None,
  vgaCd:   Option[ClockDomain] = None
) extends Component {

  val io = new Bundle {
    // BMB controller interface to external memory
    val bmb = master(Bmb(config.memConfig.bmbParameter))

    // Stack cache DMA BMB master (optional, only when useStackCache)
    val stackDmaBmb = if (config.useStackCache) Some(master(Bmb(config.memConfig.bmbParameter))) else None

    // CmpSync interface
    val syncIn  = in(SyncOut())    // From CmpSync: halted + signal
    val syncOut = out(SyncIn())    // To CmpSync: lock request + signal

    // Watchdog from BmbSys
    val wd = out Bits(32 bits)

    // UART
    val txd = out Bool()
    val rxd = in Bool()

    // Ethernet PHY (optional, only when hasEth)
    val phy = if (config.hasEth) Some(master(PhyIo(PhyParameter(txDataWidth = config.ioConfig.phyDataWidth, rxDataWidth = config.ioConfig.phyDataWidth)))) else None

    // MDIO pins (optional, only when hasEth)
    val mdc      = if (config.hasEth) Some(out Bool()) else None
    val mdioOut  = if (config.hasEth) Some(out Bool()) else None
    val mdioOe   = if (config.hasEth) Some(out Bool()) else None
    val mdioIn   = if (config.hasEth) Some(in Bool()) else None
    val phyReset = if (config.hasEth) Some(out Bool()) else None

    // SD SPI pins (optional)
    val sdSpiSclk = if (config.ioConfig.hasSdSpi) Some(out Bool()) else None
    val sdSpiMosi = if (config.ioConfig.hasSdSpi) Some(out Bool()) else None
    val sdSpiMiso = if (config.ioConfig.hasSdSpi) Some(in Bool()) else None
    val sdSpiCs   = if (config.ioConfig.hasSdSpi) Some(out Bool()) else None
    val sdSpiCd   = if (config.ioConfig.hasSdSpi) Some(in Bool()) else None

    // Config flash SPI pins (optional)
    val cfDclk  = if (config.ioConfig.hasConfigFlash) Some(out Bool()) else None
    val cfNcs   = if (config.ioConfig.hasConfigFlash) Some(out Bool()) else None
    val cfAsdo  = if (config.ioConfig.hasConfigFlash) Some(out Bool()) else None
    val cfData0 = if (config.ioConfig.hasConfigFlash) Some(in Bool()) else None
    val cfFlashReady = if (config.ioConfig.hasConfigFlash) Some(in Bool()) else None
    val cfDebugRxByte = if (config.ioConfig.hasConfigFlash) Some(out Bits(8 bits)) else None
    val cfDebugTxCount = if (config.ioConfig.hasConfigFlash) Some(out UInt(8 bits)) else None
    val cfDebugFirstWord = if (config.ioConfig.hasConfigFlash) Some(out Bits(32 bits)) else None

    // SD Native pins (optional)
    val sdClk        = if (config.ioConfig.hasSdNative) Some(out Bool()) else None
    val sdCmdWrite   = if (config.ioConfig.hasSdNative) Some(out Bool()) else None
    val sdCmdWriteEn = if (config.ioConfig.hasSdNative) Some(out Bool()) else None
    val sdCmdRead    = if (config.ioConfig.hasSdNative) Some(in Bool()) else None
    val sdDatWrite   = if (config.ioConfig.hasSdNative) Some(out Bits(4 bits)) else None
    val sdDatWriteEn = if (config.ioConfig.hasSdNative) Some(out Bits(4 bits)) else None
    val sdDatRead    = if (config.ioConfig.hasSdNative) Some(in Bits(4 bits)) else None
    val sdCd         = if (config.ioConfig.hasSdNative) Some(in Bool()) else None

    // VGA pins (optional — shared by VgaDma and VgaText)
    val vgaHsync = if (config.ioConfig.hasVga) Some(out Bool()) else None
    val vgaVsync = if (config.ioConfig.hasVga) Some(out Bool()) else None
    val vgaR     = if (config.ioConfig.hasVga) Some(out Bits(5 bits)) else None
    val vgaG     = if (config.ioConfig.hasVga) Some(out Bits(6 bits)) else None
    val vgaB     = if (config.ioConfig.hasVga) Some(out Bits(5 bits)) else None

    // VGA DMA BMB master port (optional — for framebuffer reads)
    val vgaDmaBmb = if (config.ioConfig.hasVgaDma) Some(master(Bmb(config.memConfig.bmbParameter))) else None

    // Pipeline status
    val pc        = out UInt(config.pcWidth bits)
    val jpc       = out UInt((config.jpcWidth + 1) bits)
    val instr     = out Bits(config.instrWidth bits)
    val jfetch    = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout      = out Bits(config.dataWidth bits)
    val bout      = out Bits(config.dataWidth bits)

    // Memory controller status
    val memBusy   = out Bool()

    // Debug: UART TX snoop (captures I/O write to UART data register)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Debug: exception fired (from internal BmbSys)
    val debugExc = out Bool()

    // Debug: bytecode cache fill
    val debugBcRd = out Bool()

    // Debug: memory controller state
    val debugMemState = out UInt(5 bits)
    val debugMemHandleActive = out Bool()
    val debugBcFillAddr = out UInt(config.memConfig.addressWidth bits)
    val debugBcFillLen = out UInt(10 bits)
    val debugBcFillCount = out UInt(10 bits)
    val debugBcRdCapture = out Bits(32 bits)
    val debugAddrWr = out Bool()  // Decode stage addrWr signal
    val debugRdc = out Bool()     // Memory read combined (stmrac)
    val debugRd = out Bool()      // Memory read (stmra)

    // Debug: RAM slot read
    val debugRamAddr = in UInt(8 bits)
    val debugRamData = out Bits(32 bits)

    // Debug: I/O activity counters
    val debugIoRdCount = out UInt(16 bits)
    val debugIoWrCount = out UInt(16 bits)

    // Debug: halted by CmpSync (from internal BmbSys)
    val debugHalted = out Bool()

    // Debug controller interface
    val debugHalt = in Bool()             // Freeze pipeline (from debug controller)
    val debugSp   = out UInt(config.stackConfig.spWidth bits)
    val debugVp   = out UInt(config.stackConfig.spWidth bits)
    val debugAr   = out UInt(config.stackConfig.spWidth bits)
    val debugFlags = out Bits(4 bits)
    val debugMulResult = out Bits(config.dataWidth bits)
    val debugAddrReg   = out UInt(config.memConfig.addressWidth bits)
    val debugRdDataReg = out Bits(config.dataWidth bits)
    val debugInstr     = out Bits(config.instrWidth bits)
    val debugBcopd     = out Bits(16 bits)

    // Stack cache debug (optional)
    val scDebugRotState     = if (config.useStackCache) Some(out UInt(3 bits)) else None
    val scDebugActiveBankIdx = if (config.useStackCache) Some(out UInt(2 bits)) else None
    val scDebugBankBase     = if (config.useStackCache) Some(out Vec(UInt(config.stackConfig.spWidth bits), 3)) else None
    val scDebugBankResident = if (config.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugBankDirty    = if (config.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugNeedsRot     = if (config.useStackCache) Some(out Bool()) else None

    // Write-snoop debug (passthrough from StackStage via JopPipeline)
    val scDebugPipeWrAddr = if (config.useStackCache) Some(out UInt(config.stackConfig.spWidth bits)) else None
    val scDebugPipeWrData = if (config.useStackCache) Some(out Bits(config.dataWidth bits)) else None
    val scDebugPipeWrEn   = if (config.useStackCache) Some(out Bool()) else None
    val scDebugVp0Data    = if (config.useStackCache) Some(out Bits(config.dataWidth bits)) else None

    // Snoop bus for cross-core cache invalidation
    val snoopOut = if (config.memConfig.useAcache || config.memConfig.useOcache) {
      val maxIdxBits = config.memConfig.acacheMaxIndexBits.max(config.memConfig.ocacheMaxIndexBits)
      Some(out(CacheSnoopOut(config.memConfig.addressWidth, maxIdxBits)))
    } else None
    val snoopIn = if (config.memConfig.useAcache || config.memConfig.useOcache) {
      val maxIdxBits = config.memConfig.acacheMaxIndexBits.max(config.memConfig.ocacheMaxIndexBits)
      Some(in(CacheSnoopIn(config.memConfig.addressWidth, maxIdxBits)))
    } else None
  }

  // ==========================================================================
  // Pipeline
  // ==========================================================================

  val pipeline = JopPipeline(config, romInit, ramInit, jbcInit)

  // ==========================================================================
  // Memory Controller
  // ==========================================================================

  val memCtrl = BmbMemoryController(config.memConfig, config.jpcWidth, config.blockBits)

  // Connect BMB controller to external interface
  io.bmb <> memCtrl.io.bmb

  // ==========================================================================
  // Pipeline <-> Memory Controller Wiring
  // ==========================================================================

  // Pipeline -> MemCtrl
  memCtrl.io.memIn := pipeline.io.memCtrl
  memCtrl.io.aout := pipeline.io.aout
  memCtrl.io.bout := pipeline.io.bout
  memCtrl.io.bcopd := pipeline.io.bcopd

  // MemCtrl -> Pipeline
  pipeline.io.memRdData := memCtrl.io.memOut.rdData
  pipeline.io.memBcStart := memCtrl.io.memOut.bcStart
  pipeline.io.jbcWrAddr := memCtrl.io.jbcWrite.addr
  pipeline.io.jbcWrData := memCtrl.io.jbcWrite.data
  pipeline.io.jbcWrEn := memCtrl.io.jbcWrite.enable

  // ==========================================================================
  // Snoop Bus Wiring
  // ==========================================================================

  io.snoopOut.foreach { so =>
    memCtrl.io.snoopOut.foreach { mso =>
      so := mso
    }
  }
  io.snoopIn.foreach { si =>
    memCtrl.io.snoopIn.foreach { msi =>
      msi := si
    }
  }

  // ==========================================================================
  // Stack Cache DMA (optional, only when useStackCache)
  // ==========================================================================

  if (config.useStackCache) {
    val cc = config.stackConfig.cacheConfig.get
    val dma = StackCacheDma(cc, config.memConfig.bmbParameter)

    // BMB master → external port (arbitrated at JopCluster level)
    io.stackDmaBmb.get <> dma.io.bmb

    // DMA bank RAM access → pipeline stack cache
    pipeline.io.dmaBankRdAddr.get := dma.io.bankRdAddr
    dma.io.bankRdData := pipeline.io.dmaBankRdData.get
    pipeline.io.dmaBankWrAddr.get := dma.io.bankWrAddr
    pipeline.io.dmaBankWrData.get := dma.io.bankWrData
    pipeline.io.dmaBankWrEn.get := dma.io.bankWrEn
    pipeline.io.dmaBankSelect.get := dma.io.bankSelect

    // Control: stack stage rotation controller → DMA
    dma.io.start := pipeline.io.dmaStart.get
    dma.io.isSpill := pipeline.io.dmaIsSpill.get
    dma.io.extAddr := pipeline.io.dmaExtAddr.get.resized
    dma.io.wordCount := pipeline.io.dmaWordCount.get
    dma.io.bank := pipeline.io.dmaBank.get

    // Status: DMA → stack stage
    pipeline.io.dmaBusy.get := dma.io.busy
    pipeline.io.dmaDone.get := dma.io.done

    // Debug passthrough
    io.scDebugRotState.get := pipeline.io.scDebugRotState.get
    io.scDebugActiveBankIdx.get := pipeline.io.scDebugActiveBankIdx.get
    io.scDebugBankBase.get := pipeline.io.scDebugBankBase.get
    io.scDebugBankResident.get := pipeline.io.scDebugBankResident.get
    io.scDebugBankDirty.get := pipeline.io.scDebugBankDirty.get
    io.scDebugNeedsRot.get := pipeline.io.scDebugNeedsRot.get

    // Write-snoop debug passthrough
    io.scDebugPipeWrAddr.get := pipeline.io.scDebugPipeWrAddr.get
    io.scDebugPipeWrData.get := pipeline.io.scDebugPipeWrData.get
    io.scDebugPipeWrEn.get := pipeline.io.scDebugPipeWrEn.get
    io.scDebugVp0Data.get := pipeline.io.scDebugVp0Data.get
  }

  // ==========================================================================
  // Internal I/O Subsystem
  // ==========================================================================

  // I/O address (8-bit, decoded by JopIoSpace match predicates)
  val ioAddr = memCtrl.io.ioAddr

  // Number of I/O interrupt sources (computed from IoConfig)
  val numIoInt = config.ioConfig.numIoInt

  // System I/O (0x80-0x8F)
  val bmbSys = BmbSys(clkFreqHz = config.clkFreqHz, cpuId = config.cpuId, cpuCnt = config.cpuCnt, numIoInt = numIoInt,
    memEndWords = config.memConfig.usableMemWords(config.cpuCnt))
  bmbSys.io.addr   := JopIoSpace.sysAddr(ioAddr)
  bmbSys.io.rd     := memCtrl.io.ioRd && JopIoSpace.isSys(ioAddr)
  bmbSys.io.wr     := memCtrl.io.ioWr && JopIoSpace.isSys(ioAddr)
  bmbSys.io.wrData := memCtrl.io.ioWrData

  // CmpSync interface
  bmbSys.io.syncIn := io.syncIn
  io.syncOut := bmbSys.io.syncOut

  // Pipeline busy = memory controller busy OR halted by CmpSync OR debug halt
  pipeline.io.memBusy := memCtrl.io.memOut.busy || bmbSys.io.halted || io.debugHalt

  // Exception from BmbSys
  pipeline.io.exc := bmbSys.io.exc

  // Interrupts: now generated internally by BmbSys
  pipeline.io.irq := bmbSys.io.irq
  pipeline.io.irqEna := bmbSys.io.irqEna
  bmbSys.io.ackIrq := pipeline.io.ackIrq
  bmbSys.io.ackExc := pipeline.io.ackExc

  // UART (0x90-0x93, optional)
  val bmbUart = if (config.hasUart) Some(BmbUart(config.uartBaudRate, config.clkFreqHz)) else None

  bmbUart.foreach { uart =>
    uart.io.addr   := JopIoSpace.uartAddr(ioAddr)
    uart.io.rd     := memCtrl.io.ioRd && JopIoSpace.isUart(ioAddr)
    uart.io.wr     := memCtrl.io.ioWr && JopIoSpace.isUart(ioAddr)
    uart.io.wrData := memCtrl.io.ioWrData
    io.txd := uart.io.txd
    uart.io.rxd := io.rxd
  }
  if (bmbUart.isEmpty) {
    io.txd := True  // Idle
  }

  // Ethernet MAC (0x98-0x9F, optional)
  val bmbEth = if (config.hasEth && ethTxCd.isDefined && ethRxCd.isDefined)
    Some(BmbEth(txCd = ethTxCd.get, rxCd = ethRxCd.get, phyTxDataWidth = config.ioConfig.phyDataWidth, phyRxDataWidth = config.ioConfig.phyDataWidth))
  else None

  bmbEth.foreach { eth =>
    eth.io.addr   := JopIoSpace.ethAddr(ioAddr)
    eth.io.rd     := memCtrl.io.ioRd && JopIoSpace.isEth(ioAddr)
    eth.io.wr     := memCtrl.io.ioWr && JopIoSpace.isEth(ioAddr)
    eth.io.wrData := memCtrl.io.ioWrData
    io.phy.get <> eth.io.phy
  }

  // MDIO controller (0xA0-0xA7, optional)
  val bmbMdio = if (config.hasEth) Some(BmbMdio()) else None

  bmbMdio.foreach { mdio =>
    mdio.io.addr   := JopIoSpace.mdioAddr(ioAddr)
    mdio.io.rd     := memCtrl.io.ioRd && JopIoSpace.isMdio(ioAddr)
    mdio.io.wr     := memCtrl.io.ioWr && JopIoSpace.isMdio(ioAddr)
    mdio.io.wrData := memCtrl.io.ioWrData
    io.mdc.get      := mdio.io.mdc
    io.mdioOut.get  := mdio.io.mdioOut
    io.mdioOe.get   := mdio.io.mdioOe
    mdio.io.mdioIn  := io.mdioIn.get
    io.phyReset.get := mdio.io.phyReset
    // Wire Ethernet interrupt sources to MDIO interrupt controller
    mdio.io.ethRxInt := bmbEth.map(_.io.rxInterrupt).getOrElse(False)
    mdio.io.ethTxInt := bmbEth.map(_.io.txInterrupt).getOrElse(False)
  }

  // SD SPI (0xA8-0xAB, optional)
  val bmbSdSpi = if (config.ioConfig.hasSdSpi) Some(BmbSdSpi(config.ioConfig.sdSpiClkDivInit)) else None
  bmbSdSpi.foreach { sd =>
    sd.io.addr   := JopIoSpace.sdSpiAddr(ioAddr)
    sd.io.rd     := memCtrl.io.ioRd && JopIoSpace.isSdSpi(ioAddr)
    sd.io.wr     := memCtrl.io.ioWr && JopIoSpace.isSdSpi(ioAddr)
    sd.io.wrData := memCtrl.io.ioWrData
    io.sdSpiSclk.get := sd.io.sclk
    io.sdSpiMosi.get := sd.io.mosi
    io.sdSpiCs.get   := sd.io.cs
    sd.io.miso       := io.sdSpiMiso.get
    sd.io.cd         := io.sdSpiCd.get
  }

  // Config Flash (0xD0-0xD3, optional)
  val bmbCfgFlash = if (config.ioConfig.hasConfigFlash) Some(BmbConfigFlash(config.ioConfig.cfgFlashClkDivInit)) else None
  bmbCfgFlash.foreach { cf =>
    cf.io.addr   := JopIoSpace.cfgFlashAddr(ioAddr)
    cf.io.rd     := memCtrl.io.ioRd && JopIoSpace.isCfgFlash(ioAddr)
    cf.io.wr     := memCtrl.io.ioWr && JopIoSpace.isCfgFlash(ioAddr)
    cf.io.wrData := memCtrl.io.ioWrData
    io.cfDclk.get  := cf.io.dclk
    io.cfNcs.get   := cf.io.ncs
    io.cfAsdo.get  := cf.io.asdo
    cf.io.data0    := io.cfData0.get
    cf.io.flashReady := io.cfFlashReady.get
    io.cfDebugRxByte.get  := cf.io.debugRxByte
    io.cfDebugTxCount.get := cf.io.debugTxCount
    io.cfDebugFirstWord.get := cf.io.debugFirstWord
  }

  // VGA DMA (0xAC-0xAF, optional)
  val bmbVgaDma = if (config.ioConfig.hasVgaDma && vgaCd.isDefined)
    Some(BmbVgaDma(config.memConfig.bmbParameter, vgaCd.get, config.ioConfig.vgaDmaFifoDepth))
  else None
  bmbVgaDma.foreach { vga =>
    vga.io.addr   := JopIoSpace.vgaDmaAddr(ioAddr)
    vga.io.rd     := memCtrl.io.ioRd && JopIoSpace.isVgaDma(ioAddr)
    vga.io.wr     := memCtrl.io.ioWr && JopIoSpace.isVgaDma(ioAddr)
    vga.io.wrData := memCtrl.io.ioWrData
    io.vgaDmaBmb.get <> vga.io.bmb
    io.vgaHsync.get := vga.io.vgaHsync
    io.vgaVsync.get := vga.io.vgaVsync
    io.vgaR.get     := vga.io.vgaR
    io.vgaG.get     := vga.io.vgaG
    io.vgaB.get     := vga.io.vgaB
  }

  // SD Native (0xB0-0xBF, optional)
  val bmbSdNative = if (config.ioConfig.hasSdNative) Some(BmbSdNative(config.ioConfig.sdNativeClkDivInit)) else None
  bmbSdNative.foreach { sd =>
    sd.io.addr   := JopIoSpace.sdNativeAddr(ioAddr)
    sd.io.rd     := memCtrl.io.ioRd && JopIoSpace.isSdNative(ioAddr)
    sd.io.wr     := memCtrl.io.ioWr && JopIoSpace.isSdNative(ioAddr)
    sd.io.wrData := memCtrl.io.ioWrData
    io.sdClk.get        := sd.io.sdClk
    io.sdCmdWrite.get   := sd.io.sdCmd.write
    io.sdCmdWriteEn.get := sd.io.sdCmd.writeEnable
    sd.io.sdCmd.read    := io.sdCmdRead.get
    io.sdDatWrite.get   := sd.io.sdDat.write
    io.sdDatWriteEn.get := sd.io.sdDat.writeEnable
    sd.io.sdDat.read    := io.sdDatRead.get
    sd.io.sdCd          := io.sdCd.get
  }

  // VGA Text (0xC0-0xCF, optional)
  val bmbVgaText = if (config.ioConfig.hasVgaText && vgaCd.isDefined)
    Some(BmbVgaText(vgaCd.get))
  else None
  bmbVgaText.foreach { vga =>
    vga.io.addr   := JopIoSpace.vgaTextAddr(ioAddr)
    vga.io.rd     := memCtrl.io.ioRd && JopIoSpace.isVgaText(ioAddr)
    vga.io.wr     := memCtrl.io.ioWr && JopIoSpace.isVgaText(ioAddr)
    vga.io.wrData := memCtrl.io.ioWrData
    io.vgaHsync.get := vga.io.vgaHsync
    io.vgaVsync.get := vga.io.vgaVsync
    io.vgaR.get     := vga.io.vgaR
    io.vgaG.get     := vga.io.vgaG
    io.vgaB.get     := vga.io.vgaB
  }

  // External I/O interrupts to BmbSys (dynamic indices based on IoConfig)
  bmbSys.io.ioInt := 0
  var intIdx = 0
  bmbUart.foreach { uart =>
    bmbSys.io.ioInt(intIdx)     := uart.io.rxInterrupt
    bmbSys.io.ioInt(intIdx + 1) := uart.io.txInterrupt
    intIdx += 2
  }
  if (config.hasEth) {
    bmbMdio.foreach { mdio =>
      bmbSys.io.ioInt(intIdx)     := bmbEth.map(_.io.rxInterrupt).getOrElse(False)
      bmbSys.io.ioInt(intIdx + 1) := bmbEth.map(_.io.txInterrupt).getOrElse(False)
      bmbSys.io.ioInt(intIdx + 2) := mdio.io.interrupt
      intIdx += 3
    }
  }
  bmbSdSpi.foreach { sd =>
    bmbSys.io.ioInt(intIdx) := sd.io.interrupt
    intIdx += 1
  }
  bmbSdNative.foreach { sd =>
    bmbSys.io.ioInt(intIdx) := sd.io.interrupt
    intIdx += 1
  }
  bmbVgaDma.foreach { vga =>
    bmbSys.io.ioInt(intIdx) := vga.io.vsyncInterrupt
    intIdx += 1
  }
  bmbVgaText.foreach { vga =>
    bmbSys.io.ioInt(intIdx) := vga.io.vsyncInterrupt
    intIdx += 1
  }

  // I/O read mux (last-assignment-wins; ranges are non-overlapping)
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  when(JopIoSpace.isSys(ioAddr))  { ioRdData := bmbSys.io.rdData }
  if (bmbUart.isDefined)     when(JopIoSpace.isUart(ioAddr))     { ioRdData := bmbUart.get.io.rdData }
  else                       when(JopIoSpace.isUart(ioAddr))     { ioRdData := B(1, 32 bits) } // Null device: TDRE=1 so writes silently succeed
  if (bmbEth.isDefined)      when(JopIoSpace.isEth(ioAddr))      { ioRdData := bmbEth.get.io.rdData }
  if (bmbMdio.isDefined)     when(JopIoSpace.isMdio(ioAddr))     { ioRdData := bmbMdio.get.io.rdData }
  if (bmbSdSpi.isDefined)    when(JopIoSpace.isSdSpi(ioAddr))    { ioRdData := bmbSdSpi.get.io.rdData }
  if (bmbVgaDma.isDefined)   when(JopIoSpace.isVgaDma(ioAddr))   { ioRdData := bmbVgaDma.get.io.rdData }
  if (bmbSdNative.isDefined) when(JopIoSpace.isSdNative(ioAddr)) { ioRdData := bmbSdNative.get.io.rdData }
  if (bmbVgaText.isDefined)  when(JopIoSpace.isVgaText(ioAddr))  { ioRdData := bmbVgaText.get.io.rdData }
  if (bmbCfgFlash.isDefined) when(JopIoSpace.isCfgFlash(ioAddr)) { ioRdData := bmbCfgFlash.get.io.rdData }
  memCtrl.io.ioRdData := ioRdData

  // Watchdog output
  io.wd := bmbSys.io.wd

  // Debug: UART TX snoop — registered to capture the single-cycle ioWr pulse
  // (combinational ioWr goes low before simulation can read it after waitSampling)
  val uartTxFire = memCtrl.io.ioWr && JopIoSpace.isUart(ioAddr) && JopIoSpace.uartAddr(ioAddr) === 1
  val uartTxValidReg = RegNext(uartTxFire) init(False)
  val uartTxDataReg = RegNextWhen(memCtrl.io.ioWrData(7 downto 0), uartTxFire) init(0)
  io.uartTxValid := uartTxValidReg
  io.uartTxData := uartTxDataReg

  // Debug RAM
  pipeline.io.debugRamAddr := io.debugRamAddr
  io.debugRamData := pipeline.io.debugRamData

  // ==========================================================================
  // Output Connections
  // ==========================================================================

  io.pc := pipeline.io.pc
  io.jpc := pipeline.io.jpc
  io.instr := pipeline.io.instr
  io.jfetch := pipeline.io.jfetch
  io.jopdfetch := pipeline.io.jopdfetch

  io.aout := pipeline.io.aout
  io.bout := pipeline.io.bout

  io.memBusy := memCtrl.io.memOut.busy

  io.debugExc := bmbSys.io.exc
  io.debugBcRd := pipeline.io.debugBcRd
  io.debugMemState := memCtrl.io.debug.state
  io.debugBcFillAddr := memCtrl.io.debug.bcFillAddr
  io.debugBcFillLen := memCtrl.io.debug.bcFillLen
  io.debugBcFillCount := memCtrl.io.debug.bcFillCount
  io.debugBcRdCapture := memCtrl.io.debug.bcRdCapture
  io.debugMemHandleActive := memCtrl.io.debug.handleActive
  io.debugAddrWr := pipeline.io.debugAddrWr
  io.debugRdc := pipeline.io.debugRdc
  io.debugRd := pipeline.io.debugRd

  // I/O activity counters
  val ioRdCounter = Reg(UInt(16 bits)) init(0)
  val ioWrCounter = Reg(UInt(16 bits)) init(0)
  when(memCtrl.io.ioRd) { ioRdCounter := ioRdCounter + 1 }
  when(memCtrl.io.ioWr) { ioWrCounter := ioWrCounter + 1 }
  io.debugIoRdCount := ioRdCounter
  io.debugIoWrCount := ioWrCounter

  io.debugHalted := io.debugHalt

  // Debug controller register passthrough
  io.debugSp := pipeline.io.debugSp
  io.debugVp := pipeline.io.debugVp
  io.debugAr := pipeline.io.debugAr
  io.debugFlags := pipeline.io.debugFlags
  io.debugMulResult := pipeline.io.debugMulResult
  io.debugAddrReg := memCtrl.io.debug.addrReg
  io.debugRdDataReg := memCtrl.io.debug.rdDataReg
  io.debugInstr := pipeline.io.instr
  io.debugBcopd := pipeline.io.bcopd
}

/**
 * JOP System with Block RAM Backend
 *
 * Complete JOP core using BmbOnChipRam for memory.
 * Suitable for simulation and small FPGAs.
 */
case class JopCoreWithBram(
  config: JopCoreConfig = JopCoreConfig(),
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None,
  mainMemInit: Seq[BigInt] = Seq()
) extends Component {

  val io = new Bundle {
    // CmpSync interface
    val syncIn  = in(SyncOut())
    val syncOut = out(SyncIn())

    // Watchdog from BmbSys
    val wd = out Bits(32 bits)

    // UART
    val txd = out Bool()
    val rxd = in Bool()

    // Pipeline status
    val pc        = out UInt(config.pcWidth bits)
    val jpc       = out UInt((config.jpcWidth + 1) bits)
    val instr     = out Bits(config.instrWidth bits)
    val jfetch    = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout      = out Bits(config.dataWidth bits)
    val bout      = out Bits(config.dataWidth bits)

    // Memory controller status
    val memBusy   = out Bool()

    // Debug: UART TX snoop
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()
  }

  // JOP System core
  val jopCore = JopCore(config, romInit, ramInit, jbcInit)

  // Block RAM with BMB interface
  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )

  // Initialize RAM if data provided
  if (mainMemInit.nonEmpty) {
    ram.ram.init(mainMemInit.padTo(config.memConfig.mainMemWords.toInt, BigInt(0)).map(v => B(v, 32 bits)))
  }

  // Connect BMB interfaces
  ram.io.bus << jopCore.io.bmb

  // CmpSync passthrough
  jopCore.io.syncIn := io.syncIn
  io.syncOut := jopCore.io.syncOut

  // UART passthrough
  io.txd := jopCore.io.txd
  jopCore.io.rxd := io.rxd

  // Watchdog passthrough
  io.wd := jopCore.io.wd

  // Pipeline outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch

  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout

  io.memBusy := jopCore.io.memBusy

  // Debug passthrough
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid

  // Tie unused debug inputs
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False

  // Tie off snoop (single-core, no other cores to snoop from)
  jopCore.io.snoopIn.foreach { si =>
    si.valid   := False
    si.isArray := False
    si.handle  := 0
    si.index   := 0
  }
}

/**
 * Generate Verilog for JopCore
 */
object JopCoreVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated"
  ).generate(JopCore())
}
