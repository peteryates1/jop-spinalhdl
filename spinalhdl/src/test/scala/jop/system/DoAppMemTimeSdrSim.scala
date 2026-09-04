package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.memory.sdram.sdr._
import spinal.lib.memory.sdram.sdr.sim.SdramModel
import spinal.lib.memory.sdram.SdramLayout
import jop.config._
import jop.memory.{JopMemoryConfig, SdramDeviceInfo}
import jop.utils.{JopFileLoader, JopSimDefaults}

/**
 * DoApp memory stall profile against REAL SDR SDRAM.
 *
 * The BRAM profile (`DoAppMemTimeSim`) is the least representative backend in
 * the project: single-cycle accept, next-cycle response, and only available on
 * the larger parts. Real systems run SDR, DDR2 or DDR3, where a miss costs a
 * row activate and a CAS rather than one cycle, so the RATIOS between
 * categories should move — bytecode fill is sequential and burst-friendly,
 * while a handle dereference is a random round trip that a page miss punishes.
 * This measures how far.
 *
 * THE CLOCK MUST STAY REALISTIC HERE, unlike the BRAM run. `clockFreqHz` feeds
 * both the SDRAM controller's timing (ns converted to cycles) and DoApp's
 * one-simulated-second calibration via IO_US_CNT. Lowering it to shrink the
 * run would make memory artificially fast in cycle terms and destroy the very
 * thing being measured, so this runs at the EP4CGX150's real 80 MHz and simply
 * takes longer.
 *
 *   sbt "Test/runMain jop.system.DoAppMemTimeSdrSim"
 */
case class DoAppSdrHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  clockFreqHz: Long = 80000000L
) extends Component {
  val md = MemoryDevice.W9825G6JH6
  val layout: SdramLayout = SdramDeviceInfo.layoutFor(md)

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = layout.capacity.toInt),
    clkFreq = HertzNumber(clockFreqHz)
  )

  val io = new Bundle {
    val sdram = master(SdramInterface(layout))
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
    val debugMemState = out UInt(5 bits)
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
  }

  val sys = JopCoreWithSdram(
    config = config,
    memDevice = md,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(Seq.fill(config.jbcDepth)(BigInt(0))),
    clockFreqHz = clockFreqHz
  )

  io.sdram <> sys.io.sdram
  sys.io.syncIn.halted := False
  sys.io.syncIn.s_out := False
  sys.io.syncIn.status := False
  sys.io.rxd := True

  io.memBusy := sys.io.memBusy
  io.uartTxData := sys.io.uartTxData
  io.uartTxValid := sys.io.uartTxValid
  io.debugMemState := sys.io.debugMemState
  io.bmbCmdValid := sys.io.bmbCmdValid
  io.bmbCmdReady := sys.io.bmbCmdReady
}

object DoAppMemTimeSdrSim extends App {
  val jopFilePath = jop.utils.SimApp.jop("JbeBench", "JbeBench")   // entry point is jbe.DoApp
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 1024 * 1024 / 4)

  val CLK_HZ = 80000000L
  // DoApp calibrates each benchmark to one simulated second = 80 M cycles at
  // this clock, so all three plus boot needs roughly 260 M. Capped generously;
  // the run stops early on "JVM exit!".
  val MAX_CYCLES = 400000000L
  val WARMUP = 2000000L

  println(s"Loaded main memory: ${mainMemData.length} words")

  // The clock domain must DECLARE its frequency: the SDRAM controller converts
  // its ns timings into cycles and asks ClockDomain.current.frequency for the
  // divisor. Without this, elaboration fails with "trying to get the frequency
  // of a ClockDomain that doesn't know it".
  JopSimDefaults.config
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(80 MHz)))
    .compile(DoAppSdrHarness(romData, ramData, CLK_HZ))
    .doSim("doapp_memtime_sdr", 42) { dut =>
      dut.clockDomain.forkStimulus(period = 12500) // 12.5 ns = 80 MHz

      val sdramModel = SdramModel(
        io = dut.io.sdram,
        layout = dut.layout,
        clockDomain = dut.clockDomain
      )
      for (wordIdx <- mainMemData.indices) {
        val word = mainMemData(wordIdx).toLong & 0xFFFFFFFFL
        val byteAddr = wordIdx * 4
        sdramModel.write(byteAddr + 0, ((word >> 0) & 0xFF).toByte)
        sdramModel.write(byteAddr + 1, ((word >> 8) & 0xFF).toByte)
        sdramModel.write(byteAddr + 2, ((word >> 16) & 0xFF).toByte)
        sdramModel.write(byteAddr + 3, ((word >> 24) & 0xFF).toByte)
      }
      dut.clockDomain.waitSampling(5)

      val buckets = new MemProfile.Buckets()
      val uart = new StringBuilder
      var line = new StringBuilder
      var cycle = 0L
      var done = false

      while (cycle < MAX_CYCLES && !done) {
        dut.clockDomain.waitSampling()
        cycle += 1
        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.')
          if (c == 10) { done = buckets.onLine(line.toString.trim); line = new StringBuilder }
          else if (c >= 32 && c < 127) line.append(c.toChar)
        }
        if (cycle > WARMUP)
          buckets.tick(dut.io.debugMemState.toInt, dut.io.memBusy.toBoolean,
                       dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean)
        if (cycle % 20000000 == 0) println(f"  [$cycle%,d cycles] ${uart.toString.takeRight(60)}")
      }

      buckets.report("SDR 80 MHz (W9825G6JH6)", cycle)
      println("UART tail: " + uart.toString.takeRight(220))
    }
}
