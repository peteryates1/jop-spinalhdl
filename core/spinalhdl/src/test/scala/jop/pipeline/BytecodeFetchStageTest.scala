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

  test("BytecodeFetchStage: operand accumulation with jopdfetch") {
    // Test data: sequence of bytes to accumulate
    val jbcData = Seq(
      0x12,  // First bytecode
      0x34,  // First operand byte
      0x56,  // Second operand byte
      0x78,  // Third bytecode
      0x9A,  // Fourth operand byte
      0xBC   // Fifth operand byte
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

      // Wait for RAM read - low byte will load from RAM at address 0
      dut.clockDomain.waitSampling()
      sleep(1)

      // After reset + RAM read, low byte has first RAM byte (0x12), high byte is 0
      val opdAfterReset = dut.io.opd.toInt
      assert((opdAfterReset & 0xFF) == 0x12, f"Low byte should be 0x12 from RAM, got 0x${opdAfterReset & 0xFF}%02x")
      assert((opdAfterReset >> 8) == 0, f"High byte should be 0 after reset, got 0x${opdAfterReset >> 8}%02x")

      // Fetch instruction bytecode at address 0 (0x12) - jpc 0→1
      // After jfetch, jpc=1 and RAM will output data[1]=0x34
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jfetch #= false
      sleep(1)
      dut.clockDomain.waitSampling()
      sleep(1)
      // Low byte now has 0x34 (from address 1), high byte still 0
      val opdAfterFetch = dut.io.opd.toInt
      assert((opdAfterFetch & 0xFF) == 0x34, f"Low byte should be 0x34 (addr 1), got 0x${opdAfterFetch & 0xFF}%02x")

      // Fetch first operand byte with jopdfetch - jpc 1→2
      // This should shift 0x34 to high byte and load 0x56 (from addr 2) to low byte
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jopdfetch #= false
      sleep(1)
      dut.clockDomain.waitSampling()
      sleep(1)
      val opdAfterFirst = dut.io.opd.toInt
      assert(opdAfterFirst == 0x3456, f"opd should be 0x3456 after first jopdfetch, got 0x$opdAfterFirst%04x")

      // Fetch second operand byte with jopdfetch - jpc 2→3
      // This should shift 0x56 to high byte and load 0x78 (from addr 3) to low byte
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jopdfetch #= false
      sleep(1)
      dut.clockDomain.waitSampling()
      sleep(1)
      val opdAfterSecond = dut.io.opd.toInt
      assert(opdAfterSecond == 0x5678, f"opd should be 0x5678 after second jopdfetch, got 0x$opdAfterSecond%04x")
    }
  }

  test("BytecodeFetchStage: operand low byte always updates from RAM") {
    // Test that low byte gets updated every cycle, even without jopdfetch
    val jbcData = Seq(0xAA, 0xBB, 0xCC, 0xDD)

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

      // Wait for RAM to output first byte
      dut.clockDomain.waitSampling()
      sleep(1)

      // Low byte should be 0xAA (without any jfetch/jopdfetch)
      val lowByte = dut.io.opd.toInt & 0xFF
      assert(lowByte == 0xAA, f"Low byte should be 0xAA from RAM, got 0x$lowByte%02x")
    }
  }
}
