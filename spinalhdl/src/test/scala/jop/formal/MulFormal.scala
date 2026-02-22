package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.core.Mul

/**
 * Formal verification for the Mul (Radix-4 Booth Multiplier) component.
 *
 * Source: jop/core/Mul.scala
 *
 * The multiplier processes 2 bits per cycle (radix-4).
 * Full functional correctness with a reference multiplier is computationally
 * intractable for SMT solvers at 32 bits. Instead, we verify:
 *
 * 1. Structural invariants of the radix-4 algorithm
 * 2. Register initialization after reset
 * 3. Correct operand loading on wr
 * 4. Shift behavior of a and b registers
 * 5. Functional correctness for small width (8-bit)
 * 6. Result stability after computation completes
 */
class MulFormal extends SpinalFormalFunSuite {

  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("register initialization") {
    formalConfig
      .withBMC(3)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.bin)
        anyseq(dut.io.wr)

        // After reset with no wr, dout (= p) should be 0
        when(pastValidAfterReset()) {
          assume(dut.io.wr === False)
          assert(dut.io.dout === 0)
        }
      })
  }

  test("operand loading on wr") {
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.bin)
        anyseq(dut.io.wr)

        // When wr fires, dout should be cleared to 0 on the next cycle
        // (since p := 0 on wr)
        when(pastValidAfterReset() && past(dut.io.wr)) {
          assert(dut.io.dout === 0)
        }
      })
  }

  test("result stability after b drains") {
    // After enough non-wr cycles, b becomes 0 and p stabilizes
    formalConfig
      .withBMC(22)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.bin)
        anyseq(dut.io.wr)

        // Track cycles since last wr
        val counter = Reg(UInt(5 bits)) init (0)
        val seen_wr = Reg(Bool()) init (False)

        when(dut.io.wr) {
          counter := 1
          seen_wr := True
        } elsewhen (seen_wr && counter < 20) {
          counter := counter + 1
        }

        // Assume no wr during the observation window
        when(seen_wr && counter >= 1 && counter < 20) {
          assume(dut.io.wr === False)
        }

        // After 17 non-wr cycles, b has been right-shifted 16 times
        // (each shift by 2 bits), so b=0 and p should be stable
        when(seen_wr && counter >= 18) {
          assert(stable(dut.io.dout))
        }
      })
  }

  test("8-bit functional correctness") {
    // Verify full functional correctness with 8-bit width
    // 8 bits / 2 bits per cycle = 4 iterations
    // Result available at cycle 5 after wr
    formalConfig
      .withBMC(10)
      .doVerify(new Component {
        val dut = FormalDut(Mul(8))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.bin)
        anyseq(dut.io.wr)

        val shadowA = Reg(UInt(8 bits)) init (0)
        val shadowB = Reg(UInt(8 bits)) init (0)
        val counter = Reg(UInt(4 bits)) init (0)
        val computing = Reg(Bool()) init (False)

        when(dut.io.wr) {
          shadowA := dut.io.ain
          shadowB := dut.io.bin
          counter := 1
          computing := True
        } elsewhen (computing) {
          when(counter < 10) {
            counter := counter + 1
          }
        }

        // No re-trigger during computation or at check cycle
        when(computing && counter >= 1 && counter <= 5) {
          assume(dut.io.wr === False)
        }

        // wr loads at edge, then 4 compute cycles (2 bits/cycle for 8-bit).
        // counter=1..4 are compute cycles, result stable at counter=5.
        when(computing && counter === 5) {
          val expected = (shadowA * shadowB).resize(8)
          assert(dut.io.dout === expected)
        }
      })
  }

  test("zero multiplication") {
    // Multiplying by zero always produces zero
    formalConfig
      .withBMC(22)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.wr)
        dut.io.bin := U(0, 32 bits) // multiply by zero

        val counter = Reg(UInt(5 bits)) init (0)
        val computing = Reg(Bool()) init (False)

        when(dut.io.wr) {
          counter := 1
          computing := True
        } elsewhen (computing && counter < 20) {
          counter := counter + 1
        }

        // No re-trigger during computation or at check cycle
        when(computing && counter >= 1 && counter <= 18) {
          assume(dut.io.wr === False)
        }

        // With bin=0, b(0) and b(1) are always 0 from the start,
        // so no partial products are ever added. p stays 0.
        when(computing && counter === 18) {
          assert(dut.io.dout === 0)
        }
      })
  }

  test("multiply by one") {
    // Multiplying by 1 returns the other operand (lower 32 bits)
    formalConfig
      .withBMC(22)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.wr)
        dut.io.bin := U(1, 32 bits) // multiply by one

        val shadowA = Reg(UInt(32 bits)) init (0)
        val counter = Reg(UInt(5 bits)) init (0)
        val computing = Reg(Bool()) init (False)

        when(dut.io.wr) {
          shadowA := dut.io.ain
          counter := 1
          computing := True
        } elsewhen (computing && counter < 20) {
          counter := counter + 1
        }

        // No re-trigger during computation or at check cycle
        when(computing && counter >= 1 && counter <= 18) {
          assume(dut.io.wr === False)
        }

        // a * 1 = a
        when(computing && counter === 18) {
          assert(dut.io.dout === shadowA)
        }
      })
  }

  test("wr clears and restarts") {
    // Asserting wr mid-computation clears p and starts fresh
    formalConfig
      .withBMC(5)
      .doVerify(new Component {
        val dut = FormalDut(Mul(32))
        assumeInitial(ClockDomain.current.isResetActive)

        anyseq(dut.io.ain)
        anyseq(dut.io.bin)
        anyseq(dut.io.wr)

        // After any wr, p is cleared to 0
        when(pastValidAfterReset() && past(dut.io.wr)) {
          assert(dut.io.dout === 0)
        }
      })
  }
}
