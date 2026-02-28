package jop.pipeline

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.pipeline._

/**
 * Decode Stage Configuration
 *
 * Configures the microcode decode stage parameters.
 *
 * @param iWidth     Instruction width (default: 10)
 * @param ramWidth   Stack RAM address width (default: 8)
 * @param mmuWidth   MMU instruction width (default: 4)
 */
case class DecodeConfig(
  iWidth: Int = 10,
  ramWidth: Int = 8,
  mmuWidth: Int = 4
) {
  require(iWidth == 10, "Instruction width must be 10 bits")
  require(ramWidth > 0, "RAM width must be positive")
  require(mmuWidth == 4, "MMU width must be 4 bits")

  /** Wait instruction opcode */
  def waitOpcode: Int = 0x101  // 0b0100000001

  /** JBR instruction opcode */
  def jbrOpcode: Int = 0x102   // 0b0100000010
}

/**
 * MMU Instruction Constants
 *
 * These 4-bit instruction codes control memory operations.
 * Used with instruction prefix 0x04x (ir[9:4] = "000100").
 */
object MmuInstructions {
  // MMU instructions with stack pop (prefix 0x04x)
  // Note: Using 'def' instead of 'val' to avoid netlist reuse across multiple generators
  def STMUL  = B"4'b0000"  // Start multiplier
  def STMWA  = B"4'b0001"  // Store memory write address
  def STMRA  = B"4'b0010"  // Start memory read
  def STMWD  = B"4'b0011"  // Start memory write
  def STALD  = B"4'b0100"  // Array load
  def STAST  = B"4'b0101"  // Array store
  def STGF   = B"4'b0110"  // Get field
  def STPF   = B"4'b0111"  // Put field
  def STCP   = B"4'b1000"  // Copy
  def STBCR  = B"4'b1001"  // Bytecode read
  def STIDX  = B"4'b1010"  // Store index
  def STPS   = B"4'b1011"  // Put static
  def STMRAC = B"4'b1100"  // Memory read through constant cache
  def STMRAF = B"4'b1101"  // Memory read through full assoc cache
  def STMWDF = B"4'b1110"  // Memory write through full assoc cache
  def STPFR  = B"4'b1111"  // Put field reference

  // MMU instructions without stack change (prefix 0x11x)
  def STGS     = B"4'b0000"  // Get static
  def CINVAL   = B"4'b0001"  // Cache invalidate
  def ATMSTART = B"4'b0010"  // Atomic start
  def ATMEND   = B"4'b0011"  // Atomic end
}

/**
 * Memory Control Bundle
 *
 * This Bundle corresponds to the mem_in_type record in VHDL.
 * Contains all memory control signals output by the decode stage.
 */
case class MemoryControl() extends Bundle {
  val rd        = Bool()    // Memory read
  val wr        = Bool()    // Memory write
  val addrWr    = Bool()    // Address write
  val bcRd      = Bool()    // Bytecode read
  val stidx     = Bool()    // Store index
  val iaload    = Bool()    // Array load
  val iastore   = Bool()    // Array store
  val getfield  = Bool()    // Get object field
  val putfield  = Bool()    // Put object field
  val putref    = Bool()    // Put reference
  val getstatic = Bool()    // Get static field
  val putstatic = Bool()    // Put static field
  val rdc       = Bool()    // Read through constant cache
  val rdf       = Bool()    // Read through full assoc cache
  val wrf       = Bool()    // Write through full assoc cache
  val copy      = Bool()    // Copy operation
  val cinval    = Bool()    // Cache invalidate
  val atmstart  = Bool()    // Atomic start
  val atmend    = Bool()    // Atomic end
  val bcopd     = Bits(16 bits)  // Bytecode operand (passthrough)

  /** Initialize all control signals to inactive */
  override def clearAll(): this.type = {
    rd        := False
    wr        := False
    addrWr    := False
    bcRd      := False
    stidx     := False
    iaload    := False
    iastore   := False
    getfield  := False
    putfield  := False
    putref    := False
    getstatic := False
    putstatic := False
    rdc       := False
    rdf       := False
    wrf       := False
    copy      := False
    cinval    := False
    atmstart  := False
    atmend    := False
    bcopd     := B(0, 16 bits)
    this
  }
}

/**
 * Decode Stage Payloads
 *
 * Defines the data that flows through the decode pipeline stage.
 * These payloads can be accessed from downstream pipeline stages.
 */
object DecodePayloads {
  /** Branch enable */
  val BR = Payload(Bool())

  /** Jump enable */
  val JMP = Payload(Bool())

  /** Bytecode branch enable */
  val JBR = Payload(Bool())

  /** ALU subtract select (0=add, 1=sub) */
  val SEL_SUB = Payload(Bool())

  /** ALU mux select (0=sum, 1=lmux) */
  val SEL_AMUX = Payload(Bool())

  /** Enable A register */
  val ENA_A = Payload(Bool())

  /** B mux select (0=a, 1=mem) */
  val SEL_BMUX = Payload(Bool())

  /** Logic operation select */
  val SEL_LOG = Payload(Bits(2 bits))

  /** Shift operation select */
  val SEL_SHF = Payload(Bits(2 bits))

  /** Load mux select */
  val SEL_LMUX = Payload(Bits(3 bits))

  /** Immediate mux select */
  val SEL_IMUX = Payload(Bits(2 bits))

  /** Register mux select */
  val SEL_RMUX = Payload(Bits(2 bits))

  /** Stack pointer mux select */
  val SEL_SMUX = Payload(Bits(2 bits))

  /** Memory mux select */
  val SEL_MMUX = Payload(Bool())

  /** Read address mux select */
  val SEL_RDA = Payload(Bits(3 bits))

  /** Write address mux select */
  val SEL_WRA = Payload(Bits(3 bits))

  /** RAM write enable */
  val WR_ENA = Payload(Bool())

  /** Enable B register */
  val ENA_B = Payload(Bool())

  /** Enable VP register */
  val ENA_VP = Payload(Bool())

  /** Enable JPC register */
  val ENA_JPC = Payload(Bool())

  /** Enable AR register */
  val ENA_AR = Payload(Bool())

  /** Direct RAM address */
  def DIR(config: DecodeConfig) = Payload(Bits(config.ramWidth bits))

  /** Memory control signals */
  val MEM_IN = Payload(MemoryControl())

  /** MMU instruction select */
  val MMU_INSTR = Payload(Bits(4 bits))

  /** Multiplier write */
  val MUL_WR = Payload(Bool())

  /** Write delay */
  val WR_DLY = Payload(Bool())
}

/**
 * Microcode Decode Stage
 *
 * Implements the decode stage of the JOP microcode pipeline using SpinalHDL.
 *
 * Architecture:
 * - Decodes 10-bit microcode instructions into control signals
 * - Mixed timing: some outputs combinational, some registered
 * - Generates control signals for ALU, stack, and memory operations
 *
 * Timing Characteristics:
 * - Combinational outputs (0 cycle): jbr, sel_rda, sel_wra, sel_smux, wr_ena, dir, sel_imux
 * - Registered outputs (1 cycle): br, jmp, all ALU control, MMU control
 *
 * Translated from: /srv/git/jop/vhdl/core/decode.vhd
 *
 * @param config Decode stage configuration
 */
case class DecodeStage(
  config: DecodeConfig = DecodeConfig()
) extends Component {

  // Pre-compute widths for use in Bundle
  private val instrWidth = config.iWidth
  private val mmuWidth = config.mmuWidth
  private val ramWidth = config.ramWidth

  val io = new Bundle {
    // Inputs
    val instr  = in  Bits(instrWidth bits)
    val zf     = in  Bool()
    val nf     = in  Bool()
    val eq     = in  Bool()
    val lt     = in  Bool()
    val bcopd  = in  Bits(16 bits)

    // Branch/Jump Control Outputs
    val br     = out Bool()
    val jmp    = out Bool()
    val jbr    = out Bool()

    // Memory Control Outputs
    val memIn    = out(MemoryControl())
    val mmuInstr = out Bits(mmuWidth bits)
    val dirAddr  = out Bits(ramWidth bits)  // Direct RAM Address
    val mulWr    = out Bool()
    val wrDly    = out Bool()

    // ALU Control Outputs
    val selSub  = out Bool()
    val selAmux = out Bool()
    val enaA    = out Bool()
    val selBmux = out Bool()
    val selLog  = out Bits(2 bits)
    val selShf  = out Bits(2 bits)
    val selLmux = out Bits(3 bits)
    val selImux = out Bits(2 bits)
    val selRmux = out Bits(2 bits)
    val selSmux = out Bits(2 bits)
    val selMmux = out Bool()
    val selRda  = out Bits(3 bits)
    val selWra  = out Bits(3 bits)
    val wrEna   = out Bool()
    val enaB    = out Bool()
    val enaVp   = out Bool()
    val enaJpc  = out Bool()
    val enaAr   = out Bool()

    // Stall: when True, hold all registered decode outputs (prevent advancement
    // during stack cache rotation stall).  Combinational outputs are unaffected.
    val stall   = in Bool()
  }

  // ==========================================================================
  // Instruction Register Alias
  // ==========================================================================
  val ir = io.instr

  // ==========================================================================
  // MMU Instruction Output (combinational)
  // ==========================================================================
  io.mmuInstr := ir(config.mmuWidth - 1 downto 0)

  // ==========================================================================
  // Stack Operation Classification (combinational)
  // ==========================================================================
  // Decode instruction class from ir[9:6] to determine stack behavior

  val isPop = Bool()
  val isPush = Bool()

  isPop := False
  isPush := False

  switch(ir(9 downto 6)) {
    is(B"4'b0000") { isPop := True }   // POP class
    is(B"4'b0001") { isPop := True }   // POP class
    is(B"4'b0010") { isPush := True }  // PUSH class
    is(B"4'b0011") { isPush := True }  // PUSH class
    is(B"4'b0100") { /* NOP class */ }
    is(B"4'b0101") { /* null */ }
    is(B"4'b0110") { isPop := True }   // Branch BZ (POP)
    is(B"4'b0111") { isPop := True }   // Branch BNZ (POP)
    default { /* JMP class and others - no stack change */ }
  }

  // ==========================================================================
  // Combinational Decode Logic
  // ==========================================================================
  // These outputs have 0 cycle latency - they respond immediately to ir

  val combinationalDecode = new Area {

    // ========================================================================
    // JBR Decode (combinational)
    // ========================================================================
    io.jbr := False
    when(ir === B(config.jbrOpcode, config.iWidth bits)) {
      io.jbr := True
    }

    // ========================================================================
    // RAM Write Enable
    // ========================================================================
    io.wrEna := False
    when(isPush ||                              // push instructions
         ir(9 downto 5) === B"5'b00001" ||      // stm
         ir(9 downto 3) === B"7'b0000010") {    // st, stn, stmi
      io.wrEna := True
    }

    // ========================================================================
    // Immediate Mux Select (from instruction bits)
    // ========================================================================
    io.selImux := ir(1 downto 0)

    // ========================================================================
    // Direct RAM Address
    // ========================================================================
    // Default: lower 5 bits of instruction, upper bits zeroed
    val dirDefault = B(0, config.ramWidth - 5 bits) ## ir(4 downto 0)
    io.dirAddr := dirDefault

    // For LDI: set bit 5 to indicate constant pool access (addr > 31)
    when(ir(9 downto 5) === B"5'b00110") {    // ldi
      io.dirAddr := B(1, config.ramWidth - 5 bits) ## ir(4 downto 0)
    }

    // ========================================================================
    // Read Address Mux Select
    // ========================================================================
    io.selRda := B"3'b110"  // Default: SP

    when(ir(9 downto 3) === B"7'b0011101") {   // ld, ldn, ldmi
      io.selRda := ir(2 downto 0)
    }
    when(ir(9 downto 5) === B"5'b00101") {     // ldm
      io.selRda := B"3'b111"
    }
    when(ir(9 downto 5) === B"5'b00110") {     // ldi
      io.selRda := B"3'b111"
    }

    // ========================================================================
    // Write Address Mux Select
    // ========================================================================
    io.selWra := B"3'b110"  // Default: SPP

    when(ir(9 downto 3) === B"7'b0000010") {   // st, stn, stmi
      io.selWra := ir(2 downto 0)
    }
    when(ir(9 downto 5) === B"5'b00001") {     // stm
      io.selWra := B"3'b111"
    }

    // ========================================================================
    // Stack Pointer Mux Select
    // ========================================================================
    io.selSmux := B"2'b00"  // Default: SP unchanged

    when(isPop) {
      io.selSmux := B"2'b01"  // --SP
    }
    when(isPush) {
      io.selSmux := B"2'b10"  // ++SP
    }
    when(ir === B"10'b0000011011") {  // stsp instruction (0x01B)
      io.selSmux := B"2'b11"  // SP = A
    }
  }

  // ==========================================================================
  // Registered Decode Logic - Branch/Jump
  // ==========================================================================
  // br and jmp have 1 cycle latency

  val branchDecode = new Area {
    val brReg  = Reg(Bool()) init(False)
    val jmpReg = Reg(Bool()) init(False)

    // Gate registered decode during stall (hold previous values)
    when(!io.stall) {
      // Default values each cycle
      brReg := False
      jmpReg := False

      // Branch decode: BZ (ir[9:6]=0110 and zf=1) or BNZ (ir[9:6]=0111 and zf=0)
      when((ir(9 downto 6) === B"4'b0110" && io.zf) ||
           (ir(9 downto 6) === B"4'b0111" && !io.zf)) {
        brReg := True
      }

      // Jump decode: ir[9]=1 means JMP
      when(ir(9)) {
        jmpReg := True
      }
    }

    io.br := brReg
    io.jmp := jmpReg
  }

  // ==========================================================================
  // Registered Decode Logic - ALU Control
  // ==========================================================================
  // All ALU control signals have 1 cycle latency

  val aluControlDecode = new Area {
    val selSubReg  = Reg(Bool()) init(False)
    val selAmuxReg = Reg(Bool()) init(False)
    selAmuxReg.simPublic()
    val enaAReg    = Reg(Bool()) init(False)
    enaAReg.simPublic()
    val selBmuxReg = Reg(Bool()) init(False)
    selBmuxReg.simPublic()
    val selLogReg  = Reg(Bits(2 bits)) init(B"2'b00")
    val selShfReg  = Reg(Bits(2 bits)) init(B"2'b00")
    val selLmuxReg = Reg(Bits(3 bits)) init(B"3'b000")
    selLmuxReg.simPublic()
    val selRmuxReg = Reg(Bits(2 bits)) init(B"2'b00")
    val selMmuxReg = Reg(Bool()) init(False)
    val enaBReg    = Reg(Bool()) init(False)
    enaBReg.simPublic()
    val enaVpReg   = Reg(Bool()) init(False)
    val enaJpcReg  = Reg(Bool()) init(False)
    val enaArReg   = Reg(Bool()) init(False)

    // Gate all registered decode during stall (hold previous values)
    when(!io.stall) {

    // ========================================================================
    // Logic Operation Select (sel_log)
    // ========================================================================
    selLogReg := B"2'b00"  // Default: pop path
    when(ir(9 downto 2) === B"8'b00000000") {  // pop, and, or, xor
      selLogReg := ir(1 downto 0)
    }

    // ========================================================================
    // Shift Operation Select (sel_shf)
    // ========================================================================
    selShfReg := ir(1 downto 0)

    // ========================================================================
    // Default ALU Control Values
    // ========================================================================
    selSubReg := True    // Default: subtract for lt-flag
    selAmuxReg := True   // Default: lmux
    enaAReg := True      // Default: enable A
    enaVpReg := False
    enaJpcReg := False
    enaArReg := False

    // ========================================================================
    // Instruction-Specific ALU Control
    // ========================================================================
    switch(ir) {
      // Stack/Logic Operations
      is(B"10'b0000000000") { /* pop - use defaults */ }
      is(B"10'b0000000001") { /* and - use defaults */ }
      is(B"10'b0000000010") { /* or - use defaults */ }
      is(B"10'b0000000011") { /* xor - use defaults */ }
      is(B"10'b0000000100") {  // add
        selSubReg := False
        selAmuxReg := False
      }
      is(B"10'b0000000101") {  // sub
        selAmuxReg := False
      }

      // Store Operations
      is(B"10'b0000010000") { /* st0 */ }
      is(B"10'b0000010001") { /* st1 */ }
      is(B"10'b0000010010") { /* st2 */ }
      is(B"10'b0000010011") { /* st3 */ }
      is(B"10'b0000010100") { /* st */ }
      is(B"10'b0000010101") { /* stmi */ }

      // Register Store Operations
      is(B"10'b0000011000") {  // stvp
        enaVpReg := True
      }
      is(B"10'b0000011001") {  // stjpc
        enaJpcReg := True
      }
      is(B"10'b0000011010") {  // star
        enaArReg := True
      }
      is(B"10'b0000011011") { /* stsp */ }

      // Shift Operations
      is(B"10'b0000011100") { /* ushr */ }
      is(B"10'b0000011101") { /* shl */ }
      is(B"10'b0000011110") { /* shr */ }

      // MMU Operations (0x040-0x04F)
      is(B"10'b0001000000") { /* stmul */ }
      is(B"10'b0001000001") { /* stmwa */ }
      is(B"10'b0001000010") { /* stmra */ }
      is(B"10'b0001000011") { /* stmwd */ }
      is(B"10'b0001000100") { /* stald */ }
      is(B"10'b0001000101") { /* stast */ }
      is(B"10'b0001000110") { /* stgf */ }
      is(B"10'b0001000111") { /* stpf */ }
      is(B"10'b0001001111") { /* stpfr/stpsr/stastr */ }
      is(B"10'b0001001000") { /* stcp */ }
      is(B"10'b0001001001") { /* stbcrd */ }
      is(B"10'b0001001010") { /* stidx */ }
      is(B"10'b0001001011") { /* stps */ }
      is(B"10'b0001001100") { /* stmrac */ }
      is(B"10'b0001001101") { /* stmraf */ }
      is(B"10'b0001001110") { /* stmwdf */ }

      // Load Operations (0x0E0-0x0FF)
      is(B"10'b0011100000") { /* ldmrd */ }
      is(B"10'b0011100001") { /* ldmul */ }
      is(B"10'b0011100010") { /* ldbcstart */ }
      is(B"10'b0011101000") { /* ld0 */ }
      is(B"10'b0011101001") { /* ld1 */ }
      is(B"10'b0011101010") { /* ld2 */ }
      is(B"10'b0011101011") { /* ld3 */ }
      is(B"10'b0011101100") { /* ld */ }
      is(B"10'b0011101101") { /* ldmi */ }
      is(B"10'b0011110000") { /* ldsp */ }
      is(B"10'b0011110001") { /* ldvp */ }
      is(B"10'b0011110010") { /* ldjpc */ }
      is(B"10'b0011110100") { /* ld_opd_8u */ }
      is(B"10'b0011110101") { /* ld_opd_8s */ }
      is(B"10'b0011110110") { /* ld_opd_16u */ }
      is(B"10'b0011110111") { /* ld_opd_16s */ }

      // DUP (no A register update)
      is(B"10'b0011111000") {
        enaAReg := False
      }

      // Control Operations
      is(B"10'b0100000000") {  // nop
        enaAReg := False
      }
      is(B"10'b0100000001") {  // wait
        enaAReg := False
      }
      is(B"10'b0100000010") {  // jbr
        enaAReg := False
      }

      // MMU Operations without stack change (0x110-0x11F)
      is(B"10'b0100010000") {  // stgs
        enaAReg := False
      }
      is(B"10'b0100010001") {  // cinval
        enaAReg := False
      }
      is(B"10'b0100010010") {  // atmstart
        enaAReg := False
      }
      is(B"10'b0100010011") {  // atmend
        enaAReg := False
      }

      default { /* use defaults */ }
    }

    // Special case: JMP instructions (ir[9]=1) disable A
    when(ir(9)) {
      enaAReg := False
    }

    // ========================================================================
    // Load Mux Select (sel_lmux) - Priority Encoded
    // ========================================================================
    selLmuxReg := B"3'b000"  // Default: logic unit

    when(ir(9 downto 2) === B"8'b00000111") {    // ushr, shl, shr
      selLmuxReg := B"3'b001"  // Shifter output
    }

    when(ir(9 downto 5) === B"5'b00101") {       // ldm
      selLmuxReg := B"3'b010"  // Memory output
    }
    when(ir(9 downto 5) === B"5'b00110") {       // ldi
      selLmuxReg := B"3'b010"  // Memory output
    }

    when(ir(9 downto 3) === B"7'b0011101") {     // ld, ldn, ldmi
      selLmuxReg := B"3'b010"  // Memory output
    }

    when(ir(9 downto 2) === B"8'b00111101") {    // ld_opd_x
      selLmuxReg := B"3'b011"  // Immediate operand
    }

    when(ir(9 downto 3) === B"7'b0011100") {     // ld from mmu/mul
      selLmuxReg := B"3'b100"  // MMU/Multiplier output
    }

    when(ir(9 downto 2) === B"8'b00111100") {    // ldsp, ldvp, ldjpc
      selLmuxReg := B"3'b101"  // Register output
    }

    // ========================================================================
    // B Mux and M Mux Select
    // ========================================================================
    // Default: pop behavior
    selBmuxReg := True   // mem
    selMmuxReg := False  // a

    when(!isPop) {  // push or no stack change
      selBmuxReg := False  // a
      selMmuxReg := True   // b
    }

    // ========================================================================
    // Enable B Register
    // ========================================================================
    enaBReg := True
    when(!isPush && !isPop) {  // no stack change (nop, wait, jbr)
      enaBReg := False
    }

    // ========================================================================
    // Register Mux Select (for ldsp, ldvp, ldjpc)
    // ========================================================================
    selRmuxReg := ir(1 downto 0)

    } // end when(!io.stall)

    // ========================================================================
    // Output Assignments
    // ========================================================================
    io.selSub := selSubReg
    io.selAmux := selAmuxReg
    io.enaA := enaAReg
    io.selBmux := selBmuxReg
    io.selLog := selLogReg
    io.selShf := selShfReg
    io.selLmux := selLmuxReg
    io.selRmux := selRmuxReg
    io.selMmux := selMmuxReg
    io.enaB := enaBReg
    io.enaVp := enaVpReg
    io.enaJpc := enaJpcReg
    io.enaAr := enaArReg
  }

  // ==========================================================================
  // Registered Decode Logic - MMU/Memory Control
  // ==========================================================================
  // All mem_in signals have 1 cycle latency

  val mmuControlDecode = new Area {
    val memRdReg        = Reg(Bool()) init(False)
    val memWrReg        = Reg(Bool()) init(False)
    val memAddrWrReg    = Reg(Bool()) init(False)
    val memBcRdReg      = Reg(Bool()) init(False)
    val memStidxReg     = Reg(Bool()) init(False)
    val memIaloadReg    = Reg(Bool()) init(False)
    val memIastoreReg   = Reg(Bool()) init(False)
    val memGetfieldReg  = Reg(Bool()) init(False)
    val memPutfieldReg  = Reg(Bool()) init(False)
    val memPutrefReg    = Reg(Bool()) init(False)
    val memGetstaticReg = Reg(Bool()) init(False)
    val memPutstaticReg = Reg(Bool()) init(False)
    val memRdcReg       = Reg(Bool()) init(False)
    val memRdfReg       = Reg(Bool()) init(False)
    val memWrfReg       = Reg(Bool()) init(False)
    val memCopyReg      = Reg(Bool()) init(False)
    val memCinvalReg    = Reg(Bool()) init(False)
    val memAtmstartReg  = Reg(Bool()) init(False)
    val memAtmendReg    = Reg(Bool()) init(False)
    val mulWrReg        = Reg(Bool()) init(False)
    val wrDlyReg        = Reg(Bool()) init(False)

    // Gate all registered MMU decode during stall (hold previous values)
    when(!io.stall) {

    // Default: all inactive
    memRdReg := False
    memWrReg := False
    memAddrWrReg := False
    memBcRdReg := False
    memStidxReg := False
    memIaloadReg := False
    memIastoreReg := False
    memGetfieldReg := False
    memPutfieldReg := False
    memPutrefReg := False
    memGetstaticReg := False
    memPutstaticReg := False
    memRdcReg := False
    memRdfReg := False
    memWrfReg := False
    memCopyReg := False
    memCinvalReg := False
    memAtmstartReg := False
    memAtmendReg := False
    mulWrReg := False
    wrDlyReg := False

    // ========================================================================
    // MMU Instructions with Stack Pop (prefix 0x04x)
    // ========================================================================
    when(ir(9 downto 4) === B"6'b000100") {
      wrDlyReg := True
      switch(ir(config.mmuWidth - 1 downto 0)) {
        is(MmuInstructions.STMUL) { mulWrReg := True }
        is(MmuInstructions.STMWA) { memAddrWrReg := True }
        is(MmuInstructions.STMRA) { memRdReg := True }
        is(MmuInstructions.STMWD) { memWrReg := True }
        is(MmuInstructions.STALD) { memIaloadReg := True }
        is(MmuInstructions.STAST) { memIastoreReg := True }
        is(MmuInstructions.STGF)  { memGetfieldReg := True }
        is(MmuInstructions.STPF)  { memPutfieldReg := True }
        is(MmuInstructions.STPFR) {
          memPutfieldReg := True
          memPutrefReg := True
        }
        is(MmuInstructions.STCP)   { memCopyReg := True }
        is(MmuInstructions.STBCR)  { memBcRdReg := True }
        is(MmuInstructions.STIDX)  { memStidxReg := True }
        is(MmuInstructions.STPS)   { memPutstaticReg := True }
        is(MmuInstructions.STMRAC) { memRdcReg := True }
        is(MmuInstructions.STMRAF) { memRdfReg := True }
        is(MmuInstructions.STMWDF) { memWrfReg := True }
        default { /* unknown MMU instruction */ }
      }
    }

    // ========================================================================
    // MMU Instructions without Stack Change (prefix 0x11x)
    // ========================================================================
    when(ir(9 downto 4) === B"6'b010001") {
      wrDlyReg := True
      switch(ir(config.mmuWidth - 1 downto 0)) {
        is(MmuInstructions.STGS)     { memGetstaticReg := True }
        is(MmuInstructions.CINVAL)   { memCinvalReg := True }
        is(MmuInstructions.ATMSTART) { memAtmstartReg := True }
        is(MmuInstructions.ATMEND)   { memAtmendReg := True }
        default { /* unknown MMU instruction */ }
      }
    }

    } // end when(!io.stall) for MMU decode

    // ========================================================================
    // Output Assignments
    // ========================================================================
    io.memIn.rd := memRdReg
    io.memIn.wr := memWrReg
    io.memIn.addrWr := memAddrWrReg
    io.memIn.bcRd := memBcRdReg
    io.memIn.stidx := memStidxReg
    io.memIn.iaload := memIaloadReg
    io.memIn.iastore := memIastoreReg
    io.memIn.getfield := memGetfieldReg
    io.memIn.putfield := memPutfieldReg
    io.memIn.putref := memPutrefReg
    io.memIn.getstatic := memGetstaticReg
    io.memIn.putstatic := memPutstaticReg
    io.memIn.rdc := memRdcReg
    io.memIn.rdf := memRdfReg
    io.memIn.wrf := memWrfReg
    io.memIn.copy := memCopyReg
    io.memIn.cinval := memCinvalReg
    io.memIn.atmstart := memAtmstartReg
    io.memIn.atmend := memAtmendReg
    io.mulWr := mulWrReg
    io.wrDly := wrDlyReg
  }

  // Bytecode operand passthrough (combinational)
  io.memIn.bcopd := io.bcopd

  // ==========================================================================
  // Pipeline API Integration (for downstream stages)
  // ==========================================================================

  /**
   * Pipeline output node for downstream connection.
   *
   * This node exposes the decode stage outputs as Pipeline API payloads
   * that can be connected to downstream stages (execute) using StageLink.
   */
  val outputNode = Node()

  // Export payloads to output node
  outputNode(DecodePayloads.BR) := io.br
  outputNode(DecodePayloads.JMP) := io.jmp
  outputNode(DecodePayloads.JBR) := io.jbr
  outputNode(DecodePayloads.SEL_SUB) := io.selSub
  outputNode(DecodePayloads.SEL_AMUX) := io.selAmux
  outputNode(DecodePayloads.ENA_A) := io.enaA
  outputNode(DecodePayloads.SEL_BMUX) := io.selBmux
  outputNode(DecodePayloads.SEL_LOG) := io.selLog
  outputNode(DecodePayloads.SEL_SHF) := io.selShf
  outputNode(DecodePayloads.SEL_LMUX) := io.selLmux
  outputNode(DecodePayloads.SEL_IMUX) := io.selImux
  outputNode(DecodePayloads.SEL_RMUX) := io.selRmux
  outputNode(DecodePayloads.SEL_SMUX) := io.selSmux
  outputNode(DecodePayloads.SEL_MMUX) := io.selMmux
  outputNode(DecodePayloads.SEL_RDA) := io.selRda
  outputNode(DecodePayloads.SEL_WRA) := io.selWra
  outputNode(DecodePayloads.WR_ENA) := io.wrEna
  outputNode(DecodePayloads.ENA_B) := io.enaB
  outputNode(DecodePayloads.ENA_VP) := io.enaVp
  outputNode(DecodePayloads.ENA_JPC) := io.enaJpc
  outputNode(DecodePayloads.ENA_AR) := io.enaAr
  outputNode(DecodePayloads.MUL_WR) := io.mulWr
  outputNode(DecodePayloads.WR_DLY) := io.wrDly
  outputNode(DecodePayloads.MMU_INSTR) := io.mmuInstr

  // Valid signal - decode is always producing valid output after reset
  outputNode.valid := True

  // Ready signal - always accept for standalone operation
  outputNode.ready := True
}

/**
 * Generate Verilog for synthesis
 */
object DecodeStageVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  )

  config.generateVerilog(DecodeStage())
    .printPruned()

  println("DecodeStage Verilog generated at: generated/verilog/DecodeStage.v")
}
