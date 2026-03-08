package jop

/** Trait implemented by all Jopa-generated jump table objects */
trait JumpTableSource {
  def entries: Seq[BigInt]
  def sysNoimAddr: Int
  def sysIntAddr: Int
  def sysExcAddr: Int
}
