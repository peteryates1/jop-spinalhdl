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
  val rd        = Bool()    // Memory read (stmra)
  val wr        = Bool()    // Memory write (stmwd)
  val addrWr    = Bool()    // Address write (stmwa)
  val bcRd      = Bool()    // Bytecode read (stbcrd)
  val stidx     = Bool()    // Store index (for array access)
  val iaload    = Bool()    // Array load
  val iastore   = Bool()    // Array store
  val getfield  = Bool()    // Get object field
  val putfield  = Bool()    // Put object field
  val getstatic = Bool()    // Get static field
  val putstatic = Bool()    // Put static field
  val copy      = Bool()    // Copy operation (GC)
  val cinval    = Bool()    // Cache invalidate
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
 * This component handles all memory operations for the JOP processor:
 * - Simple read/write (stmra/stmwd)
 * - Handle dereference for getfield/putfield/iaload/iastore
 * - Bytecode cache fill (stbcrd)
 * - Address decode (main memory, scratch, I/O)
 *
 * The controller presents a BMB master interface for memory access,
 * allowing connection to various backends (BRAM, SDRAM, etc.)
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
        // Simple memory access
        READ_WAIT, WRITE_WAIT,
        // Handle dereference (getfield/putfield/iaload/iastore)
        HANDLE_READ, HANDLE_WAIT, HANDLE_CALC, HANDLE_ACCESS, HANDLE_DATA_WAIT, HANDLE_DONE,
        // Bytecode cache fill
        BC_READ, BC_WAIT, BC_WRITE
      = newElement()
  }

  val state = Reg(State()) init(State.IDLE)

  // ==========================================================================
  // Registers
  // ==========================================================================

  // Address register for memory operations
  val addrReg = Reg(UInt(config.addressWidth bits)) init(0)

  // Read data register
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

  // Write data for BMB command
  val bmbWriteData = Reg(Bits(32 bits)) init(0)

  // ==========================================================================
  // BMB Command/Response Logic
  // ==========================================================================

  // Registered command signals for proper handshaking
  val cmdValid = Reg(Bool()) init(False)
  val cmdOpcode = Reg(Bits(1 bits)) init(Bmb.Cmd.Opcode.READ)
  val cmdAddress = Reg(UInt(config.addressWidth + 2 bits)) init(0)

  // Clear valid when command is accepted
  when(io.bmb.cmd.fire) {
    cmdValid := False
  }

  // BMB command outputs
  io.bmb.cmd.valid := cmdValid
  io.bmb.cmd.last := True  // Single word transfers
  io.bmb.cmd.fragment.opcode := cmdOpcode
  io.bmb.cmd.fragment.address := cmdAddress.resized
  io.bmb.cmd.fragment.length := 3  // 4 bytes (length = bytes - 1)
  io.bmb.cmd.fragment.source := 0
  io.bmb.cmd.fragment.context := 0
  if(config.bmbParameter.access.canWrite) {
    io.bmb.cmd.fragment.data := bmbWriteData
    io.bmb.cmd.fragment.mask := B"1111"
  }

  // Always ready for responses
  io.bmb.rsp.ready := True

  // ==========================================================================
  // I/O Interface Logic
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

  // Default: no write
  jbcWrEnReg := False

  // ==========================================================================
  // Main State Machine
  // ==========================================================================

  val busy = state =/= State.IDLE
  io.memOut.busy := busy
  io.memOut.rdData := rdDataReg
  io.memOut.bcStart := bcStartReg

  // Address decode helpers
  val addrIsIo = JopAddressSpace.isIoAddress(addrReg, config.addressWidth)

  switch(state) {
    is(State.IDLE) {
      // Address write - just capture address
      when(io.memIn.addrWr) {
        addrReg := io.aout(config.addressWidth - 1 downto 0).asUInt
      }

      // Simple memory read
      when(io.memIn.rd) {
        when(addrIsIo) {
          io.ioRd := True
          rdDataReg := io.ioRdData  // Combinational for I/O
        }.otherwise {
          // Issue BMB read
          cmdValid := True
          cmdOpcode := Bmb.Cmd.Opcode.READ
          cmdAddress := (addrReg << 2).resized
          state := State.READ_WAIT
        }
      }

      // Simple memory write
      when(io.memIn.wr) {
        bmbWriteData := io.aout
        when(addrIsIo) {
          io.ioWr := True
        }.otherwise {
          // Issue BMB write
          cmdValid := True
          cmdOpcode := Bmb.Cmd.Opcode.WRITE
          cmdAddress := (addrReg << 2).resized
          state := State.WRITE_WAIT
        }
      }

      // getfield - start handle dereference
      when(io.memIn.getfield) {
        addrReg := io.aout(config.addressWidth - 1 downto 0).asUInt  // Object reference
        handleIndex := io.bcopd(15 downto 0).asUInt
        handleIsWrite := False
        handleIsArray := False
        state := State.HANDLE_READ
      }

      // putfield - start handle dereference
      when(io.memIn.putfield) {
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt  // Object reference (NOS)
        handleIndex := io.bcopd(15 downto 0).asUInt
        handleIsWrite := True
        handleIsArray := False
        handleWriteData := io.aout  // Value to write (TOS)
        state := State.HANDLE_READ
      }

      // iaload - array load
      when(io.memIn.iaload) {
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt  // Array reference (NOS)
        handleIndex := io.aout(15 downto 0).asUInt  // Index (TOS)
        handleIsWrite := False
        handleIsArray := True
        state := State.HANDLE_READ
      }

      // iastore - array store (index already stored via stidx)
      when(io.memIn.iastore) {
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt  // Array reference
        handleIndex := io.aout(15 downto 0).asUInt  // Stored index
        handleIsWrite := True
        handleIsArray := True
        // Note: write data will come from next TOS value
        state := State.HANDLE_READ
      }

      // Bytecode cache fill
      when(io.memIn.bcRd) {
        bcFillAddr := io.aout(config.addressWidth - 1 downto 0).asUInt
        // Length is in bcopd (in bytes), convert to words
        bcFillLen := (io.bcopd(11 downto 0).asUInt >> 2).resized
        bcFillCount := 0
        jbcWrAddrReg := 0
        state := State.BC_READ
      }
    }

    // ========================================================================
    // Simple Memory Access States
    // ========================================================================

    is(State.READ_WAIT) {
      when(io.bmb.rsp.fire) {
        rdDataReg := io.bmb.rsp.fragment.data
        state := State.IDLE
      }
    }

    is(State.WRITE_WAIT) {
      when(io.bmb.rsp.fire) {
        state := State.IDLE
      }
    }

    // ========================================================================
    // Handle Dereference States (getfield/putfield/iaload/iastore)
    // ========================================================================

    is(State.HANDLE_READ) {
      // Issue read to dereference handle
      cmdValid := True
      cmdOpcode := Bmb.Cmd.Opcode.READ
      cmdAddress := (addrReg << 2).resized
      state := State.HANDLE_WAIT
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
      when(handleIsArray) {
        // Array: data_ptr + 1 (skip length) + index
        addrReg := handleDataPtr + 1 + handleIndex.resized
      }.otherwise {
        // Object field: data_ptr + field_index
        addrReg := handleDataPtr + handleIndex.resized
      }
      state := State.HANDLE_ACCESS
    }

    is(State.HANDLE_ACCESS) {
      // Issue read or write to field/element
      cmdValid := True
      cmdAddress := (addrReg << 2).resized
      bmbWriteData := handleWriteData
      when(handleIsWrite) {
        cmdOpcode := Bmb.Cmd.Opcode.WRITE
      }.otherwise {
        cmdOpcode := Bmb.Cmd.Opcode.READ
      }
      state := State.HANDLE_DATA_WAIT
    }

    is(State.HANDLE_DATA_WAIT) {
      when(io.bmb.rsp.fire) {
        when(!handleIsWrite) {
          rdDataReg := io.bmb.rsp.fragment.data
        }
        state := State.HANDLE_DONE
      }
    }

    is(State.HANDLE_DONE) {
      // Allow one cycle for microcode to read result via ldmrd
      state := State.IDLE
    }

    // ========================================================================
    // Bytecode Cache Fill States
    // ========================================================================

    is(State.BC_READ) {
      // Issue read for method bytecode word
      cmdValid := True
      cmdOpcode := Bmb.Cmd.Opcode.READ
      cmdAddress := (bcFillAddr << 2).resized
      state := State.BC_WAIT
    }

    is(State.BC_WAIT) {
      when(io.bmb.rsp.fire) {
        // Got bytecode word - byte-swap for JBC RAM (big-endian to little-endian)
        val word = io.bmb.rsp.fragment.data
        jbcWrDataReg := word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
        state := State.BC_WRITE
      }
    }

    is(State.BC_WRITE) {
      // Write word to JBC RAM
      jbcWrEnReg := True

      val wordsWritten = bcFillCount + 1

      when(wordsWritten >= bcFillLen) {
        // Done filling
        bcStartReg := 0  // Bytecodes loaded at JBC address 0
        state := State.IDLE
      }.otherwise {
        // More words to read
        bcFillCount := wordsWritten
        bcFillAddr := bcFillAddr + 1
        jbcWrAddrReg := jbcWrAddrReg + 1
        state := State.BC_READ
      }
    }
  }

  // ==========================================================================
  // Debug Outputs
  // ==========================================================================

  io.debug.state := state.asBits.asUInt.resized
  io.debug.busy := busy
  io.debug.handleActive := state.mux(
    State.HANDLE_READ -> True,
    State.HANDLE_WAIT -> True,
    State.HANDLE_CALC -> True,
    State.HANDLE_ACCESS -> True,
    State.HANDLE_DATA_WAIT -> True,
    State.HANDLE_DONE -> True,
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
