package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.pipeline._
import jop.memory._
import jop.io.{BmbSys, BmbUart, BmbEth, BmbMdio, SyncIn, SyncOut}
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
 * @param hasUart      Whether to instantiate BmbUart
 * @param clkFreqHz    System clock frequency in Hz (for BmbSys microsecond prescaler)
 * @param uartBaudRate UART baud rate in Hz (only used when hasUart = true)
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
  hasUart:      Boolean          = true,
  hasEth:       Boolean          = false,
  clkFreqHz:    Long             = 100000000L,
  uartBaudRate: Int              = 1000000
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth)
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
  ethRxCd: Option[ClockDomain] = None
) extends Component {

  val io = new Bundle {
    // BMB controller interface to external memory
    val bmb = master(Bmb(config.memConfig.bmbParameter))

    // CmpSync interface
    val syncIn  = in(SyncOut())    // From CmpSync: halted + signal
    val syncOut = out(SyncIn())    // To CmpSync: lock request + signal

    // Watchdog from BmbSys
    val wd = out Bits(32 bits)

    // UART
    val txd = out Bool()
    val rxd = in Bool()

    // Ethernet PHY (optional, only when hasEth)
    val phy = if (config.hasEth) Some(master(PhyIo(PhyParameter(txDataWidth = 4, rxDataWidth = 4)))) else None

    // MDIO pins (optional, only when hasEth)
    val mdc      = if (config.hasEth) Some(out Bool()) else None
    val mdioOut  = if (config.hasEth) Some(out Bool()) else None
    val mdioOe   = if (config.hasEth) Some(out Bool()) else None
    val mdioIn   = if (config.hasEth) Some(in Bool()) else None
    val phyReset = if (config.hasEth) Some(out Bool()) else None

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
    val debugSp   = out UInt(config.ramWidth bits)
    val debugVp   = out UInt(config.ramWidth bits)
    val debugAr   = out UInt(config.ramWidth bits)
    val debugFlags = out Bits(4 bits)
    val debugMulResult = out Bits(config.dataWidth bits)
    val debugAddrReg   = out UInt(config.memConfig.addressWidth bits)
    val debugRdDataReg = out Bits(config.dataWidth bits)
    val debugInstr     = out Bits(config.instrWidth bits)
    val debugBcopd     = out Bits(16 bits)

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
  // Internal I/O Subsystem
  // ==========================================================================

  // I/O address decoding
  val ioSubAddr = memCtrl.io.ioAddr(3 downto 0)
  val ioDeviceId = memCtrl.io.ioAddr(5 downto 4)

  // Number of I/O interrupt sources: 2 base (UART RX/TX) + 3 if Ethernet (ETH RX/TX, MDIO)
  val numIoInt = if (config.hasEth) 5 else 2

  // System I/O (device 0)
  val bmbSys = BmbSys(clkFreqHz = config.clkFreqHz, cpuId = config.cpuId, cpuCnt = config.cpuCnt, numIoInt = numIoInt)
  bmbSys.io.addr   := ioSubAddr
  bmbSys.io.rd     := memCtrl.io.ioRd && ioDeviceId === 0
  bmbSys.io.wr     := memCtrl.io.ioWr && ioDeviceId === 0
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

  // UART (device 1, optional)
  val bmbUart = if (config.hasUart) Some(BmbUart(config.uartBaudRate, config.clkFreqHz)) else None

  bmbUart.foreach { uart =>
    uart.io.addr   := ioSubAddr
    uart.io.rd     := memCtrl.io.ioRd && ioDeviceId === 1
    uart.io.wr     := memCtrl.io.ioWr && ioDeviceId === 1
    uart.io.wrData := memCtrl.io.ioWrData
    io.txd := uart.io.txd
    uart.io.rxd := io.rxd
  }
  if (bmbUart.isEmpty) {
    io.txd := True  // Idle
  }

  // Ethernet MAC (device 2, optional)
  val bmbEth = if (config.hasEth && ethTxCd.isDefined && ethRxCd.isDefined)
    Some(BmbEth(txCd = ethTxCd.get, rxCd = ethRxCd.get))
  else None

  bmbEth.foreach { eth =>
    eth.io.addr   := ioSubAddr
    eth.io.rd     := memCtrl.io.ioRd && ioDeviceId === 2
    eth.io.wr     := memCtrl.io.ioWr && ioDeviceId === 2
    eth.io.wrData := memCtrl.io.ioWrData
    io.phy.get <> eth.io.phy
  }

  // MDIO controller (device 3, optional)
  val bmbMdio = if (config.hasEth) Some(BmbMdio()) else None

  bmbMdio.foreach { mdio =>
    mdio.io.addr   := ioSubAddr
    mdio.io.rd     := memCtrl.io.ioRd && ioDeviceId === 3
    mdio.io.wr     := memCtrl.io.ioWr && ioDeviceId === 3
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

  // External I/O interrupts to BmbSys
  // Index 0: UART RX, Index 1: UART TX
  // Index 2: ETH RX, Index 3: ETH TX, Index 4: MDIO (via BmbMdio combined interrupt)
  bmbSys.io.ioInt := 0
  bmbUart.foreach { uart =>
    bmbSys.io.ioInt(0) := uart.io.rxInterrupt
    bmbSys.io.ioInt(1) := uart.io.txInterrupt
  }
  if (config.hasEth) {
    bmbMdio.foreach { mdio =>
      bmbSys.io.ioInt(2) := bmbEth.map(_.io.rxInterrupt).getOrElse(False)
      bmbSys.io.ioInt(3) := bmbEth.map(_.io.txInterrupt).getOrElse(False)
      bmbSys.io.ioInt(4) := mdio.io.interrupt
    }
  }

  // I/O read mux
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  switch(ioDeviceId) {
    is(0) { ioRdData := bmbSys.io.rdData }
    is(1) {
      if (bmbUart.isDefined) ioRdData := bmbUart.get.io.rdData
      // No UART: ioRdData stays 0 (TDRE=0). Cores without UART must not
      // access it — reads will return "TX busy" causing a hang, which makes
      // the bug visible. TODO: consider an exception or debug trap here.
    }
    is(2) {
      if (bmbEth.isDefined) ioRdData := bmbEth.get.io.rdData
    }
    is(3) {
      if (bmbMdio.isDefined) ioRdData := bmbMdio.get.io.rdData
    }
  }
  memCtrl.io.ioRdData := ioRdData

  // Watchdog output
  io.wd := bmbSys.io.wd

  // Debug: UART TX snoop — registered to capture the single-cycle ioWr pulse
  // (combinational ioWr goes low before simulation can read it after waitSampling)
  val uartTxFire = memCtrl.io.ioWr && ioDeviceId === 1 && ioSubAddr === 1
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
