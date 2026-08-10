package jop.system

import jop.utils.JopFileLoader

/**
 * Locate a bytecode sequence in a .jop image and name the method containing it.
 *
 * WHY: identifying the method by parsing the .jop text by hand got it WRONG —
 * it named Startup.version(), which has no synchronized block at all. The hand
 * parse produced 12354 words against a header claiming 13175, so the index of
 * a match was not the memory address and every attribution was shifted.
 *
 * This uses JopFileLoader, the same loader the simulator initialises RAM with,
 * so a word index here IS the address the running core sees. That equivalence
 * is independently confirmed: reading GC/SmpGcTest statics at their link-file
 * addresses out of sim RAM returned correct live values (phase=2,
 * pubRound=[0,1,1,1]) during the wedge.
 *
 * Usage:
 *   sbt "Test/runMain jop.system.JopBytecodeLocate <app.jop> <link.txt> b0,b1,b2..."
 */
object JopBytecodeLocate extends App {
  val jopPath  = if (args.length > 0) args(0) else "java/apps/SmpGcTest/SmpGcTest.jop"
  val linkPath = if (args.length > 1) args(1) else "java/apps/SmpGcTest/SmpGcTest.jop.link.txt"
  // Default: the synchronized-block exception handler found in the bytecode
  // cache at the 4-core wedge —
  //   monitorenter, goto, 00, 00, astore_1, aload_0, monitorexit, aload_1, athrow
  val pattern = (if (args.length > 2) args(2).split(",").map(_.trim.toInt)
                 else Array(194, 167, 0, 0, 76, 42, 195, 43, 191)).map(_ & 0xFF)

  val words = JopFileLoader.jopFileToMemoryInit(jopPath, 128 * 1024 / 4)
  println(s"loaded ${words.length} words from $jopPath")

  // JOP packs four bytecodes per word, first in the most significant byte.
  // BC_WRITE byte-swaps on the way into the cache and BytecodeFetchStage then
  // selects byte k from bits 8k, so cache byte order == this order.
  val bytes = new Array[Int](words.length * 4)
  for (i <- words.indices) {
    val w = words(i).toLong & 0xFFFFFFFFL
    bytes(i * 4 + 0) = ((w >> 24) & 0xFF).toInt
    bytes(i * 4 + 1) = ((w >> 16) & 0xFF).toInt
    bytes(i * 4 + 2) = ((w >> 8) & 0xFF).toInt
    bytes(i * 4 + 3) = (w & 0xFF).toInt
  }

  // Method table from the link file: "bytecode <name> <wordAddress>"
  val re = """bytecode\s+(\S+)\s+(\d+)""".r
  val methods = scala.io.Source.fromFile(linkPath).getLines().collect {
    case re(name, addr) => (addr.toInt, name)
  }.toVector.sortBy(_._1)
  println(s"loaded ${methods.length} method entries from $linkPath")

  def methodAt(word: Int): String = {
    val before = methods.takeWhile(_._1 <= word)
    val after  = methods.dropWhile(_._1 <= word)
    if (before.isEmpty) "(before first method)"
    else {
      val (start, name) = before.last
      val end = after.headOption.map(_._1).getOrElse(words.length)
      f"$name  [words $start..${end - 1}, offset +${word - start}]"
    }
  }

  var hits = 0
  for (b <- 0 to bytes.length - pattern.length) {
    if (pattern.indices.forall(k => bytes(b + k) == pattern(k))) {
      hits += 1
      val word = b / 4
      println(f"MATCH at byte 0x$b%06x = word $word (0x$word%04x), byte-in-word ${b % 4}")
      println(f"   in method: ${methodAt(word)}")
    }
  }
  if (hits == 0) println("no match — check the byte order or the pattern")
  else println(s"$hits match(es)")

  // Control: decode the first bytes of a method whose contents are known, so a
  // silent misalignment cannot pass unnoticed again.
  methods.find(_._2.contains("SmpGcTest.publisher")).foreach { case (a, n) =>
    val head = (0 until 16).map(i => f"0x${bytes(a * 4 + i)}%02x").mkString(" ")
    println(s"\ncontrol: $n starts at word $a")
    println(s"   first bytes: $head")
  }
}
