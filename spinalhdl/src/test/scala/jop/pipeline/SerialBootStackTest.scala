package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import jop.config.{BootMode, DeviceInstance, DeviceType, JopCoreConfig, MicrocodePaths}
import jop.memory.JopMemoryConfig
import jop.system.JopCoreTestHarness
import jop.utils.{JopFileLoader, JopSimDefaults}

import scala.collection.mutable
import scala.io.Source

/**
 * THE SERIAL BOOT LOADER MUST HAND JAVA THE STACK IT WAS GIVEN -- status item 169.
 *
 * THE FAULT, as measured on hardware. A serial download that receives stray
 * bytes during the ready handshake boots a JOP whose arithmetic is wrong: on the
 * Colorlight i5, ten bytes of junk before the host's 0x55 printed
 * "GC: generational, ! -word cards" and then looped on "Uncaught exception",
 * from an image that boots cleanly without them. Any image, any serial-boot
 * board.
 *
 * THE CAUSE is in the boot microcode's stack discipline (`asm/src/jvm.asm`,
 * the serial loader). `bz`/`bnz` POP their operand -- the download loop's
 * `dup` before `bnz ser4` depends on it -- but three places were written as if
 * they did not:
 *
 *   - `rdy_got_byte`: after `bz rdy_done` has popped the XOR, BOTH exits pop
 *     again ("not ACK, discard" and "discard XOR result"), so every byte the
 *     handshake receives, stray or the ACK itself, takes one word off an empty
 *     stack;
 *   - `ser_checksum`: checksum bytes 3, 2 and 1 are each computed before their
 *     TX-ready loop AND again after it, and only the second copy is sent, so
 *     three words are left behind;
 *   - `ser_wait_ack`: both outcomes pop the ACK byte `bnz` already took.
 *
 * Net, with N stray bytes: SP reaches `cpux_boot` at `stack_init + 1 - N`, and
 * Java starts its stack there. Below `stack_init` lie the microcode's constant
 * table and variables, so pushes land on the constants that `ldi` reads. Ten
 * bytes put the leaked checksum byte 3 onto `ldi 65535` (i2c's mask), and the
 * card-size digits came out as `!` and a space.
 *
 * WHY THE EARLIER SIMS MISSED IT. Every BRAM sim boots the SIMULATION
 * microcode, which skips the download: the handshake and the checksum never
 * run. This drives the real SERIAL microcode through the protocol
 * `fpga/scripts/download.py` speaks -- 0xAA, 0x55, the words MSB first, the
 * four checksum bytes, 0x00 or 0xFF -- with stray bytes injected the way the
 * hardware reproducer injected them, one after each 0xAA.
 *
 * THE PROPERTY, and it names no ROM addresses: from the first 0xAA until the
 * loader jumps to `cpux_boot`, SP never drops below `stack_init` (nothing pops
 * an empty stack), and at `cpux_boot` it is exactly `stack_init` (nothing is
 * left on it). The two labels and `stack_init` come from the assembler's own
 * listing and the preprocessed source, so moving code cannot stale the test.
 * The image is eight words: nothing after `cpux_boot` is under test, so the
 * run stops there.
 */
class SerialBootStackTest extends AnyFunSuite {

  val clkPeriod = 10                                   // sim time units: 100 MHz
  // 10 clocks a bit: the UART samples 5x, so its tick divider is exactly 2.
  val baud = 10000000
  val bitPeriod = 100000000L / baud * clkPeriod

  val readyByte = 0xaa
  val readyAck  = 0x55

  /** Label -> ROM address, from the listing in rom.mif: a label line, then the
   *  address of the next instruction. */
  lazy val romLabels: Map[String, Int] = {
    val labelLine = """^\s*--\s*([A-Za-z_]\w*):""".r.unanchored
    val instrLine = """^\s*([0-9a-fA-F]+)\s*:\s*[0-9a-fA-F]+;""".r.unanchored
    val src = Source.fromFile(MicrocodePaths.romMif(BootMode.Serial))
    try {
      val labels = mutable.Map[String, Int]()
      var pending = List.empty[String]
      for (line <- src.getLines()) line match {
        case instrLine(addr) =>
          pending.foreach(l => labels(l) = Integer.parseInt(addr, 16))
          pending = Nil
        case labelLine(name) => pending ::= name
        case _ =>
      }
      labels.toMap
    } finally src.close()
  }

  /** `stack_init` as the serial build assembled it. */
  lazy val stackInit: Int = {
    val src = Source.fromFile(s"${MicrocodePaths.serialDir}/jvm.asm")
    try {
      val m = """(?m)^stack_init\s*=\s*(\d+)""".r.findFirstMatchIn(src.mkString)
      m.map(_.group(1).toInt).getOrElse(fail("no stack_init in the serial jvm.asm"))
    } finally src.close()
  }

  lazy val cpuxBoot: Int = romLabels.getOrElse("cpux_boot", fail("no cpux_boot label in the serial rom.mif"))

  lazy val compiled = {
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))
    val cfg = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 16 * 1024),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> baud))),
      supersetJumpTable = JumpTableInitData.serial)
    JopSimDefaults.config
      .workspaceName("SerialBootStackTest")
      .compile(JopCoreTestHarness(rom, ram, Seq.empty, coreConfig = Some(cfg), uartRx = true))
  }

  /** The length word first, as the linker writes it; the rest is arbitrary. */
  val image: Seq[Long] = {
    val body = Seq(0x12345678L, 0xdeadbeefL, 0x00000000L, 0xffffffffL, 0x80000001L, 0x55aa55aaL, 0x0000aa55L)
    (body.length + 1).toLong +: body
  }
  val imageChecksum: Long = image.foldLeft(0L)(_ ^ _)

  case class Boot(spAtCpuxBoot: Int, minSp: Int, readyBytes: Int)

  /**
   * One boot. `handshakes` gives the stray bytes for each handshake in turn;
   * every download but the last is answered with NACK (0xFF), which sends the
   * loader back to the handshake for a retry, as download.py's retry does.
   */
  def boot(name: String, handshakes: Seq[Seq[Int]]): Boot = {
    var result: Boot = null
    compiled.doSim(name) { dut =>
      dut.io.rxd #= true
      dut.clockDomain.forkStimulus(clkPeriod)

      val tx = mutable.Queue[Int]()
      var tracking = false
      var minSp = Int.MaxValue
      var spAtBoot = -1
      var readyBytes = 0
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.uartTxValid.toBoolean) {
            val b = dut.io.uartTxData.toInt
            if (b == readyByte) readyBytes += 1
            tx.enqueue(b)
            tracking = true
          }
          if (tracking && spAtBoot < 0) {
            val sp = dut.io.debugSp.toInt
            minSp = math.min(minSp, sp)
            if (dut.io.pc.toInt == cpuxBoot) spAtBoot = sp
          }
        }
      }

      def sendByte(b: Int): Unit = {
        dut.io.rxd #= false; sleep(bitPeriod)
        for (i <- 0 until 8) { dut.io.rxd #= ((b >> i) & 1) == 1; sleep(bitPeriod) }
        dut.io.rxd #= true; sleep(bitPeriod)
      }
      def nextTx(what: String): Int = {
        var n = 0
        while (tx.isEmpty && n < 200000) { dut.clockDomain.waitSampling(); n += 1 }
        assert(tx.nonEmpty, s"no $what from the loader within $n cycles")
        tx.dequeue()
      }
      def expectReady(): Unit = {
        val b = nextTx("ready byte")
        assert(b == readyByte, f"expected the ready byte 0x$readyByte%02x, got 0x$b%02x")
      }

      for ((stray, i) <- handshakes.zipWithIndex) {
        // download.py waits for 0xAA before answering; the junk goes in the
        // same slot, one byte after each 0xAA, as the hardware reproducer did.
        for (b <- stray) { expectReady(); sendByte(b) }
        expectReady()
        sendByte(readyAck)

        for (w <- image; shift <- Seq(24, 16, 8, 0)) sendByte(((w >> shift) & 0xff).toInt)
        val ck = (0 until 4).foldLeft(0L)((acc, _) => (acc << 8) | nextTx("checksum byte"))
        assert(ck == imageChecksum, f"loader checksum 0x$ck%08x, image 0x$imageChecksum%08x")
        sendByte(if (i == handshakes.length - 1) 0x00 else 0xff)
      }

      var n = 0
      while (spAtBoot < 0 && n < 200000) { dut.clockDomain.waitSampling(); n += 1 }
      assert(spAtBoot >= 0, s"the loader never reached cpux_boot (0x${cpuxBoot.toHexString}) after the ACK")
      result = Boot(spAtBoot, minSp, readyBytes)
    }
    result
  }

  def assertStackHandedOver(b: Boot): Unit = {
    val seen = s"lowest SP ${b.minSp}, SP at cpux_boot ${b.spAtCpuxBoot}, stack_init $stackInit"
    assert(b.minSp >= stackInit,
      s"the loader popped an empty stack, so its pushes wrote below stack_init, on the constant table ($seen)")
    assert(b.spAtCpuxBoot == stackInit,
      s"Java's stack starts ${b.spAtCpuxBoot - stackInit} word(s) off stack_init ($seen)")
  }

  test("a clean download hands Java the stack at stack_init") {
    val b = boot("clean", Seq(Seq.empty))
    assert(b.readyBytes == 1)
    assertStackHandedOver(b)
  }

  test("stray bytes in the ready handshake do not move the stack") {
    // Ten is the count that reproduced the original failure on the i5.
    val stray = Seq(0x00, 0xff, 0x00, 0xaa, 0x00, 0x01, 0x00, 0x7f, 0x00, 0x80)
    val b = boot("stray10", Seq(stray))
    assert(b.readyBytes == stray.length + 1, "each stray byte should be answered with a fresh 0xAA")
    assertStackHandedOver(b)
  }

  test("a NACKed download, retried after more stray bytes, hands Java the stack at stack_init") {
    val b = boot("nackRetry", Seq(Seq(0x00, 0x00), Seq(0x00, 0x00, 0x00)))
    assert(b.readyBytes == 2 + 1 + 3 + 1)
    assertStackHandedOver(b)
  }
}
