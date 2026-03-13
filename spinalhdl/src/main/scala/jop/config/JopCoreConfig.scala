package jop.config

import spinal.core.HertzNumber
import jop.io.{DeviceContext, DeviceTypes, IoDeviceDescriptor}
import jop.pipeline._
import jop.memory._

/** Per-bytecode implementation selection — uniform for all configurable bytecodes */
sealed trait Implementation
object Implementation {
  case object Java extends Implementation       // sys_noim → Java runtime fallback
  case object Microcode extends Implementation  // Pure microcode handler (no HW peripheral)
  case object Hardware extends Implementation   // Microcode → HW compute unit / ALU
}

/** Memory primitive style — controls ROM/RAM instantiation in pipeline stages.
  * Generic:   SpinalHDL Mem with init() → $readmemb inference (works on Cyclone IV, Xilinx)
  * AlteraLpm: lpm_rom / lpm_ram_dp BlackBox with .mif → works on all Altera incl. MAX10 */
sealed trait MemoryStyle
object MemoryStyle {
  case object Generic extends MemoryStyle
  case object AlteraLpm extends MemoryStyle
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
 * @param clkFreq      System clock frequency (for Sys microsecond prescaler)
 */
case class JopCoreConfig(
  dataWidth:    Int              = 32,
  pcWidth:      Int              = 12,
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
  useCmpSync:   Boolean          = false,  // Use CmpSync (global lock) instead of IHLU (per-object lock)
  useStackCache: Boolean         = false,  // Use 3-bank rotating stack cache with DMA spill/fill
  spillBaseAddrOverride: Option[Int] = None, // Override spillBaseAddr (e.g., 0 for dedicated spill BRAM)
  useDspMul:    Boolean          = false,  // Use 1-cycle DSP multiplier in ALU (bypasses CU for imul)
  useSyncRam:   Option[Boolean]  = None,   // None = auto (true for all — readAsync emits ram_style=distributed); Some(x) = explicit override
  memoryStyle:  Option[MemoryStyle] = None, // None = auto from FPGA family; Some(x) = explicit override
  devices:      Map[String, DeviceInstance] = Map.empty, // Declarative device list (when non-empty, replaces ioConfig)

  // --- Per-bytecode implementation selection ---
  // Integer — Microcode = iterative SW, Hardware = IntegerComputeUnit, Java = JOPizer invokestatic.
  // imul is IMP_ASM (JOPizer leaves bytecode as-is) → must be Microcode or Hardware.
  // idiv/irem are IMP_JAVA (JOPizer replaces with invokestatic) → Java fallback works.
  imul:  Implementation = Implementation.Microcode,  // Microcode=shift-add ~35cyc, Hardware=CU ~22cyc or DSP 2cyc
  idiv:  Implementation = Implementation.Java,       // Java=invokestatic ~1300cyc, Hardware=ICU DivUnit ~36cyc
  irem:  Implementation = Implementation.Java,       // Java=invokestatic ~1300cyc, Hardware=ICU DivUnit ~36cyc

  // Float — Java (software) or Hardware (FloatComputeUnit / microcode)
  fadd:  Implementation = Implementation.Java,
  fsub:  Implementation = Implementation.Java,
  fmul:  Implementation = Implementation.Java,
  fdiv:  Implementation = Implementation.Java,
  fneg:  Implementation = Implementation.Java,  // Hardware → microcode XOR sign bit (no CU needed)
  i2f:   Implementation = Implementation.Java,
  f2i:   Implementation = Implementation.Java,
  fcmpl: Implementation = Implementation.Java,
  fcmpg: Implementation = Implementation.Java,

  // Long — IMP_ASM ops have pure microcode handlers; IMP_JAVA ops use JOPizer invokestatic.
  // ladd/lsub/lneg/lshl/lshr/lushr/lcmp are IMP_ASM → must be Microcode or Hardware.
  // lmul is IMP_JAVA → Java fallback works. Microcode (lmul_sw) requires ICU for partial products.
  ladd:  Implementation = Implementation.Microcode,
  lsub:  Implementation = Implementation.Microcode,
  lmul:  Implementation = Implementation.Java,       // Java=invokestatic, Microcode=lmul_sw (needs ICU), Hardware=LCU
  lneg:  Implementation = Implementation.Microcode,  // Implemented as lsub(0L - value)
  lshl:  Implementation = Implementation.Microcode,
  lshr:  Implementation = Implementation.Microcode,
  lushr: Implementation = Implementation.Microcode,
  lcmp:  Implementation = Implementation.Microcode,

  // Double — IMP_JAVA (Java=sys_noim→invokestatic, Hardware=DCU). Microcode not available.
  dadd:  Implementation = Implementation.Java,
  dsub:  Implementation = Implementation.Java,
  dmul:  Implementation = Implementation.Java,
  ddiv:  Implementation = Implementation.Java,
  i2d:   Implementation = Implementation.Java,
  d2i:   Implementation = Implementation.Java,
  l2d:   Implementation = Implementation.Java,
  d2l:   Implementation = Implementation.Java,
  f2d:   Implementation = Implementation.Java,
  d2f:   Implementation = Implementation.Java,
  dcmpl: Implementation = Implementation.Java,
  dcmpg: Implementation = Implementation.Java
) {
  import Implementation._

  // --- Resolved devices: always populated (auto-converts from ioConfig if empty) ---
  lazy val effectiveDevices: Map[String, DeviceInstance] =
    if (devices.nonEmpty) devices else ioConfig.toDevices()

  // --- Device queries (single path via effectiveDevices) ---
  def hasDevice(deviceType: String): Boolean =
    effectiveDevices.values.exists(_.deviceType == deviceType)
  def hasUart: Boolean = hasDevice("uart")
  def hasEth: Boolean = hasDevice("ethernet")
  def uartBaudRate: Int =
    effectiveDevices.values.find(_.deviceType == "uart")
      .flatMap(_.params.get("baudRate").map(_.asInstanceOf[Int]))
      .getOrElse(2000000)
  def hasAnyVga: Boolean = hasDevice("vgadma") || hasDevice("vgatext")
  def hasAnySd: Boolean = hasDevice("sdspi") || hasDevice("sdnative")

  /** Build device descriptors from effectiveDevices */
  def effectiveDeviceDescriptors(ctx: DeviceContext = DeviceContext()): Seq[IoDeviceDescriptor] = {
    val devs = effectiveDevices
    DeviceTypes.toDescriptors(devs, DeviceTypes.bootDeviceName(devs), this, ctx)
  }

  /** Interrupt count from effectiveDevices */
  def effectiveNumIoInt: Int = DeviceTypes.interruptCount(effectiveDevices)

  require(dataWidth == 32, "Only 32-bit data width supported")
  require(instrWidth == 10, "Instruction width must be 10 bits")
  require(pcWidth == 12, "PC width must be 12 bits (4K ROM)")
  require(jpcWidth == 11, "JPC width must be 11 bits (2KB cache)")
  require(!useStackCache || spillBaseAddrOverride.isDefined || memConfig.stackRegionWordsPerCore > 0,
    "useStackCache requires stackRegionWordsPerCore > 0 (or spillBaseAddrOverride) to prevent GC heap/stack overlap")
  // IMP_ASM bytecodes: JOPizer does NOT replace them with invokestatic.
  // The jump table must always point to a working handler (sthw or software).
  // Java is not a valid option because sys_noim expects invokestatic operands.
  require(imul != Java, "imul: Java is invalid — imul is IMP_ASM. Use Microcode or Hardware.")
  for ((name, impl) <- Seq("ladd"->ladd, "lsub"->lsub, "lneg"->lneg,
                            "lshl"->lshl, "lshr"->lshr, "lushr"->lushr, "lcmp"->lcmp))
    require(impl != Java, s"$name: Java is invalid — $name is IMP_ASM. Use Microcode or Hardware.")

  // lmul_sw uses ICU (sthw 0/3 for partial products) — requires IntegerComputeUnit.
  require(lmul != Microcode || needsIntegerCompute,
    "lmul: Microcode requires IntegerComputeUnit (lmul_sw uses sthw for partial products). " +
    "Set imul/idiv/irem to Hardware, or use lmul=Java or lmul=Hardware.")

  // IMP_JAVA double bytecodes: no SW microcode handlers exist.
  // Only Java (sys_noim → invokestatic) or Hardware (sthw → DCU) are valid.
  for ((name, impl) <- Seq("dadd"->dadd, "dsub"->dsub, "dmul"->dmul, "ddiv"->ddiv,
                            "i2d"->i2d, "d2i"->d2i, "l2d"->l2d, "d2l"->d2l,
                            "f2d"->f2d, "d2f"->d2f, "dcmpl"->dcmpl, "dcmpg"->dcmpg))
    require(impl != Microcode, s"$name: Microcode is invalid — no SW handler exists. Use Java or Hardware.")

  // --- Derived: what hardware to instantiate ---
  // Only Hardware needs actual compute unit instantiation.
  // Microcode uses pure-microcode handlers (e.g., imul_sw), Java uses sys_noim → JVM.f_xxx().
  def needsIntegerCompute: Boolean = Seq(imul, idiv, irem).exists(_ == Hardware)
  def needsFloatCompute: Boolean   = Seq(fadd, fsub, fmul, fdiv, i2f, f2i, fcmpl, fcmpg).exists(_ == Hardware)
  def needsLongCompute: Boolean    = Seq(ladd, lsub, lmul, lneg, lshl, lshr, lushr, lcmp).exists(_ == Hardware)
  def needsDoubleCompute: Boolean  = Seq(dadd, dsub, dmul, ddiv, i2d, d2i, l2d, d2l, f2d, d2f, dcmpl, dcmpg).exists(_ == Hardware)

  // Internal hardware within IntegerComputeUnit
  def needsIntMul: Boolean = imul == Hardware
  def needsIntDiv: Boolean = Seq(idiv, irem).exists(_ == Hardware)

  // Internal hardware within LongComputeUnit
  def needsLongMul: Boolean   = lmul == Hardware
  def needsLongShift: Boolean = Seq(lshl, lshr, lushr).exists(_ == Hardware)

  // Internal hardware within DoubleComputeUnit
  def needsDoubleAdd: Boolean     = Seq(dadd, dsub).exists(_ == Hardware)
  def needsDoubleMul: Boolean     = dmul == Hardware
  def needsDoubleDiv: Boolean     = ddiv == Hardware
  def needsDoubleConvert: Boolean = Seq(i2d, d2i, l2d, d2l, f2d, d2f).exists(_ == Hardware)
  def needsDoubleCmp: Boolean     = Seq(dcmpl, dcmpg).exists(_ == Hardware)

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
    // Long operations (IMP_ASM): Microcode→useAlt (SW handler), Hardware→keep HW handler
    val longOps: Seq[(Int, Implementation)] = Seq(
      0x61 -> ladd,  0x65 -> lsub,  0x69 -> lmul,  0x75 -> lneg,
      0x79 -> lshl,  0x7B -> lshr,  0x7D -> lushr, 0x94 -> lcmp
    )
    // Double operations (IMP_JAVA): Java→disable (sys_noim), Hardware→keep HW handler
    val doubleOps: Seq[(Int, Implementation)] = Seq(
      0x63 -> dadd,  0x67 -> dsub,  0x6B -> dmul,  0x6F -> ddiv,
      0x87 -> i2d,   0x8E -> d2i,   0x8A -> l2d,   0x8F -> d2l,
      0x8D -> f2d,   0x90 -> d2f,   0x97 -> dcmpl, 0x98 -> dcmpg
    )
    for ((bc, impl) <- longOps ++ doubleOps) {
      impl match {
        case Java     => result = result.disable(bc)
        case Microcode => result = result.useAlt(bc)
        case Hardware => // keep default HW handler (sthw → LCU/DCU)
      }
    }
    result
  }

  def resolvedMemoryStyle: MemoryStyle = memoryStyle.getOrElse(MemoryStyle.Generic)

  def fetchConfig = FetchConfig(pcWidth, instrWidth, memoryStyle = resolvedMemoryStyle)
  def decodeConfig = DecodeConfig(instrWidth, ramWidth)
  def stackConfig = StackConfig(dataWidth, jpcWidth, ramWidth,
    useDspMul = useDspMul,
    useSyncRam = useSyncRam.getOrElse(true),
    memoryStyle = resolvedMemoryStyle,
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
