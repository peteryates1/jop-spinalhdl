package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import jop.memory.BmbSdramCtrl32

/**
 * JOP System with SDR SDRAM Backend
 *
 * Complete JOP system using BmbSdramCtrl32 for external 16-bit SDRAM.
 * The BmbSdramCtrl32 handles 32-bit BMB to 16-bit SDRAM width conversion
 * internally by issuing two SDRAM operations per 32-bit transaction.
 *
 * Target: W9825G6JH6 on QMTECH EP4CGX150 board.
 */
case class JopSystemWithSdram(
  config: JopSystemConfig = JopSystemConfig(),
  sdramLayout: SdramLayout = W9825G6JH6.layout,
  sdramTiming: SdramTimings = W9825G6JH6.timingGrade7,
  CAS: Int = 3,
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // SDRAM interface (directly exposed)
    val sdram = master(SdramInterface(sdramLayout))

    // I/O interface
    val ioAddr    = out UInt(8 bits)
    val ioRd      = out Bool()
    val ioWr      = out Bool()
    val ioWrData  = out Bits(32 bits)
    val ioRdData  = in Bits(32 bits)

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

    // Interrupt interface
    val irq       = in Bool()
    val irqEna    = in Bool()

    // BMB debug
    val bmbCmdValid  = out Bool()
    val bmbCmdReady  = out Bool()
    val bmbCmdAddr   = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid  = out Bool()
    val bmbRspData   = out Bits(32 bits)
  }

  // JOP System core
  val jopSystem = JopSystem(config, romInit, ramInit, jbcInit)

  // 32-bit BMB to 16-bit SDRAM bridge (handles width conversion internally)
  val sdramCtrl = BmbSdramCtrl32(
    bmbParameter = config.memConfig.bmbParameter,
    layout = sdramLayout,
    timing = sdramTiming,
    CAS = CAS
  )

  // Connect JOP BMB directly to SDRAM controller
  sdramCtrl.io.bmb <> jopSystem.io.bmb

  // SDRAM interface
  io.sdram <> sdramCtrl.io.sdram

  // I/O interface
  io.ioAddr := jopSystem.io.ioAddr
  io.ioRd := jopSystem.io.ioRd
  io.ioWr := jopSystem.io.ioWr
  io.ioWrData := jopSystem.io.ioWrData
  jopSystem.io.ioRdData := io.ioRdData

  // Pipeline outputs
  io.pc := jopSystem.io.pc
  io.jpc := jopSystem.io.jpc
  io.instr := jopSystem.io.instr
  io.jfetch := jopSystem.io.jfetch
  io.jopdfetch := jopSystem.io.jopdfetch

  io.aout := jopSystem.io.aout
  io.bout := jopSystem.io.bout

  io.memBusy := jopSystem.io.memBusy

  // Interrupt
  jopSystem.io.irq := io.irq
  jopSystem.io.irqEna := io.irqEna

  // BMB debug
  io.bmbCmdValid := jopSystem.io.bmb.cmd.valid
  io.bmbCmdReady := jopSystem.io.bmb.cmd.ready
  io.bmbCmdAddr := jopSystem.io.bmb.cmd.fragment.address
  io.bmbCmdOpcode := jopSystem.io.bmb.cmd.fragment.opcode.asBits.resized
  io.bmbRspValid := jopSystem.io.bmb.rsp.valid
  io.bmbRspData := jopSystem.io.bmb.rsp.fragment.data
}

/**
 * Generate Verilog for JopSystemWithSdram
 */
object JopSystemWithSdramVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(JopSystemWithSdram())
}
