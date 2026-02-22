package jop.debug

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * Per-core debug signals exposed from JopCore.
 */
case class DebugCoreSignals(pcWidth: Int, jpcWidth: Int, dataWidth: Int, ramWidth: Int, addrWidth: Int = 24) extends Bundle {
  val pc      = UInt(pcWidth bits)
  val jpc     = UInt((jpcWidth + 1) bits)
  val aout    = Bits(dataWidth bits)      // TOS (A register)
  val bout    = Bits(dataWidth bits)      // NOS (B register)
  val sp      = UInt(ramWidth bits)
  val vp      = UInt(ramWidth bits)
  val ar      = UInt(ramWidth bits)
  val flags   = Bits(4 bits)              // zf##nf##eq##lt
  val mulResult = Bits(dataWidth bits)
  val addrReg = UInt(addrWidth bits)      // Memory controller address register
  val rdDataReg = Bits(dataWidth bits)    // Memory controller read data register
  val instr   = Bits(10 bits)             // Current microcode instruction
  val bcopd   = Bits(16 bits)             // Decoded bytecode operand
  val jfetch  = Bool()                    // Bytecode fetch strobe
  val memBusy = Bool()                    // Memory controller busy

  // Stack RAM async read result (address driven via DebugController.io.debugRamAddr)
  val debugRamData = Bits(dataWidth bits)
}

/**
 * Debug Controller
 *
 * Handles all debug protocol commands: halt/resume, stepping, register read,
 * breakpoints, memory access, stack read, and SMP core selection.
 *
 * One instance serves all cores in JopCluster.
 *
 * @param config   Debug configuration
 * @param cpuCnt   Number of CPU cores
 * @param pcWidth  Microcode PC width
 * @param jpcWidth Java PC width
 * @param dataWidth Data path width
 * @param ramWidth Stack RAM address width
 * @param bmbParameter BMB parameter for memory access (when hasMemAccess)
 */
case class DebugController(
  config: DebugConfig,
  cpuCnt: Int,
  pcWidth: Int = 11,
  jpcWidth: Int = 11,
  dataWidth: Int = 32,
  ramWidth: Int = 8,
  addrWidth: Int = 24,
  bmbParameter: Option[BmbParameter] = None
) extends Component {
  require(cpuCnt >= 1)

  val io = new Bundle {
    // Protocol interface
    val cmdValid     = in Bool()
    val cmdType      = in Bits(8 bits)
    val cmdCore      = in UInt(8 bits)
    val cmdPayload   = in Vec(Bits(8 bits), 8)
    val cmdPayloadLen = in UInt(16 bits)
    val cmdReady     = out Bool()

    // Streaming payload (WRITE_MEMORY_BLOCK)
    val streamByte   = in Bits(8 bits)
    val streamValid  = in Bool()
    val streamReady  = out Bool()

    // Response output
    val rspValid     = out Bool()
    val rspType      = out Bits(8 bits)
    val rspCore      = out UInt(8 bits)
    val rspPayload   = out Vec(Bits(8 bits), 64)
    val rspPayloadLen = out UInt(16 bits)
    val rspReady     = in Bool()
    val txBusy       = in Bool()

    // Per-core debug halt output
    val debugHalt    = out Vec(Bool(), cpuCnt)

    // Per-core debug signals
    val coreSignals  = in Vec(DebugCoreSignals(pcWidth, jpcWidth, dataWidth, ramWidth, addrWidth), cpuCnt)

    // Per-core stack RAM address (directly driven)
    val debugRamAddr = out Vec(UInt(ramWidth bits), cpuCnt)

    // Per-core breakpoint hit signals (from DebugBreakpoints, active when enabled)
    val bpHit        = in Vec(Bool(), cpuCnt)
    val bpHitSlot    = in Vec(UInt(3 bits), cpuCnt)

    // Breakpoint management (active for 1 cycle during PROCESS_CMD)
    val bpTargetCore = out UInt(8 bits)       // Target core for bp operation
    val bpSetValid   = out Bool()             // Set breakpoint
    val bpSetType    = out Bits(8 bits)       // PC (0x00) or JPC (0x01)
    val bpSetAddr    = out UInt(32 bits)      // Breakpoint address
    val bpSetOk      = in Bool()              // Set succeeded (muxed from target core)
    val bpSetSlot    = in UInt(3 bits)        // Assigned slot (muxed from target core)
    val bpClearValid = out Bool()             // Clear breakpoint
    val bpClearSlot  = out UInt(3 bits)       // Slot to clear
    // Breakpoint query data (muxed from target core by JopCluster)
    val bpQueryCount = in UInt(4 bits)
    val bpQuerySlotData    = in Vec(Bits(48 bits), config.numBreakpoints.max(1))
    val bpQuerySlotEnabled = in Vec(Bool(), config.numBreakpoints.max(1))

    // BMB master for memory access (optional)
    val bmb = if (config.hasMemAccess) Some(master(Bmb(bmbParameter.get))) else None

    // Configuration info for TARGET_INFO
    val stackDepth   = in UInt(16 bits)
    val memorySize   = in UInt(32 bits)
  }

  // ==========================================================================
  // Per-core State
  // ==========================================================================

  val coreHalted = Vec(Reg(Bool()) init(False), cpuCnt)
  val haltReason = Vec(Reg(Bits(8 bits)) init(0), cpuCnt)
  val haltSlot = Vec(Reg(Bits(8 bits)) init(0xFF), cpuCnt)

  // Drive debug halt outputs
  for (i <- 0 until cpuCnt) {
    io.debugHalt(i) := coreHalted(i)
    io.debugRamAddr(i) := 0  // Default, overridden during READ_STACK
  }

  // ==========================================================================
  // HALTED Notification Queue
  // ==========================================================================

  // Pending HALTED notifications (one per core)
  val haltedPending = Vec(Reg(Bool()) init(False), cpuCnt)
  val haltedReason = Vec(Reg(Bits(8 bits)) init(0), cpuCnt)
  val haltedSlot = Vec(Reg(Bits(8 bits)) init(0xFF), cpuCnt)

  // ==========================================================================
  // Stepping State
  // ==========================================================================

  val stepping = Vec(Reg(Bool()) init(False), cpuCnt)
  val stepIsBytecode = Vec(Reg(Bool()) init(False), cpuCnt)
  val stepJpcSnapshot = Vec(Reg(UInt((jpcWidth + 1) bits)) init(0), cpuCnt)
  val stepPcSnapshot = Vec(Reg(UInt(pcWidth bits)) init(0), cpuCnt)
  // For micro step: track that we've seen the pipeline execute (PC changed)
  val stepSeenRunning = Vec(Reg(Bool()) init(False), cpuCnt)

  // ==========================================================================
  // Breakpoint Detection
  // ==========================================================================

  for (i <- 0 until cpuCnt) {
    // Breakpoint hit while running (not halted, not stepping)
    when(!coreHalted(i) && !stepping(i) && io.bpHit(i)) {
      coreHalted(i) := True
      haltReason(i) := DebugHaltReason.BREAKPOINT
      haltSlot(i) := io.bpHitSlot(i).asBits.resized
      haltedPending(i) := True
      haltedReason(i) := DebugHaltReason.BREAKPOINT
      haltedSlot(i) := io.bpHitSlot(i).asBits.resized
    }
  }

  // ==========================================================================
  // Stepping Logic (runs independently per core)
  // ==========================================================================

  for (i <- 0 until cpuCnt) {
    when(stepping(i)) {
      when(stepIsBytecode(i)) {
        // STEP_BYTECODE: wait for jfetch with different JPC
        when(io.coreSignals(i).jfetch && io.coreSignals(i).jpc =/= stepJpcSnapshot(i)) {
          // Bytecode completed — re-halt
          coreHalted(i) := True
          stepping(i) := False
          haltReason(i) := DebugHaltReason.STEP
          haltSlot(i) := 0xFF
          haltedPending(i) := True
          haltedReason(i) := DebugHaltReason.STEP
          haltedSlot(i) := 0xFF
        }
      }.otherwise {
        // STEP_MICRO: release halt, wait for pipeline to execute one instruction.
        // The pipeline stalls at wait instructions (every bytecode ends with wait pair).
        // We release debugHalt, let the pipeline execute past the wait pair,
        // then when the pipeline settles, re-halt.
        when(!stepSeenRunning(i)) {
          // Phase 1: wait for PC to change from snapshot (pipeline started executing)
          when(io.coreSignals(i).pc =/= stepPcSnapshot(i)) {
            stepSeenRunning(i) := True
          }
        }.otherwise {
          // Phase 2: wait for memBusy=False (no active memory operation = safe to halt)
          when(!io.coreSignals(i).memBusy) {
            coreHalted(i) := True
            stepping(i) := False
            stepSeenRunning(i) := False
            haltReason(i) := DebugHaltReason.STEP
            haltSlot(i) := 0xFF
            haltedPending(i) := True
            haltedReason(i) := DebugHaltReason.STEP
            haltedSlot(i) := 0xFF
          }
        }
      }
    }
  }

  // ==========================================================================
  // Main Command FSM
  // ==========================================================================

  object CmdState extends SpinalEnum {
    val IDLE, PROCESS_CMD, WAIT_RSP_DONE,
        // Memory access states
        MEM_READ_CMD, MEM_READ_WAIT, MEM_READ_NEXT,
        MEM_WRITE_CMD, MEM_WRITE_WAIT,
        MEM_BLOCK_WRITE_ACCUM, MEM_BLOCK_WRITE_CMD, MEM_BLOCK_WRITE_WAIT,
        // Stack read states
        STACK_READ_WORD, STACK_READ_NEXT = newElement()
  }

  val cmdState = Reg(CmdState()) init(CmdState.IDLE)

  // Response registers
  val rspType = Reg(Bits(8 bits)) init(0)
  val rspCore = Reg(UInt(8 bits)) init(0)
  val rspPayload = Vec(Reg(Bits(8 bits)) init(0), 64)
  val rspPayloadLen = Reg(UInt(16 bits)) init(0)
  val rspValid = Reg(Bool()) init(False)

  // Memory access registers
  val memAddr = Reg(UInt(32 bits)) init(0)
  val memCount = Reg(UInt(32 bits)) init(0)
  val memIdx = Reg(UInt(32 bits)) init(0)

  // Block write accumulator
  val blockAccumBytes = Reg(UInt(2 bits)) init(0)
  val blockAccumWord = Reg(Bits(32 bits)) init(0)

  // Stack read registers
  val stackOffset = Reg(UInt(16 bits)) init(0)
  val stackCount = Reg(UInt(16 bits)) init(0)
  val stackIdx = Reg(UInt(16 bits)) init(0)

  // Target core for current command
  val targetCore = Reg(UInt(8 bits)) init(0)

  // Wire outputs
  io.cmdReady := False
  io.streamReady := False
  io.rspValid := rspValid
  io.rspType := rspType
  io.rspCore := rspCore
  for (i <- 0 until 64) io.rspPayload(i) := rspPayload(i)
  io.rspPayloadLen := rspPayloadLen

  // Breakpoint management defaults
  io.bpTargetCore := targetCore
  io.bpSetValid := False
  io.bpSetType := 0
  io.bpSetAddr := 0
  io.bpClearValid := False
  io.bpClearSlot := 0

  // BMB defaults
  if (config.hasMemAccess) {
    val bmb = io.bmb.get
    bmb.cmd.valid := False
    bmb.cmd.last := True
    bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
    bmb.cmd.fragment.address := 0
    bmb.cmd.fragment.length := 3
    bmb.cmd.fragment.source := 0
    bmb.cmd.fragment.context := 0
    if (bmbParameter.get.access.canWrite) {
      bmb.cmd.fragment.data := 0
      bmb.cmd.fragment.mask := B"1111"
    }
    bmb.rsp.ready := True
  }

  // Clear rspValid when protocol layer accepts the response
  when(io.rspReady && rspValid) {
    rspValid := False
  }

  // Helper: check if target core is valid
  def coreValid(core: UInt): Bool = core < cpuCnt

  // Helper: check if target core is halted
  def coreIsHalted(core: UInt): Bool = coreHalted(core.resized)

  // Helper: pack 32-bit value into payload at given byte offset (big-endian)
  def packWord(offset: Int, value: Bits): Unit = {
    rspPayload(offset)     := value(31 downto 24)
    rspPayload(offset + 1) := value(23 downto 16)
    rspPayload(offset + 2) := value(15 downto 8)
    rspPayload(offset + 3) := value(7 downto 0)
  }

  // Helper: unpack 32-bit value from command payload (big-endian)
  def unpackWord(offset: Int): Bits = {
    io.cmdPayload(offset) ## io.cmdPayload(offset + 1) ##
    io.cmdPayload(offset + 2) ## io.cmdPayload(offset + 3)
  }

  // Helper: send simple ACK
  def sendAck(core: UInt): Unit = {
    rspType := DebugMsgType.ACK
    rspCore := core
    rspPayloadLen := 0
    rspValid := True
    cmdState := CmdState.WAIT_RSP_DONE
  }

  // Helper: send NAK with error code
  def sendNak(core: UInt, code: Int): Unit = {
    rspType := DebugMsgType.NAK
    rspCore := core
    rspPayload(0) := code
    rspPayloadLen := 1
    rspValid := True
    cmdState := CmdState.WAIT_RSP_DONE
  }

  switch(cmdState) {

    is(CmdState.IDLE) {
      // Priority 1: send pending HALTED notifications
      // Find first pending core (reverse loop: lowest index wins via last-assignment-wins)
      val haltedCoreIdx = UInt(log2Up(cpuCnt.max(2)) bits)
      val hasHaltedPending = Bool()
      haltedCoreIdx := 0
      hasHaltedPending := False
      for (i <- (cpuCnt - 1) to 0 by -1) {
        when(haltedPending(i) && !io.txBusy && !rspValid) {
          haltedCoreIdx := i
          hasHaltedPending := True
        }
      }

      when(hasHaltedPending) {
        rspType := DebugMsgType.HALTED
        rspCore := haltedCoreIdx.resized
        rspPayload(0) := haltedReason(haltedCoreIdx.resized)
        rspPayload(1) := haltedSlot(haltedCoreIdx.resized)
        rspPayloadLen := 2
        rspValid := True
        haltedPending(haltedCoreIdx.resized) := False
        cmdState := CmdState.WAIT_RSP_DONE
      }.elsewhen(io.cmdValid) {
        // Priority 2: process incoming commands
        targetCore := io.cmdCore
        io.cmdReady := True
        cmdState := CmdState.PROCESS_CMD
      }
    }

    is(CmdState.PROCESS_CMD) {
      val core = targetCore
      val isBroadcast = (core === 0xFF)

      switch(io.cmdType) {

        // ================================================================
        // PING
        // ================================================================
        is(DebugMsgType.PING) {
          rspType := DebugMsgType.PONG
          rspCore := core
          rspPayloadLen := 0
          rspValid := True
          cmdState := CmdState.WAIT_RSP_DONE
        }

        // ================================================================
        // QUERY_INFO
        // ================================================================
        is(DebugMsgType.QUERY_INFO) {
          // Build TARGET_INFO with tag-value pairs
          var idx = 0

          // 0x01: NUM_CORES
          rspPayload(idx) := 0x01; idx += 1
          rspPayload(idx) := 1; idx += 1  // tag length
          rspPayload(idx) := cpuCnt; idx += 1

          // 0x02: NUM_BREAKPOINTS
          rspPayload(idx) := 0x02; idx += 1
          rspPayload(idx) := 1; idx += 1
          rspPayload(idx) := config.numBreakpoints; idx += 1

          // 0x03: STACK_DEPTH (2 bytes BE)
          rspPayload(idx) := 0x03; idx += 1
          rspPayload(idx) := 2; idx += 1
          rspPayload(idx) := io.stackDepth(15 downto 8).asBits
          idx += 1
          rspPayload(idx) := io.stackDepth(7 downto 0).asBits
          idx += 1

          // 0x04: MEMORY_SIZE (4 bytes BE)
          rspPayload(idx) := 0x04; idx += 1
          rspPayload(idx) := 4; idx += 1
          rspPayload(idx) := io.memorySize(31 downto 24).asBits; idx += 1
          rspPayload(idx) := io.memorySize(23 downto 16).asBits; idx += 1
          rspPayload(idx) := io.memorySize(15 downto 8).asBits; idx += 1
          rspPayload(idx) := io.memorySize(7 downto 0).asBits; idx += 1

          // 0x05: MICRO_PIPELINE_DEPTH
          rspPayload(idx) := 0x05; idx += 1
          rspPayload(idx) := 1; idx += 1
          rspPayload(idx) := 3; idx += 1  // bcfetch -> fetch -> decode

          // 0x06: BYTECODE_PIPELINE_DEPTH
          rspPayload(idx) := 0x06; idx += 1
          rspPayload(idx) := 1; idx += 1
          rspPayload(idx) := 3; idx += 1

          // 0x08: PROTOCOL_VERSION (1.0)
          rspPayload(idx) := 0x08; idx += 1
          rspPayload(idx) := 2; idx += 1
          rspPayload(idx) := 1; idx += 1  // major
          rspPayload(idx) := 0; idx += 1  // minor

          // 0x09: EXTENDED_REGISTERS (bit 0 = FLAGS/INSTR/JOPD present)
          rspPayload(idx) := 0x09; idx += 1
          rspPayload(idx) := 1; idx += 1
          rspPayload(idx) := 0x01; idx += 1  // bit 0 set

          rspType := DebugMsgType.TARGET_INFO
          rspCore := core
          rspPayloadLen := idx
          rspValid := True
          cmdState := CmdState.WAIT_RSP_DONE
        }

        // ================================================================
        // HALT
        // ================================================================
        is(DebugMsgType.HALT) {
          when(isBroadcast) {
            for (i <- 0 until cpuCnt) {
              when(!coreHalted(i)) {
                coreHalted(i) := True
                haltReason(i) := DebugHaltReason.MANUAL
                haltSlot(i) := 0xFF
                haltedPending(i) := True
                haltedReason(i) := DebugHaltReason.MANUAL
                haltedSlot(i) := 0xFF
              }
            }
          }.otherwise {
            when(coreValid(core)) {
              when(!coreIsHalted(core)) {
                coreHalted(core.resized) := True
                haltReason(core.resized) := DebugHaltReason.MANUAL
                haltSlot(core.resized) := 0xFF
                haltedPending(core.resized) := True
                haltedReason(core.resized) := DebugHaltReason.MANUAL
                haltedSlot(core.resized) := 0xFF
              }
            }
          }
          sendAck(core)
        }

        // ================================================================
        // RESUME
        // ================================================================
        is(DebugMsgType.RESUME) {
          when(isBroadcast) {
            for (i <- 0 until cpuCnt) {
              coreHalted(i) := False
            }
          }.otherwise {
            when(coreValid(core)) {
              coreHalted(core.resized) := False
            }
          }
          sendAck(core)
        }

        // ================================================================
        // STEP_MICRO
        // ================================================================
        is(DebugMsgType.STEP_MICRO) {
          when(coreValid(core) && coreIsHalted(core)) {
            // Snapshot PC, release halt, start micro-step tracking
            stepPcSnapshot(core.resized) := io.coreSignals(core.resized).pc
            coreHalted(core.resized) := False
            stepping(core.resized) := True
            stepIsBytecode(core.resized) := False
            stepSeenRunning(core.resized) := False
            sendAck(core)
          }.otherwise {
            sendNak(core, DebugNakCode.NOT_HALTED)
          }
        }

        // ================================================================
        // STEP_BYTECODE
        // ================================================================
        is(DebugMsgType.STEP_BYTECODE) {
          when(coreValid(core) && coreIsHalted(core)) {
            // Snapshot JPC, release halt
            stepJpcSnapshot(core.resized) := io.coreSignals(core.resized).jpc
            coreHalted(core.resized) := False
            stepping(core.resized) := True
            stepIsBytecode(core.resized) := True
            sendAck(core)
          }.otherwise {
            sendNak(core, DebugNakCode.NOT_HALTED)
          }
        }

        // ================================================================
        // QUERY_STATUS
        // ================================================================
        is(DebugMsgType.QUERY_STATUS) {
          when(coreValid(core)) {
            rspPayload(0) := Mux(coreIsHalted(core), B(0x01, 8 bits), B(0x00, 8 bits))
            rspPayload(1) := Mux(coreIsHalted(core), haltReason(core.resized), B(0x00, 8 bits))
            rspType := DebugMsgType.STATUS
            rspCore := core
            rspPayloadLen := 2
            rspValid := True
            cmdState := CmdState.WAIT_RSP_DONE
          }.otherwise {
            sendNak(core, DebugNakCode.UNKNOWN)
          }
        }

        // ================================================================
        // READ_REGISTERS
        // ================================================================
        is(DebugMsgType.READ_REGISTERS) {
          when(coreValid(core) && coreIsHalted(core)) {
            val sig = io.coreSignals(core.resized)

            // Architectural registers (48 bytes = 12 registers)
            // 0x00: PC
            packWord(0, sig.pc.asBits.resize(32 bits))
            // 0x01: JPC
            packWord(4, sig.jpc.asBits.resize(32 bits))
            // 0x02: A (TOS)
            packWord(8, sig.aout)
            // 0x03: B (NOS)
            packWord(12, sig.bout)
            // 0x04: SP
            packWord(16, sig.sp.asBits.resize(32 bits))
            // 0x05: VP
            packWord(20, sig.vp.asBits.resize(32 bits))
            // 0x06: AR
            packWord(24, sig.ar.asBits.resize(32 bits))
            // 0x07: MUL_RESULT
            packWord(28, sig.mulResult)

            // Implementation-specific (registers 0x08-0x0B)
            // 0x08: MEM_RD_ADDR (using addrReg)
            packWord(32, sig.addrReg.asBits.resize(32 bits))
            // 0x09: MEM_WR_ADDR (same register, different context)
            packWord(36, sig.addrReg.asBits.resize(32 bits))
            // 0x0A: MEM_RD_DATA
            packWord(40, sig.rdDataReg)
            // 0x0B: MEM_WR_DATA (same as TOS for writes)
            packWord(44, sig.aout)

            // Extended registers (0x0C-0x0E, 12 bytes)
            // 0x0C: FLAGS (zf##nf##eq##lt in lower 4 bits)
            packWord(48, sig.flags.resize(32 bits))
            // 0x0D: INSTR
            packWord(52, sig.instr.resize(32 bits))
            // 0x0E: JOPD (bytecode operand)
            packWord(56, sig.bcopd.resize(32 bits))

            rspType := DebugMsgType.REGISTERS
            rspCore := core
            rspPayloadLen := 60  // 15 registers * 4 bytes
            rspValid := True
            cmdState := CmdState.WAIT_RSP_DONE
          }.otherwise {
            sendNak(core, DebugNakCode.NOT_HALTED)
          }
        }

        // ================================================================
        // READ_STACK
        // ================================================================
        is(DebugMsgType.READ_STACK) {
          when(coreValid(core) && coreIsHalted(core)) {
            // Payload: OFFSET(16 BE) + COUNT(16 BE)
            stackOffset := (io.cmdPayload(0) ## io.cmdPayload(1)).asUInt
            stackCount := (io.cmdPayload(2) ## io.cmdPayload(3)).asUInt
            stackIdx := 0
            memIdx := 0
            cmdState := CmdState.STACK_READ_WORD
          }.otherwise {
            sendNak(core, DebugNakCode.NOT_HALTED)
          }
        }

        // ================================================================
        // READ_MEMORY
        // ================================================================
        is(DebugMsgType.READ_MEMORY) {
          if (config.hasMemAccess) {
            when(coreValid(core) && coreIsHalted(core)) {
              // Payload: ADDR(32 BE) + COUNT(32 BE)
              memAddr := unpackWord(0).asUInt
              memCount := unpackWord(4).asUInt
              memIdx := 0
              cmdState := CmdState.MEM_READ_CMD
            }.otherwise {
              sendNak(core, DebugNakCode.NOT_HALTED)
            }
          } else {
            sendNak(core, DebugNakCode.INVALID_ADDR)
          }
        }

        // ================================================================
        // WRITE_MEMORY
        // ================================================================
        is(DebugMsgType.WRITE_MEMORY) {
          if (config.hasMemAccess) {
            when(coreValid(core) && coreIsHalted(core)) {
              // Payload: ADDR(32 BE) + VALUE(32 BE)
              memAddr := unpackWord(0).asUInt
              blockAccumWord := unpackWord(4)
              cmdState := CmdState.MEM_WRITE_CMD
            }.otherwise {
              sendNak(core, DebugNakCode.NOT_HALTED)
            }
          } else {
            sendNak(core, DebugNakCode.INVALID_ADDR)
          }
        }

        // ================================================================
        // WRITE_MEMORY_BLOCK
        // ================================================================
        is(DebugMsgType.WRITE_MEMORY_BLOCK) {
          if (config.hasMemAccess) {
            when(coreValid(core) && coreIsHalted(core)) {
              // First 8 bytes of payload: ADDR(32 BE) + COUNT(32 BE)
              memAddr := unpackWord(0).asUInt
              memCount := unpackWord(4).asUInt
              memIdx := 0
              blockAccumBytes := 0
              blockAccumWord := 0
              cmdState := CmdState.MEM_BLOCK_WRITE_ACCUM
            }.otherwise {
              sendNak(core, DebugNakCode.NOT_HALTED)
            }
          } else {
            sendNak(core, DebugNakCode.INVALID_ADDR)
          }
        }

        // ================================================================
        // SET_BREAKPOINT
        // ================================================================
        is(DebugMsgType.SET_BREAKPOINT) {
          if (config.numBreakpoints > 0) {
            when(coreValid(core)) {
              // Payload: TYPE(1) + ADDR(4) = 5 bytes
              // Assert set signals (combinational, JopCluster routes to target core)
              io.bpSetValid := True
              io.bpSetType := io.cmdPayload(0)
              io.bpSetAddr := (io.cmdPayload(1) ## io.cmdPayload(2) ##
                               io.cmdPayload(3) ## io.cmdPayload(4)).asUInt
              when(io.bpSetOk) {
                // Success — return assigned slot number
                rspPayload(0) := io.bpSetSlot.asBits.resized
                rspType := DebugMsgType.ACK
                rspCore := core
                rspPayloadLen := 1
                rspValid := True
                cmdState := CmdState.WAIT_RSP_DONE
              }.otherwise {
                sendNak(core, DebugNakCode.NO_FREE_SLOTS)
              }
            }.otherwise {
              sendNak(core, DebugNakCode.UNKNOWN)
            }
          } else {
            sendNak(core, DebugNakCode.NO_FREE_SLOTS)
          }
        }

        // ================================================================
        // CLEAR_BREAKPOINT
        // ================================================================
        is(DebugMsgType.CLEAR_BREAKPOINT) {
          if (config.numBreakpoints > 0) {
            when(coreValid(core)) {
              // Payload: SLOT(1)
              io.bpClearValid := True
              io.bpClearSlot := io.cmdPayload(0).asUInt.resized
              sendAck(core)
            }.otherwise {
              sendNak(core, DebugNakCode.UNKNOWN)
            }
          } else {
            sendNak(core, DebugNakCode.INVALID_SLOT)
          }
        }

        // ================================================================
        // QUERY_BREAKPOINTS
        // ================================================================
        is(DebugMsgType.QUERY_BREAKPOINTS) {
          if (config.numBreakpoints > 0) {
            when(coreValid(core)) {
              // Build response: all slots, 6 bytes each
              // Format: SLOT_INFO(1) + TYPE(1) + ADDR(4) per slot
              // SLOT_INFO: bit 7 = enabled, bits 2:0 = slot index
              for (slot <- 0 until config.numBreakpoints) {
                val base = slot * 6
                val data = io.bpQuerySlotData(slot)  // SLOT(8) ## TYPE(8) ## ADDR(32)
                val slotByte = Mux(io.bpQuerySlotEnabled(slot),
                  B(0x80 | slot, 8 bits), B(slot, 8 bits))
                rspPayload(base + 0) := slotByte
                rspPayload(base + 1) := data(39 downto 32)  // type
                rspPayload(base + 2) := data(31 downto 24)  // addr[31:24]
                rspPayload(base + 3) := data(23 downto 16)  // addr[23:16]
                rspPayload(base + 4) := data(15 downto 8)   // addr[15:8]
                rspPayload(base + 5) := data(7 downto 0)    // addr[7:0]
              }
              rspType := DebugMsgType.BREAKPOINT_LIST
              rspCore := core
              rspPayloadLen := config.numBreakpoints * 6
              rspValid := True
              cmdState := CmdState.WAIT_RSP_DONE
            }.otherwise {
              sendNak(core, DebugNakCode.UNKNOWN)
            }
          } else {
            rspType := DebugMsgType.BREAKPOINT_LIST
            rspCore := core
            rspPayloadLen := 0
            rspValid := True
            cmdState := CmdState.WAIT_RSP_DONE
          }
        }

        default {
          sendNak(core, DebugNakCode.UNKNOWN)
        }
      }
    }

    // ================================================================
    // Wait for response to be sent
    // ================================================================
    is(CmdState.WAIT_RSP_DONE) {
      when(!rspValid) {
        // Response was consumed by protocol layer
        cmdState := CmdState.IDLE
      }
    }

    // ================================================================
    // Stack Read States
    // ================================================================
    is(CmdState.STACK_READ_WORD) {
      // Drive async read address for target core
      io.debugRamAddr(targetCore.resized) := (stackOffset + stackIdx).resized
      // Combinational read — data available same cycle
      cmdState := CmdState.STACK_READ_NEXT
    }

    is(CmdState.STACK_READ_NEXT) {
      // Capture read data (still driving address)
      io.debugRamAddr(targetCore.resized) := (stackOffset + stackIdx).resized
      val word = io.coreSignals(targetCore.resized).debugRamData
      val byteOff = (stackIdx << 2).resize(16 bits)
      rspPayload(byteOff.resized) := word(31 downto 24)
      rspPayload((byteOff + 1).resized) := word(23 downto 16)
      rspPayload((byteOff + 2).resized) := word(15 downto 8)
      rspPayload((byteOff + 3).resized) := word(7 downto 0)
      stackIdx := stackIdx + 1

      when(stackIdx + 1 >= stackCount || (stackIdx + 1) >= 16) {
        // Done (cap at 16 words = 64 bytes to fit response payload)
        rspType := DebugMsgType.STACK_DATA
        rspCore := targetCore
        rspPayloadLen := ((stackIdx + 1) << 2).resized
        rspValid := True
        cmdState := CmdState.WAIT_RSP_DONE
      }.otherwise {
        cmdState := CmdState.STACK_READ_WORD
      }
    }

    // ================================================================
    // Memory Read States (BMB)
    // ================================================================
    if (config.hasMemAccess) {
      is(CmdState.MEM_READ_CMD) {
        val bmb = io.bmb.get
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
        bmb.cmd.fragment.address := (memAddr << 2).resized
        when(bmb.cmd.fire) {
          cmdState := CmdState.MEM_READ_WAIT
        }
      }

      is(CmdState.MEM_READ_WAIT) {
        val bmb = io.bmb.get
        when(bmb.rsp.fire) {
          val word = bmb.rsp.fragment.data
          val byteOff = (memIdx << 2).resize(16 bits)
          rspPayload(byteOff.resized) := word(31 downto 24)
          rspPayload((byteOff + 1).resized) := word(23 downto 16)
          rspPayload((byteOff + 2).resized) := word(15 downto 8)
          rspPayload((byteOff + 3).resized) := word(7 downto 0)
          memIdx := memIdx + 1
          memAddr := memAddr + 1
          cmdState := CmdState.MEM_READ_NEXT
        }
      }

      is(CmdState.MEM_READ_NEXT) {
        when(memIdx >= memCount || memIdx >= 16) {
          // Done (cap at 16 words = 64 bytes to fit response payload)
          rspType := DebugMsgType.MEMORY_DATA
          rspCore := targetCore
          rspPayloadLen := (memIdx << 2).resized
          rspValid := True
          cmdState := CmdState.WAIT_RSP_DONE
        }.otherwise {
          cmdState := CmdState.MEM_READ_CMD
        }
      }

      // ================================================================
      // Memory Write States (BMB)
      // ================================================================

      is(CmdState.MEM_WRITE_CMD) {
        val bmb = io.bmb.get
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
        bmb.cmd.fragment.address := (memAddr << 2).resized
        if (bmbParameter.get.access.canWrite) {
          bmb.cmd.fragment.data := blockAccumWord
        }
        when(bmb.cmd.fire) {
          cmdState := CmdState.MEM_WRITE_WAIT
        }
      }

      is(CmdState.MEM_WRITE_WAIT) {
        val bmb = io.bmb.get
        when(bmb.rsp.fire) {
          sendAck(targetCore)
        }
      }

      // ================================================================
      // Block Write States (streaming)
      // ================================================================

      is(CmdState.MEM_BLOCK_WRITE_ACCUM) {
        // Accumulate 4 bytes from stream into one 32-bit word
        io.streamReady := True
        when(io.streamValid) {
          switch(blockAccumBytes) {
            is(0) { blockAccumWord(31 downto 24) := io.streamByte }
            is(1) { blockAccumWord(23 downto 16) := io.streamByte }
            is(2) { blockAccumWord(15 downto 8)  := io.streamByte }
            is(3) { blockAccumWord(7 downto 0)   := io.streamByte }
          }
          blockAccumBytes := blockAccumBytes + 1
          when(blockAccumBytes === 3) {
            // Full word accumulated — issue BMB write
            cmdState := CmdState.MEM_BLOCK_WRITE_CMD
          }
        }
      }

      is(CmdState.MEM_BLOCK_WRITE_CMD) {
        val bmb = io.bmb.get
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
        bmb.cmd.fragment.address := (memAddr << 2).resized
        if (bmbParameter.get.access.canWrite) {
          bmb.cmd.fragment.data := blockAccumWord
        }
        when(bmb.cmd.fire) {
          cmdState := CmdState.MEM_BLOCK_WRITE_WAIT
        }
      }

      is(CmdState.MEM_BLOCK_WRITE_WAIT) {
        val bmb = io.bmb.get
        when(bmb.rsp.fire) {
          memAddr := memAddr + 1
          memIdx := memIdx + 1
          blockAccumBytes := 0
          when(memIdx + 1 >= memCount) {
            sendAck(targetCore)
          }.otherwise {
            cmdState := CmdState.MEM_BLOCK_WRITE_ACCUM
          }
        }
      }
    }
  }
}
