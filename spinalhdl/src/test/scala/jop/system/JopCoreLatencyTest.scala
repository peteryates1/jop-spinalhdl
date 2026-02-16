package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.{JopMemoryConfig, BmbLatencyBridge}

/**
 * Test harness for JopCore with configurable extra BMB response latency.
 *
 * Inserts a BmbLatencyBridge between the JopCore BMB master and BmbOnChipRam:
 *   JopCore → BmbLatencyBridge(extraLatency) → BmbOnChipRam
 *
 * extraLatency=0 is identical to the standard BRAM test harness.
 */
case class JopCoreLatencyHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  extraLatency: Int = 0
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 32 * 1024)  // 32KB
  )

  val io = new Bundle {
    val pc        = out UInt(config.pcWidth bits)
    val jpc       = out UInt((config.jpcWidth + 1) bits)
    val memBusy   = out Bool()
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()
    val debugState  = out UInt(4 bits)
    // BMB transaction monitoring
    val bmbCmdFire  = out Bool()
    val bmbCmdAddr  = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdWrite = out Bool()
    val bmbCmdData  = out Bits(32 bits)
    val bmbRspFire  = out Bool()
    val bmbRspData  = out Bits(32 bits)
    // Extra debug signals
    val ir          = out Bits(10 bits)
    val aout        = out Bits(32 bits)
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
  }

  // JBC starts empty - BC_FILL must load bytecodes from memory
  val jbcInit = Seq.fill(2048)(BigInt(0))

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

  // Insert latency bridge between JopCore and RAM
  if (extraLatency == 0) {
    ram.io.bus << jopCore.io.bmb
  } else {
    val bridge = BmbLatencyBridge(config.memConfig.bmbParameter, extraLatency)
    bridge.io.input << jopCore.io.bmb
    ram.io.bus << bridge.io.output
  }

  // I/O simulation (same as JopCoreTestHarness)
  val sysCntReg = Reg(UInt(32 bits)) init(1000000)
  sysCntReg := sysCntReg + 10

  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  val ioRdData = Bits(32 bits)
  ioRdData := 0

  val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
  val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

  switch(ioSlaveId) {
    is(0) {
      switch(ioSubAddr) {
        is(0) { ioRdData := sysCntReg.asBits }
        is(1) { ioRdData := sysCntReg.asBits }
        is(6) { ioRdData := B(0, 32 bits) }
        is(7) { ioRdData := B(0, 32 bits) }
      }
    }
    is(1) {
      switch(ioSubAddr) {
        is(0) { ioRdData := B(0x1, 32 bits) }  // UART TX ready
      }
    }
  }
  jopCore.io.ioRdData := ioRdData

  uartTxValidReg := False
  when(jopCore.io.ioWr) {
    switch(ioSlaveId) {
      is(1) {
        switch(ioSubAddr) {
          is(1) {
            uartTxDataReg := jopCore.io.ioWrData(7 downto 0)
            uartTxValidReg := True
          }
        }
      }
    }
  }

  jopCore.io.irq := False
  jopCore.io.irqEna := False

  // Debug RAM port - tie off
  jopCore.io.debugRamAddr := 0

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.debugState := jopCore.io.debugMemState

  // BMB transaction monitoring
  io.bmbCmdFire := jopCore.io.bmb.cmd.fire
  io.bmbCmdAddr := jopCore.io.bmb.cmd.fragment.address
  io.bmbCmdWrite := jopCore.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
  io.bmbCmdData := jopCore.io.bmb.cmd.fragment.data
  io.bmbRspFire := jopCore.io.bmb.rsp.fire
  io.bmbRspData := jopCore.io.bmb.rsp.fragment.data
  io.ir := jopCore.io.instr
  io.aout := jopCore.io.aout
  io.bmbCmdValid := jopCore.io.bmb.cmd.valid
  io.bmbCmdReady := jopCore.io.bmb.cmd.ready
}

/**
 * Latency sweep simulation.
 *
 * Runs the full JopCore at multiple extra-latency values and reports
 * which ones produce "Hello World!" UART output.
 */
object JopCoreLatencySweep extends App {

  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length}, RAM: ${ramData.length}, Main: ${mainMemData.length}")

  val latencies = Seq(0, 1, 2, 3, 5)
  val maxCycles = 200000
  val bmbLogStartCycle = 33000
  val bmbLogMaxEntries = 50

  case class RunResult(
    latency: Int,
    uartOutput: String,
    bmbLog: Seq[String],
    success: Boolean
  )

  val results = scala.collection.mutable.ArrayBuffer[RunResult]()

  for (lat <- latencies) {
    println(s"\n${"=" * 60}")
    println(s"Running with extraLatency=$lat")
    println(s"${"=" * 60}")

    SimConfig
      .compile(JopCoreLatencyHarness(romData, ramData, mainMemData, extraLatency = lat))
      .doSim { dut =>
        val uartOutput = new StringBuilder
        val bmbLog = scala.collection.mutable.ArrayBuffer[String]()
        var bmbLogCount = 0

        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          // Capture UART output
          if (dut.io.uartTxValid.toBoolean) {
            val ch = dut.io.uartTxData.toInt
            if (ch >= 32 && ch < 127) uartOutput.append(ch.toChar)
            else if (ch == 10) uartOutput.append('\n')
            else if (ch == 13) { /* skip CR */ }
            else uartOutput.append(f"\\x$ch%02x")
          }

          // Log BMB transactions after the start cycle
          if (cycle >= bmbLogStartCycle && bmbLogCount < bmbLogMaxEntries) {
            if (dut.io.bmbCmdFire.toBoolean) {
              val addr = dut.io.bmbCmdAddr.toLong
              val isWrite = dut.io.bmbCmdWrite.toBoolean
              val data = dut.io.bmbCmdData.toLong & 0xFFFFFFFFL
              val op = if (isWrite) "WR" else "RD"
              val entry = f"[$cycle%6d] CMD $op addr=0x$addr%08x data=0x$data%08x"
              bmbLog += entry
              bmbLogCount += 1
            }
            if (dut.io.bmbRspFire.toBoolean) {
              val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
              val entry = f"[$cycle%6d] RSP data=0x$data%08x"
              bmbLog += entry
              bmbLogCount += 1
            }
          }

          // Progress
          if (cycle > 0 && cycle % 50000 == 0) {
            println(f"  [$cycle%6d] UART: '${uartOutput.toString.take(40)}...'")
          }
        }

        val output = uartOutput.toString
        val success = output.contains("Hello World!")
        results += RunResult(lat, output, bmbLog.toSeq, success)

        println(s"  UART output (${output.length} chars): '${output.take(80)}${if (output.length > 80) "..." else ""}'")
        println(s"  Result: ${if (success) "PASS" else "FAIL"}")
      }
  }

  // Summary
  println(s"\n${"=" * 60}")
  println("LATENCY SWEEP SUMMARY")
  println(s"${"=" * 60}")
  for (r <- results) {
    val status = if (r.success) "PASS" else "FAIL"
    val preview = r.uartOutput.take(40).replace("\n", "\\n")
    println(f"  extraLatency=${r.latency}%d: $status%-4s  UART='$preview'")
  }

  // Find first failing latency for divergence analysis
  val firstFail = results.find(!_.success)
  val baseline = results.find(_.success)

  if (firstFail.isDefined && baseline.isDefined) {
    println(s"\nFirst failing latency: ${firstFail.get.latency}")
    println(s"\nBMB log for extraLatency=${firstFail.get.latency} (first ${firstFail.get.bmbLog.size} entries after cycle $bmbLogStartCycle):")
    firstFail.get.bmbLog.foreach(println)

    println(s"\nBMB log for extraLatency=${baseline.get.latency} (baseline):")
    baseline.get.bmbLog.foreach(println)
  } else if (firstFail.isEmpty) {
    println("\nAll latencies PASSED - bug is likely in BmbSdramCtrl32, not latency-related")
  } else {
    println("\nAll latencies FAILED - something else is broken")
  }
}

/**
 * Comparative debug: runs extraLatency=0 and extraLatency=1, captures
 * the full sequence of BMB transactions (op, addr, data), and finds
 * the first divergence point.
 */
object JopCoreLatencyDebug extends App {

  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 32 * 1024 / 4)

  case class BmbTxn(cycle: Int, isCmd: Boolean, isWrite: Boolean, addr: Long, data: Long,
                    pc: Int, ir: Int, memState: Int, aout: Long)

  def runSim(lat: Int, maxCycles: Int): (Seq[BmbTxn], String) = {
    var txns = scala.collection.mutable.ArrayBuffer[BmbTxn]()
    var uart = new StringBuilder

    SimConfig
      .compile(JopCoreLatencyHarness(romData, ramData, mainMemData, extraLatency = lat))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          if (dut.io.uartTxValid.toBoolean) {
            val ch = dut.io.uartTxData.toInt
            if (ch >= 32 && ch < 127) uart.append(ch.toChar)
            else if (ch == 10) uart.append('\n')
            else if (ch == 13) { }
          }

          val pc = dut.io.pc.toInt
          val ir = dut.io.ir.toInt
          val memSt = dut.io.debugState.toInt
          val aout = dut.io.aout.toLong & 0xFFFFFFFFL

          if (dut.io.bmbCmdFire.toBoolean) {
            val addr = dut.io.bmbCmdAddr.toLong
            val isWr = dut.io.bmbCmdWrite.toBoolean
            val data = dut.io.bmbCmdData.toLong & 0xFFFFFFFFL
            txns += BmbTxn(cycle, isCmd = true, isWrite = isWr, addr = addr, data = data,
                           pc = pc, ir = ir, memState = memSt, aout = aout)
          }
          if (dut.io.bmbRspFire.toBoolean) {
            val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
            txns += BmbTxn(cycle, isCmd = false, isWrite = false, addr = 0, data = data,
                           pc = pc, ir = ir, memState = memSt, aout = aout)
          }
        }
      }
    (txns.toSeq, uart.toString)
  }

  val maxCycles = 10000  // Enough to pass "JOP start" and start first method

  println("=== Running extraLatency=0 (baseline) ===")
  val (txns0, uart0) = runSim(0, maxCycles)
  println(s"  ${txns0.size} BMB transactions, UART: '${uart0.take(40).replace("\n", "\\n")}'")

  println("=== Running extraLatency=1 ===")
  val (txns1, uart1) = runSim(1, maxCycles)
  println(s"  ${txns1.size} BMB transactions, UART: '${uart1.take(40).replace("\n", "\\n")}'")

  // Compare transaction sequences (ignoring cycle timing)
  // Match by (isCmd, isWrite, addr for cmds, data for rsps)
  val cmds0 = txns0.filter(_.isCmd)
  val cmds1 = txns1.filter(_.isCmd)
  val rsps0 = txns0.filter(!_.isCmd)
  val rsps1 = txns1.filter(!_.isCmd)

  println(s"\nBaseline: ${cmds0.size} cmds, ${rsps0.size} rsps")
  println(s"Latency1: ${cmds1.size} cmds, ${rsps1.size} rsps")

  // Find first divergent CMD
  val minCmds = scala.math.min(cmds0.size, cmds1.size)
  var firstDivCmd = -1
  for (i <- 0 until minCmds if firstDivCmd < 0) {
    val c0 = cmds0(i)
    val c1 = cmds1(i)
    if (c0.isWrite != c1.isWrite || c0.addr != c1.addr || (c0.isWrite && c0.data != c1.data)) {
      firstDivCmd = i
    }
  }

  if (firstDivCmd >= 0) {
    println(s"\n*** FIRST DIVERGENT CMD at index $firstDivCmd ***")
    // Show context: 5 before and 5 after
    val start = scala.math.max(0, firstDivCmd - 5)
    val end = scala.math.min(minCmds, firstDivCmd + 6)
    println(f"  ${"Idx"}%5s | ${"Lat0 cyc"}%8s ${"Op"}%2s ${"Addr"}%10s ${"Data"}%10s ${"PC"}%6s ${"IR"}%5s ${"St"}%2s ${"Aout"}%10s | ${"Lat1 cyc"}%8s ${"Op"}%2s ${"Addr"}%10s ${"Data"}%10s ${"PC"}%6s ${"IR"}%5s ${"St"}%2s ${"Aout"}%10s")
    for (i <- start until end) {
      val c0 = cmds0(i)
      val c1 = cmds1(i)
      val marker = if (i == firstDivCmd) ">>>" else "   "
      val op0 = if (c0.isWrite) "WR" else "RD"
      val op1 = if (c1.isWrite) "WR" else "RD"
      println(f"$marker$i%5d | ${c0.cycle}%8d $op0%2s 0x${c0.addr}%08x 0x${c0.data}%08x 0x${c0.pc}%04x 0x${c0.ir}%03x ${c0.memState}%2d 0x${c0.aout}%08x | ${c1.cycle}%8d $op1%2s 0x${c1.addr}%08x 0x${c1.data}%08x 0x${c1.pc}%04x 0x${c1.ir}%03x ${c1.memState}%2d 0x${c1.aout}%08x")
    }

    // Also show RSP around the same indices
    println(s"\nRSPs around CMD divergence (response index $firstDivCmd ± 5):")
    val rStart = scala.math.max(0, firstDivCmd - 5)
    val rEnd = scala.math.min(scala.math.min(rsps0.size, rsps1.size), firstDivCmd + 6)
    println(f"  ${"Idx"}%5s | ${"Lat0 cyc"}%8s ${"Data"}%10s ${"PC"}%6s ${"IR"}%5s ${"St"}%2s | ${"Lat1 cyc"}%8s ${"Data"}%10s ${"PC"}%6s ${"IR"}%5s ${"St"}%2s")
    for (i <- rStart until rEnd) {
      val r0 = rsps0(i)
      val r1 = rsps1(i)
      val marker = if (r0.data != r1.data) ">>>" else "   "
      println(f"$marker$i%5d | ${r0.cycle}%8d 0x${r0.data}%08x 0x${r0.pc}%04x 0x${r0.ir}%03x ${r0.memState}%2d | ${r1.cycle}%8d 0x${r1.data}%08x 0x${r1.pc}%04x 0x${r1.ir}%03x ${r1.memState}%2d")
    }
  } else if (cmds0.size != cmds1.size) {
    println(s"\nNo divergent CMD found in first $minCmds cmds, but counts differ (${cmds0.size} vs ${cmds1.size})")
  } else {
    println("\nAll CMDs match! Checking RSPs...")
  }

  // Find first divergent RSP
  val minRsps = scala.math.min(rsps0.size, rsps1.size)
  var firstDivRsp = -1
  for (i <- 0 until minRsps if firstDivRsp < 0) {
    if (rsps0(i).data != rsps1(i).data) {
      firstDivRsp = i
    }
  }

  if (firstDivRsp >= 0) {
    println(s"\n*** FIRST DIVERGENT RSP at index $firstDivRsp ***")
    val start = scala.math.max(0, firstDivRsp - 3)
    val end = scala.math.min(minRsps, firstDivRsp + 4)
    for (i <- start until end) {
      val r0 = rsps0(i)
      val r1 = rsps1(i)
      val marker = if (i == firstDivRsp) ">>>" else "   "
      println(f"$marker$i%5d | lat0: cyc=${r0.cycle}%d data=0x${r0.data}%08x pc=0x${r0.pc}%04x ir=0x${r0.ir}%03x st=${r0.memState} | lat1: cyc=${r1.cycle}%d data=0x${r1.data}%08x pc=0x${r1.pc}%04x ir=0x${r1.ir}%03x st=${r1.memState}")
    }
  } else {
    println(s"\nAll RSPs match in first $minRsps responses!")
  }
}
