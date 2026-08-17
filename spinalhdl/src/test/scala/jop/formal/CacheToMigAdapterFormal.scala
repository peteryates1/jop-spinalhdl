package jop.formal

import spinal.core._
import spinal.core.formal._
import spinal.lib._

import jop.ddr3.CacheToMigAdapter

/**
 * Formal verification for the CacheToMigAdapter component.
 *
 * Source: jop/ddr3/CacheToMigAdapter.scala
 *
 * The adapter is multi-outstanding now and has no state machine, so the old
 * IDLE / ISSUE_READ / WAIT_READ properties are gone. What replaces them is the
 * discipline that makes matching responses to commands by POSITION safe, since
 * the MIG user interface provides no tag:
 *
 * - never more than `maxOutstanding` commands in flight, so neither internal
 *   FIFO can overflow — which matters most for `readFifo`, because
 *   `app_rd_data_valid` is a one-cycle pulse that cannot be back-pressured
 * - every issued command records exactly one order-queue entry, and every
 *   response retires exactly one
 * - a returning read is never consumed by a write acknowledgement, which is the
 *   bug this rewrite had to avoid: a write is acked locally on acceptance while
 *   read data comes back much later, so an unordered response stream would let
 *   a later write answer before an earlier read
 * - MIG handshake rules: app_en only with app_rdy, write data presented with the
 *   command, and a read command never asserts app_wdf_wren
 */
class CacheToMigAdapterFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  val outstanding = 4

  def dutOf() = new CacheToMigAdapter(28, maxOutstanding = outstanding)

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

  /**
   * The MIG only returns read data for reads it was given. Without this the
   * solver invents `app_rd_data_valid` pulses out of nothing, which no
   * bounded-capture scheme can survive and which hardware never does.
   */
  def assumeWellFormedMig(dut: CacheToMigAdapter): Unit = {
    val readsOut = Reg(UInt(4 bits)) init (0)
    readsOut := readsOut +
      (dut.issue && !dut.headIsWrite).asUInt -
      dut.io.app_rd_data_valid.asUInt
    when(dut.io.app_rd_data_valid) { assume(readsOut =/= 0) }
    assume(readsOut < 12)
  }

  test("never more than maxOutstanding in flight") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        when(pastValidAfterReset()) {
          assert(dut.owed <= outstanding)
          // This is the bound that keeps the un-stallable read pulse safe.
          assert(dut.readFifo.io.occupancy <= outstanding)
          assert(dut.orderFifo.io.occupancy <= outstanding)
          when(!dut.room) { assert(!dut.io.app_en) }
        }
      })
  }

  test("read data is always captured") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        when(pastValidAfterReset()) {
          // app_rd_data_valid is a one-cycle pulse with no back-pressure. If the
          // push were ever refused the data would be gone for good.
          when(dut.io.app_rd_data_valid) {
            assert(dut.readFifo.io.push.ready)
            assert(dut.readFifo.io.push.fire)
          }
        }
      })
  }

  test("one order entry per issued command, one retired per response") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        when(pastValidAfterReset()) {
          assert(dut.io.app_en === dut.orderFifo.io.push.fire)
          assert(dut.io.rsp.fire === dut.orderFifo.io.pop.fire)
        }
      })
  }

  test("a write acknowledgement never consumes read data") {
    formalConfig
      .withBMC(12)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        when(pastValidAfterReset()) {
          // The whole point of the order queue. A write at the head answers
          // from nothing; only a read may take a buffered line.
          when(dut.io.rsp.fire && dut.rspIsWrite) {
            assert(!dut.readFifo.io.pop.fire)
          }
          // And a read response only ever goes out with data behind it.
          when(dut.io.rsp.fire && !dut.rspIsWrite) {
            assert(dut.readFifo.io.pop.fire)
          }
        }
      })
  }

  test("MIG handshake rules") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // A command is only presented when the MIG can take it.
          when(dut.io.app_en) { assert(dut.io.app_rdy) }
          // Write data goes with the command, and needs its own ready.
          when(dut.io.app_wdf_wren) {
            assert(dut.io.app_en)
            assert(dut.io.app_wdf_rdy)
            assert(dut.io.app_wdf_end)
            assert(dut.io.app_cmd === B"3'x0")
          }
          // A read must never drive the write-data port.
          when(dut.io.app_en && dut.io.app_cmd === B"3'x1") {
            assert(!dut.io.app_wdf_wren)
          }
        }
      })
  }

  test("responsive MIG drains (no deadlock)") {
    formalConfig
      .withBMC(14)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        assume(dut.io.app_rdy)
        assume(dut.io.app_wdf_rdy)
        assume(dut.io.rsp.ready)
        // A MIG that answers every outstanding read promptly.
        assume(dut.io.app_rd_data_valid)

        val stuck = Reg(UInt(5 bits)) init (0)
        when(dut.owed =/= 0) { stuck := stuck + 1 } otherwise { stuck := 0 }

        when(pastValidAfterReset()) {
          assert(stuck <= outstanding + 3)
        }
      })
  }

  test("no response without an outstanding command") {
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(dutOf())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)
        assumeWellFormedMig(dut)

        when(pastValidAfterReset()) {
          when(dut.io.rsp.valid) { assert(dut.owed =/= 0) }
          when(dut.owed === 0) { assert(!dut.io.busy || dut.io.cmd.valid || dut.cmdFifo.io.pop.valid) }
        }
      })
  }
}
