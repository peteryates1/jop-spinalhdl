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
 *   addresses. READ_WAIT and WRITE_WAIT are busy only while the BMB response
 *   has not arrived (rsp.valid=False). For BRAM this is never busy (1-cycle
 *   response). For SDRAM this stalls the pipeline until data is ready.
 *   Commands are retried if not immediately accepted (cmdAccepted tracking).
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
  jpcWidth: Int = 11,
  blockBits: Int = 4
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
        // Bytecode cache check and pipelined fill - busy
        BC_CACHE_CHECK, BC_FILL_R1, BC_FILL_LOOP, BC_FILL_CMD,
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

  // Method cache: JBC word address base for current method's cache block
  val bcCacheStartReg = Reg(UInt((jpcWidth - 2) bits)) init(0)

  // Command tracking for BMB handshake (hold cmd until accepted)
  // SDRAM controllers may not accept commands immediately (cmd.ready=False).
  // We register the command parameters and keep asserting cmd.valid until cmd.fire.
  val cmdAccepted = Reg(Bool()) init(True)
  val pendingCmdAddr = Reg(UInt(config.bmbParameter.access.addressWidth bits)) init(0)
  val pendingCmdData = Reg(Bits(32 bits)) init(0)
  val pendingCmdIsWrite = Reg(Bool()) init(False)

  // ==========================================================================
  // Busy Signal
  // ==========================================================================
  //
  // Matches VHDL mem_sc.vhd busy signal:
  //
  //   VHDL: mem_out.bsy <= '0';
  //         if sc_mem_in.rdy_cnt=3 then bsy <= '1';
  //         elsif state_bsy='1' and state/=ialrb/last/gf4 then bsy <= '1';
  //
  //   - state_bsy='0' for idl/rd1/wr1/last → pipeline NOT stalled
  //   - state_bsy='1' for complex states → pipeline stalled
  //   - rdy_cnt=3 independently forces bsy='1' (CAS-3 SDRAM only)
  //
  //   In VHDL, rdy_cnt is a 2-bit saturating countdown from the SimpCon
  //   slave: stays at 3 while >3 cycles remain, then counts 2,1,0.
  //   Pipeline stalls only when rdy_cnt=3, giving 3 free cycles before
  //   data arrives. For BRAM (rdy_cnt=1), no stall at all.
  //
  //   BMB equivalent: we don't have advance rdy_cnt from the slave, so
  //   we conservatively stall whenever the response hasn't arrived. This
  //   is correct because WAIT always precedes ldmrd in the microcode.
  //   For BRAM: response arrives same cycle as READ_WAIT → 0 stall cycles.
  //   For SDRAM: stalls until response → correct but slightly more
  //   conservative than VHDL (which allows 2-3 free cycles via rdy_cnt).
  //
  //   - IDLE: never busy
  //   - READ_WAIT/WRITE_WAIT: busy until response arrives
  //   - Complex states (BC fill, handle, getstatic, putstatic): busy

  val notBusy = (state === State.IDLE) ||
    ((state === State.READ_WAIT || state === State.WRITE_WAIT) &&
      io.bmb.rsp.valid)
  io.memOut.busy := !notBusy

  // Read data: combinational pass-through when BMB response fires in
  // READ_WAIT. Matches VHDL's combinational mem_out.dout, ensuring
  // rdData is available in the same cycle the response arrives.
  io.memOut.rdData := rdDataReg
  when(io.bmb.rsp.fire && state === State.READ_WAIT) {
    io.memOut.rdData := io.bmb.rsp.fragment.data
  }

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
  // Method Cache
  // ==========================================================================

  val methodCache = MethodCache(jpcWidth, blockBits)

  // Wire method cache inputs from registered bcFill state
  // bcFillAddr is captured from TOS when bcRd fires; bits 17:0 are the method address tag
  methodCache.io.bcAddr := bcFillAddr(17 downto 0)
  methodCache.io.bcLen := bcFillLen

  // Combinational find trigger: asserted in IDLE when bcRd fires,
  // processed on the same clock edge by both state machines (matching VHDL
  // where find is directly wired to mem_in.bc_rd)
  val mcacheFind = Bool()
  mcacheFind := False
  methodCache.io.find := mcacheFind

  // ==========================================================================
  // Main State Machine
  // ==========================================================================
  //
  // IDLE: Not busy. READ_WAIT/WRITE_WAIT: Busy until rsp.valid.
  //   - Layer 1 combinational commands driven from IDLE state.
  //   - READ_WAIT/WRITE_WAIT handle BMB responses, accept addrWr, and
  //     process I/O operations. Commands retried if not accepted in IDLE.
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
          // Register command for retry if not immediately accepted
          pendingCmdAddr := (aoutAddr << 2).resized
          pendingCmdIsWrite := False
          cmdAccepted := io.bmb.cmd.ready
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
          // Register command for retry if not immediately accepted
          pendingCmdAddr := (addrReg << 2).resized
          pendingCmdData := io.aout
          pendingCmdIsWrite := True
          cmdAccepted := io.bmb.cmd.ready
          state := State.WRITE_WAIT
        }

      }.elsewhen(io.memIn.putstatic) {
        // Put static field - address from bcopd (or stidx), data already in valueReg
        addrReg := Mux(wasStidx, indexReg, io.bcopd.asUInt.resize(config.addressWidth))
        state := State.PS_WRITE
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.getstatic) {
        // Get static field - address from bcopd (or stidx)
        addrReg := Mux(wasStidx, indexReg, io.bcopd.asUInt.resize(config.addressWidth))
        state := State.GS_READ
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.bcRd) {
        // Bytecode cache fill (stbcrd) - TOS has packed start/len
        // Lower 10 bits = length in words, upper bits >> 10 = start word address
        val packedVal = io.aout.asUInt
        bcFillAddr := (packedVal >> 10).resize(config.addressWidth bits)
        bcFillLen := (packedVal & 0x3FF).resize(10 bits)
        bcFillCount := 0
        // Trigger method cache lookup (combinational, same clock edge)
        mcacheFind := True
        state := State.BC_CACHE_CHECK

      }.elsewhen(io.memIn.iaload) {
        // Array load - NOS = array ref, TOS = index
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := aoutAddr.resized
        indexReg := aoutAddr  // Store index (like stidx)
        handleIsWrite := False
        handleIsArray := True
        state := State.HANDLE_READ
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.getfield) {
        // Get object field - TOS = object ref, bcopd = field index
        addrReg := aoutAddr
        handleIndex := Mux(wasStidx, indexReg.resize(16), io.bcopd(15 downto 0).asUInt)
        handleIsWrite := False
        handleIsArray := False
        state := State.HANDLE_READ
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.putfield) {
        // Put object field - NOS = object ref, bcopd = field index, value in valueReg
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := Mux(wasStidx, indexReg.resize(16), io.bcopd(15 downto 0).asUInt)
        handleIsWrite := True
        handleIsArray := False
        handleWriteData := io.aout  // TOS = value (also captured to valueReg above)
        state := State.HANDLE_READ
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.iastore) {
        // Array store - NOS = array ref, TOS = index
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIndex := aoutAddr.resized
        handleIsWrite := True
        handleIsArray := True
        state := State.HANDLE_READ
        // (no extra busy needed - matches VHDL)
      }
    }

    // ========================================================================
    // Simple Memory Access States (NOT busy - pipeline continues)
    // ========================================================================

    is(State.READ_WAIT) {
      // Accept address writes while waiting (pipeline is not stalled)
      when(io.memIn.addrWr) {
        addrReg := aoutAddr
      }

      // Handle I/O reads while waiting for BMB response
      when(memReadRequested && aoutIsIo) {
        io.ioAddr := io.aout(7 downto 0).asUInt
        io.ioRd := True
        rdDataReg := io.ioRdData
      }

      // Handle I/O writes while waiting for BMB response
      when((io.memIn.wr || io.memIn.wrf) && addrIsIo) {
        io.ioAddr := addrReg(7 downto 0)
        io.ioWr := True
        io.ioWrData := io.aout
      }

      // Keep driving command until accepted by BMB slave
      when(!cmdAccepted) {
        io.bmb.cmd.valid := True
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
        io.bmb.cmd.fragment.address := pendingCmdAddr
        when(io.bmb.cmd.fire) {
          cmdAccepted := True
        }
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

      // Handle I/O reads while waiting for BMB response
      when(memReadRequested && aoutIsIo) {
        io.ioAddr := io.aout(7 downto 0).asUInt
        io.ioRd := True
        rdDataReg := io.ioRdData
      }

      // Handle I/O writes while waiting for BMB response
      when((io.memIn.wr || io.memIn.wrf) && addrIsIo) {
        io.ioAddr := addrReg(7 downto 0)
        io.ioWr := True
        io.ioWrData := io.aout
      }

      // Keep driving command until accepted by BMB slave
      when(!cmdAccepted) {
        io.bmb.cmd.valid := True
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.WRITE
        io.bmb.cmd.fragment.address := pendingCmdAddr
        if(config.bmbParameter.access.canWrite) {
          io.bmb.cmd.fragment.data := pendingCmdData
        }
        when(io.bmb.cmd.fire) {
          cmdAccepted := True
        }
      }

      when(io.bmb.rsp.fire) {
        state := State.IDLE
      }
    }

    // ========================================================================
    // Bytecode Cache Check (busy) - wait for method cache tag lookup
    // ========================================================================

    is(State.BC_CACHE_CHECK) {
      when(methodCache.io.rdy) {
        // Cache lookup complete — capture block address
        bcCacheStartReg := methodCache.io.bcStart
        bcStartReg := (methodCache.io.bcStart ## U(0, 2 bits)).asUInt.resized  // byte address for CPU

        when(methodCache.io.inCache) {
          // HIT: method already in JBC RAM, skip fill entirely
          state := State.IDLE
        }.otherwise {
          // MISS: fill method into the assigned cache block
          state := State.BC_FILL_R1
        }
      }
    }

    // ========================================================================
    // Bytecode Cache Fill States (pipelined, busy)
    //
    // Matches VHDL mem_sc.vhd "pipeline level 2": issues the next memory
    // read while writing the previous word to JBC, overlapping memory
    // latency with the JBC write cycle.
    //
    // BC_FILL_R1:   Issue first BMB read command
    // BC_FILL_LOOP: On response, register JBC write + issue next read
    // BC_FILL_CMD:  Retry read command if not accepted in BC_FILL_LOOP
    // ========================================================================

    is(State.BC_FILL_R1) {
      // Issue first BMB read for method bytecode word
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (bcFillAddr << 2).resized
      when(io.bmb.cmd.fire) {
        bcFillAddr := bcFillAddr + 1
        state := State.BC_FILL_LOOP
      }
    }

    is(State.BC_FILL_LOOP) {
      when(io.bmb.rsp.fire) {
        // Byte-swap for JBC RAM and register write (actual write happens next cycle)
        val word = io.bmb.rsp.fragment.data
        jbcWrDataReg := word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
        jbcWrEnReg := True
        jbcWrAddrReg := (bcCacheStartReg + bcFillCount).resized

        val wordsWritten = bcFillCount + 1
        when(wordsWritten >= bcFillLen) {
          // Last word — JBC write happens next cycle while back in IDLE
          state := State.IDLE
        }.otherwise {
          // More words: try to issue next read in same cycle
          bcFillCount := wordsWritten
          io.bmb.cmd.valid := True
          io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
          io.bmb.cmd.fragment.address := (bcFillAddr << 2).resized
          when(io.bmb.cmd.fire) {
            bcFillAddr := bcFillAddr + 1
            // Stay in BC_FILL_LOOP — next response will arrive
          }.otherwise {
            state := State.BC_FILL_CMD  // Retry cmd next cycle
          }
        }
      }
    }

    is(State.BC_FILL_CMD) {
      // Issue next read command
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (bcFillAddr << 2).resized
      when(io.bmb.cmd.fire) {
        bcFillAddr := bcFillAddr + 1
        state := State.BC_FILL_LOOP
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
