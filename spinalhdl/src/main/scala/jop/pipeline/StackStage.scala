package jop.pipeline

import spinal.core._
import spinal.core.sim._
import jop.core.Shift

/**
 * Stack Cache Configuration (3-bank rotating stack cache with DMA spill/fill)
 *
 * When present, the stack is split into:
 *   - scratchRam: 64 entries (addresses 0-63), never rotated
 *   - 3 bank RAMs: each 192 usable entries (256 physical, 192 used)
 *
 * SP/VP/AR widen to 16 bits for virtual addressing across multiple banks.
 * Banks are rotated via DMA spill/fill to external memory when SP crosses
 * bank boundaries.
 *
 * @param numBanks        Number of bank RAMs (3 = active + ready + anti-thrash)
 * @param bankSize        Usable entries per bank (192 = 256 - 64 scratch)
 * @param scratchSize     Scratch/constants area size (64 entries, addresses 0-63)
 * @param virtualSpWidth  Width of virtual SP/VP/AR registers (16 bits)
 * @param spillBaseAddr   Base word address in external memory for stack spill area
 * @param burstLen        DMA burst length in words (4 for SDR, 8 for DDR3)
 * @param wordAddrWidth   Word address width (addressWidth - 2 type bits)
 */
case class StackCacheConfig(
  numBanks: Int = 3,
  bankSize: Int = 192,
  scratchSize: Int = 64,
  virtualSpWidth: Int = 16,
  spillBaseAddr: Int = 0x780000,
  burstLen: Int = 4,
  wordAddrWidth: Int = 24
) {
  require(numBanks == 3, "Only 3-bank configuration supported")
  require(bankSize > 0 && bankSize <= 256, "Bank size must be 1-256")
  require(scratchSize == 64, "Scratch size must be 64 (fixed by JOP microcode)")
  require(virtualSpWidth >= 8 && virtualSpWidth <= 16, "Virtual SP width must be 8-16")
  require(burstLen == 0 || (burstLen >= 2 && (burstLen & (burstLen - 1)) == 0),
    "burstLen must be 0 or power-of-2 >= 2")

  /** Physical RAM entries per bank (M9K = 256 words) */
  def bankPhysicalSize: Int = 256

  /** Total virtual stack entries (limited by virtualSpWidth) */
  def maxVirtualAddr: Int = (1 << virtualSpWidth) - 1

  /** Number of DMA bursts to transfer one full bank */
  def burstsPerBank: Int = if (burstLen > 0) bankSize / burstLen else bankSize

  /** Pre-fill threshold: when SP enters lower quarter of active bank, pre-fill previous */
  def prefillThreshold: Int = bankSize / 4
}

/**
 * Stack Stage Configuration
 *
 * Configures the stack/execute stage parameters.
 *
 * @param width     Data word width (default: 32)
 * @param jpcWidth  Java bytecode PC width (default: 10)
 * @param ramWidth  Stack RAM address width (default: 8, giving 256 entries)
 * @param cacheConfig Optional stack cache configuration (None = original single-RAM)
 */
case class StackConfig(
  width: Int = 32,
  jpcWidth: Int = 11,
  ramWidth: Int = 8,
  cacheConfig: Option[StackCacheConfig] = None
) {
  require(width == 32, "Data width must be 32 bits")
  require(jpcWidth >= 10 && jpcWidth <= 16, "JPC width must be between 10 and 16 bits")
  require(ramWidth > 0 && ramWidth <= 16, "RAM width must be between 1 and 16")

  /** Stack overflow threshold: max - 16 (only used in single-RAM mode) */
  def stackOverflowThreshold: Int = (1 << ramWidth) - 1 - 16

  /** Initial stack pointer value */
  def initialSP: Int = 128

  /** Effective SP width: 16-bit if stack cache enabled, ramWidth otherwise */
  def spWidth: Int = cacheConfig.map(_.virtualSpWidth).getOrElse(ramWidth)

  /** rmux width: max of (jpcWidth+1, spWidth) to accommodate widened SP/VP */
  def rmuxWidth: Int = (jpcWidth + 1).max(spWidth)

  /** Whether stack cache is enabled */
  def useStackCache: Boolean = cacheConfig.isDefined
}

/**
 * ALU Flags Bundle
 *
 * Contains the flag outputs from the ALU.
 */
case class AluFlags() extends Bundle {
  val zf = Bool()   // Zero flag (A == 0)
  val nf = Bool()   // Negative flag (A[31])
  val eq = Bool()   // Equal flag (A == B)
  val lt = Bool()   // Less-than flag (from 33-bit subtract)
}

/**
 * Stack/Execute Stage
 *
 * Implements the execute stage of the JOP microcode pipeline using SpinalHDL.
 *
 * When stack cache is disabled (cacheConfig = None):
 *   Original single-RAM design with ramWidth-bit SP/VP/AR registers.
 *
 * When stack cache is enabled (cacheConfig = Some(...)):
 *   3-bank rotating stack cache with 16-bit SP/VP/AR registers.
 *   Scratch RAM (64 entries, addresses 0-63) is separate and never rotated.
 *   3 bank RAMs (256 entries each, 192 usable) cover the virtual stack space.
 *   DMA spill/fill to external memory when SP crosses bank boundaries.
 *
 * @param config Stack stage configuration
 */
case class StackStage(
  config: StackConfig = StackConfig(),
  ramInit: Option[Seq[BigInt]] = None
) extends Component {

  val spWidth = config.spWidth
  val useCache = config.useStackCache

  val io = new Bundle {
    // Data Inputs
    val din    = in Bits(config.width bits)
    val dirAddr = in UInt(config.ramWidth bits)       // Direct RAM address from decode (scratch only)
    val opd = in Bits(16 bits)
    val jpc = in UInt(config.jpcWidth + 1 bits)

    // ALU Control Inputs (from decode stage)
    val selSub  = in Bool()
    val selAmux = in Bool()
    val enaA    = in Bool()
    val selBmux = in Bool()
    val selLog  = in Bits(2 bits)
    val selShf  = in Bits(2 bits)
    val selLmux = in Bits(3 bits)
    val selImux = in Bits(2 bits)
    val selRmux = in Bits(2 bits)
    val selSmux = in Bits(2 bits)

    val selMmux = in Bool()
    val selRda  = in Bits(3 bits)
    val selWra  = in Bits(3 bits)

    val wrEna   = in Bool()
    val enaB    = in Bool()
    val enaVp   = in Bool()
    val enaAr   = in Bool()

    // Debug: direct RAM read port for verification (scratch only)
    val debugRamAddr = in UInt(config.ramWidth bits)
    val debugRamData = out Bits(config.width bits)

    // Debug: direct RAM write port for simulation initialization
    val debugRamWrAddr = in UInt(config.ramWidth bits)
    val debugRamWrData = in Bits(config.width bits)
    val debugRamWrEn = in Bool()

    // Debug: stack pointer and write address for tracking (spWidth)
    val debugSp = out UInt(spWidth bits)
    val debugVp = out UInt(spWidth bits)
    val debugAr = out UInt(spWidth bits)
    val debugWrAddr = out UInt(spWidth bits)
    val debugWrEn = out Bool()
    val debugRdAddrReg = out UInt(spWidth bits)
    val debugRamDout = out Bits(config.width bits)

    // Outputs
    val spOv    = out Bool()
    val zf      = out Bool()
    val nf      = out Bool()
    val eq      = out Bool()
    val lt      = out Bool()
    val aout    = out Bits(config.width bits)
    val bout    = out Bits(config.width bits)

    // === Stack Cache Ports (only when enabled) ===
    val rotationBusy = if (useCache) Some(out Bool()) else None

    // DMA bank RAM access (from StackCacheDma)
    val dmaBankRdAddr  = if (useCache) Some(in UInt(8 bits)) else None
    val dmaBankRdData  = if (useCache) Some(out Bits(config.width bits)) else None
    val dmaBankWrAddr  = if (useCache) Some(in UInt(8 bits)) else None
    val dmaBankWrData  = if (useCache) Some(in Bits(config.width bits)) else None
    val dmaBankWrEn    = if (useCache) Some(in Bool()) else None
    val dmaBankSelect  = if (useCache) Some(in UInt(2 bits)) else None

    // DMA control (to StackCacheDma)
    val dmaStart     = if (useCache) Some(out Bool()) else None
    val dmaIsSpill   = if (useCache) Some(out Bool()) else None
    val dmaExtAddr   = if (useCache) Some(out UInt((config.cacheConfig.get.wordAddrWidth + 2) bits)) else None
    val dmaWordCount = if (useCache) Some(out UInt(8 bits)) else None
    val dmaBank      = if (useCache) Some(out UInt(2 bits)) else None

    // DMA status (from StackCacheDma)
    val dmaBusy = if (useCache) Some(in Bool()) else None
    val dmaDone = if (useCache) Some(in Bool()) else None

    // Stack cache debug outputs (for simulation)
    val scDebugRotState     = if (useCache) Some(out UInt(3 bits)) else None
    val scDebugActiveBankIdx = if (useCache) Some(out UInt(2 bits)) else None
    val scDebugBankBase     = if (useCache) Some(out Vec(UInt(spWidth bits), 3)) else None
    val scDebugBankResident = if (useCache) Some(out Bits(3 bits)) else None
    val scDebugBankDirty    = if (useCache) Some(out Bits(3 bits)) else None
    val scDebugNeedsRot     = if (useCache) Some(out Bool()) else None

    // Write-snoop debug: observe every bank write (for debugging stack cache issues)
    val scDebugPipeWrAddr = if (useCache) Some(out UInt(spWidth bits)) else None
    val scDebugPipeWrData = if (useCache) Some(out Bits(config.width bits)) else None
    val scDebugPipeWrEn   = if (useCache) Some(out Bool()) else None

    // VP+0 value readback: reads bank RAM at virtual address vp0 every cycle
    val scDebugVp0Data = if (useCache) Some(out Bits(config.width bits)) else None

    // Pipeline decode debug (for tracing A/B source during corruption)
    val debugEnaA     = out Bool()
    val debugSelLmux  = out Bits(3 bits)
    val debugEnaB     = out Bool()
    val debugSelBmux  = out Bool()
    val debugRamDoutVal = out Bits(config.width bits)
    val debugLmuxVal    = out Bits(config.width bits)
  }

  // ==========================================================================
  // Internal Registers (widened to spWidth for stack cache)
  // ==========================================================================

  val a = Reg(Bits(config.width bits)) init(0)
  val b = Reg(Bits(config.width bits)) init(0)

  val sp  = Reg(UInt(spWidth bits)) init(config.initialSP)
  val spp = Reg(UInt(spWidth bits)) init(config.initialSP + 1)
  val spm = Reg(UInt(spWidth bits)) init(config.initialSP - 1)

  val vp0 = Reg(UInt(spWidth bits)) init(0)
  val vp1 = Reg(UInt(spWidth bits)) init(0)
  val vp2 = Reg(UInt(spWidth bits)) init(0)
  val vp3 = Reg(UInt(spWidth bits)) init(0)

  val ar = Reg(UInt(spWidth bits)) init(0)

  // Mark key signals for waveform visibility (prevent Verilator pruning)
  Seq(a, b).foreach(_.simPublic())
  Seq(sp, spp, spm, vp0, vp1, vp2, vp3, ar).foreach(_.simPublic())
  val vpadd = Reg(UInt(spWidth bits)) init(0)

  val opddly = Reg(Bits(16 bits)) init(0)
  val immval = Reg(Bits(config.width bits)) init(0)

  val spOvReg = Reg(Bool()) init(False)

  // Rotation busy signal (False when cache disabled)
  val rotBusy = if (useCache) Bool() else False
  // Delayed rotBusy: registered decode effects (A/B/VP/AR/vpadd/pipeWrEn) are from
  // I_{T-1}, one cycle behind the combinational rotBusy which fires from I_T.
  // Using rotBusyDly allows I_{T-1}'s registered effects to complete on the first
  // stall cycle before gating kicks in.
  val rotBusyDly = RegNext(rotBusy) init(False)
  rotBusyDly.simPublic()

  // ==========================================================================
  // Barrel Shifter Instance
  // ==========================================================================

  val shifter = Shift(config.width)
  shifter.io.din := b.asUInt
  shifter.io.off := a(4 downto 0).asUInt
  shifter.io.shtyp := io.selShf
  val sout = shifter.io.dout.asBits

  // ==========================================================================
  // Stack Pointer Mux (smux) — computed early for rotation controller
  // ==========================================================================

  val smuxSignal = UInt(spWidth bits)
  switch(io.selSmux) {
    is(B"2'b00") { smuxSignal := sp }
    is(B"2'b01") { smuxSignal := spm }
    is(B"2'b10") { smuxSignal := spp }
    is(B"2'b11") { smuxSignal := a(spWidth - 1 downto 0).asUInt }
  }

  // ==========================================================================
  // Read/Write Address Signals (spWidth)
  // ==========================================================================

  val rdaddr = UInt(spWidth bits)
  val wraddr = UInt(spWidth bits)
  rdaddr.simPublic()
  wraddr.simPublic()

  // Memory data mux
  val mmux = Bits(config.width bits)
  mmux.simPublic()
  when(io.selMmux === False) {
    mmux := a
  } otherwise {
    mmux := b
  }

  // Write delay (1 cycle, matching VHDL aram.vhd)
  // Gate during rotation to preserve the in-flight write from the previous
  // instruction.  Without gating, wrEnaDly advances during the stall,
  // permanently losing the previous instruction's write (suppressed by
  // pipeWrEn = wrEnaDly && !rotBusy).  On un-stall the preserved write fires.
  val wrEnaDly = Reg(Bool()) init(False)
  val wrAddrDly = Reg(UInt(spWidth bits)) init(0)
  wrEnaDly.simPublic()
  wrAddrDly.simPublic()
  when(!rotBusy) {
    wrEnaDly := io.wrEna
    wrAddrDly := wraddr
  }

  // ==========================================================================
  // Stack RAM Subsystem (conditional: single-RAM or multi-bank)
  // ==========================================================================

  val ramDout = Bits(config.width bits)
  ramDout.simPublic()
  val ramRdaddrReg = Reg(UInt(spWidth bits)) init(0)
  ramRdaddrReg.simPublic()
  // Gate during rotation: keeps registered read address aligned with the gated
  // registered decode (both from the pre-stall instruction).  Without gating,
  // ramRdaddrReg advances to the stall-trigger instruction's address while the
  // registered decode stays at the previous instruction's values — causing A to
  // load data from the wrong stack address.
  when(!rotBusy) {
    ramRdaddrReg := rdaddr
  }

  if (!useCache) {
    // ------------------------------------------------------------------
    // Original Single-RAM Design
    // ------------------------------------------------------------------

    val stackRam = Mem(Bits(config.width bits), 1 << config.ramWidth)

    ramInit.foreach { initData =>
      val ramSize = 1 << config.ramWidth
      val paddedInit = initData.padTo(ramSize, BigInt(0))
      println(s"Stack RAM init (no shift): want RAM[32]=${initData(32)}, RAM[38]=${initData(38)}, RAM[45]=${initData(45)}")
      stackRam.init(paddedInit.map { v =>
        val unsigned = if (v < 0) v + (BigInt(1) << config.width) else v
        B(unsigned.toLong, config.width bits)
      })
    }

    // Debug write port takes priority
    val effectiveWrAddr = Mux(io.debugRamWrEn, io.debugRamWrAddr.resize(spWidth), wrAddrDly)
    val effectiveWrData = Mux(io.debugRamWrEn, io.debugRamWrData, mmux)
    val effectiveWrEn = io.debugRamWrEn | wrEnaDly

    stackRam.write(
      address = effectiveWrAddr.resize(config.ramWidth),
      data    = effectiveWrData,
      enable  = effectiveWrEn
    )

    ramDout := stackRam.readAsync(ramRdaddrReg.resize(config.ramWidth))

    io.debugRamData := stackRam.readAsync(io.debugRamAddr)

  } else {
    // ------------------------------------------------------------------
    // Multi-Bank Stack Cache Design
    // ------------------------------------------------------------------

    val cc = config.cacheConfig.get

    // Scratch RAM: 64 entries, addresses 0-63 (never rotated)
    val scratchRam = Mem(Bits(config.width bits), cc.scratchSize)

    // Initialize scratch from ramInit (entries 0-63)
    ramInit.foreach { initData =>
      val scratchInit = initData.take(cc.scratchSize).padTo(cc.scratchSize, BigInt(0))
      scratchRam.init(scratchInit.map { v =>
        val unsigned = if (v < 0) v + (BigInt(1) << config.width) else v
        B(unsigned.toLong, config.width bits)
      })
    }

    // 3 Bank RAMs: 256 entries each (192 usable)
    val bankRams = Seq.fill(cc.numBanks)(Mem(Bits(config.width bits), cc.bankPhysicalSize))

    // Bank descriptor registers
    val bankBaseVAddr = Vec(Reg(UInt(spWidth bits)), cc.numBanks)
    val bankResident  = Vec(Reg(Bool()), cc.numBanks)
    val bankDirty     = Vec(Reg(Bool()), cc.numBanks)
    val activeBankIdx = Reg(UInt(2 bits)) init(0)
    bankBaseVAddr.foreach(_.simPublic())
    bankResident.foreach(_.simPublic())
    activeBankIdx.simPublic()

    // Initial bank layout: bank[i] covers [64 + i*192, 64 + (i+1)*192)
    for (i <- 0 until cc.numBanks) {
      bankBaseVAddr(i).init(cc.scratchSize + i * cc.bankSize)
      bankResident(i).init(True)
      bankDirty(i).init(False)
    }

    // Rotation controller state and registers (declared early for bank write MUX)
    object RotState extends SpinalEnum {
      val IDLE, SPILL_START, SPILL_WAIT, FILL_START, FILL_WAIT, ZERO_FILL = newElement()
    }

    val rotState = Reg(RotState()) init(RotState.IDLE)
    rotState.simPublic()
    val rotVictimIdx = Reg(UInt(2 bits)) init(0)
    val rotTargetBase = Reg(UInt(spWidth bits)) init(0)
    val rotNeedFill = Reg(Bool()) init(False)
    val zeroFillCnt = Reg(UInt(8 bits)) init(0)

    // Zero-fill write signal (asserted during ZERO_FILL state, see rotation controller)
    val zeroFillActive = Bool()
    zeroFillActive := False

    // ------------------------------------------------------------------
    // Read Path: parallel read all RAMs, MUX by address translation
    // ------------------------------------------------------------------

    val rdIsScratch = ramRdaddrReg < cc.scratchSize
    val scratchDout = scratchRam.readAsync(ramRdaddrReg.resize(log2Up(cc.scratchSize)))

    // Compute physical address for each bank and check if it covers the read address
    val bankRdPhysAddr = Vec(UInt(8 bits), cc.numBanks)
    val bankRdHit = Vec(Bool(), cc.numBanks)
    val bankRdDout = Vec(Bits(config.width bits), cc.numBanks)

    for (i <- 0 until cc.numBanks) {
      bankRdPhysAddr(i) := (ramRdaddrReg - bankBaseVAddr(i)).resize(8)
      bankRdHit(i) := ramRdaddrReg >= bankBaseVAddr(i) &&
                       ramRdaddrReg < (bankBaseVAddr(i) + cc.bankSize) &&
                       bankResident(i)
      bankRdDout(i) := bankRams(i).readAsync(bankRdPhysAddr(i))
    }

    // MUX: scratch takes priority, then banks
    ramDout := 0
    when(rdIsScratch) {
      ramDout := scratchDout
    }.elsewhen(bankRdHit(0)) {
      ramDout := bankRdDout(0)
    }.elsewhen(bankRdHit(1)) {
      ramDout := bankRdDout(1)
    }.elsewhen(bankRdHit(2)) {
      ramDout := bankRdDout(2)
    }

    // ------------------------------------------------------------------
    // Write Path: route to correct RAM
    // ------------------------------------------------------------------

    // Pipeline write (delayed by 1 cycle)
    val pipeWrAddr = wrAddrDly
    val pipeWrData = mmux
    // Gate pipeline writes during rotation (use rotBusyDly: wrEnaDly is from I_{T-1})
    val pipeWrEn = wrEnaDly && !rotBusyDly
    pipeWrAddr.simPublic()
    pipeWrData.simPublic()
    pipeWrEn.simPublic()

    val pipeWrIsScratch = pipeWrAddr < cc.scratchSize

    val pipeWrBankHit = Vec(Bool(), cc.numBanks)
    val pipeWrBankPhys = Vec(UInt(8 bits), cc.numBanks)
    for (i <- 0 until cc.numBanks) {
      pipeWrBankPhys(i) := (pipeWrAddr - bankBaseVAddr(i)).resize(8)
      pipeWrBankHit(i) := pipeWrAddr >= bankBaseVAddr(i) &&
                           pipeWrAddr < (bankBaseVAddr(i) + cc.bankSize) &&
                           bankResident(i)
    }

    // Debug write port (scratch only)
    val debugWrEn = io.debugRamWrEn
    val debugWrAddr = io.debugRamWrAddr
    val debugWrData = io.debugRamWrData

    // Scratch RAM write: pipeline or debug
    scratchRam.write(
      address = Mux(debugWrEn, debugWrAddr.resize(log2Up(cc.scratchSize)),
                     pipeWrAddr.resize(log2Up(cc.scratchSize))),
      data    = Mux(debugWrEn, debugWrData, pipeWrData),
      enable  = (debugWrEn && debugWrAddr < cc.scratchSize) ||
                (pipeWrEn && pipeWrIsScratch)
    )

    // Bank RAM writes: pipeline, DMA, or zero-fill (mutually exclusive per bank)
    val dmaWrEn = io.dmaBankWrEn.get
    val dmaWrAddr = io.dmaBankWrAddr.get
    val dmaWrData = io.dmaBankWrData.get
    val dmaBankSel = io.dmaBankSelect.get

    for (i <- 0 until cc.numBanks) {
      val isZeroFillTarget = zeroFillActive && rotVictimIdx === U(i, 2 bits)
      val isDmaTarget = dmaWrEn && dmaBankSel === i
      val isPipeTarget = pipeWrEn && !pipeWrIsScratch && pipeWrBankHit(i)
      bankRams(i).write(
        address = Mux(isZeroFillTarget, zeroFillCnt,
                  Mux(isDmaTarget, dmaWrAddr, pipeWrBankPhys(i))),
        data    = Mux(isZeroFillTarget, B(0, config.width bits),
                  Mux(isDmaTarget, dmaWrData, pipeWrData)),
        enable  = isZeroFillTarget || isDmaTarget || isPipeTarget
      )
      // Mark bank dirty on pipeline write
      when(isPipeTarget) {
        bankDirty(i) := True
      }
    }

    // Debug read (scratch only)
    io.debugRamData := scratchRam.readAsync(io.debugRamAddr.resize(log2Up(cc.scratchSize)))

    // DMA read port: read all banks at DMA address, MUX by selection
    val dmaRdAddr = io.dmaBankRdAddr.get
    val dmaBankRdSel = io.dmaBankSelect.get
    val dmaBankDouts = Vec(Bits(config.width bits), cc.numBanks)
    for (i <- 0 until cc.numBanks) {
      dmaBankDouts(i) := bankRams(i).readAsync(dmaRdAddr)
    }
    io.dmaBankRdData.get := dmaBankDouts(dmaBankRdSel)

    // ------------------------------------------------------------------
    // Rotation Controller
    // ------------------------------------------------------------------

    // Check if smuxSignal is in the active bank
    val activeBase = bankBaseVAddr(activeBankIdx)
    val activeEnd = activeBase + cc.bankSize
    val smuxInScratch = smuxSignal < cc.scratchSize
    val smuxInActiveBank = smuxSignal >= activeBase && smuxSignal < activeEnd

    // Check all banks for coverage
    val bankCoversSmux = Vec(Bool(), cc.numBanks)
    for (i <- 0 until cc.numBanks) {
      bankCoversSmux(i) := smuxSignal >= bankBaseVAddr(i) &&
                            smuxSignal < (bankBaseVAddr(i) + cc.bankSize) &&
                            bankResident(i)
    }
    val anyBankCoversSmux = bankCoversSmux.reduce(_ || _)
    // Priority encoder for covering bank
    val coveringBankIdx = UInt(2 bits)
    coveringBankIdx := 0
    when(bankCoversSmux(2)) { coveringBankIdx := 2 }
    when(bankCoversSmux(1)) { coveringBankIdx := 1 }
    when(bankCoversSmux(0)) { coveringBankIdx := 0 }

    // Need rotation when smux is outside active bank and not in scratch
    val needsRotation = !smuxInScratch && !smuxInActiveBank && rotState === RotState.IDLE
    val canInstantSwitch = needsRotation && anyBankCoversSmux

    // Victim selection: (activeBankIdx + 2) % 3 — farthest from active
    val victimChoice = UInt(2 bits)
    switch(activeBankIdx) {
      is(0) { victimChoice := 2 }
      is(1) { victimChoice := 0 }
      default { victimChoice := 1 }
    }

    // Is this an underflow (need data from ext mem) or overflow (new range)?
    val isUnderflow = smuxSignal < activeBase && !smuxInScratch

    // DMA control defaults
    io.dmaStart.get := False
    io.dmaIsSpill.get := False
    io.dmaExtAddr.get := 0
    io.dmaWordCount.get := cc.bankSize
    io.dmaBank.get := 0

    // Helper: compute external byte address for a bank's virtual base
    // spillBaseAddr is a word address, wider than spWidth (16 bits)
    val wordW = cc.wordAddrWidth  // word address width (addressWidth - 2 type bits)
    val byteW = wordW + 2        // byte address width
    def extByteAddr(bankBase: UInt): UInt = {
      val spillBase = U(cc.spillBaseAddr, wordW bits)
      val bankOffset = bankBase.resize(wordW) - cc.scratchSize
      ((spillBase + bankOffset) << 2).resize(byteW)
    }

    switch(rotState) {
      is(RotState.IDLE) {
        when(needsRotation && !canInstantSwitch) {
          // Need DMA: assign victim
          val victim = victimChoice
          rotVictimIdx := victim
          rotNeedFill := isUnderflow

          // Compute target base for new bank assignment
          when(smuxSignal >= activeEnd) {
            rotTargetBase := activeEnd  // Overflow: bank above active
          }.otherwise {
            rotTargetBase := activeBase - cc.bankSize  // Underflow: bank below active
          }

          when(bankDirty(victim)) {
            // Spill victim first
            rotState := RotState.SPILL_START
          }.elsewhen(isUnderflow) {
            // Clean victim, underflow: reassign and fill
            bankBaseVAddr(victim) := Mux(smuxSignal >= activeEnd, activeEnd,
                                         activeBase - cc.bankSize)
            bankResident(victim) := False
            rotState := RotState.FILL_START
          }.otherwise {
            // Clean victim, overflow: reassign and zero-fill to prevent stale reads
            bankBaseVAddr(victim) := activeEnd
            bankResident(victim) := False  // Not resident until zero-filled
            bankDirty(victim) := False
            rotVictimIdx := victim
            zeroFillCnt := 0
            rotState := RotState.ZERO_FILL
          }
        }.elsewhen(canInstantSwitch) {
          activeBankIdx := coveringBankIdx
        }
      }

      is(RotState.SPILL_START) {
        // Assert DMA start for 1 cycle (DMA is in IDLE)
        io.dmaStart.get := True
        io.dmaIsSpill.get := True
        io.dmaBank.get := rotVictimIdx
        io.dmaExtAddr.get := extByteAddr(bankBaseVAddr(rotVictimIdx))
        io.dmaWordCount.get := cc.bankSize
        rotState := RotState.SPILL_WAIT
      }

      is(RotState.SPILL_WAIT) {
        when(io.dmaDone.get) {
          bankDirty(rotVictimIdx) := False
          bankResident(rotVictimIdx) := False
          bankBaseVAddr(rotVictimIdx) := rotTargetBase
          when(rotNeedFill) {
            rotState := RotState.FILL_START
          }.otherwise {
            // Overflow: bank assigned to new range, zero-fill to prevent stale reads
            bankResident(rotVictimIdx) := False  // Not resident until zero-filled
            bankDirty(rotVictimIdx) := False
            zeroFillCnt := 0
            rotState := RotState.ZERO_FILL
          }
        }
      }

      is(RotState.FILL_START) {
        // Assert DMA start for fill (DMA returned to IDLE after spill DONE)
        io.dmaStart.get := True
        io.dmaIsSpill.get := False
        io.dmaBank.get := rotVictimIdx
        io.dmaExtAddr.get := extByteAddr(rotTargetBase)
        io.dmaWordCount.get := cc.bankSize
        rotState := RotState.FILL_WAIT
      }

      is(RotState.FILL_WAIT) {
        when(io.dmaDone.get) {
          bankResident(rotVictimIdx) := True
          activeBankIdx := rotVictimIdx
          rotState := RotState.IDLE
        }
      }

      is(RotState.ZERO_FILL) {
        // Write zeros to bank[rotVictimIdx] at physical address zeroFillCnt.
        // This prevents stale data reads after overflow bank reassignment:
        // the invoke sequence's pop instructions after stsp read from the
        // newly-assigned bank before the pipeline has written to it.
        zeroFillActive := True
        zeroFillCnt := zeroFillCnt + 1
        when(zeroFillCnt === (cc.bankSize - 1)) {
          // Zero-fill complete: bank is now safe to use
          bankResident(rotVictimIdx) := True
          bankDirty(rotVictimIdx) := False
          activeBankIdx := rotVictimIdx
          rotState := RotState.IDLE
        }
      }
    }

    // Rotation busy: pipeline stalls during any non-IDLE rotation state
    // Also stall for 1 cycle when rotation is needed but not instant-switchable
    rotBusy := (rotState =/= RotState.IDLE) ||
               (needsRotation && !canInstantSwitch)

    io.rotationBusy.get := rotBusy

    // Debug outputs
    io.scDebugRotState.get := rotState.asBits.asUInt.resize(3)
    io.scDebugActiveBankIdx.get := activeBankIdx
    io.scDebugBankBase.get(0) := bankBaseVAddr(0)
    io.scDebugBankBase.get(1) := bankBaseVAddr(1)
    io.scDebugBankBase.get(2) := bankBaseVAddr(2)
    io.scDebugBankResident.get := bankResident(2) ## bankResident(1) ## bankResident(0)
    io.scDebugBankDirty.get := bankDirty(2) ## bankDirty(1) ## bankDirty(0)
    io.scDebugNeedsRot.get := needsRotation

    // Write-snoop debug — registered to match actual Mem.write timing.
    // At clock edge N, Mem.write captures pre-edge pipeWrEn/pipeWrAddr/pipeWrData.
    // RegNext captures these same pre-edge values, so after edge N the debug
    // outputs show what was ACTUALLY written at edge N (visible one cycle later).
    io.scDebugPipeWrAddr.get := RegNext(pipeWrAddr) init 0
    io.scDebugPipeWrData.get := RegNext(pipeWrData) init 0
    io.scDebugPipeWrEn.get := RegNext(pipeWrEn) init False

    // VP+0 readback: async read of bank RAM at virtual address vp0
    val vp0IsScratch = vp0 < cc.scratchSize
    val vp0BankHit = Vec(Bool(), cc.numBanks)
    val vp0BankPhys = Vec(UInt(8 bits), cc.numBanks)
    val vp0BankDout = Vec(Bits(config.width bits), cc.numBanks)
    for (i <- 0 until cc.numBanks) {
      vp0BankPhys(i) := (vp0 - bankBaseVAddr(i)).resize(8)
      vp0BankHit(i) := vp0 >= bankBaseVAddr(i) &&
                        vp0 < (bankBaseVAddr(i) + cc.bankSize) &&
                        bankResident(i)
      vp0BankDout(i) := bankRams(i).readAsync(vp0BankPhys(i))
    }
    val vp0Data = Bits(config.width bits)
    vp0Data := 0
    when(vp0IsScratch) {
      vp0Data := scratchRam.readAsync(vp0.resize(log2Up(cc.scratchSize)))
    }.elsewhen(vp0BankHit(0)) {
      vp0Data := vp0BankDout(0)
    }.elsewhen(vp0BankHit(1)) {
      vp0Data := vp0BankDout(1)
    }.elsewhen(vp0BankHit(2)) {
      vp0Data := vp0BankDout(2)
    }
    io.scDebugVp0Data.get := vp0Data
  }

  // ==========================================================================
  // 33-bit ALU for Correct Overflow/Comparison
  // ==========================================================================

  val sum = SInt(33 bits)
  val aExt = S(B"1'b0" ## a)
  val bExt = S(B"1'b0" ## b)

  val aSigned = (a(config.width - 1) ## a).asSInt.resize(33 bits)
  val bSigned = (b(config.width - 1) ## b).asSInt.resize(33 bits)

  when(io.selSub) {
    sum := bSigned - aSigned
  } otherwise {
    sum := bSigned + aSigned
  }

  io.lt := sum(32)

  // ==========================================================================
  // Logic Unit
  // ==========================================================================

  val log = Bits(config.width bits)

  switch(io.selLog) {
    is(B"2'b00") { log := b }
    is(B"2'b01") { log := a & b }
    is(B"2'b10") { log := a | b }
    is(B"2'b11") { log := a ^ b }
  }

  // ==========================================================================
  // Register Mux (rmux) — widened for stack cache
  // ==========================================================================

  val rmux = UInt(config.rmuxWidth bits)

  switch(io.selRmux) {
    is(B"2'b00") {
      rmux := sp.resize(config.rmuxWidth)
    }
    is(B"2'b01") {
      rmux := vp0.resize(config.rmuxWidth)
    }
    default {
      rmux := io.jpc.resize(config.rmuxWidth)
    }
  }

  // ==========================================================================
  // Immediate Mux (imux)
  // ==========================================================================

  val imux = Bits(config.width bits)

  switch(io.selImux) {
    is(B"2'b00") { imux := B(0, 24 bits) ## opddly(7 downto 0) }
    is(B"2'b01") { imux := S(opddly(7 downto 0)).resize(config.width).asBits }
    is(B"2'b10") { imux := B(0, 16 bits) ## opddly }
    is(B"2'b11") { imux := S(opddly).resize(config.width).asBits }
  }

  // ==========================================================================
  // Load Mux (lmux)
  // ==========================================================================

  val lmux = Bits(config.width bits)
  lmux.simPublic()

  switch(io.selLmux) {
    is(B"3'b000") { lmux := log }
    is(B"3'b001") { lmux := sout }
    is(B"3'b010") { lmux := ramDout }
    is(B"3'b011") { lmux := immval }
    is(B"3'b100") { lmux := io.din }
    default       { lmux := rmux.asBits.resized }
  }

  // ==========================================================================
  // A Input Mux (amux)
  // ==========================================================================

  val amux = Bits(config.width bits)
  amux.simPublic()

  when(io.selAmux === False) {
    amux := sum(31 downto 0).asBits
  } otherwise {
    amux := lmux
  }

  // ==========================================================================
  // Read Address Mux (sel_rda) — widened for stack cache
  // ==========================================================================

  switch(io.selRda) {
    is(B"3'b000") { rdaddr := vp0 }
    is(B"3'b001") { rdaddr := vp1 }
    is(B"3'b010") { rdaddr := vp2 }
    is(B"3'b011") { rdaddr := vp3 }
    is(B"3'b100") { rdaddr := vpadd }
    is(B"3'b101") { rdaddr := ar }
    is(B"3'b110") { rdaddr := sp }
    default       { rdaddr := io.dirAddr.resize(spWidth) }
  }

  // ==========================================================================
  // Write Address Mux (sel_wra) — widened for stack cache
  // ==========================================================================

  switch(io.selWra) {
    is(B"3'b000") { wraddr := vp0 }
    is(B"3'b001") { wraddr := vp1 }
    is(B"3'b010") { wraddr := vp2 }
    is(B"3'b011") { wraddr := vp3 }
    is(B"3'b100") { wraddr := vpadd }
    is(B"3'b101") { wraddr := ar }
    is(B"3'b110") { wraddr := spp }
    default       { wraddr := io.dirAddr.resize(spWidth) }
  }

  // ==========================================================================
  // Combinational Flag Outputs
  // ==========================================================================

  io.zf := (a === B(0, config.width bits))
  io.nf := a(config.width - 1)
  io.eq := (a === b)

  // ==========================================================================
  // A and B Register Process
  // ==========================================================================
  // Gate A/B updates during rotation to prevent corruption from changing bank
  // layouts. During rotBusy, readAsync returns stale data from reassigned banks;
  // without gating, enaA/enaB (still asserted from frozen decode) would latch
  // that wrong data into A/B, corrupting TOS/NOS.

  when(io.enaA && !rotBusyDly) {
    a := amux
  }

  when(io.enaB && !rotBusyDly) {
    when(io.selBmux === False) {
      b := a
    } otherwise {
      b := ramDout
    }
  }

  // Debug: capture the values that ACTUALLY drove B at each edge
  // (RegNext captures pre-edge inputs, visible 1 cycle later in sim)
  val bDbgEnaB = RegNext(io.enaB && !rotBusyDly) init(False)
  val bDbgSelBmux = RegNext(io.selBmux) init(False)
  val bDbgRamDout = RegNext(ramDout) init(0)
  val bDbgA = RegNext(a) init(0)
  val bDbgRdAddr = RegNext(ramRdaddrReg) init(0)
  bDbgEnaB.simPublic()
  bDbgSelBmux.simPublic()
  bDbgRamDout.simPublic()
  bDbgA.simPublic()
  bDbgRdAddr.simPublic()

  // ==========================================================================
  // Stack Pointer and VP Register Process
  // ==========================================================================

  // Gate SP updates during rotation (prevents repeated SP increment during stall)
  when(!rotBusy) {
    spp := smuxSignal + 1
    spm := smuxSignal - 1
    sp  := smuxSignal
  }

  // Stack overflow detection (only in single-RAM mode)
  if (!useCache) {
    when(sp === U(config.stackOverflowThreshold, spWidth bits)) {
      spOvReg := True
    }
  }

  // VP/AR registers: gate during rotation (use rotBusyDly — enaVp is registered)
  when(io.enaVp && !rotBusyDly) {
    vp0 := a(spWidth - 1 downto 0).asUInt
    vp1 := a(spWidth - 1 downto 0).asUInt + 1
    vp2 := a(spWidth - 1 downto 0).asUInt + 2
    vp3 := a(spWidth - 1 downto 0).asUInt + 3
  }

  // AR register update (use rotBusyDly — enaAr is registered)
  when(io.enaAr && !rotBusyDly) {
    ar := a(spWidth - 1 downto 0).asUInt
  }

  // VP + offset calculation (registered) — gate during rotation (rotBusyDly: vp0 is registered)
  when(!rotBusyDly) {
    vpadd := vp0 + io.opd(6 downto 0).asUInt.resize(spWidth)
  }

  // Operand delay pipeline
  opddly := io.opd
  immval := imux

  // ==========================================================================
  // Output Assignments
  // ==========================================================================

  io.spOv := spOvReg
  io.aout := a
  io.bout := b

  io.debugSp := sp
  io.debugVp := vp0
  io.debugAr := ar
  io.debugWrAddr := wrAddrDly
  io.debugWrEn := wrEnaDly
  io.debugRdAddrReg := ramRdaddrReg
  io.debugRamDout := ramDout

  // Pipeline decode debug
  io.debugEnaA     := io.enaA
  io.debugSelLmux  := io.selLmux
  io.debugEnaB     := io.enaB
  io.debugSelBmux  := io.selBmux
  io.debugRamDoutVal := ramDout
  io.debugLmuxVal    := lmux
}


/**
 * Test Bench Wrapper for StackStage (CocoTB Compatible)
 *
 * This wrapper provides the same interface as stack_tb.vhd for CocoTB testing.
 * Uses noIoPrefix() to remove the io_ prefix from port names.
 *
 * Interface matches stack_tb.vhd exactly for seamless CocoTB verification.
 * Key differences from main StackStage:
 * - Explicit clk and reset ports
 * - Flat port structure (no bundles)
 * - Debug outputs for internal state visibility
 * - Uses ClockingArea for proper clock domain handling
 */
case class StackStageTb(
  width: Int = 32,
  jpcWidth: Int = 10,
  ramWidth: Int = 8
) extends Component {

  // Use noIoPrefix to match VHDL interface naming
  noIoPrefix()

  // Clock and reset
  val clk   = in Bool()
  val reset = in Bool()

  // Data Inputs
  val din = in Bits(width bits)
  val dir = in Bits(ramWidth bits)
  val opd = in Bits(16 bits)
  val jpc = in Bits(jpcWidth + 1 bits)

  // ALU Control Inputs
  val sel_sub  = in Bool()
  val sel_amux = in Bool()
  val ena_a    = in Bool()
  val sel_bmux = in Bool()
  val sel_log  = in Bits(2 bits)
  val sel_shf  = in Bits(2 bits)
  val sel_lmux = in Bits(3 bits)
  val sel_imux = in Bits(2 bits)
  val sel_rmux = in Bits(2 bits)
  val sel_smux = in Bits(2 bits)
  val sel_mmux = in Bool()
  val sel_rda  = in Bits(3 bits)
  val sel_wra  = in Bits(3 bits)
  val wr_ena   = in Bool()
  val ena_b    = in Bool()
  val ena_vp   = in Bool()
  val ena_ar   = in Bool()

  // Outputs
  val sp_ov = out Bool()
  val zf    = out Bool()
  val nf    = out Bool()
  val `eq`  = out Bool()  // backticks to avoid Object.eq conflict
  val lt    = out Bool()
  val aout  = out Bits(width bits)
  val bout  = out Bits(width bits)

  // Debug outputs (for testing internal state)
  val dbg_sp  = out Bits(ramWidth bits)
  val dbg_vp0 = out Bits(ramWidth bits)
  val dbg_ar  = out Bits(ramWidth bits)

  // Create a new clock domain using the explicit clk and reset signals
  val stackClockDomain = ClockDomain(
    clock = clk,
    reset = reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  // Instantiate the stack logic in the explicit clock domain
  val stackArea = new ClockingArea(stackClockDomain) {
    val config = StackConfig(width, jpcWidth, ramWidth)

    // ========================================================================
    // Internal Registers
    // ========================================================================

    val a = Reg(Bits(width bits)) init(0)
    val b = Reg(Bits(width bits)) init(0)

    val sp  = Reg(UInt(ramWidth bits)) init(config.initialSP)
    val spp = Reg(UInt(ramWidth bits)) init(config.initialSP + 1)
    val spm = Reg(UInt(ramWidth bits)) init(config.initialSP - 1)

    val vp0 = Reg(UInt(ramWidth bits)) init(0)
    val vp1 = Reg(UInt(ramWidth bits)) init(0)
    val vp2 = Reg(UInt(ramWidth bits)) init(0)
    val vp3 = Reg(UInt(ramWidth bits)) init(0)

    val ar = Reg(UInt(ramWidth bits)) init(0)
    val vpadd = Reg(UInt(ramWidth bits)) init(0)

    val opddly = Reg(Bits(16 bits)) init(0)
    val immval = Reg(Bits(width bits)) init(0)
    val spOvReg = Reg(Bool()) init(False)

    // ========================================================================
    // Embedded Barrel Shifter
    // ========================================================================
    // Using same algorithm as stack_tb.vhd's embedded shifter

    val sout = Bits(width bits)

    val shifterLogic = new Area {
      val shiftin = Bits(64 bits)
      val shiftcnt = UInt(5 bits)
      val zero32 = B(0, 32 bits)

      // Default setup for USHR
      shiftin := zero32 ## b
      shiftcnt := a(4 downto 0).asUInt

      when(sel_shf === B"2'b01") {
        // SHL (shift left): position data at upper bits, invert count
        shiftin := B"1'b0" ## b ## B(0, 31 bits)
        shiftcnt := ~a(4 downto 0).asUInt
      } elsewhen(sel_shf === B"2'b10") {
        // SHR (arithmetic shift right): sign extend
        when(b(31)) {
          shiftin := B"32'hFFFFFFFF" ## b
        } otherwise {
          shiftin := zero32 ## b
        }
      }
      // sel_shf = "00" or "11" is USHR (unsigned shift right)

      // Multi-stage barrel shifter
      val s0 = Bits(64 bits)
      when(shiftcnt(4)) {
        s0 := B(0, 16 bits) ## shiftin(63 downto 16)
      } otherwise {
        s0 := shiftin
      }

      val s1 = Bits(64 bits)
      when(shiftcnt(3)) {
        s1 := B(0, 8 bits) ## s0(63 downto 8)
      } otherwise {
        s1 := s0
      }

      val s2 = Bits(64 bits)
      when(shiftcnt(2)) {
        s2 := B(0, 4 bits) ## s1(63 downto 4)
      } otherwise {
        s2 := s1
      }

      val s3 = Bits(64 bits)
      when(shiftcnt(1)) {
        s3 := B(0, 2 bits) ## s2(63 downto 2)
      } otherwise {
        s3 := s2
      }

      val s4 = Bits(64 bits)
      when(shiftcnt(0)) {
        s4 := B(0, 1 bits) ## s3(63 downto 1)
      } otherwise {
        s4 := s3
      }

      sout := s4(31 downto 0)
    }

    // ========================================================================
    // Embedded Dual-Port RAM
    // ========================================================================

    val stackRam = Mem(Bits(width bits), 1 << ramWidth)

    val rdaddr = UInt(ramWidth bits)
    val wraddr = UInt(ramWidth bits)

    val mmux = Bits(width bits)
    when(sel_mmux === False) {
      mmux := a
    } otherwise {
      mmux := b
    }

    // Write directly to RAM - SpinalHDL Mem.write() is synchronous (registers internally)
    // Matching VHDL RAM component which also has internal registration
    stackRam.write(
      address = wraddr,
      data    = mmux,
      enable  = wr_ena
    )

    // Keep registered values for debug output only
    val ramWraddrReg = Reg(UInt(ramWidth bits)) init(0)
    val ramWrenReg   = Reg(Bool()) init(False)
    ramWraddrReg := wraddr
    ramWrenReg   := wr_ena

    // Registered read address, combinational read data
    val ramDout = stackRam.readSync(
      address = rdaddr,
      enable  = True
    )

    // ========================================================================
    // 33-bit ALU
    // ========================================================================

    val sum = Bits(33 bits)
    val aSigned = (a(width - 1) ## a).asSInt.resize(33 bits)
    val bSigned = (b(width - 1) ## b).asSInt.resize(33 bits)

    when(sel_sub) {
      sum := (bSigned - aSigned).asBits
    } otherwise {
      sum := (bSigned + aSigned).asBits
    }

    lt := sum(32)

    // ========================================================================
    // Logic Unit
    // ========================================================================

    val log = Bits(width bits)

    switch(sel_log) {
      is(B"2'b00") { log := b }
      is(B"2'b01") { log := a & b }
      is(B"2'b10") { log := a | b }
      is(B"2'b11") { log := a ^ b }
    }

    // ========================================================================
    // Register Mux
    // ========================================================================

    val rmux = Bits(jpcWidth + 1 bits)

    switch(sel_rmux) {
      is(B"2'b00") {
        rmux := sp.resize(jpcWidth + 1).asBits
      }
      is(B"2'b01") {
        rmux := vp0.resize(jpcWidth + 1).asBits
      }
      default {
        rmux := jpc
      }
    }

    // ========================================================================
    // Load Mux
    // ========================================================================

    val lmux = Bits(width bits)

    switch(sel_lmux) {
      is(B"3'b000") { lmux := log }
      is(B"3'b001") { lmux := sout }
      is(B"3'b010") { lmux := ramDout }
      is(B"3'b011") { lmux := immval }
      is(B"3'b100") { lmux := din }
      default {
        // Zero-extend rmux to 32 bits (VHDL uses unsigned interpretation)
        lmux := rmux.resized
      }
    }

    // ========================================================================
    // Immediate Mux
    // ========================================================================

    val imux = Bits(width bits)

    switch(sel_imux) {
      is(B"2'b00") {
        imux := B(0, 24 bits) ## opddly(7 downto 0)
      }
      is(B"2'b01") {
        imux := S(opddly(7 downto 0)).resize(width).asBits
      }
      is(B"2'b10") {
        imux := B(0, 16 bits) ## opddly
      }
      is(B"2'b11") {
        imux := S(opddly).resize(width).asBits
      }
    }

    // ========================================================================
    // A Input Mux
    // ========================================================================

    val amux = Bits(width bits)

    when(sel_amux === False) {
      amux := sum(31 downto 0)
    } otherwise {
      amux := lmux
    }

    // ========================================================================
    // Flags (combinational)
    // ========================================================================

    zf := (a === B(0, width bits))
    nf := a(width - 1)
    StackStageTb.this.`eq` := (a === b)

    // ========================================================================
    // Stack Pointer Mux
    // ========================================================================

    val smux = UInt(ramWidth bits)

    switch(sel_smux) {
      is(B"2'b00") { smux := sp }
      is(B"2'b01") { smux := spm }
      is(B"2'b10") { smux := spp }
      is(B"2'b11") { smux := a(ramWidth - 1 downto 0).asUInt }
    }

    // ========================================================================
    // Address Muxes
    // ========================================================================

    switch(sel_rda) {
      is(B"3'b000") { rdaddr := vp0 }
      is(B"3'b001") { rdaddr := vp1 }
      is(B"3'b010") { rdaddr := vp2 }
      is(B"3'b011") { rdaddr := vp3 }
      is(B"3'b100") { rdaddr := vpadd }
      is(B"3'b101") { rdaddr := ar }
      is(B"3'b110") { rdaddr := sp }
      default       { rdaddr := dir.asUInt }
    }

    switch(sel_wra) {
      is(B"3'b000") { wraddr := vp0 }
      is(B"3'b001") { wraddr := vp1 }
      is(B"3'b010") { wraddr := vp2 }
      is(B"3'b011") { wraddr := vp3 }
      is(B"3'b100") { wraddr := vpadd }
      is(B"3'b101") { wraddr := ar }
      is(B"3'b110") { wraddr := spp }  // Note: SPP for push
      default       { wraddr := dir.asUInt }
    }

    // ========================================================================
    // A and B Register Updates
    // ========================================================================

    when(ena_a) {
      a := amux
    }

    when(ena_b) {
      when(sel_bmux === False) {
        b := a
      } otherwise {
        b := ramDout
      }
    }

    // ========================================================================
    // Stack Pointer and VP Register Updates
    // ========================================================================

    spp := smux + 1
    spm := smux - 1
    sp  := smux

    when(sp === U(config.stackOverflowThreshold, ramWidth bits)) {
      spOvReg := True
    }

    when(ena_vp) {
      vp0 := a(ramWidth - 1 downto 0).asUInt
      vp1 := a(ramWidth - 1 downto 0).asUInt + 1
      vp2 := a(ramWidth - 1 downto 0).asUInt + 2
      vp3 := a(ramWidth - 1 downto 0).asUInt + 3
    }

    when(ena_ar) {
      ar := a(ramWidth - 1 downto 0).asUInt
    }

    vpadd := vp0 + opd(6 downto 0).asUInt.resize(ramWidth)
    opddly := opd
    immval := imux

    // ========================================================================
    // Output Assignments
    // ========================================================================

    sp_ov   := spOvReg
    aout    := a
    bout    := b
    dbg_sp  := sp.asBits
    dbg_vp0 := vp0.asBits
    dbg_ar  := ar.asBits
  }
}


/**
 * Generate VHDL for CocoTB verification
 */
object StackStageTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/vhdl",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    device = Device.ALTERA
  )

  config.generateVhdl(StackStageTb(width = 32, jpcWidth = 10, ramWidth = 8))
    .printPruned()

  println("StackStageTb VHDL generated at: generated/vhdl/StackStageTb.vhd")
}


/**
 * Generate Verilog for synthesis
 */
object StackStageVerilog extends App {
  val config = SpinalConfig(
    targetDirectory = "generated/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  )

  config.generateVerilog(StackStage())
    .printPruned()

  println("StackStage Verilog generated at: generated/verilog/StackStage.v")
}
