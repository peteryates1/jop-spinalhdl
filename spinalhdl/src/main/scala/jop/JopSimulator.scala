package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._
import jop.core.Mul
import jop.memory._
import jop.utils.JopFileLoader

/**
 * JOP Simulator Configuration
 *
 * Configuration for the integrated JOP simulator that can run .jop files.
 *
 * @param dataWidth    Data path width (32 bits)
 * @param pcWidth      Microcode PC width (11 bits = 2K ROM)
 * @param instrWidth   Microcode instruction width (10 bits)
 * @param jpcWidth     Java PC width (11 bits = 2KB bytecode cache)
 * @param ramWidth     Stack RAM address width (8 bits = 256 entries)
 * @param mainMemSize  Main memory size in words
 */
case class JopSimulatorConfig(
  dataWidth: Int = 32,
  pcWidth: Int = 11,
  instrWidth: Int = 10,
  jpcWidth: Int = 11,
  ramWidth: Int = 8,
  mainMemSize: Int = 2097152  // 2M words = 8MB (matches typical JOP SDRAM size)
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth)
  def bcfetchConfig = BytecodeFetchConfig(jpcWidth, pcWidth)

  /** Main memory address width */
  def mainMemAddrWidth: Int = log2Up(mainMemSize)

  /** I/O base address (0xFFFFFF80 in negative form) */
  def ioBase: Long = 0xFFFFFF80L

  /** Check if address is in I/O space */
  def isIoAddress(addr: Long): Boolean = (addr & 0xFFFFFFF0L) >= ioBase
}

/**
 * I/O Address Constants
 *
 * JOP uses negative addresses for I/O, which map to high positive addresses.
 * Base: 0xFFFFFF80 (bipush -128 gives this as the base)
 */
object JopIoAddresses {
  // Slave 0: System (counter, WD, interrupt control)
  val IO_CNT      = 0xFFFFFF80L  // -128: System counter
  val IO_US_CNT   = 0xFFFFFF81L  // -127: Microsecond counter
  val IO_TIMER    = 0xFFFFFF82L  // -126: Timer interrupt
  val IO_WD       = 0xFFFFFF83L  // -125: Watchdog
  val IO_EXC      = 0xFFFFFF84L  // -124: Exception
  val IO_LOCK     = 0xFFFFFF85L  // -123: Lock
  val IO_CPU_ID   = 0xFFFFFF86L  // -122: CPU ID
  val IO_SIGNAL   = 0xFFFFFF87L  // -121: Signal
  val IO_INT_ENA  = 0xFFFFFF80L  // -128: Interrupt enable (same as CNT)

  // Slave 1: UART
  val IO_STATUS   = 0xFFFFFF90L  // -112: UART status
  val IO_UART     = 0xFFFFFF91L  // -111: UART data

  /** Get I/O slave from address (bits 5:4) */
  def getSlaveId(addr: Long): Int = ((addr >> 4) & 0x3).toInt

  /** Get sub-address within slave (bits 3:0) */
  def getSubAddr(addr: Long): Int = (addr & 0xF).toInt
}

/**
 * JOP Simulator - Integrated Testbench
 *
 * This component provides a complete JOP simulation environment:
 * - Main memory pre-loaded with .jop file data
 * - Microcode ROM loaded from mem_rom.dat
 * - Full pipeline: BytecodeFetch -> Fetch -> Decode -> Stack
 * - I/O simulation with UART and WD output capture
 * - Debug signals for execution logging
 *
 * Memory Interface:
 * - Main memory for object heap and method storage
 * - Method cache for fast bytecode access
 * - I/O space for UART, WD, timers
 *
 * @param config Simulator configuration
 * @param jopFilePath Path to .jop file for main memory initialization
 * @param romFilePath Path to mem_rom.dat for microcode ROM
 * @param ramFilePath Path to mem_ram.dat for stack RAM initialization (optional)
 */
case class JopSimulator(
  config: JopSimulatorConfig = JopSimulatorConfig(),
  jopFilePath: String,
  romFilePath: String,
  ramFilePath: Option[String] = None
) extends Component {

  val io = new Bundle {
    // Clock and reset (directly exposed for CocoTB)
    val clk = in Bool()
    val reset = in Bool()

    // Execution state outputs (for logging)
    val pc = out UInt(config.pcWidth bits)          // Microcode PC
    val jpc = out UInt((config.jpcWidth + 1) bits)  // Java PC
    val jpaddr = out UInt(config.pcWidth bits)      // Jump table output (microcode addr from bytecode)
    val instr = out Bits(config.instrWidth bits)    // Current microcode instruction
    val jfetch = out Bool()                         // Bytecode fetch signal
    val jopdfetch = out Bool()                      // Operand fetch signal

    // Stack outputs (for debugging)
    val aout = out Bits(config.dataWidth bits)      // TOS (top of stack)
    val bout = out Bits(config.dataWidth bits)      // NOS (next on stack)
    val dirAddr = out UInt(config.ramWidth bits)    // Direct RAM address from decode
    val selRda = out Bits(3 bits)                   // Read address select from decode
    val selLmux = out Bits(3 bits)                  // Load mux select from decode
    val selAmux = out Bool()                        // A mux select (0=ALU, 1=lmux)
    val enaA = out Bool()                           // A register enable

    // Debug: direct stack RAM access
    val debugRamAddr = in UInt(config.ramWidth bits)
    val debugRamData = out Bits(config.dataWidth bits)

    // Debug: direct stack RAM write for simulation initialization
    val debugRamWrAddr = in UInt(config.ramWidth bits)
    val debugRamWrData = in Bits(config.dataWidth bits)
    val debugRamWrEn = in Bool()

    // Debug: stack pointer and write tracking
    val debugSp = out UInt(config.ramWidth bits)
    val debugStackWrAddr = out UInt(config.ramWidth bits)
    val debugStackWrEn = out Bool()
    val debugRdAddrReg = out UInt(config.ramWidth bits)
    val debugRamDout = out Bits(config.dataWidth bits)

    // Memory interface signals (for observation)
    val memAddr = out UInt(config.mainMemAddrWidth bits)  // Memory address
    val memRd = out Bool()                                 // Memory read
    val memWr = out Bool()                                 // Memory write
    val memWrData = out Bits(config.dataWidth bits)       // Memory write data

    // I/O interface (directly accessible for simulation)
    val ioAddr = out UInt(8 bits)        // I/O address (lower 8 bits)
    val ioRd = out Bool()                // I/O read strobe
    val ioWr = out Bool()                // I/O write strobe
    val ioWrData = out Bits(32 bits)     // I/O write data

    // UART simulation interface
    val uartTxData = out Bits(8 bits)    // UART transmit data
    val uartTxValid = out Bool()         // UART transmit valid (pulse on write)
    val uartRxData = in Bits(8 bits)     // UART receive data
    val uartRxReady = in Bool()          // UART receive data ready

    // Watchdog output
    val wdOut = out Bool()               // Watchdog toggle output
    val wdToggle = out Bool()            // Watchdog toggle event (pulse)

    // System counter (simulated)
    val sysCnt = out UInt(32 bits)       // System clock counter

    // Halt detection (for simulation termination)
    val halted = out Bool()              // True if execution halted (infinite loop detected)

    // Debug: memory control signals
    val debugMemAddrWr = out Bool()      // Memory address write (stmwa)
    val debugMemRdReq = out Bool()       // Memory read request (stmra)
    val debugMemWrReq = out Bool()       // Memory write request (stmwd)

    // Debug: bytecode cache fill
    val debugBcRd = out Bool()           // stbcrd executed
    val debugBcFillActive = out Bool()   // BC fill in progress
    val debugBcFillLen = out UInt(10 bits)  // BC fill length
    val debugBcFillAddr = out UInt(config.mainMemAddrWidth bits) // BC fill start address
    val debugEnaJpc = out Bool()         // JPC write enable (stjpc)
    val debugJbcWrEn = out Bool()        // JBC write enable
    val debugJbcWrAddr = out UInt(8 bits)  // JBC write address (word)
    val debugJbcWrData = out Bits(32 bits) // JBC write data
    val debugMemRdData = out Bits(32 bits) // Memory read data (raw)
    val debugMemRdDataReg = out Bits(32 bits) // Memory read data (captured for stack)

    // Debug: handle operation state machine (getfield/putfield/iaload/iastore)
    val debugGetfield = out Bool()          // getfield signal from decode
    val debugHandleOpActive = out Bool()    // Handle op state machine active
    val debugHandleOpState = out UInt(3 bits)  // Handle op state (for tracing)
  }

  // Create clock domain from external signals
  val simClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // Load initialization data
  val jopData = JopFileLoader.jopFileToMemoryInit(jopFilePath, config.mainMemSize)
  val romData = JopFileLoader.loadMicrocodeRom(romFilePath)
  val ramData = ramFilePath.map(JopFileLoader.loadStackRam).getOrElse(Seq.fill(256)(BigInt(0)))

  // Debug: print critical RAM constants
  println(s"RAM[32] (stack_init): ${ramData(32)}")
  println(s"RAM[33] (io_cpu_id): ${ramData(33)}")
  println(s"RAM[38] (constant 2): ${ramData(38)}")
  println(s"RAM[45] (constant -1): ${ramData(45)}")

  // Main simulation area
  val simArea = new ClockingArea(simClockDomain) {

    // ========================================================================
    // Main Memory (loaded with .jop file)
    // ========================================================================
    val mainMem = Mem(Bits(32 bits), config.mainMemSize)
    mainMem.init(jopData.map(v => B(v, 32 bits)))

    // Memory access registers
    val memAddrReg = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
    val memRdReg = Reg(Bool()) init(False)
    val memWrReg = Reg(Bool()) init(False)
    val memWrDataReg = Reg(Bits(32 bits)) init(0)
    val memRdDataReg = Reg(Bits(32 bits)) init(0)
    val normalMemRdReg = Reg(Bool()) init(False)  // True if read is for normal operation (not BC fill)

    // Synchronous memory read
    // Note: readSync has 1-cycle latency. memRdReg indicates a read was REQUESTED.
    // The data arrives one cycle later, so we need to delay the capture.
    val memRdData = mainMem.readSync(memAddrReg)
    val normalMemRdDly = RegNext(normalMemRdReg) init(False)  // Delayed by 1 cycle

    // Track when handle operation data read completes
    // The data read happens in ACCESS_DATA (handleOpMemRd) and result is available in DONE
    // We need to capture the result when transitioning to DONE state
    val handleOpDataReadDly = Bool()  // Forward declaration, assigned after state machine

    // Capture to memRdDataReg for NORMAL reads AND handle operation data reads
    when(normalMemRdDly || handleOpDataReadDly) {
      memRdDataReg := memRdData
    }

    // Memory write
    mainMem.write(
      address = memAddrReg,
      data = memWrDataReg,
      enable = memWrReg
    )

    // ========================================================================
    // Microcode ROM (from mem_rom.dat)
    // ========================================================================
    val romDepth = 1 << config.pcWidth  // 2048 entries
    val romPadded = romData.padTo(romDepth, BigInt(0))
    val microcodeRom = Mem(Bits(12 bits), romDepth)  // 12 bits: instr(10) + jfetch + jopdfetch
    microcodeRom.init(romPadded.map(v => B(v.toLong, 12 bits)))

    // ========================================================================
    // JBC RAM (bytecode cache - in BytecodeFetchStage)
    // Pre-initialize with bytecodes from JOP file for simulation
    // In real operation, this would be loaded via method cache on cache miss
    // ========================================================================
    val jbcDepth = 1 << config.jpcWidth  // 2048 bytes

    // Find the boot method bytecodes from the JOP file structure:
    // - Word 1 contains mp (pointer to special pointer list)
    // - Word at mp contains pointer to boot method struct
    // - Boot method struct contains: start/len packed in first word
    //
    // For HelloWorld.jop: mp=1051, boot struct at 1555, code start=493, len=11
    // We need to load bytecodes starting from the boot method's code address
    val mpAddr = if (jopData.length > 1) jopData(1).toInt else 0
    val bootMethodStructAddr = if (jopData.length > mpAddr) jopData(mpAddr).toInt else 0
    val bootMethodStartLen = if (jopData.length > bootMethodStructAddr) jopData(bootMethodStructAddr).toLong else 0
    val bootCodeLen = (bootMethodStartLen & 0x3FF).toInt  // Lower 10 bits = length
    val bootCodeStart = (bootMethodStartLen >> 10).toInt  // Upper bits = start address

    // Debug: show boot method detection
    println(s"[JopSimulator] JOP file analysis:")
    println(s"  - mp pointer at word 1: $mpAddr")
    println(s"  - boot method struct addr: $bootMethodStructAddr")
    println(s"  - boot start/len packed: 0x${bootMethodStartLen.toHexString}")
    println(s"  - boot code start: word $bootCodeStart")
    println(s"  - boot code length: $bootCodeLen bytes")

    // For simulation, load bytecodes starting from boot method's code address
    val bytecodeStartWord = if (bootCodeStart > 0) bootCodeStart else 35
    println(s"  - Loading bytecodes from word $bytecodeStartWord")
    val bytecodeWords = jopData.slice(bytecodeStartWord, bytecodeStartWord + (jbcDepth / 4))

    // Debug: show first few bytecode words
    println(s"  - First 4 bytecode words:")
    bytecodeWords.take(4).zipWithIndex.foreach { case (w, i) =>
      val bytes = Seq((w >> 24) & 0xFF, (w >> 16) & 0xFF, (w >> 8) & 0xFF, w & 0xFF)
      println(f"    word ${bytecodeStartWord + i}: ${w.toLong}%d = ${bytes.map(b => f"$b%3d").mkString(" ")}")
    }

    // Convert 32-bit words to bytes (BIG-ENDIAN for JOP bytecode cache!)
    // JOP file stores bytecodes as big-endian packed words
    // e.g., 716636161 = 0x2AB70001 = bytes [42, 183, 0, 1] = aload_0, invokespecial, 0, 1
    val jbcInit = bytecodeWords.flatMap { word =>
      val w = word.toLong & 0xFFFFFFFFL
      Seq(
        BigInt((w >> 24) & 0xFF),  // MSB first (big-endian)
        BigInt((w >> 16) & 0xFF),
        BigInt((w >> 8) & 0xFF),
        BigInt((w >> 0) & 0xFF)   // LSB last
      )
    }.padTo(jbcDepth, BigInt(0))

    // ========================================================================
    // Pipeline Stages
    // ========================================================================

    // Bytecode Fetch Stage
    val bcfetch = BytecodeFetchStage(config.bcfetchConfig, Some(jbcInit))

    // Microcode Fetch Stage (using external ROM)
    val fetch = FetchStage(config.fetchConfig, Some(romPadded))

    // Decode Stage
    val decode = new DecodeStage(config.decodeConfig)

    // Stack Stage (with RAM initialization if provided)
    val stack = new StackStage(config.stackConfig, ramInit = Some(ramData))

    // Multiplier - connected to stack A and B outputs
    val mul = Mul(config.dataWidth)
    mul.io.ain := stack.io.aout.asUInt
    mul.io.bin := stack.io.bout.asUInt
    mul.io.wr := decode.io.mulWr

    // ========================================================================
    // I/O Simulation
    // ========================================================================

    // I/O registers
    val ioAddrReg = Reg(UInt(8 bits)) init(0)
    val ioRdReg = Reg(Bool()) init(False)
    val ioWrReg = Reg(Bool()) init(False)
    val ioWrDataReg = Reg(Bits(32 bits)) init(0)

    // UART simulation
    val uartTxDataReg = Reg(Bits(8 bits)) init(0)
    val uartTxValidReg = Reg(Bool()) init(False)
    val uartStatusReg = Reg(Bits(32 bits)) init(0x3)  // TX empty (bit 0=1), RX not full (bit 1=1)

    // Watchdog simulation
    val wdReg = Reg(Bool()) init(False)
    val wdToggleReg = Reg(Bool()) init(False)

    // System counter - start at 1000000 to skip startup wait loops faster
    val sysCntReg = Reg(UInt(32 bits)) init(1000000)
    sysCntReg := sysCntReg + 10  // Increment faster for simulation

    // ========================================================================
    // Memory Address Decode
    // ========================================================================

    // Memory decode matches VHDL jopcpu.vhd lines 301-308:
    // Top 2 bits of SC_ADDR_SIZE address determine access type:
    //   "10" -> scratch access
    //   "11" -> IO access
    //   others -> main memory access
    // This means addresses with top 2 bits = "11" are IO (negative addresses when signed)
    val addrTop2Bits = memAddrReg(config.mainMemAddrWidth - 1 downto config.mainMemAddrWidth - 2)
    val addrIsIo = addrTop2Bits === U(3, 2 bits)      // "11" = 3
    val addrIsScratch = addrTop2Bits === U(2, 2 bits) // "10" = 2

    // ========================================================================
    // Pipeline Connections
    // ========================================================================

    // BytecodeFetch connections
    bcfetch.io.jfetch := fetch.io.nxt
    bcfetch.io.jopdfetch := fetch.io.opd
    bcfetch.io.jbr := decode.io.jbr
    bcfetch.io.zf := stack.io.zf
    bcfetch.io.nf := stack.io.nf
    bcfetch.io.eq := stack.io.eq
    bcfetch.io.lt := stack.io.lt
    // JPC write control - connect decode's enaJpc signal
    bcfetch.io.jpc_wr := decode.io.enaJpc
    bcfetch.io.din := stack.io.aout

    // Memory control from decode (need this early for bytecode cache fill)
    val memCtrl = decode.io.memIn

    // Memory operation timing:
    // In the VHDL JOP, when mem_in.rdc='1', mem.vhd captures ain (A register output).
    // Both the registered decode signal (rdc) and the A register are updated at the
    // SAME clock edge, so they are naturally synchronized. After the edge, rdc=true
    // and ain=correct_address. The memory controller captures ain at the NEXT edge.
    //
    // In SpinalHDL, the equivalent is to use stack.io.aout directly (not RegNext),
    // since it's a register output updated at the same edge as memCtrl signals.
    // Using RegNext(aout) would introduce an EXTRA cycle of delay, making the
    // address one cycle stale.

    // ========================================================================
    // Bytecode Cache Fill State Machine
    // ========================================================================
    // When stbcrd (memCtrl.bcRd) is executed, TOS contains packed start/len:
    // - Lower 10 bits: length (bytecode count)
    // - Upper bits >> 10: start address in main memory (word address)
    //
    // The cache fill reads words from main memory and writes them to the JBC RAM.
    // This is a simplified implementation - real JOP uses DMA in mem.vhd.
    //
    // All memCtrl signals are registered (1-cycle delay from decode).
    // Both memCtrl and the A register update at the same clock edge, so stack.io.aout
    // is the correct synchronized value when memCtrl signals are active.

    // Bytecode cache fill state machine
    //
    // Two bugs fixed from original implementation:
    // 1. readSync latency: mainMem.readSync(memAddrReg) has 2-cycle total latency
    //    when memAddrReg is a register (1 cycle for address propagation via NBA,
    //    1 cycle for synchronous read). Added WAIT state to account for this.
    // 2. Byte endianness: JOP memory stores bytecodes big-endian (MSByte first),
    //    but our JBC RAM byte mux reads little-endian (byte 0 from bits 7:0).
    //    The fill must byte-swap words before writing to JBC.
    object BcFillState extends SpinalEnum {
      val IDLE, READ, WAIT, WRITE = newElement()
    }

    val bcFillState = Reg(BcFillState()) init(BcFillState.IDLE)
    val bcFillAddr = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
    val bcFillLen = Reg(UInt(10 bits)) init(0)  // Length in words
    val bcFillCount = Reg(UInt(10 bits)) init(0)  // Current byte count

    // JBC write signals
    val jbcWrAddrReg = Reg(UInt((config.jpcWidth - 2) bits)) init(0)  // Word address
    val jbcWrDataReg = Reg(Bits(32 bits)) init(0)
    val jbcWrEnReg = Reg(Bool()) init(False)

    // BC fill memory control (will be used to gate normal memory ops)
    val bcFillMemRd = Bool()
    val bcFillMemAddr = UInt(config.mainMemAddrWidth bits)
    bcFillMemRd := False
    bcFillMemAddr := 0

    // Default: no JBC write
    jbcWrEnReg := False

    switch(bcFillState) {
      is(BcFillState.IDLE) {
        when(memCtrl.bcRd) {
          // stbcrd executed - use aout directly (synchronized with bcRd at same clock edge)
          val packedVal = stack.io.aout.asUInt
          // Lower 10 bits = length in WORDS (not bytes!)
          // JOP packs: lower 10 bits = word count, upper bits = start word address
          val bcLenWords = (packedVal & 0x3FF).resize(10 bits)
          val bcStart = (packedVal >> 10).resize(config.mainMemAddrWidth bits)

          bcFillAddr := bcStart
          bcFillLen := bcLenWords  // Store word count directly
          bcFillCount := 0  // Word counter (not byte counter)
          jbcWrAddrReg := 0
          bcFillState := BcFillState.READ
        }
      }

      is(BcFillState.READ) {
        // Initiate memory read - memAddrReg will be set at next edge
        bcFillMemAddr := bcFillAddr
        bcFillMemRd := True
        bcFillState := BcFillState.WAIT
      }

      is(BcFillState.WAIT) {
        // readSync is reading mem[bcFillAddr] at this edge.
        // Result will be available in memRdData after this edge.
        bcFillState := BcFillState.WRITE
      }

      is(BcFillState.WRITE) {
        // memRdData is now valid with mem[bcFillAddr]
        // Byte-swap: JOP memory is big-endian (MSByte = first bytecode),
        // but JBC byte mux reads little-endian (byte 0 from bits 7:0).
        // Swap so byte 0 (JPC offset 0) = MSByte of memory word.
        jbcWrDataReg := memRdData(7 downto 0) ## memRdData(15 downto 8) ##
                        memRdData(23 downto 16) ## memRdData(31 downto 24)
        jbcWrEnReg := True

        // Use bcFillCount as write address (current value before increment)
        // This ensures the first word writes to address 0, not 1
        jbcWrAddrReg := bcFillCount.resized

        // Count words written (bcFillLen is in words)
        val wordsWritten = bcFillCount + 1
        when(wordsWritten >= bcFillLen) {
          // Done filling
          bcFillState := BcFillState.IDLE
        }.otherwise {
          // More words to read - increment count and memory address
          bcFillCount := wordsWritten
          bcFillAddr := bcFillAddr + 1
          bcFillState := BcFillState.READ
        }
      }
    }

    // Track if BC fill is active (for memory arbitration)
    val bcFillActive = bcFillState =/= BcFillState.IDLE

    // Connect bytecode cache write signals
    bcfetch.io.jbcWrAddr := jbcWrAddrReg
    bcfetch.io.jbcWrData := jbcWrDataReg
    bcfetch.io.jbcWrEn := jbcWrEnReg

    // Interrupt/exception (disabled for now)
    bcfetch.io.irq := False
    bcfetch.io.exc := False
    bcfetch.io.ena := False

    // Fetch stage connections
    fetch.io.jpaddr := bcfetch.io.jpaddr
    fetch.io.br := decode.io.br
    fetch.io.jmp := decode.io.jmp

    // Forward declaration for handleOpActive (assigned later in handle dereference state machine)
    val handleOpActive = Bool()

    // Memory busy signal - stall pipeline when:
    // 1. BC fill is active (memory bus is being used for bytecode loading)
    // 2. Handle operation is active (getfield/putfield/iaload/iastore)
    // This ensures the pipeline waits during `wait` instructions until memory is ready
    fetch.io.bsy := bcFillActive || handleOpActive

    // Decode stage connections
    decode.io.instr := fetch.io.dout
    decode.io.zf := stack.io.zf
    decode.io.nf := stack.io.nf
    decode.io.eq := stack.io.eq
    decode.io.lt := stack.io.lt
    decode.io.bcopd := bcfetch.io.opd

    // Stack stage connections
    // din mux: External data input used when selLmux=100
    // - ldmrd (0x0E0): ir(1:0)=00 → Load from memory read data register (I/O or memory)
    // - ldmul (0x0E1): ir(1:0)=01 → Load from multiplier output
    // - ldbcstart (0x0E2): ir(1:0)=10 → Load bytecode cache start address
    // Bytecode cache start register - stores the JPC base for current method
    // After bytecode cache fill, this should be 0 (we always load at JBC address 0)
    val bcStartReg = Reg(UInt(config.jpcWidth + 1 bits)) init(0)

    // Update bcstart when bytecode cache fill completes
    // bcFillCount and bcFillLen are both in words
    when(bcFillState === BcFillState.WRITE && (bcFillCount + 1) >= bcFillLen) {
      bcStartReg := 0  // Bytecodes loaded at JBC address 0
    }

    // din mux selection based on ir(1:0)
    // Pipeline timing fix: dinMuxSel must be delayed by 1 cycle to match the registered decode.
    // When ldmrd's lmux=4 is active (from registered decode), we need ldmrd's ir(1:0)=00,
    // not the following instruction's ir(1:0).
    val dinMuxSel = RegNext(fetch.io.ir_out(1 downto 0)) init(0)
    stack.io.din := dinMuxSel.mux(
      0 -> memRdDataReg,                    // ldmrd
      1 -> mul.io.dout.asBits,              // ldmul
      2 -> bcStartReg.asBits.resized,       // ldbcstart
      3 -> B(0, 32 bits)                    // reserved
    )
    stack.io.dirAddr := decode.io.dirAddr.asUInt
    stack.io.opd := bcfetch.io.opd
    stack.io.jpc := bcfetch.io.jpc_out

    // Control signals from decode
    stack.io.selSub := decode.io.selSub
    stack.io.selAmux := decode.io.selAmux
    stack.io.enaA := decode.io.enaA
    stack.io.selBmux := decode.io.selBmux
    stack.io.selLog := decode.io.selLog
    stack.io.selShf := decode.io.selShf
    stack.io.selLmux := decode.io.selLmux
    stack.io.selImux := decode.io.selImux
    stack.io.selRmux := decode.io.selRmux
    stack.io.selSmux := decode.io.selSmux
    stack.io.selMmux := decode.io.selMmux
    stack.io.selRda := decode.io.selRda
    stack.io.selWra := decode.io.selWra
    stack.io.wrEna := decode.io.wrEna
    stack.io.enaB := decode.io.enaB
    stack.io.enaVp := decode.io.enaVp
    stack.io.enaAr := decode.io.enaAr

    // ========================================================================
    // Handle Dereference State Machine (getfield, putfield, iaload, iastore)
    // ========================================================================
    // These operations require two memory accesses:
    // 1. Read the handle to get pointer to actual object/array data
    // 2. Access the field/element at (data_ptr + index)
    //
    // JOP handle format:
    // - Handle at address H: H[0] = data pointer, H[1] = array length (for arrays)
    // - For objects: data_ptr[field_index] is the field value
    // - For arrays: data_ptr[index] is the element (length in handle, not data area)
    //
    object HandleOpState extends SpinalEnum {
      val IDLE, READ_HANDLE, WAIT_HANDLE, CALC_ADDR, ACCESS_DATA, WAIT_DATA, DONE = newElement()
    }

    val handleOpState = Reg(HandleOpState()) init(HandleOpState.IDLE)
    val handleOpAddr = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
    val handleOpIndex = Reg(UInt(16 bits)) init(0)  // Field/array index
    val handleOpIsWrite = Reg(Bool()) init(False)
    val handleOpWriteData = Reg(Bits(32 bits)) init(0)
    val handleOpIsArray = Reg(Bool()) init(False)  // True for iaload/iastore
    val handleOpDataPtr = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
    val handleOpMemRd = Bool()  // Memory read request from handle op
    val handleOpMemWr = Bool()  // Memory write request from handle op
    val handleOpMemAddr = UInt(config.mainMemAddrWidth bits)
    handleOpActive := handleOpState =/= HandleOpState.IDLE  // Assign to forward-declared signal

    handleOpMemRd := False
    handleOpMemWr := False
    handleOpMemAddr := 0

    switch(handleOpState) {
      is(HandleOpState.IDLE) {
        when(memCtrl.getfield && !bcFillActive) {
          // getfield: TOS = object reference, bcopd = field index
          handleOpAddr := stack.io.aout.asUInt.resized
          handleOpIndex := memCtrl.bcopd.asUInt
          handleOpIsWrite := False
          handleOpIsArray := False
          handleOpState := HandleOpState.READ_HANDLE
        }.elsewhen(memCtrl.putfield && !bcFillActive) {
          // putfield: NOS = object reference, TOS = value, bcopd = field index
          handleOpAddr := stack.io.bout.asUInt.resized
          handleOpIndex := memCtrl.bcopd.asUInt
          handleOpWriteData := stack.io.aout
          handleOpIsWrite := True
          handleOpIsArray := False
          handleOpState := HandleOpState.READ_HANDLE
        }.elsewhen(memCtrl.iaload && !bcFillActive) {
          // iaload: NOS = array reference, TOS = index
          // Note: stald sets ain to element index, bin to array ref
          handleOpAddr := stack.io.bout.asUInt.resized
          handleOpIndex := stack.io.aout.asUInt.resized
          handleOpIsWrite := False
          handleOpIsArray := True
          handleOpState := HandleOpState.READ_HANDLE
        }.elsewhen(memCtrl.iastore && !bcFillActive) {
          // iastore: Stack has [arrayref, index, value] - need stidx first
          // stidx stores index, stast does the actual store
          // For now, handle as: bin = array ref, ain = index (after stidx)
          handleOpAddr := stack.io.bout.asUInt.resized
          handleOpIndex := stack.io.aout.asUInt.resized
          handleOpWriteData := stack.io.aout  // Value was stored by prior instruction
          handleOpIsWrite := True
          handleOpIsArray := True
          handleOpState := HandleOpState.READ_HANDLE
        }
      }

      is(HandleOpState.READ_HANDLE) {
        // Issue read to dereference handle
        handleOpMemAddr := handleOpAddr
        handleOpMemRd := True
        handleOpState := HandleOpState.WAIT_HANDLE
      }

      is(HandleOpState.WAIT_HANDLE) {
        // Wait for handle read to complete (readSync latency)
        handleOpState := HandleOpState.CALC_ADDR
      }

      is(HandleOpState.CALC_ADDR) {
        // memRdData now contains the data pointer from the handle
        handleOpDataPtr := memRdData.asUInt.resized
        handleOpState := HandleOpState.ACCESS_DATA
      }

      is(HandleOpState.ACCESS_DATA) {
        // Calculate final address and issue read/write
        // Both arrays and objects use data_ptr + index (matching VHDL mem_sc.vhd)
        // In JOP, array length is stored in the handle (handle[1]), NOT at
        // data_ptr[0]. So array elements start directly at data_ptr[0].
        val finalAddr = handleOpDataPtr + handleOpIndex.resized
        handleOpMemAddr := finalAddr
        when(handleOpIsWrite) {
          // Signal write request - actual write handled in memory section
          handleOpMemWr := True
        }.otherwise {
          handleOpMemRd := True
        }
        handleOpState := HandleOpState.WAIT_DATA
      }

      is(HandleOpState.WAIT_DATA) {
        // Wait for data access to complete
        handleOpState := HandleOpState.DONE
      }

      is(HandleOpState.DONE) {
        // Data is now available in memRdData
        // Return to idle - microcode will use ldmrd to get the result
        handleOpState := HandleOpState.IDLE
      }
    }

    // Assign handleOpDataReadDly: capture data when handle op data read completes
    // The data is available when state is DONE
    handleOpDataReadDly := handleOpState === HandleOpState.DONE

    // ========================================================================
    // Memory/IO Operation Handling
    // ========================================================================

    // Default: clear strobes
    memRdReg := False
    memWrReg := False
    ioRdReg := False
    ioWrReg := False
    uartTxValidReg := False
    wdToggleReg := False
    normalMemRdReg := False  // Default: not a normal read

    // Pending memory request queue (for when BC fill is active)
    // Queues memory operations that conflict with BC fill
    val pendingMemRd = Reg(Bool()) init(False)
    val pendingMemWr = Reg(Bool()) init(False)
    val pendingMemAddr = Reg(UInt(config.mainMemAddrWidth bits)) init(0)
    val pendingIoAddr = Reg(UInt(8 bits)) init(0)
    val pendingMemWrData = Reg(Bits(32 bits)) init(0)
    val pendingIsIo = Reg(Bool()) init(False)

    // Bytecode cache fill has priority, then handle operations, then normal operations
    when(bcFillMemRd) {
      memAddrReg := bcFillMemAddr
      memRdReg := True
      // normalMemRdReg stays False for BC fill reads
    }.elsewhen(handleOpMemRd) {
      // Handle operation memory read (getfield/putfield/iaload/iastore)
      memAddrReg := handleOpMemAddr
      memRdReg := True
      // normalMemRdReg stays False for handle op reads
    }.elsewhen(handleOpMemWr) {
      // Handle operation memory write (putfield/iastore)
      memAddrReg := handleOpMemAddr
      memWrReg := True
      memWrDataReg := handleOpWriteData
    }

    // Process pending memory operations when BC fill is not active and not reading
    when(!bcFillActive && !bcFillMemRd) {
      when(pendingMemRd) {
        memAddrReg := pendingMemAddr
        ioAddrReg := pendingIoAddr
        when(pendingIsIo) {
          ioRdReg := True
        }.otherwise {
          memRdReg := True
          normalMemRdReg := True
        }
        pendingMemRd := False
      }.elsewhen(pendingMemWr) {
        memAddrReg := pendingMemAddr
        ioAddrReg := pendingIoAddr
        when(pendingIsIo) {
          ioWrReg := True
          ioWrDataReg := pendingMemWrData
        }.otherwise {
          memWrReg := True
          memWrDataReg := pendingMemWrData
        }
        pendingMemWr := False
      }
    }

    // Handle memory write address (stmwa) - queue if BC fill active
    // Use aout directly - synchronized with memCtrl.addrWr at same clock edge
    when(memCtrl.addrWr) {
      when(bcFillActive) {
        // Queue the address for later
        pendingMemAddr := stack.io.aout.asUInt.resized
        pendingIoAddr := stack.io.aout(7 downto 0).asUInt
        pendingIsIo := stack.io.aout(config.mainMemAddrWidth - 1 downto config.mainMemAddrWidth - 8).asUInt === 0xFF
      }.otherwise {
        memAddrReg := stack.io.aout.asUInt.resized
        ioAddrReg := stack.io.aout(7 downto 0).asUInt
      }
    }

    // Handle memory write (stmwd) - queue if BC fill active
    // Use aout directly - synchronized with memCtrl.wr at same clock edge
    when(memCtrl.wr && !bcFillActive && !pendingMemWr) {
      val addr = memAddrReg
      when(addrIsIo) {
        // I/O write
        ioWrReg := True
        ioWrDataReg := stack.io.aout

        // Decode I/O address
        val subAddr = ioAddrReg(3 downto 0)
        val slaveId = ioAddrReg(5 downto 4)

        switch(slaveId) {
          is(0) {  // Slave 0: System
            switch(subAddr) {
              is(3) {  // WD
                wdReg := stack.io.aout(0)
                wdToggleReg := True
              }
            }
          }
          is(1) {  // Slave 1: UART
            switch(subAddr) {
              is(1) {  // UART data
                uartTxDataReg := stack.io.aout(7 downto 0)
                uartTxValidReg := True
              }
            }
          }
        }
      }.otherwise {
        // Normal memory write
        memWrReg := True
        memWrDataReg := stack.io.aout
      }
    }.elsewhen(memCtrl.wr && bcFillActive) {
      // Queue the write for later
      pendingMemWr := True
      pendingMemWrData := stack.io.aout
    }

    // Handle memory read (stmra, stmrac, stmraf) - queue if BC fill active
    // Use aout directly - synchronized with memCtrl signals at same clock edge
    val memReadRequested = memCtrl.rd || memCtrl.rdc || memCtrl.rdf
    when(memReadRequested && !bcFillActive && !pendingMemRd) {
      val addr = stack.io.aout.asUInt.resized
      memAddrReg := addr
      ioAddrReg := stack.io.aout(7 downto 0).asUInt

      when(addr(config.mainMemAddrWidth - 1 downto config.mainMemAddrWidth - 8) === 0xFF) {
        // I/O read
        ioRdReg := True

        // Decode I/O address
        val subAddr = stack.io.aout(3 downto 0).asUInt
        val slaveId = stack.io.aout(5 downto 4).asUInt

        switch(slaveId) {
          is(0) {  // Slave 0: System
            switch(subAddr) {
              is(0) {  // Counter (io_cnt = -128)
                memRdDataReg := sysCntReg.asBits
              }
              is(1) {  // Microsecond counter (io_us_cnt = -127)
                memRdDataReg := sysCntReg.asBits  // Use same counter for now
              }
              is(3) {  // WD (io_wd = -125)
                memRdDataReg := wdReg.asBits.resized
              }
              is(6) {  // CPU_ID (io_cpu_id = -122) - MUST return 0 for CPU0!
                memRdDataReg := B(0, 32 bits)
              }
              is(7) {  // Signal (io_signal = -121)
                memRdDataReg := B(0, 32 bits)
              }
              default {
                memRdDataReg := B(0, 32 bits)
              }
            }
          }
          is(1) {  // Slave 1: UART
            switch(subAddr) {
              is(0) {  // Status: bit0=TX ready, bit1=RX ready
                memRdDataReg := B(0x1, 32 bits)  // TX always ready, no RX
              }
              is(1) {  // UART data
                memRdDataReg := io.uartRxData.resized
              }
              default {
                memRdDataReg := 0
              }
            }
          }
          default {
            memRdDataReg := 0
          }
        }
      }.otherwise {
        // Normal memory read
        memRdReg := True
        normalMemRdReg := True  // Mark as normal read (not BC fill)
      }
    }.elsewhen(memReadRequested && bcFillActive) {
      // Queue the read for later
      pendingMemRd := True
      pendingMemAddr := stack.io.aout.asUInt.resized
      pendingIoAddr := stack.io.aout(7 downto 0).asUInt
      pendingIsIo := stack.io.aout(config.mainMemAddrWidth - 1 downto config.mainMemAddrWidth - 8).asUInt === 0xFF
    }

    // ========================================================================
    // Halt Detection (simple infinite loop detection)
    // ========================================================================
    val prevPc = Reg(UInt(config.pcWidth bits)) init(0)
    val pcStableCount = Reg(UInt(16 bits)) init(0)
    val haltedReg = Reg(Bool()) init(False)

    when(fetch.io.pc_out === prevPc) {
      pcStableCount := pcStableCount + 1
      when(pcStableCount >= 1000) {  // 1000 cycles at same PC = halted
        haltedReg := True
      }
    }.otherwise {
      pcStableCount := 0
    }
    prevPc := fetch.io.pc_out

    // ========================================================================
    // Output Connections
    // ========================================================================
    io.pc := fetch.io.pc_out
    io.jpc := bcfetch.io.jpc_out
    io.jpaddr := bcfetch.io.jpaddr
    io.instr := fetch.io.dout
    io.jfetch := fetch.io.nxt
    io.jopdfetch := fetch.io.opd

    io.aout := stack.io.aout
    io.bout := stack.io.bout
    io.dirAddr := decode.io.dirAddr.asUInt
    io.selRda := decode.io.selRda
    io.selLmux := decode.io.selLmux
    io.selAmux := decode.io.selAmux
    io.enaA := decode.io.enaA

    // Debug: connect stack RAM debug ports
    stack.io.debugRamAddr := io.debugRamAddr
    io.debugRamData := stack.io.debugRamData
    stack.io.debugRamWrAddr := io.debugRamWrAddr
    stack.io.debugRamWrData := io.debugRamWrData
    stack.io.debugRamWrEn := io.debugRamWrEn
    io.debugSp := stack.io.debugSp
    io.debugStackWrAddr := stack.io.debugWrAddr
    io.debugStackWrEn := stack.io.debugWrEn
    io.debugRdAddrReg := stack.io.debugRdAddrReg
    io.debugRamDout := stack.io.debugRamDout

    io.memAddr := memAddrReg
    io.memRd := memRdReg
    io.memWr := memWrReg
    io.memWrData := memWrDataReg

    io.ioAddr := ioAddrReg
    io.ioRd := ioRdReg
    io.ioWr := ioWrReg
    io.ioWrData := ioWrDataReg

    io.uartTxData := uartTxDataReg
    io.uartTxValid := uartTxValidReg

    io.wdOut := wdReg
    io.wdToggle := wdToggleReg

    io.sysCnt := sysCntReg
    io.halted := haltedReg

    // Debug: memory control signals
    io.debugMemAddrWr := memCtrl.addrWr
    io.debugMemRdReq := memCtrl.rd || memCtrl.rdc || memCtrl.rdf
    io.debugMemWrReq := memCtrl.wr

    // Debug: bytecode cache fill signals
    io.debugBcRd := memCtrl.bcRd
    io.debugBcFillActive := bcFillActive
    io.debugBcFillLen := bcFillLen
    io.debugBcFillAddr := bcFillAddr
    io.debugEnaJpc := decode.io.enaJpc
    io.debugJbcWrEn := jbcWrEnReg
    io.debugJbcWrAddr := jbcWrAddrReg.resized
    io.debugJbcWrData := jbcWrDataReg
    io.debugMemRdData := memRdData
    io.debugMemRdDataReg := memRdDataReg

    // Debug: handle operation state machine
    io.debugGetfield := memCtrl.getfield
    io.debugHandleOpActive := handleOpActive
    io.debugHandleOpState := handleOpState.asBits.asUInt.resized
  }
}

/**
 * JOP Simulator Testbench Generator
 *
 * Generates VHDL for CocoTB simulation with HelloWorld.jop
 */
object JopSimulatorTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  // Use local SIM-built microcode (not serial-boot which loads from UART)
  // JOP file from Smallest target, microcode from local SIM build
  config.generateVhdl(JopSimulator(
    jopFilePath = "/srv/git/jop/java/Smallest/HelloWorld.jop",
    romFilePath = "asm/generated/mem_rom.dat",
    ramFilePath = Some("asm/generated/mem_ram.dat")
  ))

  println("JopSimulator VHDL generated in generated/JopSimulator.vhd")
}
