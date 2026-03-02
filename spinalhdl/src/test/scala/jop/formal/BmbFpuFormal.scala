package jop.formal

import spinal.core._
import spinal.core.formal._

/**
 * Formal verification for the BmbFpu I/O register logic.
 *
 * Source: jop/io/BmbFpu.scala (132 lines)
 *
 * FpuCore (embedded via JopFpuAdapter) has async constructs incompatible
 * with SymbiYosys async2sync mode, and clk2fflogic mode breaks past().
 * Tests use an inline replica of BmbFpu's register logic with a mocked
 * FPU interface to verify the I/O peripheral envelope.
 *
 * Properties verified:
 * - Write captures operands and starts computing
 * - opCode derived from write address
 * - startPulse only during write cycle
 * - busy reflects computing state
 */
class BmbFpuFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  /** Minimal replica of BmbFpu register logic with mocked FPU adapter */
  case class BmbFpuShell() extends Component {
    val io = new Bundle {
      val addr   = in UInt(4 bits)
      val rd     = in Bool()
      val wr     = in Bool()
      val wrData = in Bits(32 bits)
      val bout   = in Bits(32 bits)
      val rdData = out Bits(32 bits)
      val busy   = out Bool()
      // Mock FPU adapter interface
      val fpuReady  = in Bool()
      val fpuResult = in Bits(32 bits)
    }

    val opA = Reg(Bits(32 bits)) init(0)
    val opB = Reg(Bits(32 bits)) init(0)
    val opCode = Reg(UInt(2 bits)) init(0)
    val startPulse = Reg(Bool()) init(False)
    val computing = Reg(Bool()) init(False)
    val result = Reg(Bits(32 bits)) init(0)

    startPulse := False

    when(io.wr) {
      opA := io.bout
      opB := io.wrData
      opCode := io.addr(1 downto 0)
      startPulse := True
      computing := True
    }

    when(io.fpuReady) {
      result := io.fpuResult
      computing := False
    }

    io.rdData := 0
    switch(io.addr(1 downto 0)) {
      is(0) { io.rdData := result }
      is(1) { io.rdData(0) := !computing }
    }

    io.busy := computing
  }

  def setupDut(dut: BmbFpuShell): Unit = {
    anyseq(dut.io.addr)
    anyseq(dut.io.rd)
    anyseq(dut.io.wr)
    anyseq(dut.io.wrData)
    anyseq(dut.io.bout)
    anyseq(dut.io.fpuReady)
    anyseq(dut.io.fpuResult)
  }

  test("write captures operands and starts computing") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbFpuShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Prevent FPU ready from interfering with write test
        assume(!dut.io.fpuReady)

        when(pastValidAfterReset()) {
          when(past(dut.io.wr)) {
            assert(dut.computing)
            assert(dut.opA === past(dut.io.bout))
            assert(dut.opB === past(dut.io.wrData))
          }
        }
      })
  }

  test("opCode derived from write address") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbFpuShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        assume(!dut.io.fpuReady)

        when(pastValidAfterReset()) {
          when(past(dut.io.wr)) {
            assert(dut.opCode === past(dut.io.addr)(1 downto 0))
          }
        }
      })
  }

  test("startPulse only during write cycle") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbFpuShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(!past(dut.io.wr)) {
            assert(!dut.startPulse)
          }
        }
      })
  }

  test("busy reflects computing state") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbFpuShell())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.busy === dut.computing)
        }
      })
  }
}
