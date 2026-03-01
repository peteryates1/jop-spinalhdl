package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32, W9864G6JT}
import jop.pipeline.JumpTableInitData
import jop.debug.{DebugConfig, DebugUart}

/**
 * CYC5000 PLL BlackBox
 *
 * Wraps a Cyclone V altera_pll megafunction.
 * 12 MHz input -> c0=80MHz (JOP system), c1=80MHz/-2.5ns (SDRAM clock pin)
 */
case class Cyc5000Pll() extends BlackBox {
  setDefinitionName("cyc5000_pll")

  val io = new Bundle {
    val refclk   = in Bool()
    val rst      = in Bool()
    val outclk_0 = out Bool()
    val outclk_1 = out Bool()
    val locked   = out Bool()
  }

  noIoPrefix()
}

/**
 * JOP SDRAM FPGA Top-Level for Trenz CYC5000
 *
 * Board: Trenz CYC5000 (TEI0050)
 * FPGA: Altera Cyclone V E (5CEBA2U15C8N)
 * SDRAM: W9864G6JT-6 (64Mbit, 16-bit, 12-bit addr, 4 banks)
 * Clock: 12 MHz oscillator -> PLL -> 80 MHz system, 80 MHz/-2.5ns SDRAM
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to each JopCore.
 *
 * When cpuCnt = 1 (single-core):
 *   - One JopCore, BMB goes directly to BmbSdramCtrl32
 *   - No CmpSync
 *   - LED[7:3] = memState, LED[2] = memBusy, LED[1] = heartbeat, LED[0] = WD
 *
 * When cpuCnt >= 2 (SMP):
 *   - N JopCore instances with BmbArbiter -> shared BmbSdramCtrl32
 *   - CmpSync for global lock synchronization
 *   - LED[7] = heartbeat, LED[N-1:0] = per-core WD
 *   - Entity name set to JopSmpCyc5000Top for separate Quartus project
 *
 * @param cpuCnt  Number of CPU cores (1 = single-core, 2+ = SMP)
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopCyc5000Top(
  cpuCnt: Int = 1,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  debugConfig: Option[DebugConfig] = None,
  fpuMode: FpuMode.FpuMode = FpuMode.Software,
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // Preserve Quartus entity names
  if (cpuCnt >= 2) setDefinitionName("JopSmpCyc5000Top")

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val led       = out Bits(8 bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9864G6JT.layout))
    // Debug UART pins (optional)
    val debug_txd = if (debugConfig.isDefined) Some(out Bool()) else None
    val debug_rxd = if (debugConfig.isDefined) Some(in Bool()) else None
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 12 MHz -> 80 MHz system, 80 MHz/-2.5ns SDRAM clock
  // ========================================================================

  val pll = Cyc5000Pll()
  pll.io.refclk := io.clk_in
  pll.io.rst    := False

  // SDRAM clock output (PLL outclk_1: 80 MHz with -2.5ns phase shift)
  io.sdram_clk := pll.io.outclk_1

  // ========================================================================
  // Reset Generator (on PLL outclk_0 = 80 MHz)
  // ========================================================================

  val rawClockDomain = ClockDomain(
    clock = pll.io.outclk_0,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init(0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  val mainClockDomain = ClockDomain(
    clock = pll.io.outclk_0,
    reset = resetGen.int_res,
    frequency = FixedFrequency(80 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area (80 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    // ==================================================================
    // JOP Cluster: N cores with arbiter + CmpSync
    // ==================================================================

    val cluster = JopCluster(
      cpuCnt = cpuCnt,
      baseConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(burstLen = 0),
        jumpTable = JumpTableInitData.serial,
        clkFreqHz = 80000000L,
        fpuMode = fpuMode
      ),
      debugConfig = debugConfig,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0))),
      perCoreConfigs = perCoreConfigs
    )

    // Debug UART (when debug is enabled)
    debugConfig.foreach { cfg =>
      val debugUart = DebugUart(cfg.baudRate, 80000000L)
      debugUart.io.transport <> cluster.io.debugTransport.get
      io.debug_txd.get := debugUart.io.txd
      debugUart.io.rxd := io.debug_rxd.get
    }

    // ==================================================================
    // SDRAM Controller (shared)
    // ==================================================================

    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = cluster.bmbParameter,
      layout = W9864G6JT.layout,
      timing = W9864G6JT.timingGrade6,
      CAS = 2,
      useAlteraCtrl = true,
      clockFreqHz = 80000000L
    )

    sdramCtrl.io.bmb <> cluster.io.bmb
    io.sdram <> sdramCtrl.io.sdram

    // ==================================================================
    // UART
    // ==================================================================

    io.ser_txd := cluster.io.txd
    cluster.io.rxd := io.ser_rxd

    // ==================================================================
    // LED Driver
    // ==================================================================

    // Heartbeat: ~1 Hz toggle (40M cycles at 80 MHz)
    val heartbeat = Reg(Bool()) init(False)
    val heartbeatCnt = Reg(UInt(26 bits)) init(0)
    heartbeatCnt := heartbeatCnt + 1
    when(heartbeatCnt === 39999999) {
      heartbeatCnt := 0
      heartbeat := ~heartbeat
    }

    // CYC5000 LEDs are active low
    if (cpuCnt == 1) {
      // LED[7:3] = memory controller state (5 bits)
      // LED[2]   = memBusy
      // LED[1]   = heartbeat (proves clock is running)
      // LED[0]   = watchdog bit 0 (proves Java code is running)
      io.led(7 downto 3) := ~cluster.io.debugMemState.asBits.resized
      io.led(2) := ~cluster.io.memBusy(0)
      io.led(1) := ~heartbeat
      io.led(0) := ~cluster.io.wd(0)(0)
    } else {
      // LED[7]     = heartbeat (proves clock is running)
      // LED[N-1:0] = per-core watchdog bit 0
      // Remaining  = off (active low = 1)
      io.led := B"11111111"
      io.led(7) := ~heartbeat
      for (i <- 0 until cpuCnt.min(7)) {
        io.led(i) := ~cluster.io.wd(i)(0)
      }
    }
  }
}

/**
 * Generate Verilog for JopCyc5000Top (single-core)
 */
object JopCyc5000TopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(JopCyc5000Top(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData
  )))

  println("Generated: spinalhdl/generated/JopCyc5000Top.v")
}

/**
 * Generate Verilog for JopCyc5000Top in SMP mode (entity: JopSmpCyc5000Top)
 */
object JopSmpCyc5000TopVerilog extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Generating $cpuCnt-core SMP Verilog...")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(JopCyc5000Top(
    cpuCnt = cpuCnt,
    romInit = romData,
    ramInit = ramData
  )))

  println(s"Generated: spinalhdl/generated/JopSmpCyc5000Top.v ($cpuCnt cores)")
}
