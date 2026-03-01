package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.JumpTableData

class JumpTableTest extends AnyFunSuite {

  test("JumpTable: known bytecode mappings") {
    SimConfig.withWave.compile(JumpTable()).doSim { dut =>

      dut.io.intPend #= false
      dut.io.excPend #= false

      // Test known bytecode → microcode address mappings
      // (addresses from generated JumpTableData.scala)
      val testCases = Seq(
        (0x00, 0x20C),  // nop
        (0x01, 0x20E),  // aconst_null
        (0x02, 0x20D),  // iconst_m1
        (0x03, 0x20E),  // iconst_0
        (0x60, 0x260),  // iadd
        (0x64, 0x261),  // isub
        (0x68, 0x26C),  // imul
        (0xA7, 0x28A)   // goto
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

      dut.io.intPend #= false
      dut.io.excPend #= false

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

      dut.io.intPend #= false
      dut.io.excPend #= false

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
    assert(JumpTable.SpecialAddr.SYS_NOIM == 0x0B1, "sys_noim address")
    assert(JumpTable.SpecialAddr.SYS_INT == 0x09F, "sys_int address")
    assert(JumpTable.SpecialAddr.SYS_EXC == 0x0A7, "sys_exc address")
  }

  test("JumpTable: getAddress helper function") {
    // Test the helper function (addresses from generated JumpTableData.scala)
    assert(JumpTable.getAddress(0x00) == 0x20C, "nop address")
    assert(JumpTable.getAddress(0x60) == 0x260, "iadd address")
    assert(JumpTable.getAddress(0xA7) == 0x28A, "goto address")
  }
}
