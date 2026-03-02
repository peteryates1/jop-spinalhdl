package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.io.BmbUart

/**
 * Formal verification for the BmbUart component.
 *
 * Source: jop/io/BmbUart.scala
 *
 * Note: UartCtrl internals are complex (shift register, baud generator).
 * We verify the I/O slave interface and FIFO handshake properties.
 *
 * Properties verified:
 * - Status register bit 0 reflects TX FIFO availability
 * - Status register bit 1 reflects RX FIFO data presence
 * - TX FIFO push only on write to addr 1
 * - RX FIFO pop only on read from addr 1
 */
class BmbUartFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(300)

  def setupDut(dut: BmbUart): Unit = {
    anyseq(dut.io.addr)
    anyseq(dut.io.rd)
    anyseq(dut.io.wr)
    anyseq(dut.io.wrData)
    anyseq(dut.io.rxd)
  }

  test("TX push only on write to addr 1") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbUart())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.txFifo.io.push.valid) {
            assert(dut.io.wr)
            assert(dut.io.addr === 1)
          }
        }
      })
  }

  test("RX pop only on read from addr 1") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbUart())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.rxFifo.io.pop.ready) {
            assert(dut.io.rd)
            assert(dut.io.addr === 1)
          }
        }
      })
  }

  test("no TX push when not writing") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbUart())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(!dut.io.wr) {
            assert(!dut.txFifo.io.push.valid)
          }
        }
      })
  }

  test("status bit 0 reflects TX FIFO availability") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbUart())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          // When reading status (addr 0), bit 0 should indicate TX FIFO not full
          when(dut.io.addr === 0) {
            assert(dut.io.rdData(0) === dut.txFifo.io.availability.orR)
          }
        }
      })
  }

  test("status bit 1 reflects RX FIFO data presence") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BmbUart())
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.addr === 0) {
            assert(dut.io.rdData(1) === dut.rxFifo.io.pop.valid)
          }
        }
      })
  }
}
