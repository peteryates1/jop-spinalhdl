package jop.test

import spinal.core._
import spinal.core.sim._
import jop.JopSimulator
import jop.utils.JopFileLoader
import jop.MicrocodeNames

/**
 * Focused Memory Operation Test
 *
 * Tests fundamental memory read/write operations in the JOP simulator:
 * 1. Stack RAM writes (wrEna, wrAddr, din)
 * 2. Stack RAM reads (rdAddr, dout)
 * 3. stm/ldm instructions (scratch memory)
 * 4. SP register updates
 */
object MemoryOpTest extends App {

  val jopFilePath = "/home/peter/git/jopmin/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  println("=== Memory Operation Test ===")
  println()

  SimConfig
    .withWave
    .workspacePath("simWorkspace")
    .compile(JopSimulator(
      jopFilePath = jopFilePath,
      romFilePath = romFilePath,
      ramFilePath = Some(ramFilePath)
    ))
    .doSim { dut =>
      // Fork clock
      dut.io.clk #= false
      fork {
        while (true) {
          dut.io.clk #= !dut.io.clk.toBoolean
          sleep(10)
        }
      }

      sleep(1)

      // Initialize
      dut.io.reset #= true
      dut.io.uartRxData #= 0
      dut.io.uartRxReady #= false
      dut.io.debugRamAddr #= 0
      dut.io.debugRamWrAddr #= 0
      dut.io.debugRamWrData #= 0
      dut.io.debugRamWrEn #= false

      // Reset cycle
      sleep(60)
      dut.io.reset #= false
      sleep(20)

      println("Reset complete, tracing memory operations...")
      println()
      println("Legend:")
      println("  WR[addr]=data   : Stack RAM write")
      println("  RD[addr]->data  : Stack RAM read")
      println("  SP=value        : Stack pointer")
      println("  TOS=value       : Top of stack (A register)")
      println()

      var cycleCount = 0
      val maxCycles = 150  // Just first 150 cycles for detailed trace

      // Track previous values to detect changes
      var prevSp = -1
      var prevWrEn = false

      // RAM state tracking (simulate what should be in RAM)
      val ramState = scala.collection.mutable.Map[Int, Long]()

      // Initialize RAM state from init file
      val ramInitData = JopFileLoader.loadStackRam(ramFilePath)
      for (i <- ramInitData.indices) {
        ramState(i) = (ramInitData(i) & 0xFFFFFFFFL).toLong
      }
      println(s"Initial RAM state loaded (${ramInitData.length} entries)")
      println(f"  RAM[32] = ${ramState(32)}%08x (STACK_OFF)")
      println(f"  RAM[38] = ${ramState(38)}%08x (constant 2)")
      println(f"  RAM[45] = ${ramState.getOrElse(45, 0L)}%08x (constant -1)")
      println()

      while (cycleCount < maxCycles) {
        waitUntil(dut.io.clk.toBoolean)
        cycleCount += 1

        val pc = dut.io.pc.toInt
        val instr = dut.io.instr.toInt
        val sp = dut.io.debugSp.toInt
        val tos = dut.io.aout.toLong
        val nos = dut.io.bout.toLong
        val wrAddr = dut.io.debugStackWrAddr.toInt
        val wrEn = dut.io.debugStackWrEn.toBoolean
        val rdAddrReg = dut.io.debugRdAddrReg.toInt
        val ramDout = dut.io.debugRamDout.toLong
        val dirAddr = dut.io.dirAddr.toInt

        // Build cycle info string
        val instrInfo = MicrocodeNames.disasm(instr)
        var events = scala.collection.mutable.ArrayBuffer[String]()

        // Detect SP change
        if (sp != prevSp && prevSp >= 0) {
          events += f"SP: $prevSp -> $sp"
        }
        prevSp = sp

        // Detect write
        if (wrEn) {
          // Write data comes from mmux (A or B depending on selMmux)
          // For now, assume it's TOS
          events += f"WR[$wrAddr%3d]=$tos%08x"
          ramState(wrAddr) = tos
        }

        // Show read address and data
        val readVal = ramDout
        events += f"RD[$rdAddrReg%3d]->$readVal%08x"

        // Check for stm/ldm instructions
        val ir95 = (instr >> 5) & 0x1F
        val ir40 = instr & 0x1F
        val isStm = ir95 == 1  // stm: ir(9:5) = 00001
        val isLdm = ir95 == 5  // ldm: ir(9:5) = 00101

        if (isStm) {
          events += f"STM[$ir40%d] (dir=$dirAddr)"
        }
        if (isLdm) {
          events += f"LDM[$ir40%d] (dir=$dirAddr)"
        }

        // Build output line
        val eventsStr = events.mkString(" | ")
        println(f"[$cycleCount%4d] PC=$pc%04x I=$instr%03x $instrInfo%-12s SP=$sp%3d TOS=$tos%08x NOS=$nos%08x | $eventsStr")

        waitUntil(!dut.io.clk.toBoolean)
      }

      println()
      println("=== RAM State After Simulation ===")
      println("Scratch memory (addresses 0-10):")
      for (i <- 0 to 10) {
        val val_ = ramState.getOrElse(i, 0L)
        val varName = i match {
          case 0 => "mp"
          case 1 => "cp"
          case 2 => "heap"
          case 3 => "jjp"
          case 4 => "jjhp"
          case 5 => "lockcnt"
          case 6 => "a"
          case 7 => "b"
          case 8 => "c"
          case _ => ""
        }
        println(f"  RAM[$i%3d] = $val_%08x  ($varName)")
      }
      println()
      println("Constants area (addresses 32-45):")
      for (i <- 32 to 45) {
        val val_ = ramState.getOrElse(i, 0L)
        println(f"  RAM[$i%3d] = $val_%08x")
      }
      println()
      println("Stack area (addresses 64-70):")
      for (i <- 64 to 70) {
        val val_ = ramState.getOrElse(i, 0L)
        println(f"  RAM[$i%3d] = $val_%08x")
      }
    }
}
