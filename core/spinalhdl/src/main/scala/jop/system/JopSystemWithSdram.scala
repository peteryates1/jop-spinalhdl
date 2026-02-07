package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.memory.sdram._
import spinal.lib.memory.sdram.sdr._
import jop.memory.BmbSdramCtrlFixed

/**
 * JOP System with SDR SDRAM Backend
 *
 * Complete JOP system using BmbSdramCtrl for external SDRAM.
 * Target: W9825G6JH6 on QMTECH EP4CGX150 board.
 *
 * @param config System configuration
 * @param sdramLayout SDRAM chip layout
 * @param sdramTiming SDRAM timing parameters
 * @param CAS CAS latency (typically 2 or 3)
 * @param romInit Optional microcode ROM initialization
 * @param ramInit Optional stack RAM initialization
 * @param jbcInit Optional JBC RAM initialization
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
  }

  // JOP System core
  val jopSystem = JopSystem(config, romInit, ramInit, jbcInit)

  // JOP uses 32-bit data, W9825G6JH6 is 16-bit
  // Need a BmbDownSizerBridge to convert 32-bit -> 16-bit
  val inputBmbParam = config.memConfig.bmbParameter

  // Get source parameters from JOP's BMB config
  val jopSourceParam = inputBmbParam.access.sources.head._2

  // Create BMB parameters matching the SDRAM's address space
  // W9825G6JH6: 2 banks, 13 rows, 9 cols, 16-bit = 32MB byte addressable
  val sdramBmbAccessParam = BmbAccessParameter(
    addressWidth = sdramLayout.byteAddressWidth,
    dataWidth = sdramLayout.dataWidth
  ).addSources(1, BmbSourceParameter(
    contextWidth = jopSourceParam.contextWidth + 1,  // Extra bit for downsizer
    lengthWidth = jopSourceParam.lengthWidth,
    canWrite = true,
    canRead = true,
    alignment = BmbParameter.BurstAlignement.WORD
  ))

  val sdramBmbParam = BmbParameter(
    access = sdramBmbAccessParam,
    invalidation = BmbInvalidationParameter()
  )

  // Create intermediate BMB parameters for the downsizer output
  // Must have same address width as input, but 16-bit data
  val downsizerOutputAccessParam = BmbAccessParameter(
    addressWidth = sdramLayout.byteAddressWidth,  // Match SDRAM address space
    dataWidth = sdramLayout.dataWidth
  ).addSources(1, BmbSourceParameter(
    contextWidth = jopSourceParam.contextWidth + 1,  // Extra bit for sel
    lengthWidth = jopSourceParam.lengthWidth,
    canWrite = true,
    canRead = true,
    alignment = BmbParameter.BurstAlignement.WORD
  ))

  val downsizerOutputParam = BmbParameter(
    access = downsizerOutputAccessParam,
    invalidation = BmbInvalidationParameter()
  )

  // Create input parameters for downsizer (32-bit, SDRAM address space)
  val downsizerInputAccessParam = BmbAccessParameter(
    addressWidth = sdramLayout.byteAddressWidth,
    dataWidth = 32
  ).addSources(1, BmbSourceParameter(
    contextWidth = jopSourceParam.contextWidth,
    lengthWidth = jopSourceParam.lengthWidth,
    canWrite = true,
    canRead = true,
    alignment = BmbParameter.BurstAlignement.WORD
  ))

  val downsizerInputParam = BmbParameter(
    access = downsizerInputAccessParam,
    invalidation = BmbInvalidationParameter()
  )

  // Width adapter: 32-bit JOP -> 16-bit SDRAM
  val downSizer = BmbDownSizerBridge(downsizerInputParam, downsizerOutputParam)

  // Connect JOP BMB to downsizer (with address truncation)
  downSizer.io.input.cmd.valid := jopSystem.io.bmb.cmd.valid
  downSizer.io.input.cmd.last := jopSystem.io.bmb.cmd.last
  downSizer.io.input.cmd.fragment.opcode := jopSystem.io.bmb.cmd.fragment.opcode
  downSizer.io.input.cmd.fragment.address := jopSystem.io.bmb.cmd.fragment.address.resized
  downSizer.io.input.cmd.fragment.length := jopSystem.io.bmb.cmd.fragment.length
  downSizer.io.input.cmd.fragment.source := jopSystem.io.bmb.cmd.fragment.source.resized
  downSizer.io.input.cmd.fragment.context := jopSystem.io.bmb.cmd.fragment.context.resized
  downSizer.io.input.cmd.fragment.data := jopSystem.io.bmb.cmd.fragment.data
  downSizer.io.input.cmd.fragment.mask := jopSystem.io.bmb.cmd.fragment.mask
  jopSystem.io.bmb.cmd.ready := downSizer.io.input.cmd.ready

  jopSystem.io.bmb.rsp.valid := downSizer.io.input.rsp.valid
  jopSystem.io.bmb.rsp.last := downSizer.io.input.rsp.last
  jopSystem.io.bmb.rsp.fragment.opcode := downSizer.io.input.rsp.fragment.opcode
  jopSystem.io.bmb.rsp.fragment.data := downSizer.io.input.rsp.fragment.data
  jopSystem.io.bmb.rsp.fragment.source := downSizer.io.input.rsp.fragment.source.resized
  jopSystem.io.bmb.rsp.fragment.context := downSizer.io.input.rsp.fragment.context.resized
  downSizer.io.input.rsp.ready := jopSystem.io.bmb.rsp.ready

  // SDRAM Controller with BMB interface (using fixed version that handles rsp.last)
  val sdramCtrl = BmbSdramCtrlFixed(
    bmbParameter = downsizerOutputParam,
    layout = sdramLayout,
    timing = sdramTiming,
    CAS = CAS
  )

  // Connect downsized BMB to SDRAM controller (manual connection for debugging)
  // Command path: downsizer -> SDRAM controller
  sdramCtrl.io.bmb.cmd.valid := downSizer.io.output.cmd.valid
  sdramCtrl.io.bmb.cmd.last := downSizer.io.output.cmd.last
  sdramCtrl.io.bmb.cmd.fragment.opcode := downSizer.io.output.cmd.fragment.opcode
  sdramCtrl.io.bmb.cmd.fragment.address := downSizer.io.output.cmd.fragment.address
  sdramCtrl.io.bmb.cmd.fragment.length := downSizer.io.output.cmd.fragment.length
  sdramCtrl.io.bmb.cmd.fragment.data := downSizer.io.output.cmd.fragment.data
  sdramCtrl.io.bmb.cmd.fragment.mask := downSizer.io.output.cmd.fragment.mask
  sdramCtrl.io.bmb.cmd.fragment.source := downSizer.io.output.cmd.fragment.source
  sdramCtrl.io.bmb.cmd.fragment.context := downSizer.io.output.cmd.fragment.context
  downSizer.io.output.cmd.ready := sdramCtrl.io.bmb.cmd.ready

  // Response path: SDRAM controller -> downsizer
  downSizer.io.output.rsp.valid := sdramCtrl.io.bmb.rsp.valid
  downSizer.io.output.rsp.last := sdramCtrl.io.bmb.rsp.last
  downSizer.io.output.rsp.fragment.opcode := sdramCtrl.io.bmb.rsp.fragment.opcode
  downSizer.io.output.rsp.fragment.data := sdramCtrl.io.bmb.rsp.fragment.data
  downSizer.io.output.rsp.fragment.source := sdramCtrl.io.bmb.rsp.fragment.source
  downSizer.io.output.rsp.fragment.context := sdramCtrl.io.bmb.rsp.fragment.context
  sdramCtrl.io.bmb.rsp.ready := downSizer.io.output.rsp.ready

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
}

/**
 * Generate Verilog for JopSystemWithSdram
 */
object JopSystemWithSdramVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)  // SDRAM controller needs this
  ).generate(JopSystemWithSdram())
}
