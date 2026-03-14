package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.config._
import jop.pipeline._
import jop.memory._
import jop.io._
import spinal.lib.com.eth._
import jop.JopPipeline

/**
 * JOP Core - Complete JOP Processor with BMB Memory Interface and Internal I/O
 *
 * Integrates:
 * - JopPipeline: All pipeline stages (BytecodeFetch, Fetch, Decode, Stack, Mul)
 * - Memory controller with BMB controller interface
 * - Sys: System I/O (clock counter, watchdog, CmpSync lock, etc.)
 * - Uart (optional): UART TX/RX with FIFOs
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

    // Watchdog from Sys
    val wd = out Bits(32 bits)

    // DMA BMB master ports (for devices with hasDma, e.g. VGA framebuffer reads)
    val dmaBmbCount = DeviceTypes.dmaCount(config.effectiveDevices)
    val dmaBmb = Vec(master(Bmb(config.memConfig.bmbParameter)), dmaBmbCount)

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

    // Debug: exception fired (from internal Sys)
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

    // Debug: halted by CmpSync (from internal Sys)
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
    // Pipeline the response path to break timing from BmbSdramCtrl32 through
    // the BMB arbiter response routing to the DMA's spillData capture register.
    io.stackDmaBmb.get <> dma.io.bmb.pipelined(rspValid = true)

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

  // Number of I/O interrupt sources
  val numIoInt = config.effectiveNumIoInt

  // System I/O (0xF0-0xFF, always present, manually wired)
  val sys = Sys(clkFreq = config.clkFreq, cpuId = config.cpuId, cpuCnt = config.cpuCnt, numIoInt = numIoInt,
    memEndWords = config.memConfig.usableMemWords(config.cpuCnt),
    fpuCapability = if (config.needsFloatCompute) 1 else 0)
  sys.io.addr   := JopIoSpace.sysAddr(ioAddr)
  sys.io.rd     := memCtrl.io.ioRd && JopIoSpace.isSys(ioAddr)
  sys.io.wr     := memCtrl.io.ioWr && JopIoSpace.isSys(ioAddr)
  sys.io.wrData := memCtrl.io.ioWrData

  // CmpSync interface
  sys.io.syncIn := io.syncIn
  io.syncOut := sys.io.syncOut

  // Exception from Sys
  pipeline.io.exc := sys.io.exc

  // Interrupts: now generated internally by Sys
  pipeline.io.irq := sys.io.irq
  pipeline.io.irqEna := sys.io.irqEna
  sys.io.ackIrq := pipeline.io.ackIrq
  sys.io.ackExc := pipeline.io.ackExc

  // ---------- Pluggable I/O: allocate addresses and instantiate devices ----------
  val ctx = DeviceContext(vgaCd, ethTxCd, ethRxCd)
  val allDescriptors = config.effectiveDeviceDescriptors(ctx)
  val allocatedDevices = IoAddressAllocator.allocate(allDescriptors)
  IoAddressAllocator.printAllocation(allocatedDevices)

  // Instantiate all devices and wire HasBusIo interface
  val ioDevices: Map[String, Component with HasBusIo] =
    allocatedDevices.map { ad =>
      val dev = ad.descriptor.factory(config)
      dev.busAddr   := ad.subAddr(ioAddr)
      dev.busRd     := memCtrl.io.ioRd && ad.isSelected(ioAddr)
      dev.busWr     := memCtrl.io.ioWr && ad.isSelected(ioAddr)
      dev.busWrData := memCtrl.io.ioWrData
      dev.busBoutSink.foreach(_ := pipeline.io.bout)
      ad.descriptor.name -> dev
    }.toMap

  // Pipeline busy: memory busy OR halted OR debug halt OR HW compute OR ext device busy
  val extBusy = ioDevices.values.flatMap(_.busBusy).foldLeft(False: Bool)(_ || _)
  pipeline.io.memBusy := memCtrl.io.memOut.busy || sys.io.halted || io.debugHalt || pipeline.io.hwBusy || extBusy

  // Interrupts: auto-wired from all device descriptors
  sys.io.ioInt := 0
  var intIdx = 0
  allocatedDevices.foreach { ad =>
    ioDevices(ad.descriptor.name).busInterrupts.foreach { irq =>
      sys.io.ioInt(intIdx) := irq
      intIdx += 1
    }
  }

  // I/O read mux (Sys + all allocated devices)
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  when(JopIoSpace.isSys(ioAddr)) { ioRdData := sys.io.rdData }
  // Null UART: if UART not present, return TDRE=1 so writes silently succeed
  if (!config.hasDevice(DeviceType.Uart)) {
    when(JopIoSpace.isUart(ioAddr)) { ioRdData := B(1, 32 bits) }
  }
  allocatedDevices.foreach { ad =>
    when(ad.isSelected(ioAddr)) { ioRdData := ioDevices(ad.descriptor.name).busRdData }
  }
  memCtrl.io.ioRdData := ioRdData

  // ---------- Dynamic pin passthrough for all I/O device external pins ----------
  //
  // For each device that declares busExternalIo, create matching passthrough
  // ports on JopCore. This avoids SpinalHDL hierarchy violations when board
  // code needs to access device pins (txd, rxd, spi, vga, etc.).
  //
  // Board code accesses pins via: jopCore.devicePins("deviceName")

  val devicePins: Map[String, Bundle] =
    allocatedDevices.flatMap { ad =>
      val dev = ioDevices(ad.descriptor.name)
      dev.busExternalIo.map { extIo =>
        val pt = cloneOf(extIo).setName(s"io_${ad.descriptor.name}")
        for ((ptSig, devSig) <- pt.flatten.zip(extIo.flatten)) {
          if (devSig.isOutput) {
            out(ptSig)
            ptSig := devSig
          } else {
            in(ptSig)
            devSig := ptSig
          }
        }
        ad.descriptor.name -> pt
      }
    }.toMap

  /** Typed accessor for individual device pin signals.
   *  Usage: jopCore.devicePin[Bool]("uart", "txd")
   */
  def devicePin[T <: Data](deviceName: String, pinName: String): T = {
    val bundle = devicePins.getOrElse(deviceName,
      throw new NoSuchElementException(s"No device pins for '$deviceName'. Available: ${devicePins.keys.mkString(", ")}"))
    bundle.elements.find(_._1 == pinName).getOrElse(
      throw new NoSuchElementException(s"No pin '$pinName' on device '$deviceName'. Available: ${bundle.elements.map(_._1).mkString(", ")}"))
      ._2.asInstanceOf[T]
  }

  // ---------- DMA BMB master wiring (generic for any device with busDmaBmb) ----------
  {
    var dmaIdx = 0
    ioDevices.values.foreach { dev =>
      dev.busDmaBmb.foreach { dmaBmb =>
        io.dmaBmb(dmaIdx) <> dmaBmb
        dmaIdx += 1
      }
    }
  }

  // Watchdog output
  io.wd := sys.io.wd

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

  io.debugExc := sys.io.exc
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

    // Watchdog from Sys
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

  // UART passthrough (via dynamic devicePins)
  if (jopCore.devicePins.contains("uart")) {
    io.txd := jopCore.devicePin[Bool]("uart", "txd")
    jopCore.devicePin[Bool]("uart", "rxd") := io.rxd
  } else {
    io.txd := True  // Idle when no UART
  }

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
