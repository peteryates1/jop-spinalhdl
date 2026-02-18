package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.io.{BmbSys, BmbUart}
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

/**
 * JOP BRAM FPGA Top-Level for QMTECH EP4CGX150
 *
 * Runs JOP processor with BRAM-backed memory and real UART output.
 * Uses PLL to run at 100 MHz from 50 MHz input clock.
 *
 * @param romInit Microcode ROM initialization data
 * @param ramInit Stack RAM initialization data
 * @param mainMemInit Main memory initialization data (from .jop file)
 */
case class JopBramTop(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  mainMemSize: Int = 32 * 1024
) extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val led     = out Bits(2 bits)
  }

  // Remove auto-generated clock/reset - we manage our own
  noIoPrefix()

  // ========================================================================
  // PLL: 50 MHz -> 100 MHz system clock
  // ========================================================================

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

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
  // Main Design Area
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = mainMemSize)
    )

    // Extract JBC init from main memory (same logic as JopCoreTestHarness)
    val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
    val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
    val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
    val bootCodeStart = (bootMethodStartLen >> 10).toInt
    val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
    val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

    // Convert words to bytes (big-endian)
    val jbcInit = bytecodeWords.flatMap { word =>
      val w = word.toLong & 0xFFFFFFFFL
      Seq(
        BigInt((w >> 24) & 0xFF),
        BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF),
        BigInt((w >> 0) & 0xFF)
      )
    }.padTo(2048, BigInt(0))

    // JOP System core
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

    // Interrupts (disabled)
    jopCore.io.irq := False
    jopCore.io.irqEna := False

    // ======================================================================
    // I/O Slaves
    // ======================================================================

    val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
    val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

    // System I/O (slave 0)
    val bmbSys = BmbSys(clkFreqHz = 100000000L)
    bmbSys.io.addr := ioSubAddr
    bmbSys.io.rd := jopCore.io.ioRd && ioSlaveId === 0
    bmbSys.io.wr := jopCore.io.ioWr && ioSlaveId === 0
    bmbSys.io.wrData := jopCore.io.ioWrData

    // UART (slave 1)
    val bmbUart = BmbUart()
    bmbUart.io.addr := ioSubAddr
    bmbUart.io.rd := jopCore.io.ioRd && ioSlaveId === 1
    bmbUart.io.wr := jopCore.io.ioWr && ioSlaveId === 1
    bmbUart.io.wrData := jopCore.io.ioWrData
    io.ser_txd := bmbUart.io.txd
    bmbUart.io.rxd := True  // No RX in BRAM top

    // I/O read mux
    val ioRdData = Bits(32 bits)
    ioRdData := 0
    switch(ioSlaveId) {
      is(0) { ioRdData := bmbSys.io.rdData }
      is(1) { ioRdData := bmbUart.io.rdData }
    }
    jopCore.io.ioRdData := ioRdData

    // Exception signal from BmbSys
    jopCore.io.exc := bmbSys.io.exc

    // ======================================================================
    // LED Driver
    // ======================================================================

    // LEDs from watchdog register (active low on QMTECH board)
    io.led := ~bmbSys.io.wd(1 downto 0)
  }
}

/**
 * Generate Verilog for JopBramTop
 */
object JopBramTopVerilog extends App {
  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopBramTop(romData, ramData, mainMemData))

  println("Generated: generated/JopBramTop.v")
}

/**
 * Generate Verilog for JopBramTop with Small GC app (128KB BRAM)
 * Used to isolate SDRAM-specific issues by testing GC on BRAM FPGA.
 */
object JopBramGcTopVerilog extends App {
  val jopFilePath = "/home/peter/workspaces/ai/jop/java/apps/Small/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"
  val memSize = 128 * 1024  // 128KB BRAM for GC heap

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memSize / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopBramTop(romData, ramData, mainMemData, mainMemSize = memSize))

  println("Generated: generated/JopBramTop.v (GC variant)")
}

/**
 * JOP BRAM FPGA Top-Level with Serial Boot
 *
 * Same pinout as JopSdramTop but uses BRAM instead of SDRAM.
 * Serial-boot microcode downloads .jop via UART RX into uninitialised BRAM.
 * Isolates whether the GC hang is caused by serial download vs SDRAM issues.
 */
case class JopBramSerialTop(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemSize: Int = 128 * 1024
) extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val ser_rxd = in Bool()
    val led     = out Bits(2 bits)
  }

  noIoPrefix()

  // PLL: 50 MHz -> 100 MHz system clock
  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  // Reset Generator
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

  val mainArea = new ClockingArea(mainClockDomain) {

    // Serial-boot config: serial jump table, no burst (BRAM)
    val config = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = mainMemSize),
      jumpTable = JumpTableInitData.serial
    )

    // JBC init: empty — BC_FILL loads bytecodes from BRAM after download
    val jbcInit = Seq.fill(2048)(BigInt(0))

    // JOP core
    val jopCore = JopCore(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )

    // Uninitialised BRAM with BMB interface
    val ram = BmbOnChipRam(
      p = config.memConfig.bmbParameter,
      size = config.memConfig.mainMemSize,
      hexInit = null
    )

    // Connect BMB
    ram.io.bus << jopCore.io.bmb

    // Drive debug RAM port (unused)
    jopCore.io.debugRamAddr := 0

    // Interrupts (disabled)
    jopCore.io.irq := False
    jopCore.io.irqEna := False

    // I/O Slaves
    val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
    val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

    // System I/O (slave 0)
    val bmbSys = BmbSys(clkFreqHz = 100000000L)
    bmbSys.io.addr := ioSubAddr
    bmbSys.io.rd := jopCore.io.ioRd && ioSlaveId === 0
    bmbSys.io.wr := jopCore.io.ioWr && ioSlaveId === 0
    bmbSys.io.wrData := jopCore.io.ioWrData

    // UART (slave 1) — TX + RX for serial download
    val bmbUart = BmbUart()
    bmbUart.io.addr := ioSubAddr
    bmbUart.io.rd := jopCore.io.ioRd && ioSlaveId === 1
    bmbUart.io.wr := jopCore.io.ioWr && ioSlaveId === 1
    bmbUart.io.wrData := jopCore.io.ioWrData
    io.ser_txd := bmbUart.io.txd
    bmbUart.io.rxd := io.ser_rxd

    // I/O read mux
    val ioRdData = Bits(32 bits)
    ioRdData := 0
    switch(ioSlaveId) {
      is(0) { ioRdData := bmbSys.io.rdData }
      is(1) { ioRdData := bmbUart.io.rdData }
    }
    jopCore.io.ioRdData := ioRdData

    // Exception signal from BmbSys
    jopCore.io.exc := bmbSys.io.exc

    // LEDs from watchdog register (active low on QMTECH board)
    io.led := ~bmbSys.io.wd(1 downto 0)
  }
}

/**
 * Generate Verilog for JopBramSerialTop (serial-boot BRAM, 128KB)
 */
object JopBramSerialTopVerilog extends App {
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/serial/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopBramSerialTop(romData, ramData))

  println("Generated: generated/JopBramSerialTop.v")
}
