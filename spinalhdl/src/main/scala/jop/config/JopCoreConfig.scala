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
  useDspMul:    Boolean          = false,  // Use 1-cycle DSP multiplier in ALU (bypasses CU for imul)

  // --- Per-bytecode implementation selection ---
  // Integer — always Hardware (IntegerComputeUnit). Microcode = iterative, Hardware = CU or DSP.
  imul:  Implementation = Implementation.Microcode,  // Microcode=shift-add ~35cyc, Hardware=CU ~22cyc or DSP 2cyc
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
  // imul (0x68) is IMP_ASM — JOPizer does NOT replace it with invokestatic.
  // The jump table must always point to a working handler (sthw or software).
  // Java is not a valid option because sys_noim expects invokestatic operands.
  require(imul != Java, "imul: Java is invalid — imul is IMP_ASM. Use Microcode or Hardware.")

  // --- Derived: what hardware to instantiate ---
  // Only Hardware needs actual compute unit instantiation.
  // Microcode uses pure-microcode handlers (e.g., imul_sw), Java uses sys_noim → JVM.f_xxx().
  def needsIntegerCompute: Boolean = Seq(imul, idiv, irem).exists(_ == Hardware)
  def needsFloatCompute: Boolean   = Seq(fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg).exists(_ == Hardware)

  // Internal hardware within IntegerComputeUnit
  def needsIntMul: Boolean = imul == Hardware
  def needsIntDiv: Boolean = Seq(idiv, irem).exists(_ == Hardware)

  // BmbFpu I/O peripheral (legacy VexRiscv FPU path — only used when useBmbFpu=true)
  def needsBmbFpu: Boolean = useBmbFpu

  /** Resolve the jump table: patch bytecodes based on their configured Implementation.
    *
    * - Java → patch to sys_noim (JOPizer replaces these with invokestatic)
    * - Microcode → use alternate handler from ROM if available (e.g., imul_sw)
    * - Hardware → keep default (HW handler from superset ROM)
    *
    * Note: imul (0x68) is IMP_ASM in JopInstr.java — JOPizer does NOT replace it,
    * so the jump table entry must ALWAYS point to a working handler.
    * When imul: Microcode, we use the imul_sw alternate handler.
    * When imul: Hardware, we use the default sthw handler.
    */
  def resolveJumpTable: JumpTableInitData = {
    val bytecodeMap: Seq[(Int, Implementation)] = Seq(
      0x68 -> imul,  0x6C -> idiv,  0x70 -> irem,
      0x62 -> fadd,  0x66 -> fsub,  0x6A -> fmul,  0x6E -> fdiv,
      0x76 -> fneg,  0x86 -> i2f,   0x8B -> f2i,
      0x95 -> fcmpl, 0x96 -> fcmpg
    )
    var result = supersetJumpTable
    for ((bc, impl) <- bytecodeMap) {
      impl match {
        case Java     => result = result.disable(bc)
        case Microcode => result = result.useAlt(bc)
        case Hardware =>
          // When useDspMul, patch imul to DSP handler instead of CU handler
          if (bc == 0x68 && useDspMul) {
            result = result.useDspAlt(bc)
          }
          // else keep default HW handler
      }
    }
    result
  }

  def fetchConfig = FetchConfig(pcWidth, instrWidth)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth,
    useDspMul = useDspMul,
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
