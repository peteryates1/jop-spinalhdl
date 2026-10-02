package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import java.io.File
import scala.io.Source

/**
 * NO `stjpc` IS FOLLOWED BY A BYTECODE DISPATCH OR OPERAND FETCH — status item
 * 160.
 *
 * `stjpc`'s JPC write lands in the cycle after its decode, and since
 * 2026-10-02 it lands even when the NEXT instruction has frozen the pipeline in
 * that cycle (a stack-cache rotation, or a word served from the spill region):
 * gated by the freeze it was dropped, then re-applied with the wrong A. Landing
 * during a freeze moves `jpc`, and so the RAM read and `jpaddr`, while the
 * next instruction waits in decode.
 *
 * That is invisible ONLY because the waiting instruction is never a dispatch
 * (`nxt`, which would take `jpaddr` on release) or an operand fetch (`opd`,
 * which would increment the NEW jpc where an unfrozen pipeline would have let
 * the write win). Every `stjpc` in jvm.asm is followed by `nop` or by
 * `ldm jjhp` (sys_int). This pins that, in every assembled variant, so a
 * microcode edit cannot quietly make the freeze visible.
 * `BytecodeFetchStageFormal` allows the jpc write during a stall on exactly
 * this premise.
 */
class StjpcFollowerTest extends AnyFunSuite {

  private val Stjpc = 0x019
  private val InstrMask = (1 << 10) - 1
  private val OpdBit = 1 << 10   // FetchStage: jopdfetch = romData(iWidth)
  private val NxtBit = 1 << 11   // FetchStage: jfetch    = romData(iWidth + 1)

  private def roms: Seq[File] = {
    val root = new File("build/microcode")
    Option(root.listFiles).toSeq.flatten.filter(_.isDirectory)
      .map(new File(_, "mem_rom.dat")).filter(_.exists).sortBy(_.getPath)
  }

  private def load(f: File): IndexedSeq[Int] = {
    val src = Source.fromFile(f)
    try src.getLines().flatMap(_.trim.split("\\s+")).filter(_.nonEmpty).map(_.toInt).toIndexedSeq
    finally src.close()
  }

  test("every assembled microcode variant: the instruction after stjpc neither dispatches nor fetches an operand") {
    assert(roms.nonEmpty, "no build/microcode/*/mem_rom.dat -- assemble the microcode first (make -C asm all)")
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    var seen = 0
    for (f <- roms) {
      val words = load(f)
      for (k <- words.indices if (words(k) & InstrMask) == Stjpc) {
        seen += 1
        if (k + 1 < words.length) {
          val next = words(k + 1)
          if ((next & (NxtBit | OpdBit)) != 0)
            failures += f"${f.getParentFile.getName}: stjpc at 0x$k%03x is followed by 0x${next & InstrMask}%03x " +
              s"with ${if ((next & NxtBit) != 0) "nxt" else ""}${if ((next & OpdBit) != 0) " opd" else ""}"
        }
      }
    }
    // A test that finds nothing to check proves nothing.
    assert(seen > 0, "no stjpc found in any ROM -- the encoding or the file format changed")
    assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
  }
}
