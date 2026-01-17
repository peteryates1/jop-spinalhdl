package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._
import jop.core.Mul

/**
 * JOP Core with Bytecode Fetch Integration
 *
 * This component integrates BytecodeFetchStage with the existing microcode pipeline:
 *
 * Pipeline Flow:
 *   BytecodeFetch → FetchStage → DecodeStage → StackStage
 *        ↓              ↓            ↓            ↓
 *    bytecode       microcode    control      execute
 *    → jpaddr       → instr     signals
 *
 * Connections:
 * - BytecodeFetch.jpaddr → FetchStage.jpaddr (microcode address)
 * - BytecodeFetch.opd → DecodeStage.bcopd (operand for immediate loads)
 * - BytecodeFetch.jpc_out → StackStage.jpc (Java PC for returns)
 * - FetchStage.nxt → BytecodeFetch.jfetch (fetch next bytecode)
 * - FetchStage.opd → BytecodeFetch.jopdfetch (fetch operand byte)
 * - DecodeStage.jbr → BytecodeFetch.jbr (branch evaluation)
 * - StackStage flags → BytecodeFetch condition flags (zf, nf, eq, lt)
 *
 * This enables end-to-end bytecode execution:
 * 1. BytecodeFetch reads Java bytecode from JBC RAM
 * 2. Jump table translates bytecode to microcode address
 * 3. FetchStage reads microcode instruction from ROM
 * 4. DecodeStage generates control signals
 * 5. StackStage executes the operation
 * 6. Condition flags fed back for branch decisions
 */
case class JopCoreWithBytecodeConfig(
  dataWidth: Int = 32,      // Data path width
  pcWidth: Int = 11,        // Microcode PC width (2K ROM)
  instrWidth: Int = 10,     // Microcode instruction width
  jpcWidth: Int = 11,       // Java PC width (2KB bytecode cache)
  ramWidth: Int = 8         // Stack RAM address width (256 entries)
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")
  require(ramWidth > 0 && ramWidth <= 16, "RAM width must be 1-16 bits")

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth)
  def bcfetchConfig = BytecodeFetchConfig(jpcWidth, pcWidth)
}

/**
 * JOP Core with Bytecode I/O Bundle
 */
case class JopCoreWithBytecodeIO(config: JopCoreWithBytecodeConfig) extends Bundle {
  // External memory interface (data)
  val memDataIn = in(Bits(config.dataWidth bits))
  val memBusy = in(Bool())

  // JBC write interface (for loading bytecodes into cache)
  val jbcWrAddr = in(UInt((config.jpcWidth - 2) bits))  // Word address
  val jbcWrData = in(Bits(32 bits))
  val jbcWrEn = in(Bool())

  // Stack outputs (for observation/debugging)
  val aout = out(Bits(config.dataWidth bits))
  val bout = out(Bits(config.dataWidth bits))
  val spOv = out(Bool())

  // JPC interface (for debugging)
  val jpcOut = out(UInt((config.jpcWidth + 1) bits))
  val jpcWr = in(Bool())  // Write JPC from stack (for method calls)

  // Bytecode stage outputs (for debugging)
  val jfetch = out(Bool())      // Java bytecode fetch signal
  val jopdfetch = out(Bool())   // Java operand fetch signal
  val jpaddr = out(UInt(config.pcWidth bits))  // Microcode address
  val opd = out(Bits(16 bits))  // Current operand

  // Multiplier output (for debugging/ldmul)
  val mulDout = out(UInt(config.dataWidth bits))
}

/**
 * JOP Core with Bytecode Fetch - Main Component
 */
class JopCoreWithBytecode(
  val config: JopCoreWithBytecodeConfig = JopCoreWithBytecodeConfig(),
  jbcInit: Option[Seq[BigInt]] = None,
  romInit: Option[Seq[BigInt]] = None
) extends Component {
  val io = JopCoreWithBytecodeIO(config)

  // ========================================================================
  // Pipeline Stage Instantiation
  // ========================================================================

  val bcfetchStage = BytecodeFetchStage(config.bcfetchConfig, jbcInit)
  val fetchStage = FetchStage(config.fetchConfig, romInit)
  val decodeStage = new DecodeStage(config.decodeConfig)
  val stackStage = new StackStage(config.stackConfig)
  val multiplier = Mul(config.dataWidth)

  // ========================================================================
  // BytecodeFetch Stage Connections
  // ========================================================================

  // Control inputs from fetch stage
  bcfetchStage.io.jfetch := fetchStage.io.nxt
  bcfetchStage.io.jopdfetch := fetchStage.io.opd

  // Branch control from decode stage
  bcfetchStage.io.jbr := decodeStage.io.jbr

  // Condition flags from stack stage
  bcfetchStage.io.zf := stackStage.io.zf
  bcfetchStage.io.nf := stackStage.io.nf
  bcfetchStage.io.eq := stackStage.io.eq
  bcfetchStage.io.lt := stackStage.io.lt

  // JPC write from external (for method calls)
  bcfetchStage.io.jpc_wr := io.jpcWr
  bcfetchStage.io.din := stackStage.io.aout  // Stack TOS for method calls

  // JBC write interface (for loading bytecodes into cache)
  bcfetchStage.io.jbcWrAddr := io.jbcWrAddr
  bcfetchStage.io.jbcWrData := io.jbcWrData
  bcfetchStage.io.jbcWrEn := io.jbcWrEn

  // Debug outputs
  io.jfetch := fetchStage.io.nxt
  io.jopdfetch := fetchStage.io.opd
  io.jpaddr := bcfetchStage.io.jpaddr
  io.opd := bcfetchStage.io.opd
  io.jpcOut := bcfetchStage.io.jpc_out

  // ========================================================================
  // Fetch Stage Connections
  // ========================================================================

  // Microcode address from bytecode stage (via jump table)
  fetchStage.io.jpaddr := bcfetchStage.io.jpaddr

  // Branch control from decode stage
  fetchStage.io.br := decodeStage.io.br
  fetchStage.io.jmp := decodeStage.io.jmp
  fetchStage.io.bsy := io.memBusy

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

  // Operand from bytecode stage
  decodeStage.io.bcopd := bcfetchStage.io.opd

  // ========================================================================
  // Stack Stage Connections
  // ========================================================================

  // Data inputs
  stackStage.io.din := io.memDataIn
  stackStage.io.dirAddr := decodeStage.io.dirAddr.asUInt
  stackStage.io.opd := bcfetchStage.io.opd
  stackStage.io.jpc := bcfetchStage.io.jpc_out

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
  // Multiplier Connections
  // ========================================================================

  multiplier.io.ain := stackStage.io.aout.asUInt
  multiplier.io.bin := stackStage.io.bout.asUInt
  multiplier.io.wr := decodeStage.io.mulWr
  io.mulDout := multiplier.io.dout

  // ========================================================================
  // Component Names for Debug
  // ========================================================================

  bcfetchStage.setName("bcfetch")
  fetchStage.setName("fetch")
  decodeStage.setName("decode")
  stackStage.setName("stack")
  multiplier.setName("mul")
}

/**
 * Generate VHDL for JOP Core with Bytecode
 */
object JopCoreWithBytecodeVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  config.generateVhdl(new JopCoreWithBytecode())
  println("JopCoreWithBytecode VHDL generated in generated/JopCoreWithBytecode.vhd")
}

/**
 * Bytecode Execution ROM Generator
 *
 * Creates a microcode ROM where bytecode handler addresses have jfetch=1
 * to dispatch to the next bytecode. This enables end-to-end bytecode execution.
 *
 * ROM format (12 bits for 10-bit instructions):
 * - Bit 11: jfetch (triggers bytecode fetch)
 * - Bit 10: jopdfetch (triggers operand byte fetch)
 * - Bits 9:0: microcode instruction
 */
object BytecodeExecutionRom {
  /**
   * Generate ROM with bytecode handlers
   *
   * @param romDepth ROM size (2048 for 11-bit address)
   * @param romWidth ROM data width (12 for 10-bit instruction + 2 flags)
   */
  def generate(romDepth: Int = 2048, romWidth: Int = 12): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    // Helper to set ROM entry
    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      if (addr < romDepth) {
        val value = (jf << (romWidth - 1)) | (jod << (romWidth - 2)) | instr
        rom(addr) = BigInt(value)
      }
    }

    // Jump table addresses from jtbl.vhd (discovered from tests)
    // Each bytecode handler ends with jfetch=1 to dispatch to next bytecode

    // Address 0: Boot/reset - NOP (no jfetch yet, let JBC RAM settle)
    // The first jfetch happens when PC naturally reaches a bytecode handler
    // Boot sequence: ROM[0] -> ROM[1] -> ... until we hit a handler with jfetch=1
    setRom(0, 0, 0, 0x000)  // NOP without jfetch
    setRom(1, 0, 0, 0x000)  // NOP without jfetch
    setRom(2, 0, 0, 0x000)  // NOP without jfetch
    setRom(3, 0, 0, 0x000)  // NOP without jfetch

    // Bytecode handlers (single-cycle: just set jfetch=1 to dispatch)
    // These are minimal handlers - real microcode would have actual instructions

    // nop (0x00) -> 0x218
    setRom(0x218, 1, 0, 0x000)  // NOP with jfetch=1

    // iconst_m1 (0x02) -> iconst handlers
    // iconst_0 (0x03) -> 0x21A
    setRom(0x21A, 1, 0, 0x000)

    // iconst_1 (0x04) -> 0x21B
    setRom(0x21B, 1, 0, 0x000)

    // iconst_2 (0x05) -> 0x21C
    setRom(0x21C, 1, 0, 0x000)

    // iconst_3 (0x06) -> 0x21D
    setRom(0x21D, 1, 0, 0x000)

    // iconst_4 (0x07) -> 0x21E
    setRom(0x21E, 1, 0, 0x000)

    // iconst_5 (0x08) -> 0x21F
    setRom(0x21F, 1, 0, 0x000)

    // bipush (0x10) -> 0x220 (needs operand fetch)
    setRom(0x220, 0, 1, 0x000)  // First: fetch operand byte (jopdfetch=1)
    setRom(0x221, 1, 0, 0x000)  // Then: dispatch (jfetch=1)

    // iload_0 (0x1A) -> 0x236
    setRom(0x236, 1, 0, 0x000)

    // iload_1 (0x1B) -> 0x237
    setRom(0x237, 1, 0, 0x000)

    // iload_2 (0x1C) -> 0x238
    setRom(0x238, 1, 0, 0x000)

    // iload_3 (0x1D) -> 0x239
    setRom(0x239, 1, 0, 0x000)

    // istore_0 (0x3B) -> 0x23C
    setRom(0x23C, 1, 0, 0x000)

    // istore_1 (0x3C) -> 0x23D
    setRom(0x23D, 1, 0, 0x000)

    // istore_2 (0x3D) -> 0x23E
    setRom(0x23E, 1, 0, 0x000)

    // istore_3 (0x3E) -> 0x23F
    setRom(0x23F, 1, 0, 0x000)

    // pop (0x57) -> 0x240
    setRom(0x240, 1, 0, 0x000)

    // dup (0x59) -> 0x243
    setRom(0x243, 1, 0, 0x000)

    // iadd (0x60) -> 0x26C
    setRom(0x26C, 1, 0, 0x000)

    // isub (0x64) -> 0x26D
    setRom(0x26D, 1, 0, 0x000)

    // iand (0x7E) -> 0x272
    setRom(0x272, 1, 0, 0x000)

    // ior (0x80) -> 0x273
    setRom(0x273, 1, 0, 0x000)

    // ixor (0x82) -> 0x274
    setRom(0x274, 1, 0, 0x000)

    // sys_noim - unknown bytecode handler -> 0x0EC
    setRom(0x0EC, 1, 0, 0x000)

    rom.toSeq
  }
}

/**
 * Generate testbench wrapper for CocoTB
 */
object JopCoreWithBytecodeTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  // Generate ROM with bytecode handlers
  val bytecodeRom = BytecodeExecutionRom.generate(2048, 12)

  class JopCoreWithBytecodeTb extends Component {
    noIoPrefix()

    // Explicit clock and reset for CocoTB
    val clk = in Bool()
    val reset = in Bool()

    // Flatten the I/O for CocoTB
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val jbc_wr_addr = in UInt(9 bits)  // Word address (jpcWidth - 2 = 9)
    val jbc_wr_data = in Bits(32 bits)
    val jbc_wr_en = in Bool()
    val jpc_wr = in Bool()
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jpc_out = out UInt(12 bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()
    val jpaddr = out UInt(11 bits)
    val opd = out Bits(16 bits)
    val mul_dout = out UInt(32 bits)

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
      // Use bytecode execution ROM
      val core = new JopCoreWithBytecode(
        romInit = Some(bytecodeRom)
      )

      // Connect flattened I/O
      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.jbcWrAddr := jbc_wr_addr
      core.io.jbcWrData := jbc_wr_data
      core.io.jbcWrEn := jbc_wr_en
      core.io.jpcWr := jpc_wr

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jpc_out := core.io.jpcOut
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
      jpaddr := core.io.jpaddr
      opd := core.io.opd
      mul_dout := core.io.mulDout
    }
  }

  config.generateVhdl(new JopCoreWithBytecodeTb)
  println("JopCoreWithBytecodeTb VHDL generated in generated/JopCoreWithBytecodeTb.vhd")
}
