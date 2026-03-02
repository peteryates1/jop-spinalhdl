package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.pipeline.{JumpTable, JumpTableConfig, JumpTableInitData}

/**
 * Formal verification for the JumpTable component.
 *
 * Source: jop/pipeline/JumpTable.scala
 *
 * The JumpTable is a combinational ROM-based lookup with priority muxing:
 *   Exception > Interrupt > Normal bytecode
 *
 * All properties use BMC depth 2 (1 reset + 1 check) since the logic is combinational.
 */
class JumpTableFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("exception has highest priority") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := True
        anyseq(dut.io.intPend)

        when(pastValidAfterReset()) {
          // When excPend is set, jpaddr must always be sysExcAddr
          assert(dut.io.jpaddr === U(JumpTableInitData.simulation.sysExcAddr, dut.config.pcWidth bits))
        }
      })
  }

  test("interrupt priority when no exception") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := False
        dut.io.intPend := True

        when(pastValidAfterReset()) {
          assert(dut.io.jpaddr === U(JumpTableInitData.simulation.sysIntAddr, dut.config.pcWidth bits))
        }
      })
  }

  test("normal bytecode when no pending") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := False
        dut.io.intPend := False

        when(pastValidAfterReset()) {
          // jpaddr should not be exception or interrupt handler
          // (it should be the ROM lookup value — we can't easily assert
          // the exact value without replicating the ROM, but we can verify
          // it's not the special handler addresses when inputs are zero)
          when(dut.io.bytecode === B(0, 8 bits)) {
            // Bytecode 0x00 (nop) should map to its ROM entry, not handlers
            assert(dut.io.jpaddr =/= U(JumpTableInitData.simulation.sysExcAddr, dut.config.pcWidth bits))
            assert(dut.io.jpaddr =/= U(JumpTableInitData.simulation.sysIntAddr, dut.config.pcWidth bits))
          }
        }
      })
  }

}
