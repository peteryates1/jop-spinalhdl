package jop.utils

import scala.io.Source
import scala.util.Using

/**
 * Utilities for loading hex trace files at Verilog generation time.
 *
 * Used by Ddr3TraceReplayerTop to initialize BRAMs with:
 *   - gc_mem_init.hex: one 32-bit hex word per line (memory image)
 *   - gc_bmb_trace.hex: two 32-bit hex words per line (BMB trace entries)
 */
object TraceFileLoader {

  /**
   * Load memory init hex file.
   *
   * Format: one 32-bit hex word per line (no 0x prefix)
   *   00000897
   *   0000041b
   *   ...
   *
   * @return Seq of 32-bit BigInt values
   */
  def loadInitHex(path: String): Seq[BigInt] = {
    Using(Source.fromFile(path)) { source =>
      source.getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(line => BigInt(line, 16))
        .toSeq
    }.getOrElse {
      throw new RuntimeException(s"Failed to load init hex file: $path")
    }
  }

  /**
   * Load BMB trace hex file.
   *
   * Format: two 32-bit hex words per line, space-separated (high word first)
   *   XXXXXXXX YYYYYYYY
   *
   * Returns a flat Seq of 32-bit words: [entry0_word0, entry0_word1, entry1_word0, ...]
   * where word0 is the low word (opcode+addr) and word1 is the high word (data),
   * stored in trace BRAM order: traceMem[2*N] = word0, traceMem[2*N+1] = word1.
   *
   * @return Flat Seq of 32-bit BigInt values (2 per trace entry)
   */
  def loadTraceHex(path: String): Seq[BigInt] = {
    Using(Source.fromFile(path)) { source =>
      source.getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap { line =>
          val parts = line.split("\\s+")
          if (parts.length >= 2) {
            val word1 = BigInt(parts(0), 16)  // high word (data)
            val word0 = BigInt(parts(1), 16)  // low word (opcode+addr)
            Seq(word0, word1)  // stored as traceMem[2*N], traceMem[2*N+1]
          } else {
            throw new RuntimeException(s"Malformed trace line: $line")
          }
        }
        .toSeq
    }.getOrElse {
      throw new RuntimeException(s"Failed to load trace hex file: $path")
    }
  }
}
