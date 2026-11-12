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
 *   Exception > GC stop > Interrupt > Normal bytecode
 *
 * BMC DEPTH 4, NOT 2 -- status item 165. The logic is combinational, so one
 * checked step would do, but every assertion sits under pastValidAfterReset(),
 * and in this flow an assertion under it is first checked at depth 4: a
 * guarded assert(False) passes at depths 2 and 3 and fails at 4 (calibrated
 * 2026-11-12). At depth 2 none of the assertions here had ever been checked.
 * Found when the jump table's GC-stop branch was disabled and its property
 * still passed; at depth 4 that break fails it.
 */
class JumpTableFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("exception has highest priority") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := True
        anyseq(dut.io.intPend)
        anyseq(dut.io.gcStopPend)

        when(pastValidAfterReset()) {
          // When excPend is set, jpaddr must always be sysExcAddr
          assert(dut.io.jpaddr === U(JumpTableInitData.simulation.sysExcAddr, dut.config.pcWidth bits))
        }
      })
  }

  test("interrupt priority when no exception") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := False
        dut.io.intPend := True
        dut.io.gcStopPend := False

        when(pastValidAfterReset()) {
          assert(dut.io.jpaddr === U(JumpTableInitData.simulation.sysIntAddr, dut.config.pcWidth bits))
        }
      })
  }

  test("normal bytecode when no pending") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := False
        dut.io.intPend := False
        dut.io.gcStopPend := False

        when(pastValidAfterReset()) {
          // jpaddr should not be exception or interrupt handler
          // (it should be the ROM lookup value — we can't easily assert
          // the exact value without replicating the ROM, but we can verify
          // it's not the special handler addresses when inputs are zero)
          when(dut.io.bytecode === B(0, 8 bits)) {
            // Bytecode 0x00 (nop) should map to its ROM entry, not handlers
            assert(dut.io.jpaddr =/= U(JumpTableInitData.simulation.sysExcAddr, dut.config.pcWidth bits))
            assert(dut.io.jpaddr =/= U(JumpTableInitData.simulation.sysIntAddr, dut.config.pcWidth bits))
            assert(dut.io.jpaddr =/= U(JumpTableInitData.simulation.sysGcStopAddr, dut.config.pcWidth bits))
          }
        }
      })
  }

  // Item 165: a GC-halted core waits in sys_gcstop instead of dispatching, and
  // an interrupt stays pending until the release -- so the stop outranks it.
  test("GC stop outranks an interrupt when no exception") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(JumpTable())
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.bytecode)
        dut.io.excPend := False
        anyseq(dut.io.intPend)
        dut.io.gcStopPend := True

        when(pastValidAfterReset()) {
          assert(dut.io.jpaddr === U(JumpTableInitData.simulation.sysGcStopAddr, dut.config.pcWidth bits))
        }
      })
  }

}
