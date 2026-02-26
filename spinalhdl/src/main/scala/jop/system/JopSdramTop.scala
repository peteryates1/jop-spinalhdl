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
    val c3     = out Bool()   // 25 MHz VGA pixel clock
    val locked = out Bool()
  }

  noIoPrefix()
}

/**
 * Ethernet 125 MHz PLL BlackBox
 *
 * Wraps the pll_125 Verilog module (Altera altpll megafunction).
 * 50 MHz input -> c0=125 MHz for GMII TX clock
 */
case class EthPll() extends BlackBox {
  setDefinitionName("pll_125")

  val io = new Bundle {
    val inclk0 = in Bool()
    val c0     = out Bool()
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
  ioConfig: IoConfig = IoConfig()
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

    // Ethernet PHY pins (GMII 8-bit @ 1Gbps or MII 4-bit @ 100Mbps)
    val e_txd    = if (ioConfig.hasEth) Some(out Bits(ioConfig.phyDataWidth bits)) else None
    val e_txen   = if (ioConfig.hasEth) Some(out Bool())                else None
    val e_txer   = if (ioConfig.hasEth) Some(out Bool())                else None
    val e_txc    = if (ioConfig.hasEth && !ioConfig.ethGmii) Some(in Bool()) else None  // 25 MHz from PHY (MII only)
    val e_gtxc   = if (ioConfig.hasEth) Some(out Bool())                else None  // 125 MHz to PHY (GMII) or tied low (MII)
    val e_rxd    = if (ioConfig.hasEth) Some(in Bits(ioConfig.phyDataWidth bits)) else None
    val e_rxdv   = if (ioConfig.hasEth) Some(in Bool())                 else None
    val e_rxer   = if (ioConfig.hasEth) Some(in Bool())                 else None
    val e_rxc    = if (ioConfig.hasEth) Some(in Bool())                 else None  // 25 MHz (MII) or 125 MHz (GMII)
    val e_mdc    = if (ioConfig.hasEth) Some(out Bool())                else None
    val e_mdio   = if (ioConfig.hasEth) Some(master(TriState(Bool())))  else None
    val e_resetn = if (ioConfig.hasEth) Some(out Bool())                else None  // Active-low

    // VGA (optional)
    val vga_hs = if (ioConfig.hasVga) Some(out Bool()) else None
    val vga_vs = if (ioConfig.hasVga) Some(out Bool()) else None
    val vga_r  = if (ioConfig.hasVga) Some(out Bits(5 bits)) else None
    val vga_g  = if (ioConfig.hasVga) Some(out Bits(6 bits)) else None
    val vga_b  = if (ioConfig.hasVga) Some(out Bits(5 bits)) else None

    // SD Native (optional, bidirectional CMD + per-bit DAT tristate)
    val sd_clk   = if (ioConfig.hasSdNative) Some(out Bool()) else None
    val sd_cmd   = if (ioConfig.hasSdNative) Some(master(TriState(Bool()))) else None
    val sd_dat_0 = if (ioConfig.hasSdNative) Some(master(TriState(Bool()))) else None
    val sd_dat_1 = if (ioConfig.hasSdNative) Some(master(TriState(Bool()))) else None
    val sd_dat_2 = if (ioConfig.hasSdNative) Some(master(TriState(Bool()))) else None
    val sd_dat_3 = if (ioConfig.hasSdNative) Some(master(TriState(Bool()))) else None
    val sd_cd    = if (ioConfig.hasSdNative) Some(in Bool()) else None

    // SD SPI (optional, unidirectional)
    val sd_spi_clk  = if (ioConfig.hasSdSpi) Some(out Bool()) else None
    val sd_spi_mosi = if (ioConfig.hasSdSpi) Some(out Bool()) else None
    val sd_spi_miso = if (ioConfig.hasSdSpi) Some(in Bool()) else None
    val sd_spi_cs   = if (ioConfig.hasSdSpi) Some(out Bool()) else None
    val sd_spi_cd   = if (ioConfig.hasSdSpi) Some(in Bool()) else None
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
  // Ethernet PLL (125 MHz for GMII TX, only when ethGmii)
  // ========================================================================

  val ethPll = if (ioConfig.ethGmii) Some({
    val p = EthPll()
    p.io.inclk0 := io.clk_in
    p
  }) else None

  // ========================================================================
  // Ethernet Clock Domains
  // ========================================================================

  // Ethernet clock domains: ASYNC (not BOOT) with synchronized system reset.
  // MacEth.copy(reset=...) adds its own reset (system+flush); BOOT would
  // forbid the added reset wire. The synchronized reset also ensures init()
  // works correctly in the txArea/rxArea pin-registration code.

  // TX clock: PLL 125 MHz (GMII) or PHY e_txc 25 MHz (MII)
  val ethTxClk = if (ioConfig.ethGmii) ethPll.get.io.c0 else if (ioConfig.hasEth) io.e_txc.get else null

  val ethTxCd = if (ioConfig.hasEth) Some({
    val txBootCd = ClockDomain(ethTxClk, config = ClockDomainConfig(resetKind = BOOT))
    val txReset = ResetCtrl.asyncAssertSyncDeassert(
      input = resetGen.int_res,
      clockDomain = txBootCd,
      inputPolarity = HIGH,
      outputPolarity = HIGH
    )
    ClockDomain(
      clock = ethTxClk,
      reset = txReset,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )
  }) else None

  // RX clock: PHY e_rxc (source-synchronous with RX data)
  // GMII: PHY drives data ON rising edge of e_rxc; to get setup time we
  //        sample on the FALLING edge (invert the clock). This gives ~4ns
  //        of setup margin at 125 MHz (half period).
  // MII:  e_rxc = 25 MHz, plenty of margin, use rising edge as-is.
  val ethRxClk = if (ioConfig.ethGmii) !io.e_rxc.get else if (ioConfig.hasEth) io.e_rxc.get else null

  val ethRxCd = if (ioConfig.hasEth) Some({
    val rxBootCd = ClockDomain(ethRxClk, config = ClockDomainConfig(resetKind = BOOT))
    val rxReset = ResetCtrl.asyncAssertSyncDeassert(
      input = resetGen.int_res,
      clockDomain = rxBootCd,
      inputPolarity = HIGH,
      outputPolarity = HIGH
    )
    ClockDomain(
      clock = ethRxClk,
      reset = rxReset,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )
  }) else None

  // ========================================================================
  // VGA Clock Domain (25 MHz from PLL c3, only when VGA is present)
  // ========================================================================

  val vgaCd = if (ioConfig.hasVga) Some({
    val vgaBootCd = ClockDomain(pll.io.c3, config = ClockDomainConfig(resetKind = BOOT))
    val vgaReset = ResetCtrl.asyncAssertSyncDeassert(
      input = resetGen.int_res,
      clockDomain = vgaBootCd,
      inputPolarity = HIGH,
      outputPolarity = HIGH
    )
    ClockDomain(
      clock = pll.io.c3,
      reset = vgaReset,
      config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
    )
  }) else None

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
        ioConfig = ioConfig
      ),
      debugConfig = debugConfig,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(Seq.fill(2048)(BigInt(0))),
      ethTxCd = ethTxCd,
      ethRxCd = ethRxCd,
      vgaCd = vgaCd
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

    if (ioConfig.hasEth) {
      val dataWidth = ioConfig.phyDataWidth

      // GTX clock: 125 MHz from PLL (GMII) or tied low (MII)
      if (ioConfig.ethGmii) {
        io.e_gtxc.get := ethPll.get.io.c0
      } else {
        io.e_gtxc.get := False
      }

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

      // TX adapter: adds inter-frame gap then registers to PHY TX pins
      // GMII: ethTxCd = PLL 125 MHz, dataWidth = 8
      // MII:  ethTxCd = PHY TX_CLK 25 MHz, dataWidth = 4
      val txArea = new ClockingArea(ethTxCd.get) {
        val interframe = MacTxInterFrame(dataWidth)
        interframe.io.input << cluster.io.phy.get.tx
        io.e_txen.get := RegNext(interframe.io.output.valid) init(False)
        io.e_txd.get  := RegNext(interframe.io.output.fragment.data) init(0)
        io.e_txer.get := False
      }

      // RX adapter: registers PHY RX pins, detects frame boundaries, feeds MacEth
      // GMII: ethRxCd = PHY RX_CLK 125 MHz, dataWidth = 8
      // MII:  ethRxCd = PHY RX_CLK 25 MHz, dataWidth = 4
      val rxArea = new ClockingArea(ethRxCd.get) {
        // Two-stage pipeline (matches MiiRx.toRxFlow() pattern)
        val s1_dv = RegNext(io.e_rxdv.get) init(False)
        val s1_d  = RegNext(io.e_rxd.get)  init(0)
        val s1_er = RegNext(io.e_rxer.get) init(False)

        val s2_dv = RegNext(s1_dv) init(False)
        val s2_d  = RegNext(s1_d)  init(0)
        val s2_er = RegNext(s1_er) init(False)

        val rxFlow = Flow(Fragment(PhyRx(dataWidth)))
        rxFlow.valid          := s2_dv
        rxFlow.fragment.data  := s2_d
        rxFlow.fragment.error := s2_er
        rxFlow.last           := !s1_dv && s2_dv  // Falling edge of DV = end of frame

        cluster.io.phy.get.rx << rxFlow.toStream
      }
    }

    // ==================================================================
    // VGA (optional)
    // ==================================================================

    if (ioConfig.hasVga) {
      io.vga_hs.get := cluster.io.vgaHsync.get
      io.vga_vs.get := cluster.io.vgaVsync.get
      io.vga_r.get  := cluster.io.vgaR.get
      io.vga_g.get  := cluster.io.vgaG.get
      io.vga_b.get  := cluster.io.vgaB.get
    }

    // ==================================================================
    // SD Native (optional)
    // ==================================================================

    if (ioConfig.hasSdNative) {
      io.sd_clk.get := cluster.io.sdClk.get

      // CMD line (bidirectional)
      io.sd_cmd.get.write       := cluster.io.sdCmdWrite.get
      io.sd_cmd.get.writeEnable := cluster.io.sdCmdWriteEn.get
      cluster.io.sdCmdRead.get  := io.sd_cmd.get.read

      // DAT[0..3] (per-bit tristate)
      io.sd_dat_0.get.write       := cluster.io.sdDatWrite.get(0)
      io.sd_dat_0.get.writeEnable := cluster.io.sdDatWriteEn.get(0)
      io.sd_dat_1.get.write       := cluster.io.sdDatWrite.get(1)
      io.sd_dat_1.get.writeEnable := cluster.io.sdDatWriteEn.get(1)
      io.sd_dat_2.get.write       := cluster.io.sdDatWrite.get(2)
      io.sd_dat_2.get.writeEnable := cluster.io.sdDatWriteEn.get(2)
      io.sd_dat_3.get.write       := cluster.io.sdDatWrite.get(3)
      io.sd_dat_3.get.writeEnable := cluster.io.sdDatWriteEn.get(3)
      cluster.io.sdDatRead.get    := io.sd_dat_3.get.read ## io.sd_dat_2.get.read ## io.sd_dat_1.get.read ## io.sd_dat_0.get.read

      // Card detect
      cluster.io.sdCd.get := io.sd_cd.get
    }

    // ==================================================================
    // SD SPI (optional)
    // ==================================================================

    if (ioConfig.hasSdSpi) {
      io.sd_spi_clk.get  := cluster.io.sdSpiSclk.get
      io.sd_spi_mosi.get := cluster.io.sdSpiMosi.get
      io.sd_spi_cs.get   := cluster.io.sdSpiCs.get
      cluster.io.sdSpiMiso.get := io.sd_spi_miso.get
      cluster.io.sdSpiCd.get   := io.sd_spi_cd.get
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

/**
 * Generate Verilog for JopSdramTop with DB_FPGA I/O (single-core)
 */
object JopDbFpgaTopVerilog extends App {
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
    ramInit = ramData,
    ioConfig = IoConfig.qmtechDbFpga
  )))

  println("Generated: spinalhdl/generated/JopSdramTop.v (DB_FPGA I/O)")
}

/**
 * Generate Verilog for JopSdramTop with DB_FPGA VGA DMA I/O (single-core)
 */
object JopDbFpgaVgaDmaTopVerilog extends App {
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
    ramInit = ramData,
    ioConfig = IoConfig.qmtechDbFpgaVgaDma
  )))

  println("Generated: spinalhdl/generated/JopSdramTop.v (DB_FPGA VGA DMA)")
}

/**
 * Generate Verilog for JopSdramTop with DB_FPGA I/O in SMP mode
 */
object JopSmpDbFpgaTopVerilog extends App {
  val cpuCnt = if (args.length > 0) args(0).toInt else 2

  val romFilePath = "asm/generated/serial/mem_rom.dat"
  val ramFilePath = "asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Generating $cpuCnt-core SMP DB_FPGA Verilog...")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(80 MHz)
  ).generate(InOutWrapper(JopSdramTop(
    cpuCnt = cpuCnt,
    romInit = romData,
    ramInit = ramData,
    ioConfig = IoConfig.qmtechDbFpga
  )))

  println(s"Generated: spinalhdl/generated/JopSmpSdramTop.v ($cpuCnt cores, DB_FPGA I/O)")
}
