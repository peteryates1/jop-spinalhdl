package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.JopFileLoader
import jop.debug.{DebugConfig, DebugMsgType, DebugHaltReason, DebugNakCode}

/**
 * Self-contained debug protocol simulation test.
 *
 * Programmatically sends debug protocol commands to a booted JOP core
 * via the byte-stream transport pins, receives responses, and verifies
 * correctness.
 *
 * Usage: sbt "Test / runMain jop.system.JopDebugProtocolSim"
 */
object JopDebugProtocolSim extends App {

  // ===========================================================================
  // Scala-side CRC-8/MAXIM (matches hardware Crc8Maxim)
  // ===========================================================================

  def crc8Maxim(data: Seq[Int]): Int = {
    var crc = 0
    for (byte <- data) {
      for (i <- 0 until 8) {
        val xorBit = (crc & 1) ^ ((byte >> i) & 1)
        crc = (crc >> 1) & 0x7F
        if (xorBit != 0) crc ^= 0x8C
      }
    }
    crc & 0xFF
  }

  // ===========================================================================
  // Frame builder
  // ===========================================================================

  def buildFrame(msgType: Int, core: Int, payload: Seq[Int] = Seq.empty): Seq[Int] = {
    val lenHi = (payload.length >> 8) & 0xFF
    val lenLo = payload.length & 0xFF
    val header = Seq(msgType, lenHi, lenLo, core)
    val crcData = header ++ payload
    val crc = crc8Maxim(crcData)
    Seq(0xA5) ++ crcData ++ Seq(crc)
  }

  // ===========================================================================
  // Load data and compile
  // ===========================================================================

  val romData = JopFileLoader.loadMicrocodeRom("asm/generated/mem_rom.dat")
  val ramData = JopFileLoader.loadStackRam("asm/generated/mem_ram.dat")
  val jopData = JopFileLoader.loadJopFile("java/apps/Smallest/HelloWorld.jop")

  println(s"Debug Protocol Sim: ROM=${romData.length}, RAM=${ramData.length}, JOP=${jopData.words.length} words")

  val simConfig = SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .allOptimisation

  simConfig.compile(JopDebugTestHarness(
    cpuCnt = 1,
    debugConfig = DebugConfig(numBreakpoints = 4),
    romInit = romData,
    ramInit = ramData,
    mainMemInit = jopData.words
  )).doSim("JopDebugProtocolSim") { dut =>

    dut.clockDomain.forkStimulus(period = 10)

    // Defaults
    dut.io.dbgRxValid #= false
    dut.io.dbgRxData #= 0
    dut.io.dbgTxReady #= true

    // =========================================================================
    // Send frame: byte-at-a-time with backpressure
    // =========================================================================

    def sendFrame(frame: Seq[Int]): Unit = {
      for (byte <- frame) {
        dut.io.dbgRxValid #= false
        dut.clockDomain.waitSampling()
        // Wait until ready
        var waitCount = 0
        while (!dut.io.dbgRxReady.toBoolean) {
          dut.clockDomain.waitSampling()
          waitCount += 1
          if (waitCount > 10000) simFailure("sendFrame: rxReady timeout")
        }
        dut.io.dbgRxData #= byte
        dut.io.dbgRxValid #= true
        dut.clockDomain.waitSampling()
      }
      dut.io.dbgRxValid #= false
    }

    // =========================================================================
    // Receive frame (with timeout)
    // =========================================================================

    /** Receive one response frame. Returns (msgType, core, payload). */
    def recvFrame(timeoutCycles: Int = 50000): (Int, Int, Seq[Int]) = {
      var remaining = timeoutCycles

      // Skip until SYNC byte
      def waitByte(): Int = {
        while (remaining > 0) {
          dut.clockDomain.waitSampling()
          remaining -= 1
          if (dut.io.dbgTxValid.toBoolean) {
            return dut.io.dbgTxData.toInt & 0xFF
          }
        }
        simFailure("recvFrame: timeout waiting for byte")
        -1
      }

      // Wait for SYNC
      var sync = waitByte()
      while (sync != 0xA5 && remaining > 0) {
        sync = waitByte()
      }
      if (sync != 0xA5) simFailure("recvFrame: no SYNC found")

      val msgType = waitByte()
      val lenHi = waitByte()
      val lenLo = waitByte()
      val core = waitByte()
      val payloadLen = (lenHi << 8) | lenLo

      val payload = (0 until payloadLen).map(_ => waitByte())
      val rxCrc = waitByte()

      // Validate CRC
      val crcData = Seq(msgType, lenHi, lenLo, core) ++ payload
      val expectedCrc = crc8Maxim(crcData)
      if (rxCrc != expectedCrc) {
        simFailure(s"recvFrame: CRC mismatch got=0x${rxCrc.toHexString} expected=0x${expectedCrc.toHexString}")
      }

      (msgType, core, payload)
    }

    /** Try to receive a frame, returning None on timeout. */
    def tryRecvFrame(timeoutCycles: Int = 5000): Option[(Int, Int, Seq[Int])] = {
      var remaining = timeoutCycles
      // Look for a SYNC byte first — if none arrives, return None
      while (remaining > 0) {
        dut.clockDomain.waitSampling()
        remaining -= 1
        if (dut.io.dbgTxValid.toBoolean) {
          val b = dut.io.dbgTxData.toInt & 0xFF
          if (b == 0xA5) {
            // Got SYNC — receive rest of frame
            def waitByte(): Int = {
              while (remaining > 0) {
                dut.clockDomain.waitSampling()
                remaining -= 1
                if (dut.io.dbgTxValid.toBoolean) {
                  return dut.io.dbgTxData.toInt & 0xFF
                }
              }
              simFailure("tryRecvFrame: timeout mid-frame")
              -1
            }
            val msgType = waitByte()
            val lenHi = waitByte()
            val lenLo = waitByte()
            val core = waitByte()
            val payloadLen = (lenHi << 8) | lenLo
            val payload = (0 until payloadLen).map(_ => waitByte())
            val rxCrc = waitByte()
            val crcData = Seq(msgType, lenHi, lenLo, core) ++ payload
            val expectedCrc = crc8Maxim(crcData)
            if (rxCrc != expectedCrc) {
              simFailure(s"tryRecvFrame: CRC mismatch got=0x${rxCrc.toHexString} expected=0x${expectedCrc.toHexString}")
            }
            return Some((msgType, core, payload))
          }
        }
      }
      None
    }

    /** Drain any pending TX frames (e.g., async HALTED notifications). */
    def drainFrames(timeoutCycles: Int = 5000): Seq[(Int, Int, Seq[Int])] = {
      val frames = scala.collection.mutable.ListBuffer[(Int, Int, Seq[Int])]()
      var more = true
      while (more) {
        tryRecvFrame(timeoutCycles) match {
          case Some(f) => frames += f
          case None => more = false
        }
      }
      frames.toSeq
    }

    // =========================================================================
    // Test infrastructure
    // =========================================================================

    var passed = 0
    var failed = 0
    var testNum = 0

    def pass(name: String): Unit = {
      testNum += 1
      println(s"  [PASS] Test $testNum: $name")
      passed += 1
    }

    def fail(name: String, detail: String): Unit = {
      testNum += 1
      println(s"  [FAIL] Test $testNum: $name — $detail")
      failed += 1
    }

    def check(name: String, cond: Boolean, detail: => String = ""): Unit = {
      if (cond) pass(name)
      else fail(name, detail)
    }

    /** Extract big-endian 32-bit word from payload at offset. */
    def wordAt(payload: Seq[Int], offset: Int): Long = {
      ((payload(offset) & 0xFF).toLong << 24) |
      ((payload(offset + 1) & 0xFF).toLong << 16) |
      ((payload(offset + 2) & 0xFF).toLong << 8) |
       (payload(offset + 3) & 0xFF).toLong
    }

    // =========================================================================
    // UART capture (fork)
    // =========================================================================

    val uartBuf = new StringBuilder
    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (dut.io.uartTxValid.toBoolean) {
          val ch = dut.io.uartTxData.toInt & 0xFF
          if (ch >= 32 && ch < 127) uartBuf.append(ch.toChar)
          else if (ch == '\n' || ch == '\r') { /* skip */ }
        }
      }
    }

    // =========================================================================
    // 1. Boot & settle
    // =========================================================================

    println("=== JOP Debug Protocol Sim ===")
    println("Booting JOP (150k cycles)...")
    dut.clockDomain.waitSampling(150000)
    println(s"  UART so far: '${uartBuf.toString.take(40)}'")
    check("Boot: UART output detected", uartBuf.nonEmpty,
      "No UART output after 150k cycles")

    // =========================================================================
    // 2. PING/PONG
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.PING, 0))
    val (pingType, pingCore, pingPayload) = recvFrame()
    check("PING → PONG response",
      pingType == DebugMsgType.PONG && pingCore == 0 && pingPayload.isEmpty,
      s"type=0x${pingType.toHexString} core=$pingCore payload=${pingPayload.length}")

    // =========================================================================
    // 3. QUERY_INFO
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.QUERY_INFO, 0))
    val (infoType, infoCore, infoPayload) = recvFrame()
    check("QUERY_INFO → TARGET_INFO",
      infoType == DebugMsgType.TARGET_INFO,
      s"type=0x${infoType.toHexString}")

    // Parse TLV: find NUM_CORES, NUM_BREAKPOINTS, PROTOCOL_VERSION
    var numCores = -1
    var numBp = -1
    var protoMajor = -1
    var protoMinor = -1
    var tlvIdx = 0
    while (tlvIdx < infoPayload.length) {
      val tag = infoPayload(tlvIdx)
      val len = infoPayload(tlvIdx + 1)
      tag match {
        case 0x01 => numCores = infoPayload(tlvIdx + 2)
        case 0x02 => numBp = infoPayload(tlvIdx + 2)
        case 0x08 =>
          protoMajor = infoPayload(tlvIdx + 2)
          protoMinor = infoPayload(tlvIdx + 3)
        case _ => // ignore
      }
      tlvIdx += 2 + len
    }
    check("TARGET_INFO: NUM_CORES=1", numCores == 1, s"got $numCores")
    check("TARGET_INFO: NUM_BREAKPOINTS=4", numBp == 4, s"got $numBp")
    check("TARGET_INFO: PROTOCOL_VERSION=1.0",
      protoMajor == 1 && protoMinor == 0,
      s"got $protoMajor.$protoMinor")

    // =========================================================================
    // 4. HALT core 0
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.HALT, 0))
    val (haltAckType, _, _) = recvFrame()
    check("HALT → ACK", haltAckType == DebugMsgType.ACK,
      s"type=0x${haltAckType.toHexString}")

    // Expect HALTED notification (may come before or after ACK)
    val (haltedType, haltedCore, haltedPayload) = recvFrame()
    check("HALT → HALTED notification",
      haltedType == DebugMsgType.HALTED && haltedCore == 0,
      s"type=0x${haltedType.toHexString} core=$haltedCore")
    check("HALTED reason=MANUAL",
      haltedPayload.nonEmpty && haltedPayload(0) == DebugHaltReason.MANUAL,
      s"reason=${if (haltedPayload.nonEmpty) haltedPayload(0) else -1}")

    // Wait a bit for halted signal to propagate
    dut.clockDomain.waitSampling(10)
    check("dut.io.halted(0) == true", dut.io.halted(0).toBoolean,
      "halted signal not asserted")

    // =========================================================================
    // 5. QUERY_STATUS (while halted)
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.QUERY_STATUS, 0))
    val (statusType, statusCore, statusPayload) = recvFrame()
    check("QUERY_STATUS → STATUS",
      statusType == DebugMsgType.STATUS,
      s"type=0x${statusType.toHexString}")
    check("STATUS: halted=1",
      statusPayload.length >= 2 && statusPayload(0) == 0x01,
      s"halted=${if (statusPayload.nonEmpty) statusPayload(0) else -1}")
    check("STATUS: reason=MANUAL",
      statusPayload.length >= 2 && statusPayload(1) == DebugHaltReason.MANUAL,
      s"reason=${if (statusPayload.length >= 2) statusPayload(1) else -1}")

    // =========================================================================
    // 6. READ_REGISTERS
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.READ_REGISTERS, 0))
    val (regType, _, regPayload) = recvFrame()
    check("READ_REGISTERS → REGISTERS",
      regType == DebugMsgType.REGISTERS,
      s"type=0x${regType.toHexString}")
    check("REGISTERS: 60 bytes payload",
      regPayload.length == 60,
      s"got ${regPayload.length} bytes")

    // Verify PC matches hardware
    if (regPayload.length >= 4) {
      val regPc = wordAt(regPayload, 0)
      val hwPc = dut.io.pc(0).toInt.toLong
      check("REGISTERS: PC matches hardware",
        regPc == hwPc,
        s"reg=$regPc hw=$hwPc")
    }

    // Verify JPC matches hardware
    if (regPayload.length >= 8) {
      val regJpc = wordAt(regPayload, 4)
      val hwJpc = dut.io.jpc(0).toInt.toLong
      check("REGISTERS: JPC matches hardware",
        regJpc == hwJpc,
        s"reg=$regJpc hw=$hwJpc")
    }

    // =========================================================================
    // 7. READ_STACK
    // =========================================================================

    // Payload: offset=0 (2B BE) + count=4 (2B BE)
    val stackPayload = Seq(0, 0, 0, 4)
    sendFrame(buildFrame(DebugMsgType.READ_STACK, 0, stackPayload))
    val (stackType, _, stackData) = recvFrame()
    check("READ_STACK → STACK_DATA",
      stackType == DebugMsgType.STACK_DATA,
      s"type=0x${stackType.toHexString}")
    check("STACK_DATA: 16 bytes (4 words)",
      stackData.length == 16,
      s"got ${stackData.length} bytes")

    // =========================================================================
    // 8. READ_MEMORY
    // =========================================================================

    // Payload: addr=0 (4B BE) + count=2 (4B BE)
    val memRdPayload = Seq(0, 0, 0, 0, 0, 0, 0, 2)
    sendFrame(buildFrame(DebugMsgType.READ_MEMORY, 0, memRdPayload))
    val (memRdType, _, memRdData) = recvFrame()
    check("READ_MEMORY → MEMORY_DATA",
      memRdType == DebugMsgType.MEMORY_DATA,
      s"type=0x${memRdType.toHexString}")
    check("MEMORY_DATA: 8 bytes (2 words)",
      memRdData.length == 8,
      s"got ${memRdData.length} bytes")

    // Word 0 should match the known init value at address 0
    if (memRdData.length >= 4) {
      val word0 = wordAt(memRdData, 0)
      val expectedWord0 = jopData.words(0).toLong & 0xFFFFFFFFL
      check("MEMORY_DATA: word[0] matches init",
        word0 == expectedWord0,
        f"got=0x$word0%08x expected=0x$expectedWord0%08x")
    }

    // =========================================================================
    // 9. WRITE_MEMORY + verify readback
    // =========================================================================

    // Use an address unlikely to be touched by the (halted) program
    // Address 0x7FFC (word address) = near top of 128KB BRAM
    val safeAddr = 0x7FFC
    val magicValue = 0xDEADBEEFL

    // WRITE_MEMORY payload: ADDR(4B BE) + VALUE(4B BE)
    val memWrPayload = Seq(
      (safeAddr >> 24) & 0xFF, (safeAddr >> 16) & 0xFF,
      (safeAddr >> 8) & 0xFF, safeAddr & 0xFF,
      0xDE, 0xAD, 0xBE, 0xEF
    )
    sendFrame(buildFrame(DebugMsgType.WRITE_MEMORY, 0, memWrPayload))
    val (wrAckType, _, _) = recvFrame()
    check("WRITE_MEMORY → ACK",
      wrAckType == DebugMsgType.ACK,
      s"type=0x${wrAckType.toHexString}")

    // Read it back
    val memVerPayload = Seq(
      (safeAddr >> 24) & 0xFF, (safeAddr >> 16) & 0xFF,
      (safeAddr >> 8) & 0xFF, safeAddr & 0xFF,
      0, 0, 0, 1
    )
    sendFrame(buildFrame(DebugMsgType.READ_MEMORY, 0, memVerPayload))
    val (memVerType, _, memVerData) = recvFrame()
    check("WRITE_MEMORY readback → MEMORY_DATA",
      memVerType == DebugMsgType.MEMORY_DATA,
      s"type=0x${memVerType.toHexString}")
    if (memVerData.length >= 4) {
      val readback = wordAt(memVerData, 0)
      check("WRITE_MEMORY readback = 0xDEADBEEF",
        readback == magicValue,
        f"got=0x$readback%08x")
    }

    // =========================================================================
    // 10. SET_BREAKPOINT
    // =========================================================================

    // Set a JPC breakpoint (type=0x01) at address 0x0000 (unlikely to be hit while halted)
    val bpPayload = Seq(0x01, 0, 0, 0, 0)
    sendFrame(buildFrame(DebugMsgType.SET_BREAKPOINT, 0, bpPayload))
    val (bpSetType, _, bpSetData) = recvFrame()
    check("SET_BREAKPOINT → ACK",
      bpSetType == DebugMsgType.ACK,
      s"type=0x${bpSetType.toHexString}")
    val bpSlot = if (bpSetData.nonEmpty) bpSetData(0) else -1
    check("SET_BREAKPOINT: slot returned",
      bpSlot >= 0,
      s"slot=$bpSlot")

    // =========================================================================
    // 11. QUERY_BREAKPOINTS
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.QUERY_BREAKPOINTS, 0))
    val (bpListType, _, bpListData) = recvFrame()
    check("QUERY_BREAKPOINTS → BREAKPOINT_LIST",
      bpListType == DebugMsgType.BREAKPOINT_LIST,
      s"type=0x${bpListType.toHexString}")

    // 4 slots * 6 bytes = 24 bytes
    check("BREAKPOINT_LIST: 24 bytes (4 slots)",
      bpListData.length == 24,
      s"got ${bpListData.length} bytes")

    // Verify our slot is enabled (bit 7 set in slot info byte)
    if (bpListData.length >= 6 && bpSlot >= 0 && bpSlot < 4) {
      val slotInfo = bpListData(bpSlot * 6)
      check("BREAKPOINT_LIST: our slot is enabled",
        (slotInfo & 0x80) != 0,
        f"slotInfo=0x$slotInfo%02x")
    }

    // =========================================================================
    // 12. CLEAR_BREAKPOINT
    // =========================================================================

    if (bpSlot >= 0) {
      sendFrame(buildFrame(DebugMsgType.CLEAR_BREAKPOINT, 0, Seq(bpSlot)))
      val (clrType, _, _) = recvFrame()
      check("CLEAR_BREAKPOINT → ACK",
        clrType == DebugMsgType.ACK,
        s"type=0x${clrType.toHexString}")

      // Verify cleared
      sendFrame(buildFrame(DebugMsgType.QUERY_BREAKPOINTS, 0))
      val (bpList2Type, _, bpList2Data) = recvFrame()
      if (bpList2Data.length >= 6 && bpSlot < 4) {
        val slotInfo = bpList2Data(bpSlot * 6)
        check("CLEAR_BREAKPOINT: slot now disabled",
          (slotInfo & 0x80) == 0,
          f"slotInfo=0x$slotInfo%02x")
      }
    }

    // =========================================================================
    // 13. STEP_MICRO
    // =========================================================================

    // Core is still halted from step 4
    sendFrame(buildFrame(DebugMsgType.STEP_MICRO, 0))
    val (stepAckType, _, _) = recvFrame()
    check("STEP_MICRO → ACK",
      stepAckType == DebugMsgType.ACK,
      s"type=0x${stepAckType.toHexString}")

    // Wait for HALTED notification (step complete)
    val (stepHaltedType, stepHaltedCore, stepHaltedPayload) = recvFrame()
    check("STEP_MICRO → HALTED notification",
      stepHaltedType == DebugMsgType.HALTED,
      s"type=0x${stepHaltedType.toHexString}")
    check("STEP_MICRO: reason=STEP",
      stepHaltedPayload.nonEmpty && stepHaltedPayload(0) == DebugHaltReason.STEP,
      s"reason=${if (stepHaltedPayload.nonEmpty) stepHaltedPayload(0) else -1}")

    dut.clockDomain.waitSampling(10)
    check("STEP_MICRO: core re-halted",
      dut.io.halted(0).toBoolean,
      "halted not asserted after step")

    // =========================================================================
    // 14. RESUME
    // =========================================================================

    sendFrame(buildFrame(DebugMsgType.RESUME, 0))
    val (resumeAckType, _, _) = recvFrame()
    check("RESUME → ACK",
      resumeAckType == DebugMsgType.ACK,
      s"type=0x${resumeAckType.toHexString}")

    dut.clockDomain.waitSampling(100)
    check("RESUME: core running",
      !dut.io.halted(0).toBoolean,
      "halted still asserted after resume")

    // Verify pipeline is making progress (PC changes over time)
    val pcAtResume = dut.io.pc(0).toInt
    dut.clockDomain.waitSampling(10000)
    val pcAfterRun = dut.io.pc(0).toInt
    check("RESUME: pipeline executing (PC changing)",
      pcAfterRun != pcAtResume,
      s"PC stuck at $pcAtResume after 10k cycles")

    // =========================================================================
    // Summary
    // =========================================================================

    val total = passed + failed
    println(s"\n=== Debug Protocol Sim: $passed/$total tests passed ===")
    if (failed > 0) {
      simFailure(s"$failed test(s) failed")
    } else {
      println("All tests passed.")
    }
  }
}
