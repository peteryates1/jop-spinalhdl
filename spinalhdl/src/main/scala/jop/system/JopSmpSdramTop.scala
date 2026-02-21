package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram.sdr._
import jop.io.CmpSync
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32}
import jop.pipeline.JumpTableInitData

/**
 * JOP SMP (Symmetric Multi-Processing) SDRAM Top-Level
 *
 * Instantiates N JOP cores sharing a single SDRAM through BmbArbiter.
 * Each core has internal BmbSys (with unique cpuId). Only core 0 has BmbUart.
 * CmpSync provides the global lock for Java synchronized blocks.
 *
 * Target: QMTECH EP4CGX150 (150K LEs, plenty of room for 2-4 cores).
 *
 * Architecture:
 *   JopCore[0] (BmbSys+BmbUart) ─ BMB ─┐
 *   JopCore[1] (BmbSys)          ─ BMB ─┤→ BmbArbiter → SDRAM
 *                                        ↕
 *                                     CmpSync
 *
 * @param cpuCnt    Number of CPU cores (2+)
 * @param romInit   Microcode ROM initialization data (serial-boot)
 * @param ramInit   Stack RAM initialization data (serial-boot)
 */
case class JopSmpSdramTop(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {
  require(cpuCnt >= 2, "SMP requires at least 2 cores")

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

  val rawClockDomain = ClockDomain(
    clock = pll.io.c1,
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

    // JBC init: empty (zeros) — BC_FILL loads bytecodes dynamically from SDRAM
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // ====================================================================
    // Instantiate N JOP Cores (each with internal BmbSys, core 0 with BmbUart)
    // ====================================================================

    val cores = (0 until cpuCnt).map { i =>
      val coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(burstLen = 4),
        jumpTable = JumpTableInitData.serial,
        cpuId = i,
        cpuCnt = cpuCnt,
        hasUart = (i == 0),  // Only core 0 gets UART
        clkFreqHz = 100000000L
      )
      JopCore(
        config = coreConfig,
        romInit = Some(romInit),
        ramInit = Some(ramInit),
        jbcInit = Some(jbcInit)
      )
    }

    // ====================================================================
    // BMB Arbiter: N masters -> 1 slave
    // ====================================================================

    val inputParam = cores(0).config.memConfig.bmbParameter
    val sourceRouteWidth = log2Up(cpuCnt)

    // Arbiter output parameter: wider source to route responses back
    val outputSourceCount = 1 << sourceRouteWidth
    val inputSourceParam = inputParam.access.sources.values.head
    val arbiterOutputParam = BmbParameter(
      access = BmbAccessParameter(
        addressWidth = inputParam.access.addressWidth,
        dataWidth = inputParam.access.dataWidth
      ).addSources(outputSourceCount, BmbSourceParameter(
        contextWidth = inputSourceParam.contextWidth,
        lengthWidth = inputSourceParam.lengthWidth,
        canWrite = true,
        canRead = true,
        alignment = BmbParameter.BurstAlignement.WORD
      )),
      invalidation = BmbInvalidationParameter()
    )

    val arbiter = BmbArbiter(
      inputsParameter = Seq.fill(cpuCnt)(inputParam),
      outputParameter = arbiterOutputParam,
      lowerFirstPriority = false  // Round-robin
    )

    // Wire cores -> arbiter
    for (i <- 0 until cpuCnt) {
      arbiter.io.inputs(i) << cores(i).io.bmb
    }

    // ====================================================================
    // SDRAM Controller (shared)
    // ====================================================================

    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = arbiterOutputParam,
      layout = W9825G6JH6.layout,
      timing = W9825G6JH6.timingGrade7,
      CAS = 3,
      useAlteraCtrl = true,
      clockFreqHz = 100000000L
    )

    sdramCtrl.io.bmb <> arbiter.io.output
    io.sdram <> sdramCtrl.io.sdram

    // ====================================================================
    // CmpSync + Per-core Wiring
    // ====================================================================

    val cmpSync = CmpSync(cpuCnt)
    for (i <- 0 until cpuCnt) {
      cmpSync.io.syncIn(i) := cores(i).io.syncOut
      cores(i).io.syncIn := cmpSync.io.syncOut(i)
      cores(i).io.irq := False
      cores(i).io.irqEna := False
      cores(i).io.debugRamAddr := 0
    }

    // UART (core 0 only)
    io.ser_txd := cores(0).io.txd
    cores(0).io.rxd := io.ser_rxd
    for (i <- 1 until cpuCnt) {
      cores(i).io.rxd := True  // No UART on cores 1+
    }

    // ====================================================================
    // LED Driver
    // ====================================================================

    // QMTECH LEDs are active low
    // LED[0] = watchdog bit 0 from core 0 (proves core 0 Java code is running)
    // LED[1] = watchdog bit 0 from core 1 (proves core 1 Java code is running)
    io.led(0) := ~cores(0).io.wd(0)
    io.led(1) := ~cores(1).io.wd(0)
  }
}

/**
 * Generate Verilog for JopSmpSdramTop
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
  ).generate(InOutWrapper(JopSmpSdramTop(cpuCnt, romData, ramData)))

  println(s"Generated: spinalhdl/generated/JopSmpSdramTop.v ($cpuCnt cores)")
}
