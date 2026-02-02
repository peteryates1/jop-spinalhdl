package jop.pipeline

import spinal.core._
import jop.core.Shift

/**
 * Stack Stage Configuration
 *
 * Configures the stack/execute stage parameters.
 *
 * @param width     Data word width (default: 32)
 * @param jpcWidth  Java bytecode PC width (default: 10)
 * @param ramWidth  Stack RAM address width (default: 8, giving 256 entries)
 */
case class StackConfig(
  width: Int = 32,
  jpcWidth: Int = 11,
  ramWidth: Int = 8
) {
  require(width == 32, "Data width must be 32 bits")
  require(jpcWidth >= 10 && jpcWidth <= 16, "JPC width must be between 10 and 16 bits")
  require(ramWidth > 0 && ramWidth <= 16, "RAM width must be between 1 and 16")

  /** Stack overflow threshold: max - 16 */
  def stackOverflowThreshold: Int = (1 << ramWidth) - 1 - 16

  /** Initial stack pointer value */
  def initialSP: Int = 128
}

/**
 * ALU Flags Bundle
 *
 * Contains the flag outputs from the ALU.
 */
case class AluFlags() extends Bundle {
  val zf = Bool()   // Zero flag (A == 0)
  val nf = Bool()   // Negative flag (A[31])
  val eq = Bool()   // Equal flag (A == B)
  val lt = Bool()   // Less-than flag (from 33-bit subtract)
}

/**
 * Stack/Execute Stage
 *
 * Implements the execute stage of the JOP microcode pipeline using SpinalHDL.
 *
 * Architecture:
 * - 32-bit ALU with add/subtract, logic operations, and comparison
 * - Stack RAM for local variables and operand storage
 * - Integration with barrel shifter for shift operations
 * - Multiple register banks (VP0-VP3, AR, SP)
 * - Complex muxing for data paths based on decode stage control signals
 *
 * Timing Characteristics:
 * - Combinational outputs (0 cycle): zf, nf, eq, lt, aout, bout
 * - Registered outputs (1 cycle): A, B, SP, VP0-3, AR, sp_ov, opddly, immval
 *
 * Translated from: original/vhdl/core/stack.vhd
 *
 * @param config Stack stage configuration
 */
case class StackStage(
  config: StackConfig = StackConfig(),
  ramInit: Option[Seq[BigInt]] = None  // Optional RAM initialization data
) extends Component {

  val io = new Bundle {
    // Data Inputs
    val din    = in Bits(config.width bits)                // External data input (from memory/IO)
    val dirAddr = in UInt(config.ramWidth bits)           // Direct RAM address from decode
    val opd = in Bits(16 bits)                            // Java bytecode operand
    val jpc = in UInt(config.jpcWidth + 1 bits)           // JPC read value (11 bits)

    // ALU Control Inputs (from decode stage)
    val selSub  = in Bool()                               // 0=add, 1=subtract
    val selAmux = in Bool()                               // 0=sum, 1=lmux
    val enaA    = in Bool()                               // Enable A register
    val selBmux = in Bool()                               // 0=A, 1=RAM
    val selLog  = in Bits(2 bits)                         // Logic op: 00=pass, 01=AND, 10=OR, 11=XOR
    val selShf  = in Bits(2 bits)                         // Shift type: 00=USHR, 01=SHL, 10=SHR
    val selLmux = in Bits(3 bits)                         // Load mux select
    val selImux = in Bits(2 bits)                         // Immediate mux: 00=8u, 01=8s, 10=16u, 11=16s
    val selRmux = in Bits(2 bits)                         // Register mux: 00=SP, 01=VP, 10+=JPC
    val selSmux = in Bits(2 bits)                         // SP update: 00=SP, 01=SP-1, 10=SP+1, 11=A

    val selMmux = in Bool()                               // Memory data: 0=A, 1=B
    val selRda  = in Bits(3 bits)                         // Read address mux
    val selWra  = in Bits(3 bits)                         // Write address mux

    val wrEna   = in Bool()                               // RAM write enable
    val enaB    = in Bool()                               // Enable B register
    val enaVp   = in Bool()                               // Enable VP registers
    val enaAr   = in Bool()                               // Enable AR register

    // Debug: direct RAM read port for verification
    val debugRamAddr = in UInt(config.ramWidth bits)
    val debugRamData = out Bits(config.width bits)

    // Debug: direct RAM write port for simulation initialization
    val debugRamWrAddr = in UInt(config.ramWidth bits)
    val debugRamWrData = in Bits(config.width bits)
    val debugRamWrEn = in Bool()

    // Debug: stack pointer and write address for tracking
    val debugSp = out UInt(config.ramWidth bits)
    val debugWrAddr = out UInt(config.ramWidth bits)
    val debugWrEn = out Bool()
    val debugRdAddrReg = out UInt(config.ramWidth bits)
    val debugRamDout = out Bits(config.width bits)

    // Outputs
    val spOv    = out Bool()                              // Stack overflow flag
    val zf      = out Bool()                              // Zero flag
    val nf      = out Bool()                              // Negative flag
    val eq      = out Bool()                              // Equal flag
    val lt      = out Bool()                              // Less-than flag
    val aout    = out Bits(config.width bits)             // A register (TOS)
    val bout    = out Bits(config.width bits)             // B register (NOS)
  }

  // ==========================================================================
  // Internal Registers
  // ==========================================================================

  // A and B registers (TOS and NOS)
  val a = Reg(Bits(config.width bits)) init(0)
  val b = Reg(Bits(config.width bits)) init(0)

  // Stack pointers
  val sp  = Reg(UInt(config.ramWidth bits)) init(config.initialSP)
  val spp = Reg(UInt(config.ramWidth bits)) init(config.initialSP + 1)
  val spm = Reg(UInt(config.ramWidth bits)) init(config.initialSP - 1)

  // Variable pointers (VP0-VP3)
  val vp0 = Reg(UInt(config.ramWidth bits)) init(0)
  val vp1 = Reg(UInt(config.ramWidth bits)) init(0)
  val vp2 = Reg(UInt(config.ramWidth bits)) init(0)
  val vp3 = Reg(UInt(config.ramWidth bits)) init(0)

  // Address register
  val ar = Reg(UInt(config.ramWidth bits)) init(0)

  // VP + offset calculation (registered)
  val vpadd = Reg(UInt(config.ramWidth bits)) init(0)

  // Immediate value pipeline
  val opddly = Reg(Bits(16 bits)) init(0)
  val immval = Reg(Bits(config.width bits)) init(0)

  // Stack overflow flag
  val spOvReg = Reg(Bool()) init(False)

  // ==========================================================================
  // Barrel Shifter Instance
  // ==========================================================================

  val shifter = Shift(config.width)
  shifter.io.din := b.asUInt
  shifter.io.off := a(4 downto 0).asUInt
  shifter.io.shtyp := io.selShf
  val sout = shifter.io.dout.asBits

  // ==========================================================================
  // Stack RAM
  // ==========================================================================
  // Matches VHDL aram.vhd timing:
  // - Write address and enable delayed by 1 cycle (wrEnaDly, wrAddrDly)
  // - Write data (mmux) is combinational from current sel_mmux and A/B
  // - Read address registered (ramRdaddrReg)
  // - Read data unregistered (readAsync after registered address)

  val stackRam = Mem(Bits(config.width bits), 1 << config.ramWidth)

  // Initialize RAM if initialization data provided
  // $readmemb loads: file line N+1 -> array[N] (1-indexed file, 0-indexed array)
  // SpinalHDL Mem.init: init[N] -> file line N+1
  // Combined: init[N] -> array[N] - no adjustment needed!
  ramInit.foreach { initData =>
    val ramSize = 1 << config.ramWidth
    val paddedInit = initData.padTo(ramSize, BigInt(0))
    println(s"Stack RAM init (no shift): want RAM[32]=${initData(32)}, RAM[38]=${initData(38)}, RAM[45]=${initData(45)}")
    // Convert negative values to unsigned 32-bit representation
    stackRam.init(paddedInit.map { v =>
      val unsigned = if (v < 0) v + (BigInt(1) << config.width) else v
      B(unsigned.toLong, config.width bits)
    })
  }

  // Read/write address signals (combinational)
  val rdaddr = UInt(config.ramWidth bits)
  val wraddr = UInt(config.ramWidth bits)

  // Memory data mux
  val mmux = Bits(config.width bits)
  when(io.selMmux === False) {
    mmux := a
  } otherwise {
    mmux := b
  }

  // VHDL aram.vhd delays write address and enable by 1 cycle:
  //   process(clock) begin
  //     if rising_edge(clock) then
  //       wraddr_dly <= wraddress;
  //       wren_dly <= wren;
  //     end if;
  //   end process;
  // This is necessary because wr_ena and wraddr are COMBINATIONAL from the decode,
  // but sel_mmux (which controls the write data mux) is REGISTERED. Without this
  // delay, the write data would use the PREVIOUS instruction's sel_mmux instead of
  // the current instruction's. The 1-cycle delay aligns the write with the updated
  // sel_mmux and A/B register values.
  val wrEnaDly = RegNext(io.wrEna, init = False)
  val wrAddrDly = RegNext(wraddr, init = U(0, config.ramWidth bits))

  // Debug write port takes priority and bypasses the pipeline delay
  val effectiveWrAddr = Mux(io.debugRamWrEn, io.debugRamWrAddr, wrAddrDly)
  val effectiveWrData = Mux(io.debugRamWrEn, io.debugRamWrData, mmux)
  val effectiveWrEn = io.debugRamWrEn | wrEnaDly

  stackRam.write(
    address = effectiveWrAddr,
    data    = effectiveWrData,
    enable  = effectiveWrEn
  )

  // Debug: track the delayed write values (same timing as actual RAM write)
  val ramWraddrReg = wrAddrDly
  val ramWrenReg   = wrEnaDly

  // VHDL's LPM_RAM_DP with LPM_RDADDRESS_CONTROL=REGISTERED has:
  //   - Address registered internally at clock edge
  //   - Data output combinationally from registered address (same cycle, after edge)
  //
  // In VHDL decode:
  //   - sel_lmux IS registered (in rising_edge(clk) process)
  //   - sel_rda, dir are COMBINATIONAL from ir
  //   - But LPM RAM internally registers rdaddr
  //
  // Timing at cycle N edge:
  //   - selLmuxReg samples new value M from decode of instruction I (cycle N-1)
  //   - LPM RAM registers rdaddr = address A for instruction I
  //
  // During cycle N:
  //   - lmux = M (selLmuxReg) - from instruction I
  //   - ramDout = RAM[A] - from instruction I's address
  //   - These are CONSISTENT because both are registered at the same edge
  //
  // To match this in SpinalHDL:
  val ramRdaddrReg = Reg(UInt(config.ramWidth bits)) init(0)
  ramRdaddrReg := rdaddr
  val ramDout = stackRam.readAsync(ramRdaddrReg)

  // Debug: direct async read for RAM verification
  io.debugRamData := stackRam.readAsync(io.debugRamAddr)

  // ==========================================================================
  // 33-bit ALU for Correct Overflow/Comparison
  // ==========================================================================
  // Uses 33-bit signed arithmetic to get correct less-than comparison

  val sum = SInt(33 bits)
  val aExt = S(B"1'b0" ## a)  // Sign-extend a to 33 bits: sign bit of a concatenated
  val bExt = S(B"1'b0" ## b)  // Sign-extend b to 33 bits: sign bit of b concatenated

  // Proper sign extension: replicate MSB
  val aSigned = (a(config.width - 1) ## a).asSInt.resize(33 bits)
  val bSigned = (b(config.width - 1) ## b).asSInt.resize(33 bits)

  when(io.selSub) {
    sum := bSigned - aSigned  // Subtract: B - A
  } otherwise {
    sum := bSigned + aSigned  // Add: B + A
  }

  // Less-than flag from MSB of 33-bit result
  io.lt := sum(32)

  // ==========================================================================
  // Logic Unit
  // ==========================================================================

  val log = Bits(config.width bits)

  switch(io.selLog) {
    is(B"2'b00") {
      log := b                 // Pass-through (for POP)
    }
    is(B"2'b01") {
      log := a & b             // Bitwise AND
    }
    is(B"2'b10") {
      log := a | b             // Bitwise OR
    }
    is(B"2'b11") {
      log := a ^ b             // Bitwise XOR
    }
  }

  // ==========================================================================
  // Register Mux (rmux)
  // ==========================================================================
  // Selects between SP, VP0, and JPC for register read operations

  val rmux = UInt(config.jpcWidth + 1 bits)

  switch(io.selRmux) {
    is(B"2'b00") {
      rmux := sp.resize(config.jpcWidth + 1)
    }
    is(B"2'b01") {
      rmux := vp0.resize(config.jpcWidth + 1)
    }
    default {
      rmux := io.jpc
    }
  }

  // ==========================================================================
  // Immediate Mux (imux)
  // ==========================================================================
  // Converts 16-bit bytecode operand to 32-bit value with extension

  val imux = Bits(config.width bits)

  switch(io.selImux) {
    is(B"2'b00") {
      // 8-bit unsigned extension
      imux := B(0, 24 bits) ## opddly(7 downto 0)
    }
    is(B"2'b01") {
      // 8-bit signed extension
      imux := S(opddly(7 downto 0)).resize(config.width).asBits
    }
    is(B"2'b10") {
      // 16-bit unsigned extension
      imux := B(0, 16 bits) ## opddly
    }
    is(B"2'b11") {
      // 16-bit signed extension
      imux := S(opddly).resize(config.width).asBits
    }
  }

  // ==========================================================================
  // Load Mux (lmux)
  // ==========================================================================
  // Selects the source for A register updates

  val lmux = Bits(config.width bits)

  switch(io.selLmux) {
    is(B"3'b000") {
      lmux := log                                                  // Logic unit output
    }
    is(B"3'b001") {
      lmux := sout                                                 // Shift unit output
    }
    is(B"3'b010") {
      lmux := ramDout                                              // Stack RAM output
    }
    is(B"3'b011") {
      lmux := immval                                               // Immediate value
    }
    is(B"3'b100") {
      lmux := io.din                                               // External data input
    }
    default {
      // Register output (SP, VP, JPC) - zero-extend to 32 bits
      // Note: VHDL uses to_integer(unsigned(rmux)) which is unsigned interpretation
      lmux := rmux.asBits.resized
    }
  }

  // ==========================================================================
  // A Input Mux (amux)
  // ==========================================================================

  val amux = Bits(config.width bits)

  when(io.selAmux === False) {
    amux := sum(31 downto 0).asBits  // ALU sum output
  } otherwise {
    amux := lmux                      // Load mux output
  }

  // ==========================================================================
  // Stack Pointer Mux (smux)
  // ==========================================================================

  val smux = UInt(config.ramWidth bits)

  switch(io.selSmux) {
    is(B"2'b00") {
      smux := sp                                          // No change
    }
    is(B"2'b01") {
      smux := spm                                         // Decrement (pop)
    }
    is(B"2'b10") {
      smux := spp                                         // Increment (push)
    }
    is(B"2'b11") {
      smux := a(config.ramWidth - 1 downto 0).asUInt      // Load from A
    }
  }

  // ==========================================================================
  // Read Address Mux (sel_rda)
  // ==========================================================================

  switch(io.selRda) {
    is(B"3'b000") { rdaddr := vp0 }
    is(B"3'b001") { rdaddr := vp1 }
    is(B"3'b010") { rdaddr := vp2 }
    is(B"3'b011") { rdaddr := vp3 }
    is(B"3'b100") { rdaddr := vpadd }
    is(B"3'b101") { rdaddr := ar }
    is(B"3'b110") { rdaddr := sp }
    default       { rdaddr := io.dirAddr }
  }

  // ==========================================================================
  // Write Address Mux (sel_wra)
  // Note: sel_wra=110 uses SPP (not SP) for push operations
  // ==========================================================================

  switch(io.selWra) {
    is(B"3'b000") { wraddr := vp0 }
    is(B"3'b001") { wraddr := vp1 }
    is(B"3'b010") { wraddr := vp2 }
    is(B"3'b011") { wraddr := vp3 }
    is(B"3'b100") { wraddr := vpadd }
    is(B"3'b101") { wraddr := ar }
    is(B"3'b110") { wraddr := spp }    // Note: SPP for push, not SP
    default       { wraddr := io.dirAddr }
  }

  // ==========================================================================
  // Combinational Flag Outputs
  // ==========================================================================

  // Zero flag: A == 0
  io.zf := (a === B(0, config.width bits))

  // Negative flag: A[31] (sign bit)
  io.nf := a(config.width - 1)

  // Equal flag: A == B
  io.eq := (a === b)

  // ==========================================================================
  // A and B Register Process
  // ==========================================================================

  when(io.enaA) {
    a := amux
  }

  when(io.enaB) {
    when(io.selBmux === False) {
      b := a
    } otherwise {
      b := ramDout
    }
  }

  // ==========================================================================
  // Stack Pointer and VP Register Process
  // ==========================================================================

  // Update SP, SPP, SPM
  spp := smux + 1
  spm := smux - 1
  sp  := smux

  // Stack overflow detection
  when(sp === U(config.stackOverflowThreshold, config.ramWidth bits)) {
    spOvReg := True
  }

  // VP registers update
  when(io.enaVp) {
    vp0 := a(config.ramWidth - 1 downto 0).asUInt
    vp1 := a(config.ramWidth - 1 downto 0).asUInt + 1
    vp2 := a(config.ramWidth - 1 downto 0).asUInt + 2
    vp3 := a(config.ramWidth - 1 downto 0).asUInt + 3
  }

  // AR register update
  when(io.enaAr) {
    ar := a(config.ramWidth - 1 downto 0).asUInt
  }

  // VP + offset calculation (registered)
  vpadd := vp0 + io.opd(6 downto 0).asUInt.resize(config.ramWidth)

  // Operand delay pipeline
  opddly := io.opd

  // Immediate value registration
  immval := imux

  // ==========================================================================
  // Output Assignments
  // ==========================================================================

  io.spOv := spOvReg
  io.aout := a
  io.bout := b

  // Debug outputs for simulation
  io.debugSp := sp
  io.debugWrAddr := ramWraddrReg
  io.debugWrEn := ramWrenReg
  io.debugRdAddrReg := ramRdaddrReg
  io.debugRamDout := ramDout
}


/**
 * Test Bench Wrapper for StackStage (CocoTB Compatible)
 *
 * This wrapper provides the same interface as stack_tb.vhd for CocoTB testing.
 * Uses noIoPrefix() to remove the io_ prefix from port names.
 *
 * Interface matches stack_tb.vhd exactly for seamless CocoTB verification.
 * Key differences from main StackStage:
 * - Explicit clk and reset ports
 * - Flat port structure (no bundles)
 * - Debug outputs for internal state visibility
 * - Uses ClockingArea for proper clock domain handling
 */
case class StackStageTb(
  width: Int = 32,
  jpcWidth: Int = 10,
  ramWidth: Int = 8
) extends Component {

  // Use noIoPrefix to match VHDL interface naming
  noIoPrefix()

  // Clock and reset
  val clk   = in Bool()
  val reset = in Bool()

  // Data Inputs
  val din = in Bits(width bits)
  val dir = in Bits(ramWidth bits)
  val opd = in Bits(16 bits)
  val jpc = in Bits(jpcWidth + 1 bits)

  // ALU Control Inputs
  val sel_sub  = in Bool()
  val sel_amux = in Bool()
  val ena_a    = in Bool()
  val sel_bmux = in Bool()
  val sel_log  = in Bits(2 bits)
  val sel_shf  = in Bits(2 bits)
  val sel_lmux = in Bits(3 bits)
  val sel_imux = in Bits(2 bits)
  val sel_rmux = in Bits(2 bits)
  val sel_smux = in Bits(2 bits)
  val sel_mmux = in Bool()
  val sel_rda  = in Bits(3 bits)
  val sel_wra  = in Bits(3 bits)
  val wr_ena   = in Bool()
  val ena_b    = in Bool()
  val ena_vp   = in Bool()
  val ena_ar   = in Bool()

  // Outputs
  val sp_ov = out Bool()
  val zf    = out Bool()
  val nf    = out Bool()
  val `eq`  = out Bool()  // backticks to avoid Object.eq conflict
  val lt    = out Bool()
  val aout  = out Bits(width bits)
  val bout  = out Bits(width bits)

  // Debug outputs (for testing internal state)
  val dbg_sp  = out Bits(ramWidth bits)
  val dbg_vp0 = out Bits(ramWidth bits)
  val dbg_ar  = out Bits(ramWidth bits)

  // Create a new clock domain using the explicit clk and reset signals
  val stackClockDomain = ClockDomain(
    clock = clk,
    reset = reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  // Instantiate the stack logic in the explicit clock domain
  val stackArea = new ClockingArea(stackClockDomain) {
    val config = StackConfig(width, jpcWidth, ramWidth)

    // ========================================================================
    // Internal Registers
    // ========================================================================

    val a = Reg(Bits(width bits)) init(0)
    val b = Reg(Bits(width bits)) init(0)

    val sp  = Reg(UInt(ramWidth bits)) init(config.initialSP)
    val spp = Reg(UInt(ramWidth bits)) init(config.initialSP + 1)
    val spm = Reg(UInt(ramWidth bits)) init(config.initialSP - 1)

    val vp0 = Reg(UInt(ramWidth bits)) init(0)
    val vp1 = Reg(UInt(ramWidth bits)) init(0)
    val vp2 = Reg(UInt(ramWidth bits)) init(0)
    val vp3 = Reg(UInt(ramWidth bits)) init(0)

    val ar = Reg(UInt(ramWidth bits)) init(0)
    val vpadd = Reg(UInt(ramWidth bits)) init(0)

    val opddly = Reg(Bits(16 bits)) init(0)
    val immval = Reg(Bits(width bits)) init(0)
    val spOvReg = Reg(Bool()) init(False)

    // ========================================================================
    // Embedded Barrel Shifter
    // ========================================================================
    // Using same algorithm as stack_tb.vhd's embedded shifter

    val sout = Bits(width bits)

    val shifterLogic = new Area {
      val shiftin = Bits(64 bits)
      val shiftcnt = UInt(5 bits)
      val zero32 = B(0, 32 bits)

      // Default setup for USHR
      shiftin := zero32 ## b
      shiftcnt := a(4 downto 0).asUInt

      when(sel_shf === B"2'b01") {
        // SHL (shift left): position data at upper bits, invert count
        shiftin := B"1'b0" ## b ## B(0, 31 bits)
        shiftcnt := ~a(4 downto 0).asUInt
      } elsewhen(sel_shf === B"2'b10") {
        // SHR (arithmetic shift right): sign extend
        when(b(31)) {
          shiftin := B"32'hFFFFFFFF" ## b
        } otherwise {
          shiftin := zero32 ## b
        }
      }
      // sel_shf = "00" or "11" is USHR (unsigned shift right)

      // Multi-stage barrel shifter
      val s0 = Bits(64 bits)
      when(shiftcnt(4)) {
        s0 := B(0, 16 bits) ## shiftin(63 downto 16)
      } otherwise {
        s0 := shiftin
      }

      val s1 = Bits(64 bits)
      when(shiftcnt(3)) {
        s1 := B(0, 8 bits) ## s0(63 downto 8)
      } otherwise {
        s1 := s0
      }

      val s2 = Bits(64 bits)
      when(shiftcnt(2)) {
        s2 := B(0, 4 bits) ## s1(63 downto 4)
      } otherwise {
        s2 := s1
      }

      val s3 = Bits(64 bits)
      when(shiftcnt(1)) {
        s3 := B(0, 2 bits) ## s2(63 downto 2)
      } otherwise {
        s3 := s2
      }

      val s4 = Bits(64 bits)
      when(shiftcnt(0)) {
        s4 := B(0, 1 bits) ## s3(63 downto 1)
      } otherwise {
        s4 := s3
      }

      sout := s4(31 downto 0)
    }

    // ========================================================================
    // Embedded Dual-Port RAM
    // ========================================================================

    val stackRam = Mem(Bits(width bits), 1 << ramWidth)

    val rdaddr = UInt(ramWidth bits)
    val wraddr = UInt(ramWidth bits)

    val mmux = Bits(width bits)
    when(sel_mmux === False) {
      mmux := a
    } otherwise {
      mmux := b
    }

    // Write directly to RAM - SpinalHDL Mem.write() is synchronous (registers internally)
    // Matching VHDL RAM component which also has internal registration
    stackRam.write(
      address = wraddr,
      data    = mmux,
      enable  = wr_ena
    )

    // Keep registered values for debug output only
    val ramWraddrReg = Reg(UInt(ramWidth bits)) init(0)
    val ramWrenReg   = Reg(Bool()) init(False)
    ramWraddrReg := wraddr
    ramWrenReg   := wr_ena

    // Registered read address, combinational read data
    val ramDout = stackRam.readSync(
      address = rdaddr,
      enable  = True
    )

    // ========================================================================
    // 33-bit ALU
    // ========================================================================

    val sum = Bits(33 bits)
    val aSigned = (a(width - 1) ## a).asSInt.resize(33 bits)
    val bSigned = (b(width - 1) ## b).asSInt.resize(33 bits)

    when(sel_sub) {
      sum := (bSigned - aSigned).asBits
    } otherwise {
      sum := (bSigned + aSigned).asBits
    }

    lt := sum(32)

    // ========================================================================
    // Logic Unit
    // ========================================================================

    val log = Bits(width bits)

    switch(sel_log) {
      is(B"2'b00") { log := b }
      is(B"2'b01") { log := a & b }
      is(B"2'b10") { log := a | b }
      is(B"2'b11") { log := a ^ b }
    }

    // ========================================================================
    // Register Mux
    // ========================================================================

    val rmux = Bits(jpcWidth + 1 bits)

    switch(sel_rmux) {
      is(B"2'b00") {
        rmux := sp.resize(jpcWidth + 1).asBits
      }
      is(B"2'b01") {
        rmux := vp0.resize(jpcWidth + 1).asBits
      }
      default {
        rmux := jpc
      }
    }

    // ========================================================================
    // Load Mux
    // ========================================================================

    val lmux = Bits(width bits)

    switch(sel_lmux) {
      is(B"3'b000") { lmux := log }
      is(B"3'b001") { lmux := sout }
      is(B"3'b010") { lmux := ramDout }
      is(B"3'b011") { lmux := immval }
      is(B"3'b100") { lmux := din }
      default {
        // Zero-extend rmux to 32 bits (VHDL uses unsigned interpretation)
        lmux := rmux.resized
      }
    }

    // ========================================================================
    // Immediate Mux
    // ========================================================================

    val imux = Bits(width bits)

    switch(sel_imux) {
      is(B"2'b00") {
        imux := B(0, 24 bits) ## opddly(7 downto 0)
      }
      is(B"2'b01") {
        imux := S(opddly(7 downto 0)).resize(width).asBits
      }
      is(B"2'b10") {
        imux := B(0, 16 bits) ## opddly
      }
      is(B"2'b11") {
        imux := S(opddly).resize(width).asBits
      }
    }

    // ========================================================================
    // A Input Mux
    // ========================================================================

    val amux = Bits(width bits)

    when(sel_amux === False) {
      amux := sum(31 downto 0)
    } otherwise {
      amux := lmux
    }

    // ========================================================================
    // Flags (combinational)
    // ========================================================================

    zf := (a === B(0, width bits))
    nf := a(width - 1)
    StackStageTb.this.`eq` := (a === b)

    // ========================================================================
    // Stack Pointer Mux
    // ========================================================================

    val smux = UInt(ramWidth bits)

    switch(sel_smux) {
      is(B"2'b00") { smux := sp }
      is(B"2'b01") { smux := spm }
      is(B"2'b10") { smux := spp }
      is(B"2'b11") { smux := a(ramWidth - 1 downto 0).asUInt }
    }

    // ========================================================================
    // Address Muxes
    // ========================================================================

    switch(sel_rda) {
      is(B"3'b000") { rdaddr := vp0 }
      is(B"3'b001") { rdaddr := vp1 }
      is(B"3'b010") { rdaddr := vp2 }
      is(B"3'b011") { rdaddr := vp3 }
      is(B"3'b100") { rdaddr := vpadd }
      is(B"3'b101") { rdaddr := ar }
      is(B"3'b110") { rdaddr := sp }
      default       { rdaddr := dir.asUInt }
    }

    switch(sel_wra) {
      is(B"3'b000") { wraddr := vp0 }
      is(B"3'b001") { wraddr := vp1 }
      is(B"3'b010") { wraddr := vp2 }
      is(B"3'b011") { wraddr := vp3 }
      is(B"3'b100") { wraddr := vpadd }
      is(B"3'b101") { wraddr := ar }
      is(B"3'b110") { wraddr := spp }  // Note: SPP for push
      default       { wraddr := dir.asUInt }
    }

    // ========================================================================
    // A and B Register Updates
    // ========================================================================

    when(ena_a) {
      a := amux
    }

    when(ena_b) {
      when(sel_bmux === False) {
        b := a
      } otherwise {
        b := ramDout
      }
    }

    // ========================================================================
    // Stack Pointer and VP Register Updates
    // ========================================================================

    spp := smux + 1
    spm := smux - 1
    sp  := smux

    when(sp === U(config.stackOverflowThreshold, ramWidth bits)) {
      spOvReg := True
    }

    when(ena_vp) {
      vp0 := a(ramWidth - 1 downto 0).asUInt
      vp1 := a(ramWidth - 1 downto 0).asUInt + 1
      vp2 := a(ramWidth - 1 downto 0).asUInt + 2
      vp3 := a(ramWidth - 1 downto 0).asUInt + 3
    }

    when(ena_ar) {
      ar := a(ramWidth - 1 downto 0).asUInt
    }

    vpadd := vp0 + opd(6 downto 0).asUInt.resize(ramWidth)
    opddly := opd
    immval := imux

    // ========================================================================
    // Output Assignments
    // ========================================================================

    sp_ov   := spOvReg
    aout    := a
    bout    := b
    dbg_sp  := sp.asBits
    dbg_vp0 := vp0.asBits
    dbg_ar  := ar.asBits
  }
}


/**
 * Generate VHDL for CocoTB verification
 */
object StackStageTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/vhdl",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    device = Device.ALTERA
  )

  config.generateVhdl(StackStageTb(width = 32, jpcWidth = 10, ramWidth = 8))
    .printPruned()

  println("StackStageTb VHDL generated at: generated/vhdl/StackStageTb.vhd")
}


/**
 * Generate Verilog for synthesis
 */
object StackStageVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  )

  config.generateVerilog(StackStage())
    .printPruned()

  println("StackStage Verilog generated at: generated/verilog/StackStage.v")
}
