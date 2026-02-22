package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.debug.{DebugConfig, DebugTransport}
import java.net.{ServerSocket, Socket}
import java.io.{InputStream, OutputStream}

/**
 * Debug Sim Test Harness: JOP core(s) with BRAM + debug controller.
 *
 * Exposes the debug transport byte-stream as io pins so the SpinalHDL sim
 * can bridge them to/from a TCP socket. Eclipse connects to localhost:4567
 * and uses the same protocol as FPGA hardware.
 *
 * @param cpuCnt      Number of CPU cores
 * @param debugConfig Debug subsystem configuration
 * @param romInit     Microcode ROM data
 * @param ramInit     Stack RAM data
 * @param mainMemInit Main memory (BRAM) data
 */
case class JopDebugTestHarness(
  cpuCnt: Int = 1,
  debugConfig: DebugConfig = DebugConfig(),
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 128 * 1024
) extends Component {
  require(cpuCnt >= 1)

  val io = new Bundle {
    // Per-core pipeline outputs
    val pc  = out Vec(UInt(11 bits), cpuCnt)
    val jpc = out Vec(UInt(12 bits), cpuCnt)
    val aout = out Vec(Bits(32 bits), cpuCnt)
    val bout = out Vec(Bits(32 bits), cpuCnt)
    val memBusy = out Vec(Bool(), cpuCnt)
    val halted  = out Vec(Bool(), cpuCnt)

    // UART output (from core 0)
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Debug transport: byte-stream to/from debug controller
    // These are directly driven from the sim thread (TCP bridge)
    val dbgRxData  = in Bits(8 bits)   // Byte from host
    val dbgRxValid = in Bool()
    val dbgRxReady = out Bool()

    val dbgTxData  = out Bits(8 bits)  // Byte to host
    val dbgTxValid = out Bool()
    val dbgTxReady = in Bool()
  }

  // Extract JBC init from main memory (same as other harnesses)
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
  // JOP Cluster with debug enabled
  // ====================================================================

  val cluster = JopCluster(
    cpuCnt = cpuCnt,
    baseConfig = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = memSize)
    ),
    debugConfig = Some(debugConfig),
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // ====================================================================
  // BRAM (shared memory)
  // ====================================================================

  val ram = BmbOnChipRam(
    p = cluster.bmbParameter,
    size = memSize,
    hexInit = null
  )
  val memWords = memSize / 4
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))
  ram.io.bus << cluster.io.bmb

  // ====================================================================
  // UART (core 0 only)
  // ====================================================================

  cluster.io.rxd := True  // No serial RX in debug sim

  // ====================================================================
  // Debug transport wiring: io pins <-> cluster.debugTransport
  // ====================================================================

  val transport = cluster.io.debugTransport.get

  // RX: sim -> transport.rxByte (host-to-target)
  transport.rxByte.valid := io.dbgRxValid
  transport.rxByte.payload := io.dbgRxData
  io.dbgRxReady := transport.rxByte.ready

  // TX: transport.txByte -> sim (target-to-host)
  io.dbgTxData := transport.txByte.payload
  io.dbgTxValid := transport.txByte.valid
  transport.txByte.ready := io.dbgTxReady

  // ====================================================================
  // Output routing
  // ====================================================================

  for (i <- 0 until cpuCnt) {
    io.pc(i)      := cluster.io.pc(i)
    io.jpc(i)     := cluster.io.jpc(i)
    io.aout(i)    := cluster.io.aout(i)
    io.bout(i)    := cluster.io.bout(i)
    io.memBusy(i) := cluster.io.memBusy(i)
    io.halted(i)  := cluster.io.halted(i)
  }

  io.uartTxData  := cluster.io.uartTxData
  io.uartTxValid := cluster.io.uartTxValid
}


/**
 * Debug simulation with TCP socket bridge.
 *
 * Runs JOP with debug enabled and opens a TCP server on port 4567.
 * Eclipse connects to localhost:4567 and uses the same debug protocol
 * as FPGA hardware.
 *
 * Usage: sbt "Test / runMain jop.system.JopDebugSim"
 */
object JopDebugSim extends App {
  val tcpPort = if (args.nonEmpty) args(0).toInt else 4567
  val cpuCnt = if (args.length > 1) args(1).toInt else 1
  val maxCycles = if (args.length > 2) args(2).toLong else 100000000L  // 100M cycles

  // Load ROM, RAM, and .jop
  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val jopData = JopFileLoader.loadJopFile("java/apps/Smallest/HelloWorld.jop")

  println(s"JOP Debug Sim: $cpuCnt core(s), TCP port $tcpPort, max $maxCycles cycles")
  println(s"ROM: ${romData.length} words, RAM: ${ramData.length} words, JOP: ${jopData.length} words")

  val simConfig = SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .withWave
    .allOptimisation

  simConfig.compile(JopDebugTestHarness(
    cpuCnt = cpuCnt,
    debugConfig = DebugConfig(numBreakpoints = 4),
    romInit = romData,
    ramInit = ramData,
    mainMemInit = jopData.words
  )).doSim("JopDebugSim") { dut =>

    dut.clockDomain.forkStimulus(period = 10)

    // Default: no debug byte from host
    dut.io.dbgRxValid #= false
    dut.io.dbgRxData #= 0
    dut.io.dbgTxReady #= true

    // ================================================================
    // TCP Server Thread
    // ================================================================

    @volatile var tcpSocket: Socket = null
    @volatile var tcpIn: InputStream = null
    @volatile var tcpOut: OutputStream = null
    @volatile var tcpConnected = false

    val serverThread = new Thread {
      override def run(): Unit = {
        val server = new ServerSocket(tcpPort)
        server.setReuseAddress(true)
        println(s"[DEBUG] TCP server listening on port $tcpPort")
        println(s"[DEBUG] Connect Eclipse debugger to localhost:$tcpPort")
        try {
          tcpSocket = server.accept()
          tcpIn = tcpSocket.getInputStream
          tcpOut = tcpSocket.getOutputStream
          tcpConnected = true
          println(s"[DEBUG] Client connected from ${tcpSocket.getRemoteSocketAddress}")
        } catch {
          case _: Exception => // Server closed
        }
      }
    }
    serverThread.setDaemon(true)
    serverThread.start()

    // ================================================================
    // RX bridge: TCP -> debug transport (fork thread)
    // ================================================================

    // Queue for bytes from TCP to send to DUT
    val rxQueue = new java.util.concurrent.LinkedBlockingQueue[Int](256)

    val rxBridgeThread = new Thread {
      override def run(): Unit = {
        while (true) {
          if (tcpConnected && tcpIn != null) {
            try {
              val b = tcpIn.read()
              if (b >= 0) {
                rxQueue.put(b)
              } else {
                // Connection closed
                tcpConnected = false
                println("[DEBUG] TCP connection closed by client")
                return
              }
            } catch {
              case _: Exception =>
                tcpConnected = false
                return
            }
          } else {
            Thread.sleep(10)
          }
        }
      }
    }
    rxBridgeThread.setDaemon(true)
    rxBridgeThread.start()

    // ================================================================
    // Main simulation loop
    // ================================================================

    var cycle = 0L
    var uartBuf = new StringBuilder()
    var lastPrintCycle = 0L

    while (cycle < maxCycles) {
      dut.clockDomain.waitSampling()
      cycle += 1

      // --- RX bridge: send queued bytes to DUT ---
      if (!rxQueue.isEmpty && dut.io.dbgRxReady.toBoolean) {
        val b = rxQueue.poll()
        if (b != null) {
          dut.io.dbgRxValid #= true
          dut.io.dbgRxData #= b
        }
      } else {
        dut.io.dbgRxValid #= false
      }

      // --- TX bridge: read bytes from DUT and send to TCP ---
      if (dut.io.dbgTxValid.toBoolean) {
        val b = dut.io.dbgTxData.toInt & 0xFF
        dut.io.dbgTxReady #= true
        if (tcpConnected && tcpOut != null) {
          try {
            tcpOut.write(b)
            tcpOut.flush()
          } catch {
            case _: Exception =>
              tcpConnected = false
          }
        }
      }

      // --- UART output capture ---
      if (dut.io.uartTxValid.toBoolean) {
        val ch = dut.io.uartTxData.toInt & 0xFF
        if (ch == '\n') {
          println(s"[UART] ${uartBuf.toString}")
          uartBuf.clear()
        } else if (ch >= 32 && ch < 127) {
          uartBuf.append(ch.toChar)
        }
      }

      // --- Periodic status ---
      if (cycle - lastPrintCycle >= 1000000) {
        val pc0 = dut.io.pc(0).toInt
        val jpc0 = dut.io.jpc(0).toInt
        val connected = if (tcpConnected) "connected" else "waiting"
        println(f"[DEBUG] cycle=$cycle%,d PC=$pc0%04x JPC=$jpc0%04x TCP=$connected")
        lastPrintCycle = cycle
      }
    }

    println(s"[DEBUG] Simulation ended after $cycle cycles")
    if (tcpSocket != null) tcpSocket.close()
  }
}
