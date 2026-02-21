package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.{JopFileLoader, TestHistory}
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Debug harness for JopCore BRAM simulation
 * Exposes additional debug signals for tracing BMB transactions
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 */
case class JopCoreBramDebugHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)
  )

  val io = new Bundle {
    // Pipeline outputs
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val instr = out Bits(config.instrWidth bits)
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout = out Bits(config.dataWidth bits)
    val bout = out Bits(config.dataWidth bits)

    // Memory status
    val memBusy = out Bool()

    // UART output (from JopCore debug snoop)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // BMB debug signals
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
    val bmbCmdAddress = out UInt(32 bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid = out Bool()
    val bmbRspReady = out Bool()
    val bmbRspData = out Bits(32 bits)

    // Memory controller debug
    val memCtrlState = out UInt(5 bits)
    val bcRd = out Bool()  // stbcrd signal
    val memRd = out Bool()  // stmra signal
    val memRdc = out Bool() // stmrac signal
    val memWr = out Bool()  // no longer available with internal I/O
    val memAddrWr = out Bool()  // stmwa signal

    // RAM slot debug (mp register at slot 0)
    val mpRegValue = out Bits(32 bits)
  }

  // Extract JBC init from main memory
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

  // JOP Core (BmbSys + BmbUart internal)
  val jopSystem = JopCore(
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
  ram.io.bus << jopSystem.io.bmb

  // Single-core: no CmpSync
  jopSystem.io.syncIn.halted := False
  jopSystem.io.syncIn.s_out := False

  // No UART RX in debug harness
  jopSystem.io.rxd := True

  // Interrupts disabled
  jopSystem.io.irq := False
  jopSystem.io.irqEna := False

  // Pipeline outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch
  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := jopSystem.io.uartTxData
  io.uartTxValid := jopSystem.io.uartTxValid

  // BMB debug signals
  io.bmbCmdValid := jopSystem.io.bmb.cmd.valid
  io.bmbCmdReady := jopSystem.io.bmb.cmd.ready
  io.bmbCmdAddress := jopSystem.io.bmb.cmd.fragment.address.resized
  io.bmbCmdOpcode := jopSystem.io.bmb.cmd.fragment.opcode
  io.bmbRspValid := jopSystem.io.bmb.rsp.valid
  io.bmbRspReady := jopSystem.io.bmb.rsp.ready
  io.bmbRspData := jopSystem.io.bmb.rsp.fragment.data

  // Memory controller debug - use exposed signals
  io.memCtrlState := jopSystem.io.debugMemState
  io.bcRd := jopSystem.io.debugBcRd
  io.memRd := jopSystem.io.debugRd
  io.memRdc := jopSystem.io.debugRdc
  io.memWr := False  // I/O write no longer visible with internal I/O
  io.memAddrWr := jopSystem.io.debugAddrWr

  // Read mp register (RAM slot 0)
  jopSystem.io.debugRamAddr := 0
  io.mpRegValue := jopSystem.io.debugRamData
}

/**
 * Debug simulation to trace BMB transactions
 */
object JopCoreBramDebug extends App {

  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"
  val logFilePath = "spinalhdl/bram_debug.log"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries")
  println(s"Log file: $logFilePath")

  val run = TestHistory.startRun("JopCoreBramDebug", "sim-verilator", jopFilePath, romFilePath, ramFilePath)

  SimConfig
    .compile(JopCoreBramDebugHarness(romData, ramData, mainMemData))
    .doSim { dut =>
      val log = new PrintWriter(logFilePath)
      var uartOutput = new StringBuilder
      var lastPc = -1
      var lastJpc = -1
      var lastState = -1
      var bmbTxCount = 0

      def logLine(msg: String): Unit = {
        log.println(msg)
        log.flush()
      }

      logLine("=== JOP BRAM Debug Simulation ===")
      logLine(s"ROM: ${romData.length}, RAM: ${ramData.length}, Main: ${mainMemData.length}")
      logLine("")
      logLine("Columns: cycle | PC | JPC | memState | BMB cmd/rsp | aout | bout")
      logLine("-" * 100)

      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 10000  // Extended trace for full boot

      for (cycle <- 0 until maxCycles) {
        dut.clockDomain.waitSampling()

        val pc = dut.io.pc.toInt
        val jpc = dut.io.jpc.toInt
        val memState = dut.io.memCtrlState.toInt
        val memBusy = dut.io.memBusy.toBoolean
        val aout = dut.io.aout.toLong
        val bout = dut.io.bout.toLong

        // BMB signals
        val cmdValid = dut.io.bmbCmdValid.toBoolean
        val cmdReady = dut.io.bmbCmdReady.toBoolean
        val cmdFire = cmdValid && cmdReady
        val cmdAddr = dut.io.bmbCmdAddress.toLong
        val cmdOpcode = dut.io.bmbCmdOpcode.toInt
        val rspValid = dut.io.bmbRspValid.toBoolean
        val rspReady = dut.io.bmbRspReady.toBoolean
        val rspFire = rspValid && rspReady
        val rspData = dut.io.bmbRspData.toLong

        // Memory control signals
        val bcRd = dut.io.bcRd.toBoolean
        val memRd = dut.io.memRd.toBoolean
        val memRdc = dut.io.memRdc.toBoolean
        val memWr = dut.io.memWr.toBoolean
        val memAddrWr = dut.io.memAddrWr.toBoolean
        val mpReg = dut.io.mpRegValue.toLong

        // Log interesting events
        val stateChanged = memState != lastState
        val pcChanged = pc != lastPc || jpc != lastJpc
        val bmbActivity = cmdFire || rspFire
        val memActivity = bcRd || memRd || memRdc || memWr || memAddrWr
        // Log more cycles around where bytecode execution starts (log EVERY cycle here)
        val detailedRange = (cycle >= 85 && cycle <= 115) || (cycle >= 195 && cycle <= 250) || (cycle >= 3940 && cycle <= 3990)

        if (stateChanged || bmbActivity || memActivity || cycle < 100 || cycle % 100 == 0 || detailedRange) {
          val stateStr = memState match {
            case 0 => "IDLE"
            case 1 => "RD_WAIT"
            case 2 => "WR_WAIT"
            case 3 => "H_READ"
            case 4 => "H_WAIT"
            case 5 => "H_CALC"
            case 6 => "H_ACC"
            case 7 => "H_DW"
            case 8 => "BC_RD"
            case 9 => "BC_WAIT"
            case 10 => "BC_WR"
            case 11 => "GS_RD"
            case 12 => "PS_WR"
            case 13 => "LAST"
            case _ => f"S$memState%d"
          }

          val busyStr = if (memBusy) "BSY" else "   "
          val cmdStr = if (cmdFire) f"CMD[${if(cmdOpcode == 0) "R" else "W"}@0x$cmdAddr%06x]" else "           "
          val rspStr = if (rspFire) f"RSP[0x$rspData%08x]" else "            "
          val memStr = Seq(
            if (bcRd) "bcRd" else "",
            if (memRd) "rd" else "",
            if (memRdc) "rdc" else "",
            if (memWr) "wr" else "",
            if (memAddrWr) "addrWr" else ""
          ).filter(_.nonEmpty).mkString(",")

          logLine(f"[$cycle%5d] PC=$pc%04x JPC=$jpc%04x $busyStr $stateStr%-8s $cmdStr $rspStr aout=$aout%08x bout=$bout%08x mp=$mpReg%08x $memStr")

          if (cmdFire) bmbTxCount += 1
          lastState = memState
        }

        lastPc = pc
        lastJpc = jpc

        // UART output
        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          val charStr = if (char >= 32 && char < 127) char.toChar.toString else f"\\x$char%02x"
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          logLine(f"[$cycle%5d] *** UART TX: '$charStr' (0x$char%02x) ***")
        }
      }

      logLine("")
      logLine("=" * 100)
      logLine("=== Summary ===")
      logLine(s"Cycles: $maxCycles")
      logLine(s"Final PC: ${dut.io.pc.toInt}")
      logLine(s"Final JPC: ${dut.io.jpc.toInt}")
      logLine(s"Final memState: ${dut.io.memCtrlState.toInt}")
      logLine(s"memBusy: ${dut.io.memBusy.toBoolean}")
      logLine(s"BMB transactions: $bmbTxCount")
      logLine(s"UART Output: '${uartOutput.toString}'")
      logLine("=" * 100)

      log.close()

      println(s"\n=== Debug Simulation Complete ===")
      println(s"Final PC: ${dut.io.pc.toInt}")
      println(s"Final JPC: ${dut.io.jpc.toInt}")
      println(s"BMB transactions: $bmbTxCount")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Log written to: $logFilePath")

      run.finish("PASS", s"$maxCycles cycles")
    }
}
