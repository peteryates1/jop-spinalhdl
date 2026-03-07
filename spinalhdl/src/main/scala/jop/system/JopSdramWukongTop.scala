package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.utils.JopFileLoader
import jop.memory.{BmbSdramCtrl32, JopMemoryConfig}
import jop.pipeline.JumpTableInitData

/**
 * JOP SDRAM FPGA Top-Level for QMTECH XC7A100T Wukong V3
 *
 * Architecture:
 *   Board 50 MHz -> ClkWiz -> clk_100 (JOP system @ 100 MHz)
 *                            + clk_100_shift (-108° -> sdram_clk pin)
 *
 *   JopCluster.bmb -> BmbSdramCtrl32 (SdramCtrlNoCke) -> W9825G6KH-6 (32 MB)
 *
 * Reuses SdramExerciserClkWiz BlackBox (same clk_wiz_0 IP, 2 outputs).
 * Same reset generator as exerciser (BOOT -> 3-bit counter -> SYNC).
 *
 * Board clock domain (default CD, 50 MHz): heartbeat, hang detector,
 * diagnostic UART mux — same pattern as JopDdr3WukongTop.
 *
 * Serial-boot: same download protocol as DDR3 FPGA.
 */
case class JopSdramWukongTop(
  cpuCnt: Int = 1,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  jumpTable: JumpTableInitData = JumpTableInitData.serial
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  val io = new Bundle {
    val led       = out Bits(2 bits)
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9825G6JH6.layout))
  }

  noIoPrefix()

  // ========================================================================
  // Clock Wizard: Board 50 MHz -> 100 MHz system + 100 MHz -108° SDRAM clock
  // Reuses SdramExerciserClkWiz (same clk_wiz_0 IP)
  // ========================================================================

  val clkWiz = new SdramExerciserClkWiz
  clkWiz.io.clk_in := ClockDomain.current.readClockWire
  clkWiz.io.resetn := True
  io.sdram_clk := clkWiz.io.clk_100_shift

  // ========================================================================
  // Reset Generator (on clk_100)
  // ========================================================================

  val rawClockDomain = ClockDomain(
    clock = clkWiz.io.clk_100,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init (0)
    when(clkWiz.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !clkWiz.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  val mainClockDomain = ClockDomain(
    clock = clkWiz.io.clk_100,
    reset = resetGen.int_res,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // ========================================================================
  // Main Design Area (100 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    // ==================================================================
    // JOP Cluster
    // ==================================================================

    val burstLen = if (cpuCnt > 1) 4 else 0

    val cluster = JopCluster(
      cpuCnt = cpuCnt,
      baseConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(burstLen = burstLen),
        supersetJumpTable = jumpTable,
        clkFreqHz = 100000000L,
        useStackCache = true
      ),
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0)))
    )

    // ==================================================================
    // SDRAM Controller
    // ==================================================================

    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = cluster.bmbParameter,
      layout = W9825G6JH6.layout,
      timing = W9825G6JH6.timingGrade7,
      CAS = 3
    )

    sdramCtrl.io.bmb <> cluster.io.bmb
    io.sdram <> sdramCtrl.io.sdram

    // ==================================================================
    // UART
    // ==================================================================

    cluster.io.rxd := io.ser_rxd
  }

  // ========================================================================
  // Board Clock Domain Area (default CD, 50 MHz)
  // Heartbeat, hang detector, diagnostic UART mux, LED driver
  // ========================================================================

  // Heartbeat: ~1 Hz toggle (25M cycles at 50 MHz board clock)
  val hbCounter = Reg(UInt(25 bits)) init(0)
  val heartbeat = Reg(Bool()) init(False)
  hbCounter := hbCounter + 1
  when(hbCounter === 24999999) {
    hbCounter := 0
    heartbeat := ~heartbeat
  }

  // Debug signals: cross-domain sync from 100 MHz main -> 50 MHz board
  val memStateSync = BufferCC(mainArea.cluster.io.debugMemState, init = U(0, 5 bits))
  val memBusySync = BufferCC(mainArea.cluster.io.memBusy(0), init = False)
  val wdSync = (0 until cpuCnt).map(i => BufferCC(mainArea.cluster.io.wd(i)(0), init = False))
  val pcSync = BufferCC(mainArea.cluster.io.pc(0), init = U(0, 11 bits))
  val jpcSync = BufferCC(mainArea.cluster.io.jpc(0), init = U(0, 12 bits))

  // Hang detector: count cycles while memBusy stays True.
  // After ~335ms (2^24 @ 50MHz), switch LED display and trigger UART dump.
  val hangCounter = Reg(UInt(25 bits)) init(0)
  val hangDetected = Reg(Bool()) init(False)
  when(memBusySync) {
    when(!hangCounter.msb) {
      hangCounter := hangCounter + 1
    } otherwise {
      hangDetected := True
    }
  } otherwise {
    hangCounter := 0
  }

  // Latch memState at hang detection
  val hangMemState = Reg(UInt(5 bits)) init(0)
  when(!hangDetected) {
    hangMemState := memStateSync
  }

  // Diagnostic UART (board clock domain, 50 MHz)
  // No cache/adapter in SDRAM path, so feed zeros for those fields
  val diagUart = DiagUart(clockFreqHz = 50000000, baudRate = 1000000)
  diagUart.io.trigger      := hangDetected
  diagUart.io.memState     := memStateSync
  diagUart.io.pc           := pcSync
  diagUart.io.jpc          := jpcSync
  diagUart.io.cacheState   := U(0, 3 bits)
  diagUart.io.adapterState := U(0, 3 bits)

  // UART TX MUX: JOP's UART during normal operation, DiagUart when hung.
  val jopTxdSync = BufferCC(mainArea.cluster.io.txd, init = True)
  io.ser_txd := Mux(hangDetected, diagUart.io.txd, jopTxdSync)

  // LED Display (2 LEDs, active HIGH on Wukong)
  // LED[0] = WD (proves Java running), LED[1] = heartbeat
  // On hang: both LEDs alternate blink
  when(!hangDetected) {
    io.led(1) := heartbeat
    io.led(0) := wdSync(0)
  } otherwise {
    io.led(1) := heartbeat
    io.led(0) := ~heartbeat
  }
}

/**
 * Generate Verilog for JopSdramWukongTop (single-core)
 */
object JopSdramWukongTopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(JopSdramWukongTop(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData
  )))

  println("Generated: spinalhdl/generated/JopSdramWukongTop.v")
}
