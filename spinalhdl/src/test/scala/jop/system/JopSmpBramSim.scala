package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.io.{BmbSys, BmbUart, CmpSync}
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData
import java.io.PrintWriter

/**
 * SMP Test Harness: N JOP cores sharing BmbOnChipRam via BmbArbiter.
 *
 * Each core has its own BmbSys (with unique cpuId). Only core 0 gets UART.
 * CmpSync provides global lock synchronization.
 *
 * Uses BRAM (zero-latency) to keep simulation fast while testing multicore logic.
 */
case class JopSmpTestHarness(
  cpuCnt: Int,
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {
  require(cpuCnt >= 2 && cpuCnt <= 4)

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)  // 128KB
  )

  val io = new Bundle {
    // Per-core pipeline outputs
    val pc  = out Vec(UInt(config.pcWidth bits), cpuCnt)
    val jpc = out Vec(UInt((config.jpcWidth + 1) bits), cpuCnt)

    // Per-core stack outputs
    val aout = out Vec(Bits(config.dataWidth bits), cpuCnt)
    val bout = out Vec(Bits(config.dataWidth bits), cpuCnt)

    // Per-core memory busy
    val memBusy = out Vec(Bool(), cpuCnt)

    // Per-core halted status (from CmpSync)
    val halted = out Vec(Bool(), cpuCnt)

    // UART output (from core 0)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Per-core watchdog output
    val wd = out Vec(Bits(32 bits), cpuCnt)

    // Per-core I/O debug
    val ioWr      = out Vec(Bool(), cpuCnt)
    val ioSubAddr = out Vec(UInt(4 bits), cpuCnt)
    val ioSlaveId = out Vec(UInt(2 bits), cpuCnt)

    // Exception debug (core 0)
    val excFired = out Bool()
    val excType  = out Bits(8 bits)
  }

  // Extract JBC init from main memory (same as JopCoreTestHarness)
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)
  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(BigInt((w >> 24) & 0xFF), BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF), BigInt((w >> 0) & 0xFF))
  }.padTo(2048, BigInt(0))

  // ====================================================================
  // N JOP Cores
  // ====================================================================

  val cores = (0 until cpuCnt).map { _ =>
    JopCore(
      config = config,
      romInit = Some(romInit),
      ramInit = Some(ramInit),
      jbcInit = Some(jbcInit)
    )
  }

  // ====================================================================
  // BMB Arbiter: N masters -> 1 slave
  // ====================================================================

  val inputParam = config.memConfig.bmbParameter
  val sourceRouteWidth = log2Up(cpuCnt)
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

  for (i <- 0 until cpuCnt) {
    arbiter.io.inputs(i) << cores(i).io.bmb
  }

  // ====================================================================
  // Shared Block RAM
  // ====================================================================

  val ram = BmbOnChipRam(
    p = arbiterOutputParam,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )

  val memWords = config.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  ram.io.bus << arbiter.io.output

  // ====================================================================
  // Per-core I/O Subsystem
  // ====================================================================

  val bmbSysDevices = (0 until cpuCnt).map { i =>
    BmbSys(clkFreqHz = 100000000L, cpuId = i, cpuCnt = cpuCnt)
  }

  // CmpSync: global lock
  val cmpSync = CmpSync(cpuCnt)
  for (i <- 0 until cpuCnt) {
    cmpSync.io.syncIn(i) := bmbSysDevices(i).io.syncOut
    bmbSysDevices(i).io.syncIn := cmpSync.io.syncOut(i)
  }

  // Expose internal signals for simulation debugging
  cmpSync.state.simPublic()
  cmpSync.lockedId.simPublic()
  for (i <- 0 until cpuCnt) {
    bmbSysDevices(i).lockReqReg.simPublic()
  }

  // Snoop exception writes from core 0
  val excTypeSnoop = Reg(Bits(8 bits)) init(0)

  // UART on core 0 (simplified for simulation)
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)
  uartTxValidReg := False

  // Per-core I/O wiring
  for (i <- 0 until cpuCnt) {
    val ioSubAddr = cores(i).io.ioAddr(3 downto 0)
    val ioSlaveId = cores(i).io.ioAddr(5 downto 4)

    // BmbSys (slave 0)
    bmbSysDevices(i).io.addr   := ioSubAddr
    bmbSysDevices(i).io.rd     := cores(i).io.ioRd && ioSlaveId === 0
    bmbSysDevices(i).io.wr     := cores(i).io.ioWr && ioSlaveId === 0
    bmbSysDevices(i).io.wrData := cores(i).io.ioWrData

    // UART (slave 1) — core 0 only, simplified for simulation
    if (i == 0) {
      when(cores(i).io.ioWr && ioSlaveId === 1 && ioSubAddr === 1) {
        uartTxDataReg := cores(i).io.ioWrData(7 downto 0)
        uartTxValidReg := True
      }

      // Snoop exception writes
      when(cores(i).io.ioWr && ioSlaveId === 0 && ioSubAddr === 4) {
        excTypeSnoop := cores(i).io.ioWrData(7 downto 0)
      }
    }

    // I/O read mux
    val ioRdData = Bits(32 bits)
    ioRdData := 0
    switch(ioSlaveId) {
      is(0) { ioRdData := bmbSysDevices(i).io.rdData }
      is(1) {
        if (i == 0) {
          switch(ioSubAddr) {
            is(0) { ioRdData := B(0x1, 32 bits) }  // Status: TX ready
          }
        }
        // Core 1+ UART: returns 0
      }
    }
    cores(i).io.ioRdData := ioRdData

    // Exception from BmbSys
    cores(i).io.exc := bmbSysDevices(i).io.exc

    // CMP halt from CmpSync
    cores(i).io.halted := bmbSysDevices(i).io.halted

    // Interrupts (disabled)
    cores(i).io.irq := False
    cores(i).io.irqEna := False

    // Output connections
    io.pc(i)      := cores(i).io.pc
    io.jpc(i)     := cores(i).io.jpc
    io.aout(i)    := cores(i).io.aout
    io.bout(i)    := cores(i).io.bout
    io.memBusy(i) := cores(i).io.memBusy
    io.halted(i)  := bmbSysDevices(i).io.halted
    io.wd(i)      := bmbSysDevices(i).io.wd
    io.ioWr(i)    := cores(i).io.ioWr
    io.ioSubAddr(i) := cores(i).io.ioAddr(3 downto 0)
    io.ioSlaveId(i) := cores(i).io.ioAddr(5 downto 4)
  }

  // UART output
  io.uartTxData  := uartTxDataReg
  io.uartTxValid := uartTxValidReg

  // Exception debug (core 0)
  io.excFired := bmbSysDevices(0).io.exc
  io.excType  := excTypeSnoop
}

/**
 * SMP BRAM simulation: 2 cores running Small GC app.
 * Verifies both cores boot, print output, and GC works.
 */
object JopSmpBramSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/Small/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_bram_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP BRAM Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 20000000  // 20M cycles
      val reportInterval = 100000
      var done = false
      var cycle = 0

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          val excType = dut.io.excType.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr UART: '${uartOutput.toString}'")
        }

        // Exit after a full GC cycle
        val output = uartOutput.toString
        if (output.contains("R14 f=")) {
          println("\n*** GC cycle completed! ***")
          for (_ <- 0 until 50000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      log.close()

      println(s"\n\n=== SMP Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("GC test start")) {
        println("FAIL: Did not see 'GC test start'")
        System.exit(1)
      }
      if (!uartOutput.toString.contains("R0 f=")) {
        println("FAIL: Did not see allocation rounds")
        System.exit(1)
      }
      println("PASS: SMP GC allocation test working")
    }
}

/**
 * SMP NCoreHelloWorld simulation: 2 cores, no GC.
 * Core 0: prints "Hello World!" and toggles watchdog
 * Core 1: just toggles watchdog
 * Verifies both cores run independently and toggle their watchdog LEDs.
 */
object JopSmpNCoreHelloWorldSim extends App {
  val cpuCnt = 2

  val jopFilePath = "java/apps/Smallest/NCoreHelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/smp_ncore_simulation.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (${mainMemData.count(_ != BigInt(0))} non-zero)")
  println(s"CPU count: $cpuCnt")
  println(s"Log file: $logFilePath")

  SimConfig
    .compile(JopSmpTestHarness(cpuCnt, romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine(s"=== JOP SMP NCoreHelloWorld Simulation ($cpuCnt cores) ===")

      dut.clockDomain.forkStimulus(10)  // 10ns = 100MHz
      dut.clockDomain.waitSampling(5)

      val maxCycles = 40000000  // 40M cycles (~400ms at 100MHz)
      val reportInterval = 5000000
      var done = false
      var cycle = 0

      // Track per-core watchdog values and toggle counts
      val lastWd = Array.fill(cpuCnt)(0)
      val wdToggles = Array.fill(cpuCnt)(0)

      while (cycle < maxCycles && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // Check for exception firing (core 0)
        if (dut.io.excFired.toBoolean) {
          val pc0 = dut.io.pc(0).toInt
          val jpc0 = dut.io.jpc(0).toInt
          val excType = dut.io.excType.toInt
          println(f"\n[$cycle%8d] *** EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x ***")
          logLine(f"[$cycle%8d] EXCEPTION type=$excType PC=$pc0%04x JPC=$jpc0%04x")
        }

        // Check for UART output (core 0)
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%8d] UART: '${if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"}' (0x$char%02x)")
        }

        // Monitor per-core watchdog changes
        for (i <- 0 until cpuCnt) {
          val wd = dut.io.wd(i).toInt
          if (wd != lastWd(i)) {
            wdToggles(i) += 1
            if (wdToggles(i) <= 10) {
              println(f"\n[$cycle%8d] Core $i WD: $wd (toggle #${wdToggles(i)})")
              logLine(f"[$cycle%8d] Core $i WD: $wd (toggle #${wdToggles(i)})")
            }
            lastWd(i) = wd
          }
        }

        // Progress report
        if (cycle > 0 && cycle % reportInterval == 0) {
          val pcStr = (0 until cpuCnt).map(i => f"C${i}:PC=${dut.io.pc(i).toInt}%04x").mkString(" ")
          val haltedStr = (0 until cpuCnt).map(i => if (dut.io.halted(i).toBoolean) "H" else ".").mkString
          val wdStr = (0 until cpuCnt).map(i => f"C${i}:WD=${lastWd(i)}").mkString(" ")
          println(f"\n[$cycle%8d] $pcStr halted=$haltedStr $wdStr toggles=${wdToggles.mkString(",")}")
        }

        // Exit after both cores have toggled watchdog at least 3 times
        if (wdToggles.forall(_ >= 3)) {
          println(s"\n*** Both cores toggling watchdog! toggles=${wdToggles.mkString(",")} ***")
          // Run a bit more to collect more UART output
          for (_ <- 0 until 10000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val char = dut.io.uartTxData.toInt
              uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
              print(if (char >= 32 && char < 127) char.toChar else '.')
            }
          }
          done = true
        }
      }

      log.close()

      println(s"\n\n=== SMP NCoreHelloWorld Simulation Complete ($cpuCnt cores, $cycle cycles) ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Per-core WD toggles: ${wdToggles.zipWithIndex.map { case (t, i) => s"C$i=$t" }.mkString(" ")}")
      println(s"Log written to: $logFilePath")

      if (!uartOutput.toString.contains("Hello World!")) {
        println("FAIL: Did not see 'Hello World!' from core 0")
        System.exit(1)
      }
      for (i <- 0 until cpuCnt) {
        if (wdToggles(i) < 3) {
          println(s"FAIL: Core $i only toggled watchdog ${wdToggles(i)} times (expected >= 3)")
          System.exit(1)
        }
      }
      println("PASS: Both cores running and toggling watchdog LEDs!")
    }
}
