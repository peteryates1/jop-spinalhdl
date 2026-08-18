package jop.pipeline

import spinal.core._
import jop._

/**
 * Jump Table Init Data - microcode-layout-specific addresses and entries.
 *
 * Holds the 256-entry jump table plus special handler addresses.
 * All ROMs are supersets containing ALL hardware handlers.
 * Use resolveJumpTable() in JopCoreConfig to patch unwanted bytecodes to sys_noim.
 */
case class JumpTableInitData(
  entries:     Seq[BigInt],
  sysNoimAddr: Int,
  sysIntAddr:  Int,
  sysExcAddr:  Int,
  altEntries:  Map[Int, Int] = Map.empty,
  dspAltEntries: Map[Int, Int] = Map.empty
) {
  /** Patch specific bytecodes to sys_noim, making their HW handlers unreachable (dead code in ROM). */
  def disable(bytecodes: Int*): JumpTableInitData =
    copy(entries = entries.zipWithIndex.map { case (addr, i) =>
      if (bytecodes.contains(i)) BigInt(sysNoimAddr) else addr
    })

  /**
   * Patch a bytecode to its alternate (software) handler address.
   *
   * Throws if the ROM has no `<name>_sw` handler, rather than leaving the entry
   * alone. Failing open here is not a safe default: the superset ROM's default
   * entry for these opcodes is the `_hw` handler, so quietly keeping it turns
   * "implement this in microcode" into "dispatch to a compute unit" — and if the
   * configuration does not instantiate that unit, the result is wrong arithmetic
   * at run time with no elaboration error and no failing build.
   *
   * That is not hypothetical. Only 13 of the 32 configurable bytecodes have a
   * `_sw` alternate (all 8 long ops, `imul`, and `fadd`/`fsub`/`fmul`/`fdiv`);
   * the other 19 reach only the Java trap. See item 18 in
   * docs/current-status.md and the per-unit tables in
   * docs/architecture/compute-unit-design.md.
   *
   * @param name bytecode mnemonic, for the error message only
   */
  def useAlt(bytecode: Int, name: String): JumpTableInitData =
    altEntries.get(bytecode) match {
      case Some(altAddr) => copy(entries = entries.updated(bytecode, BigInt(altAddr)))
      case None =>
        val available = altEntries.keys.toSeq.sorted.map(op => f"0x$op%02X").mkString(", ")
        throw new IllegalArgumentException(
          f"Bytecode '$name' (0x$bytecode%02X) is configured as Microcode, but this " +
          f"microcode ROM has no '${name}_sw' handler.\n" +
          "  The superset ROM's default entry for this opcode is the '_hw' handler, so " +
          "accepting this would silently dispatch to a compute unit instead of to " +
          "microcode — and produce wrong results rather than an error if that unit is " +
          "not instantiated.\n" +
          f"  Fix: set '$name' to \"java\" or \"hw\", or add a '${name}_sw' handler to " +
          "asm/src/jvm.asm.\n" +
          f"  Opcodes with a _sw alternate in this ROM: $available")
    }

  /**
   * Patch a bytecode to its DSP-accelerated handler address.
   *
   * Strict for the same reason as [[useAlt]]: silently keeping the default would
   * mean `useDspMul = true` quietly did nothing, which is a performance claim
   * the build would then not be delivering.
   *
   * @param name bytecode mnemonic, for the error message only
   */
  def useDspAlt(bytecode: Int, name: String): JumpTableInitData =
    dspAltEntries.get(bytecode) match {
      case Some(dspAddr) => copy(entries = entries.updated(bytecode, BigInt(dspAddr)))
      case None =>
        throw new IllegalArgumentException(
          f"useDspMul is set, but this microcode ROM has no '${name}_dsp' handler " +
          f"for '$name' (0x$bytecode%02X). Accepting this would leave useDspMul " +
          "silently ineffective. Fix: set useDspMul = false, or add a " +
          f"'${name}_dsp' handler to asm/src/jvm.asm.")
    }
}

object JumpTableInitData {
  /** Create from any Jopa-generated jump table object */
  private def from(src: JumpTableSource): JumpTableInitData =
    JumpTableInitData(src.entries, src.sysNoimAddr, src.sysIntAddr, src.sysExcAddr, src.altEntries, src.dspAltEntries)

  /** SIMULATION superset ROM (all HW handlers: IntegerCU + FloatCU) */
  def simulation: JumpTableInitData = from(JumpTableData)

  /** SERIAL-boot superset ROM (all HW handlers: IntegerCU + FloatCU) */
  def serial: JumpTableInitData = from(jop.SerialJumpTableData)

  /** FLASH-boot superset ROM */
  def flash: JumpTableInitData = from(FlashJumpTableData)

}

/**
 * Jump Table Configuration
 *
 * Configures the jump table parameters for bytecode-to-microcode address translation.
 *
 * @param pcWidth  Address bits of microcode ROM (default: 12 bits = 4K instructions)
 * @param initData Jump table init data (default: simulation microcode)
 */
case class JumpTableConfig(
  pcWidth:  Int              = 12,
  initData: JumpTableInitData = JumpTableInitData.simulation
) {
  require(pcWidth > 0, "PC width must be positive")
  require(pcWidth <= 16, "PC width too large (max 16 bits)")

  /** Number of bytecode entries (always 256 for Java bytecode) */
  def entries: Int = 256
}

/**
 * Jump Table - Java Bytecode to Microcode Address Translation
 *
 * Translates 8-bit Java bytecode opcodes to microcode ROM addresses.
 * Uses ROM-based lookup with data generated by Jopa assembler.
 *
 * @param config Jump table configuration
 */
case class JumpTable(
  config: JumpTableConfig = JumpTableConfig()
) extends Component {

  val io = new Bundle {
    val bytecode = in Bits(8 bits)              // Java bytecode opcode (0x00-0xFF)
    val jpaddr   = out UInt(config.pcWidth bits) // Microcode ROM address

    // Interrupt/exception priority muxing (Phase E)
    val intPend = in Bool()                     // Interrupt pending
    val excPend = in Bool()                     // Exception pending
  }

  // ROM stores microcode addresses (256 entries, one per bytecode)
  val rom = Mem(UInt(config.pcWidth bits), config.entries)

  // Initialize from provided jump table data
  rom.init(config.initData.entries.map(addr => U(addr.toInt, config.pcWidth bits)))

  // Asynchronous ROM read (combinational, 0-cycle latency)
  val normalAddr = rom.readAsync(io.bytecode.asUInt, writeFirst)

  // Priority muxing: Exception > Interrupt > Normal bytecode
  when(io.excPend) {
    io.jpaddr := U(config.initData.sysExcAddr, config.pcWidth bits)
  }.elsewhen(io.intPend) {
    io.jpaddr := U(config.initData.sysIntAddr, config.pcWidth bits)
  }.otherwise {
    io.jpaddr := normalAddr
  }

  // Verification helpers (disabled in synthesis)
  if (GenerationFlags.simulation) {
    assert(
      assertion = io.bytecode.asUInt < config.entries,
      message = "Invalid bytecode out of range",
      severity = WARNING
    )
    assert(
      assertion = io.jpaddr < (1 << config.pcWidth),
      message = "Jump address out of microcode ROM range",
      severity = WARNING
    )
  }
}

object JumpTable {

  object SpecialAddr {
    val SYS_NOIM = JumpTableData.sysNoimAddr
    val SYS_INT  = JumpTableData.sysIntAddr
    val SYS_EXC  = JumpTableData.sysExcAddr
  }

  def getAddress(bytecode: Int): Int = {
    require(bytecode >= 0 && bytecode < 256, "Bytecode out of range")
    JumpTableData.entries(bytecode).toInt
  }

  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "spinalhdl/generated"
    ).generate(JumpTable())
  }
}
