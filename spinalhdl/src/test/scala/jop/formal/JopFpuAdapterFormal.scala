package jop.formal

import spinal.core._
import spinal.core.formal._

/**
 * Formal verification for the JopFpuAdapter FSM.
 *
 * Source: jop/io/JopFpuAdapter.scala (237 lines)
 *
 * FpuCore has async constructs incompatible with SymbiYosys async2sync.
 * Tests use an inline FSM replica with mocked FpuCore handshake signals
 * (cmd.ready, commit.ready, rsp.valid) to verify the FSM envelope.
 *
 * Properties verified:
 * - Valid state transitions (each state transitions only to valid successors)
 * - ready pulse only in DONE state
 * - IDLE stays IDLE when not started
 * - start latches operands
 */
class JopFpuAdapterFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  /** Minimal FSM replica of JopFpuAdapter without FpuCore */
  case class JopFpuAdapterShell() extends Component {
    val io = new Bundle {
      val opa     = in Bits(32 bits)
      val opb     = in Bits(32 bits)
      val opcode  = in UInt(2 bits)
      val start   = in Bool()
      val result  = out Bits(32 bits)
      val ready   = out Bool()
      // Mock FpuCore handshake signals
      val cmdReady    = in Bool()
      val commitReady = in Bool()
      val rspValid    = in Bool()
      val rspValue    = in Bits(32 bits)
    }

    object State extends SpinalEnum {
      val IDLE, LOAD_A_CMD, LOAD_A_COMMIT, LOAD_B_CMD, LOAD_B_COMMIT,
          COMPUTE_CMD, COMPUTE_COMMIT, STORE_CMD, STORE_COMMIT, STORE_RSP, DONE = newElement()
    }

    val state = RegInit(State.IDLE)
    val opA = Reg(Bits(32 bits))
    val opB = Reg(Bits(32 bits))
    val op  = Reg(UInt(2 bits))
    val resultReg = Reg(Bits(32 bits)) init(0)

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
      is(State.LOAD_A_CMD)     { when(io.cmdReady)    { state := State.LOAD_A_COMMIT } }
      is(State.LOAD_A_COMMIT)  { when(io.commitReady) { state := State.LOAD_B_CMD } }
      is(State.LOAD_B_CMD)     { when(io.cmdReady)    { state := State.LOAD_B_COMMIT } }
      is(State.LOAD_B_COMMIT)  { when(io.commitReady) { state := State.COMPUTE_CMD } }
      is(State.COMPUTE_CMD)    { when(io.cmdReady)    { state := State.COMPUTE_COMMIT } }
      is(State.COMPUTE_COMMIT) { when(io.commitReady) { state := State.STORE_CMD } }
      is(State.STORE_CMD)      { when(io.cmdReady)    { state := State.STORE_COMMIT } }
      is(State.STORE_COMMIT)   { when(io.commitReady) { state := State.STORE_RSP } }
      is(State.STORE_RSP) {
        when(io.rspValid) {
          resultReg := io.rspValue
          state := State.DONE
        }
      }
      is(State.DONE) {
        io.ready := True
        state := State.IDLE
      }
    }
  }

  def setupDut(dut: JopFpuAdapterShell): Unit = {
    anyseq(dut.io.opa)
    anyseq(dut.io.opb)
    anyseq(dut.io.opcode)
    anyseq(dut.io.start)
    anyseq(dut.io.cmdReady)
    anyseq(dut.io.commitReady)
    anyseq(dut.io.rspValid)
    anyseq(dut.io.rspValue)
  }

  test("valid state transitions") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(JopFpuAdapterShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        val S = dut.State

        when(pastValidAfterReset()) {
          when(past(dut.state) === S.IDLE)           { assert(dut.state === S.IDLE || dut.state === S.LOAD_A_CMD) }
          when(past(dut.state) === S.LOAD_A_CMD)     { assert(dut.state === S.LOAD_A_CMD || dut.state === S.LOAD_A_COMMIT) }
          when(past(dut.state) === S.LOAD_A_COMMIT)  { assert(dut.state === S.LOAD_A_COMMIT || dut.state === S.LOAD_B_CMD) }
          when(past(dut.state) === S.LOAD_B_CMD)     { assert(dut.state === S.LOAD_B_CMD || dut.state === S.LOAD_B_COMMIT) }
          when(past(dut.state) === S.LOAD_B_COMMIT)  { assert(dut.state === S.LOAD_B_COMMIT || dut.state === S.COMPUTE_CMD) }
          when(past(dut.state) === S.COMPUTE_CMD)    { assert(dut.state === S.COMPUTE_CMD || dut.state === S.COMPUTE_COMMIT) }
          when(past(dut.state) === S.COMPUTE_COMMIT) { assert(dut.state === S.COMPUTE_COMMIT || dut.state === S.STORE_CMD) }
          when(past(dut.state) === S.STORE_CMD)      { assert(dut.state === S.STORE_CMD || dut.state === S.STORE_COMMIT) }
          when(past(dut.state) === S.STORE_COMMIT)   { assert(dut.state === S.STORE_COMMIT || dut.state === S.STORE_RSP) }
          when(past(dut.state) === S.STORE_RSP)      { assert(dut.state === S.STORE_RSP || dut.state === S.DONE) }
          when(past(dut.state) === S.DONE)           { assert(dut.state === S.IDLE) }
        }
      })
  }

  test("ready pulse only in DONE state") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(JopFpuAdapterShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.ready) {
            assert(dut.state === dut.State.DONE)
          }
        }
      })
  }

  test("IDLE stays IDLE when not started") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JopFpuAdapterShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(!dut.io.start)

        when(pastValidAfterReset()) {
          when(past(dut.state === dut.State.IDLE)) {
            assert(dut.state === dut.State.IDLE)
          }
        }
      })
  }

  test("start latches operands") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JopFpuAdapterShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(past(dut.io.start) && past(dut.state === dut.State.IDLE)) {
            assert(dut.opA === past(dut.io.opa))
            assert(dut.opB === past(dut.io.opb))
            assert(dut.op === past(dut.io.opcode))
          }
        }
      })
  }
}
