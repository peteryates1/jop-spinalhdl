package jop.system
import jop.config._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * ClkWiz BlackBox for Wukong board.
 * Vivado clk_wiz_0: 50 MHz input -> 100 MHz output (single clock).
 */
class WukongClkWizBlackBox extends BlackBox {
  val io = new Bundle {
    val resetn  = in Bool()
    val clk_in  = in Bool()
    val clk_100 = out Bool()
    val locked  = out Bool()
  }

  setBlackBoxName("clk_wiz_0")
  noIoPrefix()
}

/**
 * JOP BRAM FPGA Top-Level for QMTECH XC7A100T Wukong V3
 *
 * Runs JOP processor with BRAM-backed memory and real UART output.
 * Uses Vivado ClkWiz to run at 100 MHz from 50 MHz input clock.
 * UART via on-board CH340N (TX + RX).
 * LEDs active HIGH.
 */
case class JopBramWukongTop(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  mainMemSize: Int = 64 * 1024
) extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val ser_rxd = in Bool()
    val led     = out Bits(2 bits)
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 100 MHz system clock (Vivado ClkWiz)
  // ========================================================================

  val clkWiz = new WukongClkWizBlackBox
  clkWiz.io.clk_in := io.clk_in
  // ClkWiz reset is active-HIGH despite port name "resetn" (Xilinx default polarity).
  // Tie to False = no reset.
  clkWiz.io.resetn := False

  // ========================================================================
  // Reset Generator (on ClkWiz 100 MHz output)
  // ========================================================================

  val rawClockDomain = ClockDomain(
    clock = clkWiz.io.clk_100,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init(0)
    when(clkWiz.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !clkWiz.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  val mainClockDomain = ClockDomain(
    clock = clkWiz.io.clk_100,
    reset = resetGen.int_res,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = mainMemSize)
    )

    // Extract JBC init from main memory
    val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
    val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
    val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
    val bootCodeStart = (bootMethodStartLen >> 10).toInt
    val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
    val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

    val jbcInit = bytecodeWords.flatMap { word =>
      val w = word.toLong & 0xFFFFFFFFL
      Seq(
        BigInt((w >> 24) & 0xFF),
        BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF),
        BigInt((w >> 0) & 0xFF)
      )
    }.padTo(2048, BigInt(0))

    val jopCore = JopCore(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // Block RAM with BMB interface
    val ram = BmbOnChipRam(
      p = config.memConfig.bmbParameter,
      size = config.memConfig.mainMemSize,
      hexInit = null
    )

    // Initialize RAM
    val memWords = config.memConfig.mainMemWords.toInt
    val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
    ram.ram.init(initData.map(v => B(v, 32 bits)))

    // Connect BMB
    ram.io.bus << jopCore.io.bmb

    // Drive debug RAM port (unused)
    jopCore.io.debugRamAddr := 0
    jopCore.io.debugHalt := False

    // Tie off snoop (single-core)
    jopCore.io.snoopIn.foreach { si =>
      si.valid   := False
      si.isArray := False
      si.handle  := 0
      si.index   := 0
    }

    // Single-core: no CmpSync
    jopCore.io.syncIn.halted := False
    jopCore.io.syncIn.s_out := False
    jopCore.io.syncIn.status := False

    // UART: TX + RX via CH340N
    io.ser_txd := jopCore.io.txd
    jopCore.io.rxd := io.ser_rxd

    // ======================================================================
    // LED Driver (active HIGH on Wukong)
    // ======================================================================

    // Heartbeat: ~1 Hz toggle (50M cycles at 100 MHz)
    val heartbeat = Reg(Bool()) init(False)
    val heartbeatCnt = Reg(UInt(26 bits)) init(0)
    heartbeatCnt := heartbeatCnt + 1
    when(heartbeatCnt === 49999999) {
      heartbeatCnt := 0
      heartbeat := ~heartbeat
    }

    // LED[1] = heartbeat (proves clock is running)
    // LED[0] = watchdog bit 0 (proves Java code is running)
    io.led(1) := heartbeat
    io.led(0) := jopCore.io.wd(0)
  }
}

/**
 * Generate Verilog for JopBramWukongTop
 */
object JopBramWukongTopVerilog extends App {
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val memSize = 64 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopBramWukongTop(romData, ramData, mainMemData, mainMemSize = memSize))

  println("Generated: spinalhdl/generated/JopBramWukongTop.v")
}
