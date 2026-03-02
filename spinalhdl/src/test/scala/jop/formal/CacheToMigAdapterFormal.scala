package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib._

import jop.ddr3.{CacheToMigAdapter, CacheToMigAdapterState}

/**
 * Formal verification for the CacheToMigAdapter component.
 *
 * Source: jop/ddr3/CacheToMigAdapter.scala
 *
 * Properties verified:
 * - Initial state: IDLE, not busy
 * - busy reflects non-IDLE state
 * - Responsive MIG returns to IDLE (no deadlock)
 * - IDLE stable when no commands
 * - Read data captured when rspFifo full (MIG one-cycle pulse protection)
 * - Write: both cmd and data sent before response
 * - app_en only asserted in active states
 */
class CacheToMigAdapterFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  def setupDut(dut: CacheToMigAdapter): Unit = {
    anyseq(dut.io.cmd.valid)
    anyseq(dut.io.cmd.payload.addr)
    anyseq(dut.io.cmd.payload.write)
    anyseq(dut.io.cmd.payload.wdata)
    anyseq(dut.io.cmd.payload.wmask)
    anyseq(dut.io.rsp.ready)
    anyseq(dut.io.app_rdy)
    anyseq(dut.io.app_wdf_rdy)
    anyseq(dut.io.app_rd_data)
    anyseq(dut.io.app_rd_data_valid)
  }

  test("initial state after reset") {
    formalConfig
      .withBMC(2)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.state === CacheToMigAdapterState.IDLE)
          assert(!dut.io.busy)
        }
      })
  }

  test("busy reflects non-IDLE state") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.busy === (dut.state =/= CacheToMigAdapterState.IDLE))
        }
      })
  }

  test("IDLE stable when no commands") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // No commands ever
        assume(!dut.io.cmd.valid)

        when(pastValidAfterReset()) {
          assert(dut.state === CacheToMigAdapterState.IDLE)
          assert(!dut.io.app_en)
        }
      })
  }

  test("responsive MIG returns to IDLE (no deadlock)") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume responsive MIG
        assume(dut.io.app_rdy)
        assume(dut.io.app_wdf_rdy)
        // For reads, data arrives 1 cycle after ISSUE_READ
        when(past(dut.state === CacheToMigAdapterState.ISSUE_READ) &&
             past(dut.io.app_rdy)) {
          assume(dut.io.app_rd_data_valid)
        }
        // Consumer drains responses
        assume(dut.io.rsp.ready)

        val stuckCounter = Reg(UInt(4 bits)) init(0)
        when(dut.state =/= CacheToMigAdapterState.IDLE) {
          stuckCounter := stuckCounter + 1
        } otherwise {
          stuckCounter := 0
        }

        when(pastValidAfterReset()) {
          // Should never be stuck for more than 6 cycles
          assert(stuckCounter < 6)
        }
      })
  }

  test("app_en only in ISSUE_WRITE or ISSUE_READ") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.app_en) {
            assert(
              dut.state === CacheToMigAdapterState.ISSUE_WRITE ||
              dut.state === CacheToMigAdapterState.ISSUE_READ
            )
          }
        }
      })
  }

  test("app_wdf_wren only in ISSUE_WRITE") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.app_wdf_wren) {
            assert(dut.state === CacheToMigAdapterState.ISSUE_WRITE)
          }
        }
      })
  }

  test("read data captured on rspFifo backpressure") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // If we're in WAIT_READ, data arrives, but rspFifo is full,
          // data must be captured in readDataReg
          when(past(dut.state === CacheToMigAdapterState.WAIT_READ) &&
               !past(dut.readDataCaptured) &&
               past(dut.io.app_rd_data_valid) &&
               !past(dut.rspFifo.io.push.ready)) {
            assert(dut.readDataCaptured)
          }
        }
      })
  }

  test("write response only after both cmd and data sent") {
    formalConfig
      .withBMC(8)
      .doVerify(new Component {
        val dut = FormalDut(new CacheToMigAdapter())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // In ISSUE_WRITE, rspFifo push only when both phases complete
          when(dut.state === CacheToMigAdapterState.ISSUE_WRITE &&
               dut.rspFifo.io.push.valid) {
            assert(dut.writeCmdSent)
            assert(dut.writeDataSent)
          }
        }
      })
  }
}
