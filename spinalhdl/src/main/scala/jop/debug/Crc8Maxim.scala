package jop.debug

import spinal.core._

/**
 * CRC-8/MAXIM (Dallas/Maxim 1-Wire) calculator.
 *
 * Polynomial: x^8 + x^5 + x^4 + 1 (0x31)
 * Init: 0x00, RefIn: true, RefOut: true, XorOut: 0x00
 * Reflected polynomial: 0x8C
 *
 * Processes one byte per clock when enable is asserted.
 * Assert clear to reset the CRC register to 0x00.
 */
case class Crc8Maxim() extends Component {
  val io = new Bundle {
    val clear  = in Bool()
    val enable = in Bool()
    val data   = in Bits(8 bits)
    val crc    = out Bits(8 bits)
  }

  val crcReg = Reg(Bits(8 bits)) init(0)

  when(io.clear) {
    crcReg := 0
  }.elsewhen(io.enable) {
    crcReg := Crc8MaximCalc(crcReg, io.data)
  }

  io.crc := crcReg
}

/**
 * Combinational CRC-8/MAXIM for a single byte.
 * Takes current CRC and a data byte, returns updated CRC.
 *
 * Uses reflected algorithm (LSB-first) with polynomial 0x8C:
 *   for each bit i (0..7):
 *     xorBit = crc[0] ^ data[i]
 *     crc >>= 1
 *     if xorBit: crc ^= 0x8C
 *
 * Reflected poly 0x8C = 10001100:
 *   After shift right: new[7] = xorBit, new[3] ^= xorBit, new[2] ^= xorBit
 */
object Crc8MaximCalc {
  def apply(crc: Bits, data: Bits): Bits = {
    var c = crc
    for (i <- 0 until 8) {
      val xorBit = c(0) ^ data(i)
      // Shift right by 1, XOR reflected polynomial 0x8C at bit positions 7, 3, 2
      c = xorBit ## c(7) ## c(6) ## c(5) ## (c(4) ^ xorBit) ## (c(3) ^ xorBit) ## c(2) ## c(1)
    }
    c
  }
}
