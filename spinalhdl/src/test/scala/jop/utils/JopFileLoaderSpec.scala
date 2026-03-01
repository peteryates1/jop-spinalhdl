package jop.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for JopFileLoader utility
 */
class JopFileLoaderSpec extends AnyFlatSpec with Matchers {

  // Path to test files
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val jtblPath = "asm/generated/jtbl.vhd"
  val memRomPath = "asm/generated/mem_rom.dat"
  val memRamPath = "asm/generated/mem_ram.dat"

  "JopFileLoader" should "load a .jop file" in {
    val data = JopFileLoader.loadJopFile(jopFilePath)

    // First word is the length
    data.length should be > 0
    data.words.length should be > 0

    // Check that length matches first word
    data.length shouldEqual data.words.head.toInt
  }

  it should "correctly parse .jop file values" in {
    val data = JopFileLoader.loadJopFile(jopFilePath)

    // First value is the length — should be a positive integer
    data.words.head.toInt should be > 0

    // Second value is the pointer to special pointers — should be positive
    data.words(1).toInt should be > 0
  }

  it should "load mem_rom.dat (microcode ROM)" in {
    val rom = JopFileLoader.loadMicrocodeRom(memRomPath)

    rom.length should be > 0
    // First few values should be valid microcode words
    rom.head.toInt should be >= 0
    rom(1).toInt should be >= 0
  }

  it should "load mem_ram.dat (stack RAM)" in {
    val ram = JopFileLoader.loadStackRam(memRamPath)

    ram.length should be > 0
  }

  it should "parse jump table from jtbl.vhd" in {
    val table = JopFileLoader.loadJumpTable(jtblPath)

    // Should have some entries
    table.size should be > 0

    // NOP (0x00) should map to some address
    table.contains(0x00) shouldBe true

    // Common bytecodes should be present
    table.contains(0x60) shouldBe true // iadd
    table.contains(0x64) shouldBe true // isub
  }

  it should "convert jump table to sequence" in {
    val seq = JopFileLoader.loadJumpTableAsSeq(jtblPath)

    // Should have exactly 256 entries
    seq.length shouldEqual 256

    // Default should be 0 for unmapped
    seq.forall(_ >= 0) shouldBe true
  }

  it should "create test bytecode ROM" in {
    val bytecodes = Seq(0x1A, 0x1B, 0x60, 0x3D)  // iload_0, iload_1, iadd, istore_2
    val rom = JopFileLoader.createTestBytecodeRom(bytecodes, padTo = 16)

    rom.length shouldEqual 16
    rom(0) shouldEqual BigInt(0x1A)
    rom(1) shouldEqual BigInt(0x1B)
    rom(2) shouldEqual BigInt(0x60)
    rom(3) shouldEqual BigInt(0x3D)
    rom(4) shouldEqual BigInt(0)  // Padding
  }

  it should "create packed bytecode ROM" in {
    val bytecodes = Seq(0x1A, 0x1B, 0x60, 0x3D)  // 4 bytes = 1 word
    val rom = JopFileLoader.createPackedBytecodeRom(bytecodes, padTo = 2)

    rom.length shouldEqual 2
    // Word 0: 0x3D601B1A (little-endian: 1A, 1B, 60, 3D)
    val expected = (0x1A) | (0x1B << 8) | (0x60 << 16) | (0x3D << 24)
    rom(0) shouldEqual BigInt(expected & 0xFFFFFFFFL)
  }

  it should "convert .jop file to memory initialization" in {
    val jopData = JopFileLoader.loadJopFile(jopFilePath)
    val memSize = jopData.words.length.max(4096)
    val memInit = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize = memSize)

    memInit.length shouldEqual memSize
    memInit.head.toInt should be > 0  // Length
  }
}
