package jop.memory

import spinal.core._

/**
 * JBC RAM Configuration
 *
 * @param jpcWidth Address bits for byte addressing (default: 11 = 2KB)
 */
case class JbcRamConfig(
  jpcWidth: Int = 11
) {
  require(jpcWidth >= 10 && jpcWidth <= 12, "JPC width must be 10-12 bits")

  /** RAM depth in bytes */
  def depth: Int = 1 << jpcWidth

  /** RAM depth in 32-bit words */
  def wordDepth: Int = depth / 4

  /** Word address width */
  def wordAddrWidth: Int = jpcWidth - 2
}

/**
 * JBC RAM - Java Bytecode Cache RAM
 *
 * Dual-port RAM with different port widths:
 * - Write port: 32-bit word-addressed (for method loading from memory)
 * - Read port: 8-bit byte-addressed (for bytecode fetch)
 *
 * The RAM is organized as 32-bit words internally. Byte selection
 * is performed using a mux on the read port.
 *
 * Little-endian byte ordering:
 * - Word address 0 contains bytes at JPC 0, 1, 2, 3
 * - Byte 0 is at bits 7:0, byte 3 is at bits 31:24
 *
 * @param config JBC RAM configuration
 * @param init Optional initialization data (bytes)
 */
case class JbcRam(
  config: JbcRamConfig = JbcRamConfig(),
  init: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Write port (32-bit, word-addressed)
    val wrAddr = in UInt(config.wordAddrWidth bits)
    val wrData = in Bits(32 bits)
    val wrEn   = in Bool()

    // Read port (8-bit, byte-addressed)
    val rdAddr = in UInt(config.jpcWidth bits)
    val rdData = out Bits(8 bits)
  }

  // ==========================================================================
  // Internal RAM (32-bit words)
  // ==========================================================================

  val ram = Mem(Bits(32 bits), config.wordDepth)

  // Initialize RAM (optional)
  init match {
    case Some(data) =>
      // Pack bytes into words (little-endian)
      val wordData = data.grouped(4).map { bytes =>
        val padded = bytes.padTo(4, BigInt(0))
        padded(0) | (padded(1) << 8) | (padded(2) << 16) | (padded(3) << 24)
      }.toSeq
      ram.init(wordData.padTo(config.wordDepth, BigInt(0)).map(v => B(v, 32 bits)))
    case None =>
      ram.init(Seq.fill(config.wordDepth)(B(0, 32 bits)))
  }

  // ==========================================================================
  // Write Port (Synchronous)
  // ==========================================================================

  ram.write(
    address = io.wrAddr,
    data = io.wrData,
    enable = io.wrEn
  )

  // ==========================================================================
  // Read Port (Synchronous Address, Combinational Byte Mux)
  // ==========================================================================

  // Word address from byte address
  val wordAddr = io.rdAddr(config.jpcWidth - 1 downto 2)

  // Byte select (registered to align with RAM read)
  val byteSelect = RegNext(io.rdAddr(1 downto 0))

  // Synchronous word read
  val wordData = ram.readSync(wordAddr, enable = True)

  // Byte selection mux (little-endian)
  io.rdData := byteSelect.mux(
    0 -> wordData(7 downto 0),
    1 -> wordData(15 downto 8),
    2 -> wordData(23 downto 16),
    3 -> wordData(31 downto 24)
  )
}

/**
 * JbcRam Companion Object
 */
object JbcRam {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "core/spinalhdl/generated"
    ).generate(JbcRam())
  }
}
