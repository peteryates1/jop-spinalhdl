package jop.sim

import org.scalatest.funsuite.AnyFunSuite
import jop.utils.JopFileLoader
import java.nio.file.Files

/**
 * An image that does not fit its memory is refused, not truncated.
 *
 * `jopFileToMemoryInit` took the first `memSize` words and dropped the rest.
 * DoAll is 75,005 words, and four sims load it into 256 KB -- 65,536 words --
 * so they ran a program without its last 9,469 words: `JopConfigJvmTestsSim`
 * printed `ni 065619 ...`, a method pointer past the end of memory, for 60M
 * cycles, and nothing said why (item 176). `JopTopVerilog` initialises BRAM
 * bitstreams through the same call.
 */
class ImageFitTest extends AnyFunSuite {

  /** A .jop of `words` words; the first is its own length, as JOPizer writes it. */
  private def image(words: Int): String = {
    val f = Files.createTempFile("image", ".jop")
    f.toFile.deleteOnExit()
    Files.write(f, (words +: (1 until words)).map(w => s"$w,").mkString("\n").getBytes)
    f.toString
  }

  test("an image that fits loads whole, padded to the memory") {
    val m = JopFileLoader.jopFileToMemoryInit(image(10), 16)
    assert(m.length == 16)
    assert(m.take(10) == (10 +: (1 until 10)).map(BigInt(_)))
    assert(m.drop(10).forall(_ == 0))
  }

  test("an image larger than its memory is refused, not truncated") {
    val e = intercept[IllegalArgumentException](JopFileLoader.jopFileToMemoryInit(image(10), 8))
    assert(e.getMessage.contains("10 words") && e.getMessage.contains("8-word"), e.getMessage)
  }
}
