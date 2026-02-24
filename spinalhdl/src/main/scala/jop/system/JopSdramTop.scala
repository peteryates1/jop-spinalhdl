package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.io.{InOutWrapper, TriState}
import spinal.lib.memory.sdram.sdr._
import spinal.lib.com.eth._
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbSdramCtrl32}
import jop.pipeline.JumpTableInitData
import jop.debug.{DebugConfig, DebugUart}

/**
 * DRAM PLL BlackBox
 *
 * Wraps the dram_pll VHDL entity (Altera altpll megafunction).
 * 50 MHz input -> c0=50MHz, c1=80MHz, c2=80MHz/-3ns phase shift
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
 * Runs JOP processor(s) with SDRAM-backed memory at 80 MHz.
 * PLL: 50 MHz input -> 80 MHz system clock, 80 MHz/-3ns SDRAM clock.
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
  ramInit: Seq[BigInt],
  debugConfig: Option[DebugConfig] = None,
  hasEth: Boolean = false
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // Preserve existing Quartus entity names
  if (cpuCnt >= 2) setDefinitionName("JopSmpSdramTop")

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val ser_rxd   = in Bool()
    val led       = out Bits(Math.max(2, cpuCnt) bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9825G6JH6.layout))
    // Debug UART pins (optional)
    val debug_txd = if (debugConfig.isDefined) Some(out Bool()) else None
    val debug_rxd = if (debugConfig.isDefined) Some(in Bool()) else None

    // Ethernet GMII pins (optional, active at 100 Mbps MII subset)
    val e_txd    = if (hasEth) Some(out Bits(4 bits))          else None
    val e_txen   = if (hasEth) Some(out Bool())                else None
    val e_txer   = if (hasEth) Some(out Bool())                else None
    val e_txc    = if (hasEth) Some(in Bool())                 else None  // 25 MHz from PHY
    val e_gtxc   = if (hasEth) Some(out Bool())                else None  // Tie low for 100M
    val e_rxd    = if (hasEth) Some(in Bits(4 bits))           else None
    val e_rxdv   = if (hasEth) Some(in Bool())                 else None
    val e_rxer   = if (hasEth) Some(in Bool())                 else None
    val e_rxc    = if (hasEth) Some(in Bool())                 else None  // 25 MHz from PHY
    val e_mdc    = if (hasEth) Some(out Bool())                else None
    val e_mdio   = if (hasEth) Some(master(TriState(Bool())))  else None
    val e_resetn = if (hasEth) Some(out Bool())                else None  // Active-low
  }

  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 80 MHz system, 80 MHz/-3ns SDRAM clock
  // ========================================================================

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  // SDRAM clock output (PLL c2: 80 MHz with -3ns phase shift)
  io.sdram_clk := pll.io.c2

  // ========================================================================
  // Reset Generator (on PLL c1 = 80 MHz)
  // ========================================================================

  // Raw clock domain from PLL c1 (80 MHz, no reset yet)
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

  // Main clock domain: 80 MHz with generated reset
  val mainClockDomain = ClockDomain(
    clock = pll.io.c1,
    reset = resetGen.int_res,
    frequency = FixedFrequency(80 MHz),
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // ========================================================================
  // Ethernet Clock Domains (25 MHz from PHY, only when hasEth)
  // ========================================================================

  val ethTxCd = if (hasEth) Some(ClockDomain(
    clock = io.e_txc.get,
    config = ClockDomainConfig(resetKind = BOOT)
  )) else None

  val ethRxCd = if (hasEth) Some(ClockDomain(
    clock = io.e_rxc.get,
    config = ClockDomainConfig(resetKind = BOOT)
  )) else None

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
        memConfig = JopMemoryConfig(burstLen = 4),
        jumpTable = JumpTableInitData.serial,
        clkFreqHz = 80000000L,
        hasEth = hasEth
      ),
      debugConfig = debugConfig,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0))),
      ethTxCd = ethTxCd,
      ethRxCd = ethRxCd
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
      layout = W9825G6JH6.layout,
      timing = W9825G6JH6.timingGrade7,
      CAS = 3,
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

    if (cpuCnt == 1) {
      // Heartbeat: ~1 Hz toggle (40M cycles at 80 MHz)
      val heartbeat = Reg(Bool()) init(False)
      val heartbeatCnt = Reg(UInt(26 bits)) init(0)
      heartbeatCnt := heartbeatCnt + 1
      when(heartbeatCnt === 39999999) {
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
      // LED[i] = core i watchdog bit 0 (proves core i Java code is running)
      for (i <- 0 until cpuCnt) {
        io.led(i) := ~cluster.io.wd(i)(0)
      }
    }

    // ==================================================================
    // Ethernet (optional)
    // ==================================================================

    if (hasEth) {
      // GTX clock not used at 100M (PHY provides TX_CLK)
      io.e_gtxc.get := False

      // MDIO wiring (combinational passthrough)
      io.e_mdc.get := cluster.io.mdc.get
      io.e_mdio.get.write := cluster.io.mdioOut.get
      io.e_mdio.get.writeEnable := cluster.io.mdioOe.get
      cluster.io.mdioIn.get := io.e_mdio.get.read

      // PHY collision/busy tie-offs (full-duplex, not applicable)
      cluster.io.phy.get.colision := False
      cluster.io.phy.get.busy := False

      // PHY hardware reset: 20-bit counter (~13ms at 80 MHz)
      // ANDed with software reset register from BmbMdio
      val phyRstCnt = Reg(UInt(20 bits)) init(0)
      val phyRstDone = phyRstCnt.andR
      when(!phyRstDone) { phyRstCnt := phyRstCnt + 1 }
      // Active-low output: low during HW reset OR when SW reset asserted
      io.e_resetn.get := phyRstDone && cluster.io.phyReset.get

      // TX adapter (in ethTxCd = 25 MHz from PHY TX_CLK)
      // Adds inter-frame gap (12 byte times) then registers to MII TX pins
      val txArea = new ClockingArea(ethTxCd.get) {
        val interframe = MacTxInterFrame(4)
        interframe.io.input << cluster.io.phy.get.tx
        io.e_txen.get := RegNext(interframe.io.output.valid) init(False)
        io.e_txd.get  := RegNext(interframe.io.output.fragment.data) init(0)
        io.e_txer.get := False
      }

      // RX adapter (in ethRxCd = 25 MHz from PHY RX_CLK)
      // Registers MII RX pins, detects frame boundaries, feeds MacEth
      val rxArea = new ClockingArea(ethRxCd.get) {
        // Two-stage pipeline (matches MiiRx.toRxFlow() pattern)
        val s1_dv = RegNext(io.e_rxdv.get) init(False)
        val s1_d  = RegNext(io.e_rxd.get)  init(0)
        val s1_er = RegNext(io.e_rxer.get) init(False)

        val s2_dv = RegNext(s1_dv) init(False)
        val s2_d  = RegNext(s1_d)  init(0)
        val s2_er = RegNext(s1_er) init(False)

        // Create Flow(Fragment(PhyRx(4))) then convert to Stream
        val rxFlow = Flow(Fragment(PhyRx(4)))
        rxFlow.valid          := s2_dv
        rxFlow.fragment.data  := s2_d
        rxFlow.fragment.error := s2_er
        rxFlow.last           := !s1_dv && s2_dv  // Falling edge of DV = end of frame

        cluster.io.phy.get.rx << rxFlow.toStream
      }
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
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
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
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(JopSdramTop(
    cpuCnt = cpuCnt,
    romInit = romData,
    ramInit = ramData
  )))

  println(s"Generated: spinalhdl/generated/JopSmpSdramTop.v ($cpuCnt cores)")
}
