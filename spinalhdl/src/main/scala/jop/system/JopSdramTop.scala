package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32}
import jop.pipeline.JumpTableInitData

/**
 * DRAM PLL BlackBox
 *
 * Wraps the dram_pll VHDL entity (Altera altpll megafunction).
 * 50 MHz input -> c0=50MHz, c1=100MHz, c2=100MHz/-3ns phase shift
 */
case class DramPll() extends BlackBox {
  setDefinitionName("dram_pll")

  val io = new Bundle {
    val inclk0 = in Bool()
    val areset = in Bool()
    val c0     = out Bool()
    val c1     = out Bool()
    val c2     = out Bool()
    val locked = out Bool()
  }

  noIoPrefix()
}

/**
 * JOP SDRAM FPGA Top-Level for QMTECH EP4CGX150
 *
 * Runs JOP processor(s) with SDRAM-backed memory at 100 MHz.
 * PLL: 50 MHz input -> 100 MHz system clock, 100 MHz/-3ns SDRAM clock.
 * Full UART TX+RX for serial download protocol (core 0 only).
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to each JopCore.
 *
 * When cpuCnt = 1 (single-core):
 *   - One JopCore, BMB goes directly to BmbSdramCtrl32
 *   - No CmpSync
 *   - LED[0] = WD bit 0, LED[1] = heartbeat
 *
 * When cpuCnt >= 2 (SMP):
 *   - N JopCore instances with BmbArbiter -> shared BmbSdramCtrl32
 *   - CmpSync for global lock synchronization
 *   - LED[0] = core 0 WD, LED[1] = core 1 WD
 *   - Entity name set to JopSmpSdramTop for Quartus backward compat
 *
 * @param cpuCnt  Number of CPU cores (1 = single-core, 2+ = SMP)
 * @param romInit Microcode ROM initialization data (serial-boot)
 * @param ramInit Stack RAM initialization data (serial-boot)
 */
case class JopSdramTop(
  cpuCnt: Int = 1,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // Preserve existing Quartus entity names
  if (cpuCnt >= 2) setDefinitionName("JopSmpSdramTop")

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val led       = out Bits(2 bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9825G6JH6.layout))
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 100 MHz system, 100 MHz/-3ns SDRAM clock
  // ========================================================================

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  // SDRAM clock output (PLL c2: 100 MHz with -3ns phase shift)
  io.sdram_clk := pll.io.c2

  // ========================================================================
  // Reset Generator (on PLL c1 = 100 MHz)
  // ========================================================================

  // Raw clock domain from PLL c1 (100 MHz, no reset yet)
  val rawClockDomain = ClockDomain(
    clock = pll.io.c1,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  // Reset active (high) until counter saturates AND PLL locked
  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init(0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }

  // Main clock domain: 100 MHz with generated reset
  val mainClockDomain = ClockDomain(
    clock = pll.io.c1,
    reset = resetGen.int_res,
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Main Design Area (100 MHz)
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    // ==================================================================
    // JOP Cluster: N cores with arbiter + CmpSync
    // ==================================================================

    val cluster = JopCluster(
      cpuCnt = cpuCnt,
      baseConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(burstLen = 4),
        jumpTable = JumpTableInitData.serial,
        clkFreqHz = 100000000L
      ),
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0)))
    )

    // ==================================================================
    // SDRAM Controller (shared)
    // ==================================================================

    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = cluster.bmbParameter,
      layout = W9825G6JH6.layout,
      timing = W9825G6JH6.timingGrade7,
      CAS = 3,
      useAlteraCtrl = true,
      clockFreqHz = 100000000L
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

    if (cpuCnt == 1) {
      // Heartbeat: ~1 Hz toggle (50M cycles at 100 MHz)
      val heartbeat = Reg(Bool()) init(False)
      val heartbeatCnt = Reg(UInt(26 bits)) init(0)
      heartbeatCnt := heartbeatCnt + 1
      when(heartbeatCnt === 49999999) {
        heartbeatCnt := 0
        heartbeat := ~heartbeat
      }

      // QMTECH LEDs are active low
      // LED[1] = heartbeat (proves clock is running)
      // LED[0] = watchdog bit 0 (proves Java code is running)
      io.led(1) := ~heartbeat
      io.led(0) := ~cluster.io.wd(0)(0)
    } else {
      // QMTECH LEDs are active low
      // LED[0] = core 0 watchdog bit 0 (proves core 0 Java code is running)
      // LED[1] = core 1 watchdog bit 0 (proves core 1 Java code is running)
      io.led(0) := ~cluster.io.wd(0)(0)
      io.led(1) := ~cluster.io.wd(1)(0)
    }
  }
}

/**
 * Generate Verilog for JopSdramTop (single-core)
 */
object JopSdramTopVerilog extends App {
  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(JopSdramTop(
    cpuCnt = 1,
    romInit = romData,
    ramInit = ramData
  )))

  println("Generated: spinalhdl/generated/JopSdramTop.v")
}

/**
 * Generate Verilog for JopSdramTop in SMP mode (entity: JopSmpSdramTop)
 */
object JopSmpSdramTopVerilog extends App {
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
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(JopSdramTop(
    cpuCnt = cpuCnt,
    romInit = romData,
    ramInit = ramData
  )))

  println(s"Generated: spinalhdl/generated/JopSmpSdramTop.v ($cpuCnt cores)")
}
