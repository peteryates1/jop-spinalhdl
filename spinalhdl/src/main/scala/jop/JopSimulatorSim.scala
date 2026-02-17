package jop

import spinal.core._
import spinal.core.sim._
import jop.utils.JopFileLoader

/**
 * SpinalSim-based JOP Simulator
 *
 * Runs HelloWorld.jop with execution logging using SpinalHDL's built-in simulator.
 * This is simpler for debugging than CocoTB/GHDL.
 */
object JopSimulatorSim extends App {

  // Configuration
  val jopFilePath = "/home/peter/git/jop/java/Smallest/HelloWorld.jop"
  val romFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_rom.dat"
  val ramFilePath = "/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat"

  // Load jump table for bytecode decoding
  val jumpTable = JopFileLoader.loadJumpTable("/home/peter/workspaces/ai/jop/asm/generated/jtbl.vhd")
  val bytecodeNames = Map(
    0x00 -> "nop", 0x01 -> "aconst_null", 0x02 -> "iconst_m1",
    0x03 -> "iconst_0", 0x04 -> "iconst_1", 0x05 -> "iconst_2",
    0x06 -> "iconst_3", 0x07 -> "iconst_4", 0x08 -> "iconst_5",
    0x10 -> "bipush", 0x11 -> "sipush",
    0x1A -> "iload_0", 0x1B -> "iload_1", 0x1C -> "iload_2", 0x1D -> "iload_3",
    0x3B -> "istore_0", 0x3C -> "istore_1", 0x3D -> "istore_2", 0x3E -> "istore_3",
    0x57 -> "pop", 0x59 -> "dup",
    0x60 -> "iadd", 0x64 -> "isub", 0x68 -> "imul",
    0x7E -> "iand", 0x80 -> "ior", 0x82 -> "ixor",
    0x84 -> "iinc",
    0x99 -> "ifeq", 0x9A -> "ifne", 0x9B -> "iflt", 0x9C -> "ifge", 0x9D -> "ifgt", 0x9E -> "ifle",
    0x9F -> "if_icmpeq", 0xA0 -> "if_icmpne", 0xA1 -> "if_icmplt",
    0xA7 -> "goto",
    0xAC -> "ireturn", 0xB1 -> "return",
    0xB6 -> "invokevirtual", 0xB7 -> "invokespecial", 0xB8 -> "invokestatic"
  )

  val microcodeNames = Map(
    0xF0 -> "bc_exit", 0xF1 -> "bc_enter", 0xF2 -> "bc_ret",
    0xF3 -> "bc_stmjpc", 0xF4 -> "bc_stbcrd",
    0xF5 -> "bc_syscall", 0xF6 -> "bc_breakpoint"
  )

  // Reverse lookup: jpaddr -> bytecode name
  val jpaddrToName = jumpTable.map { case (bc, addr) =>
    addr -> bytecodeNames.getOrElse(bc, f"bc_0x$bc%02x")
  }

  println("=== JOP Simulator (SpinalSim) ===")
  println(s"JOP file: $jopFilePath")
  println(s"ROM file: $romFilePath")
  println()

  // Compile and simulate
  SimConfig
    .withWave  // Generate VCD waveform
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
          sleep(10)  // 20ns period = 50MHz
        }
      }

      // Skip early debug reads to avoid interference
      sleep(1)

      // Initialize inputs
      dut.io.reset #= true
      dut.io.uartRxData #= 0
      dut.io.uartRxReady #= false
      dut.io.debugRamAddr #= 0
      dut.io.debugRamWrAddr #= 0
      dut.io.debugRamWrData #= 0
      dut.io.debugRamWrEn #= false

      // Load RAM data for explicit initialization
      val ramInitData = JopFileLoader.loadStackRam("/home/peter/workspaces/ai/jop/asm/generated/mem_ram.dat")
      println(s"Loaded ${ramInitData.length} RAM init values")
      println(f"  RAM[32] = ${ramInitData(32)}%08x")
      println(f"  RAM[38] = ${ramInitData(38)}%08x")
      println(f"  RAM[45] = ${ramInitData(45)}%08x (${ramInitData(45)})")

      // Reset cycle
      sleep(60)
      dut.io.reset #= false
      sleep(20)

      // Skip debug reads during this test
      dut.io.debugRamAddr #= 0  // Set to neutral address
      println("Reset complete, starting simulation...")

      // Verify JOP data was parsed correctly
      println("JOP data verification (from loader):")
      val jopVerify = JopFileLoader.loadJopFile(jopFilePath)
      for (addr <- Seq(0x613, 0x614, 0x41b, 0x41c, 0x48c, 0x48d, 0x48e)) {
        if (addr < jopVerify.words.length) {
          val data = jopVerify.words(addr)
          println(f"  JOP[$addr%05d] = 0x$data%08x")
        }
      }

      // Simulation state
      var cycleCount = 0
      var bytecodeCount = 0
      var lastJpc = -1
      var uartBuffer = new StringBuilder
      var wdToggles = 0

      // Run simulation
      val maxCycles = 500000  // Run longer to see UART output
      while (cycleCount < maxCycles) {
        // Wait for rising edge
        waitUntil(dut.io.clk.toBoolean)

        cycleCount += 1

        // Check for jfetch (bytecode dispatch)
        if (dut.io.jfetch.toBoolean) {
          bytecodeCount += 1
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val jpaddr = dut.io.jpaddr.toInt

          // Only log if JPC changed (actual bytecode execution)
          if (jpc != lastJpc) {
            val bcName = jpaddrToName.getOrElse(jpaddr, f"0x$jpaddr%03x")
            if (bytecodeCount <= 100 || bytecodeCount % 100 == 0) {
              println(f"[$cycleCount%6d] JFETCH #$bytecodeCount%4d: JPC=$jpc%04x JPADDR=$jpaddr%04x ($bcName) PC=$pc%04x")
            }
            // Log arraylength bytecode (0xd1 at jpaddr 0x530)
            if (jpaddr == 0x530) {
              val aout = dut.io.aout.toLong
              println(f"[$cycleCount%6d] ARRAYLENGTH: arrayref=0x$aout%08x")
            }
            lastJpc = jpc
          }
        }

        // Check for UART output
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt
          val char = if (ch >= 32 && ch < 127) ch.toChar.toString else f"\\x$ch%02x"
          uartBuffer.append(if (ch < 128) ch.toChar else '?')
          println(f"[$cycleCount%6d] UART TX: '$char' (0x$ch%02x)")
        }

        // Check for WD toggle
        if (dut.io.wdToggle.toBoolean) {
          wdToggles += 1
          val wdState = dut.io.wdOut.toBoolean
          println(f"[$cycleCount%6d] WD Toggle #$wdToggles -> $wdState")
        }

        // Monitor I/O writes - trace around WD writes
        if (dut.io.ioWr.toBoolean) {
          val ioAddr = dut.io.ioAddr.toInt
          val ioData = dut.io.ioWrData.toLong
          val jpc = dut.io.jpc.toInt
          val pc = dut.io.pc.toInt
          println(f"[$cycleCount%6d] IO WRITE: Addr=0x$ioAddr%02x Data=0x$ioData%08x JPC=$jpc%04x PC=$pc%04x")
        }

        // Monitor memory writes
        if (dut.io.memWr.toBoolean) {
          val memAddr = dut.io.memAddr.toLong
          val memData = dut.io.memWrData.toLong
          println(f"[$cycleCount%6d] MEM WRITE: Addr=0x$memAddr%08x Data=0x$memData%08x")
        }

        // Monitor ALL writes in first 20 cycles to catch early corruption
        val sp = dut.io.debugSp.toInt
        val wrAddr = dut.io.debugStackWrAddr.toInt
        val wrEn = dut.io.debugStackWrEn.toBoolean
        if (wrEn && cycleCount <= 20) {
          val wrData = dut.io.aout.toLong  // Approximate write data
          println(f"[$cycleCount%6d] EARLY WRITE: WrAddr=$wrAddr SP=$sp")
        }
        if (wrEn && wrAddr >= 32 && wrAddr <= 63) {
          val pc = dut.io.pc.toInt
          val instr = dut.io.instr.toInt
          val aout = dut.io.aout.toLong
          println(f"[$cycleCount%6d] *** WRITE TO CONSTANT AREA! PC=$pc%04x I=$instr%03x WrAddr=$wrAddr SP=$sp TOS=$aout%08x ***")
        }

        // Detailed tracing for debugging
        if (cycleCount >= 1 && cycleCount <= 50 || cycleCount >= 598 && cycleCount <= 660) {
          val pc = dut.io.pc.toInt
          val aout = dut.io.aout.toLong
          val bout = dut.io.bout.toLong
          val instr = dut.io.instr.toInt
          val dirAddr = dut.io.dirAddr.toInt
          val selRda = dut.io.selRda.toInt
          val selLmux = dut.io.selLmux.toInt
          val selAmux = if (dut.io.selAmux.toBoolean) 1 else 0
          val enaA = if (dut.io.enaA.toBoolean) 1 else 0
          // Decode analysis
          val instrBits = f"${instr.toBinaryString.reverse.padTo(10, '0').reverse}"
          val ir95 = (instr >> 5) & 0x1F  // ir(9 downto 5)
          val ir40 = instr & 0x1F  // ir(4 downto 0)
          val isLdi = ir95 == 6  // 0b00110
          val expectedDir = if (isLdi) (32 + ir40) else ir40
          val wrEnStr = if (wrEn) f"WR@$wrAddr" else "----"
          val lmuxVal = selLmux
          val rdAddrReg = dut.io.debugRdAddrReg.toInt
          val ramDoutVal = dut.io.debugRamDout.toLong
          println(f"[$cycleCount%6d] PC=$pc%04x I=$instr%03x ${MicrocodeNames.disasm(instr)} isLdi=$isLdi dir=$dirAddr rdReg=$rdAddrReg lmux=$lmuxVal ramDout=$ramDoutVal%08x TOS=$aout%08x")
        }

        // Trace all stsp instructions (I=0x01b sets SP from TOS)
        val instr = dut.io.instr.toInt
        if (instr == 0x01b) {
          val pc = dut.io.pc.toInt
          val aout = dut.io.aout.toLong
          val newSp = aout & 0xFF
          println(f"[$cycleCount%6d] STSP: PC=$pc%04x TOS=$aout%08x -> SP will become $newSp%d (0x$newSp%02x)")
          if (newSp < 64) {
            println(f"[$cycleCount%6d] *** WARNING: SP underflow! New SP=$newSp is below stack base (64) ***")
          }
        }

        // Monitor memory control signals from decode
        val memAddrWr = dut.io.debugMemAddrWr.toBoolean
        val memRdReq = dut.io.debugMemRdReq.toBoolean
        val memWrReq = dut.io.debugMemWrReq.toBoolean

        if (memAddrWr && cycleCount <= 10000) {
          val tos = dut.io.aout.toLong
          val pc = dut.io.pc.toInt
          println(f"[$cycleCount%6d] STMWA (decode): PC=$pc%04x TOS=0x$tos%08x")
        }
        if (memRdReq && cycleCount <= 10000) {
          val tos = dut.io.aout.toLong
          val pc = dut.io.pc.toInt
          println(f"[$cycleCount%6d] STMRA (decode): PC=$pc%04x TOS=0x$tos%08x")
        }
        if (memWrReq && cycleCount <= 10000) {
          val tos = dut.io.aout.toLong
          val pc = dut.io.pc.toInt
          val addr = dut.io.memAddr.toLong
          println(f"[$cycleCount%6d] STMWD: PC=$pc%04x addr=0x$addr%08x data=0x$tos%08x")
        }

        // Monitor memory read - show captured data when stack receives it
        // ldmrd uses lmux=4 which feeds memRdDataReg to stack
        val selLmux = dut.io.selLmux.toInt
        if (selLmux == 4 && cycleCount <= 200) {
          val capturedData = dut.io.debugMemRdDataReg.toLong
          println(f"[$cycleCount%6d] LDMRD: stack receiving data=0x$capturedData%08x")
        }

        // Monitor bytecode cache fill (stbcrd)
        val bcRd = dut.io.debugBcRd.toBoolean
        val bcFillActive = dut.io.debugBcFillActive.toBoolean
        val enaJpc = dut.io.debugEnaJpc.toBoolean

        if (bcRd) {
          val tos = dut.io.aout.toLong
          val pc = dut.io.pc.toInt
          val bcLen = tos & 0x3FF
          val bcStart = tos >> 10
          println(f"[$cycleCount%6d] STBCRD: PC=$pc%04x TOS=0x$tos%08x start=$bcStart len=$bcLen")
        }

        if (bcFillActive && cycleCount <= 10000) {
          val fillLen = dut.io.debugBcFillLen.toInt
          val fillAddr = dut.io.debugBcFillAddr.toLong
          println(f"[$cycleCount%6d] BC FILL ACTIVE: addr=$fillAddr len=$fillLen")
        }

        // Monitor JBC writes during bytecode cache fill
        val jbcWrEn = dut.io.debugJbcWrEn.toBoolean
        if (jbcWrEn && cycleCount <= 10000) {
          val jbcAddr = dut.io.debugJbcWrAddr.toInt
          val jbcData = dut.io.debugJbcWrData.toLong
          val memData = dut.io.debugMemRdData.toLong
          val b0 = (jbcData >> 24) & 0xFF
          val b1 = (jbcData >> 16) & 0xFF
          val b2 = (jbcData >> 8) & 0xFF
          val b3 = jbcData & 0xFF
          println(f"[$cycleCount%6d] JBC WRITE: addr=$jbcAddr data=0x$jbcData%08x (bytes: $b0%02x $b1%02x $b2%02x $b3%02x) memRdData=0x$memData%08x")
        }

        if (enaJpc && cycleCount <= 10000) {
          val tos = dut.io.aout.toLong
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          println(f"[$cycleCount%6d] STJPC: PC=$pc%04x TOS=0x$tos%08x (new JPC) current JPC=$jpc%04x")
        }

        // Monitor I/O reads (especially timer reads in wait loops)
        val ioRd = dut.io.ioRd.toBoolean
        if (ioRd) {
          val ioAddr = dut.io.ioAddr.toInt
          val sysCnt = dut.io.sysCnt.toLong
          // Only log timer reads for first 2000 cycles, then every 1000
          if (cycleCount <= 2000 || cycleCount % 1000 == 0) {
            println(f"[$cycleCount%6d] IO READ: Addr=0x$ioAddr%02x SysCnt=$sysCnt%d")
          }
        }

        // Monitor getfield signal and handle operation state machine
        val getfieldActive = dut.io.debugGetfield.toBoolean
        val handleOpActive = dut.io.debugHandleOpActive.toBoolean
        val handleOpState = dut.io.debugHandleOpState.toInt
        if (getfieldActive && cycleCount <= 10000) {
          val pc = dut.io.pc.toInt
          val aout = dut.io.aout.toLong
          val bout = dut.io.bout.toLong
          println(f"[$cycleCount%6d] GETFIELD: PC=$pc%04x TOS=0x$aout%08x NOS=0x$bout%08x handleOpState=$handleOpState")
        }
        if (handleOpActive && cycleCount <= 10000) {
          val pc = dut.io.pc.toInt
          val memAddr = dut.io.memAddr.toLong
          val memRd = dut.io.memRd.toBoolean
          val memData = dut.io.debugMemRdData.toLong
          val memDataReg = dut.io.debugMemRdDataReg.toLong
          // Log every cycle while handle op is active
          println(f"[$cycleCount%6d] HANDLE_OP: state=$handleOpState memAddr=0x$memAddr%08x memRd=$memRd memData=0x$memData%08x memDataReg=0x$memDataReg%08x")
        }

        // Detailed trace around invoke sequence (cycles 140-200)
        if (cycleCount >= 140 && cycleCount <= 200) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val instr = dut.io.instr.toInt
          val jpaddr = dut.io.jpaddr.toInt
          val aout = dut.io.aout.toLong
          val bout = dut.io.bout.toLong
          println(f"[$cycleCount%6d] INVOKE: PC=$pc%04x JPC=$jpc%04x I=$instr%03x ${MicrocodeNames.disasm(instr)} TOS=$aout%08x NOS=$bout%08x")
        }

        // Periodic status - more detailed to track class initialization
        if (cycleCount % 1000 == 0 || (cycleCount >= 900 && cycleCount <= 940)) {
          val pc = dut.io.pc.toInt
          val jpc = dut.io.jpc.toInt
          val aout = dut.io.aout.toLong
          val bout = dut.io.bout.toLong
          val sp = dut.io.debugSp.toInt
          val bcFillActive = dut.io.debugBcFillActive.toBoolean
          val instr = dut.io.instr.toInt
          println(f"[$cycleCount%6d] STATUS: PC=$pc%04x I=$instr%03x JPC=$jpc%04x TOS=$aout%08x SP=$sp bcFill=$bcFillActive WD=$wdToggles")
        }

        // Check for halt
        if (dut.io.halted.toBoolean) {
          println(f"[$cycleCount%6d] HALTED")
          cycleCount = maxCycles  // Exit loop
        }

        // Wait for falling edge
        waitUntil(!dut.io.clk.toBoolean)
      }

      // Final report
      println()
      println("=== Simulation Complete ===")
      println(f"Total cycles: $cycleCount")
      println(f"Bytecodes executed: $bytecodeCount")
      println(f"UART output: '${uartBuffer.toString}'")
      println(f"WD toggles: $wdToggles")
    }
}
