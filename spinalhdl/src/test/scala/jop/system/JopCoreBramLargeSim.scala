package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig
import jop.io.BmbSys

/**
 * Test harness with configurable BRAM size to test address wrapping effects.
 */
case class JopCoreLargeBramHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  bramSize: Int = 2 * 1024 * 1024  // 2MB default
) extends Component {

  val config = JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = bramSize)
  )

  val io = new Bundle {
    val pc = out UInt(config.pcWidth bits)
    val jpc = out UInt((config.jpcWidth + 1) bits)
    val memBusy = out Bool()
    val uartTxData = out Bits(8 bits)
    val uartTxValid = out Bool()
    // BMB debug
    val bmbCmdValid = out Bool()
    val bmbCmdReady = out Bool()
    val bmbCmdAddr = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid = out Bool()
    val bmbRspData = out Bits(32 bits)
  }

  // Extract JBC init from main memory
  val mpAddr = if (mainMemInit.length > 1) mainMemInit(1).toInt else 0
  val bootMethodStructAddr = if (mainMemInit.length > mpAddr) mainMemInit(mpAddr).toInt else 0
  val bootMethodStartLen = if (mainMemInit.length > bootMethodStructAddr) mainMemInit(bootMethodStructAddr).toLong else 0
  val bootCodeStart = (bootMethodStartLen >> 10).toInt
  val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
  val bytecodeWords = mainMemInit.slice(bytecodeStartWord, bytecodeStartWord + 512)

  val jbcInit = bytecodeWords.flatMap { word =>
    val w = word.toLong & 0xFFFFFFFFL
    Seq(
      BigInt((w >> 24) & 0xFF),
      BigInt((w >> 16) & 0xFF),
      BigInt((w >> 8) & 0xFF),
      BigInt((w >> 0) & 0xFF)
    )
  }.padTo(2048, BigInt(0))

  val jopSystem = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  val ram = BmbOnChipRam(
    p = config.memConfig.bmbParameter,
    size = config.memConfig.mainMemSize,
    hexInit = null
  )

  val memWords = config.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))
  ram.ram.init(initData.map(v => B(v, 32 bits)))

  ram.io.bus << jopSystem.io.bmb

  // Decode I/O address
  val ioSubAddr = jopSystem.io.ioAddr(3 downto 0)
  val ioSlaveId = jopSystem.io.ioAddr(5 downto 4)

  // System I/O (slave 0) — real BmbSys component
  val bmbSys = BmbSys(clkFreqHz = 100000000L)
  bmbSys.io.addr   := ioSubAddr
  bmbSys.io.rd     := jopSystem.io.ioRd && ioSlaveId === 0
  bmbSys.io.wr     := jopSystem.io.ioWr && ioSlaveId === 0
  bmbSys.io.wrData := jopSystem.io.ioWrData

  // Exception signal from BmbSys
  jopSystem.io.exc := bmbSys.io.exc

  // UART (slave 1) — simplified for simulation
  val uartTxDataReg = Reg(Bits(8 bits)) init(0)
  val uartTxValidReg = Reg(Bool()) init(False)

  uartTxValidReg := False
  when(jopSystem.io.ioWr && ioSlaveId === 1 && ioSubAddr === 1) {
    uartTxDataReg := jopSystem.io.ioWrData(7 downto 0)
    uartTxValidReg := True
  }

  // I/O read mux
  val ioRdData = Bits(32 bits)
  ioRdData := 0
  switch(ioSlaveId) {
    is(0) { ioRdData := bmbSys.io.rdData }
    is(1) {
      switch(ioSubAddr) {
        is(0) { ioRdData := B(0x1, 32 bits) }  // Status: TX ready
      }
    }
  }
  jopSystem.io.ioRdData := ioRdData

  jopSystem.io.irq := False
  jopSystem.io.irqEna := False

  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.memBusy := jopSystem.io.memBusy
  io.uartTxData := uartTxDataReg
  io.uartTxValid := uartTxValidReg
  io.bmbCmdValid := jopSystem.io.bmb.cmd.valid
  io.bmbCmdReady := jopSystem.io.bmb.cmd.ready
  io.bmbCmdAddr := jopSystem.io.bmb.cmd.fragment.address
  io.bmbCmdOpcode := jopSystem.io.bmb.cmd.fragment.opcode.asBits.resized
  io.bmbRspValid := jopSystem.io.bmb.rsp.valid
  io.bmbRspData := jopSystem.io.bmb.rsp.fragment.data
}

/**
 * Run BRAM sim with 2MB memory (no address wrapping for typical program addresses)
 * to test if the BRAM sim works because of wrapping or because of correct execution.
 */
object JopCoreBramLargeSim extends App {
  val jopFilePath = "java/apps/Smallest/HelloWorld.jop"
  val romFilePath = "asm/generated/mem_rom.dat"
  val ramFilePath = "asm/generated/mem_ram.dat"

  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = JopFileLoader.loadStackRam(ramFilePath)
  // Load enough data for 2MB BRAM
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 2 * 1024 * 1024 / 4)

  val bramSize = 2 * 1024 * 1024  // 2MB

  println(s"Loaded ROM: ${romData.length} entries")
  println(s"Loaded RAM: ${ramData.length} entries")
  println(s"Loaded main memory: ${mainMemData.length} entries (for ${bramSize/1024}KB BRAM)")

  SimConfig
    .compile(JopCoreLargeBramHarness(romData, ramData, mainMemData, bramSize))
    .doSim { dut =>
      var uartOutput = new StringBuilder
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      val maxCycles = 200000
      var running = true
      var cycle = 0

      while (running && cycle < maxCycles) {
        dut.clockDomain.waitSampling()
        cycle += 1

        if (dut.io.uartTxValid.toBoolean) {
          val char = dut.io.uartTxData.toInt
          uartOutput.append(if (char >= 32 && char < 127) char.toChar else '.')
          print(if (char >= 32 && char < 127) char.toChar else '.')
        }

        // Log ALL BMB transactions
        val cmdFire = dut.io.bmbCmdValid.toBoolean && dut.io.bmbCmdReady.toBoolean
        if (cmdFire) {
          val addr = dut.io.bmbCmdAddr.toLong
          val wordAddr = addr / 4
          val op = if (dut.io.bmbCmdOpcode.toInt == 0) "RD" else "WR"
          println(f"\n[$cycle%6d] BMB CMD $op addr=0x$addr%08x (word=$wordAddr)")
        }
        if (dut.io.bmbRspValid.toBoolean) {
          val data = dut.io.bmbRspData.toLong & 0xFFFFFFFFL
          println(f"[$cycle%6d] BMB RSP data=0x$data%08x")
        }

        if (cycle > 0 && cycle % 50000 == 0) {
          println(f"\n[$cycle%6d] PC=${dut.io.pc.toInt}%04x JPC=${dut.io.jpc.toInt}%04x UART='${uartOutput.toString}'")
        }

        if (uartOutput.toString.contains("Hello World")) {
          println(s"\n*** Hello World detected at cycle $cycle ***")
          for (_ <- 0 until 5000) {
            dut.clockDomain.waitSampling()
            if (dut.io.uartTxValid.toBoolean) {
              val c = dut.io.uartTxData.toInt
              uartOutput.append(if (c >= 32 && c < 127) c.toChar else '.')
              print(if (c >= 32 && c < 127) c.toChar else '.')
            }
          }
          println()
          running = false
        }
      }

      println(s"\n\n=== Simulation Complete ===")
      println(s"UART Output: '${uartOutput.toString}'")
      println(s"Final PC: ${dut.io.pc.toInt}")
      println(s"Final JPC: ${dut.io.jpc.toInt}")
    }
}
