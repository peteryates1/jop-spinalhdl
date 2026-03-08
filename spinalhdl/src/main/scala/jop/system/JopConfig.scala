package jop.system

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
 * @param clkFreqHz      System clock frequency in Hz (after PLL)
 * @param cpuCnt         Number of CPU cores
 * @param coreConfig     Default configuration for all cores
 * @param perCoreConfigs Optional per-core override (heterogeneous cores)
 * @param ioConfig       I/O device configuration
 * @param drivers        Which device drivers to instantiate
 * @param perCoreUart    Whether each core gets its own UART (debug)
 */
case class JopSystem(
  name: String,
  memory: String,
  bootMode: BootMode,
  arbiterType: ArbiterType = ArbiterType.RoundRobin,
  clkFreqHz: Long,
  cpuCnt: Int = 1,
  coreConfig: JopCoreConfig = JopCoreConfig(),
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None,
  ioConfig: IoConfig = IoConfig(),
  drivers: Seq[DeviceDriver] = Seq.empty,
  perCoreUart: Boolean = false
) {
  require(cpuCnt >= 1, s"System '$name': cpuCnt must be at least 1")
  perCoreConfigs.foreach(pcc =>
    require(pcc.length == cpuCnt,
      s"System '$name': perCoreConfigs length (${pcc.length}) must match cpuCnt ($cpuCnt)"))

  // --- Derived paths from boot mode ---
  def romPath: String = s"asm/generated/${bootMode.dirName}/mem_rom.dat"
  def ramPath: String = s"asm/generated/${bootMode.dirName}/mem_ram.dat"

  def baseJumpTable: JumpTableInitData = bootMode match {
    case BootMode.Serial     => JumpTableInitData.serial
    case BootMode.Flash      => JumpTableInitData.flash
    case BootMode.Simulation => JumpTableInitData.simulation
  }

  // --- Derived: per-core configs (heterogeneous or uniform) ---
  def coreConfigs: Seq[JopCoreConfig] = perCoreConfigs.getOrElse(
    Seq.fill(cpuCnt)(coreConfig))

  // --- Derived: union of all cores' needs ---
  def needsIntegerCompute: Boolean = coreConfigs.exists(_.needsIntegerCompute)
  def needsFloatCompute: Boolean = coreConfigs.exists(_.needsFloatCompute)
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
    systems.head
  }

  // --- Validation ---

  // Each system's memory must exist on the assembly (by part name or role)
  systems.foreach { sys =>
    require(
      assembly.findDevice(sys.memory).isDefined ||
      assembly.findDeviceByRole(sys.memory).isDefined,
      s"System '${sys.name}' references memory '${sys.memory}' " +
      s"but assembly '${assembly.name}' has no such device or role")
  }

  // Each driver's device must exist on the assembly
  systems.foreach { sys =>
    sys.drivers.foreach { d =>
      require(assembly.findDevice(d.devicePart).isDefined,
        s"System '${sys.name}' driver ${d.componentName} requires device " +
        s"'${d.devicePart}' but assembly '${assembly.name}' has none")
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
}

// ==========================================================================
// Presets — builder pattern with copy()
// ==========================================================================

object JopConfig {
  import Implementation._

  // ========================================================================
  // Single-system presets (common case)
  // ========================================================================

  /** QMTECH EP4CGX150 + daughter board — primary dev platform */
  def qmtechSerial = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9825G6JH6",
      bootMode = BootMode.Serial,
      clkFreqHz = 80000000L,
      drivers = Seq(DeviceDriver.Uart, DeviceDriver.EthGmii, DeviceDriver.SdNative))))

  /** QMTECH SMP — N cores */
  def qmtechSmp(n: Int) = {
    val base = qmtechSerial
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n)))
  }

  /** QMTECH with hardware integer math (IntegerComputeUnit) */
  def qmtechHwMath = {
    val base = qmtechSerial
    base.copy(systems = Seq(base.system.copy(
      name = "hwmath",
      coreConfig = JopCoreConfig(
        memConfig = base.system.coreConfig.memConfig,
        supersetJumpTable = base.system.coreConfig.supersetJumpTable,
        imul = Microcode, idiv = Hardware, irem = Hardware))))
  }

  /** QMTECH with hardware float (FloatComputeUnit) */
  def qmtechHwFloat = {
    val base = qmtechSerial
    base.copy(systems = Seq(base.system.copy(
      name = "hwfloat",
      coreConfig = JopCoreConfig(
        memConfig = base.system.coreConfig.memConfig,
        supersetJumpTable = base.system.coreConfig.supersetJumpTable,
        imul = Microcode, idiv = Hardware, irem = Hardware,
        fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
        fneg = Hardware, i2f = Hardware, f2i = Hardware,
        fcmpl = Hardware, fcmpg = Hardware))))
  }

  /** CYC5000 standalone */
  def cyc5000Serial = JopConfig(
    assembly = SystemAssembly.cyc5000,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9864G6JT",
      bootMode = BootMode.Serial,
      clkFreqHz = 100000000L,
      drivers = Seq(DeviceDriver.UartFt2232))))

  /** Alchitry Au V2 */
  def auSerial = JopConfig(
    assembly = SystemAssembly.alchitryAuV2,
    systems = Seq(JopSystem(
      name = "main",
      memory = "MT41K128M16JT-125:K",
      bootMode = BootMode.Serial,
      clkFreqHz = 83333333L,
      drivers = Seq(DeviceDriver.UartFt2232))))

  /** Simulation (no physical board — uses QMTECH assembly as placeholder) */
  def simulation = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "sim",
      memory = "W9825G6JH6",
      bootMode = BootMode.Simulation,
      clkFreqHz = 100000000L)))

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
        clkFreqHz = 100000000L,
        cpuCnt = 4,
        coreConfig = JopCoreConfig(
          imul = Microcode, idiv = Hardware, irem = Hardware,
          fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
          fneg = Hardware, i2f = Hardware, f2i = Hardware,
          fcmpl = Hardware, fcmpg = Hardware),
        drivers = Seq(DeviceDriver.EthGmii, DeviceDriver.SdNative)),
      JopSystem(
        name = "io",
        memory = "sdr",                  // by role
        bootMode = BootMode.Serial,
        clkFreqHz = 50000000L,
        cpuCnt = 2)),
    interconnect = Some(InterconnectConfig(fifoDepth = 64)),
    monitors = Seq(WatchdogConfig(timeoutMs = 2000)))

  // ========================================================================
  // Minimum resource preset
  // ========================================================================

  /** Absolute minimum: no compute units, pure microcode imul, no peripherals */
  def minimum = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "min",
      memory = "W9825G6JH6",
      bootMode = BootMode.Serial,
      clkFreqHz = 80000000L,
      coreConfig = JopCoreConfig(
        supersetJumpTable = JumpTableInitData.bareSerial,
        imul = Microcode,
        idiv = Java,
        irem = Java),
      drivers = Seq(DeviceDriver.Uart))))
}
