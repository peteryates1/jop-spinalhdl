package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._

/**
 * JOP Core - Microcode Pipeline Integration
 *
 * Integrates the three main pipeline stages:
 * 1. FetchStage  - Microcode instruction fetch from ROM
 * 2. DecodeStage - Microcode instruction decode (generates control signals)
 * 3. StackStage  - Execution (ALU, stack, registers)
 *
 * This implementation focuses on the microcode pipeline (not bytecode level).
 * For full JOP integration including bytecode fetch, see JopCpu.scala (future).
 *
 * Pipeline Flow:
 * - FetchStage reads microcode instruction from ROM
 * - DecodeStage decodes instruction to control signals
 * - StackStage executes using control signals
 * - Flags feedback from Stack to Decode for branch decisions
 *
 * Based on core.vhd lines 307-343, but using SpinalHDL idioms.
 */
case class JopCoreConfig(
  dataWidth: Int = 32,      // Data path width
  pcWidth: Int = 11,        // Microcode PC width (2K ROM)
  instrWidth: Int = 10,     // Microcode instruction width
  jpcWidth: Int = 10,       // Java PC width (for future bytecode integration)
  ramWidth: Int = 8         // Stack RAM address width (256 entries)
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(ramWidth > 0 && ramWidth <= 16, "RAM width must be 1-16 bits")

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth)
}

/**
 * JOP Core I/O Bundle
 *
 * External interface for the JOP microcode core.
 * Simplified for Phase 2.1 - microcode pipeline only, bytecode fetch deferred.
 */
case class JopCoreIO(config: JopCoreConfig) extends Bundle with IMasterSlave {
  // External memory interface (data)
  val memDataIn = in(Bits(config.dataWidth bits))
  val memBusy = in(Bool())

  // Stack outputs (for observation/debugging)
  val aout = out(Bits(config.dataWidth bits))
  val bout = out(Bits(config.dataWidth bits))
  val spOv = out(Bool())

  // Operand input (from bytecode stage, for now externally driven for testing)
  val operand = in(Bits(16 bits))
  val jpc = in(UInt((config.jpcWidth + 1) bits))

  // Fetch stage outputs (for debugging/observation)
  val jfetch = out(Bool())      // Java bytecode fetch signal
  val jopdfetch = out(Bool())   // Java operand fetch signal

  override def asMaster(): Unit = {
    in(memDataIn, memBusy, operand, jpc)
    out(aout, bout, spOv, jfetch, jopdfetch)
  }
}

/**
 * JOP Core - Main Pipeline Component
 *
 * Integrates Fetch → Decode → Stack pipeline.
 * Uses explicit signal connections (not Pipeline API) for clarity and control.
 */
class JopCore(val config: JopCoreConfig = JopCoreConfig()) extends Component {
  val io = JopCoreIO(config)

  // ========================================================================
  // Pipeline Stage Instantiation
  // ========================================================================

  val fetchStage = new FetchStage(config.fetchConfig)
  val decodeStage = new DecodeStage(config.decodeConfig)
  val stackStage = new StackStage(config.stackConfig)

  // ========================================================================
  // Fetch Stage Connections
  // ========================================================================

  // Branch control from decode stage
  fetchStage.io.br := decodeStage.io.br
  fetchStage.io.jmp := decodeStage.io.jmp
  fetchStage.io.bsy := io.memBusy
  fetchStage.io.jpaddr := U(0, config.pcWidth bits)  // TODO: Connect to bytecode fetch stage

  // Fetch stage outputs
  io.jfetch := fetchStage.io.nxt
  io.jopdfetch := fetchStage.io.opd

  // ========================================================================
  // Decode Stage Connections
  // ========================================================================

  // Instruction input from fetch
  decodeStage.io.instr := fetchStage.io.dout

  // Flags from stack (for conditional branches)
  decodeStage.io.zf := stackStage.io.zf
  decodeStage.io.nf := stackStage.io.nf
  decodeStage.io.eq := stackStage.io.eq
  decodeStage.io.lt := stackStage.io.lt

  // Operand from bytecode (for now, externally provided for testing)
  decodeStage.io.bcopd := io.operand

  // ========================================================================
  // Stack Stage Connections
  // ========================================================================

  // Data inputs
  stackStage.io.din := io.memDataIn
  stackStage.io.dirAddr := decodeStage.io.dirAddr.asUInt
  stackStage.io.opd := io.operand
  stackStage.io.jpc := io.jpc

  // Control signals from decode (ALU control)
  stackStage.io.selSub := decodeStage.io.selSub
  stackStage.io.selAmux := decodeStage.io.selAmux
  stackStage.io.enaA := decodeStage.io.enaA

  // Control signals from decode (data path muxes)
  stackStage.io.selBmux := decodeStage.io.selBmux
  stackStage.io.selLog := decodeStage.io.selLog
  stackStage.io.selShf := decodeStage.io.selShf
  stackStage.io.selLmux := decodeStage.io.selLmux
  stackStage.io.selImux := decodeStage.io.selImux
  stackStage.io.selRmux := decodeStage.io.selRmux
  stackStage.io.selSmux := decodeStage.io.selSmux

  // Control signals from decode (memory)
  stackStage.io.selMmux := decodeStage.io.selMmux
  stackStage.io.selRda := decodeStage.io.selRda
  stackStage.io.selWra := decodeStage.io.selWra
  stackStage.io.wrEna := decodeStage.io.wrEna

  // Control signals from decode (register enables)
  stackStage.io.enaB := decodeStage.io.enaB
  stackStage.io.enaVp := decodeStage.io.enaVp
  stackStage.io.enaAr := decodeStage.io.enaAr

  // Outputs to external interface
  io.aout := stackStage.io.aout
  io.bout := stackStage.io.bout
  io.spOv := stackStage.io.spOv

  // ========================================================================
  // Debug/Monitoring (can be removed for synthesis)
  // ========================================================================

  // Add component name for better debug visibility
  fetchStage.setName("fetch")
  decodeStage.setName("decode")
  stackStage.setName("stack")
}

/**
 * Generate VHDL/Verilog for JOP Core
 */
object JopCoreVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  config.generateVerilog(new JopCore())
  println("JopCore Verilog generated in generated/JopCore.v")
}

object JopCoreVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  config.generateVhdl(new JopCore())
  println("JopCore VHDL generated in generated/JopCore.vhd")
}

/**
 * Generate testbench wrapper for CocoTB
 *
 * Creates a testbench with explicit clock/reset and flattened I/O
 * for CocoTB compatibility (similar to StackStageTb).
 */
object JopCoreTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreTb extends Component {
    noIoPrefix()  // Match VHDL naming conventions

    // Explicit clock and reset for CocoTB
    val clk = in Bool()
    val reset = in Bool()

    // Flatten the JopCore I/O for CocoTB
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    // Create explicit clock domain
    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCore()

      // Connect flattened I/O
      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreTb)
  println("JopCoreTb VHDL generated in generated/JopCoreTb.vhd")
}
