package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import jop.memory.BmbSdramCtrl32

/**
 * JOP Core with SDR SDRAM Backend
 *
 * Complete JOP system using BmbSdramCtrl32 for external 16-bit SDRAM.
 * The BmbSdramCtrl32 handles 32-bit BMB to 16-bit SDRAM width conversion
 * internally by issuing two SDRAM operations per 32-bit transaction.
 *
 * Target: W9825G6JH6 on QMTECH EP4CGX150 board.
 */
case class JopCoreWithSdram(
  config: JopCoreConfig = JopCoreConfig(),
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

    // Interrupt / Exception interface
    val irq       = in Bool()
    val irqEna    = in Bool()
    val exc       = in Bool()   // Exception signal from I/O subsystem

    // BMB debug
    val bmbCmdValid  = out Bool()
    val bmbCmdReady  = out Bool()
    val bmbCmdAddr   = out UInt(config.memConfig.bmbParameter.access.addressWidth bits)
    val bmbCmdOpcode = out Bits(1 bits)
    val bmbRspValid  = out Bool()
    val bmbRspData   = out Bits(32 bits)
  }

  // JOP Core core
  val jopCore = JopCore(config, romInit, ramInit, jbcInit)

  // 32-bit BMB to 16-bit SDRAM bridge (handles width conversion internally)
  val sdramCtrl = BmbSdramCtrl32(
    bmbParameter = config.memConfig.bmbParameter,
    layout = sdramLayout,
    timing = sdramTiming,
    CAS = CAS
  )

  // Connect JOP BMB directly to SDRAM controller
  sdramCtrl.io.bmb <> jopCore.io.bmb

  // SDRAM interface
  io.sdram <> sdramCtrl.io.sdram

  // I/O interface
  io.ioAddr := jopCore.io.ioAddr
  io.ioRd := jopCore.io.ioRd
  io.ioWr := jopCore.io.ioWr
  io.ioWrData := jopCore.io.ioWrData
  jopCore.io.ioRdData := io.ioRdData

  // Pipeline outputs
  io.pc := jopCore.io.pc
  io.jpc := jopCore.io.jpc
  io.instr := jopCore.io.instr
  io.jfetch := jopCore.io.jfetch
  io.jopdfetch := jopCore.io.jopdfetch

  io.aout := jopCore.io.aout
  io.bout := jopCore.io.bout

  io.memBusy := jopCore.io.memBusy

  // Interrupt / Exception
  jopCore.io.irq := io.irq
  jopCore.io.irqEna := io.irqEna
  jopCore.io.exc := io.exc

  // BMB debug
  io.bmbCmdValid := jopCore.io.bmb.cmd.valid
  io.bmbCmdReady := jopCore.io.bmb.cmd.ready
  io.bmbCmdAddr := jopCore.io.bmb.cmd.fragment.address
  io.bmbCmdOpcode := jopCore.io.bmb.cmd.fragment.opcode.asBits.resized
  io.bmbRspValid := jopCore.io.bmb.rsp.valid
  io.bmbRspData := jopCore.io.bmb.rsp.fragment.data
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
