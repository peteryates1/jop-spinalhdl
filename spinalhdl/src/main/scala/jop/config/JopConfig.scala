package jop.config

import spinal.core._
import jop.memory.JopMemoryConfig
import jop.pipeline.JumpTableInitData

/**
 * JOP System Configuration — Top-Level Config Driving Everything
 *
 * Hierarchy:
 *   JopConfig         — assembly + one or more JOP systems
 *     SystemAssembly  — collection of boards (physical hardware)
 *     JopSystem       — processor cluster (cores + memory + I/O)
 *       JopCoreConfig — per-core bytecode implementation choices
 *
 * JopConfig is the single source of truth. Every downstream artifact is derived from it:
 *   - Microcode ROM (gcc -D flags from bytecode config)
 *   - Jump table (patched per-core at elaboration)
 *   - Compute unit instantiation (derived from bytecode config)
 *   - Java runtime (Const.java, JopInstr.java, module selection)
 *   - SpinalHDL Verilog (top-level ports, memory controller, I/O)
 *   - FPGA build (pin assignments from board data)
 *   - JOPizer (IMP_ASM/IMP_JAVA from bytecode config)
 */

// ==========================================================================
// Boot Mode
// ==========================================================================

sealed trait BootMode {
  def dirName: String
}
object BootMode {
  case object Serial extends BootMode     { val dirName = "serial" }
  case object Flash extends BootMode      { val dirName = "flash" }
  case object Simulation extends BootMode { val dirName = "simulation" }
}

// ==========================================================================
// Arbiter Type
// ==========================================================================

sealed trait ArbiterType
object ArbiterType {
  case object RoundRobin extends ArbiterType
  case object Tdma extends ArbiterType
}

// ==========================================================================
// JOP System — a processor cluster targeting memory on the assembly
// ==========================================================================

/**
 * A single JOP processor system (cluster of cores sharing memory).
 *
 * Most assemblies have one JopSystem. The Wukong dual-subsystem has two,
 * each using a different memory device.
 *
 * @param name           System name (for logging and artifact naming)
 * @param memory         Which memory device to use (by part name or role)
 * @param bootMode       Boot source (Serial, Flash, Simulation)
 * @param arbiterType    Bus arbiter type for multi-core
 * @param clkFreq        System clock frequency (after PLL)
 * @param cpuCnt         Number of CPU cores
 * @param coreConfig     Default configuration for all cores
 * @param perCoreConfigs Optional per-core override (heterogeneous cores).
 *                       Per-core configs with non-empty `devices` keep their
 *                       own devices; otherwise core 0 inherits system `devices`
 *                       and cores 1+ start with an empty device map.
 */
case class JopSystem(
  name: String,
  memory: String,
  bootMode: BootMode,
  arbiterType: ArbiterType = ArbiterType.RoundRobin,
  clkFreq: HertzNumber,
  cpuCnt: Int = 1,
  coreConfig: JopCoreConfig = JopCoreConfig(),
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None,
  devices: Map[String, DeviceInstance] = Map.empty
) {
  require(cpuCnt >= 1, s"System '$name': cpuCnt must be at least 1")
  perCoreConfigs.foreach(pcc =>
    require(pcc.length == cpuCnt,
      s"System '$name': perCoreConfigs length (${pcc.length}) must match cpuCnt ($cpuCnt)"))

  // --- Derived paths from boot mode ---
  // Simulation outputs directly to asm/generated/; serial/flash use subdirectories.
  private def generatedDir: String = bootMode match {
    case BootMode.Simulation => "asm/generated"
    case other               => s"asm/generated/${other.dirName}"
  }
  def romPath: String = s"$generatedDir/mem_rom.dat"
  def ramPath: String = s"$generatedDir/mem_ram.dat"

  def baseJumpTable: JumpTableInitData = bootMode match {
    case BootMode.Serial     => JumpTableInitData.serial
    case BootMode.Flash      => JumpTableInitData.flash
    case BootMode.Simulation => JumpTableInitData.simulation
  }

  // --- Derived: per-core configs with devices distributed ---
  // Core 0 inherits system `devices` (unless its config already has devices).
  // Cores 1+ start with empty devices (unless their config has explicit devices).
  def coreConfigs: Seq[JopCoreConfig] = {
    val base = perCoreConfigs.getOrElse(Seq.fill(cpuCnt)(coreConfig))
    base.zipWithIndex.map { case (cc, i) =>
      if (cc.devices.nonEmpty) cc
      else if (i == 0) cc.copy(devices = devices)
      else cc
    }
  }

  // --- Derived: union of all cores' needs ---
  def needsIntegerCompute: Boolean = coreConfigs.exists(_.needsIntegerCompute)
  def needsFloatCompute: Boolean = coreConfigs.exists(_.needsFloatCompute)

  // --- Resolved devices (union of all cores' devices) ---
  lazy val effectiveDevices: Map[String, DeviceInstance] =
    coreConfigs.flatMap(_.effectiveDevices).toMap

  // --- Per-core UART detection ---
  def hasPerCoreUart: Boolean = cpuCnt > 1 &&
    coreConfigs.drop(1).exists(_.effectiveDevices.values.exists(_.deviceType == "uart"))

  // --- Device presence queries (single path via effectiveDevices) ---
  def hasDevice(deviceType: String): Boolean =
    effectiveDevices.values.exists(_.deviceType == deviceType)
  def hasUart: Boolean = hasDevice("uart")
  def hasEth: Boolean = hasDevice("ethernet")
  def hasVga: Boolean = hasDevice("vgadma") || hasDevice("vgatext")
  def hasSdNative: Boolean = hasDevice("sdnative")
  def hasSdSpi: Boolean = hasDevice("sdspi")
  def hasConfigFlash: Boolean = hasDevice("cfgflash")
  def ethGmii: Boolean =
    effectiveDevices.values.find(_.deviceType == "ethernet")
      .flatMap(_.params.get("gmii"))
      .exists(_.asInstanceOf[Boolean])
  def phyDataWidth: Int = if (ethGmii) 8 else 4
}

// ==========================================================================
// Interconnect (for multi-system assemblies)
// ==========================================================================

/** Cross-system interconnect configuration (FIFO message queues) */
case class InterconnectConfig(
  fifoDepth: Int = 16,
  dataWidth: Int = 32
)

// ==========================================================================
// Monitors
// ==========================================================================

/** Hardware monitor configuration */
sealed trait MonitorConfig
case class WatchdogConfig(timeoutMs: Int = 1000) extends MonitorConfig

// ==========================================================================
// JOP Config — top-level
// ==========================================================================

/**
 * Top-level configuration — assembly + one or more JOP systems.
 *
 * This is the single source of truth that drives the entire build chain:
 * microcode assembly, Java runtime generation, SpinalHDL elaboration,
 * FPGA synthesis, JOPizer, and FPGA testing.
 */
case class JopConfig(
  assembly: SystemAssembly,
  systems: Seq[JopSystem],
  interconnect: Option[InterconnectConfig] = None,
  monitors: Seq[MonitorConfig] = Seq.empty
) {
  require(systems.nonEmpty, "At least one JopSystem required")

  // --- Single-system convenience ---
  def system: JopSystem = {
    require(systems.length == 1,
      s"Use .systems for multi-system configs (have ${systems.length} systems)")
    resolvedSystems.head
  }

  /** Systems with useSyncRam and memoryStyle auto-resolved from FPGA family.
    *
    * useSyncRam: always true — readAsync emits ram_style=distributed, preventing
    * BRAM inference.  readSync with write-bypass works on all targets.
    *
    * memoryStyle: AlteraLpm for Altera (lpm_rom/lpm_ram_dp BlackBox with .mif),
    * Generic for others.  MAX10 requires AlteraLpm because its inference engine
    * does not support MIF initialization from $readmemb.  Cyclone IV inference
    * works but AlteraLpm is more robust and matches proven jopmin approach. */
  lazy val resolvedSystems: Seq[JopSystem] = {
    val needsSync = true
    val autoMemStyle = fpgaFamily.manufacturer match {
      case Manufacturer.Altera => MemoryStyle.AlteraLpm
      case _                   => MemoryStyle.Generic
    }
    def resolveCore(cc: JopCoreConfig): JopCoreConfig = {
      val r1 = cc.useSyncRam match {
        case Some(_) => cc
        case None    => cc.copy(useSyncRam = Some(needsSync))
      }
      r1.memoryStyle match {
        case Some(_) => r1
        case None    => r1.copy(memoryStyle = Some(autoMemStyle))
      }
    }
    systems.map { sys =>
      val resolved = resolveCore(sys.coreConfig)
      val resolvedPerCore = sys.perCoreConfigs.map(_.map(resolveCore))
      sys.copy(coreConfig = resolved, perCoreConfigs = resolvedPerCore)
    }
  }

  // --- Validation ---

  // Each system's memory must exist on the assembly (by part name or role), or be "bram"
  systems.foreach { sys =>
    require(
      sys.memory == "bram" ||
      assembly.findDevice(sys.memory).isDefined ||
      assembly.findDeviceByRole(sys.memory).isDefined,
      s"System '${sys.name}' references memory '${sys.memory}' " +
      s"but assembly '${assembly.name}' has no such device or role")
  }

  // Each device's devicePart must exist on the assembly (when specified)
  systems.foreach { sys =>
    sys.effectiveDevices.foreach { case (name, inst) =>
      inst.devicePart.foreach { part =>
        require(assembly.findDevice(part).isDefined,
          s"System '${sys.name}' device '$name' references part " +
          s"'$part' but assembly '${assembly.name}' has none")
      }
    }
  }

  // --- Derived from physical assembly ---
  def fpga: FpgaDevice = assembly.fpga
  def fpgaFamily: FpgaFamily = assembly.fpgaFamily

  /** Resolve the memory device for a system */
  def resolveMemory(sys: JopSystem): Option[MemoryDevice] = {
    val bd = assembly.findDevice(sys.memory)
      .orElse(assembly.findDeviceByRole(sys.memory))
    bd.flatMap(d => MemoryDevice.byName(d.part))
  }

  /** All memory types used across all systems */
  def memoryTypes: Seq[MemoryType] =
    systems.flatMap(sys => resolveMemory(sys).map(_.memType)).distinct

  /** Entity name derived from Board properties (entityTag, entitySuffix) and memory type */
  def entityName: String = {
    val sys = systems.head
    val board = assembly.fpgaBoard
    val smp = if (sys.cpuCnt >= 2) "Smp" else ""
    val platform = if (board.entityTag.nonEmpty) {
      board.entityTag
    } else {
      val memPart =
        if (memoryTypes.contains(MemoryType.SDRAM_DDR3)) "Ddr3"
        else if (memoryTypes.contains(MemoryType.SDRAM_SDR)) "Sdram"
        else if (sys.bootMode == BootMode.Serial) "BramSerial"
        else "Bram"
      memPart + board.entitySuffix
    }
    s"Jop${smp}${platform}Top"
  }
}

// ==========================================================================
// Presets — builder pattern with copy()
// ==========================================================================

object JopConfig {

  // ========================================================================
  // Single-system presets (common case)
  // ========================================================================

  /** EP4CGX150 + daughter board — full drivers (UART + Ethernet + SD) */
  def ep4cgx150Serial = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9825G6JH6",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  /** EP4CGX150 + daughter board — SMP, N cores */
  def ep4cgx150Smp(n: Int) = {
    val base = ep4cgx150Serial
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n)))
  }

  /** EP4CGX150 + daughter board — hardware integer math (IntegerComputeUnit) */
  def ep4cgx150HwMath = {
    val base = ep4cgx150Serial
    base.copy(systems = Seq(base.system.copy(
      name = "hwmath",
      coreConfig = JopCoreConfig(
        memConfig = base.system.coreConfig.memConfig,
        supersetJumpTable = base.system.coreConfig.supersetJumpTable,
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")))))
  }

  /** EP4CGX150 + daughter board — hardware float (FloatComputeUnit) */
  def ep4cgx150HwFloat = {
    val base = ep4cgx150Serial
    base.copy(systems = Seq(base.system.copy(
      name = "hwfloat",
      coreConfig = JopCoreConfig(
        memConfig = base.system.coreConfig.memConfig,
        supersetJumpTable = base.system.coreConfig.supersetJumpTable,
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw", "float" -> "hw")))))
  }

  /** EP4CGX150 — pre-initialized BRAM (32KB, simulation microcode) */
  def ep4cgx150Bram = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "bram",
      memory = "bram",
      bootMode = BootMode.Simulation,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 32 * 1024)),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  /** EP4CGX150 — pre-initialized BRAM (128KB) for GC testing */
  def ep4cgx150BramGc = {
    val base = ep4cgx150Bram
    base.copy(systems = Seq(base.system.copy(
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)))))
  }

  /** EP4CGX150 — serial download into BRAM (128KB) */
  def ep4cgx150BramSerial = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "bram-serial",
      memory = "bram",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  /** CYC5000 standalone */
  def cyc5000Serial = JopConfig(
    assembly = SystemAssembly.cyc5000,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9864G6JT",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("FT2232H"))))))

  /** CYC5000 SMP (N cores) */
  def cyc5000Smp(n: Int) = {
    val base = cyc5000Serial
    base.copy(systems = Seq(base.system.copy(cpuCnt = n)))
  }

  /** Alchitry Au V2 */
  def auSerial = JopConfig(
    assembly = SystemAssembly.alchitryAuV2,
    systems = Seq(JopSystem(
      name = "main",
      memory = "MT41K128M16JT-125:K",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,  // MIG ui_clk = 100 MHz (4:1, DDR3-800)
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("FT2232H"))))))

  /** Alchitry Au V2 — minimum: no caches, no HW math */
  def auMinimal = JopConfig(
    assembly = SystemAssembly.alchitryAuV2,
    systems = Seq(JopSystem(
      name = "min",
      memory = "MT41K128M16JT-125:K",
      bootMode = BootMode.Serial,
      clkFreq = HertzNumber(BigDecimal(250000000) / 3),
      coreConfig = JopCoreConfig(memConfig = noCacheMemConfig),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("FT2232H"))))))


  /** Simulation (no physical board — uses QMTECH assembly as placeholder) */
  def simulation = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "sim",
      memory = "W9825G6JH6",
      bootMode = BootMode.Simulation,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  // ========================================================================
  // Multi-system preset (Wukong dual-subsystem)
  // ========================================================================

  /** Wukong: heavy compute on DDR3 + light I/O on SDR SDRAM */
  def wukongDual = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(
      JopSystem(
        name = "compute",
        memory = "ddr3",                 // by role
        bootMode = BootMode.Serial,
        clkFreq = 100 MHz,
        cpuCnt = 4,
        coreConfig = JopCoreConfig(
          bytecodes = Map("idiv" -> "hw", "irem" -> "hw", "float" -> "hw")),
        devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N")))),
      JopSystem(
        name = "io",
        memory = "sdr",                  // by role
        bootMode = BootMode.Serial,
        clkFreq = 50 MHz,
        cpuCnt = 2,
        coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
        devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))),
    interconnect = Some(InterconnectConfig(fifoDepth = 64)),
    monitors = Seq(WatchdogConfig(timeoutMs = 2000)))

  // ========================================================================
  // Minimum resource preset
  // ========================================================================

  /** Absolute minimum: no compute units, all defaults (imul=Microcode, rest=Java) */
  def minimum = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "min",
      memory = "W9825G6JH6",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(
        supersetJumpTable = JumpTableInitData.serial),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  // ========================================================================
  // Small FPGA presets (fit-check targets)
  // ========================================================================

  /** Smallest-FPGA memory config: no object/array caches to save ~1900 LEs */
  private def noCacheMemConfig = JopMemoryConfig(
    useOcache = false,
    useAcache = false
  )

  /** Arrow MAX1000 — SDR SDRAM with O$/A$ caches (fits at 85%) */
  def max1000Sdram = JopConfig(
    assembly = SystemAssembly.max1000,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9864G6JT",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("FT2232H"))))))

  /** Generic EP4CE6 — SDR SDRAM, no caches (6K LEs too small for O$/A$) */
  def ep4ce6Sdram = JopConfig(
    assembly = SystemAssembly.genericEp4ce6,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9864G6JT",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(memConfig = noCacheMemConfig),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))


  // ========================================================================
  // Wukong single-system presets
  // ========================================================================

  /** Wukong SDR SDRAM (single-system, 100 MHz) */
  def wukongSdram = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))

  /** Wukong SDR — all compute units, UART only (no Ethernet/SD) */
  def wukongSdrAllCu = {
    val base = wukongSdram
    base.copy(systems = Seq(base.system.copy(
      coreConfig = JopCoreConfig(useDspMul = true, bytecodes = Map("*" -> "hw")))))
  }

  /** Wukong DDR3 (single-system, 100 MHz) */
  def wukongDdr3 = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))

  /** Wukong BRAM (single-system, simulation-mode) */
  def wukongBram = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "bram",  // no physical memory — uses on-chip BRAM
      bootMode = BootMode.Simulation,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 64 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))

  /** Wukong BRAM with all compute units (DCU debug — simulation only) */
  def wukongBramFull = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(
      name = "main",
      memory = "bram",
      bootMode = BootMode.Simulation,
      cpuCnt = 1,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 64 * 1024),
        bytecodes = base.system.coreConfig.bytecodes),
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))
  }

  // ========================================================================
  // Wukong full-featured presets
  // ========================================================================

  /** Wukong DDR3 — full featured: HW integer + float + long + double compute, Ethernet, SD */
  def wukongFull = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      devices = Map(
        "uart" -> DeviceInstance("uart", devicePart = Some("CH340N")),
        "eth" -> DeviceInstance("ethernet", params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance("sdnative", devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(useDspMul = true, bytecodes = Map("*" -> "hw")))))

  /** Wukong DDR3 — all compute units, UART only (no Ethernet/SD) */
  def wukongDdr3AllCu = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))
  }

  /** Wukong DDR3 — full featured SMP (with Ethernet + SD) */
  def wukongFullSmp(n: Int) = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n)))
  }

  /** Wukong DDR3 — SMP with all compute units, no Ethernet/SD (saves LUTs) */
  def wukongSmp(n: Int) = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(
      name = s"smp$n",
      cpuCnt = n,
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CH340N"))))))
  }

  /** Wukong DDR3 — minimal SMP (no CUs, just cores + UART) for SMP+DDR3 debug */
  def wukongSmpMinimal(n: Int) = {
    val base = wukongDdr3
    base.copy(systems = Seq(base.system.copy(
      name = s"smpmin$n",
      cpuCnt = n,
      coreConfig = JopCoreConfig())))
  }

  /** Wukong DDR3 — all CUs except DCU (debug: isolate DCU hang).
    * Derived from wukongSmp(1) with all double ops set to Java. */
  def wukongNoDcu = {
    val base = wukongSmp(1)
    base.copy(systems = Seq(base.system.copy(
      coreConfig = base.system.coreConfig.copy(
        bytecodes = base.system.coreConfig.bytecodes + ("double" -> "java")))))
  }

  // === Debug configs: isolate which CU causes DoubleField hang on DDR3 ===

  /** Wukong DDR3 — ICU + DSP mul only (test useDspMul in isolation) */
  def wukongDdr3DspMul = {
    val base = wukongDdr3
    base.copy(systems = Seq(base.system.copy(
      coreConfig = base.system.coreConfig.copy(
        useDspMul = true,
        bytecodes = base.system.coreConfig.bytecodes + ("imul" -> "hw")))))
  }

  /** Wukong DDR3 — ICU + FCU only (test FCU in isolation) */
  def wukongDdr3Fcu = {
    val base = wukongDdr3
    base.copy(systems = Seq(base.system.copy(
      coreConfig = base.system.coreConfig.copy(
        bytecodes = base.system.coreConfig.bytecodes + ("float" -> "hw")))))
  }

  /** Wukong DDR3 — ICU + LCU only (test LCU in isolation) */
  def wukongDdr3Lcu = {
    val base = wukongDdr3
    base.copy(systems = Seq(base.system.copy(
      coreConfig = base.system.coreConfig.copy(
        bytecodes = base.system.coreConfig.bytecodes + ("long" -> "hw")))))
  }

  /** Wukong SDR — full featured: HW integer + float + long + double compute, Ethernet, SD */
  def wukongSdrFull = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      devices = Map(
        "uart" -> DeviceInstance("uart", devicePart = Some("CH340N")),
        "eth" -> DeviceInstance("ethernet", params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance("sdnative", devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(useDspMul = true, bytecodes = Map("*" -> "hw")))))
}
