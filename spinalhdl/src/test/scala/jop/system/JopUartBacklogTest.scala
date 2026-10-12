package jop.system

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, JopSimDefaults}

/**
 * A UART READ WITH BYTES QUEUED BEHIND IT -- status item 68.
 *
 * `Uart`'s RX data register pops its FIFO on the read strobe, and the FIFO's
 * output register moves to the byte queued behind it the cycle after.
 * BmbMemoryController used to re-latch an I/O read from the same address,
 * without the strobe, until the next memory operation (`ioRdPending`), so
 * `ldmrd` took that NEXT byte: a burst 00 01 55 ... came back 01 55 ... with the
 * last byte twice. One byte at a time it never shows, because an emptied
 * FIFO's output does not move; a serial download polls a byte at a time and
 * the host is never more than a byte ahead at 2 Mbaud, so downloads worked.
 *
 * The real pipeline, microcode and Uart, running java/apps/Small UartBacklog:
 * it waits for a burst to queue, reads the FIFO dry with consecutive
 * Native.rd(IO_UART) and prints the bytes back.
 *
 *   make -C java/apps/Small APP_NAME=UartBacklog
 *   sbt "testOnly jop.system.JopUartBacklogTest"
 */
class JopUartBacklogTest extends AnyFunSuite {

  val baud = 2000000
  val clkPeriod = 10                                 // sim time units: 100 MHz
  val bitPeriod = 100000000L / baud * clkPeriod      // 50 clocks per bit

  test("a burst queued in the UART RX FIFO reads back intact") {
    val jopFile = jop.utils.SimApp.jop("Small", "UartBacklog")
    val rom  = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
    val ram  = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
    val main = JopFileLoader.jopFileToMemoryInit(jopFile, 256 * 1024 / 4)
    val cfg = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, params = Map("baudRate" -> baud))))

    // Consecutive bytes all differ, so a read one byte late cannot pass.
    val burst = Seq(0x00, 0x01, 0x55, 0xaa, 0xff, 0x80, 0x7f, 0x10,
                    0x20, 0x30, 0x41, 0x42, 0x43, 0x44, 0x0d, 0x0a)

    JopSimDefaults.config
      .compile(JopCoreTestHarness(rom, ram, main, coreConfig = Some(cfg), uartRx = true))
      .doSim { dut =>
        dut.io.rxd #= true
        dut.clockDomain.forkStimulus(clkPeriod)

        val out = new StringBuilder
        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) out.append(dut.io.uartTxData.toInt.toChar)
          }
        }
        def waitFor(text: String, maxCycles: Int): Unit = {
          var n = 0
          while (!out.toString.contains(text) && n < maxCycles) {
            dut.clockDomain.waitSampling(1000); n += 1000
          }
          assert(out.toString.contains(text), s"no '$text' within $maxCycles cycles; console: '$out'")
        }

        waitFor("UB ready\n", 5000000)

        // Back to back, start to stop bit: the FIFO fills while the app waits.
        for (b <- burst) {
          dut.io.rxd #= false; sleep(bitPeriod)
          for (i <- 0 until 8) { dut.io.rxd #= ((b >> i) & 1) == 1; sleep(bitPeriod) }
          dut.io.rxd #= true; sleep(bitPeriod)
        }

        waitFor("UB n=", 2000000)
        val start = out.indexOf("UB n=")
        var n = 0
        while (out.indexOf("\n", start) < 0 && n < 1000000) { dut.clockDomain.waitSampling(1000); n += 1000 }
        val line = out.substring(start, out.indexOf("\n", start))
        val expected = s"UB n=${burst.length} " + burst.map(b => f"$b%02x").mkString(" ")
        assert(line == expected, s"\n  read back: $line\n  sent:      $expected")
      }
  }
}
