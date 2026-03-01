package jop.io

import spinal.core._
import spinal.lib._
import jop.ip.fpu._

/**
 * Adapter wrapping VexRiscv's FpuCore for JOP's simple I/O peripheral model.
 *
 * JOP writes two IEEE 754 operands + operation code, then reads back the result.
 * FpuCore expects a multi-stream cmd/commit/rsp protocol with internal register file.
 * This FSM bridges the two: it loads operands into FpuCore registers, issues the
 * compute command, then stores the result back out via the rsp stream.
 *
 * FpuCore register allocation:
 *   r0 = operand A
 *   r1 = operand B
 *   r2 = result
 *
 * Operations: 0=ADD, 1=SUB (ADD with arg(0)=1), 2=MUL, 3=DIV
 */
case class JopFpuAdapter() extends Component {
  val io = new Bundle {
    val opa     = in Bits(32 bits)
    val opb     = in Bits(32 bits)
    val opcode  = in UInt(2 bits)    // 0=ADD, 1=SUB, 2=MUL, 3=DIV
    val start   = in Bool()
    val result  = out Bits(32 bits)
    val ready   = out Bool()         // Single-cycle pulse when result is valid
  }

  // FpuCore with minimal single-precision configuration
  val fpuParam = FpuParameter(
    withDouble = false,
    withAdd = true,
    withMul = true,
    withDiv = true,
    withSqrt = false,
    withShortPipMisc = true,    // Needed for LOAD/STORE opcodes
    sim = false
  )

  val fpu = FpuCore(portCount = 1, p = fpuParam)
  val port = fpu.io.port(0)

  // FSM states
  object State extends SpinalEnum {
    val IDLE, LOAD_A_CMD, LOAD_A_COMMIT, LOAD_B_CMD, LOAD_B_COMMIT,
        COMPUTE_CMD, COMPUTE_COMMIT, STORE_CMD, STORE_COMMIT, STORE_RSP, DONE = newElement()
  }

  val state = RegInit(State.IDLE)

  // Latched inputs
  val opA = Reg(Bits(32 bits))
  val opB = Reg(Bits(32 bits))
  val op  = Reg(UInt(2 bits))

  // Result register
  val resultReg = Reg(Bits(32 bits)) init(0)

  // Default: drive all streams invalid
  port.cmd.valid   := False
  port.cmd.opcode  := FpuOpcode.LOAD
  port.cmd.arg     := 0
  port.cmd.rs1     := 0
  port.cmd.rs2     := 0
  port.cmd.rs3     := 0
  port.cmd.rd      := 0
  port.cmd.format  := FpuFormat.FLOAT
  port.cmd.roundMode := FpuRoundMode.RNE

  port.commit.valid  := False
  port.commit.opcode := FpuOpcode.LOAD
  port.commit.rd     := 0
  port.commit.write  := False
  port.commit.value  := 0

  port.rsp.ready := False

  // Ready output (single-cycle pulse)
  io.ready := False
  io.result := resultReg

  switch(state) {
    is(State.IDLE) {
      when(io.start) {
        opA := io.opa
        opB := io.opb
        op  := io.opcode
        state := State.LOAD_A_CMD
      }
    }

    // ========================================
    // LOAD operand A into FPU register r0
    // ========================================
    is(State.LOAD_A_CMD) {
      port.cmd.valid  := True
      port.cmd.opcode := FpuOpcode.LOAD
      port.cmd.rd     := 0
      port.cmd.format := FpuFormat.FLOAT
      port.cmd.roundMode := FpuRoundMode.RNE
      when(port.cmd.ready) {
        state := State.LOAD_A_COMMIT
      }
    }
    is(State.LOAD_A_COMMIT) {
      port.commit.valid  := True
      port.commit.opcode := FpuOpcode.LOAD
      port.commit.rd     := 0
      port.commit.write  := True
      port.commit.value  := opA
      when(port.commit.ready) {
        state := State.LOAD_B_CMD
      }
    }

    // ========================================
    // LOAD operand B into FPU register r1
    // ========================================
    is(State.LOAD_B_CMD) {
      port.cmd.valid  := True
      port.cmd.opcode := FpuOpcode.LOAD
      port.cmd.rd     := 1
      port.cmd.format := FpuFormat.FLOAT
      port.cmd.roundMode := FpuRoundMode.RNE
      when(port.cmd.ready) {
        state := State.LOAD_B_COMMIT
      }
    }
    is(State.LOAD_B_COMMIT) {
      port.commit.valid  := True
      port.commit.opcode := FpuOpcode.LOAD
      port.commit.rd     := 1
      port.commit.write  := True
      port.commit.value  := opB
      when(port.commit.ready) {
        state := State.COMPUTE_CMD
      }
    }

    // ========================================
    // COMPUTE: issue ADD/SUB/MUL/DIV
    // ========================================
    is(State.COMPUTE_CMD) {
      port.cmd.valid := True
      port.cmd.rs1   := 0
      port.cmd.rs2   := 1
      port.cmd.rd    := 2
      port.cmd.format := FpuFormat.FLOAT
      port.cmd.roundMode := FpuRoundMode.RNE

      switch(op) {
        is(0) {  // ADD
          port.cmd.opcode := FpuOpcode.ADD
          port.cmd.arg    := 0
        }
        is(1) {  // SUB = ADD with arg(0)=1 (negate rs2 sign)
          port.cmd.opcode := FpuOpcode.ADD
          port.cmd.arg    := 1
        }
        is(2) {  // MUL
          port.cmd.opcode := FpuOpcode.MUL
          port.cmd.arg    := 0
        }
        is(3) {  // DIV
          port.cmd.opcode := FpuOpcode.DIV
          port.cmd.arg    := 0
        }
      }

      when(port.cmd.ready) {
        state := State.COMPUTE_COMMIT
      }
    }
    is(State.COMPUTE_COMMIT) {
      port.commit.valid := True
      port.commit.rd    := 2
      port.commit.write := True
      port.commit.value := 0  // Not used for compute, but must be driven

      switch(op) {
        is(0) { port.commit.opcode := FpuOpcode.ADD }
        is(1) { port.commit.opcode := FpuOpcode.ADD }
        is(2) { port.commit.opcode := FpuOpcode.MUL }
        is(3) { port.commit.opcode := FpuOpcode.DIV }
      }

      when(port.commit.ready) {
        state := State.STORE_CMD
      }
    }

    // ========================================
    // STORE: read result from FPU register r2
    // ========================================
    is(State.STORE_CMD) {
      port.cmd.valid  := True
      port.cmd.opcode := FpuOpcode.STORE
      port.cmd.rs1    := 2   // Note: STORE reads from rs1 in cmd, but FpuCore remaps to rs2 internally
      port.cmd.rs2    := 2
      port.cmd.rd     := 2
      port.cmd.format := FpuFormat.FLOAT
      port.cmd.roundMode := FpuRoundMode.RNE
      when(port.cmd.ready) {
        state := State.STORE_COMMIT
      }
    }
    is(State.STORE_COMMIT) {
      port.commit.valid  := True
      port.commit.opcode := FpuOpcode.STORE
      port.commit.rd     := 2
      port.commit.write  := False  // STORE doesn't write to RF
      port.commit.value  := 0
      when(port.commit.ready) {
        state := State.STORE_RSP
      }
    }
    is(State.STORE_RSP) {
      port.rsp.ready := True
      when(port.rsp.valid) {
        resultReg := port.rsp.value(31 downto 0)
        state := State.DONE
      }
    }

    // ========================================
    // DONE: pulse ready, return to IDLE
    // ========================================
    is(State.DONE) {
      io.ready := True
      state := State.IDLE
    }
  }
}
