package jop

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.pipeline._
import jop.core.Mul
import jop.memory.MemCtrlInput

/**
 * JOP Pipeline - All Pipeline Stages Integrated
 *
 * Builds all pipeline stages internally:
 *   BytecodeFetch -> Fetch -> Decode -> Stack + Multiplier
 *
 * Exposes a clean interface for connection to a memory controller.
 * The din mux and busy logic live inside the pipeline for proper encapsulation.
 *
 * @param config    System configuration
 * @param romInit   Optional microcode ROM initialization
 * @param ramInit   Optional stack RAM initialization
 * @param jbcInit   Optional JBC RAM initialization
 */
case class JopPipeline(
  config: jop.system.JopCoreConfig,
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // === Memory data inputs (from memory controller) ===
    val memRdData  = in Bits(32 bits)
    val memBcStart = in UInt(12 bits)
    val memBusy    = in Bool()

    // === JBC write (from memory controller for bytecode cache fill) ===
    val jbcWrAddr  = in UInt((config.jpcWidth - 2) bits)
    val jbcWrData  = in Bits(32 bits)
    val jbcWrEn    = in Bool()

    // === Memory control outputs (to memory controller) ===
    val memCtrl    = out(MemCtrlInput())
    val aout       = out Bits(config.dataWidth bits)
    val bout       = out Bits(config.dataWidth bits)
    val bcopd      = out Bits(16 bits)

    // === Interrupt / Exception ===
    val irq        = in Bool()
    val irqEna     = in Bool()
    val exc        = in Bool()   // Exception signal from I/O subsystem
    val ackIrq     = out Bool()  // Interrupt acknowledged (from bcfetch)
    val ackExc     = out Bool()  // Exception acknowledged (from bcfetch)

    // === Pipeline status ===
    val pc         = out UInt(config.pcWidth bits)
    val jpc        = out UInt((config.jpcWidth + 1) bits)
    val instr      = out Bits(config.instrWidth bits)
    val jfetch     = out Bool()
    val jopdfetch  = out Bool()

    // === Memory controller status ===
    val memBusyOut = out Bool()

    // === I/O passthrough from decode ===
    val debugBcRd           = out Bool()
    val debugAddrWr         = out Bool()
    val debugRdc            = out Bool()
    val debugRd             = out Bool()

    // === Debug: RAM slot read ===
    val debugRamAddr = in UInt(config.ramWidth bits)
    val debugRamData = out Bits(config.dataWidth bits)

    // === Debug: register values for debug controller ===
    val debugSp      = out UInt(config.stackConfig.spWidth bits)
    val debugVp      = out UInt(config.stackConfig.spWidth bits)
    val debugAr      = out UInt(config.stackConfig.spWidth bits)
    val debugFlags   = out Bits(4 bits)   // zf##nf##eq##lt
    val debugMulResult = out Bits(config.dataWidth bits)

    // === Stack Cache DMA Passthrough (only when stack cache enabled) ===
    // Bank RAM access (from StackCacheDma via JopCore)
    val dmaBankRdAddr  = if (config.stackConfig.useStackCache) Some(in UInt(8 bits)) else None
    val dmaBankRdData  = if (config.stackConfig.useStackCache) Some(out Bits(config.dataWidth bits)) else None
    val dmaBankWrAddr  = if (config.stackConfig.useStackCache) Some(in UInt(8 bits)) else None
    val dmaBankWrData  = if (config.stackConfig.useStackCache) Some(in Bits(config.dataWidth bits)) else None
    val dmaBankWrEn    = if (config.stackConfig.useStackCache) Some(in Bool()) else None
    val dmaBankSelect  = if (config.stackConfig.useStackCache) Some(in UInt(2 bits)) else None
    // DMA control (from stack stage rotation controller)
    val dmaStart     = if (config.stackConfig.useStackCache) Some(out Bool()) else None
    val dmaIsSpill   = if (config.stackConfig.useStackCache) Some(out Bool()) else None
    val dmaExtAddr   = if (config.stackConfig.useStackCache) Some(out UInt(26 bits)) else None
    val dmaWordCount = if (config.stackConfig.useStackCache) Some(out UInt(8 bits)) else None
    val dmaBank      = if (config.stackConfig.useStackCache) Some(out UInt(2 bits)) else None
    // DMA status (from StackCacheDma via JopCore)
    val dmaBusy = if (config.stackConfig.useStackCache) Some(in Bool()) else None
    val dmaDone = if (config.stackConfig.useStackCache) Some(in Bool()) else None

    // Stack cache debug (passthrough from StackStage)
    val scDebugRotState     = if (config.stackConfig.useStackCache) Some(out UInt(3 bits)) else None
    val scDebugActiveBankIdx = if (config.stackConfig.useStackCache) Some(out UInt(2 bits)) else None
    val scDebugBankBase     = if (config.stackConfig.useStackCache) Some(out Vec(UInt(config.stackConfig.spWidth bits), 3)) else None
    val scDebugBankResident = if (config.stackConfig.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugBankDirty    = if (config.stackConfig.useStackCache) Some(out Bits(3 bits)) else None
    val scDebugNeedsRot     = if (config.stackConfig.useStackCache) Some(out Bool()) else None

    // Write-snoop debug (passthrough from StackStage)
    val scDebugPipeWrAddr = if (config.stackConfig.useStackCache) Some(out UInt(config.stackConfig.spWidth bits)) else None
    val scDebugPipeWrData = if (config.stackConfig.useStackCache) Some(out Bits(config.dataWidth bits)) else None
    val scDebugPipeWrEn   = if (config.stackConfig.useStackCache) Some(out Bool()) else None

    // VP+0 value readback (passthrough from StackStage)
    val scDebugVp0Data = if (config.stackConfig.useStackCache) Some(out Bits(config.dataWidth bits)) else None
  }

  // ==========================================================================
  // Pipeline Stages
  // ==========================================================================

  val bcfetch = BytecodeFetchStage(config.bcfetchConfig, jbcInit)
  val fetch   = FetchStage(config.fetchConfig, romInit)
  val decode  = new DecodeStage(config.decodeConfig)
  val stack   = new StackStage(config.stackConfig, ramInit = ramInit).setName("stackStg")
  val mul     = Mul(config.dataWidth)

  // ==========================================================================
  // Pipeline Connections
  // ==========================================================================

  // BytecodeFetch connections
  bcfetch.io.jfetch := fetch.io.nxt
  bcfetch.io.jopdfetch := fetch.io.opd
  bcfetch.io.jbr := decode.io.jbr
  bcfetch.io.zf := stack.io.zf
  bcfetch.io.nf := stack.io.nf
  bcfetch.io.eq := stack.io.eq
  bcfetch.io.lt := stack.io.lt
  bcfetch.io.jpc_wr := decode.io.enaJpc
  bcfetch.io.din := stack.io.aout

  // JBC write from memory controller (for bytecode cache fill)
  bcfetch.io.jbcWrAddr := io.jbcWrAddr
  bcfetch.io.jbcWrData := io.jbcWrData
  bcfetch.io.jbcWrEn := io.jbcWrEn

  // Interrupt/exception
  bcfetch.io.irq := io.irq
  bcfetch.io.exc := io.exc
  bcfetch.io.ena := io.irqEna
  io.ackIrq := bcfetch.io.ack_irq
  io.ackExc := bcfetch.io.ack_exc

  // Fetch stage connections
  fetch.io.jpaddr := bcfetch.io.jpaddr
  fetch.io.br := decode.io.br
  fetch.io.jmp := decode.io.jmp
  // rotationBusy stalls the pipeline during stack cache bank rotation (DMA).
  // extStall unconditionally freezes the fetch stage PC/IR — needed because
  // rotation can trigger on any instruction, not just 'wait' instructions.
  val stackRotBusy = stack.io.rotationBusy.getOrElse(False)
  fetch.io.bsy := decode.io.wrDly || io.memBusy || stackRotBusy
  fetch.io.extStall := stackRotBusy
  decode.io.stall := stackRotBusy
  bcfetch.io.stall := stackRotBusy  // Freeze jopd/jpc during rotation

  // Decode stage connections
  decode.io.instr := fetch.io.dout
  decode.io.zf := stack.io.zf
  decode.io.nf := stack.io.nf
  decode.io.eq := stack.io.eq
  decode.io.lt := stack.io.lt
  decode.io.bcopd := bcfetch.io.opd

  // ==========================================================================
  // Stack Stage Connections
  // ==========================================================================

  // din mux: ir(1:0) selects between ldmrd, ldmul, ldbcstart
  val dinMuxSel = RegNext(fetch.io.ir_out(1 downto 0)) init(0)
  dinMuxSel.simPublic()
  stack.io.din := dinMuxSel.mux(
    0 -> io.memRdData,
    1 -> mul.io.dout.asBits,
    2 -> io.memBcStart.asBits.resized,
    3 -> B(0, 32 bits)
  )

  stack.io.dirAddr := decode.io.dirAddr.asUInt
  stack.io.opd := bcfetch.io.opd
  stack.io.jpc := bcfetch.io.jpc_out

  // Control signals from decode
  stack.io.selSub := decode.io.selSub
  stack.io.selAmux := decode.io.selAmux
  stack.io.enaA := decode.io.enaA
  stack.io.selBmux := decode.io.selBmux
  stack.io.selLog := decode.io.selLog
  stack.io.selShf := decode.io.selShf
  stack.io.selLmux := decode.io.selLmux
  stack.io.selImux := decode.io.selImux
  stack.io.selRmux := decode.io.selRmux
  stack.io.selSmux := decode.io.selSmux
  stack.io.selMmux := decode.io.selMmux
  stack.io.selRda := decode.io.selRda
  stack.io.selWra := decode.io.selWra
  stack.io.wrEna := decode.io.wrEna
  stack.io.enaB := decode.io.enaB
  stack.io.enaVp := decode.io.enaVp
  stack.io.enaAr := decode.io.enaAr

  // Debug ports
  stack.io.debugRamAddr := io.debugRamAddr
  stack.io.debugRamWrAddr := 0
  stack.io.debugRamWrData := 0
  stack.io.debugRamWrEn := False
  io.debugRamData := stack.io.debugRamData

  // ==========================================================================
  // Multiplier
  // ==========================================================================

  mul.io.ain := stack.io.aout.asUInt
  mul.io.bin := stack.io.bout.asUInt
  mul.io.wr := decode.io.mulWr

  // ==========================================================================
  // Output Connections
  // ==========================================================================

  // Memory control signals from decode (wire field-by-field: MemoryControl -> MemCtrlInput)
  io.memCtrl.rd := decode.io.memIn.rd
  io.memCtrl.rdc := decode.io.memIn.rdc
  io.memCtrl.rdf := decode.io.memIn.rdf
  io.memCtrl.wr := decode.io.memIn.wr
  io.memCtrl.wrf := decode.io.memIn.wrf
  io.memCtrl.addrWr := decode.io.memIn.addrWr
  io.memCtrl.bcRd := decode.io.memIn.bcRd
  io.memCtrl.stidx := decode.io.memIn.stidx
  io.memCtrl.iaload := decode.io.memIn.iaload
  io.memCtrl.iastore := decode.io.memIn.iastore
  io.memCtrl.getfield := decode.io.memIn.getfield
  io.memCtrl.putfield := decode.io.memIn.putfield
  io.memCtrl.putref := decode.io.memIn.putref
  io.memCtrl.getstatic := decode.io.memIn.getstatic
  io.memCtrl.putstatic := decode.io.memIn.putstatic
  io.memCtrl.copy := decode.io.memIn.copy
  io.memCtrl.cinval := decode.io.memIn.cinval
  io.memCtrl.atmstart := decode.io.memIn.atmstart
  io.memCtrl.atmend := decode.io.memIn.atmend
  io.memCtrl.bcopd := decode.io.memIn.bcopd

  // Stack outputs
  io.aout := stack.io.aout
  io.bout := stack.io.bout

  // Bytecode operand
  io.bcopd := bcfetch.io.opd

  // Pipeline status
  io.pc := fetch.io.pc_out
  io.jpc := bcfetch.io.jpc_out
  io.instr := fetch.io.dout
  io.jfetch := fetch.io.nxt
  io.jopdfetch := fetch.io.opd

  // Memory busy (pipeline-level: includes wrDly)
  io.memBusyOut := io.memBusy

  // Debug
  io.debugBcRd := decode.io.memIn.bcRd
  io.debugAddrWr := decode.io.memIn.addrWr
  io.debugRdc := decode.io.memIn.rdc
  io.debugRd := decode.io.memIn.rd

  // Debug registers for debug controller
  io.debugSp := stack.io.debugSp
  io.debugVp := stack.io.debugVp
  io.debugAr := stack.io.debugAr
  io.debugFlags := stack.io.zf ## stack.io.nf ## stack.io.eq ## stack.io.lt
  io.debugMulResult := mul.io.dout.asBits

  // ==========================================================================
  // Stack Cache DMA Passthrough
  // ==========================================================================

  if (config.stackConfig.useStackCache) {
    // Bank RAM access: DMA → stack stage
    stack.io.dmaBankRdAddr.get := io.dmaBankRdAddr.get
    io.dmaBankRdData.get := stack.io.dmaBankRdData.get
    stack.io.dmaBankWrAddr.get := io.dmaBankWrAddr.get
    stack.io.dmaBankWrData.get := io.dmaBankWrData.get
    stack.io.dmaBankWrEn.get := io.dmaBankWrEn.get
    stack.io.dmaBankSelect.get := io.dmaBankSelect.get

    // DMA control: stack stage rotation controller → JopCore
    io.dmaStart.get := stack.io.dmaStart.get
    io.dmaIsSpill.get := stack.io.dmaIsSpill.get
    io.dmaExtAddr.get := stack.io.dmaExtAddr.get
    io.dmaWordCount.get := stack.io.dmaWordCount.get
    io.dmaBank.get := stack.io.dmaBank.get

    // DMA status: JopCore → stack stage
    stack.io.dmaBusy.get := io.dmaBusy.get
    stack.io.dmaDone.get := io.dmaDone.get

    // Debug passthrough
    io.scDebugRotState.get := stack.io.scDebugRotState.get
    io.scDebugActiveBankIdx.get := stack.io.scDebugActiveBankIdx.get
    io.scDebugBankBase.get := stack.io.scDebugBankBase.get
    io.scDebugBankResident.get := stack.io.scDebugBankResident.get
    io.scDebugBankDirty.get := stack.io.scDebugBankDirty.get
    io.scDebugNeedsRot.get := stack.io.scDebugNeedsRot.get

    // Write-snoop debug passthrough
    io.scDebugPipeWrAddr.get := stack.io.scDebugPipeWrAddr.get
    io.scDebugPipeWrData.get := stack.io.scDebugPipeWrData.get
    io.scDebugPipeWrEn.get := stack.io.scDebugPipeWrEn.get

    // VP+0 readback passthrough
    io.scDebugVp0Data.get := stack.io.scDebugVp0Data.get
  }
}
