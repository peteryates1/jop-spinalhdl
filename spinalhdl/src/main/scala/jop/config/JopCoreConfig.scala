package jop.config

import spinal.core.HertzNumber
import jop.pipeline._
import jop.memory._

/** Per-bytecode implementation selection — uniform for all configurable bytecodes */
sealed trait Implementation
object Implementation {
  case object Java extends Implementation       // sys_noim → Java runtime fallback
  case object Microcode extends Implementation  // Pure microcode handler (no HW peripheral)
  case object Hardware extends Implementation   // Microcode → HW compute unit / ALU
}

/**
 * JOP Core Configuration
 *
 * Unified configuration for the complete JOP core.
 *
 * @param dataWidth    Data path width (32 bits)
 * @param pcWidth      Microcode PC width (11 bits = 2K ROM)
 * @param instrWidth   Microcode instruction width (10 bits)
 * @param jpcWidth     Java PC width (11 bits = 2KB bytecode cache)
 * @param ramWidth     Stack RAM address width (8 bits = 256 entries)
 * @param blockBits    Method cache block bits (4 = 16 blocks in JBC RAM)
 * @param memConfig    Memory subsystem configuration
 * @param supersetJumpTable  Superset jump table (all HW handlers present). Patched by resolveJumpTable().
 * @param cpuId        CPU identifier (for multi-core; 0 for single-core)
 * @param cpuCnt       Total number of CPUs (1 for single-core)
 * @param ioConfig     I/O device configuration (device presence, parameters, interrupts)
 * @param clkFreq      System clock frequency (for BmbSys microsecond prescaler)
 */
case class JopCoreConfig(
  dataWidth:    Int              = 32,
  pcWidth:      Int              = 11,
  instrWidth:   Int              = 10,
  jpcWidth:     Int              = 11,
  ramWidth:     Int              = 8,
  blockBits:    Int              = 4,
  memConfig:    JopMemoryConfig  = JopMemoryConfig(),
  supersetJumpTable: JumpTableInitData = JumpTableInitData.simulation,
  cpuId:        Int              = 0,
  cpuCnt:       Int              = 1,
  ioConfig:     IoConfig         = IoConfig(),
  clkFreq:      HertzNumber      = HertzNumber(100000000),
  useIhlu:      Boolean          = false,  // Use IHLU (per-object lock) instead of CmpSync (global lock)
  useStackCache: Boolean         = false,  // Use 3-bank rotating stack cache with DMA spill/fill
  spillBaseAddrOverride: Option[Int] = None, // Override spillBaseAddr (e.g., 0 for dedicated spill BRAM)
  useBmbFpu:    Boolean          = false,  // Legacy: use BmbFpu I/O peripheral instead of FloatComputeUnit

  // --- Per-bytecode implementation selection ---
  // Integer — always Hardware (IntegerComputeUnit). Microcode = iterative, future: Hardware = DSP.
  imul:  Implementation = Implementation.Microcode,  // Microcode=radix-4 ~18cyc, future Hardware=DSP 1cyc
  idiv:  Implementation = Implementation.Hardware,   // Hardware→IntegerComputeUnit DivUnit ~36cyc
  irem:  Implementation = Implementation.Hardware,   // Hardware→IntegerComputeUnit DivUnit ~36cyc

  // Float — Java (software) or Hardware (FloatComputeUnit / microcode)
  fadd:  Implementation = Implementation.Java,
  fsub:  Implementation = Implementation.Java,
  fmul:  Implementation = Implementation.Java,
  fdiv:  Implementation = Implementation.Java,
  fneg:  Implementation = Implementation.Java,  // Hardware → microcode XOR sign bit (no CU needed)
  i2f:   Implementation = Implementation.Java,
  f2i:   Implementation = Implementation.Java,
  fcmpl: Implementation = Implementation.Java,
  fcmpg: Implementation = Implementation.Java
) {
  import Implementation._

  // Convenience accessors
  def hasUart: Boolean = ioConfig.hasUart
  def hasEth: Boolean = ioConfig.hasEth
  def uartBaudRate: Int = ioConfig.uartBaudRate

  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 11, "PC width must be 11 bits (2K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")

  // --- Derived: what hardware to instantiate ---
  def needsIntegerCompute: Boolean = Seq(imul, idiv, irem).exists(_ != Java)
  def needsFloatCompute: Boolean   = Seq(fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg).exists(_ != Java)

  // Internal hardware within IntegerComputeUnit
  def needsIntMul: Boolean = imul != Java
  def needsIntDiv: Boolean = Seq(idiv, irem).exists(_ != Java)

  // BmbFpu I/O peripheral (legacy VexRiscv FPU path — only used when useBmbFpu=true)
  def needsBmbFpu: Boolean = useBmbFpu

  /** Resolve the jump table: patch IMP_JAVA bytecodes configured as Java to sys_noim.
    * Note: imul (0x68) is IMP_ASM in JopInstr.java — JOPizer does NOT replace it,
    * so the jump table entry must ALWAYS point to a working handler (sthw or software).
    * The microcode ROM controls which handler is used (HW_MUL flag).
    */
  def resolveJumpTable: JumpTableInitData = {
    // Only IMP_JAVA bytecodes are safe to patch — JOPizer replaces them with invokestatic.
    // imul (0x68) is IMP_ASM and must NOT be patched.
    val bytecodeMap: Seq[(Int, Implementation)] = Seq(
      0x6C -> idiv,  0x70 -> irem,
      0x62 -> fadd,  0x66 -> fsub,  0x6A -> fmul,  0x6E -> fdiv,
      0x76 -> fneg,  0x86 -> i2f,   0x8B -> f2i,
      0x95 -> fcmpl, 0x96 -> fcmpg
    )
    val javaBytecodes = bytecodeMap.collect { case (bc, Java) => bc }
    supersetJumpTable.disable(javaBytecodes: _*)
  }

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth,
    cacheConfig = if (useStackCache) Some(StackCacheConfig(
      burstLen = memConfig.burstLen,
      wordAddrWidth = memConfig.addressWidth - 2,
      spillBaseAddr = spillBaseAddrOverride.getOrElse {
        val memWords = (memConfig.mainMemSize / 4).toInt
        if (memConfig.stackRegionWordsPerCore > 0) {
          memWords - (cpuId + 1) * memConfig.stackRegionWordsPerCore
        } else if (memConfig.burstLen == 0) {
          (memWords * 3 / 4) + cpuId * 4096
        } else {
          0x780000 + cpuId * 0x8000
        }
      }
    )) else None
  )
  def bcfetchConfig = BytecodeFetchConfig(jpcWidth, pcWidth, resolveJumpTable)
}
