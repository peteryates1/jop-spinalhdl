package jop.pipeline

import spinal.core._

/**
 * Bytecode Fetch Stage Configuration
 *
 * @param jpcWidth Address bits of Java bytecode PC (default: 11 bits = 2KB)
 * @param pcWidth  Address bits of microcode ROM (default: 11 bits)
 */
case class BytecodeFetchConfig(
  jpcWidth:  Int              = 11,
  pcWidth:   Int              = 11,
  jumpTable: JumpTableInitData = JumpTableInitData.simulation
) {
  require(jpcWidth > 0, "JPC width must be positive")
  require(pcWidth > 0, "PC width must be positive")

  /** JBC RAM depth (bytes) */
  def jbcDepth: Int = 1 << jpcWidth

  /** Operand width */
  def opdWidth: Int = 16
}

/**
 * Java Bytecode Fetch Stage
 *
 * Implemented features (Phase A + Phase B + Phase E):
 * - JPC increment on jfetch
 * - JBC RAM read (synchronous, 2KB)
 * - JumpTable integration for bytecode → microcode translation
 * - JPC write for method calls (jpc_wr)
 * - Operand accumulation (jopdfetch) - 16-bit shift register
 * - Branch logic - 15 branch types with condition evaluation
 * - Interrupt/exception handling with pending latches (Phase E)
 *   - int_pend: Set on irq (latched even when ena=0), cleared on ack during jfetch
 *   - exc_pend: Set on exc, cleared on ack during jfetch
 *   - Priority: Exception > Interrupt > Normal bytecode
 *   - Interrupt only acknowledged when ena=1 (allows deferred interrupt handling)
 *
 * @param config Configuration
 * @param jbcInit Optional JBC RAM initialization
 */
case class BytecodeFetchStage(
  config: BytecodeFetchConfig = BytecodeFetchConfig(),
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Control inputs (Phase A - implemented)
    val jpc_wr    = in Bool()                         // Write jpc (method call)
    val din       = in Bits(32 bits)                  // Stack TOS
    val jfetch    = in Bool()                         // Fetch bytecode

    // Operand and branch control (Phase B - implemented)
    val jopdfetch = in Bool()                         // Fetch operand (triggers shift)
    val jbr       = in Bool()                         // Branch evaluation enable

    // Branch condition flags (Phase B - implemented, used for 15 branch types)
    val zf = in Bool()                                // Zero flag (for ifeq, ifne, ifgt, ifle)
    val nf = in Bool()                                // Negative flag (for iflt, ifge, ifgt, ifle)
    val eq = in Bool()                                // Equal flag (for if_icmpeq, if_icmpne, if_icmpgt, if_icmple)
    val lt = in Bool()                                // Less-than flag (for if_icmplt, if_icmpge, if_icmpgt, if_icmple)

    // JBC write interface (for loading bytecodes into cache)
    val jbcWrAddr = in UInt((config.jpcWidth - 2) bits)  // Word address (byte_addr / 4)
    val jbcWrData = in Bits(32 bits)                     // 32-bit word (4 bytes)
    val jbcWrEn   = in Bool()                            // Write enable

    // Pipeline stall (freeze jopd/jpc during stack cache rotation)
    val stall = in Bool()

    // Interrupt/Exception interface (Phase E)
    val irq = in Bool()                               // Interrupt request
    val exc = in Bool()                               // Exception request
    val ena = in Bool()                               // Interrupt enable
    val ack_irq = out Bool()                          // Interrupt acknowledged
    val ack_exc = out Bool()                          // Exception acknowledged

    // Outputs
    val jpaddr   = out UInt(config.pcWidth bits)      // Microcode address from jump table
    val opd      = out Bits(config.opdWidth bits)     // Operand (16-bit, accumulated via jopdfetch)
    val jpc_out  = out UInt(config.jpcWidth + 1 bits) // Current jpc
  }

  // ==========================================================================
  // JBC RAM (Java Bytecode Storage)
  // ==========================================================================

  // JBC RAM is organized as 32-bit words for efficient write access
  // Read is byte-addressed (8-bit output)
  val jbcWordDepth = config.jbcDepth / 4  // Number of 32-bit words
  val jbcRamWord = Mem(Bits(32 bits), jbcWordDepth)

  // Initialize RAM (optional)
  jbcInit match {
    case Some(data) =>
      // Pack bytes into words
      val wordData = data.grouped(4).map { bytes =>
        val padded = bytes.padTo(4, BigInt(0))
        padded(0) | (padded(1) << 8) | (padded(2) << 16) | (padded(3) << 24)
      }.toSeq
      jbcRamWord.init(wordData.map(v => B(v, 32 bits)))
    case None =>
      jbcRamWord.init(Seq.fill(jbcWordDepth)(B(0, 32 bits)))
  }

  // Write port (32-bit words)
  jbcRamWord.write(
    address = io.jbcWrAddr,
    data = io.jbcWrData,
    enable = io.jbcWrEn
  )

  // ==========================================================================
  // Registers
  // ==========================================================================

  val jpc = Reg(UInt(config.jpcWidth + 1 bits)) init(0)       // Java PC
  val jopd = Reg(Bits(config.opdWidth bits)) init(0)          // Operand accumulator
  val jpc_br = Reg(UInt(config.jpcWidth + 1 bits)) init(0)    // Branch start address
  val jinstr = Reg(Bits(8 bits)) init(0)                      // Instruction bytecode
  val jmp_addr = Reg(UInt(config.jpcWidth + 1 bits)) init(0)  // Branch target address

  // ==========================================================================
  // JBC Address and Read
  // ==========================================================================

  // JBC address calculation with branch support
  // Note: jpc is (jpcWidth + 1) bits (12 bits) to detect overflow,
  //       jbcAddr is jpcWidth bits (11 bits) for RAM addressing (2KB = 2048 bytes)
  //       The upper bit is intentionally truncated via slicing.
  //
  // Priority: jmp > (jfetch | jopdfetch) > hold
  // - If branch taken (jmp), use branch target address (jmp_addr)
  // - If fetching, read next address (jpc + 1)
  // - Otherwise, read current address (jpc)
  val jbcAddr = UInt(config.jpcWidth bits)

  // Forward declare jmp (will be defined in branch logic section)
  val jmp = Bool()

  when(jmp) {
    jbcAddr := jmp_addr(config.jpcWidth - 1 downto 0)  // Branch target
  }.elsewhen(io.jfetch || io.jopdfetch) {
    jbcAddr := (jpc + 1)(config.jpcWidth - 1 downto 0)  // Next address
  }.otherwise {
    jbcAddr := jpc(config.jpcWidth - 1 downto 0)  // Current address
  }

  // Synchronous RAM read (word-addressed with byte selection)
  // Write-first bypass: SpinalHDL's readSync with dontCare returns undefined
  // data when the read and write ports access the same word address on the
  // same clock edge. During BC fill, the last write can coincide with the
  // bytecode fetch read at the dispatch moment (timing depends on memory
  // latency). We manually bypass the RAM output with the write data when
  // a same-cycle read-write collision is detected.
  val jbcWordAddr = jbcAddr(config.jpcWidth - 1 downto 2)  // Word address
  val jbcByteSelect = RegNext(jbcAddr(1 downto 0))         // Register byte selector (aligned with RAM read)
  val jbcWordDataRaw = jbcRamWord.readSync(jbcWordAddr, enable = True)

  // Detect read-write collision: register both addresses and write enable,
  // then compare one cycle later (aligned with readSync output)
  val bypassWrEn   = RegNext(io.jbcWrEn) init(False)
  val bypassWrAddr = RegNext(io.jbcWrAddr)
  val bypassWrData = RegNext(io.jbcWrData)
  val bypassRdAddr = RegNext(jbcWordAddr)
  val doBypass = bypassWrEn && (bypassWrAddr === bypassRdAddr)
  val jbcWordData = Mux(doBypass, bypassWrData, jbcWordDataRaw)

  // Byte selection mux (little-endian: byte 0 at bits 7:0, byte 3 at bits 31:24)
  val jbcData = jbcByteSelect.mux(
    0 -> jbcWordData(7 downto 0),
    1 -> jbcWordData(15 downto 8),
    2 -> jbcWordData(23 downto 16),
    3 -> jbcWordData(31 downto 24)
  )

  // ==========================================================================
  // JPC Update Logic
  // ==========================================================================

  // Priority: stall > jpc_wr > jmp > jfetch/jopdfetch > hold
  // During pipeline stall (stack cache rotation), freeze JPC to prevent
  // advancing past the current operand bytes.
  when(!io.stall) {
    when(io.jpc_wr) {
      // Method call: load from stack
      jpc := io.din(config.jpcWidth downto 0).asUInt
    }.elsewhen(jmp) {
      // Branch taken: load branch target
      jpc := jmp_addr
    }.elsewhen(io.jfetch || io.jopdfetch) {
      // Increment
      jpc := jpc + 1
    }
  }

  io.jpc_out := jpc

  // ==========================================================================
  // Operand Accumulation Logic
  // ==========================================================================

  // During pipeline stall (stack cache rotation), freeze jopd to prevent
  // the unconditional low-byte update from overwriting the accumulated
  // operand with the next bytecode (jbcData reads from jpc, which points
  // past the operand bytes after accumulation completes).
  when(!io.stall) {
    // Low byte always gets updated with current JBC RAM output
    jopd(7 downto 0) := jbcData

    // When jopdfetch is asserted, shift low byte to high byte
    // This allows accumulating multi-byte operands:
    //   Cycle 1: jfetch=1, bytecode 0x12 → jopd = 0x00_12
    //   Cycle 2: jopdfetch=1, operand 0x34 → jopd = 0x12_34 (shift + load)
    when(io.jopdfetch) {
      jopd(15 downto 8) := jopd(7 downto 0)
    }
  }

  io.opd := jopd

  // ==========================================================================
  // Branch Logic
  // ==========================================================================

  // Capture instruction bytecode and branch start address on jfetch
  // (also frozen during stall for consistency)
  when(!io.stall && io.jfetch) {
    jinstr := jbcData
    jpc_br := jpc
  }

  // Extract branch type from instruction, with remapping for bytecodes
  // whose lower 4 bits don't match the standard branch type encoding.
  // Matches VHDL bcfetch.vhd: if_acmpeq/if_acmpne (0xa5/0xa6) and
  // ifnull/ifnonnull (0xc6/0xc7) need explicit remapping.
  val tp = Bits(4 bits)
  switch(jinstr) {
    is(0xa5) { tp := 15 }  // if_acmpeq → eq type (same as if_icmpeq)
    is(0xa6) { tp := 0 }   // if_acmpne → ne type (same as if_icmpne)
    is(0xc6) { tp := 9 }   // ifnull → ifeq type
    is(0xc7) { tp := 10 }  // ifnonnull → ifne type
    default  { tp := jinstr(3 downto 0) }
  }

  // Branch target address calculation (registered)
  // Target = jpc_br + sign_extend(jopd_high & jbc_q)
  // Using upper bits of jopd and current jbc_q to form 16-bit signed offset
  val branchOffset = (jopd(config.jpcWidth - 8 downto 0) ## jbcData).asSInt.resize(config.jpcWidth + 1 bits)
  jmp_addr := (jpc_br.asSInt + branchOffset).asUInt

  // Branch condition evaluation (combinational)
  // Note: jmp was forward-declared earlier for use in jbcAddr calculation
  jmp := False
  when(io.jbr) {
    switch(tp) {
      is(9) {  // ifeq, ifnull
        when(io.zf) { jmp := True }
      }
      is(10) {  // ifne, ifnonnull
        when(!io.zf) { jmp := True }
      }
      is(11) {  // iflt
        when(io.nf) { jmp := True }
      }
      is(12) {  // ifge
        when(!io.nf) { jmp := True }
      }
      is(13) {  // ifgt
        when(!io.zf && !io.nf) { jmp := True }
      }
      is(14) {  // ifle
        when(io.zf || io.nf) { jmp := True }
      }
      is(15) {  // if_icmpeq, if_acmpeq
        when(io.eq) { jmp := True }
      }
      is(0) {  // if_icmpne, if_acmpne
        when(!io.eq) { jmp := True }
      }
      is(1) {  // if_icmplt
        when(io.lt) { jmp := True }
      }
      is(2) {  // if_icmpge
        when(!io.lt) { jmp := True }
      }
      is(3) {  // if_icmpgt
        when(!io.eq && !io.lt) { jmp := True }
      }
      is(4) {  // if_icmple
        when(io.eq || io.lt) { jmp := True }
      }
      is(7) {  // goto
        jmp := True
      }
    }
  }

  // ==========================================================================
  // Interrupt/Exception Pending Logic (Phase E)
  // ==========================================================================

  // Pending latches (matching VHDL bcfetch.vhd behavior)
  // int_pend: Set when irq (latched regardless of ena), cleared on acknowledge during jfetch
  // exc_pend: Set when exc, cleared on acknowledge during jfetch
  // NOTE: Unlike a naive implementation, IRQ is latched even when ena=0.
  // The ena check happens at acknowledge time, so an IRQ arriving while
  // interrupts are disabled will fire once interrupts are re-enabled.
  val intPend = Reg(Bool()) init(False)
  val excPend = Reg(Bool()) init(False)

  // Acknowledge signals (active during jfetch when pending is cleared)
  val doAckIrq = Bool()
  val doAckExc = Bool()

  // Use io.exc combinationally alongside registered excPend so that if the
  // exc pulse and jfetch arrive in the same cycle, the exception is caught
  // immediately rather than missing this jfetch and firing at the next one
  // (which may be in a different bytecode context, outside the try block).
  val excPendImmediate = excPend || io.exc

  // Exception has priority; interrupt requires ena to be acknowledged
  doAckExc := excPendImmediate && io.jfetch
  doAckIrq := intPend && io.ena && !excPendImmediate && io.jfetch

  // Update pending latches
  when(doAckExc) {
    excPend := False
  }.elsewhen(io.exc) {
    excPend := True
  }

  when(doAckIrq) {
    intPend := False
  }.elsewhen(io.irq) {
    intPend := True
  }

  // Output acknowledge signals
  io.ack_irq := doAckIrq
  io.ack_exc := doAckExc

  // ==========================================================================
  // Jump Table Integration
  // ==========================================================================

  val jumpTable = JumpTable(JumpTableConfig(pcWidth = config.pcWidth, initData = config.jumpTable))
  jumpTable.io.bytecode := jbcData
  // Pass int_req (int_pend AND ena) to JumpTable, matching VHDL behavior
  // This means the interrupt handler address is only output when interrupts are enabled
  jumpTable.io.intPend := intPend && io.ena
  jumpTable.io.excPend := excPendImmediate
  io.jpaddr := jumpTable.io.jpaddr
}

/**
 * BytecodeFetchStage Companion Object
 */
object BytecodeFetchStage {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "core/spinalhdl/generated"
    ).generate(BytecodeFetchStage())
  }
}
