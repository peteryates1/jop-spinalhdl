package jop.pipeline

import spinal.core._

/**
 * Bytecode Fetch Stage Configuration
 *
 * @param jpcWidth Address bits of Java bytecode PC (default: 11 bits = 2KB)
 * @param pcWidth  Address bits of microcode ROM (default: 11 bits)
 */
case class BytecodeFetchConfig(
  jpcWidth: Int = 11,
  pcWidth: Int = 11
) {
  require(jpcWidth > 0, "JPC width must be positive")
  require(pcWidth > 0, "PC width must be positive")

  /** JBC RAM depth (bytes) */
  def jbcDepth: Int = 1 << jpcWidth

  /** Operand width */
  def opdWidth: Int = 16
}

/**
 * Java Bytecode Fetch Stage
 *
 * Implemented features (Phase A + Phase B.1):
 * - JPC increment on jfetch
 * - JBC RAM read (synchronous, 2KB)
 * - JumpTable integration for bytecode → microcode translation
 * - JPC write for method calls (jpc_wr)
 * - Operand accumulation (jopdfetch) - 16-bit shift register
 *
 * Features to add incrementally (Phase B.2+):
 * - Branch logic (15 branch types)
 * - Interrupt/exception handling
 *
 * @param config Configuration
 * @param jbcInit Optional JBC RAM initialization
 */
case class BytecodeFetchStage(
  config: BytecodeFetchConfig = BytecodeFetchConfig(),
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Control inputs (Phase A - implemented)
    val jpc_wr    = in Bool()                         // Write jpc (method call)
    val din       = in Bits(32 bits)                  // Stack TOS
    val jfetch    = in Bool()                         // Fetch bytecode

    // Operand fetch (Phase B.1 - implemented)
    val jopdfetch = in Bool()                         // Fetch operand (triggers shift)

    // ==========================================================================
    // DEFERRED FEATURES (Phase B.2+)
    // These inputs are declared but not yet implemented:
    // - jbr: Branch logic and condition evaluation (TODO Phase B.2)
    // - zf/nf/eq/lt: Branch condition flags (TODO Phase B.2)
    // ==========================================================================
    val jbr       = in Bool()                         // TODO Phase B.2: Branch enable

    // Branch condition flags (TODO Phase B: used for 15 branch types)
    val zf = in Bool()                                // Zero flag
    val nf = in Bool()                                // Negative flag
    val eq = in Bool()                                // Equal flag
    val lt = in Bool()                                // Less-than flag

    // Outputs
    val jpaddr   = out UInt(config.pcWidth bits)      // Microcode address from jump table
    val opd      = out Bits(config.opdWidth bits)     // Operand (16-bit, accumulated via jopdfetch)
    val jpc_out  = out UInt(config.jpcWidth + 1 bits) // Current jpc
  }

  // ==========================================================================
  // JBC RAM (Java Bytecode Storage)
  // ==========================================================================

  val jbcRam = Mem(Bits(8 bits), config.jbcDepth)

  // Initialize ROM
  jbcInit match {
    case Some(data) =>
      jbcRam.init(data.map(v => B(v, 8 bits)))
    case None =>
      jbcRam.init(Seq.fill(config.jbcDepth)(B(0x00, 8 bits)))
  }

  // ==========================================================================
  // Registers
  // ==========================================================================

  val jpc = Reg(UInt(config.jpcWidth + 1 bits)) init(0)  // Java PC
  val jopd = Reg(Bits(config.opdWidth bits)) init(0)     // Operand accumulator

  // ==========================================================================
  // JBC Address and Read
  // ==========================================================================

  // JBC address calculation
  // Note: jpc is (jpcWidth + 1) bits (12 bits) to detect overflow,
  //       jbcAddr is jpcWidth bits (11 bits) for RAM addressing (2KB = 2048 bytes)
  //       The upper bit is intentionally truncated via slicing.
  //
  // Logic: On jfetch or jopdfetch, read next address (jpc + 1)
  //        Otherwise, read current address (jpc)
  val jbcAddr = UInt(config.jpcWidth bits)
  when(io.jfetch || io.jopdfetch) {
    jbcAddr := (jpc + 1)(config.jpcWidth - 1 downto 0)  // Truncate to RAM address width
  }.otherwise {
    jbcAddr := jpc(config.jpcWidth - 1 downto 0)
  }

  // Synchronous RAM read
  val jbcData = jbcRam.readSync(jbcAddr, enable = True)

  // ==========================================================================
  // JPC Update Logic
  // ==========================================================================

  // Priority: jpc_wr > jfetch/jopdfetch > hold (register holds by default)
  when(io.jpc_wr) {
    // Method call: load from stack
    jpc := io.din(config.jpcWidth downto 0).asUInt
  }.elsewhen(io.jfetch || io.jopdfetch) {
    // Increment
    jpc := jpc + 1
  }
  // No .otherwise needed - register holds its value by default

  io.jpc_out := jpc

  // ==========================================================================
  // Operand Accumulation Logic
  // ==========================================================================

  // Low byte always gets updated with current JBC RAM output
  jopd(7 downto 0) := jbcData

  // When jopdfetch is asserted, shift low byte to high byte
  // This allows accumulating multi-byte operands:
  //   Cycle 1: jfetch=1, bytecode 0x12 → jopd = 0x00_12
  //   Cycle 2: jopdfetch=1, operand 0x34 → jopd = 0x12_34 (shift + load)
  when(io.jopdfetch) {
    jopd(15 downto 8) := jopd(7 downto 0)
  }

  io.opd := jopd

  // ==========================================================================
  // Jump Table Integration
  // ==========================================================================

  val jumpTable = JumpTable(JumpTableConfig(pcWidth = config.pcWidth))
  jumpTable.io.bytecode := jbcData
  io.jpaddr := jumpTable.io.jpaddr
}

/**
 * BytecodeFetchStage Companion Object
 */
object BytecodeFetchStage {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "core/spinalhdl/generated"
    ).generate(BytecodeFetchStage())
  }
}
