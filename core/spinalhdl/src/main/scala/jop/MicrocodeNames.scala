package jop

// MicrocodeNames.scala
// 10-bit microcode instruction -> mnemonic string
//
// Note: This is for elaboration-time utilities, debug, simulation, disassembly, etc.
// Strings/Scala Maps are not synthesizable into RTL.


object MicrocodeNamesTest extends App {
  println(MicrocodeNames.disasm(0x100))
  println(MicrocodeNames.disasm(0x2FF))
  println(MicrocodeNames.disasm(0x3FF))
  println(MicrocodeNames.disasm(0x1C0))
  println(MicrocodeNames.disasm(0x1DF))
  println(MicrocodeNames.disasm(0x1EF))
  println(MicrocodeNames.disasm(0x1FF))
}

object MicrocodeNames {
  final val Width = 10
  final val Mask  = (1 << Width) - 1 // 0x3FF

  /** Flat lookup table: instruction word (0..1023) -> mnemonic name. */
  val microcodeNames: Map[Int, String] = {
    val m = scala.collection.mutable.LinkedHashMap.empty[Int, String]
    def add(code: Int, name: String): Unit = m += ((code & Mask) -> name)

    // ---- Fixed (exact) opcodes (10-bit) ----
    add(0x000, "pop")
    add(0x001, "and")
    add(0x002, "or")
    add(0x003, "xor")
    add(0x004, "add")
    add(0x005, "sub")

    add(0x014, "st")
    add(0x015, "stmi")

    add(0x018, "stvp")
    add(0x019, "stjpc")
    add(0x01A, "star")
    add(0x01B, "stsp")

    add(0x01C, "ushr")
    add(0x01D, "shl")
    add(0x01E, "shr")

    add(0x040, "stmul")
    add(0x041, "stmwa")
    add(0x042, "stmra")
    add(0x043, "stmwd")
    add(0x044, "stald")
    add(0x045, "stast")
    add(0x046, "stgf")
    add(0x047, "stpf")
    add(0x048, "stcp")
    add(0x049, "stbcrd")
    add(0x04A, "stidx")
    add(0x04B, "stps")
    add(0x04C, "stmrac")
    add(0x04D, "stmraf")
    add(0x04E, "stmwdf")
    add(0x04F, "stpfr")

    add(0x0E0, "ldmrd")
    add(0x0E1, "ldmul")
    add(0x0E2, "ldbcstart")
    add(0x0EC, "ld")
    add(0x0ED, "ldmi")

    add(0x0F0, "ldsp")
    add(0x0F1, "ldvp")
    add(0x0F2, "ldjpc")
    add(0x0F4, "ld_opd_8u")
    add(0x0F5, "ld_opd_8s")
    add(0x0F6, "ld_opd_16u")
    add(0x0F7, "ld_opd_16s")
    add(0x0F8, "dup")

    add(0x100, "nop")
    add(0x101, "wait")
    add(0x102, "jbr")

    add(0x110, "stgs")
    add(0x111, "cinval")
    add(0x112, "atmstart")
    add(0x113, "atmend")

    // ---- Patterned families ----
    // st[n] : 00000100nn  (n = 0..3) => 0x10..0x13
    for (n <- 0 until 4) add(0x010 | n, s"st$n")

    // stm[n] : 00001nnnnn (n = 0..31) => 0x20..0x3F
    for (n <- 0 until 32) add(0x020 | n, s"stm $n")

    // ldm[n] : 00101nnnnn (n = 0..31) => 0xA0..0xBF
    for (n <- 0 until 32) add(0x0A0 | n, s"ldm $n")

    // ldi[n] : 00110nnnnn (n = 0..31) => 0xC0..0xDF
    for (n <- 0 until 32) add(0x0C0 | n, s"ldi $n")

    // ld[n] : 00111010nn (n = 0..3) => 0xE8..0xEB
    for (n <- 0 until 4) add(0x0E8 | n, s"ld$n")

    // bz   : 0110nnnnnn (imm6) => 0x180..0x1BF
    for (imm <- 0 until 64) add(0x180 | imm, "bz")

    // bnz  : 0111nnnnnn (imm6) => 0x1C0..0x1FF
    for (imm <- 0 until 64) add(0x1C0 | imm, "bnz")

    // jmp  : 1nnnnnnnnn (imm9) => 0x200..0x3FF
    for (imm <- 0 until 512) add(0x200 | imm, "jmp")

    m.toMap
  }

  def nameOf(instr: Int): String =
    microcodeNames.getOrElse(instr & Mask, f"UNKNOWN(0x${instr & Mask}%03X)")

  private def sext(value: Int, bits: Int): Int = {
    // Sign-extend 'bits' LSBs of value to 32-bit signed Int
    val shift = 32 - bits
    (value << shift) >> shift
  }

  /** Disassemble a 10-bit micro-instruction, showing signed branch/jump offsets. */
  def disasm(instr: Int): String = {
    val x = instr & Mask

    // bz: 0110iiiiii => 0x180..0x1BF
    if ((x & 0x3C0) == 0x180) {
      val imm6  = x & 0x3F
      val off   = sext(imm6, 6)
      // Example: "bz -3 (imm=0x3D)"
      s"bz $off"
    }
    // bnz: 0111iiiiii => 0x1C0..0x1FF
    else if ((x & 0x3C0) == 0x1C0) {
      val imm6  = x & 0x3F
      val off   = sext(imm6, 6)
      s"bnz $off"
    }
    // jmp: 1iiiiiiiii => 0x200..0x3FF
    else if ((x & 0x200) != 0) {
      val imm9  = x & 0x1FF
      val off   = sext(imm9, 9)
      // Example: "jmp +12 (imm=0x00C)"
      val sign = if (off >= 0) "+" else ""
      s"jmp $sign$off"
    }
    else {
      nameOf(x)
    }
  }
}

