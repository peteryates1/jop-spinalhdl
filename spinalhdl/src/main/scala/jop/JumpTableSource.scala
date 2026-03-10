package jop

/** Trait implemented by all Jopa-generated jump table objects */
trait JumpTableSource {
  def entries: Seq[BigInt]
  def sysNoimAddr: Int
  def sysIntAddr: Int
  def sysExcAddr: Int
  /** Alternate handler addresses for bytecodes with both HW and SW handlers.
    * Maps bytecode opcode -> microcode address of the software (Microcode) handler.
    * E.g., 0x68 -> imul_sw address. Used by resolveJumpTable when imul: Microcode. */
  def altEntries: Map[Int, Int]
  /** DSP-accelerated handler addresses for bytecodes with DSP alternatives.
    * Maps bytecode opcode -> microcode address of the DSP handler.
    * E.g., 0x68 -> imul_dsp address. Used by resolveJumpTable when useDspMul=true. */
  def dspAltEntries: Map[Int, Int] = Map.empty
}
