package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.JumpTableData

class JumpTableTest extends AnyFunSuite {

  test("JumpTable: known bytecode mappings") {
    SimConfig.withWave.compile(JumpTable()).doSim { dut =>

      // Test known bytecode → microcode address mappings
      // (addresses from generated JumpTableData.scala)
      val testCases = Seq(
        (0x00, 0x218),  // nop
        (0x01, 0x21A),  // aconst_null
        (0x02, 0x219),  // iconst_m1
        (0x03, 0x21A),  // iconst_0
        (0x60, 0x26C),  // iadd
        (0x64, 0x26D),  // isub
        (0x68, 0x278),  // imul
        (0xA7, 0x296)   // goto
      )

      testCases.foreach { case (bytecode, expectedAddr) =>
        dut.io.bytecode #= bytecode
        sleep(1)
        val actualAddr = dut.io.jpaddr.toInt
        assert(
          actualAddr == expectedAddr,
          f"Bytecode 0x$bytecode%02x: expected 0x$expectedAddr%03x, got 0x$actualAddr%03x"
        )
      }
    }
  }

  test("JumpTable: all 256 entries present") {
    SimConfig.withWave.compile(JumpTable()).doSim { dut =>

      // Verify all 256 bytecodes produce valid addresses
      for (bytecode <- 0 until 256) {
        dut.io.bytecode #= bytecode
        sleep(1)

        val actualAddr = dut.io.jpaddr.toInt
        val expectedAddr = JumpTableData.entries(bytecode).toInt

        assert(
          actualAddr == expectedAddr,
          f"Bytecode 0x$bytecode%02x: expected 0x$expectedAddr%03x, got 0x$actualAddr%03x"
        )
      }
    }
  }

  test("JumpTable: unmapped bytecodes route to sys_noim") {
    SimConfig.withWave.compile(JumpTable()).doSim { dut =>

      // Test some known unmapped bytecodes
      val unmappedBytecodes = Seq(
        0x0C,  // fconst_1 (floating point, not implemented)
        0x53,  // aastore (array store, not implemented)
        0x62   // fadd (floating point, not implemented)
      )

      val sysNoimAddr = JumpTableData.sysNoimAddr

      unmappedBytecodes.foreach { bytecode =>
        dut.io.bytecode #= bytecode
        sleep(1)
        val actualAddr = dut.io.jpaddr.toInt
        assert(
          actualAddr == sysNoimAddr,
          f"Unmapped bytecode 0x$bytecode%02x should route to sys_noim (0x$sysNoimAddr%03x), got 0x$actualAddr%03x"
        )
      }
    }
  }

  test("JumpTable: special addresses match JumpTableData") {
    // Verify special addresses are correct
    assert(JumpTable.SpecialAddr.SYS_NOIM == 0x0EC, "sys_noim address")
    assert(JumpTable.SpecialAddr.SYS_INT == 0x0DA, "sys_int address")
    assert(JumpTable.SpecialAddr.SYS_EXC == 0x0E2, "sys_exc address")
  }

  test("JumpTable: getAddress helper function") {
    // Test the helper function (addresses from generated JumpTableData.scala)
    assert(JumpTable.getAddress(0x00) == 0x218, "nop address")
    assert(JumpTable.getAddress(0x60) == 0x26C, "iadd address")
    assert(JumpTable.getAddress(0xA7) == 0x296, "goto address")
  }
}
