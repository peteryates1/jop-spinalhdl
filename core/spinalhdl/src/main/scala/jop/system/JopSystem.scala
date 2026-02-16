package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.pipeline._
import jop.core.Mul
import jop.memory._
import jop.JumpTableData

/**
 * JOP System Configuration
 *
 * Unified configuration for the complete JOP system.
 *
 * @param dataWidth    Data path width (32 bits)
 * @param pcWidth      Microcode PC width (11 bits = 2K ROM)
 * @param instrWidth   Microcode instruction width (10 bits)
 * @param jpcWidth     Java PC width (11 bits = 2KB bytecode cache)
 * @param ramWidth     Stack RAM address width (8 bits = 256 entries)
 * @param memConfig    Memory subsystem configuration
 */
case class JopSystemConfig(
  dataWidth:  Int              = 32,
  pcWidth:    Int              = 11,
  instrWidth: Int              = 10,
  jpcWidth:   Int              = 11,
  ramWidth:   Int              = 8,
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
 * JOP System - Complete JOP Processor with BMB Memory Interface
 *
 * Integrates:
 * - Pipeline stages: BytecodeFetch → Fetch → Decode → Stack
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
case class JopSystem(
  config: JopSystemConfig = JopSystemConfig(),
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

    // Interrupt interface
    val irq       = in Bool()
    val irqEna    = in Bool()

    // Debug: bytecode cache fill
    val debugBcRd = out Bool()

    // Debug: memory controller state
    val debugMemState = out UInt(4 bits)
    val debugMemHandleActive = out Bool()
    val debugAddrWr = out Bool()  // Decode stage addrWr signal
    val debugRdc = out Bool()     // Memory read combined (stmrac)
    val debugRd = out Bool()      // Memory read (stmra)

    // Debug: RAM slot read
    val debugRamAddr = in UInt(8 bits)
    val debugRamData = out Bits(32 bits)
  }

  // ==========================================================================
  // Pipeline Stages
  // ==========================================================================

  val bcfetch = BytecodeFetchStage(config.bcfetchConfig, jbcInit)
  val fetch = FetchStage(config.fetchConfig, romInit)
  val decode = new DecodeStage(config.decodeConfig)
  val stack = new StackStage(config.stackConfig, ramInit = ramInit)
  val mul = Mul(config.dataWidth)

  // ==========================================================================
  // Memory Controller
  // ==========================================================================

  val memCtrl = BmbMemoryController(config.memConfig, config.jpcWidth)

  // Connect BMB master to external interface
  io.bmb <> memCtrl.io.bmb

  // ==========================================================================
  // Pipeline Connections
  // ==========================================================================

  // BytecodeFetch connections
  bcfetch.io.jfetch := fetch.io.nxt
  bcfetch.io.jopdfetch := fetch.io.opd
  bcfetch.io.jbr := decode.io.jbr
  bcfetch.io.zf := stack.io.zf
  bcfetch.io.nf := stack.io.nf
  bcfetch.io.eq := stack.io.eq
  bcfetch.io.lt := stack.io.lt
  bcfetch.io.jpc_wr := decode.io.enaJpc
  bcfetch.io.din := stack.io.aout

  // JBC write from memory controller (for bytecode cache fill)
  bcfetch.io.jbcWrAddr := memCtrl.io.jbcWrite.addr
  bcfetch.io.jbcWrData := memCtrl.io.jbcWrite.data
  bcfetch.io.jbcWrEn := memCtrl.io.jbcWrite.enable

  // Interrupt/exception
  bcfetch.io.irq := io.irq
  bcfetch.io.exc := False
  bcfetch.io.ena := io.irqEna

  // Fetch stage connections
  fetch.io.jpaddr := bcfetch.io.jpaddr
  fetch.io.br := decode.io.br
  fetch.io.jmp := decode.io.jmp
  fetch.io.bsy := decode.io.wrDly || memCtrl.io.memOut.busy

  // Decode stage connections
  decode.io.instr := fetch.io.dout
  decode.io.zf := stack.io.zf
  decode.io.nf := stack.io.nf
  decode.io.eq := stack.io.eq
  decode.io.lt := stack.io.lt
  decode.io.bcopd := bcfetch.io.opd

  // ==========================================================================
  // Memory Controller Interface
  // ==========================================================================

  // Memory control signals from decode
  memCtrl.io.memIn.rd := decode.io.memIn.rd
  memCtrl.io.memIn.rdc := decode.io.memIn.rdc
  memCtrl.io.memIn.rdf := decode.io.memIn.rdf
  memCtrl.io.memIn.wr := decode.io.memIn.wr
  memCtrl.io.memIn.wrf := decode.io.memIn.wrf
  memCtrl.io.memIn.addrWr := decode.io.memIn.addrWr
  memCtrl.io.memIn.bcRd := decode.io.memIn.bcRd
  memCtrl.io.memIn.stidx := decode.io.memIn.stidx
  memCtrl.io.memIn.iaload := decode.io.memIn.iaload
  memCtrl.io.memIn.iastore := decode.io.memIn.iastore
  memCtrl.io.memIn.getfield := decode.io.memIn.getfield
  memCtrl.io.memIn.putfield := decode.io.memIn.putfield
  memCtrl.io.memIn.putref := decode.io.memIn.putref
  memCtrl.io.memIn.getstatic := decode.io.memIn.getstatic
  memCtrl.io.memIn.putstatic := decode.io.memIn.putstatic
  memCtrl.io.memIn.copy := decode.io.memIn.copy
  memCtrl.io.memIn.cinval := decode.io.memIn.cinval
  memCtrl.io.memIn.atmstart := decode.io.memIn.atmstart
  memCtrl.io.memIn.atmend := decode.io.memIn.atmend
  memCtrl.io.memIn.bcopd := decode.io.memIn.bcopd

  // Stack values for memory operations
  memCtrl.io.aout := stack.io.aout
  memCtrl.io.bout := stack.io.bout

  // Bytecode operand
  memCtrl.io.bcopd := bcfetch.io.opd

  // I/O data input (directly from external)
  memCtrl.io.ioRdData := io.ioRdData

  // ==========================================================================
  // Stack Stage Connections
  // ==========================================================================

  // External data input (memory read result or multiplier)
  // din mux: ir(1:0) selects between ldmrd, ldmul, ldbcstart
  val dinMuxSel = RegNext(fetch.io.ir_out(1 downto 0)) init(0)
  stack.io.din := dinMuxSel.mux(
    0 -> memCtrl.io.memOut.rdData,           // ldmrd
    1 -> mul.io.dout.asBits,                 // ldmul
    2 -> memCtrl.io.memOut.bcStart.asBits.resized,  // ldbcstart
    3 -> B(0, 32 bits)                       // reserved
  )

  stack.io.dirAddr := decode.io.dirAddr.asUInt
  stack.io.opd := bcfetch.io.opd
  stack.io.jpc := bcfetch.io.jpc_out

  // Control signals from decode
  stack.io.selSub := decode.io.selSub
  stack.io.selAmux := decode.io.selAmux
  stack.io.enaA := decode.io.enaA
  stack.io.selBmux := decode.io.selBmux
  stack.io.selLog := decode.io.selLog
  stack.io.selShf := decode.io.selShf
  stack.io.selLmux := decode.io.selLmux
  stack.io.selImux := decode.io.selImux
  stack.io.selRmux := decode.io.selRmux
  stack.io.selSmux := decode.io.selSmux
  stack.io.selMmux := decode.io.selMmux
  stack.io.selRda := decode.io.selRda
  stack.io.selWra := decode.io.selWra
  stack.io.wrEna := decode.io.wrEna
  stack.io.enaB := decode.io.enaB
  stack.io.enaVp := decode.io.enaVp
  stack.io.enaAr := decode.io.enaAr

  // Debug ports - use input for address, expose data
  stack.io.debugRamAddr := io.debugRamAddr
  stack.io.debugRamWrAddr := 0
  stack.io.debugRamWrData := 0
  stack.io.debugRamWrEn := False
  io.debugRamData := stack.io.debugRamData

  // ==========================================================================
  // Multiplier
  // ==========================================================================

  mul.io.ain := stack.io.aout.asUInt
  mul.io.bin := stack.io.bout.asUInt
  mul.io.wr := decode.io.mulWr

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

  io.pc := fetch.io.pc_out
  io.jpc := bcfetch.io.jpc_out
  io.instr := fetch.io.dout
  io.jfetch := fetch.io.nxt
  io.jopdfetch := fetch.io.opd

  io.aout := stack.io.aout
  io.bout := stack.io.bout

  io.memBusy := memCtrl.io.memOut.busy

  io.debugBcRd := decode.io.memIn.bcRd
  io.debugMemState := memCtrl.io.debug.state
  io.debugMemHandleActive := memCtrl.io.debug.handleActive
  io.debugAddrWr := decode.io.memIn.addrWr
  io.debugRdc := decode.io.memIn.rdc
  io.debugRd := decode.io.memIn.rd
}

/**
 * JOP System with Block RAM Backend
 *
 * Complete JOP system using BmbOnChipRam for memory.
 * Suitable for simulation and small FPGAs.
 */
case class JopSystemWithBram(
  config: JopSystemConfig = JopSystemConfig(),
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

    // Interrupt interface
    val irq       = in Bool()
    val irqEna    = in Bool()
  }

  // JOP System core
  val jopSystem = JopSystem(config, romInit, ramInit, jbcInit)

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
  ram.io.bus << jopSystem.io.bmb

  // Wire up I/O
  io.ioAddr := jopSystem.io.ioAddr
  io.ioRd := jopSystem.io.ioRd
  io.ioWr := jopSystem.io.ioWr
  io.ioWrData := jopSystem.io.ioWrData
  jopSystem.io.ioRdData := io.ioRdData

  // Pipeline outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch

  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout

  io.memBusy := jopSystem.io.memBusy

  // Interrupt
  jopSystem.io.irq := io.irq
  jopSystem.io.irqEna := io.irqEna
}

/**
 * Generate Verilog for JopSystem
 */
object JopSystemVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated"
  ).generate(JopSystem())
}
