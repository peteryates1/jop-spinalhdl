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
 * Java Bytecode Fetch Stage (Simplified Version)
 *
 * Minimal implementation focusing on:
 * - JPC increment on jfetch
 * - JBC RAM read
 * - JumpTable integration for bytecode → microcode translation
 *
 * Features to add incrementally:
 * - Operand accumulation (jopdfetch)
 * - Branch logic
 * - Method call (jpc_wr)
 *
 * @param config Configuration
 * @param jbcInit Optional JBC RAM initialization
 */
case class BytecodeFetchStage(
  config: BytecodeFetchConfig = BytecodeFetchConfig(),
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Control inputs
    val jpc_wr    = in Bool()                         // Write jpc (method call)
    val din       = in Bits(32 bits)                  // Stack TOS
    val jfetch    = in Bool()                         // Fetch bytecode
    val jopdfetch = in Bool()                         // Fetch operand (not implemented yet)
    val jbr       = in Bool()                         // Branch enable (not implemented yet)

    // Branch conditions (not used yet)
    val zf = in Bool()
    val nf = in Bool()
    val eq = in Bool()
    val lt = in Bool()

    // Outputs
    val jpaddr   = out UInt(config.pcWidth bits)      // Microcode address from jump table
    val opd      = out Bits(config.opdWidth bits)     // Operand (not implemented yet)
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
  // Java PC Register
  // ==========================================================================

  val jpc = Reg(UInt(config.jpcWidth + 1 bits)) init(0)

  // ==========================================================================
  // JBC Address and Read
  // ==========================================================================

  // Simple logic: JBC address = jpc (no branches yet)
  // On jfetch or jopdfetch, read next address (jpc + 1)
  // Otherwise, read current address (jpc)
  val jbcAddr = UInt(config.jpcWidth bits)
  when(io.jfetch || io.jopdfetch) {
    jbcAddr := (jpc + 1)(config.jpcWidth - 1 downto 0)
  }.otherwise {
    jbcAddr := jpc(config.jpcWidth - 1 downto 0)
  }

  // Synchronous RAM read
  val jbcData = jbcRam.readSync(jbcAddr, enable = True)

  // ==========================================================================
  // JPC Update Logic
  // ==========================================================================

  // Simple priority: jpc_wr > jfetch > hold
  when(io.jpc_wr) {
    // Method call: load from stack
    jpc := io.din(config.jpcWidth downto 0).asUInt
  }.elsewhen(io.jfetch || io.jopdfetch) {
    // Increment
    jpc := jpc + 1
  }.otherwise {
    // Hold
    jpc := jpc
  }

  io.jpc_out := jpc

  // ==========================================================================
  // Jump Table Integration
  // ==========================================================================

  val jumpTable = JumpTable(JumpTableConfig(pcWidth = config.pcWidth))
  jumpTable.io.bytecode := jbcData
  io.jpaddr := jumpTable.io.jpaddr

  // ==========================================================================
  // Operand Output (Not Implemented Yet)
  // ==========================================================================

  io.opd := 0
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
