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
      val state        = out UInt(5 bits)
      val busy         = out Bool()
      val handleActive = out Bool()
      val addrReg      = out UInt(config.addressWidth bits)
      val rdDataReg    = out Bits(32 bits)
    }

    // Snoop bus for cross-core cache invalidation (only when caches are enabled)
    val snoopOut = if (config.useAcache || config.useOcache) {
      val maxIdxBits = config.acacheMaxIndexBits.max(config.ocacheMaxIndexBits)
      Some(out(CacheSnoopOut(config.addressWidth, maxIdxBits)))
    } else None
    val snoopIn = if (config.useAcache || config.useOcache) {
      val maxIdxBits = config.acacheMaxIndexBits.max(config.ocacheMaxIndexBits)
      Some(in(CacheSnoopIn(config.addressWidth, maxIdxBits)))
    } else None
  }

  // ==========================================================================
  // State Machine
  // ==========================================================================

  object State extends SpinalEnum {
    val IDLE,
        // Simple memory access - NOT busy (matching VHDL rd1/wr1)
        READ_WAIT, WRITE_WAIT,
        // Array store wait (matching VHDL iast0) - 1 cycle for stack pop
        IAST_WAIT,
        // Putfield operand wait (matching VHDL pf0 "waste cycle to get opd in ok position")
        PF_WAIT,
        // Handle dereference (getfield/putfield/iaload/iastore) - busy
        HANDLE_READ, HANDLE_WAIT, HANDLE_CALC, HANDLE_ACCESS, HANDLE_DATA_WAIT,
        // Array bounds check (matching VHDL ialrb/iasrb) - busy
        HANDLE_BOUND_READ, HANDLE_BOUND_WAIT,
        // Exception states (matching VHDL npexc/abexc) - busy
        NP_EXC, AB_EXC,
        // Bytecode cache check and pipelined fill - busy
        BC_CACHE_CHECK, BC_FILL_R1, BC_FILL_LOOP, BC_FILL_CMD,
        // Array cache line fill (iaload miss with A$ enabled) - busy
        AC_FILL_CMD, AC_FILL_WAIT,
        // GC copy states (matching VHDL cp0-cpstop) - busy
        CP_SETUP, CP_READ, CP_READ_WAIT, CP_WRITE, CP_STOP,
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
  val handleIndex = Reg(UInt(config.addressWidth bits)) init(0)
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

  // GC copy / address translation state (matching VHDL base_reg, pos_reg, offset_reg, cp_stopbit)
  val baseReg = Reg(UInt(config.addressWidth bits)) init(0)
  val posReg = Reg(UInt(config.addressWidth bits)) init(0)
  val offsetReg = Reg(UInt(config.addressWidth bits)) init(0)
  val cpStopBit = Reg(Bool()) init(False)

  // Command tracking for BMB handshake (hold cmd until accepted)
  // SDRAM controllers may not accept commands immediately (cmd.ready=False).
  // We register the command parameters and keep asserting cmd.valid until cmd.fire.
  val cmdAccepted = Reg(Bool()) init(True)
  val pendingCmdAddr = Reg(UInt(config.bmbParameter.access.addressWidth bits)) init(0)
  val pendingCmdData = Reg(Bits(32 bits)) init(0)
  val pendingCmdIsWrite = Reg(Bool()) init(False)

  // Object cache state (matching VHDL read_ocache, was_a_hwo signals)
  val readObjectCache = if(config.useOcache) Reg(Bool()) init(False) else null
  val ocWasGetfield = if(config.useOcache) Reg(Bool()) init(False) else null
  val wasHwo = if(config.useOcache) Reg(Bool()) init(False) else null
  val handleAddrReg = if(config.useOcache) Reg(UInt(config.addressWidth bits)) init(0) else null

  // Snoop handle register: captures the original handle address before
  // HANDLE_CALC overwrites addrReg with (data_ptr + index).
  val snoopHandleReg = if(config.useAcache || config.useOcache) {
    Reg(UInt(config.addressWidth bits)) init(0)
  } else null

  // Array cache state
  val readArrayCache = if(config.useAcache) Reg(Bool()) init(False) else null
  val acFillAddr = if(config.useAcache) Reg(UInt(config.addressWidth bits)) init(0) else null
  val acFillCount = if(config.useAcache) Reg(UInt(config.acacheFieldBits bits)) init(0) else null
  val acFillRequestedIdx = if(config.useAcache) Reg(UInt(config.acacheFieldBits bits)) init(0) else null

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

  // VHDL: npexc/abexc do NOT set state_bsy — pipeline continues executing
  // putfield's cleanup microcode (nop opd, pop) while exception fires.
  val notBusy = (state === State.IDLE) ||
    ((state === State.READ_WAIT || state === State.WRITE_WAIT) &&
      io.bmb.rsp.valid) ||
    (state === State.NP_EXC) ||
    (state === State.AB_EXC)
  io.memOut.busy := !notBusy

  // Read data output MUX (base assignment; objectCache override added after instantiation)
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

  // Snoop output defaults
  io.snoopOut.foreach { s =>
    s.valid   := False
    s.isArray := False
    s.handle  := 0
    s.index   := 0
  }

  // ==========================================================================
  // Address Decode Helpers
  // ==========================================================================

  val aoutAddr = io.aout(config.addressWidth - 1 downto 0).asUInt
  val aoutIsIo = JopAddressSpace.isIoAddress(aoutAddr, config.addressWidth)
  val addrIsIo = JopAddressSpace.isIoAddress(addrReg, config.addressWidth)

  // Memory read request (any variant)
  val memReadRequested = io.memIn.rd || io.memIn.rdc || io.memIn.rdf

  // Address translation for GC copy (matching VHDL ram_addr MUX translation).
  // During copy, accesses to addresses in [baseReg, posReg) are redirected
  // by adding offsetReg (= dest - src), so partially-copied objects are
  // accessible at their new location.
  //
  // Currently unused: for single-core stop-the-world GC, the copy loop
  // (GC.java: `for (i=0; i<size; i++) Native.memCopy(...)`) executes only
  // stcp microcode + stack ops (iinc, iload, if_icmplt) between calls —
  // no field accesses or memory reads reach IDLE state during copying.
  // Concurrent or multi-core GC would need translateAddr applied to
  // IDLE-state reads (memReadRequested) and writes (wr/wrf).
  def translateAddr(addr: UInt): UInt = {
    val inRange = addr >= baseReg && addr < posReg
    Mux(inRange, addr + offsetReg, addr)
  }

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
  // Object Cache (matching VHDL ocache in mem_sc.vhd)
  // ==========================================================================

  val objectCache = if(config.useOcache) {
    val oc = ObjectCache(
      addrBits = config.addressWidth,
      wayBits = config.ocacheWayBits,
      indexBits = config.ocacheIndexBits,
      maxIndexBits = config.ocacheMaxIndexBits
    )

    // Default wiring (matching VHDL combinational wiring in mem_sc.vhd)
    oc.io.handle := aoutAddr
    oc.io.fieldIdx := io.bcopd(config.ocacheMaxIndexBits - 1 downto 0).asUInt
    oc.io.chkGf := io.memIn.getfield && !wasStidx
    oc.io.chkPf := False  // Driven from HANDLE_READ for putfield
    oc.io.gfVal := io.bmb.rsp.fragment.data  // Memory read data
    oc.io.pfVal := handleWriteData             // Value being written
    oc.io.inval := io.memIn.stidx || io.memIn.cinval
    oc.io.wrGf := False   // Driven from HANDLE_DATA_WAIT
    oc.io.wrPf := False   // Driven from HANDLE_DATA_WAIT

    Some(oc)
  } else None

  // ==========================================================================
  // Array Cache (matching VHDL acache in cache/acache.vhd)
  // ==========================================================================

  val arrayCache = if(config.useAcache) {
    val ac = ArrayCache(
      addrBits = config.addressWidth,
      wayBits = config.acacheWayBits,
      fieldBits = config.acacheFieldBits,
      maxIndexBits = config.acacheMaxIndexBits
    )

    // Default wiring: handle from NOS (bout), index from TOS (aout)
    // This is correct for both IDLE iaload and IAST_WAIT iastore
    ac.io.handle := io.bout(config.addressWidth - 1 downto 0).asUInt
    ac.io.index := aoutAddr.resize(config.acacheMaxIndexBits)
    ac.io.chkIal := io.memIn.iaload  // Combinational check in IDLE
    ac.io.chkIas := False            // Driven from IAST_WAIT
    ac.io.ialVal := io.bmb.rsp.fragment.data  // Memory read data for fill
    ac.io.iasVal := handleWriteData            // Value being written for iastore
    ac.io.inval := io.memIn.stidx || io.memIn.cinval
    ac.io.wrIal := False   // Driven from AC_FILL_WAIT
    ac.io.wrIas := False   // Driven from HANDLE_DATA_WAIT

    Some(ac)
  } else None

  // Array cache output MUX override
  if(config.useAcache) {
    when(readArrayCache) {
      io.memOut.rdData := arrayCache.get.io.dout
    }
  }

  // readArrayCache: Clear on any new memory operation OR when state leaves IDLE.
  // The iaload hit handler re-sets it via "last assignment wins" for hits.
  // Without this, readArrayCache persists across I/O reads in IDLE (which don't
  // change state), causing A$ dout to override the I/O read result.
  if(config.useAcache) {
    when(state =/= State.IDLE) {
      readArrayCache := False
    }
    when(state === State.IDLE && (memReadRequested || io.memIn.wr || io.memIn.wrf ||
        io.memIn.getfield || io.memIn.putfield || io.memIn.iaload || io.memIn.iastore ||
        io.memIn.bcRd || io.memIn.getstatic || io.memIn.putstatic || io.memIn.copy)) {
      readArrayCache := False
    }
  }

  // Object cache output MUX override (must be after objectCache instantiation)
  // On a cache hit, readObjectCache selects cached data instead of rdDataReg.
  // This takes priority over the base rdDataReg assignment but is overridden
  // by the READ_WAIT combinational pass-through (which comes earlier in code,
  // but SpinalHDL uses "last when wins" — since readObjectCache is only True in
  // IDLE state and READ_WAIT override only fires in READ_WAIT, they don't
  // conflict in practice).
  if(config.useOcache) {
    when(readObjectCache) {
      io.memOut.rdData := objectCache.get.io.dout
    }
  }

  // readObjectCache: Clear on any new memory operation OR when state leaves IDLE.
  // The getfield hit handler re-sets it via "last assignment wins" for hits.
  // Same fix as readArrayCache: prevents stale cache output from overriding I/O reads.
  if(config.useOcache) {
    when(state =/= State.IDLE) {
      readObjectCache := False
    }
    when(state === State.IDLE && (memReadRequested || io.memIn.wr || io.memIn.wrf ||
        io.memIn.getfield || io.memIn.putfield || io.memIn.iaload || io.memIn.iastore ||
        io.memIn.bcRd || io.memIn.getstatic || io.memIn.putstatic || io.memIn.copy)) {
      readObjectCache := False
    }
  }

  // ==========================================================================
  // Snoop Input → Cache Invalidation Wiring
  // ==========================================================================

  io.snoopIn.foreach { s =>
    if (config.useAcache) {
      arrayCache.get.io.snoopValid  := s.valid && s.isArray
      arrayCache.get.io.snoopHandle := s.handle
      arrayCache.get.io.snoopIndex  := s.index.resize(config.acacheMaxIndexBits)
    }
    if (config.useOcache) {
      objectCache.get.io.snoopValid    := s.valid && !s.isArray
      objectCache.get.io.snoopHandle   := s.handle
      objectCache.get.io.snoopFieldIdx := s.index.resize(config.ocacheMaxIndexBits)
    }
  }

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
          // Note: address translation not applied — see translateAddr comment.
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
          // Note: address translation not applied — see translateAddr comment.
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
        if(config.useAcache) {
          // Array cache: chkIal fires combinationally (wired above).
          // On hit: stay IDLE, set readArrayCache for next-cycle output MUX.
          // On miss: normal handle dereference path with A$ line fill.
          when(arrayCache.get.io.hit) {
            // Cache hit — 0 busy cycles (matching VHDL next_state <= idl on hit)
            readArrayCache := True
          }.otherwise {
            addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
            handleIndex := aoutAddr
            indexReg := aoutAddr
            handleIsWrite := False
            handleIsArray := True
            state := State.HANDLE_READ
          }
        } else {
          addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
          handleIndex := aoutAddr
          indexReg := aoutAddr
          handleIsWrite := False
          handleIsArray := True
          state := State.HANDLE_READ
        }
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.getfield) {
        // Get object field - TOS = object ref, bcopd = field index
        if(config.useOcache) {
          // Object cache: chkGf fires combinationally (wired above).
          // On hit: stay IDLE, set readObjectCache for next-cycle output MUX.
          // On miss: normal handle dereference path.
          when(objectCache.get.io.hit && !wasStidx) {
            // Cache hit — 0 busy cycles (matching VHDL next_state <= idl on hit)
            readObjectCache := True
            wasStidx := False
          }.otherwise {
            addrReg := aoutAddr
            handleIndex := Mux(wasStidx, indexReg, io.bcopd(15 downto 0).asUInt.resize(config.addressWidth))
            handleIsWrite := False
            handleIsArray := False
            ocWasGetfield := True
            state := State.HANDLE_READ
          }
        } else {
          addrReg := aoutAddr
          handleIndex := Mux(wasStidx, indexReg, io.bcopd(15 downto 0).asUInt.resize(config.addressWidth))
          handleIsWrite := False
          handleIsArray := False
          state := State.HANDLE_READ
        }
        // (no extra busy needed - matches VHDL)

      }.elsewhen(io.memIn.putfield) {
        // Put object field - NOS = object ref, bcopd = field index, value in valueReg
        // NOTE: handleIndex is NOT captured here. The pipeline hasn't loaded the
        // operand bytes yet (stpf has no opd flag, unlike stgf which has opd).
        // We go to PF_WAIT first to let bcopd settle — matching VHDL pf0
        // "waste cycle to get opd in ok position".
        addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        handleIsWrite := True
        handleIsArray := False
        handleWriteData := io.aout  // TOS = value (also captured to valueReg above)
        if(config.useOcache) {
          ocWasGetfield := False
        }
        state := State.PF_WAIT

      }.elsewhen(io.memIn.copy) {
        // GC copy (stcp) - matching VHDL mem_in.copy handler
        // At stcp: TOS = pos, NOS = src
        // Capture: baseReg = src, posReg = pos + src, cpStopBit = pos[31]
        baseReg := io.bout(config.addressWidth - 1 downto 0).asUInt
        posReg := aoutAddr + io.bout(config.addressWidth - 1 downto 0).asUInt
        cpStopBit := io.aout(31)
        state := State.CP_SETUP

      }.elsewhen(io.memIn.iastore) {
        // Array store - 3-operand: TOS=value, NOS=index, 3rd=arrayref
        // Value captured to valueReg above. Wait 1 cycle (VHDL iast0) for
        // stast's implicit pop to shift the stack so index/arrayref are accessible.
        handleIsWrite := True
        handleIsArray := True
        state := State.IAST_WAIT
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
    // Array Store Wait (busy, matching VHDL iast0)
    // ========================================================================
    // One cycle delay for stast's implicit pop to shift the stack:
    //   Before pop: TOS=value, NOS=index, 3rd=arrayref
    //   After pop:  TOS=index, NOS=arrayref
    // Value was captured to valueReg in IDLE. Now capture index and arrayref.

    is(State.IAST_WAIT) {
      addrReg := io.bout(config.addressWidth - 1 downto 0).asUInt  // NOS = arrayref
      handleIndex := aoutAddr                                        // TOS = index
      handleWriteData := valueReg                                    // stored value

      // Array cache: check iastore tag (matching VHDL chk_ias)
      if(config.useAcache) {
        arrayCache.get.io.chkIas := True
      }

      state := State.HANDLE_READ
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
    // Bytecode Cache Fill States (busy)
    //
    // Two generation-time paths selected by config.burstLen:
    //
    // burstLen == 0 (BRAM): Pipelined single-word reads matching VHDL
    //   mem_sc.vhd "pipeline level 2". Issues next read while writing
    //   previous word to JBC.
    //
    // burstLen > 0 (SDRAM/DDR): Burst reads. One BMB command fetches
    //   multiple consecutive words. After initial CAS latency, data
    //   streams at 1 word/clock.
    // ========================================================================

    if (config.burstLen == 0) {
      // Path A: No burst — pipelined single-word reads (unchanged)

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

    } else {
      // Path B: Burst reads

      is(State.BC_FILL_R1) {
        // Issue burst read command
        val remaining = bcFillLen - bcFillCount
        val burstWords = remaining.min(U(config.burstLen, 10 bits))
        io.bmb.cmd.valid := True
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
        io.bmb.cmd.fragment.address := (bcFillAddr << 2).resized
        io.bmb.cmd.fragment.length := ((burstWords << 2) - 1).resized
        when(io.bmb.cmd.fire) {
          bcFillAddr := bcFillAddr + burstWords
          state := State.BC_FILL_LOOP
        }
      }

      is(State.BC_FILL_LOOP) {
        // Process burst response beats
        when(io.bmb.rsp.fire) {
          // Byte-swap and register JBC write (same as non-burst path)
          val word = io.bmb.rsp.fragment.data
          jbcWrDataReg := word(7 downto 0) ## word(15 downto 8) ## word(23 downto 16) ## word(31 downto 24)
          jbcWrEnReg := True
          jbcWrAddrReg := (bcCacheStartReg + bcFillCount).resized
          bcFillCount := bcFillCount + 1

          when(io.bmb.rsp.last) {
            // End of burst batch
            when(bcFillCount + 1 >= bcFillLen) {
              state := State.IDLE  // All words filled
            }.otherwise {
              state := State.BC_FILL_R1  // More batches needed
            }
          }
        }
      }

      is(State.BC_FILL_CMD) {
        // Not used in burst mode — kept for enum completeness
        state := State.IDLE
      }

    }

    // ========================================================================
    // Putfield Operand Wait (matching VHDL pf0) - busy
    // ========================================================================

    is(State.PF_WAIT) {
      // "Waste cycle to get opd in ok position" (VHDL mem_sc.vhd pf0 comment).
      // The pipeline is still running (stpf was in IDLE which is not busy).
      // By now, the first 'nop opd' after stpf has caused jopdfetch=1,
      // and bcopd has advanced. Capture the field index here.
      handleIndex := Mux(wasStidx, indexReg, io.bcopd(15 downto 0).asUInt.resize(config.addressWidth))

      // Object cache: check putfield tag (matching VHDL chk_pf in pf0)
      if(config.useOcache) {
        when(!wasStidx) {
          objectCache.get.io.handle := addrReg  // Override default (aoutAddr)
          objectCache.get.io.chkPf := True
        }
      }

      state := State.HANDLE_READ
    }

    // ========================================================================
    // Handle Dereference States (getfield/putfield/iaload/iastore) - busy
    // ========================================================================

    is(State.HANDLE_READ) {
      // Object cache: save handle address for putfield invalidation.
      // NOTE: chkPf for putfield is now done in PF_WAIT (matching VHDL pf0).
      if(config.useOcache) {
        handleAddrReg := addrReg  // Save handle address before HANDLE_CALC overwrites addrReg
      }

      // Snoop: save handle for broadcast on write completion
      if(config.useAcache || config.useOcache) {
        snoopHandleReg := addrReg
      }

      // Exception checks (matching VHDL iald0/gf0):
      // - Null pointer: handle address == 0
      // - Negative array index: MSB of index is set
      when(addrReg === 0) {
        io.ioAddr := U(0x04, 8 bits)
        io.ioWr := True
        io.ioWrData := B(2, 32 bits)  // EXC_NP = 2
        state := State.NP_EXC
      }.elsewhen(handleIsArray && handleIndex(config.addressWidth - 1)) {
        io.ioAddr := U(0x04, 8 bits)
        io.ioWr := True
        io.ioWrData := B(3, 32 bits)  // EXC_AB = 3
        state := State.AB_EXC
      }.otherwise {
        // Normal handle dereference — issue read for handle[0] (data pointer)
        io.bmb.cmd.valid := True
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
        io.bmb.cmd.fragment.address := (addrReg << 2).resized
        when(io.bmb.cmd.fire) {
          state := State.HANDLE_WAIT
        }
      }
    }

    is(State.HANDLE_WAIT) {
      when(io.bmb.rsp.fire) {
        // Got data pointer from handle[0]
        handleDataPtr := io.bmb.rsp.fragment.data(config.addressWidth - 1 downto 0).asUInt

        // Object cache: detect HardwareObject (I/O data pointer)
        // Matches VHDL was_a_hwo / sc_mem_in.rd_data(31) check
        if(config.useOcache) {
          wasHwo := JopAddressSpace.isIoAddress(
            io.bmb.rsp.fragment.data(config.addressWidth - 1 downto 0).asUInt,
            config.addressWidth)
        }

        when(handleIsArray) {
          state := State.HANDLE_BOUND_READ  // Arrays need upper bounds check
        }.otherwise {
          state := State.HANDLE_CALC  // Fields skip bounds check
        }
      }
    }

    is(State.HANDLE_CALC) {
      // Calculate field/element address
      // Both arrays and objects use data_ptr + index (matching VHDL mem_sc.vhd)
      // In JOP, array length is stored in the handle (handle[1]), NOT at
      // data_ptr[0]. So array elements start directly at data_ptr[0].
      addrReg := handleDataPtr + handleIndex.resized

      if(config.useAcache) {
        when(handleIsArray && !handleIsWrite) {
          // iaload A$ miss: fill entire cache line (4 elements from aligned base)
          val fieldBits = config.acacheFieldBits
          val alignedIndex = (handleIndex >> fieldBits) << fieldBits
          acFillAddr := handleDataPtr + alignedIndex.resized
          acFillCount := 0
          acFillRequestedIdx := handleIndex(fieldBits - 1 downto 0)
          state := State.AC_FILL_CMD
        }.otherwise {
          state := State.HANDLE_ACCESS
        }
      } else {
        state := State.HANDLE_ACCESS
      }
    }

    is(State.HANDLE_ACCESS) {
      // Issue read or write to field/element.
      // Must check for I/O addresses — HardwareObject fields (getfield/putfield)
      // have data pointers in I/O space, so addrReg may be an I/O address.
      when(addrIsIo) {
        // I/O access — route to I/O bus (combinational, one cycle)
        io.ioAddr := addrReg(7 downto 0)
        when(handleIsWrite) {
          io.ioWr := True
          io.ioWrData := handleWriteData
        }.otherwise {
          io.ioRd := True
          rdDataReg := io.ioRdData
        }
        wasStidx := False
        state := State.IDLE
      }.otherwise {
        // External memory access — issue BMB command
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
    }

    is(State.HANDLE_DATA_WAIT) {
      when(io.bmb.rsp.fire) {
        when(!handleIsWrite) {
          rdDataReg := io.bmb.rsp.fragment.data
        }

        // Object cache update (matching VHDL wr_gf in idl from gf4, wr_pf in pf4)
        // Only for non-array, non-stidx, non-HWO operations
        if(config.useOcache) {
          when(!handleIsArray && !wasStidx && !wasHwo) {
            when(ocWasGetfield) {
              // Getfield miss returning to IDLE → fill cache
              // gfVal is wired to io.bmb.rsp.fragment.data (the field data just read)
              objectCache.get.io.wrGf := True
            }.otherwise {
              // Putfield completing → write-through (cache gates with hitTagReg)
              // pfVal is wired to handleWriteData (the value just written)
              objectCache.get.io.wrPf := True
            }
          }
        }

        // Array cache: iastore write-through (only on tag hit, gated inside A$)
        if(config.useAcache) {
          when(handleIsArray && handleIsWrite) {
            arrayCache.get.io.wrIas := True
          }
        }

        // Snoop output: broadcast store event to other cores
        io.snoopOut.foreach { s =>
          when(handleIsWrite) {
            s.valid   := True
            s.isArray := handleIsArray
            s.handle  := snoopHandleReg
            s.index   := handleIndex.resize(s.index.getWidth)
          }
        }

        wasStidx := False  // Reset stidx marker after handle operation
        state := State.IDLE
      }
    }

    // ========================================================================
    // Array Cache Line Fill States (iaload A$ miss) - busy
    //
    // Reads fieldCnt (4) consecutive elements starting from the aligned base
    // address, writing each to the array cache via wrIal. The requested
    // element's data is captured to rdDataReg for the pipeline.
    // ========================================================================

    is(State.AC_FILL_CMD) {
      if(config.useAcache) {
        io.bmb.cmd.valid := True
        io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
        if (config.burstLen > 0) {
          // Burst path (SDRAM): single burst for entire cache line.
          // Holds the bus (burstActive in BmbSdramCtrl32) — no interleaving.
          val fieldCnt = 1 << config.acacheFieldBits
          io.bmb.cmd.fragment.address := (acFillAddr << 2).resized
          io.bmb.cmd.fragment.length := (fieldCnt * 4 - 1)  // 15 for 4 words
        } else {
          // Non-burst path (BRAM): single-word read per element
          io.bmb.cmd.fragment.address := ((acFillAddr + acFillCount.resized) << 2).resized
        }
        when(io.bmb.cmd.fire) {
          state := State.AC_FILL_WAIT
        }
      } else {
        state := State.IDLE  // Unreachable if A$ disabled
      }
    }

    is(State.AC_FILL_WAIT) {
      if(config.useAcache) {
        when(io.bmb.rsp.fire) {
          // Write element to array cache
          arrayCache.get.io.wrIal := True
          arrayCache.get.io.ialVal := io.bmb.rsp.fragment.data

          // Capture rdDataReg for the requested element
          when(acFillCount === acFillRequestedIdx) {
            rdDataReg := io.bmb.rsp.fragment.data
          }

          if (config.burstLen > 0) {
            // Burst path: responses stream in, use rsp.last to detect completion
            acFillCount := acFillCount + 1
            when(io.bmb.rsp.last) {
              wasStidx := False
              state := State.IDLE
            }
          } else {
            // Non-burst path: count elements manually, loop back for next read
            val fieldCnt = 1 << config.acacheFieldBits
            when(acFillCount === U(fieldCnt - 1, config.acacheFieldBits bits)) {
              wasStidx := False
              state := State.IDLE
            }.otherwise {
              acFillCount := acFillCount + 1
              state := State.AC_FILL_CMD
            }
          }
        }
      } else {
        state := State.IDLE  // Unreachable if A$ disabled
      }
    }

    // ========================================================================
    // Array Bounds Check States (matching VHDL ialrb/iasrb) - busy
    // ========================================================================

    is(State.HANDLE_BOUND_READ) {
      // Read handle[1] (array length). addrReg still holds handle base address.
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := ((addrReg + 1) << 2).resized  // handle[1]
      when(io.bmb.cmd.fire) {
        state := State.HANDLE_BOUND_WAIT
      }
    }

    is(State.HANDLE_BOUND_WAIT) {
      when(io.bmb.rsp.fire) {
        val arrayLength = io.bmb.rsp.fragment.data(config.addressWidth - 1 downto 0).asUInt
        when(handleIndex >= arrayLength) {
          // Upper bounds violation — write exc_type=3 (EXC_AB)
          io.ioAddr := U(0x04, 8 bits)
          io.ioWr := True
          io.ioWrData := B(3, 32 bits)  // EXC_AB = 3
          state := State.AB_EXC
        }.otherwise {
          state := State.HANDLE_CALC  // Bounds OK, proceed
        }
      }
    }

    // ========================================================================
    // Exception States (matching VHDL npexc/abexc) - busy
    //
    // One cycle each. The exc_type I/O write happened in the previous
    // cycle; BmbSys will pulse io.exc on the next cycle. These states
    // just clean up and return to IDLE.
    // ========================================================================

    is(State.NP_EXC) {
      wasStidx := False
      state := State.IDLE
    }

    is(State.AB_EXC) {
      wasStidx := False
      state := State.IDLE
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

    // ========================================================================
    // GC Copy States (matching VHDL cp0-cpstop) - busy
    //
    // Copies one word per Native.memCopy() call (read from source, write to
    // dest) and maintains address translation so that accesses in
    // [baseReg, posReg) are transparently redirected by +offsetReg.
    // ========================================================================

    is(State.CP_SETUP) {
      // Compute offset = dest - src (matching VHDL cp0)
      // After microcode pop: TOS = src, NOS = dest
      // bout = NOS = dest, baseReg = src (captured in IDLE)
      offsetReg := io.bout(config.addressWidth - 1 downto 0).asUInt - baseReg
      when(cpStopBit) {
        state := State.CP_STOP
      }.otherwise {
        state := State.CP_READ
      }
    }

    is(State.CP_READ) {
      // Issue read from source address posReg (matching VHDL cp1)
      io.bmb.cmd.valid := True
      io.bmb.cmd.fragment.opcode := Bmb.Cmd.Opcode.READ
      io.bmb.cmd.fragment.address := (posReg << 2).resized
      when(io.bmb.cmd.fire) {
        state := State.CP_READ_WAIT
      }
    }

    is(State.CP_READ_WAIT) {
      // Wait for read response, capture data (matching VHDL cp2+cp3)
      when(io.bmb.rsp.fire) {
        valueReg := io.bmb.rsp.fragment.data
        addrReg := posReg + offsetReg  // write address (dest side)
        posReg := posReg + 1           // advance position (expand translation range)
        state := State.CP_WRITE
      }
    }

    is(State.CP_WRITE) {
      // Issue write to dest address (matching VHDL cp4)
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

    is(State.CP_STOP) {
      // Reset translation range (matching VHDL cpstop)
      // posReg := baseReg makes range [baseReg, baseReg) = empty
      posReg := baseReg
      state := State.IDLE
    }
  }

  // ==========================================================================
  // Debug Outputs
  // ==========================================================================

  io.debug.state := state.asBits.asUInt.resized
  io.debug.busy := !notBusy
  io.debug.addrReg := addrReg
  io.debug.rdDataReg := rdDataReg
  io.debug.handleActive := state.mux(
    State.PF_WAIT -> True,
    State.HANDLE_READ -> True,
    State.HANDLE_WAIT -> True,
    State.HANDLE_CALC -> True,
    State.HANDLE_ACCESS -> True,
    State.HANDLE_DATA_WAIT -> True,
    State.HANDLE_BOUND_READ -> True,
    State.HANDLE_BOUND_WAIT -> True,
    State.AC_FILL_CMD -> True,
    State.AC_FILL_WAIT -> True,
    State.NP_EXC -> True,
    State.AB_EXC -> True,
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
