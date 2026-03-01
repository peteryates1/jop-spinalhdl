package jop.io

import spinal.core._

/**
 * FPU I/O Peripheral with auto-capture of TOS and NOS.
 *
 * Wraps the VexRiscv-derived SpinalHDL FPU (JopFpuAdapter) as a JOP I/O device.
 * Key optimisation: the write address encodes the operation, and the
 * peripheral automatically captures both operands (TOS via wrData,
 * NOS via the direct bout wire) on a single I/O write, halving the
 * I/O overhead compared to the original VHDL microcode.
 *
 * Write register map (address encodes operation):
 *   0: Float ADD — auto-capture wrData→opA, bout→opB, start
 *   1: Float SUB — auto-capture + start
 *   2: Float MUL — auto-capture + start
 *   3: Float DIV — auto-capture + start
 *
 * Read register map:
 *   0: Result (32-bit IEEE 754 float)
 *   1: Status — bit 0 = ready (1 when not computing)
 *
 * Pipeline stall: io.busy is high while the FPU is computing.
 * JopCore adds this to the memBusy chain, so the pipeline stalls
 * automatically after the write until the result is ready.
 *
 * Typical microcode (fadd):
 *   ldi FPU_ADD_ADDR ; stmwa ; stmwd ; pop
 *   ldi FPU_RES_ADDR ; stmra ; wait ; wait ; ldmrd nxt
 *
 * No external pins — FPU is entirely internal to the core.
 * Pure SpinalHDL — compatible with both Verilator simulation and FPGA synthesis.
 */
case class BmbFpu() extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)   // = aout (TOS) from pipeline
    val bout   = in Bits(32 bits)   // Direct NOS wire from pipeline stack stage
    val rdData = out Bits(32 bits)
    val busy   = out Bool()         // High while computing → feeds memBusy
    // Debug ports for simulation tracing
    val dbgOpA    = out Bits(32 bits)
    val dbgOpB    = out Bits(32 bits)
    val dbgOpCode = out UInt(2 bits)
    val dbgStart  = out Bool()
    val dbgResult = out Bits(32 bits)
  }

  // ==========================================================================
  // FPU Core — pure SpinalHDL (VexRiscv-derived IEEE 754)
  // ==========================================================================

  val fpuAdapter = JopFpuAdapter()

  // ==========================================================================
  // Operand and Operation Registers
  // ==========================================================================

  val opA = Reg(Bits(32 bits)) init(0)    // Operand A (from bout = NOS = value1)
  val opB = Reg(Bits(32 bits)) init(0)    // Operand B (from wrData = TOS = value2)
  val opCode = Reg(UInt(2 bits)) init(0)  // FPU operation code (0=ADD,1=SUB,2=MUL,3=DIV)
  val startPulse = Reg(Bool()) init(False)
  val computing = Reg(Bool()) init(False)
  val result = Reg(Bits(32 bits)) init(0)

  // Default: clear start pulse each cycle
  startPulse := False

  // ==========================================================================
  // Write Handling — auto-capture operands + start computation
  // ==========================================================================

  when(io.wr) {
    // Capture operands: bout = NOS = value1 → opA, wrData = TOS = value2 → opB
    // This gives correct operand order for non-commutative ops:
    //   SUB: opA - opB = value1 - value2
    //   DIV: opA / opB = value1 / value2
    opA := io.bout
    opB := io.wrData

    // Encode operation from write sub-address
    opCode := io.addr(1 downto 0)

    startPulse := True
    computing := True
  }

  // ==========================================================================
  // FPU Adapter Wiring
  // ==========================================================================

  fpuAdapter.io.opa    := opA
  fpuAdapter.io.opb    := opB
  fpuAdapter.io.opcode := opCode
  fpuAdapter.io.start  := startPulse

  // ==========================================================================
  // Result Capture — latch on ready pulse from adapter
  // ==========================================================================

  when(fpuAdapter.io.ready) {
    result := fpuAdapter.io.result
    computing := False
  }

  // ==========================================================================
  // Read Mux
  // ==========================================================================

  io.rdData := 0
  switch(io.addr(1 downto 0)) {
    is(0) { io.rdData := result }                // FPU_RES: computation result
    is(1) { io.rdData(0) := !computing }          // FPU_STATUS: bit 0 = ready
  }

  // ==========================================================================
  // Busy Output — stalls pipeline while FPU is computing
  // ==========================================================================

  io.busy := computing

  // Debug outputs
  io.dbgOpA    := opA
  io.dbgOpB    := opB
  io.dbgOpCode := opCode
  io.dbgStart  := startPulse
  io.dbgResult := result
}
