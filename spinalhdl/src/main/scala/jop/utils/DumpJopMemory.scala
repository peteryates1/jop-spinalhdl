package jop.utils

/**
 * Utility to dump JOP file memory contents for debugging.
 */
object DumpJopMemory extends App {
  val jopFilePath = "/srv/git/jop/java/Smallest/HelloWorld.jop"

  println("Loading JOP file...")
  val jopData = JopFileLoader.loadJopFile(jopFilePath)

  // Also check what raw bytes look like at start of file
  val rawLines = scala.io.Source.fromFile(jopFilePath).getLines().take(10).toSeq
  println("=== First 10 lines of JOP file ===")
  rawLines.foreach(println)

  println(s"Loaded ${jopData.words.length} words")
  println(s"Length (from header): ${jopData.length}")
  println()

  // Key addresses from the investigation
  val keyAddresses = Seq(
    (1051, "special pointers base"),
    (1480, "main method struct"),
    (1483, "0x5cb - suspected CP base"),
    (1490, "0x5d2 - failing CP lookup"),
    (1555, "boot method struct"),
    (1624, "JVM method struct")
  )

  // === H CHARACTER CORRUPTION INVESTIGATION ===
  // From debug log: getfield chain leading to corrupted 'H':
  //   1. getfield(handle=0x483) -> data_ptr=0x487, access data_ptr+0=0x487 -> 0x485
  //   2. getfield(handle=0x485) -> data_ptr=0x488, access data_ptr+0xB=0x493 -> 0x497
  // Expected: 0x48 ('H'), got: 0x497
  println("=== H CORRUPTION: Memory at addresses 0x480-0x4A0 ===")
  for (addr <- 0x480 to 0x4A0) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr)
      val hexVal = f"0x${value.toLong}%08x"
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      val marker = addr match {
        case 0x483 => " <-- handle for 1st getfield"
        case 0x485 => " <-- result of 1st getfield; handle for 2nd getfield"
        case 0x487 => " <-- data_ptr from handle 0x483"
        case 0x488 => " <-- data_ptr from handle 0x485"
        case 0x493 => " <-- accessed by 2nd getfield (data_ptr+0xB), RETURNS 0x497?"
        case 0x497 => " <-- address 0x497 (value is corrupted 'H'?)"
        case _ => ""
      }
      println(f"  mem[0x$addr%04x] ($addr%4d): $hexVal$comment$marker")
    }
  }
  println()

  // Dump method bytecodes at 0xBE-0xC5 (loaded in BC fill at cycle 3754)
  println("=== Method bytecodes at 0xBE-0xC5 (method at mp=0x532) ===")
  for (addr <- 0xBE to 0xC5) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr).toLong
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      // Byte swap (same as BC_WRITE does)
      val b0 = (value >> 0) & 0xFF   // goes to bits [31:24] in JBC
      val b1 = (value >> 8) & 0xFF   // goes to bits [23:16] in JBC
      val b2 = (value >> 16) & 0xFF  // goes to bits [15:8] in JBC
      val b3 = (value >> 24) & 0xFF  // goes to bits [7:0] in JBC
      val wordIdx = addr - 0xBE
      val jpcBase = wordIdx * 4
      println(f"  mem[0x$addr%04x]: 0x$value%08x -> JBC[${jpcBase}%2d-${jpcBase+3}%2d]: 0x$b0%02x 0x$b1%02x 0x$b2%02x 0x$b3%02x$comment")
    }
  }
  println()

  // Also dump the method struct at 0x532
  println("=== Method struct at mp=0x532 ===")
  for (addr <- 0x530 to 0x540) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr)
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      println(f"  mem[0x$addr%04x] ($addr%4d): 0x${value.toLong}%08x$comment")
    }
  }

  // Decode method struct
  val struct0_532 = jopData.words(0x532).toLong
  val struct1_532 = jopData.words(0x533).toLong
  val start_532 = (struct0_532 >> 10) & 0x3FFFFF
  val len_532 = struct0_532 & 0x3FF
  val cp_532 = (struct1_532 >> 10) & 0x3FFFFF
  val args_532 = (struct1_532 >> 5) & 0x1F
  val locals_532 = struct1_532 & 0x1F
  println(f"  struct0=0x$struct0_532%08x: start=$start_532, len=$len_532")
  println(f"  struct1=0x$struct1_532%08x: cp=$cp_532, args=$args_532, locals=$locals_532")

  // Dump the constant pool
  println(f"  === CP at 0x${cp_532.toInt}%04x ===")
  for (i <- -1 to 20) {
    val addr = cp_532.toInt + i
    if (addr >= 0 && addr < jopData.words.length) {
      val value = jopData.words(addr)
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      println(f"    cp[$i%2d] = mem[0x$addr%04x]: 0x${value.toLong}%08x$comment")
    }
  }
  println()

  println("=== Key Addresses ===")
  for ((addr, desc) <- keyAddresses) {
    val value = if (addr < jopData.words.length) jopData.words(addr) else BigInt(0)
    val comment = if (addr < jopData.comments.length) jopData.comments(addr) else ""
    println(f"mem[$addr%4d] (0x$addr%04x): $value%10d (0x${value.toLong}%08x) // $comment")
  }
  println()

  // Dump area around 0x5cb - 0x5d5
  println("=== Memory around 0x5cb - 0x5d5 ===")
  for (addr <- 0x5c0 to 0x5e0) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr)
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      println(f"mem[$addr%4d] (0x$addr%04x): $value%10d (0x${value.toLong}%08x)$comment")
    }
  }
  println()

  // Dump special pointers section (starts at 1051)
  println("=== Special Pointers (at 1051) ===")
  for (addr <- 1051 to 1060) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr)
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      println(f"mem[$addr%4d] (0x$addr%04x): $value%10d (0x${value.toLong}%08x)$comment")
    }
  }
  println()

  // Dump boot method struct area
  println("=== Boot method struct (1555) ===")
  for (addr <- 1555 to 1565) {
    if (addr < jopData.words.length) {
      val value = jopData.words(addr)
      val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
        s" // ${jopData.comments(addr)}" else ""
      println(f"mem[$addr%4d] (0x$addr%04x): $value%10d (0x${value.toLong}%08x)$comment")
    }
  }

  // Dump boot method bytecode
  println("\n=== Boot method bytecode (start=493, len=11 words) ===")
  var bootByteOffset = 0
  for (i <- 0 until 11) {
    val addr = 493 + i
    if (addr < jopData.words.length) {
      val word = jopData.words(addr).toLong
      val b0 = (word >> 0) & 0xFF
      val b1 = (word >> 8) & 0xFF
      val b2 = (word >> 16) & 0xFF
      val b3 = (word >> 24) & 0xFF
      println(f"  word[$addr%4d]: 0x${word}%08x = bytes[$bootByteOffset%2d-${bootByteOffset+3}%2d]: 0x$b3%02x 0x$b2%02x 0x$b1%02x 0x$b0%02x")
      bootByteOffset += 4
    }
  }
  println("\n=== Critical: Boot method byte 12-15 (what main method sees at JPC 12+) ===")
  val bootWord3 = jopData.words(493 + 3).toLong
  println(f"  Boot word 496 (JBC word 3): 0x$bootWord3%08x")
  val b12 = (bootWord3 >> 24) & 0xFF
  val b13 = (bootWord3 >> 16) & 0xFF
  val b14 = (bootWord3 >> 8) & 0xFF
  val b15 = bootWord3 & 0xFF
  println(f"  Byte 12: 0x$b12%02x (${bytecodeToName(b12.toInt)})")
  println(f"  Byte 13: 0x$b13%02x")
  println(f"  Byte 14: 0x$b14%02x")
  println(f"  Byte 15: 0x$b15%02x")

  def bytecodeToName(bc: Int): String = bc match {
    case 0xb7 => "invokespecial"
    case 0xb8 => "invokestatic"
    case 0xa3 => "if_icmpgt"
    case _ => f"bc_0x$bc%02x"
  }
  println()

  // Decode method struct format
  println("=== Decoding method struct at 1555 ===")
  if (1556 < jopData.words.length) {
    val struct0 = jopData.words(1555).toLong
    val struct1 = jopData.words(1556).toLong
    val start = (struct0 >> 10) & 0x3FF
    val len = struct0 & 0x3FF
    val cpArgsLocals = struct1
    val cp = (cpArgsLocals >> 10) & 0x3FF
    val args = (cpArgsLocals >> 5) & 0x1F
    val locals = cpArgsLocals & 0x1F
    println(f"  struct0 = 0x$struct0%08x: start=$start len=$len")
    println(f"  struct1 = 0x$struct1%08x: cp=$cp args=$args locals=$locals")
    println()

    // Look up what's at the CP
    println(f"=== Constant pool for boot method (cp=$cp, 0x${cp.toInt}%04x) ===")
    for (i <- 0 until 20) {
      val addr = cp.toInt + i
      if (addr < jopData.words.length) {
        val value = jopData.words(addr)
        val comment = if (addr < jopData.comments.length && jopData.comments(addr).nonEmpty)
          s" // ${jopData.comments(addr)}" else ""
        println(f"  cp[$i%2d] = mem[$addr%4d]: $value%10d (0x${value.toLong}%08x)$comment")
      }
    }
  }
  println()

  // Also check the second method invoked
  println("=== Main method struct (1480) ===")
  if (1481 < jopData.words.length) {
    val struct0 = jopData.words(1480).toLong
    val struct1 = jopData.words(1481).toLong
    val start = (struct0 >> 10) & 0x3FF
    val len = struct0 & 0x3FF
    val cpArgsLocals = struct1
    val cp = (cpArgsLocals >> 10) & 0x3FF
    val args = (cpArgsLocals >> 5) & 0x1F
    val locals = cpArgsLocals & 0x1F
    println(f"  struct0 = 0x$struct0%08x: start=$start len=$len")
    println(f"  struct1 = 0x$struct1%08x: cp=$cp args=$args locals=$locals")

    // Dump the bytecode content
    println(s"\n=== Main method bytecode (start=$start, len=$len words) ===")
    var byteOffset = 0
    for (i <- 0 until len.toInt) {
      val addr = start.toInt + i
      if (addr < jopData.words.length) {
        val word = jopData.words(addr).toLong
        val b0 = (word >> 0) & 0xFF
        val b1 = (word >> 8) & 0xFF
        val b2 = (word >> 16) & 0xFF
        val b3 = (word >> 24) & 0xFF
        println(f"  word[$addr%4d]: 0x${word}%08x = bytes[$byteOffset%2d-${byteOffset+3}%2d]: 0x$b3%02x 0x$b2%02x 0x$b1%02x 0x$b0%02x")
        byteOffset += 4
      }
    }

    // Specifically look at bytes 12-14 (invokespecial and its operand)
    println("\n=== Analyzing invokespecial at JPC=0x0c ===")
    val wordIdx = 0x0c / 4  // Which word contains byte 12
    val byteInWord = 0x0c % 4  // Which byte within that word
    if (start.toInt + wordIdx < jopData.words.length) {
      val word = jopData.words(start.toInt + wordIdx).toLong
      println(f"  Word at ${start.toInt + wordIdx}: 0x$word%08x")
      println(f"  JPC 0x0c is in word $wordIdx, byte position $byteInWord")

      // Extract bytes around JPC=0x0c
      for (jpc <- 0x0a to 0x10 if jpc / 4 <= len) {
        val w = start.toInt + jpc / 4
        if (w < jopData.words.length) {
          val wd = jopData.words(w).toLong
          val bytePos = jpc % 4
          // JOP bytecode is stored big-endian in each word (byte 0 at bits 31:24)
          val b = (wd >> (24 - bytePos * 8)) & 0xFF
          println(f"  JPC 0x$jpc%02x: bytecode 0x$b%02x")
        }
      }
    }
  }
}
