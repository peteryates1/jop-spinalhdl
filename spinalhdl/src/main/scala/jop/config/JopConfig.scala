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
    coreConfigs.drop(1).exists(_.effectiveDevices.values.exists(_.deviceType == DeviceType.Uart))

  // --- Device presence queries (single path via effectiveDevices) ---
  def hasDevice(dt: DeviceType): Boolean =
    effectiveDevices.values.exists(_.deviceType == dt)
  def ethGmii: Boolean =
    effectiveDevices.values.find(_.deviceType == DeviceType.Ethernet)
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
    val autoMemStyle = fpgaFamily.memoryStyle
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

  /** Resolve the board device entry for a system's memory */
  def resolveBoardDevice(sys: JopSystem): Option[BoardDevice] =
    assembly.findDevice(sys.memory)
      .orElse(assembly.findDeviceByRole(sys.memory))

  /** All memory types used across all systems */
  def memoryTypes: Seq[MemoryType] =
    systems.flatMap(sys => resolveMemory(sys).map(_.memType)).distinct

  /** Entity name derived from Board properties (entityTag, entitySuffix) and memory type */
  def entityName: String = {
    val board = assembly.fpgaBoard
    if (systems.length > 1) {
      // Multi-system: JopDual<suffix>Top
      s"JopDual${board.entitySuffix}Top"
    } else {
      val sys = systems.head
      val smp = if (sys.cpuCnt >= 2) "Smp" else ""
      val platform = if (board.entityTag.nonEmpty) {
        board.entityTag
      } else {
        val memPart =
          if (memoryTypes.contains(MemoryType.SDRAM_DDR3)) "Ddr3"
          else if (memoryTypes.contains(MemoryType.SDRAM_DDR2)) "Ddr2"
          else if (memoryTypes.contains(MemoryType.SDRAM_SDR)) "Sdram"
          else if (sys.bootMode == BootMode.Serial) "BramSerial"
          else "Bram"
        memPart + board.entitySuffix
      }
      s"Jop${smp}${platform}Top"
    }
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
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      // hasBackendFill: use the SDR backend's fast block-fill for GC zeroing
      // (~full SDR bandwidth) instead of the controller's per-word loop.
      // hasCardTable: HW card-marking barrier (generational GC, Stage 1), 16KB.
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasBackendFill = true,
        hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

  /** EP4CGX150 + daughter board — SMP, N cores */
  def ep4cgx150Smp(n: Int) = {
    val base = ep4cgx150Serial
    // Leaves useCmpSync at its default (false), so JopCluster instantiates Ihlu
    // — per-object hardware locking. That is the point of SMP: CmpSync is a
    // single global lock, so two threads locking DIFFERENT objects still
    // serialise on it.
    //
    // Ihlu previously missed 100 MHz here by 1.282 ns, owning the four worst
    // paths in the design, because its queue RAM address was computed from the
    // combinational CAM result inside one cycle. That is fixed in Ihlu.scala by
    // moving the address into the already-idle RAM_DELAY state and driving it
    // from the registered index — no extra cycles. Set useCmpSync = true if you
    // want the simpler global lock.
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

  /** EP4CGX150 — pre-initialized BRAM (512KB, simulation microcode) */
  def ep4cgx150Bram = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "bram",
      memory = "bram",
      bootMode = BootMode.Simulation,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 512 * 1024),
        memoryStyle = Some(MemoryStyle.Generic)),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

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
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

  /**
   * CYC5000 standalone.
   *
   * hasCardTable is REQUIRED, not optional: `GC.USE_GENERATIONAL` defaults to
   * true, and without the hardware card-marking barrier the remembered set is
   * permanently empty — `IO_CARD_SHIFT` reads 0 (JopCore drives cardRdData := 0
   * when the table is absent), so `scanCards` finds nothing and every
   * tenured->nursery reference is invisible to the minor collector. Those young
   * objects are then collected while still live. Measured on this board without
   * it: GcPauseTest copied 3 survivors instead of 66 and reported
   * `corrupt 23 / MAJOR FAIL`, while DoAll still passed 66/66 — the mutator
   * cannot see the damage, only the collector can.
   *
   * 4 KB covers the 8 MB SDR at 64 words per card, which is finer than any
   * other board and costs almost nothing on a 5CEBA2.
   */
  def cyc5000Serial = JopConfig(
    assembly = SystemAssembly.cyc5000,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(
        hasCardTable = true, cardTableBudgetBytes = 4 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("FT2232H"))))))

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
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,  // MIG ui_clk = 100 MHz (4:1, DDR3-800)
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("FT2232H"))))))

  /** Alchitry Au V2 — minimum: no caches, no HW math */
  def auMinimal = JopConfig(
    assembly = SystemAssembly.alchitryAuV2,
    systems = Seq(JopSystem(
      name = "min",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = HertzNumber(BigDecimal(250000000) / 3),
      coreConfig = JopCoreConfig(memConfig = noCacheMemConfig),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("FT2232H"))))))


  /** Simulation (no physical board — uses QMTECH assembly as placeholder) */
  def simulation = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "sim",
      memory = "sdr",
      bootMode = BootMode.Simulation,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

  // ========================================================================
  // Multi-system preset (Wukong dual-subsystem)
  // ========================================================================

  /** Wukong: heavy compute on DDR3 + light I/O on SDR SDRAM */
  // Card-table budgets on the Wukong presets below.
  //
  // hasCardTable is required for generational GC to be SOUND, not just fast:
  // without it GC.init sees IO_CARD_SHIFT == 0 and falls back to the classic
  // collector (safe, but no minor GC at all). See the CYC5000 note above.
  //
  // Sizing: cardShift is derived as the smallest shift fitting the budget over
  // `mainMemWords`, and JopTop only overrides mainMemSize from the memory
  // device for DDR3/DDR2 — SDR systems keep the 8 MB JopMemoryConfig default
  // regardless of the physical chip (the Wukong carries 32 MB but JOP uses 8).
  // So:
  //   ddr3 (256 MB, device-derived) + 16 KB -> 512 words/card
  //   sdr  (8 MB default)           +  8 KB ->  32 words/card
  // Both cover the whole GC-visible heap, which matters: CardTable resizes the
  // index, so an address past the covered range would ALIAS onto a low card and
  // the real one would never be marked.
  def wukongDual = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(
      JopSystem(
        name = "compute",
        memory = "ddr3",                 // by role
        bootMode = BootMode.Serial,
        clkFreq = 100 MHz,
        cpuCnt = 4,
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        
          bytecodes = Map("idiv" -> "hw", "irem" -> "hw", "float" -> "hw")),
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N")))),
      JopSystem(
        name = "io",
        memory = "sdr",                  // by role
        bootMode = BootMode.Serial,
        clkFreq = 50 MHz,
        cpuCnt = 2,
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))),
    interconnect = Some(InterconnectConfig(fifoDepth = 64)),
    monitors = Seq(WatchdogConfig(timeoutMs = 2000)))

  /** Wukong dual-independent: DDR3 + SDR with separate UARTs (no interconnect) */
  def wukongDualIndependent = wukongDualIndependentSmp()

  def wukongDualIndependentSmp(cpuCnt: Int = 1, sdrClkMhz: Int = 80) = JopConfig(
    assembly = SystemAssembly.wukongWithJ12Uart,
    systems = Seq(
      JopSystem(name = "ddr3", memory = "ddr3", bootMode = BootMode.Serial,
        clkFreq = 100 MHz, cpuCnt = cpuCnt,
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        useDspMul = true, bytecodes = Map("*" -> "hw")),
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N")))),
      JopSystem(name = "sdr", memory = "sdr", bootMode = BootMode.Serial,
        clkFreq = sdrClkMhz MHz, cpuCnt = cpuCnt, coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024)),
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("J12_UART"),
          params = Map("baudRate" -> 115200))))),
    interconnect = None)

  // ========================================================================
  // Minimum resource preset
  // ========================================================================

  /** Absolute minimum: no compute units, all defaults (imul=Microcode, rest=Java) */
  def minimum = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "min",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(
        supersetJumpTable = JumpTableInitData.serial),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

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
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("FT2232H"))))))

  /** Generic EP4CE6 — SDR SDRAM, no caches (6K LEs too small for O$/A$) */
  def ep4ce6Sdram = JopConfig(
    assembly = SystemAssembly.genericEp4ce6,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(memConfig = noCacheMemConfig),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))


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
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))

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
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))

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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))

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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))
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
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N")),
        "eth" -> DeviceInstance(DeviceType.Ethernet, params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        useDspMul = true, bytecodes = Map("*" -> "hw")))))

  /** Wukong DDR3 — all compute units, UART only (no Ethernet/SD) */
  def wukongDdr3AllCu = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))
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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))))))
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

  // ========================================================================
  // QMTECH XC7A100T + DB_FPGA presets
  // ========================================================================

  /** XC7A100T + DB_FPGA V5 — DDR3, UART via RP2040 */
  def xc7a100tDbSerial = JopConfig(
    assembly = SystemAssembly.xc7a100tWithDbV5,
    systems = Seq(JopSystem(
      name = "main",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      // hasBackendFill: DDR3 backend zeroes GC free space via full-line 128-bit
      // cache writes (no refill) instead of the controller's per-word loop.
      // JopTop overrides addressWidth/mainMemSize from the device but preserves
      // this flag.
      // hasCardTable: HW card-marking barrier (generational GC, Stage 1), 16KB.
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasBackendFill = true,
        hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      // 2 Mbaud (default). host->FPGA is lossless at full speed here (RP2040
      // USB backpressure works). FPGA->host byte drops were a ring-wrap bug in
      // the RP2040 cdc_uart.c bridge, fixed in firmware, not a baud problem.
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("RP2040"))))))

  /**
   * A-E115FB — EP4CE115 + 1 GB DDR2 SODIMM, serial boot.
   *
   * clkFreq is 75 MHz because the core runs in the controller's `phy_clk`
   * domain. The IP is generated at a 150 MHz memory clock, half-rate, so
   * phy_clk = 75 MHz. It was originally 166/83 MHz, but LruCacheCore could not
   * close timing at 83 MHz on this -7 part (-1.053 ns, on
   * pendingIndex -> compVictimIsDirty), and lowering the memory clock fixes
   * every path at once. DDR2 bandwidth is not the constraint — the exerciser
   * measured 1.2 GB/s and was command-rate limited, not clock limited. The
   * 25 MHz board oscillator only feeds the controller's PLL reference.
   *
   * addressWidth/mainMemSize are overridden from the memory device by JopTop, so
   * this picks up 1 GB (addressWidth 30) automatically. That path was
   * parameterised in Stage 1 but has never run on hardware before this board.
   *
   * The UART is the board's own CH340 (FPGA TX H5 / RX N1), not a Pico bridge.
   */
  def ae115fbDdr2 = JopConfig(
    assembly = SystemAssembly.ae115fb,
    systems = Seq(JopSystem(
      name = "main",
      memory = "ddr2",
      bootMode = BootMode.Serial,
      clkFreq = 75 MHz,
      // cardTableBudgetBytes 64 KB, not the 16 KB the other boards use.
      // cardShift is derived as the smallest shift that fits the budget, so on
      // a 1 GB heap 16 KB gives 2048-word (8 KB) cards — four times coarser
      // than the XC7A100T gets from the same budget over 256 MB. A dirty card
      // costs a scan of every word it covers, which made the card scan 5.1 ms
      // of a 17.3 ms minor pause here against 1.5 ms on the XC7A100T. 64 KB
      // buys 512-word cards, matching that board's granularity, for ~10% more
      // of the EP4CE115's BRAM.
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasBackendFill = true,
        hasCardTable = true, cardTableBudgetBytes = 64 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
      // 1 Mbaud, not the 2 Mbaud default. UartCtrl divides by baud x 5 samples,
      // so at 75 MHz a 2 Mbaud divider is 7.5 -> 7, i.e. 2.143 Mbaud: +7% and
      // far outside UART tolerance. 75 MHz divides EXACTLY into 1 M (divider
      // 15), 1.5 M (10) and 3 M (5) — 2 M is the one standard rate it cannot
      // reach, so the odd core clock is not the problem it looks like.
      // Brought up at 115200 (divider 130, +0.16%) and raised once the link was
      // proven; 1 Mbaud cuts a 44 KB download from 4.0 s to ~0.5 s.
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340"),
        params = Map("baudRate" -> 1000000))))))

  /** XC7A100T + DB_FPGA V5 — DDR3, full I/O (Ethernet + VGA + SD) */
  def xc7a100tDbFull = JopConfig(
    assembly = SystemAssembly.xc7a100tWithDbV5,
    systems = Seq(JopSystem(
      name = "main",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      devices = Map(
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("RP2040")),
        "eth" -> DeviceInstance(DeviceType.Ethernet, params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "vgaText" -> DeviceInstance(DeviceType.VgaText, devicePart = Some("VGA")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(useDspMul = true, bytecodes = Map("*" -> "hw")))))

  /** XC7A100T + DB_FPGA V5 — DDR3 SMP */
  def xc7a100tDbSmp(n: Int) = {
    val base = xc7a100tDbSerial
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n)))
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
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N")),
        "eth" -> DeviceInstance(DeviceType.Ethernet, params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        useDspMul = true, bytecodes = Map("*" -> "hw")))))
}
