package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * Memory Controller Input from Core
 *
 * Signals from the JOP decode stage that trigger memory operations.
 */
case class MemCtrlInput() extends Bundle {
  val rd        = Bool()    // Memory read (stmra) - uses address register
  val rdc       = Bool()    // Memory read combined (stmrac) - address from TOS
  val rdf       = Bool()    // Memory read field (stmraf) - address from TOS
  val wr        = Bool()    // Memory write (stmwd)
  val wrf       = Bool()    // Memory write field (stmwf)
  val addrWr    = Bool()    // Address write (stmwa)
  val bcRd      = Bool()    // Bytecode read (stbcrd)
  val stidx     = Bool()    // Store index (for array access)
  val iaload    = Bool()    // Array load
  val iastore   = Bool()    // Array store
  val getfield  = Bool()    // Get object field
  val putfield  = Bool()    // Put object field
  val putref    = Bool()    // Put reference (GC barrier)
  val getstatic = Bool()    // Get static field
  val putstatic = Bool()    // Put static field
  val copy      = Bool()    // Copy operation (GC)
  val cinval    = Bool()    // Cache invalidate
  val atmstart  = Bool()    // Atomic start
  val atmend    = Bool()    // Atomic end
  val bcopd     = Bits(16 bits)  // Bytecode operand
}

/**
 * Memory Controller Output to Core
 *
 * Status and data signals returned to the JOP core.
 */
case class MemCtrlOutput() extends Bundle {
  val rdData    = Bits(32 bits)  // Read data result
  val busy      = Bool()          // Memory controller is busy
  val bcStart   = UInt(12 bits)   // Bytecode start address (after fill)
}

/**
 * Bytecode Cache Write Interface
 *
 * Interface for writing bytecodes to the JBC RAM during method load.
 */
case class JbcWritePort(jpcWidth: Int) extends Bundle {
  val addr   = UInt((jpcWidth - 2) bits)  // Word address
  val data   = Bits(32 bits)               // 32-bit word (4 bytes)
  val enable = Bool()                      // Write enable
}

/**
 * JOP Memory Controller with BMB Master Interface
 *
 * Two-layer architecture matching the VHDL mem_sc.vhd:
 *
 * Layer 1 (Combinational): Simple reads (rd/rdc/rdf) and writes (wr/wrf)
 *   drive the BMB bus COMBINATIONALLY using io.aout (TOS) directly for read
 *   addresses. READ_WAIT and WRITE_WAIT states are NOT busy, allowing the
 *   pipeline to continue.
 *
 * Layer 2 (State Machine): Complex operations (bc fill, getfield, putfield,
 *   iaload, iastore, getstatic, putstatic) use a registered state machine
 *   that sets busy=True and stalls the pipeline.
 *
 * @param config Memory configuration
 * @param jpcWidth Java PC width (for bytecode cache addressing)
 */
case class BmbMemoryController(
  config: JopMemoryConfig = JopMemoryConfig(),
  jpcWidth: Int = 11
) extends Component {

  val io = new Bundle {
    // Interface to JOP core
    val memIn     = in(MemCtrlInput())
    val memOut    = out(MemCtrlOutput())

    // Stack values (TOS/NOS) for memory operations
    val aout      = in Bits(32 bits)   // TOS
    val bout      = in Bits(32 bits)   // NOS

    // Bytecode operand (for field/array index)
    val bcopd     = in Bits(16 bits)

    // Bytecode cache write interface
    val jbcWrite  = out(JbcWritePort(jpcWidth))

    // BMB master interface to memory
    val bmb       = master(Bmb(config.bmbParameter))

    // I/O interface (directly exposed, not through BMB)
    val ioAddr    = out UInt(8 bits)
    val ioRd      = out Bool()
    val ioWr      = out Bool()
    val ioWrData  = out Bits(32 bits)
    val ioRdData  = in Bits(32 bits)

    // Debug signals
    val debug = new Bundle {
      val state        = out UInt(4 bits)
      val busy         = out Bool()
      val handleActive = out Bool()
    }
  }

  // ==========================================================================
  // State Machine
  // ==========================================================================

  object State extends SpinalEnum {
    val IDLE,
        // Simple memory access - NOT busy (matching VHDL rd1/wr1)
        READ_WAIT, WRITE_WAIT,
        // Handle dereference (getfield/putfield/iaload/iastore) - busy
        HANDLE_READ, HANDLE_WAIT, HANDLE_CALC, HANDLE_ACCESS, HANDLE_DATA_WAIT,
        // Bytecode cache fill - busy
        BC_READ, BC_WAIT, BC_WRITE,
        // getstatic/putstatic - busy
        GS_READ, PS_WRITE, LAST
      = newElement()
  }

  val state = Reg(State()) init(State.IDLE)

  // ==========================================================================
  // Registers
  // ==========================================================================

  // Address register for memory writes (set by stmwa, used by stmwd)
  val addrReg = Reg(UInt(config.addressWidth bits)) init(0)

  // Read data register (captured from BMB response or I/O read)
  val rdDataReg = Reg(Bits(32 bits)) init(0)

  // Handle operation state
  val handleDataPtr = Reg(UInt(config.addressWidth bits)) init(0)
  val handleIndex = Reg(UInt(16 bits)) init(0)
  val handleIsWrite = Reg(Bool()) init(False)
  val handleIsArray = Reg(Bool()) init(False)
  val handleWriteData = Reg(Bits(32 bits)) init(0)

  // Bytecode fill state
  val bcFillAddr = Reg(UInt(config.addressWidth bits)) init(0)
  val bcFillLen = Reg(UInt(10 bits)) init(0)
  val bcFillCount = Reg(UInt(10 bits)) init(0)
  val bcStartReg = Reg(UInt(12 bits)) init(0)

  // JBC write registers
  val jbcWrAddrReg = Reg(UInt((jpcWidth - 2) bits)) init(0)
  val jbcWrDataReg = Reg(Bits(32 bits)) init(0)
  val jbcWrEnReg = Reg(Bool()) init(False)

  // Value register for three-operand ops (putstatic, putfield, iastore)
  val valueReg = Reg(Bits(32 bits)) init(0)

  // Index register for stidx
  val indexReg = Reg(UInt(config.addressWidth bits)) init(0)
  val wasStidx = Reg(Bool()) init(False)

  // ==========================================================================
  // Busy Signal
  // ==========================================================================
  // Matching VHDL: rd1 and wr1 have state_bsy='0' (NOT busy)
  // Only complex operations (state machine) are busy.

  val notBusy = state === State.IDLE || state === State.READ_WAIT || state === State.WRITE_WAIT
  io.memOut.busy := !notBusy
  io.memOut.rdData := rdDataReg
  io.memOut.bcStart := bcStartReg

  // ==========================================================================
  // BMB Command Defaults (combinational)
  // ==========================================================================

  io.bmb.cmd.valid := False
  io.bmb.cmd.last := True  // Single word transfers
  io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
  io.bmb.cmd.fragment.address := 0
  io.bmb.cmd.fragment.length := 3  // 4 bytes (length = bytes - 1)
  io.bmb.cmd.fragment.source := 0
  io.bmb.cmd.fragment.context := 0
  if(config.bmbParameter.access.canWrite) {
    io.bmb.cmd.fragment.data := 0
    io.bmb.cmd.fragment.mask := B"1111"
  }

  // Always ready for responses
  io.bmb.rsp.ready := True

  // ==========================================================================
  // I/O Interface Defaults
  // ==========================================================================

  io.ioAddr := addrReg(7 downto 0)
  io.ioRd := False
  io.ioWr := False
  io.ioWrData := io.aout

  // ==========================================================================
  // JBC Write Interface
  // ==========================================================================

  io.jbcWrite.addr := jbcWrAddrReg
  io.jbcWrite.data := jbcWrDataReg
  io.jbcWrite.enable := jbcWrEnReg

  // Default: no write this cycle
  jbcWrEnReg := False

  // ==========================================================================
  // Address Decode Helpers
  // ==========================================================================

  val aoutAddr = io.aout(config.addressWidth - 1 downto 0).asUInt
  val aoutIsIo = JopAddressSpace.isIoAddress(aoutAddr, config.addressWidth)
  val addrIsIo = JopAddressSpace.isIoAddress(addrReg, config.addressWidth)

  // Memory read request (any variant)
  val memReadRequested = io.memIn.rd || io.memIn.rdc || io.memIn.rdf

  // ==========================================================================
  // Main State Machine
  // ==========================================================================
  //
  // IDLE, READ_WAIT, WRITE_WAIT: Not busy. Pipeline runs freely.
  //   - Layer 1 combinational commands driven from IDLE state.
  //   - READ_WAIT/WRITE_WAIT handle BMB responses and accept addrWr.
  //
  // All other states: Busy. Pipeline stalled.
  //   - Layer 2 state machine drives BMB commands via registered state.

  switch(state) {

    // ========================================================================
    // IDLE - Accept new operations (Layer 1: Combinational)
    // ========================================================================
    is(State.IDLE) {

      // --- Always-accepted operations (no state transition) ---

      // Address write (stmwa) - latch write address from TOS
      when(io.memIn.addrWr) {
        addrReg := aoutAddr
      }

      // Store index (stidx) - pre-store array/field index from TOS
      when(io.memIn.stidx) {
        indexReg := aoutAddr
        wasStidx := True
      }

      // Capture value for three-operand ops (fires same cycle as operation signal)
      when(io.memIn.iastore || io.memIn.putfield || io.memIn.putstatic) {
        valueReg := io.aout
      }

      // --- Priority chain for operations requiring state transition ---
      // Matches VHDL next_state priority (elsif chain in idl state)

      when(memReadRequested) {
        // Memory read (stmra/stmrac/stmraf) - use io.aout DIRECTLY (combinational)
        when(aoutIsIo) {
          // I/O read - combinational, result available immediately
          io.ioAddr := io.aout(7 downto 0).asUInt
          io.ioRd := True
          rdDataReg := io.ioRdData
        }.otherwise {
          // BMB read - drive command combinationally from io.aout
          io.bmb.cmd.valid := True
          io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
          io.bmb.cmd.fragment.address := (aoutAddr << 2).resized
          state := State.READ_WAIT
        }

      }.elsewhen(io.memIn.wr || io.memIn.wrf) {
        // Memory write (stmwd/stmwf) - use addrReg for address, io.aout for data
        when(addrIsIo) {
          io.ioAddr := addrReg(7 downto 0)
          io.ioWr := True
          io.ioWrData := io.aout
        }.otherwise {
          io.bmb.cmd.valid := True
          io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
          io.bmb.cmd.fragment.address := (addrReg << 2).resized
          if(config.bmbParameter.access.canWrite) {
            io.bmb.cmd.fragment.data := io.aout
          }
          state := State.WRITE_WAIT
        }

      }.elsewhen(io.memIn.putstatic) {
        // Put static field - address from bcopd (or stidx), data already in valueReg
        addrReg := Mux(wasStidx, indexReg, io.bcopd.asUInt.resize(config.addressWidth))
        state := State.PS_WRITE

      }.elsewhen(io.memIn.getstatic) {
        // Get static field - address from bcopd (or stidx)
        addrReg := Mux(wasStidx, indexReg, io.bcopd.asUInt.resize(config.addressWidth))
        state := State.GS_READ

      }.elsewhen(io.memIn.bcRd) {
        // Bytecode cache fill (stbcrd) - TOS has packed start/len
        // Lower 10 bits = length in words, upper bits >> 10 = start word address
        val packedVal = io.aout.asUInt
        bcFillAddr := (packedVal >> 10).resize(config.addressWidth bits)
        bcFillLen := (packedVal & 0x3FF).resize(10 bits)
        bcFillCount := 0
        jbcWrAddrReg := 0
        state := State.BC_READ

      }.elsewhen(io.memIn.iaload) {
        // Array load - NOS = array ref, TOS = index
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := aoutAddr.resized
        indexReg := aoutAddr  // Store index (like stidx)
        handleIsWrite := False
        handleIsArray := True
        state := State.HANDLE_READ

      }.elsewhen(io.memIn.getfield) {
        // Get object field - TOS = object ref, bcopd = field index
        addrReg := aoutAddr
        handleIndex := Mux(wasStidx, indexReg.resize(16), io.bcopd(15 downto 0).asUInt)
        handleIsWrite := False
        handleIsArray := False
        state := State.HANDLE_READ

      }.elsewhen(io.memIn.putfield) {
        // Put object field - NOS = object ref, bcopd = field index, value in valueReg
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := Mux(wasStidx, indexReg.resize(16), io.bcopd(15 downto 0).asUInt)
        handleIsWrite := True
        handleIsArray := False
        handleWriteData := io.aout  // TOS = value (also captured to valueReg above)
        state := State.HANDLE_READ

      }.elsewhen(io.memIn.iastore) {
        // Array store - NOS = array ref, TOS = index
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := aoutAddr.resized
        handleIsWrite := True
        handleIsArray := True
        state := State.HANDLE_READ
      }
    }

    // ========================================================================
    // Simple Memory Access States (NOT busy - pipeline continues)
    // ========================================================================

    is(State.READ_WAIT) {
      // Accept address writes while waiting (matching VHDL addr_next behavior)
      when(io.memIn.addrWr) {
        addrReg := aoutAddr
      }

      // Capture BMB response data
      when(io.bmb.rsp.fire) {
        rdDataReg := io.bmb.rsp.fragment.data
        state := State.IDLE
      }
    }

    is(State.WRITE_WAIT) {
      // Accept address writes while waiting
      when(io.memIn.addrWr) {
        addrReg := aoutAddr
      }

      when(io.bmb.rsp.fire) {
        state := State.IDLE
      }
    }

    // ========================================================================
    // Bytecode Cache Fill States (busy)
    // ========================================================================

    is(State.BC_READ) {
      // Issue BMB read for method bytecode word
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (bcFillAddr << 2).resized
      when(io.bmb.cmd.fire) {
        state := State.BC_WAIT
      }
    }

    is(State.BC_WAIT) {
      when(io.bmb.rsp.fire) {
        // Byte-swap for JBC RAM (big-endian to little-endian byte order)
        val word = io.bmb.rsp.fragment.data
        jbcWrDataReg := word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
        state := State.BC_WRITE
      }
    }

    is(State.BC_WRITE) {
      // Write word to JBC RAM
      jbcWrEnReg := True
      // Use current fill count as write address (both are Regs, so they
      // take effect at the same edge - the address must be the CURRENT
      // count, not incremented, to match the write enable timing)
      jbcWrAddrReg := bcFillCount.resized

      val wordsWritten = bcFillCount + 1

      when(wordsWritten >= bcFillLen) {
        // Done filling - bytecodes loaded at JBC address 0
        bcStartReg := 0
        state := State.IDLE
      }.otherwise {
        // More words to read
        bcFillCount := wordsWritten
        bcFillAddr := bcFillAddr + 1
        state := State.BC_READ
      }
    }

    // ========================================================================
    // Handle Dereference States (getfield/putfield/iaload/iastore) - busy
    // ========================================================================

    is(State.HANDLE_READ) {
      // Issue read to dereference handle
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (addrReg << 2).resized
      when(io.bmb.cmd.fire) {
        state := State.HANDLE_WAIT
      }
    }

    is(State.HANDLE_WAIT) {
      when(io.bmb.rsp.fire) {
        // Got data pointer from handle
        handleDataPtr := io.bmb.rsp.fragment.data(config.addressWidth - 1 downto 0).asUInt
        state := State.HANDLE_CALC
      }
    }

    is(State.HANDLE_CALC) {
      // Calculate field/element address
      // Both arrays and objects use data_ptr + index (matching VHDL mem_sc.vhd)
      // In JOP, array length is stored in the handle (handle[1]), NOT at
      // data_ptr[0]. So array elements start directly at data_ptr[0].
      addrReg := handleDataPtr + handleIndex.resized
      state := State.HANDLE_ACCESS
    }

    is(State.HANDLE_ACCESS) {
      // Issue read or write to field/element
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.address := (addrReg << 2).resized
      when(handleIsWrite) {
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
        if(config.bmbParameter.access.canWrite) {
          io.bmb.cmd.fragment.data := handleWriteData
        }
      }.otherwise {
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      }
      when(io.bmb.cmd.fire) {
        state := State.HANDLE_DATA_WAIT
      }
    }

    is(State.HANDLE_DATA_WAIT) {
      when(io.bmb.rsp.fire) {
        when(!handleIsWrite) {
          rdDataReg := io.bmb.rsp.fragment.data
        }
        wasStidx := False  // Reset stidx marker after handle operation
        state := State.IDLE
      }
    }

    // ========================================================================
    // getstatic / putstatic States (busy)
    // ========================================================================

    is(State.GS_READ) {
      // Issue read from address in addrReg (set from bcopd/index)
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (addrReg << 2).resized
      when(io.bmb.cmd.fire) {
        state := State.LAST
      }
    }

    is(State.PS_WRITE) {
      // Issue write to address in addrReg with data from valueReg
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
      io.bmb.cmd.fragment.address := (addrReg << 2).resized
      if(config.bmbParameter.access.canWrite) {
        io.bmb.cmd.fragment.data := valueReg
      }
      when(io.bmb.cmd.fire) {
        state := State.LAST
      }
    }

    is(State.LAST) {
      // Wait for response to complete
      when(io.bmb.rsp.fire) {
        rdDataReg := io.bmb.rsp.fragment.data  // Capture for getstatic
        wasStidx := False  // Reset stidx marker
        state := State.IDLE
      }
    }
  }

  // ==========================================================================
  // Debug Outputs
  // ==========================================================================

  io.debug.state := state.asBits.asUInt.resized
  io.debug.busy := !notBusy
  io.debug.handleActive := state.mux(
    State.HANDLE_READ -> True,
    State.HANDLE_WAIT -> True,
    State.HANDLE_CALC -> True,
    State.HANDLE_ACCESS -> True,
    State.HANDLE_DATA_WAIT -> True,
    default -> False
  )
}

/**
 * Generate Verilog for BmbMemoryController
 */
object BmbMemoryControllerVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated"
  ).generate(BmbMemoryController())
}
