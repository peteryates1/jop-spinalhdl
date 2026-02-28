package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import io.circe._
import io.circe.parser._
import scala.io.Source
import java.nio.file.{Path, Paths}

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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false

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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()

      // Wait one more cycle for synchronous RAM read
      dut.clockDomain.waitSampling()
      sleep(1)

      // Read NOP at address 0
      val nopAddr = dut.io.jpaddr.toInt
      assert(nopAddr == 0x20C, f"NOP should map to 0x20C, got 0x$nopAddr%03x")

      // Increment to address 1 (iadd)
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()  // jpc increments 0→1, RAM latches addr 1
      dut.io.jfetch #= false  // Stop incrementing
      sleep(1)
      dut.clockDomain.waitSampling()  // RAM outputs data from addr 1
      sleep(1)

      val iaddAddr = dut.io.jpaddr.toInt
      assert(iaddAddr == 0x260, f"IADD should map to 0x260, got 0x$iaddAddr%03x")

      // Increment to address 2 (goto)
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()  // jpc increments 1→2, RAM latches addr 2
      dut.io.jfetch #= false  // Stop incrementing
      sleep(1)
      dut.clockDomain.waitSampling()  // RAM outputs data from addr 2
      sleep(1)

      val gotoAddr = dut.io.jpaddr.toInt
      assert(gotoAddr == 0x28A, f"GOTO should map to 0x28A, got 0x$gotoAddr%03x")
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
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

  test("BytecodeFetchStage: goto branch (unconditional)") {
    // Test unconditional branch (goto, tp=7)
    // Bytecode: 0xA7 (goto) with low bits = 0x7
    val jbcData = Seq(
      0xA7,  // goto instruction at address 0 (tp = 7)
      0x00,  // high byte of offset
      0x05,  // low byte of offset (+5)
      0x99,  // target at address 6 (0+1+5=6)
      0x99,
      0x99,
      0xAA   // Should jump here
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(1)

      // Fetch goto instruction at address 0
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jfetch #= false
      sleep(1)
      // jpc_br = 0, jinstr = 0xA7, jpc = 1

      // Fetch offset bytes with jopdfetch
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()  // Fetch high byte (0x00)
      dut.clockDomain.waitSampling()  // Fetch low byte (0x05)
      dut.io.jopdfetch #= false
      sleep(1)
      // jopd = 0x0005

      // Assert branch (goto is unconditional)
      dut.io.jbr #= true
      dut.clockDomain.waitSampling()
      sleep(1)
      // jmp should be true, jmp_addr = jpc_br + offset = 0 + 5 = 5
      // But wait, target should be jpc_br(0) + 1 (next instruction) + offset(5) = 6
      // Actually, in VHDL: jmp_addr = jpc_br + (jopd_high & jbc_q)
      // After jopdfetch twice, jopd has the full offset

      // After branch, jpc should be at target address
      val jpc_after_branch = dut.io.jpc_out.toInt
      // Expected: jpc_br(0) + offset(5) = 5, but need to account for instruction fetch
      // The branch target calculation uses jpc_br (start of instruction = 0)
      // Target = jpc_br + sign_extend(offset) = 0 + 5 = 5
      assert(jpc_after_branch == 5, f"JPC should be 5 after goto, got $jpc_after_branch")
    }
  }

  test("BytecodeFetchStage: ifeq branch taken") {
    // Test conditional branch (ifeq, tp=9) when zf=1
    val jbcData = Seq(
      0x99,  // ifeq instruction at address 0 (opcode 0x99, tp = 9)
      0x00,  // high byte of offset
      0x03,  // low byte of offset (+3)
      0x11,
      0x22,
      0x33   // Should jump here (address 0 + 3 = 3)
    )

    SimConfig.withWave.compile(createDut(jbcData)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Reset — same pattern as if_icmplt test (which passes reliably)
      dut.clockDomain.assertReset()
      dut.io.jpc_wr #= false
      dut.io.din #= 0
      dut.io.jfetch #= false
      dut.io.jopdfetch #= false
      dut.io.jbr #= false
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(1)

      // Fetch ifeq instruction
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jfetch #= false
      sleep(1)

      // Fetch offset bytes
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.jopdfetch #= false
      sleep(1)

      // Branch with zf=1 (condition true for ifeq)
      dut.io.zf #= true
      dut.io.jbr #= true
      dut.clockDomain.waitSampling()
      sleep(1)

      val jpc_after = dut.io.jpc_out.toInt
      assert(jpc_after == 3, f"JPC should be 3 after ifeq with zf=1, got $jpc_after")
    }
  }

  test("BytecodeFetchStage: ifeq branch not taken") {
    // Test conditional branch (ifeq, tp=9) when zf=0
    val jbcData = Seq(
      0x99,  // ifeq instruction at address 0
      0x00,  // offset high
      0x03,  // offset low
      0x11,  // Should NOT jump, continue here
      0x22
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(1)

      val jpc_before = dut.io.jpc_out.toInt

      // Fetch ifeq instruction
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jfetch #= false
      sleep(1)

      // Fetch offset bytes
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.jopdfetch #= false
      sleep(1)

      val jpc_after_operands = dut.io.jpc_out.toInt

      // Assert branch with zf=0 (condition false - should NOT branch)
      dut.io.zf #= false
      dut.io.jbr #= true
      dut.clockDomain.waitSampling()
      sleep(1)

      val jpc_after = dut.io.jpc_out.toInt
      // Should NOT have branched, jpc should remain at current position
      assert(jpc_after == jpc_after_operands, f"JPC should not change when ifeq with zf=0, was $jpc_after_operands, got $jpc_after")
    }
  }

  test("BytecodeFetchStage: if_icmplt branch types") {
    // Test if_icmplt (tp=1) - branch if lt=1
    val jbcData = Seq(
      0xA1,  // if_icmplt at address 0 (opcode 0xA1, tp = 1)
      0x00,
      0x04,
      0x11,
      0x22   // Target at address 4
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
      dut.io.stall #= false
      dut.io.zf #= false
      dut.io.nf #= false
      dut.io.eq #= false
      dut.io.lt #= false
      dut.io.irq #= false
      dut.io.exc #= false
      dut.io.ena #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(1)

      // Fetch instruction
      dut.io.jfetch #= true
      dut.clockDomain.waitSampling()
      dut.io.jfetch #= false
      sleep(1)

      // Fetch offset
      dut.io.jopdfetch #= true
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.jopdfetch #= false
      sleep(1)

      // Branch with lt=1 (should take branch)
      dut.io.lt #= true
      dut.io.jbr #= true
      dut.clockDomain.waitSampling()
      sleep(1)

      val jpc_after = dut.io.jpc_out.toInt
      assert(jpc_after == 4, f"JPC should be 4 after if_icmplt with lt=1, got $jpc_after")
    }
  }

  // ==========================================================================
  // JSON-driven tests from bcfetch.json (v2.0 sequence format)
  // ==========================================================================

  // --- Data model for v2.0 test vector format ---

  case class BcFetchSequenceAction(
    action: String,
    cycles: Option[Int],
    signals: Option[Map[String, String]],
    address: Option[Json],       // write_jbc address (can be int or string)
    data: Option[String],        // write_jbc data
    comment: Option[String]
  )

  case class BcFetchTestCase(
    name: String,
    testType: String,
    description: Option[String],
    tags: Seq[String],
    sequence: Seq[BcFetchSequenceAction]
  )

  case class BcFetchTestVectors(
    module: String,
    version: String,
    description: Option[String],
    testCases: Seq[BcFetchTestCase]
  )

  // --- Circe decoders ---

  implicit val actionDecoder: Decoder[BcFetchSequenceAction] = (c: HCursor) => for {
    action  <- c.get[String]("action")
    cycles  <- c.get[Option[Int]]("cycles")
    signals <- c.get[Option[Map[String, String]]]("signals")
    address <- c.get[Option[Json]]("address")
    data    <- c.get[Option[String]]("data")
    comment <- c.get[Option[String]]("comment")
  } yield BcFetchSequenceAction(action, cycles, signals, address, data, comment)

  implicit val testCaseDecoder: Decoder[BcFetchTestCase] = (c: HCursor) => for {
    name     <- c.get[String]("name")
    testType <- c.get[String]("type")
    desc     <- c.get[Option[String]]("description")
    tags     <- c.getOrElse[Seq[String]]("tags")(Seq.empty)
    sequence <- c.get[Seq[BcFetchSequenceAction]]("sequence")
  } yield BcFetchTestCase(name, testType, desc, tags, sequence)

  implicit val testVectorsDecoder: Decoder[BcFetchTestVectors] = (c: HCursor) => for {
    module   <- c.get[String]("module")
    version  <- c.get[String]("version")
    desc     <- c.get[Option[String]]("description")
    cases    <- c.get[Seq[BcFetchTestCase]]("test_cases")
  } yield BcFetchTestVectors(module, version, desc, cases)

  // --- Project root finder ---

  private def findProjectRoot(): Path = {
    var dir = Paths.get(System.getProperty("user.dir"))
    while (dir != null) {
      if (dir.resolve("build.sbt").toFile.exists()) return dir
      dir = dir.getParent
    }
    throw new RuntimeException("Could not find project root (no build.sbt found)")
  }

  // --- Loader ---

  private def loadTestVectors(): BcFetchTestVectors = {
    val root = findProjectRoot()
    val jsonPath = root.resolve("verification/test-vectors/modules/bcfetch.json")
    val jsonStr = Source.fromFile(jsonPath.toFile).mkString
    decode[BcFetchTestVectors](jsonStr) match {
      case Right(tv) => tv
      case Left(err) => throw new RuntimeException(s"Failed to parse bcfetch.json: $err")
    }
  }

  // --- Value parsing helpers ---

  private def parseValue(s: String): Long = {
    val trimmed = s.trim
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
      java.lang.Long.parseLong(trimmed.substring(2), 16)
    else
      trimmed.toLong
  }

  private def parseAddressJson(j: Json): Long = {
    j.asNumber.flatMap(_.toLong) match {
      case Some(v) => v
      case None =>
        j.asString match {
          case Some(s) => parseValue(s)
          case None => throw new RuntimeException(s"Cannot parse address: $j")
        }
    }
  }

  // Debug-only signals to skip during check actions (not on external IO)
  private val skipCheckSignals = Set("dbg_int_pend", "dbg_exc_pend", "dbg_jmp", "dbg_jinstr")

  // --- Sequence executor ---

  private def runJsonTestCase(tc: BcFetchTestCase): Unit = {
    SimConfig.compile(createDut(Seq.fill(2048)(0x00))).doSim(s"bcfetch_json_${tc.name}") { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Helper to initialize all inputs to defaults
      def initInputs(): Unit = {
        dut.io.jfetch #= false
        dut.io.jopdfetch #= false
        dut.io.jbr #= false
        dut.io.jpc_wr #= false
        dut.io.din #= 0
        dut.io.zf #= false
        dut.io.nf #= false
        dut.io.eq #= false
        dut.io.lt #= false
        dut.io.irq #= false
        dut.io.exc #= false
        dut.io.ena #= false
        dut.io.stall #= false
        dut.io.jbcWrAddr #= 0
        dut.io.jbcWrData #= 0
        dut.io.jbcWrEn #= false
      }

      // Initialize before first action
      initInputs()

      // Helper to drive a single input signal by JSON name
      def setSignal(name: String, value: Long): Unit = name match {
        case "jfetch"     => dut.io.jfetch #= (value != 0)
        case "jopdfetch"  => dut.io.jopdfetch #= (value != 0)
        case "jbr"        => dut.io.jbr #= (value != 0)
        case "jpc_wr"     => dut.io.jpc_wr #= (value != 0)
        case "din"        => dut.io.din #= value
        case "zf"         => dut.io.zf #= (value != 0)
        case "nf"         => dut.io.nf #= (value != 0)
        case "eq"         => dut.io.eq #= (value != 0)
        case "lt"         => dut.io.lt #= (value != 0)
        case "irq" | "irq_in_irq" => dut.io.irq #= (value != 0)
        case "exc" | "irq_in_exc" => dut.io.exc #= (value != 0)
        case "ena" | "irq_in_ena" => dut.io.ena #= (value != 0)
        case "stall"      => dut.io.stall #= (value != 0)
        case other        => // Unknown input signal, ignore
      }

      // Helper to read an output signal by JSON name. Returns None for skip signals.
      def readSignal(name: String): Option[Long] = name match {
        case "jpc_out"    => Some(dut.io.jpc_out.toLong)
        case "opd"        => Some(dut.io.opd.toLong)
        case "jpaddr"     => Some(dut.io.jpaddr.toLong)
        case "ack_irq"    => Some(if (dut.io.ack_irq.toBoolean) 1L else 0L)
        case "ack_exc"    => Some(if (dut.io.ack_exc.toBoolean) 1L else 0L)
        case s if skipCheckSignals.contains(s) => None
        case other        => None  // Unknown signal, skip
      }

      // Collect all check failures for this test case
      val failures = scala.collection.mutable.ArrayBuffer[String]()
      var stepIdx = 0

      for (step <- tc.sequence) {
        step.action match {

          case "reset" =>
            val cycles = step.cycles.getOrElse(2)
            dut.clockDomain.assertReset()
            initInputs()
            for (_ <- 0 until cycles) dut.clockDomain.waitRisingEdge()
            dut.clockDomain.deassertReset()
            dut.clockDomain.waitRisingEdge()
            initInputs()

          case "set" =>
            step.signals.foreach { signals =>
              for ((name, valueStr) <- signals) {
                setSignal(name, parseValue(valueStr))
              }
            }

          case "clock" =>
            val cycles = step.cycles.getOrElse(1)
            dut.clockDomain.waitRisingEdge(cycles)

          case "settle" =>
            sleep(1)

          case "write_jbc" =>
            val addr = step.address.map(parseAddressJson).getOrElse(
              throw new RuntimeException(s"write_jbc action missing address in test ${tc.name} step $stepIdx")
            )
            val data = step.data.map(parseValue).getOrElse(
              throw new RuntimeException(s"write_jbc action missing data in test ${tc.name} step $stepIdx")
            )
            dut.io.jbcWrAddr #= addr
            dut.io.jbcWrData #= data
            dut.io.jbcWrEn #= true
            dut.clockDomain.waitRisingEdge()
            dut.io.jbcWrEn #= false

          case "check" =>
            sleep(1)  // Allow combinational propagation after clock edges
            step.signals.foreach { signals =>
              for ((name, expectedStr) <- signals) {
                readSignal(name) match {
                  case Some(actual) =>
                    val expected = parseValue(expectedStr)
                    if (actual != expected) {
                      failures += f"[${tc.name}] step $stepIdx: signal '$name' expected 0x${expected}%X, got 0x${actual}%X"
                    }
                  case None =>
                    // Skip (debug-only or unknown signal)
                }
              }
            }

          case other =>
            // Unknown action, ignore
        }
        stepIdx += 1
      }

      // Assert all collected failures
      if (failures.nonEmpty) {
        fail(failures.mkString("\n"))
      }
    }
  }

  // --- Load test vectors and generate per-vector tests ---

  private lazy val bcfetchTestVectors: BcFetchTestVectors = loadTestVectors()

  bcfetchTestVectors.testCases.foreach { tc =>
    test(s"bcfetch_json_${tc.name}") {
      runJsonTestCase(tc)
    }
  }

  // --- Verify expected test count ---

  test("bcfetch_json_count_verification") {
    assert(
      bcfetchTestVectors.testCases.size == 31,
      s"Expected 31 JSON test cases, got ${bcfetchTestVectors.testCases.size}"
    )
  }
}
