package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import jop.io.{SyncIn, SyncOut}
import jop.memory.BmbSdramCtrl32

/**
 * JOP Core with SDR SDRAM Backend
 *
 * Complete JOP system using BmbSdramCtrl32 for external 16-bit SDRAM.
 * The BmbSdramCtrl32 handles 32-bit BMB to 16-bit SDRAM width conversion
 * internally by issuing two SDRAM operations per 32-bit transaction.
 *
 * I/O subsystem (BmbSys, BmbUart) is internal to JopCore.
 *
 * Target: W9825G6JH6 on QMTECH EP4CGX150 board.
 */
case class JopCoreWithSdram(
  config: JopCoreConfig = JopCoreConfig(),
  sdramLayout: SdramLayout = W9825G6JH6.layout,
  sdramTiming: SdramTimings = W9825G6JH6.timingGrade7,
  CAS: Int = 3,
  useAlteraCtrl: Boolean = false,
  clockFreqHz: Long = 100000000L,
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // SDRAM interface (directly exposed)
    val sdram = master(SdramInterface(sdramLayout))

    // CmpSync interface
    val syncIn  = in(SyncOut())
    val syncOut = out(SyncIn())

    // Watchdog from BmbSys
    val wd = out Bits(32 bits)

    // UART
    val txd = out Bool()
    val rxd = in Bool()

    // Pipeline status
    val pc        = out UInt(config.pcWidth bits)
    val jpc       = out UInt((config.jpcWidth + 1) bits)
    val instr     = out Bits(config.instrWidth bits)
    val jfetch    = out Bool()
    val jopdfetch = out Bool()

    // Stack outputs
    val aout      = out Bits(config.dataWidth bits)
    val bout      = out Bits(config.dataWidth bits)

    // Memory controller status
    val memBusy   = out Bool()

    // Debug: UART TX snoop
    val uartTxData  = out Bits(8 bits)
    val uartTxValid = out Bool()

    // Memory controller debug
    val debugMemState = out UInt(5 bits)
    val debugMemHandleActive = out Bool()

    // BMB debug
    val bmbCmdValid  = out Bool()
    val bmbCmdReady  = out Bool()
    val bmbCmdAddr   = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid  = out Bool()
    val bmbRspLast   = out Bool()
    val bmbRspData   = out Bits(32 bits)

    // SDRAM controller debug
    val debugSdramCtrl = out(new Bundle {
      val sendingHigh   = Bool()
      val burstActive   = Bool()
      val ctrlCmdValid  = Bool()
      val ctrlCmdReady  = Bool()
      val ctrlCmdWrite  = Bool()
      val ctrlRspValid  = Bool()
      val ctrlRspIsHigh = Bool()
      val lowHalfData   = Bits(16 bits)
    })
  }

  // JOP Core core
  val jopCore = JopCore(config, romInit, ramInit, jbcInit)

  // 32-bit BMB to 16-bit SDRAM bridge (handles width conversion internally)
  val sdramCtrl = BmbSdramCtrl32(
    bmbParameter = config.memConfig.bmbParameter,
    layout = sdramLayout,
    timing = sdramTiming,
    CAS = CAS,
    useAlteraCtrl = useAlteraCtrl,
    clockFreqHz = clockFreqHz
  )

  // Connect JOP BMB directly to SDRAM controller
  sdramCtrl.io.bmb <> jopCore.io.bmb

  // SDRAM interface
  io.sdram <> sdramCtrl.io.sdram

  // CmpSync passthrough
  jopCore.io.syncIn := io.syncIn
  io.syncOut := jopCore.io.syncOut

  // UART passthrough
  io.txd := jopCore.io.txd
  jopCore.io.rxd := io.rxd

  // Watchdog passthrough
  io.wd := jopCore.io.wd

  // Pipeline outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch

  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout

  io.memBusy := jopCore.io.memBusy

  // Memory controller debug
  io.debugMemState := jopCore.io.debugMemState
  io.debugMemHandleActive := jopCore.io.debugMemHandleActive

  // Debug passthrough
  io.uartTxData := jopCore.io.uartTxData
  io.uartTxValid := jopCore.io.uartTxValid

  // Tie unused debug inputs
  jopCore.io.debugRamAddr := 0
  jopCore.io.debugHalt := False

  // BMB debug
  io.bmbCmdValid := jopCore.io.bmb.cmd.valid
  io.bmbCmdReady := jopCore.io.bmb.cmd.ready
  io.bmbCmdAddr := jopCore.io.bmb.cmd.fragment.address
  io.bmbCmdOpcode := jopCore.io.bmb.cmd.fragment.opcode.asBits.resized
  io.bmbRspValid := jopCore.io.bmb.rsp.valid
  io.bmbRspLast := jopCore.io.bmb.rsp.last
  io.bmbRspData := jopCore.io.bmb.rsp.fragment.data

  // SDRAM controller debug (wire individually — SpinalHDL can't assign anonymous Bundles)
  io.debugSdramCtrl.sendingHigh   := sdramCtrl.io.debug.sendingHigh
  io.debugSdramCtrl.burstActive   := sdramCtrl.io.debug.burstActive
  io.debugSdramCtrl.ctrlCmdValid  := sdramCtrl.io.debug.ctrlCmdValid
  io.debugSdramCtrl.ctrlCmdReady  := sdramCtrl.io.debug.ctrlCmdReady
  io.debugSdramCtrl.ctrlCmdWrite  := sdramCtrl.io.debug.ctrlCmdWrite
  io.debugSdramCtrl.ctrlRspValid  := sdramCtrl.io.debug.ctrlRspValid
  io.debugSdramCtrl.ctrlRspIsHigh := sdramCtrl.io.debug.ctrlRspIsHigh
  io.debugSdramCtrl.lowHalfData   := sdramCtrl.io.debug.lowHalfData
}

/**
 * Generate Verilog for JopCoreWithSdram
 */
object JopCoreWithSdramVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopCoreWithSdram())
}
