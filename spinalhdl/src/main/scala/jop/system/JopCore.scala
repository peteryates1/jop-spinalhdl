package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.pipeline._
import jop.memory._
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
 */
case class JopCoreConfig(
  dataWidth:  Int              = 32,
  pcWidth:    Int              = 11,
  instrWidth: Int              = 10,
  jpcWidth:   Int              = 11,
  ramWidth:   Int              = 8,
  blockBits:  Int              = 4,
  memConfig:  JopMemoryConfig  = JopMemoryConfig(),
  jumpTable:  JumpTableInitData = JumpTableInitData.simulation
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
 * JOP Core - Complete JOP Processor with BMB Memory Interface
 *
 * Integrates:
 * - JopPipeline: All pipeline stages (BytecodeFetch, Fetch, Decode, Stack, Mul)
 * - Memory controller with BMB master interface
 * - I/O handling (directly exposed for external connection)
 *
 * The BMB master interface connects to external memory (BmbOnChipRam, BmbSdramCtrl, etc.)
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
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // BMB master interface to external memory
    val bmb = master(Bmb(config.memConfig.bmbParameter))

    // I/O interface (directly accessible)
    val ioAddr    = out UInt(8 bits)
    val ioRd      = out Bool()
    val ioWr      = out Bool()
    val ioWrData  = out Bits(32 bits)
    val ioRdData  = in Bits(32 bits)

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

    // Interrupt / Exception interface
    val irq       = in Bool()
    val irqEna    = in Bool()
    val exc       = in Bool()   // Exception signal from I/O subsystem

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
  }

  // ==========================================================================
  // Pipeline
  // ==========================================================================

  val pipeline = JopPipeline(config, romInit, ramInit, jbcInit)

  // ==========================================================================
  // Memory Controller
  // ==========================================================================

  val memCtrl = BmbMemoryController(config.memConfig, config.jpcWidth, config.blockBits)

  // Connect BMB master to external interface
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
  pipeline.io.memBusy := memCtrl.io.memOut.busy
  pipeline.io.jbcWrAddr := memCtrl.io.jbcWrite.addr
  pipeline.io.jbcWrData := memCtrl.io.jbcWrite.data
  pipeline.io.jbcWrEn := memCtrl.io.jbcWrite.enable

  // I/O data input (directly from external)
  memCtrl.io.ioRdData := io.ioRdData

  // Interrupts / Exceptions
  pipeline.io.irq := io.irq
  pipeline.io.irqEna := io.irqEna
  pipeline.io.exc := io.exc

  // Debug RAM
  pipeline.io.debugRamAddr := io.debugRamAddr
  io.debugRamData := pipeline.io.debugRamData

  // ==========================================================================
  // I/O Interface
  // ==========================================================================

  io.ioAddr := memCtrl.io.ioAddr
  io.ioRd := memCtrl.io.ioRd
  io.ioWr := memCtrl.io.ioWr
  io.ioWrData := memCtrl.io.ioWrData

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

  io.debugBcRd := pipeline.io.debugBcRd
  io.debugMemState := memCtrl.io.debug.state
  io.debugMemHandleActive := memCtrl.io.debug.handleActive
  io.debugAddrWr := pipeline.io.debugAddrWr
  io.debugRdc := pipeline.io.debugRdc
  io.debugRd := pipeline.io.debugRd
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
    // I/O interface
    val ioAddr    = out UInt(8 bits)
    val ioRd      = out Bool()
    val ioWr      = out Bool()
    val ioWrData  = out Bits(32 bits)
    val ioRdData  = in Bits(32 bits)

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

    // Interrupt / Exception interface
    val irq       = in Bool()
    val irqEna    = in Bool()
    val exc       = in Bool()   // Exception signal from I/O subsystem
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

  // Wire up I/O
  io.ioAddr := jopCore.io.ioAddr
  io.ioRd := jopCore.io.ioRd
  io.ioWr := jopCore.io.ioWr
  io.ioWrData := jopCore.io.ioWrData
  jopCore.io.ioRdData := io.ioRdData

  // Pipeline outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch

  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout

  io.memBusy := jopCore.io.memBusy

  // Interrupt / Exception
  jopCore.io.irq := io.irq
  jopCore.io.irqEna := io.irqEna
  jopCore.io.exc := io.exc
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
