// DSP variant stubs — alias to base JumpTableData until DSP microcode is generated
package jop

object DspJumpTableData extends JumpTableSource {
  def entries: Seq[BigInt] = JumpTableData.entries
  def sysNoimAddr: Int = JumpTableData.sysNoimAddr
  def sysIntAddr: Int = JumpTableData.sysIntAddr
  def sysExcAddr: Int = JumpTableData.sysExcAddr
  def altEntries: Map[Int, Int] = Map.empty
}

object SerialDspJumpTableData extends JumpTableSource {
  def entries: Seq[BigInt] = SerialJumpTableData.entries
  def sysNoimAddr: Int = SerialJumpTableData.sysNoimAddr
  def sysIntAddr: Int = SerialJumpTableData.sysIntAddr
  def sysExcAddr: Int = SerialJumpTableData.sysExcAddr
  def altEntries: Map[Int, Int] = Map.empty
}
