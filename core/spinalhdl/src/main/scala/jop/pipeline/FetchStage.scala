package jop.pipeline

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/**
 * Fetch Stage Configuration
 *
 * Configures the microcode fetch stage parameters.
 *
 * @param pcWidth  Address bits of internal instruction ROM (default: 10)
 * @param iWidth   Instruction width (default: 10)
 */
case class FetchConfig(
  pcWidth: Int = 10,
  iWidth: Int = 10
) {
  require(pcWidth > 0, "PC width must be positive")
  require(iWidth > 0, "Instruction width must be positive")

  /** ROM depth (number of entries) */
  def romDepth: Int = 1 << pcWidth

  /** ROM data width (instruction + jfetch + jopdfetch) */
  def romWidth: Int = iWidth + 2

  /** Wait instruction opcode */
  def waitOpcode: Int = 0x101  // 0b0100000001
}

/**
 * Fetch Stage Payloads
 *
 * Defines the data that flows through the fetch pipeline stage.
 * These payloads can be accessed from downstream pipeline stages.
 */
object FetchPayloads {
  /** Program counter value */
  def PC(config: FetchConfig) = Payload(UInt(config.pcWidth bits))

  /** Fetched instruction (i_width bits) */
  def INSTRUCTION(config: FetchConfig) = Payload(Bits(config.iWidth bits))

  /** Java bytecode fetch signal (from ROM) */
  val JFETCH = Payload(Bool())

  /** Java operand fetch signal (from ROM) */
  val JOPDFETCH = Payload(Bool())

  /** PC is waiting (wait instruction detected) */
  val PC_WAIT = Payload(Bool())
}

/**
 * Microcode ROM Fetch Stage
 *
 * Implements the fetch stage of the JOP microcode pipeline using SpinalHDL Pipeline API.
 *
 * Architecture:
 * - Microcode ROM with registered address, unregistered output
 * - PC control with priority: jfetch > branch > jump > stall > increment
 * - 6-bit signed branch offset from ir[5:0]
 * - 9-bit signed jump offset from ir[8:0]
 * - Wait instruction (0x101) stalls PC when bsy=1
 *
 * Pipeline Timing:
 * - Cycle N: pc_mux calculated, ROM address registered
 * - Cycle N+1: ROM output available, IR captures instruction, offsets calculated
 * - dout has 1-cycle latency from PC change
 *
 * Translated from: original/vhdl/core/fetch.vhd
 *
 * @param config Fetch stage configuration
 * @param romInit Optional ROM initialization data (for testing)
 */
case class FetchStage(
  config: FetchConfig = FetchConfig(),
  romInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Control inputs
    val br     = in Bool()                          // Branch control signal
    val jmp    = in Bool()                          // Jump control signal
    val bsy    = in Bool()                          // Memory busy signal
    val jpaddr = in UInt(config.pcWidth bits)       // Jump address for Java bytecode fetch

    // Outputs
    val nxt    = out Bool()                         // jfetch signal (fetch Java bytecode)
    val opd    = out Bool()                         // jopdfetch signal (fetch Java operand)
    val dout   = out Bits(config.iWidth bits)       // Instruction output

    // Debug outputs (for CocoTB test compatibility)
    val pc_out = out UInt(config.pcWidth bits)      // Current PC for test observation
    val ir_out = out Bits(config.iWidth bits)       // Instruction register for test
  }

  // ==========================================================================
  // Microcode ROM
  // ==========================================================================

  // ROM stores: [jfetch(1)][jopdfetch(1)][instruction(iWidth)]
  val rom = Mem(Bits(config.romWidth bits), config.romDepth)

  // Initialize ROM with provided data or default test pattern
  romInit match {
    case Some(data) =>
      rom.init(data.map(v => B(v, config.romWidth bits)))
    case None =>
      rom.init(initDefaultRom())
  }

  // ROM address register (registered address, unregistered output)
  val romAddrReg = Reg(UInt(config.pcWidth bits)) init(0)

  // Combinational ROM output (from registered address)
  val romData = rom.readAsync(romAddrReg)

  // Extract fields from ROM data
  val jfetch    = romData(config.iWidth + 1)
  val jopdfetch = romData(config.iWidth)
  val romInstr  = romData(config.iWidth - 1 downto 0)

  // ==========================================================================
  // PC and Control Registers
  // ==========================================================================

  val pc     = Reg(UInt(config.pcWidth bits)) init(0)
  val brdly  = Reg(UInt(config.pcWidth bits)) init(0)  // Branch delay (target address)
  val jpdly  = Reg(UInt(config.pcWidth bits)) init(0)  // Jump delay (target address)
  val ir     = Reg(Bits(config.iWidth bits)) init(0)   // Instruction register
  val pcwait = Reg(Bool()) init(False)                  // Wait state flag

  // ==========================================================================
  // PC Increment (Combinational)
  // ==========================================================================

  val pcInc = pc + 1

  // ==========================================================================
  // PC MUX with Priority Logic
  // ==========================================================================
  // Priority (highest to lowest):
  // 1. jfetch='1': Load from jpaddr (Java bytecode dispatch)
  // 2. br='1': Load from brdly (branch target)
  // 3. jmp='1': Load from jpdly (jump target)
  // 4. pcwait='1' AND bsy='1': Hold current PC (wait instruction stall)
  // 5. Default: Increment PC (pc + 1)

  val pcMux = UInt(config.pcWidth bits)

  when(jfetch) {
    pcMux := io.jpaddr
  }.elsewhen(io.br) {
    pcMux := brdly
  }.elsewhen(io.jmp) {
    pcMux := jpdly
  }.elsewhen(pcwait && io.bsy) {
    pcMux := pc  // Stall - hold current PC
  }.otherwise {
    pcMux := pcInc  // Normal increment
  }

  // ==========================================================================
  // Clocked Logic
  // ==========================================================================

  // ROM address register update
  romAddrReg := pcMux

  // IR and pcwait update (no reset needed per original VHDL)
  ir := romInstr

  // Decode wait instruction from unregistered ROM output
  pcwait := False
  when(romInstr === B(config.waitOpcode, config.iWidth bits)) {
    pcwait := True
  }

  // PC and offset register update (with async reset behavior)
  when(clockDomain.isResetActive) {
    pc    := 0
    brdly := 0
    jpdly := 0
  }.otherwise {
    // 6-bit signed branch offset from ir[5:0]
    val branchOffset = ir(5 downto 0).asSInt.resize(config.pcWidth bits)
    brdly := (pc.asSInt + branchOffset).asUInt

    // 9-bit signed jump offset from ir[iWidth-2:0] (bits 8:0)
    val jumpOffset = ir(config.iWidth - 2 downto 0).asSInt.resize(config.pcWidth bits)
    jpdly := (pc.asSInt + jumpOffset).asUInt

    pc := pcMux
  }

  // ==========================================================================
  // Pipeline Freeze During Memory Stall
  // ==========================================================================
  //
  // When the ROM outputs a "wait" instruction (0x101) and the memory
  // controller is busy, freeze the entire fetch stage. This holds PC, IR,
  // romAddrReg, and pcwait until bsy goes low.
  //
  // The JOP microcode uses DOUBLE wait instructions (e.g., at PCs 01da-01db
  // and 0195-0196) to ensure both the decode and execute/stack stages are
  // properly stalled:
  // - 1st wait: sets pcwait register (takes effect next cycle). Decode still
  //   processes the previous instruction (last real operation before freeze).
  // - 2nd wait: pcwait is now True. If bsy=True, pipeline freezes. IR holds
  //   the 1st wait instruction (NOP for decode), so decode sees NOP.
  //
  // Last-assignment-wins in SpinalHDL: this overrides the assignments above.
  when(pcwait && io.bsy) {
    romAddrReg := romAddrReg  // Hold ROM address
    ir         := ir          // Hold IR on wait instruction
    pcwait     := True        // Keep stall active
    pc         := pc          // Hold PC
  }

  // ==========================================================================
  // Output Assignments
  // ==========================================================================

  io.nxt    := jfetch
  io.opd    := jopdfetch
  io.dout   := ir

  // Debug outputs
  io.pc_out := pc
  io.ir_out := ir

  // ==========================================================================
  // Pipeline API Integration (for downstream stages)
  // ==========================================================================

  /**
   * Pipeline node for downstream connection.
   *
   * This node exposes the fetch stage outputs as Pipeline API payloads
   * that can be connected to downstream stages using StageLink.
   *
   * Usage in a pipeline:
   *   val fetchStage = FetchStage(config)
   *   val decodeNode = Node()
   *   val link = StageLink(fetchStage.outputNode, decodeNode)
   *   Builder(link)
   *
   * Note: The outputNode must be connected to a downstream stage and built
   * via Builder() when used in a pipeline. For standalone testing, use
   * FetchStageTb which does not include the Pipeline API node.
   */
  val outputNode = Node()

  // Create payload instances for this configuration
  val PC_PAYLOAD = FetchPayloads.PC(config)
  val INSTR_PAYLOAD = FetchPayloads.INSTRUCTION(config)

  // Export payloads to output node
  outputNode(PC_PAYLOAD) := pc
  outputNode(INSTR_PAYLOAD) := ir
  outputNode(FetchPayloads.JFETCH) := jfetch
  outputNode(FetchPayloads.JOPDFETCH) := jopdfetch
  outputNode(FetchPayloads.PC_WAIT) := pcwait

  // Valid signal - fetch is always producing valid output after reset
  outputNode.valid := True

  // Ready signal - always accept for standalone operation
  // This provides a default; in a real pipeline it will be driven by downstream
  outputNode.ready := True

  // ==========================================================================
  // Helper Functions
  // ==========================================================================

  /**
   * Initialize ROM with default test pattern
   * Matches the test pattern in verification/cocotb/vhdl/fetch_tb.vhd
   */
  private def initDefaultRom(): Seq[Bits] = {
    val rom = Array.fill(config.romDepth)(B(0, config.romWidth bits))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (config.iWidth + 1)) | (jod << config.iWidth) | instr
      rom(addr) = B(value, config.romWidth bits)
    }

    // Address 0: NOP (skipped during reset)
    setRom(0, 0, 0, 0x000)

    // Address 1: Regular NOP instruction
    setRom(1, 0, 0, 0x000)

    // Address 2: Wait instruction (0x101)
    setRom(2, 0, 0, 0x101)

    // Address 3: Branch offset +5 in bits [5:0]
    setRom(3, 0, 0, 0x005)

    // Address 4: Branch offset -3 in bits [5:0] (0x3D = 0b111101)
    setRom(4, 0, 0, 0x03D)

    // Address 5: Jump offset +10 in bits [8:0]
    setRom(5, 0, 0, 0x00A)

    // Address 6-8: Sequential test values
    setRom(6, 0, 0, 0x006)
    setRom(7, 0, 0, 0x007)
    setRom(8, 0, 0, 0x008)

    // Address 9: Jump offset -5 in bits [8:0] (0x1FB = 0b111111011)
    setRom(9, 0, 0, 0x1FB)

    // Address 10: Distinct pattern
    setRom(10, 0, 0, 0x2AA)

    // Address 11: Another wait instruction
    setRom(11, 0, 0, 0x101)

    // Address 12-15: Sequence markers
    for (i <- 12 to 15) {
      setRom(i, 0, 0, i)
    }

    // Address 16-31: Incrementing pattern
    for (i <- 16 to 31) {
      setRom(i, 0, 0, i)
    }

    // Address 32: Branch offset +31 (max positive 6-bit)
    setRom(32, 0, 0, 0x01F)

    // Address 50: jfetch=1 instruction
    setRom(50, 1, 0, 0x000)

    // Address 51: jopdfetch=1 instruction
    setRom(51, 0, 1, 0x000)

    // Address 52: Both jfetch=1 and jopdfetch=1
    setRom(52, 1, 1, 0x000)

    // Address 64: Branch offset -32 (max negative 6-bit)
    setRom(64, 0, 0, 0x020)

    // Address 100: Jump offset +255 (max positive 9-bit)
    setRom(100, 0, 0, 0x0FF)

    // Address 300: Jump offset -256 (max negative 9-bit)
    setRom(300, 0, 0, 0x100)

    // Address 1023: Max PC (wraparound test)
    setRom(1023, 0, 0, 0x3FF)

    rom.toSeq
  }
}

/**
 * Test Bench Wrapper for FetchStage (CocoTB Compatible)
 *
 * This wrapper provides the same interface as fetch_tb.vhd for CocoTB testing.
 * Uses noIoPrefix() to remove the io_ prefix from port names.
 *
 * Interface matches fetch_tb.vhd exactly:
 * - clk, reset (std_logic)
 * - br, jmp, bsy (std_logic inputs)
 * - jpaddr (std_logic_vector input)
 * - nxt, opd (std_logic outputs)
 * - dout (std_logic_vector output)
 * - pc_out, ir_out (std_logic_vector debug outputs)
 */
case class FetchStageTb(
  pcWidth: Int = 10,
  iWidth: Int = 10
) extends Component {

  // Use noIoPrefix to match VHDL interface naming
  noIoPrefix()

  val clk = in Bool()      // Note: SpinalHDL uses its own clock domain
  val reset = in Bool()

  val br     = in Bool()
  val jmp    = in Bool()
  val bsy    = in Bool()
  val jpaddr = in Bits(pcWidth bits)    // std_logic_vector for VHDL compatibility

  val nxt    = out Bool()
  val opd    = out Bool()
  val dout   = out Bits(iWidth bits)

  val pc_out = out Bits(pcWidth bits)   // std_logic_vector for VHDL compatibility
  val ir_out = out Bits(iWidth bits)

  // Create a new clock domain using the explicit clk and reset signals
  val fetchClockDomain = ClockDomain(
    clock = clk,
    reset = reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,          // Async reset like original VHDL
      resetActiveLevel = HIGH
    )
  )

  // Instantiate the fetch stage with the explicit clock domain
  val fetchArea = new ClockingArea(fetchClockDomain) {
    val config = FetchConfig(pcWidth, iWidth)

    // ==========================================================================
    // Microcode ROM
    // ==========================================================================
    val rom = Mem(Bits(config.romWidth bits), config.romDepth)
    rom.init(initDefaultRom(config))

    val romAddrReg = Reg(UInt(config.pcWidth bits)) init(0)
    val romData = rom.readAsync(romAddrReg)

    val jfetch    = romData(config.iWidth + 1)
    val jopdfetch = romData(config.iWidth)
    val romInstr  = romData(config.iWidth - 1 downto 0)

    // ==========================================================================
    // PC and Control Registers
    // ==========================================================================
    val pc     = Reg(UInt(config.pcWidth bits)) init(0)
    val brdly  = Reg(UInt(config.pcWidth bits)) init(0)
    val jpdly  = Reg(UInt(config.pcWidth bits)) init(0)
    val ir     = Reg(Bits(config.iWidth bits)) init(0)
    val pcwait = Reg(Bool()) init(False)

    val pcInc = pc + 1

    // ==========================================================================
    // PC MUX with Priority Logic
    // ==========================================================================
    val pcMux = UInt(config.pcWidth bits)

    when(jfetch) {
      pcMux := jpaddr.asUInt
    }.elsewhen(br) {
      pcMux := brdly
    }.elsewhen(jmp) {
      pcMux := jpdly
    }.elsewhen(pcwait && bsy) {
      pcMux := pc
    }.otherwise {
      pcMux := pcInc
    }

    // ==========================================================================
    // Clocked Logic
    // ==========================================================================
    romAddrReg := pcMux
    ir := romInstr

    pcwait := False
    when(romInstr === B(config.waitOpcode, config.iWidth bits)) {
      pcwait := True
    }

    // PC and offset register update
    // 6-bit signed branch offset from ir[5:0]
    val branchOffset = ir(5 downto 0).asSInt.resize(config.pcWidth bits)
    brdly := (pc.asSInt + branchOffset).asUInt

    // 9-bit signed jump offset from ir[iWidth-2:0] (bits 8:0)
    val jumpOffset = ir(config.iWidth - 2 downto 0).asSInt.resize(config.pcWidth bits)
    jpdly := (pc.asSInt + jumpOffset).asUInt

    pc := pcMux

    // Pipeline freeze during memory stall (see FetchStage for explanation)
    when(pcwait && bsy) {
      romAddrReg := romAddrReg
      ir         := ir
      pcwait     := True
      pc         := pc
    }

    // ==========================================================================
    // Output Assignments
    // ==========================================================================
    nxt    := jfetch
    opd    := jopdfetch
    dout   := ir
    pc_out := pc.asBits
    ir_out := ir
  }

  /**
   * Initialize ROM with default test pattern
   */
  private def initDefaultRom(config: FetchConfig): Seq[Bits] = {
    val rom = Array.fill(config.romDepth)(B(0, config.romWidth bits))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (config.iWidth + 1)) | (jod << config.iWidth) | instr
      rom(addr) = B(value, config.romWidth bits)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x101)
    setRom(3, 0, 0, 0x005)
    setRom(4, 0, 0, 0x03D)
    setRom(5, 0, 0, 0x00A)
    setRom(6, 0, 0, 0x006)
    setRom(7, 0, 0, 0x007)
    setRom(8, 0, 0, 0x008)
    setRom(9, 0, 0, 0x1FB)
    setRom(10, 0, 0, 0x2AA)
    setRom(11, 0, 0, 0x101)

    for (i <- 12 to 15) { setRom(i, 0, 0, i) }
    for (i <- 16 to 31) { setRom(i, 0, 0, i) }

    setRom(32, 0, 0, 0x01F)
    setRom(50, 1, 0, 0x000)
    setRom(51, 0, 1, 0x000)
    setRom(52, 1, 1, 0x000)
    setRom(64, 0, 0, 0x020)
    setRom(100, 0, 0, 0x0FF)
    setRom(300, 0, 0, 0x100)
    setRom(1023, 0, 0, 0x3FF)

    rom.toSeq
  }
}

/**
 * Generate VHDL for CocoTB verification
 */
object FetchStageVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/vhdl",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    device = Device.ALTERA
  )

  // Generate the testbench wrapper (matches fetch_tb interface)
  config.generateVhdl(FetchStageTb(pcWidth = 10, iWidth = 10))
    .printPruned()

  println("FetchStageTb VHDL generated at: generated/vhdl/FetchStageTb.vhd")
}

/**
 * Generate Verilog for synthesis
 */
object FetchStageVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  )

  config.generateVerilog(FetchStage())
    .printPruned()

  println("FetchStage Verilog generated at: generated/verilog/FetchStage.v")
}
