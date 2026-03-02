package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.io.BmbSdNative

/**
 * Formal verification for the BmbSdNative SD card controller.
 *
 * Source: jop/io/BmbSdNative.scala (822 lines)
 *
 * Large component with 128-entry FIFO memory; use shallow BMC depths.
 * Focus on key invariants rather than deep protocol verification
 * (protocol already covered by BmbSdNativeTest simulation).
 *
 * Properties verified:
 * - FIFO occupancy coherence with pointer difference
 * - Clock divider toggles on counter match
 * - CMD state valid transitions (no abort)
 * - DATA state valid transitions (no abort)
 * - cmdBusy true while CMD state machine active
 * - FIFO occupancy bounded at 128
 */
class BmbSdNativeFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  def setupDut(dut: BmbSdNative): Unit = {
    anyseq(dut.io.addr)
    anyseq(dut.io.rd)
    anyseq(dut.io.wr)
    anyseq(dut.io.wrData)
    anyseq(dut.io.sdCmd.read)
    anyseq(dut.io.sdDat.read)
    anyseq(dut.io.sdCd)
  }

  test("FIFO occupancy coherence") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Prevent FIFO underflow/overflow: no data FSM operations, no abort
        assume(!(dut.io.wr && dut.io.addr === 7))
        assume(!(dut.io.wr && dut.io.addr === 0 && dut.io.wrData(1)))
        // Prevent pop when empty (CPU read at addr 5)
        assume(!(dut.io.rd && dut.io.addr === 5 && dut.fifoEmpty))
        // Prevent push when full (CPU write at addr 5)
        assume(!(dut.io.wr && dut.io.addr === 5 && dut.fifoFull))

        when(pastValidAfterReset()) {
          // When FIFO is not full, occupancy equals pointer difference
          when(!dut.fifoFull) {
            assert(dut.fifoOccupancy === (dut.fifoWrPtr - dut.fifoRdPtr).resize(8))
          }
          // When full, pointers are equal but occupancy is 128
          when(dut.fifoFull) {
            assert(dut.fifoWrPtr === dut.fifoRdPtr)
          }
        }
      })
  }

  test("clock divider toggles on counter match") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Constrain divider to 0 so match occurs every cycle (reachable in BMC 4)
        assume(dut.clkDivider === 0)

        when(pastValidAfterReset()) {
          when(past(dut.clkCounter === dut.clkDivider)) {
            assert(dut.sdClkReg === !past(dut.sdClkReg))
          }
        }
      })
  }

  test("CMD state valid transitions") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume no abort to isolate normal CMD state machine flow
        assume(!(dut.io.wr && dut.io.addr === 0 && dut.io.wrData(1)))

        val CS = dut.CmdState

        when(pastValidAfterReset()) {
          when(past(dut.cmdState) === CS.IDLE)      { assert(dut.cmdState === CS.IDLE || dut.cmdState === CS.SENDING) }
          when(past(dut.cmdState) === CS.SENDING)    { assert(dut.cmdState === CS.SENDING || dut.cmdState === CS.WAIT_RSP || dut.cmdState === CS.DONE || dut.cmdState === CS.IDLE) }
          when(past(dut.cmdState) === CS.WAIT_RSP)   { assert(dut.cmdState === CS.WAIT_RSP || dut.cmdState === CS.RECEIVING || dut.cmdState === CS.DONE || dut.cmdState === CS.IDLE) }
          when(past(dut.cmdState) === CS.RECEIVING)  { assert(dut.cmdState === CS.RECEIVING || dut.cmdState === CS.DONE || dut.cmdState === CS.IDLE) }
          when(past(dut.cmdState) === CS.DONE)       { assert(dut.cmdState === CS.IDLE) }
        }
      })
  }

  test("DATA state valid transitions") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume no abort and no mid-stream startRead/startWrite
        assume(!(dut.io.wr && dut.io.addr === 0 && dut.io.wrData(1)))
        assume(!(dut.io.wr && dut.io.addr === 7) || dut.dataState === dut.DataState.IDLE)

        val DS = dut.DataState

        when(pastValidAfterReset()) {
          when(past(dut.dataState) === DS.IDLE)            { assert(dut.dataState === DS.IDLE || dut.dataState === DS.WAIT_START || dut.dataState === DS.SEND_START) }
          when(past(dut.dataState) === DS.WAIT_START)      { assert(dut.dataState === DS.WAIT_START || dut.dataState === DS.RECEIVING || dut.dataState === DS.DONE || dut.dataState === DS.IDLE) }
          when(past(dut.dataState) === DS.RECEIVING)        { assert(dut.dataState === DS.RECEIVING || dut.dataState === DS.RECV_CRC) }
          when(past(dut.dataState) === DS.RECV_CRC)         { assert(dut.dataState === DS.RECV_CRC || dut.dataState === DS.DONE || dut.dataState === DS.IDLE) }
          when(past(dut.dataState) === DS.SEND_START)       { assert(dut.dataState === DS.SEND_START || dut.dataState === DS.SENDING) }
          when(past(dut.dataState) === DS.SENDING)          { assert(dut.dataState === DS.SENDING || dut.dataState === DS.SEND_CRC) }
          when(past(dut.dataState) === DS.SEND_CRC)         { assert(dut.dataState === DS.SEND_CRC || dut.dataState === DS.SEND_STOP) }
          when(past(dut.dataState) === DS.SEND_STOP)        { assert(dut.dataState === DS.SEND_STOP || dut.dataState === DS.WAIT_CRC_STATUS) }
          when(past(dut.dataState) === DS.WAIT_CRC_STATUS)  { assert(dut.dataState === DS.WAIT_CRC_STATUS || dut.dataState === DS.WAIT_BUSY || dut.dataState === DS.DONE || dut.dataState === DS.IDLE) }
          when(past(dut.dataState) === DS.WAIT_BUSY)        { assert(dut.dataState === DS.WAIT_BUSY || dut.dataState === DS.DONE || dut.dataState === DS.IDLE) }
          when(past(dut.dataState) === DS.DONE)             { assert(dut.dataState === DS.IDLE) }
        }
      })
  }

  test("cmdBusy true while CMD active") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Assume no abort
        assume(!(dut.io.wr && dut.io.addr === 0 && dut.io.wrData(1)))

        val CS = dut.CmdState

        when(pastValidAfterReset()) {
          when(dut.cmdState === CS.SENDING || dut.cmdState === CS.WAIT_RSP || dut.cmdState === CS.RECEIVING) {
            assert(dut.cmdBusy)
          }
        }
      })
  }

  test("FIFO occupancy bounded at 128") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbSdNative())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Prevent FIFO underflow/overflow: no data FSM operations, no abort
        assume(!(dut.io.wr && dut.io.addr === 7))
        assume(!(dut.io.wr && dut.io.addr === 0 && dut.io.wrData(1)))
        // Prevent pop when empty, push when full
        assume(!(dut.io.rd && dut.io.addr === 5 && dut.fifoEmpty))
        assume(!(dut.io.wr && dut.io.addr === 5 && dut.fifoFull))

        when(pastValidAfterReset()) {
          assert(dut.fifoOccupancy <= 128)
        }
      })
  }
}
