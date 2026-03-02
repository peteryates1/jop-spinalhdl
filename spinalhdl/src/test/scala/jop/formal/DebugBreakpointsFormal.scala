package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.debug.DebugBreakpoints

/**
 * Formal verification for the DebugBreakpoints component.
 *
 * Source: jop/debug/DebugBreakpoints.scala (135 lines)
 *
 * Properties verified:
 * - queryCount matches sum of enabled flags
 * - halted suppresses hit
 * - hit implies a matching enabled slot
 * - set allocates and enables a slot
 * - clear disables the specified slot
 */
class DebugBreakpointsFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  val numSlots = 4

  def setupDut(dut: DebugBreakpoints): Unit = {
    anyseq(dut.io.pc)
    anyseq(dut.io.jpc)
    anyseq(dut.io.jfetch)
    anyseq(dut.io.halted)
    anyseq(dut.io.setValid)
    anyseq(dut.io.setType)
    anyseq(dut.io.setAddr)
    anyseq(dut.io.clearValid)
    anyseq(dut.io.clearSlot)
    anyseq(dut.io.queryValid)
  }

  test("queryCount matches sum of enabled flags") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(DebugBreakpoints(numSlots))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          val sum = dut.enabled.map(e => Mux(e, U(1, 4 bits), U(0, 4 bits))).reduce(_ + _)
          assert(dut.io.queryCount === sum)
        }
      })
  }

  test("halted suppresses hit") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(DebugBreakpoints(numSlots))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.halted) {
            assert(!dut.io.hit)
          }
        }
      })
  }

  test("hit implies matching enabled slot") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(DebugBreakpoints(numSlots))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        when(pastValidAfterReset()) {
          when(dut.io.hit) {
            // There must exist an enabled slot with address match
            val anyMatch = (0 until numSlots).map { i =>
              dut.enabled(i) && (
                (dut.bpType(i) === 0x00 && dut.io.pc === dut.bpAddr(i).resized) ||
                (dut.bpType(i) =/= 0x00 && dut.io.jfetch && dut.io.jpc === dut.bpAddr(i).resized)
              )
            }.reduce(_ || _)
            assert(anyMatch)
          }
        }
      })
  }

  test("set allocates and enables a slot") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(DebugBreakpoints(numSlots))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Isolate set behavior: no concurrent clears
        assume(!dut.io.clearValid)

        when(pastValidAfterReset()) {
          when(past(dut.io.setValid) && past(dut.io.setOk)) {
            assert(dut.enabled(past(dut.io.setSlot).resized))
          }
        }
      })
  }

  test("clear disables the specified slot") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(DebugBreakpoints(numSlots))
        assumeInitial(ClockDomain.current.isResetActive)
        setupDut(dut)

        // Isolate clear behavior: no concurrent sets
        assume(!dut.io.setValid)

        when(pastValidAfterReset()) {
          when(past(dut.io.clearValid) && past(dut.io.clearSlot) < numSlots) {
            assert(!dut.enabled(past(dut.io.clearSlot).resized))
          }
        }
      })
  }
}
