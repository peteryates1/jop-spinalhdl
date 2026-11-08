package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.TestVectorUtils

/**
 * The carrier's socket straps, read once at start-up.
 *
 * Every socket grounds SODIMM 113, and 115 (LSB) and 117 (MSB) encode its node
 * number: grounded reads 0, open reads 1 through the FPGA's pull-up. A module
 * on any other board reads 113 high, and the cluster design then drives
 * nothing. Nothing is driven until the straps have been stable for
 * `settleCycles` either, since the pull-ups need time.
 */
class MeshStrapsTest extends AnyFunSuite {

  val settle = 40

  lazy val compiled = TestVectorUtils.simWave(SimConfig)
    .workspaceName("MeshStrapsTest")
    .compile(MeshStraps(settle))

  def run(carrierN: Boolean, node: Int)(body: MeshStraps => Unit): Unit =
    compiled.doSim { dut =>
      dut.io.carrierN #= carrierN
      dut.io.node #= node
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling(3)
      dut.clockDomain.deassertReset()
      body(dut)
    }

  def read(dut: MeshStraps) = (dut.io.valid.toBoolean, dut.io.onCarrier.toBoolean, dut.io.nodeId.toInt)

  test("on the carrier each socket reads as its node, once the straps have settled") {
    for (n <- 0 to 3) run(carrierN = false, node = n) { dut =>
      dut.clockDomain.waitSampling(settle - 5)
      assert(!dut.io.valid.toBoolean && !dut.io.onCarrier.toBoolean, s"node $n: valid before settling")
      dut.clockDomain.waitSampling(20)
      assert(read(dut) == (true, true, n), s"node $n")
    }
  }

  test("off the carrier (113 open) the straps are read, and say drive nothing") {
    run(carrierN = true, node = 3) { dut =>
      dut.clockDomain.waitSampling(settle + 20)
      val (valid, onCarrier, _) = read(dut)
      assert(valid && !onCarrier)
    }
  }

  test("a strap that changes while settling restarts the wait, and once read they never change") {
    run(carrierN = false, node = 1) { dut =>
      dut.clockDomain.waitSampling(settle / 2)
      dut.io.node #= 2
      dut.clockDomain.waitSampling(settle - 5)
      assert(!dut.io.valid.toBoolean, "valid less than `settle` cycles after a change")
      dut.clockDomain.waitSampling(20)
      assert(read(dut) == (true, true, 2))
      dut.io.node #= 0
      dut.io.carrierN #= true
      dut.clockDomain.waitSampling(3 * settle)
      assert(read(dut) == (true, true, 2), "the straps were read again")
    }
  }
}
