package jop.test

import jop.utils.JopFileLoader

object TestJopFile extends App {
  val data = JopFileLoader.loadJopFile("/srv/git/jop/java/Smallest/HelloWorld.jop")
  println(s"Total words: ${data.words.length}")
  println(s"Word 0 (length): ${data.words(0)}")
  println(s"Word 1 (mp): ${data.words(1)}")
  val mp = data.words(1).toInt
  println(s"Word $mp (should be boot ptr): ${data.words(mp)}")
  val bootPtr = data.words(mp).toInt
  println(s"Word $bootPtr (boot struct): ${data.words(bootPtr)}")
  val bootStruct = data.words(bootPtr).toLong
  println(s"  - packed: 0x${bootStruct.toHexString}")
  println(s"  - len: ${bootStruct & 0x3FF}")
  println(s"  - start: ${bootStruct >> 10}")
  
  // Check what's at words 1051-1056
  println("\nSpecial pointer area (around mp=1051):")
  for (i <- 1051 to 1056) {
    println(s"  Word $i: ${data.words(i)}")
  }
  
  // Check what's at words 1553-1558
  println("\nBoot method area (around 1555):")
  for (i <- 1553 to 1560) {
    if (i < data.words.length) {
      println(s"  Word $i: ${data.words(i)}")
    }
  }
}
