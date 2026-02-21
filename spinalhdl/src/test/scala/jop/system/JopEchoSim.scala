package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.io.BmbSys

/**
 * Echo test harness: JopCore with BRAM, I/O decode inside,
 * UART RX data/valid exposed as inputs for simulation.
 */
case class JopEchoHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt]
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)
  )

  val io = new Bundle {
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val memBusy = out Bool()

    // UART TX (from JOP)
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()

    // UART RX (from simulation)
    val uartRxData = in Bits(8 bits)
    val uartRxValid = in Bool()
    val uartRxRead = out Bool()

    // I/O debug
    val ioRd = out Bool()
    val ioWr = out Bool()
    val ioAddr = out UInt(8 bits)
    val ioWrData = out Bits(32 bits)
  }

  // Empty JBC (echo.asm doesn't use it)
  val jbcInit = Seq.fill(2048)(BigInt(0))

  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // BRAM (echo.asm doesn't access it, but BMB needs a slave)
  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )
  ram.io.bus << jopCore.io.bmb

  // Drive debug RAM port (unused)
  jopCore.io.debugRamAddr := 0

  // Interrupts disabled
  jopCore.io.irq := False
  jopCore.io.irqEna := False
  jopCore.io.halted := False  // Single-core: never halted

  // Decode I/O address
  val ioSubAddr = jopCore.io.ioAddr(3 downto 0)
  val ioSlaveId = jopCore.io.ioAddr(5 downto 4)

  // System I/O (slave 0) — real BmbSys component
  val bmbSys = BmbSys(clkFreqHz = 100000000L)
  bmbSys.io.addr   := ioSubAddr
  bmbSys.io.rd     := jopCore.io.ioRd && ioSlaveId === 0
  bmbSys.io.wr     := jopCore.io.ioWr && ioSlaveId === 0
  bmbSys.io.wrData := jopCore.io.ioWrData
  bmbSys.io.syncIn.halted := False  // Single-core: no CmpSync
  bmbSys.io.syncIn.s_out := False

  // Exception signal from BmbSys
  jopCore.io.exc := bmbSys.io.exc

  // UART TX capture
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  // UART RX read tracking (registered, fires one cycle after ioRd)
  val uartRxReadReg = Reg(Bool()) init(False)
  uartRxReadReg := False

  // UART TX write
  uartTxValidReg := False
  when(jopCore.io.ioWr && ioSlaveId === 1 && ioSubAddr === 1) {
    uartTxDataReg := jopCore.io.ioWrData(7 downto 0)
    uartTxValidReg := True
  }

  // I/O read mux
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  switch(ioSlaveId) {
    is(0) { ioRdData := bmbSys.io.rdData }
    is(1) {
      switch(ioSubAddr) {
        is(0) {
          // Status: bit 0 = TDRE, bit 1 = RDRF
          ioRdData := B(0, 30 bits) ## io.uartRxValid.asBits ## B"1"
        }
        is(1) {
          // Data read
          ioRdData := B(0, 24 bits) ## io.uartRxData
          when(jopCore.io.ioRd) {
            uartRxReadReg := True
          }
        }
      }
    }
  }
  jopCore.io.ioRdData := ioRdData

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.uartRxRead := uartRxReadReg
  io.ioRd := jopCore.io.ioRd
  io.ioWr := jopCore.io.ioWr
  io.ioAddr := jopCore.io.ioAddr
  io.ioWrData := jopCore.io.ioWrData
}

/**
 * Simulation of echo.asm microcode.
 * Tests UART I/O: polls status, reads byte, echoes it.
 */
object JopEchoSim extends App {

  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)

  println(s"Loaded echo ROM: ${romData.length} entries")
  println(s"Loaded echo RAM: ${ramData.length} entries")

  SimConfig
    .compile(JopEchoHarness(romData, ramData))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(20) // 50 MHz

      // UART RX defaults
      dut.io.uartRxData #= 0
      dut.io.uartRxValid #= false

      var txOutput = new StringBuilder
      var echoCount = 0

      val testBytes = Array(0x41, 0x42, 0x43, 0x55, 0xAA)
      var testIdx = 0
      var sendDelay = 0
      var startupByteSeen = false

      dut.clockDomain.waitSampling(10)
      println("Starting echo simulation...")

      val maxCycles = 5000
      var cycle = 0

      while (cycle < maxCycles && echoCount < testBytes.length) {
        // Drive next RX byte if ready
        if (startupByteSeen && testIdx < testBytes.length && !dut.io.uartRxValid.toBoolean) {
          sendDelay += 1
          if (sendDelay >= 10) {
            dut.io.uartRxData #= testBytes(testIdx)
            dut.io.uartRxValid #= true
            println(f"  [$cycle%5d] >> Feeding byte 0x${testBytes(testIdx)}%02x to RX")
            testIdx += 1
            sendDelay = 0
          }
        }

        dut.clockDomain.waitSampling()
        cycle += 1

        // Check if byte was consumed
        if (dut.io.uartRxRead.toBoolean) {
          dut.io.uartRxValid #= false
          println(f"  [$cycle%5d] RX byte consumed")
        }

        // Check UART TX
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          val c = if (ch >= 32 && ch < 127) ch.toChar else '.'
          txOutput.append(c)

          if (!startupByteSeen) {
            startupByteSeen = true
            println(f"  [$cycle%5d] *** Startup byte: '$c' (0x$ch%02x) ***")
          } else {
            echoCount += 1
            println(f"  [$cycle%5d] *** Echo #$echoCount: '$c' (0x$ch%02x) ***")
          }
        }

        // Log I/O activity for first 100 cycles
        if (cycle <= 30) {
          val pc = dut.io.pc.toInt
          if (dut.io.ioRd.toBoolean) {
            println(f"  [$cycle%5d] IO RD addr=0x${dut.io.ioAddr.toInt}%02x  PC=$pc%04x")
          }
          if (dut.io.ioWr.toBoolean) {
            val data = dut.io.ioWrData.toLong & 0xFFFFFFFFL
            println(f"  [$cycle%5d] IO WR addr=0x${dut.io.ioAddr.toInt}%02x data=0x$data%08x  PC=$pc%04x")
          }
        }
      }

      println(s"\n=== Echo Simulation Result ===")
      println(s"Cycles: $cycle")
      println(s"TX output: '${txOutput.toString}'")
      println(s"Echoed: $echoCount / ${testBytes.length}")

      if (echoCount == testBytes.length) {
        println("SUCCESS: All bytes echoed!")
      } else {
        println("FAIL: Not all bytes echoed")
      }
    }
}
