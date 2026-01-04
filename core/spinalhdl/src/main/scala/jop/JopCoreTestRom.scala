package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._

/**
 * JOP Core Test ROM Configurations
 *
 * Provides custom ROM initializations for testing specific microcode instructions.
 * Each configuration loads the ROM with specific instruction sequences to test
 * particular functionality.
 *
 * Instruction Format (10 bits):
 * - ir[9:6]: Instruction class (0000=POP/ALU, 0010=PUSH, 0110=BZ, 0111=BNZ, 1xxx=JMP)
 * - ir[5:0]: Operand/offset depending on instruction
 *
 * Based on DecodeStage.scala instruction decoding.
 */
object TestRomPatterns {

  /**
   * ALU Operations Test ROM
   *
   * Tests basic ALU operations: add, sub, and, or, xor
   *
   * Instructions:
   * - Addr 0-1: NOP (initialization)
   * - Addr 2: 0x000 - POP, pass-through (ir[1:0]=00, selLog=00)
   * - Addr 3: 0x001 - POP, AND (ir[1:0]=01, selLog=01)
   * - Addr 4: 0x002 - POP, OR (ir[1:0]=10, selLog=10)
   * - Addr 5: 0x003 - POP, XOR (ir[1:0]=11, selLog=11)
   * - Addr 6: 0x004 - POP, ADD (ir[9:6]=0000, selSub=0)
   * - Addr 7: 0x040 - POP, SUB (ir[9:6]=0001, selSub=1)
   */
  def aluOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    // Initialization NOPs
    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)

    // ALU operations (all are POP class, ir[9:6]=0000)
    setRom(2, 0, 0, 0x000)  // pass-through (selLog=00)
    setRom(3, 0, 0, 0x001)  // AND (selLog=01)
    setRom(4, 0, 0, 0x002)  // OR (selLog=10)
    setRom(5, 0, 0, 0x003)  // XOR (selLog=11)
    setRom(6, 0, 0, 0x004)  // ADD (ir[9:6]=0000, selSub=0)
    setRom(7, 0, 0, 0x040)  // SUB (ir[9:6]=0001, selSub=1)

    // Fill rest with NOPs
    for (i <- 8 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Shift Operations Test ROM
   *
   * Tests shift operations: USHR, SHL, SHR
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x01C - USHR (ir[9:2]=00000111, ir[1:0]=00)
   * - Addr 3: 0x01D - SHL (ir[9:2]=00000111, ir[1:0]=01)
   * - Addr 4: 0x01E - SHR (ir[9:2]=00000111, ir[1:0]=10)
   */
  def shiftOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x01C)  // USHR
    setRom(3, 0, 0, 0x01D)  // SHL
    setRom(4, 0, 0, 0x01E)  // SHR

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Load/Store Operations Test ROM
   *
   * Tests memory operations: ldm, stm, ldi
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0A0 - ldm (ir[9:5]=00101, load from memory)
   * - Addr 3: 0x020 - stm (ir[9:5]=00001, store to memory)
   * - Addr 4: 0x0C0 - ldi (ir[9:5]=00110, load immediate)
   */
  def loadStoreRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0A0)  // ldm
    setRom(3, 0, 0, 0x020)  // stm
    setRom(4, 0, 0, 0x0C0)  // ldi

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Branch Operations Test ROM
   *
   * Tests conditional branches: BZ, BNZ
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x180 - BZ (ir[9:6]=0110, branch if zero)
   * - Addr 3: 0x1C0 - BNZ (ir[9:6]=0111, branch if not zero)
   */
  def branchOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x180)  // BZ
    setRom(3, 0, 0, 0x1C0)  // BNZ

    for (i <- 4 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }
}

/**
 * JOP Core with Custom Test ROM
 *
 * Allows specifying custom ROM contents for testing specific microcode instructions.
 */
class JopCoreTestRom(
  config: JopCoreConfig = JopCoreConfig(),
  romPattern: Seq[BigInt]
) extends Component {
  val io = JopCoreIO(config)

  // Create fetch stage with custom ROM
  val fetchStage = new FetchStage(
    config = config.fetchConfig,
    romInit = Some(romPattern)
  )

  val decodeStage = new DecodeStage(config.decodeConfig)
  val stackStage = new StackStage(config.stackConfig)

  // Same connections as JopCore
  fetchStage.io.br := decodeStage.io.br
  fetchStage.io.jmp := decodeStage.io.jmp
  fetchStage.io.bsy := io.memBusy
  fetchStage.io.jpaddr := U(0, config.pcWidth bits)

  io.jfetch := fetchStage.io.nxt
  io.jopdfetch := fetchStage.io.opd

  decodeStage.io.instr := fetchStage.io.dout
  decodeStage.io.zf := stackStage.io.zf
  decodeStage.io.nf := stackStage.io.nf
  decodeStage.io.eq := stackStage.io.eq
  decodeStage.io.lt := stackStage.io.lt
  decodeStage.io.bcopd := io.operand

  stackStage.io.din := io.memDataIn
  stackStage.io.dirAddr := decodeStage.io.dirAddr.asUInt
  stackStage.io.opd := io.operand
  stackStage.io.jpc := io.jpc

  stackStage.io.selSub := decodeStage.io.selSub
  stackStage.io.selAmux := decodeStage.io.selAmux
  stackStage.io.enaA := decodeStage.io.enaA
  stackStage.io.selBmux := decodeStage.io.selBmux
  stackStage.io.selLog := decodeStage.io.selLog
  stackStage.io.selShf := decodeStage.io.selShf
  stackStage.io.selLmux := decodeStage.io.selLmux
  stackStage.io.selImux := decodeStage.io.selImux
  stackStage.io.selRmux := decodeStage.io.selRmux
  stackStage.io.selSmux := decodeStage.io.selSmux
  stackStage.io.selMmux := decodeStage.io.selMmux
  stackStage.io.selRda := decodeStage.io.selRda
  stackStage.io.selWra := decodeStage.io.selWra
  stackStage.io.wrEna := decodeStage.io.wrEna
  stackStage.io.enaB := decodeStage.io.enaB
  stackStage.io.enaVp := decodeStage.io.enaVp
  stackStage.io.enaAr := decodeStage.io.enaAr

  io.aout := stackStage.io.aout
  io.bout := stackStage.io.bout
  io.spOv := stackStage.io.spOv

  fetchStage.setName("fetch")
  decodeStage.setName("decode")
  stackStage.setName("stack")
}

/**
 * Generate testbench with ALU operations ROM for CocoTB testing
 */
object JopCoreAluTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreAluTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.aluOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreAluTestTb)
  println("JopCoreAluTestTb VHDL generated in generated/JopCoreAluTestTb.vhd")
}
