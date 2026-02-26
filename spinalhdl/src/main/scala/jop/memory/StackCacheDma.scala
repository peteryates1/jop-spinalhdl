package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.pipeline.StackCacheConfig

/**
 * Stack Cache DMA Controller
 *
 * Transfers stack bank contents between bank RAMs and external memory via BMB bus.
 * Used by the 3-bank rotating stack cache to spill (write to ext mem) and fill
 * (read from ext mem) banks as the stack pointer crosses bank boundaries.
 *
 * Burst support: transfers in burstLen-word chunks for efficient SDRAM/DDR3 access.
 *   SDR:  burstLen=4 → 192/4 = 48 bursts per bank, ~336 cycles, ~3.4us at 100MHz
 *   DDR3: burstLen=8 → 192/8 = 24 bursts per bank, ~168 cycles, ~1.7us at 100MHz
 *   BRAM: burstLen=0 → 192 single-word transfers, ~384 cycles
 *
 * The DMA has its own BMB master port, arbitrated locally within JopCore against
 * the memory controller's BMB port. Memory controller gets priority.
 *
 * @param cacheConfig Stack cache configuration
 * @param bmbParam    BMB parameters (must match JopMemoryConfig.bmbParameter)
 */
case class StackCacheDma(
  cacheConfig: StackCacheConfig,
  bmbParam: BmbParameter
) extends Component {

  val io = new Bundle {
    // BMB master port to external memory
    val bmb = master(Bmb(bmbParam))

    // Bank RAM read port (for spill: read from bank, write to ext mem)
    val bankRdAddr = out UInt(8 bits)
    val bankRdData = in Bits(32 bits)

    // Bank RAM write port (for fill: read from ext mem, write to bank)
    val bankWrAddr = out UInt(8 bits)
    val bankWrData = out Bits(32 bits)
    val bankWrEn   = out Bool()

    // Which bank to access (0, 1, or 2)
    val bankSelect = out UInt(2 bits)

    // Control interface
    val start     = in Bool()     // Pulse to begin transfer
    val isSpill   = in Bool()     // True=spill (bank→ext), False=fill (ext→bank)
    val extAddr   = in UInt(bmbParam.access.addressWidth bits)  // Byte address in ext mem
    val wordCount = in UInt(8 bits)   // Number of words to transfer (up to bankSize=192)
    val bank      = in UInt(2 bits)   // Bank index to spill/fill

    // Status
    val busy = out Bool()
    val done = out Bool()   // Single-cycle pulse when transfer completes
  }

  // ==========================================================================
  // State Machine
  // ==========================================================================

  object State extends SpinalEnum {
    val IDLE,
        // Spill: read bank RAM, issue BMB write
        SPILL_READ, SPILL_CMD, SPILL_WAIT,
        // Fill: issue BMB read, write to bank RAM
        FILL_CMD, FILL_WAIT,
        DONE
      = newElement()
  }

  val state = Reg(State()) init(State.IDLE)

  // Transfer counters
  val wordsDone = Reg(UInt(8 bits)) init(0)
  val totalWords = Reg(UInt(8 bits)) init(0)
  val baseAddr = Reg(UInt(bmbParam.access.addressWidth bits)) init(0)
  val bankIdx = Reg(UInt(2 bits)) init(0)
  val isSpillReg = Reg(Bool()) init(False)

  // Spill: data read from bank RAM (1-cycle latency, so we read first then send)
  val spillData = Reg(Bits(32 bits)) init(0)

  // Burst tracking
  val burstLen = cacheConfig.burstLen
  val wordsInBurst = if (burstLen > 0) burstLen else 1
  val burstWordsSent = Reg(UInt(log2Up(wordsInBurst.max(2)) + 1 bits)) init(0)

  // ==========================================================================
  // BMB Defaults
  // ==========================================================================

  io.bmb.cmd.valid := False
  io.bmb.cmd.last := True
  io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
  io.bmb.cmd.fragment.address := 0
  io.bmb.cmd.fragment.length := 3  // 4 bytes (single word)
  io.bmb.cmd.fragment.source := 0
  io.bmb.cmd.fragment.context := 0
  if (bmbParam.access.canWrite) {
    io.bmb.cmd.fragment.data := 0
    io.bmb.cmd.fragment.mask := B"1111"
  }
  io.bmb.rsp.ready := True

  // ==========================================================================
  // Bank RAM port defaults
  // ==========================================================================

  io.bankRdAddr := wordsDone.resized
  io.bankWrAddr := 0
  io.bankWrData := 0
  io.bankWrEn := False
  io.bankSelect := bankIdx

  // ==========================================================================
  // Status
  // ==========================================================================

  io.busy := state =/= State.IDLE
  io.done := False

  // ==========================================================================
  // State Machine Logic
  // ==========================================================================

  switch(state) {
    is(State.IDLE) {
      when(io.start) {
        totalWords := io.wordCount
        baseAddr := io.extAddr
        bankIdx := io.bank
        isSpillReg := io.isSpill
        wordsDone := 0
        burstWordsSent := 0
        when(io.isSpill) {
          // Spill: start by reading first word from bank RAM
          io.bankRdAddr := 0
          io.bankSelect := io.bank
          state := State.SPILL_READ
        }.otherwise {
          // Fill: start by issuing BMB read
          state := State.FILL_CMD
        }
      }
    }

    // ========================================================================
    // Spill Path: bank RAM → external memory
    // ========================================================================

    is(State.SPILL_READ) {
      // Bank RAM has 1-cycle read latency (readAsync from registered address).
      // Read address was set in previous cycle; data is now available.
      // For first word, address was set in IDLE → SPILL_READ transition.
      io.bankRdAddr := wordsDone.resized
      io.bankSelect := bankIdx
      spillData := io.bankRdData
      state := State.SPILL_CMD
    }

    is(State.SPILL_CMD) {
      // Issue single-word BMB write with the data read from bank RAM
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
      io.bmb.cmd.fragment.address := baseAddr + (wordsDone << 2).resized
      io.bmb.cmd.fragment.length := 3
      io.bmb.cmd.last := True
      if (bmbParam.access.canWrite) {
        io.bmb.cmd.fragment.data := spillData
        io.bmb.cmd.fragment.mask := B"1111"
      }
      when(io.bmb.cmd.fire) {
        state := State.SPILL_WAIT
      }
    }

    is(State.SPILL_WAIT) {
      when(io.bmb.rsp.fire) {
        wordsDone := wordsDone + 1
        when(wordsDone === totalWords - 1) {
          state := State.DONE
        }.otherwise {
          // Pre-read next word from bank RAM
          io.bankRdAddr := (wordsDone + 1).resized
          io.bankSelect := bankIdx
          state := State.SPILL_READ
        }
      }
    }

    // ========================================================================
    // Fill Path: external memory → bank RAM
    // ========================================================================

    is(State.FILL_CMD) {
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ

      if (burstLen > 0) {
        // Burst path: issue burst read for burstLen words at a time
        io.bmb.cmd.fragment.address := baseAddr + (wordsDone << 2).resized
        io.bmb.cmd.fragment.length := (burstLen * 4 - 1)
        burstWordsSent := 0
      } else {
        // Single-word path
        io.bmb.cmd.fragment.address := baseAddr + (wordsDone << 2).resized
        io.bmb.cmd.fragment.length := 3
      }

      when(io.bmb.cmd.fire) {
        state := State.FILL_WAIT
      }
    }

    is(State.FILL_WAIT) {
      when(io.bmb.rsp.fire) {
        // Write response data to bank RAM
        io.bankWrAddr := wordsDone.resized
        io.bankWrData := io.bmb.rsp.fragment.data
        io.bankWrEn := True
        io.bankSelect := bankIdx

        if (burstLen > 0) {
          // Burst path: count words in current burst
          val nextBurstWord = burstWordsSent + 1
          burstWordsSent := nextBurstWord
          wordsDone := wordsDone + 1

          when(wordsDone === totalWords - 1) {
            state := State.DONE
          }.elsewhen(nextBurstWord === U(burstLen)) {
            // Burst complete, issue next burst
            state := State.FILL_CMD
          }
          // Otherwise stay in FILL_WAIT for next word of current burst
        } else {
          // Single-word path
          wordsDone := wordsDone + 1
          when(wordsDone === totalWords - 1) {
            state := State.DONE
          }.otherwise {
            state := State.FILL_CMD
          }
        }
      }
    }

    is(State.DONE) {
      io.done := True
      state := State.IDLE
    }
  }
}
