package jop.utils

import scala.io.Source
import scala.util.{Try, Using}

/**
 * JOP File Data Structure
 *
 * Contains parsed data from a .jop file, which includes:
 * - Static fields
 * - Method bytecode
 * - Constant pools
 * - Class structures
 *
 * @param words     All words in the file (as BigInt for SpinalHDL compatibility)
 * @param length    Total length in words (from header)
 * @param comments  Associated comments for each word (for debugging)
 */
case class JopFileData(
  words: Seq[BigInt],
  length: Int,
  comments: Seq[String]
) {
  /** Get word at address */
  def apply(addr: Int): BigInt = {
    if (addr >= 0 && addr < words.length) words(addr)
    else BigInt(0)
  }

  /** Check if address is valid */
  def isValidAddr(addr: Int): Boolean = addr >= 0 && addr < words.length

  /** Get a range of words */
  def getRange(start: Int, length: Int): Seq[BigInt] = {
    (start until (start + length)).map(apply)
  }
}

/**
 * JOP File Loader Utility
 *
 * Loads various JOP-related data files for simulation and testing:
 * - .jop files (Java program data)
 * - mem_rom.dat (microcode ROM)
 * - mem_ram.dat (stack RAM initialization)
 * - jtbl.vhd (jump table - parsed for address mappings)
 *
 * File Formats:
 * - .jop: Comma-separated decimal values with // comments
 * - mem_rom.dat: Space/newline-separated decimal values
 * - mem_ram.dat: Space/newline-separated decimal values
 * - jtbl.vhd: VHDL switch statement (requires special parsing)
 */
object JopFileLoader {

  /**
   * Load a .jop file (Java program)
   *
   * Format:
   *   2173,//	length of the application in words (including this word)
   *   1051,//	pointer to special pointers
   *   0,	//	2: IO_BASEI
   *   ...
   *
   * Note: Values are treated as unsigned 32-bit integers. Negative values
   * (from signed interpretation) are converted to positive BigInt.
   *
   * @param filepath Path to .jop file
   * @return JopFileData with parsed words and comments
   */
  def loadJopFile(filepath: String): JopFileData = {
    val lines = Using(Source.fromFile(filepath))(_.getLines().toSeq).getOrElse(Seq.empty)

    val parsed = lines.flatMap { line =>
      val trimmed = line.trim
      if (trimmed.isEmpty || trimmed.startsWith("//")) {
        Seq.empty
      } else {
        // Extract data part before any // comment
        val parts = trimmed.split("//", 2)
        val dataPart = parts(0).trim
        val comment = if (parts.length > 1) parts(1).trim else ""

        if (dataPart.isEmpty) Seq.empty
        else {
          // Handle multiple comma-separated values on one line (e.g., string character arrays)
          // Lines like: "13, 10, 74, 79, 80, 58, 32, 98, "
          val values = dataPart.split(",").map(_.trim).filter(_.nonEmpty)

          values.flatMap { valuePart =>
            Try {
              val value = valuePart.toLong
              // Convert to unsigned 32-bit representation
              val unsigned = value & 0xFFFFFFFFL
              // Only first value gets the comment
              Some((BigInt(unsigned), if (values.head == valuePart) comment else ""))
            }.getOrElse(None)
          }.toSeq
        }
      }
    }

    val words = parsed.map(_._1)
    val comments = parsed.map(_._2)
    val length = if (words.nonEmpty) words.head.toInt else 0

    JopFileData(words, length, comments)
  }

  /**
   * Load mem_rom.dat (microcode ROM data)
   *
   * Format: One decimal value per line, optionally with leading spaces
   *   256
   *   256
   *   192
   *   ...
   *
   * @param filepath Path to mem_rom.dat
   * @return Sequence of BigInt values for ROM initialization
   */
  def loadMicrocodeRom(filepath: String): Seq[BigInt] = {
    loadDecimalFile(filepath)
  }

  /**
   * Load mem_ram.dat (stack RAM initialization)
   *
   * Format: Same as mem_rom.dat
   *
   * @param filepath Path to mem_ram.dat
   * @return Sequence of BigInt values for RAM initialization
   */
  def loadStackRam(filepath: String): Seq[BigInt] = {
    loadDecimalFile(filepath)
  }

  /**
   * Load a file with space/newline-separated decimal values
   *
   * @param filepath Path to file
   * @return Sequence of BigInt values
   */
  def loadDecimalFile(filepath: String): Seq[BigInt] = {
    Using(Source.fromFile(filepath)) { source =>
      source.getLines()
        .flatMap(_.trim.split("\\s+"))
        .filter(_.nonEmpty)
        .flatMap { s =>
          Try(BigInt(s.toLong)).toOption
        }
        .toSeq
    }.getOrElse(Seq.empty)
  }

  /**
   * Load hex file (MIF-style format)
   *
   * Format:
   *   0000 : 12345678;
   *   0001 : DEADBEEF;
   *   ...
   *
   * @param filepath Path to .mif file
   * @return Sequence of BigInt values
   */
  def loadHexFile(filepath: String): Seq[BigInt] = {
    Using(Source.fromFile(filepath)) { source =>
      source.getLines()
        .flatMap { line =>
          // Parse lines like "0000 : 12345678;"
          val trimmed = line.trim
          if (trimmed.contains(":") && !trimmed.startsWith("--") && !trimmed.startsWith("//")) {
            val parts = trimmed.split(":")
            if (parts.length >= 2) {
              val hexValue = parts(1).trim.stripSuffix(";").trim
              Try(BigInt(hexValue, 16)).toOption
            } else None
          } else None
        }
        .toSeq
    }.getOrElse(Seq.empty)
  }

  /**
   * Load jump table from jtbl.vhd
   *
   * Parses VHDL case statements to extract bytecode -> microcode mappings
   *
   * Format (JOP jtbl.vhd style):
   *   when "10111011" => addr <= "00001111110";	--	007e	new
   *   when "01100000" => addr <= "00110010100";	--	0194	iadd
   *
   * @param filepath Path to jtbl.vhd
   * @return Map of bytecode (0-255) to microcode address
   */
  def loadJumpTable(filepath: String): Map[Int, Int] = {
    Using(Source.fromFile(filepath)) { source =>
      source.getLines()
        .flatMap { line =>
          // Match pattern: when "XXXXXXXX" => addr <= "XXXXXXXXXXX";
          val pattern = """when\s+"([01]+)"\s*=>\s*addr\s*<=\s*"([01]+)"""".r
          pattern.findFirstMatchIn(line).map { m =>
            val bytecode = Integer.parseInt(m.group(1), 2)
            val address = Integer.parseInt(m.group(2), 2)
            (bytecode, address)
          }
        }
        .toMap
    }.getOrElse(Map.empty)
  }

  /**
   * Load jump table and convert to a 256-entry sequence
   *
   * @param filepath Path to jtbl.vhd
   * @param defaultAddr Default address for unmapped bytecodes (usually 0)
   * @return Sequence of 256 BigInt values (one per bytecode)
   */
  def loadJumpTableAsSeq(filepath: String, defaultAddr: Int = 0): Seq[BigInt] = {
    val table = loadJumpTable(filepath)
    (0 until 256).map { bc =>
      BigInt(table.getOrElse(bc, defaultAddr))
    }
  }

  /**
   * Create a simple test ROM with a few bytecodes
   *
   * @param bytecodes Bytecode sequence to load
   * @param padTo     Total size to pad to
   * @return Sequence for ROM initialization
   */
  def createTestBytecodeRom(bytecodes: Seq[Int], padTo: Int = 1024): Seq[BigInt] = {
    bytecodes.map(bc => BigInt(bc & 0xFF)).padTo(padTo, BigInt(0))
  }

  /**
   * Create a word-packed bytecode ROM (4 bytes per word)
   *
   * @param bytecodes Bytecode sequence
   * @param padTo     Total size in words
   * @return Sequence for word-based ROM initialization
   */
  def createPackedBytecodeRom(bytecodes: Seq[Int], padTo: Int = 256): Seq[BigInt] = {
    val padded = bytecodes.map(_ & 0xFF).padTo(padTo * 4, 0)
    padded.grouped(4).map { bytes =>
      val word = (bytes(0) & 0xFF) |
                 ((bytes(1) & 0xFF) << 8) |
                 ((bytes(2) & 0xFF) << 16) |
                 ((bytes(3) & 0xFF) << 24)
      BigInt(word & 0xFFFFFFFFL)
    }.toSeq
  }

  /**
   * Convert a .jop file to memory initialization data
   *
   * @param filepath Path to .jop file
   * @param memSize  Target memory size in words
   * @return Sequence of BigInt values for memory initialization
   */
  def jopFileToMemoryInit(filepath: String, memSize: Int = 65536): Seq[BigInt] = {
    val data = loadJopFile(filepath)
    data.words.padTo(memSize, BigInt(0))
  }
}
