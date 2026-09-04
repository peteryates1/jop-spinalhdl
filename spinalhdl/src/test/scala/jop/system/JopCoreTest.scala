package jop.system
import jop.config._

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb._
import org.scalatest.funsuite.AnyFunSuite
import jop.utils.JopFileLoader
import jop.memory.JopMemoryConfig

/**
 * Test harness for JopCore with BmbOnChipRam
 * Uses default SpinalHDL clock domain for simpler simulation.
 *
 * I/O subsystem (Sys, Uart) is internal to JopCore.
 * UART TX is snooped via JopCore's debug outputs.
 */
case class JopCoreTestHarness(
  romInit: Seq[BigInt],
  ramInit: Seq[BigInt],
  mainMemInit: Seq[BigInt],
  memSize: Int = 256 * 1024,  // default 256KB; increase for large .jop files
  coreConfig: Option[JopCoreConfig] = None  // override core config (default: JopCoreConfig())
) extends Component {

  val config = coreConfig.getOrElse(JopCoreConfig(
    memConfig = JopMemoryConfig(mainMemSize = memSize)
  ))

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

    // Debug
    val debugState = out UInt(4 bits)

    // Exception debug
    val excFired = out Bool()
    val excType = out Bits(8 bits)

    // Stack pointer debug
    val debugSp = out UInt(config.stackConfig.spWidth bits)
    val debugVp = out UInt(config.stackConfig.spWidth bits)

    // Stack RAM debug probe (read-only; address hardwired to 0 internally)
    val debugRamData = out Bits(config.dataWidth bits)
  }

  // JBC RAM starts empty — microcode fills it from main memory on demand
  // (Pre-loading extracted bytecodes causes BC fill state machine conflicts)
  val jbcInit = Seq.fill(config.jbcDepth)(BigInt(0))

  // JOP System core (Sys + Uart internal)
  val jopCore = JopCore(
    config = config,
    romInit = Some(romInit),
    ramInit = Some(ramInit),
    jbcInit = Some(jbcInit)
  )

  // Card table. The table is a CLUSTER-level resource (see JopCluster — a
  // per-core one is item 1's bug), so a harness that instantiates JopCore
  // directly has to provide it. Tying the port off instead would be quietly
  // unsound rather than merely wrong: the core answers CARD_SHIFT/CARD_COUNT
  // from its own config, so GC.init would see a card table, enable generational
  // mode, and then mark into nothing — every remembered-set entry lost, in
  // exactly the sims that exist to exercise generational GC.
  if (config.memConfig.hasCardTable) {
    val mc = config.memConfig
    val ct = new jop.memory.CardTable(mc.cardCount, mc.cardShift, mc.addressWidth)
    val port = jopCore.io.card.get
    val idxW = ct.idxWidth

    val cmdIsWrite = jopCore.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
    ct.io.markValid := jopCore.io.bmb.cmd.fire && cmdIsWrite
    ct.io.markAddr  := (jopCore.io.bmb.cmd.fragment.address >> 2).resize(mc.addressWidth)

    val wrValid = RegNext(port.wr)     init (False)
    val wrSel   = RegNext(port.sel)    init (0)
    val wrData  = RegNext(port.wrData) init (0)

    val cardLo    = Reg(UInt(mc.addressWidth bits)) init (0)
    val cardHi    = Reg(UInt(mc.addressWidth bits)) init (0)
    val cardRdIdx = Reg(UInt(idxW bits)) init (0)
    when(wrValid) {
      switch(wrSel) {
        is(0) { cardLo    := wrData(mc.addressWidth - 1 downto 0).asUInt }
        is(1) { cardHi    := wrData(mc.addressWidth - 1 downto 0).asUInt }
        is(2) { cardRdIdx := wrData(idxW - 1 downto 0).asUInt }
      }
    }
    ct.io.baseWord := cardLo
    ct.io.topWord  := cardHi
    ct.io.rdIdx    := cardRdIdx

    val clrWr   = wrValid && (wrSel === U(6, 3 bits))
    val clrAllV = wrData.andR
    ct.io.clrEn  := clrWr && !clrAllV
    ct.io.clrAll := clrWr && clrAllV
    ct.io.clrIdx := wrData(idxW - 1 downto 0).asUInt

    port.rdData := ct.io.rdData
    // The clear-all sweep stalls the core until the table is clear (status item
    // 131). This harness wires the card table itself rather than going through
    // JopCluster, so it has to drive this too -- and because SpinalHDL only
    // reports NO DRIVER at ELABORATION, adding a port to CardCtrlPort breaks
    // this file in the sims and never in `sbt test` or `compile`.
    port.busy := ct.io.clrBusy
  }

  // Connect BMB (arbiter if stack DMA present)
  val memWords = config.memConfig.mainMemWords.toInt
  val initData = mainMemInit.take(memWords).padTo(memWords, BigInt(0))

  jopCore.io.stackDmaBmb match {
    case Some(dmaBmb) =>
      val inputParam = config.memConfig.bmbParameter
      val inputSourceParam = inputParam.access.sources.values.head
      val outputParam = BmbParameter(
        access = BmbAccessParameter(
          addressWidth = inputParam.access.addressWidth,
          dataWidth = inputParam.access.dataWidth
        ).addSources(2, BmbSourceParameter(
          contextWidth = inputSourceParam.contextWidth,
          lengthWidth = inputSourceParam.lengthWidth,
          canWrite = true,
          canRead = true,
          alignment = BmbParameter.BurstAlignement.WORD
        )),
        invalidation = BmbInvalidationParameter()
      )
      val arbiter = BmbArbiter(
        inputsParameter = Seq.fill(2)(inputParam),
        outputParameter = outputParam,
        lowerFirstPriority = false
      )
      arbiter.io.inputs(0) << jopCore.io.bmb
      arbiter.io.inputs(1) << dmaBmb
      val ram = BmbOnChipRam(p = outputParam, size = config.memConfig.mainMemSize, hexInit = null)
      ram.ram.init(initData.map(v => B(v, 32 bits)))
      ram.io.bus << arbiter.io.output
    case None =>
      val ram = BmbOnChipRam(p = config.memConfig.bmbParameter, size = config.memConfig.mainMemSize, hexInit = null)
      ram.ram.init(initData.map(v => B(v, 32 bits)))
      ram.io.bus << jopCore.io.bmb
  }

  // Single-core: no CmpSync
  jopCore.io.syncIn.halted := False
  jopCore.io.syncIn.s_out := False
  jopCore.io.syncIn.status := False

  // No UART RX in test harness
  if (jopCore.devicePins.contains("uart")) jopCore.devicePin[Bool]("uart", "rxd") := True

  // Debug RAM probe: hardwire to 0 to avoid readSync MUX corruption in simulation.
  // When debugRamAddr is an external IO port, Verilator leaves it uninitialized (X),
  // which causes the StackStage readSync address MUX to corrupt pipeline reads.
  jopCore.io.debugRamAddr := 0
  io.debugRamData := jopCore.io.debugRamData
  jopCore.io.debugHalt := False
  jopCore.io.snoopIn.foreach { si =>
    si.valid := False; si.isArray := False; si.handle := 0; si.index := 0
  }

  // SimPublic for I/O tracing
  jopCore.memCtrl.io.ioRd.simPublic()
  jopCore.memCtrl.io.ioWr.simPublic()
  jopCore.memCtrl.io.ioAddr.simPublic()
  jopCore.memCtrl.io.ioWrData.simPublic()
  jopCore.memCtrl.io.ioRdData.simPublic()
  jopCore.io.debugIoRdCount.simPublic()
  jopCore.io.debugIoWrCount.simPublic()

  // SimPublic for memory controller internals
  jopCore.memCtrl.io.memIn.rd.simPublic()
  jopCore.memCtrl.io.memIn.wr.simPublic()
  jopCore.memCtrl.io.memIn.wrf.simPublic()
  jopCore.memCtrl.io.memIn.addrWr.simPublic()
  jopCore.memCtrl.addrIsIo.simPublic()
  jopCore.memCtrl.aoutIsIo.simPublic()
  jopCore.memCtrl.addrReg.simPublic()
  jopCore.memCtrl.memReadRequested.simPublic()
  jopCore.memCtrl.rdDataReg.simPublic()
  jopCore.io.debugRdDataReg.simPublic()
  jopCore.memCtrl.ioRdPending.simPublic()

  // Outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch
  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout
  io.memBusy := jopCore.io.memBusy
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid
  io.debugState := 0  // Placeholder - internal signal not accessible
  io.excFired := jopCore.io.debugExc
  io.excType := 0  // Exception type not easily snooped with internal I/O
  io.debugSp := jopCore.io.debugSp
  io.debugVp := jopCore.io.debugVp
}

/**
 * JopCore Tests
 */
class JopCoreTest extends AnyFunSuite {

  // Paths to initialization files
  val jopFilePath = jop.utils.SimApp.jop("Smallest", "HelloWorld")
  val romFilePath = MicrocodePaths.simulationRom
  val ramFilePath = MicrocodePaths.simulationRam

  test("JopCore: basic execution with BMB memory") {
    // Load initialization data
    val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
    val ramData = JopFileLoader.loadStackRam(ramFilePath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 128 * 1024 / 4)  // 128KB / 4 = 32K words

    println(s"Loaded ROM: ${romData.length} entries")
    println(s"Loaded RAM: ${ramData.length} entries")
    println(s"Loaded main memory: ${mainMemData.length} entries")

    SimConfig
      // .withWave  // Disabled for faster testing
      .compile(JopCoreTestHarness(romData, ramData, mainMemData))
      .doSim { dut =>
        // Initialize clock
        dut.clockDomain.forkStimulus(10)  // 10ns period

        dut.clockDomain.waitSampling(5)

        // Run for a small number of cycles to verify integration
        val maxCycles = 100

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()
        }

        println(s"=== Executed $maxCycles cycles ===")
        println(s"Final PC: ${dut.io.pc.toInt}")
        println(s"Final JPC: ${dut.io.jpc.toInt}")
        println(s"memBusy: ${dut.io.memBusy.toBoolean}")
        println(s"debugState: ${dut.io.debugState.toInt}")

        // Verify the system is running (PC should have changed from initial value)
        val finalPc = dut.io.pc.toInt
        assert(finalPc > 0, "Pipeline should have started executing")
        println("=== Test PASSED: JopCore integration works ===")
      }
  }
}
