package jop.config

import spinal.core.HertzNumber
import jop.io.{DeviceContext, DeviceTypes, IoDeviceDescriptor}
import jop.pipeline._
import jop.memory._

/** Per-bytecode implementation selection */
sealed trait Implementation
object Implementation {
  case object Java extends Implementation       // sys_noim -> Java runtime fallback
  case object Microcode extends Implementation  // Pure microcode handler (no HW peripheral)
  case object Hardware extends Implementation   // Microcode -> HW compute unit / ALU
}

/** Memory primitive style — controls ROM/RAM instantiation in pipeline stages.
  * Generic:   SpinalHDL Mem with init() -> $readmemb inference (works on Cyclone IV, Xilinx)
  * AlteraLpm: lpm_rom / lpm_ram_dp BlackBox with .mif -> works on all Altera incl. MAX10 */
sealed trait MemoryStyle
object MemoryStyle {
  case object Generic extends MemoryStyle
  case object AlteraLpm extends MemoryStyle
}

// ==========================================================================
// BytecodeConfig — registry of all 32 configurable bytecodes
// ==========================================================================

/** Constraint on which Implementations are valid for a bytecode */
sealed trait ImpConstraint
object ImpConstraint {
  /** IMP_ASM: JOPizer keeps bytecode. Java invalid (sys_noim expects invokestatic operands). */
  case object Asm extends ImpConstraint
  /** IMP_JAVA: JOPizer replaces with invokestatic. All implementations valid. */
  case object JavaOk extends ImpConstraint
  /** IMP_JAVA + no microcode handler exists. Only Java or Hardware. */
  case object NoMicrocode extends ImpConstraint
}

/** A single configurable bytecode entry */
case class BytecodeEntry(name: String, opcode: Int, group: String,
                         default: Implementation, constraint: ImpConstraint)

/** Registry and resolver for bytecode implementation configuration.
  *
  * Bytecodes map keys: individual name ("imul"), group ("int","float","long","double"), or "*".
  * Values: "java"/"j", "mc"/"microcode", "hw"/"hardware".
  * Resolution priority: individual name > group > "*" > default.
  */
object BytecodeConfig {
  import Implementation._
  import ImpConstraint._

  val all: Seq[BytecodeEntry] = Seq(
    // Integer
    BytecodeEntry("imul",  0x68, "int",    Microcode, Asm),      // Microcode=shift-add ~35cyc, Hardware=CU ~22cyc or DSP 2cyc
    BytecodeEntry("idiv",  0x6C, "int",    Java,      JavaOk),   // Java=invokestatic ~1300cyc, Hardware=ICU DivUnit ~36cyc
    BytecodeEntry("irem",  0x70, "int",    Java,      JavaOk),   // Java=invokestatic ~1300cyc, Hardware=ICU DivUnit ~36cyc
    // Float
    BytecodeEntry("fadd",  0x62, "float",  Java, JavaOk),
    BytecodeEntry("fsub",  0x66, "float",  Java, JavaOk),
    BytecodeEntry("fmul",  0x6A, "float",  Java, JavaOk),
    BytecodeEntry("fdiv",  0x6E, "float",  Java, JavaOk),
    BytecodeEntry("fneg",  0x76, "float",  Java, JavaOk),   // Hardware -> microcode XOR sign bit (no FCU needed)
    BytecodeEntry("i2f",   0x86, "float",  Java, JavaOk),
    BytecodeEntry("f2i",   0x8B, "float",  Java, JavaOk),
    BytecodeEntry("fcmpl", 0x95, "float",  Java, JavaOk),
    BytecodeEntry("fcmpg", 0x96, "float",  Java, JavaOk),
    // Long — IMP_ASM ops have pure microcode handlers; lmul is IMP_JAVA.
    BytecodeEntry("ladd",  0x61, "long",   Microcode, Asm),
    BytecodeEntry("lsub",  0x65, "long",   Microcode, Asm),
    BytecodeEntry("lmul",  0x69, "long",   Java,      JavaOk),  // Microcode (lmul_sw) requires ICU for partial products
    BytecodeEntry("lneg",  0x75, "long",   Microcode, Asm),
    BytecodeEntry("lshl",  0x79, "long",   Microcode, Asm),
    BytecodeEntry("lshr",  0x7B, "long",   Microcode, Asm),
    BytecodeEntry("lushr", 0x7D, "long",   Microcode, Asm),
    BytecodeEntry("lcmp",  0x94, "long",   Microcode, Asm),
    // Double — IMP_JAVA, no microcode handlers exist
    BytecodeEntry("dadd",  0x63, "double", Java, NoMicrocode),
    BytecodeEntry("dsub",  0x67, "double", Java, NoMicrocode),
    BytecodeEntry("dmul",  0x6B, "double", Java, NoMicrocode),
    BytecodeEntry("ddiv",  0x6F, "double", Java, NoMicrocode),
    BytecodeEntry("i2d",   0x87, "double", Java, NoMicrocode),
    BytecodeEntry("d2i",   0x8E, "double", Java, NoMicrocode),
    BytecodeEntry("l2d",   0x8A, "double", Java, NoMicrocode),
    BytecodeEntry("d2l",   0x8F, "double", Java, NoMicrocode),
    BytecodeEntry("f2d",   0x8D, "double", Java, NoMicrocode),
    BytecodeEntry("d2f",   0x90, "double", Java, NoMicrocode),
    BytecodeEntry("dcmpl", 0x97, "double", Java, NoMicrocode),
    BytecodeEntry("dcmpg", 0x98, "double", Java, NoMicrocode)
  )

  private val byName: Map[String, BytecodeEntry] = all.map(e => e.name -> e).toMap
  val groups: Set[String] = all.map(_.group).toSet  // "int", "float", "long", "double"
  val validKeys: Set[String] = byName.keySet ++ groups + "*"

  def inGroup(group: String): Seq[BytecodeEntry] = all.filter(_.group == group)

  /** Parse implementation string to Implementation enum */
  def parseImpl(s: String): Implementation = s.toLowerCase match {
    case "java" | "j"             => Java
    case "microcode" | "mc"       => Microcode
    case "hardware" | "hw"        => Hardware
    case other => throw new IllegalArgumentException(
      s"Unknown implementation: '$other'. Use java/mc/hw.")
  }

  /** Resolve a bytecodes map to per-bytecode Implementation.
    * Priority: individual name > group > "*" > default. */
  def resolve(bytecodes: Map[String, String]): Map[String, Implementation] = {
    // Validate keys
    for (key <- bytecodes.keys)
      require(validKeys.contains(key),
        s"Unknown bytecode key '$key'. Valid: ${validKeys.toSeq.sorted.mkString(", ")}")
    val star = bytecodes.get("*").map(parseImpl)
    all.map { entry =>
      val impl = bytecodes.get(entry.name).map(parseImpl)
        .orElse(bytecodes.get(entry.group).map(parseImpl))
        .orElse(star)
        .getOrElse(entry.default)
      entry.name -> impl
    }.toMap
  }

  /** Validate resolved implementations against constraints */
  def validate(resolved: Map[String, Implementation]): Unit = {
    for (entry <- all) {
      val impl = resolved(entry.name)
      entry.constraint match {
        case Asm =>
          require(impl != Java,
            s"${entry.name}: java is invalid — ${entry.name} is IMP_ASM. Use mc or hw.")
        case NoMicrocode =>
          require(impl != Microcode,
            s"${entry.name}: mc is invalid — no SW handler exists. Use java or hw.")
        case JavaOk => // all valid
      }
    }
  }
}

// ==========================================================================
// JopCoreConfig
// ==========================================================================

/**
 * JOP Core Configuration
 *
 * @param dataWidth    Data path width (32 bits)
 * @param pcWidth      Microcode PC width (12 bits = 4K ROM)
 * @param instrWidth   Microcode instruction width (10 bits)
 * @param jpcWidth     Java PC width (11 bits = 2KB bytecode cache)
 * @param ramWidth     Stack RAM address width (8 bits = 256 entries)
 * @param blockBits    Method cache block bits (4 = 16 blocks in JBC RAM)
 * @param memConfig    Memory subsystem configuration
 * @param supersetJumpTable  Superset jump table (all HW handlers present). Patched by resolveJumpTable().
 * @param cpuId        CPU identifier (for multi-core; 0 for single-core)
 * @param cpuCnt       Total number of CPUs (1 for single-core)
 * @param clkFreq      System clock frequency (for Sys microsecond prescaler)
 * @param bytecodes    Declarative bytecode implementation map. Keys: name ("imul"), group
 *                     ("int","float","long","double"), or "*". Values: "java"/"mc"/"hw".
 *                     Resolution: name > group > "*" > default. Empty = all defaults.
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
  clkFreq:      HertzNumber      = HertzNumber(100000000),
  useCmpSync:   Boolean          = false,
  useStackCache: Boolean         = false,
  spillBaseAddrOverride: Option[Int] = None,
  useDspMul:    Boolean          = false,
  useSyncRam:   Option[Boolean]  = None,
  memoryStyle:  Option[MemoryStyle] = None,
  devices:      Map[String, DeviceInstance] = Map.empty,
  bytecodes:    Map[String, String] = Map.empty
) {
  import Implementation._

  // --- Resolved bytecode implementations ---
  lazy val resolved: Map[String, Implementation] = {
    val r = BytecodeConfig.resolve(bytecodes)
    BytecodeConfig.validate(r)
    r
  }

  /** Get the resolved Implementation for a bytecode by name */
  def impl(name: String): Implementation = resolved(name)

  // --- Device map ---
  lazy val effectiveDevices: Map[String, DeviceInstance] = devices

  // --- Device queries ---
  def hasDevice(dt: DeviceType): Boolean =
    effectiveDevices.values.exists(_.deviceType == dt)
  def uartBaudRate: Int =
    effectiveDevices.values.find(_.deviceType == DeviceType.Uart)
      .flatMap(_.params.get("baudRate").map(_.asInstanceOf[Int]))
      .getOrElse(2000000)

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

  // lmul_sw uses ICU (sthw 0/3 for partial products) — requires IntegerComputeUnit.
  require(impl("lmul") != Microcode || needsIntegerCompute,
    "lmul: mc requires IntegerComputeUnit (lmul_sw uses sthw for partial products). " +
    "Set int=hw, or use lmul=java or lmul=hw.")

  // --- Derived: what hardware to instantiate ---
  private def isHw(names: String*): Boolean = names.exists(impl(_) == Hardware)

  def needsIntegerCompute: Boolean = isHw("imul", "idiv", "irem")
  def needsFloatCompute: Boolean   = isHw("fadd", "fsub", "fmul", "fdiv", "i2f", "f2i", "fcmpl", "fcmpg")
  def needsLongCompute: Boolean    = isHw("ladd", "lsub", "lmul", "lneg", "lshl", "lshr", "lushr", "lcmp")
  def needsDoubleCompute: Boolean  = isHw("dadd", "dsub", "dmul", "ddiv", "i2d", "d2i", "l2d", "d2l", "f2d", "d2f", "dcmpl", "dcmpg")

  def needsIntMul: Boolean = impl("imul") == Hardware
  def needsIntDiv: Boolean = isHw("idiv", "irem")

  def needsLongMul: Boolean   = impl("lmul") == Hardware
  def needsLongShift: Boolean = isHw("lshl", "lshr", "lushr")

  def needsDoubleAdd: Boolean     = isHw("dadd", "dsub")
  def needsDoubleMul: Boolean     = impl("dmul") == Hardware
  def needsDoubleDiv: Boolean     = impl("ddiv") == Hardware
  def needsDoubleConvert: Boolean = isHw("i2d", "d2i", "l2d", "d2l", "f2d", "d2f")
  def needsDoubleCmp: Boolean     = isHw("dcmpl", "dcmpg")

  /** True if any float bytecode uses Java (needs SW float class library) */
  def needsJavaFloat: Boolean =
    BytecodeConfig.inGroup("float").exists(e => impl(e.name) == Java)

  /** Resolve the jump table: patch bytecodes based on their configured Implementation.
    *
    * - Java -> patch to sys_noim (JOPizer replaces these with invokestatic)
    * - Microcode -> use alternate handler from ROM if available (e.g., imul_sw)
    * - Hardware -> keep default (HW handler from superset ROM)
    */
  def resolveJumpTable: JumpTableInitData = {
    var result = supersetJumpTable
    for (entry <- BytecodeConfig.all) {
      impl(entry.name) match {
        case Java     => result = result.disable(entry.opcode)
        case Microcode => result = result.useAlt(entry.opcode)
        case Hardware =>
          if (entry.name == "imul" && useDspMul)
            result = result.useDspAlt(entry.opcode)
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
