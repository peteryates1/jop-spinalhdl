package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

class BytecodeFetchStageTest extends AnyFunSuite {

  // Helper to create DUT with test bytecode ROM
  def createDut(jbcData: Seq[Int]): BytecodeFetchStage = {
    val config = BytecodeFetchConfig(jpcWidth = 11, pcWidth = 11)
    // Pad to full JBC RAM size (2^11 = 2048 bytes)
    val paddedData = jbcData.padTo(1 << config.jpcWidth, 0)
    BytecodeFetchStage(
      config = config,
      jbcInit = Some(paddedData.map(BigInt(_)))
    )
  }

  test("BytecodeFetchStage: reset clears state") {
    SimConfig.withWave.compile(createDut(Seq.fill(16)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Apply reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false

      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Verify reset state
      assert(dut.io.jpc_out.toInt == 0, "jpc should be 0 after reset")
      assert(dut.io.opd.toInt == 0, "opd should be 0 after reset")
    }
  }

  test("BytecodeFetchStage: jpc increments on jfetch") {
    // Simple ROM: all NOP (0x00)
    SimConfig.withWave.compile(createDut(Seq.fill(16)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Initial jpc = 0
      assert(dut.io.jpc_out.toInt == 0, "Initial jpc should be 0")

      // jfetch cycle 1
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      sleep(1)  // Wait for combinational logic to settle
      assert(dut.io.jpc_out.toInt == 1, "jpc should increment to 1")

      // jfetch cycle 2
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 2, "jpc should increment to 2")

      // jfetch cycle 3
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 3, "jpc should increment to 3")

      // Hold (jfetch = false)
      dut.io.jfetch #= false
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 3, "jpc should hold at 3")

      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 3, "jpc should still hold at 3")
    }
  }

  test("BytecodeFetchStage: jpc_wr loads from stack") {
    SimConfig.withWave.compile(createDut(Seq.fill(16)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Write jpc = 0x123
      dut.io.jpc_wr #= true
      dut.io.din #= 0x123
      dut.clockDomain.waitSampling()
      sleep(1)

      assert(dut.io.jpc_out.toInt == 0x123, f"jpc should be 0x123, got 0x${dut.io.jpc_out.toInt.toHexString}")

      // Clear jpc_wr
      dut.io.jpc_wr #= false
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 0x123, "jpc should hold at 0x123")
    }
  }

  test("BytecodeFetchStage: JumpTable integration") {
    // ROM with known bytecodes
    val jbcData = Seq(
      0x00,  // nop at address 0
      0x60,  // iadd at address 1
      0xA7   // goto at address 2
    )

    SimConfig.withWave.compile(createDut(jbcData)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Wait one more cycle for synchronous RAM read
      dut.clockDomain.waitSampling()
      sleep(1)

      // Read NOP at address 0
      val nopAddr = dut.io.jpaddr.toInt
      assert(nopAddr == 0x218, f"NOP should map to 0x218, got 0x$nopAddr%03x")

      // Increment to address 1 (iadd)
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()  // jpc increments 0→1, RAM latches addr 1
      dut.io.jfetch #= false  // Stop incrementing
      sleep(1)
      dut.clockDomain.waitSampling()  // RAM outputs data from addr 1
      sleep(1)

      val iaddAddr = dut.io.jpaddr.toInt
      assert(iaddAddr == 0x26C, f"IADD should map to 0x26C, got 0x$iaddAddr%03x")

      // Increment to address 2 (goto)
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()  // jpc increments 1→2, RAM latches addr 2
      dut.io.jfetch #= false  // Stop incrementing
      sleep(1)
      dut.clockDomain.waitSampling()  // RAM outputs data from addr 2
      sleep(1)

      val gotoAddr = dut.io.jpaddr.toInt
      assert(gotoAddr == 0x296, f"GOTO should map to 0x296, got 0x$gotoAddr%03x")
    }
  }

  test("BytecodeFetchStage: jpc_wr has priority over jfetch") {
    SimConfig.withWave.compile(createDut(Seq.fill(16)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Assert both jpc_wr and jfetch - jpc_wr should win
      dut.io.jpc_wr #= true
      dut.io.din #= 0x456
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      sleep(1)

      // Should load 0x456, not increment from 0
      assert(dut.io.jpc_out.toInt == 0x456, f"jpc_wr should have priority, got 0x${dut.io.jpc_out.toInt.toHexString}")
    }
  }

  test("BytecodeFetchStage: jpc overflow behavior") {
    SimConfig.withWave.compile(createDut(Seq.fill(2048)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Set jpc to near maximum (2047 = 0x7FF)
      dut.io.jpc_wr #= true
      dut.io.din #= 0x7FF
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.jpc_out.toInt == 0x7FF, "jpc should be at 0x7FF")

      // Clear jpc_wr and increment
      dut.io.jpc_wr #= false
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      sleep(1)

      // Should overflow to 0x800 (bit 11 set, indicating overflow)
      val jpcAfterOverflow = dut.io.jpc_out.toInt
      assert(jpcAfterOverflow == 0x800, f"jpc should overflow to 0x800, got 0x$jpcAfterOverflow%03x")
    }
  }

  test("BytecodeFetchStage: operand output remains zero in Phase A") {
    SimConfig.withWave.compile(createDut(Seq.fill(16)(0x00))).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Verify opd is 0 after reset
      assert(dut.io.opd.toInt == 0, "opd should be 0 after reset")

      // Try jfetch - opd should remain 0 (Phase A simplified)
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.opd.toInt == 0, "opd should remain 0 during jfetch")

      // Try jopdfetch - opd should remain 0 (not implemented in Phase A)
      dut.io.jfetch #= false
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.opd.toInt == 0, "opd should remain 0 during jopdfetch (Phase A)")
    }
  }
}
