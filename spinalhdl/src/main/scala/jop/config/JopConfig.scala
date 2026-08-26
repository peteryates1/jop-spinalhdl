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
  // The layout itself lives in MicrocodePaths, which is the only place it is
  // written down; see the note there on the simulation asymmetry.
  def romPath: String = MicrocodePaths.rom(bootMode)
  def ramPath: String = MicrocodePaths.ram(bootMode)

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
/** Turn on the IO_PERFCNT memory-stall counters for a whole config.
  *
  * A build-time switch rather than a preset variant: every board needs the same
  * measurement build, and duplicating four presets to flip one Boolean would
  * guarantee they drift. `JopTopVerilog <preset> perf` applies it.
  *
  * Off in every preset on purpose — eleven 32-bit counters are not free on a
  * marginal fit, and the XC7A100T closes at +0.001 ns.
  */
/** Move the UART onto a different board connector by swapping the device part.
  *
  * `JopTopVerilog <preset> uart=PICO_UART0` retargets without duplicating a
  * preset. The pins come from the board definition, so a part that the board
  * does not define is a hard error rather than a silently unconnected UART.
  */
object UartPartOverride {
  def apply(c: JopConfig, part: String): JopConfig = {
    require(c.assembly.fpgaBoard.devices.exists(_.part == part),
      s"board ${c.assembly.fpgaBoard.name} defines no device part '$part'; " +
      s"known: ${c.assembly.fpgaBoard.devices.map(_.part).distinct.mkString(", ")}")
    c.copy(systems = c.systems.map { sys =>
      def on(cc: JopCoreConfig) = cc.copy(devices = cc.devices.map {
        case (k, d) if d.deviceType == DeviceType.Uart => k -> d.copy(devicePart = Some(part))
        case other => other
      })
      sys.copy(
        devices = sys.devices.map {
          case (k, d) if d.deviceType == DeviceType.Uart => k -> d.copy(devicePart = Some(part))
          case other => other
        },
        coreConfig = on(sys.coreConfig),
        perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
    })
  }
}

object PerfCountersOverride {
  def apply(c: JopConfig): JopConfig = c.copy(systems = c.systems.map { sys =>
    def on(cc: JopCoreConfig) = cc.copy(memConfig = cc.memConfig.copy(hasPerfCounters = true))
    sys.copy(
      coreConfig = on(sys.coreConfig),
      perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
  })
}

/** Method cache geometry override: `mcache=<jpcWidth>/<blockBits>`.
  *
  * Exists so item 51's sweep result can be built on hardware without hand-
  * editing a preset. The geometry is
  *   size  = 1 << jpcWidth bytes,  blocks = 1 << blockBits,
  *   blockWords = 1 << (jpcWidth - 2 - blockBits)
  * and the sweep found FRAGMENTATION dominates: at 2 KB the 32-word block
  * costs Kfl 34.8 % and UdpIp 23.2 % miss, while 4 KB with 32 blocks takes
  * both to ~0.1-0.6 %. `12/5` is that point; `12/6` goes further at twice the
  * comparators. JopCoreConfig validates the combination.
  */
/** Build-time override for the DRAM L2 size, so a sweep needs no preset edit:
  * `JopTopVerilog auSerial l2sets=256`. Sets must be a power of two.
  *
  * 1 does NOT elaborate -- log2Up(1) = 0 against a 1-bit index gives
  * "Vec address width mismatch: lruArray". 2 is the practical floor. */
object L2SetsOverride {
  def apply(c: JopConfig, spec: String): JopConfig = {
    val n = spec.toInt
    require(n >= 2 && (n & (n - 1)) == 0,
      s"l2sets=$spec must be a power of two and at least 2 (1 does not elaborate)")
    c.copy(systems = c.systems.map { sys =>
      def on(cc: JopCoreConfig) = cc.copy(memConfig = cc.memConfig.copy(l2SetCount = n))
      sys.copy(coreConfig = on(sys.coreConfig),
               perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
    })
  }
}

/** Bytecode implementation override: `bc=<key>:<impl>[,<key>:<impl>...]`,
  * e.g. `bc=double:mc` or `bc=double:mc,float:mc`.
  *
  * Exists because the method cache's real competitor for LUTs is the compute
  * units, not the core count, and that trade could not be explored without
  * hand-editing a preset. Per-core costs measured on this part
  * (`docs/analysis/wukong-utilization-sweep.md`): DCU 4,961 LUTs, FCU 1,788,
  * LCU 1,452 -- against ~850 per core to take the method cache from 16 to 64
  * blocks. CUs are PER CORE, so at four cores dropping the DCU frees ~19,800
  * LUTs and buys the 64-block geometry nearly six times over.
  *
  * Merged ON TOP of the preset's map, and `BytecodeConfig.resolve` ranks
  * name > group > "*", so `bc=double:mc` demotes just that group on a preset
  * built with `"*" -> "hw"`. Keys are validated there.
  *
  * NOT free: microcode fallbacks are unevenly covered (item 18 -- `lmul_sw`
  * went years unexecuted with a `require` checking the wrong predicate), so a
  * build that drops a CU needs the JVM suite run against it, not just a fit.
  */
object BytecodesOverride {
  def apply(c: JopConfig, spec: String): JopConfig = {
    val pairs = spec.split(",").filter(_.nonEmpty).map { p =>
      val kv = p.split(":")
      require(kv.length == 2, s"bc=$spec: each entry must be <key>:<impl>, e.g. double:mc")
      kv(0) -> kv(1)
    }.toMap
    require(pairs.nonEmpty, s"bc=$spec set no bytecodes")
    c.copy(systems = c.systems.map { sys =>
      def on(cc: JopCoreConfig) = cc.copy(bytecodes = cc.bytecodes ++ pairs)
      sys.copy(coreConfig = on(sys.coreConfig),
               perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
    })
  }
}

/** Point the LPM ROM/RAM at their .mif files for a project at a given depth.
  *
  * `MemoryStyle.AlteraLpm` carries `mifBasePath`, and its default
  * `../../build/microcode/serial` assumes the Quartus project sits two levels
  * below the repo root -- true of the in-tree board directories, false once a
  * project moves under `build/<config>/quartus`. The path is baked into the
  * generated Verilog as the megafunction's LPM_FILE generic, so getting it
  * wrong is a synthesis failure:
  *
  *   Error (127001): Can't find Memory Initialization File ... rom.mif
  *
  * SEARCH_PATH does not rescue it -- Quartus resolves the literal. So the path
  * is computed from the layout instead of assumed, and the old and new trees
  * can coexist while boards are converted one at a time.
  */
object MifPathOverride {
  def apply(c: JopConfig, mifBasePath: String): JopConfig =
    c.copy(systems = c.systems.map { sys =>
      // Consult the FAMILY, not the core config. The style is normally left
      // unset on JopCoreConfig and filled in from FpgaFamily.memoryStyle during
      // resolution, so testing the raw config sees None and this override
      // silently did nothing.
      val familyUsesLpm = c.fpgaFamily.memoryStyle.isInstanceOf[MemoryStyle.AlteraLpm]
      def on(cc: JopCoreConfig) =
        if (familyUsesLpm || cc.resolvedMemoryStyle.isInstanceOf[MemoryStyle.AlteraLpm])
          cc.copy(memoryStyle = Some(MemoryStyle.AlteraLpm(mifBasePath)))
        else cc
      sys.copy(coreConfig = on(sys.coreConfig),
               perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
    })
}

object MCacheOverride {
  def apply(c: JopConfig, spec: String): JopConfig = {
    val parts = spec.split("/")
    require(parts.length == 2, s"mcache=$spec must be <jpcWidth>/<blockBits>, e.g. mcache=12/5")
    val (jw, bb) = (parts(0).toInt, parts(1).toInt)
    c.copy(systems = c.systems.map { sys =>
      def on(cc: JopCoreConfig) = cc.copy(jpcWidth = jw, blockBits = bb)
      sys.copy(coreConfig = on(sys.coreConfig),
               perCoreConfigs = sys.perCoreConfigs.map(_.map(on)))
    })
  }
}

/** A board reset input: top-level port, FPGA pin, and how it is wired. */
case class ResetInput(port: String, pin: String, activeLow: Boolean)

case class JopConfig(
  assembly: SystemAssembly,
  systems: Seq[JopSystem],
  interconnect: Option[InterconnectConfig] = None,
  monitors: Seq[MonitorConfig] = Seq.empty,
  /** DDR3 memory-clock profile, for boards whose system clock is MIG's ui_clk.
    * None everywhere else. See MigProfile for why this is an enum of measured
    * points rather than a frequency parameter. */
  migProfile: Option[MigProfile] = None
) extends BoardDesign {
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

  /** The board reset INPUT: which port exists, on which pin, at what polarity.
    *
    * ONE definition, used by `JopTop` to decide whether to declare the port and
    * how to interpret it, and by `XdcGenerator`/`QsfGenerator` to constrain it.
    * Before this it was a private predicate inside `JopTop` plus a hardcoded
    * port name in `XdcGenerator` plus nothing at all in `QsfGenerator` — which
    * is why the generated QSF silently omitted the EP4CGX150's reset button
    * (item 57).
    *
    * The two families differ and both are captured here:
    *
    *  - **Xilinx** tops already carry `resetn`, SpinalHDL's default clock-domain
    *    reset, which reaches the core by unlocking the clock wizard. There is
    *    never a second port; the button is constrained onto that one.
    *  - **Everyone else** gets an explicit `reset_n` port, and only when the
    *    board actually maps a SWITCH "reset" pin. Adding it unconditionally
    *    would give every board a top-level input with nowhere to go.
    *
    * NOT YET AVAILABLE ON DDR2/DDR3, and that is an RTL limit rather than a
    * config one: those paths build their resets per memory controller
    * (`ddr2RuntimeReset`, `ddr3RuntimeReset` in `JopTop`) and deliberately do
    * not reset the MIG, so calibration survives. Wiring a button into them is
    * real work, not a flag. Boards with a spare switch — the A-E115FB has
    * one — stay without a reset button until that is done.
    */
  def resetInput: Option[ResetInput] = {
    val pin = jop.generate.PinResolver.resetFpgaPin(assembly)
    val activeLow = assembly.fpgaBoard.resetActiveLow
    if (fpgaFamily.manufacturer == Manufacturer.Xilinx) {
      // Always present: it is the clock domain's own reset.
      pin.map(ResetInput("resetn", _, activeLow))
    } else {
      val singleSystem = systems.length == 1
      val mem = if (singleSystem) resolveMemory(system).map(_.memType) else None
      val dramReset = mem.contains(MemoryType.SDRAM_DDR3) || mem.contains(MemoryType.SDRAM_DDR2)
      if (singleSystem && !dramReset) pin.map(ResetInput("reset_n", _, activeLow))
      else None
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
  // --- BoardDesign ---------------------------------------------------------
  // Every one of these was already computed at the point of use inside a
  // generator; naming them here is what lets a non-JOP design supply them too.
  def designName: String = system.name
  def devices: Map[String, DeviceInstance] = system.effectiveDevices
  def memType: Option[MemoryType] = resolveMemory(system).map(_.memType)
  def usesSdr: Boolean =
    systems.exists(s => resolveMemory(s).exists(_.memType == MemoryType.SDRAM_SDR))

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

  /**
   * EP4CGX150 + DB_FPGA V4 with the daughter board's peripherals: Gigabit
   * Ethernet, SD card and VGA.
   *
   * RESTORES A CAPABILITY THAT WAS LOST, NOT A NEW ONE. This board ran a
   * poll-based TCP/IP stack over an RTL8211EG at 1 Gbps -- ARP, DHCP, TCP, an
   * HTTP server -- documented in docs/peripherals/networking.md. The RTL came
   * from `JopDbFpgaTopVerilog`, an object inside the 920-line hand-written
   * JopSdramTop.scala, configured by `IoConfig.qmtechDbFpga`:
   *
   *   hasEth = true, ethGmii = true, hasSdNative = true, hasVgaText = true
   *
   * Commit 7258661 deleted that top in the move to declarative presets, and
   * 8641942 repointed `make generate-dbfpga` at `ep4cgx150Serial` -- which
   * declares none of those devices. Nothing replaced it, so jop_dbfpga.qsf has
   * been assigning 95 pins to a 45-port design ever since. See item 66.
   *
   * ONLY WHAT BOTH DB BOARDS CARRY. V4 and V5 share Ethernet, SD, VGA, J10, J11
   * and JP1; V4 adds a 7-segment display, LEDs and switches, V5 drops those and
   * adds an RP2040 for JTAG and UART. This preset takes the common set, which is
   * exactly what the working configuration used -- no 7-segment, no DIP
   * switches. The UART is the one board-specific part, and `devicePart` already
   * carries it: CP2102N here, RP2040 on the V5 (see `xc7a100tDbFull`).
   *
   * DIFFERS FROM THE ORIGINAL IN ONE KNOWN WAY: `useStackCache` was true in
   * JopDbFpgaTopVerilog and is left false here, matching `ep4cgx150Serial`.
   * Stack-cache SDRAM integration is still open (it needs per-core stack
   * regions), so the first build back differs from the working configuration in
   * one place rather than two. See item 67.
   */
  def ep4cgx150DbFull = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(hasBackendFill = true, hasCardTable = true,
          cardTableBudgetBytes = 16 * 1024,
          // From JopDbFpgaTopVerilog: the Ethernet driver moves whole frames,
          // so the burst length was raised for it.
          burstLen = 4, stackRegionWordsPerCore = 1024),
        useDspMul = true,
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw", "imul" -> "hw")),
      devices = Map(
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N")),
        "eth" -> DeviceInstance(DeviceType.Ethernet,
          params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "vgaText" -> DeviceInstance(DeviceType.VgaText, devicePart = Some("VGA")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))))))

  /**
   * `ep4cgx150DbFull` with VGA DMA in place of VGA text.
   *
   * The second half of what the migration dropped: history had TWO DB_FPGA
   * configurations, `IoConfig.qmtechDbFpga` (vgaText) and
   * `IoConfig.qmtechDbFpgaVgaDma` (vgaDma), generated by `JopDbFpgaTopVerilog`
   * and `JopDbFpgaVgaDmaTopVerilog`. Commit 8641942 repointed BOTH at
   * `ep4cgx150Serial`, so the two flows have since produced byte-identical RTL
   * and the distinction existed only in which hand-written project was built.
   *
   * They share a Quartus revision (jop_dbfpga) because the PINS are the same --
   * VGA is VGA either way. Only the framebuffer driver differs.
   */
  def ep4cgx150DbVgaDma = {
    val base = ep4cgx150DbFull
    val sys = base.systems.head
    base.copy(systems = Seq(sys.copy(devices =
      sys.devices - "vgaText" +
        ("vgaDma" -> DeviceInstance(DeviceType.VgaDma, devicePart = Some("VGA"))))))
  }

  /**
   * EP4CGX150 single core with the object and array caches REMOVED.
   *
   * The A/B half of current-status item 11's cache question: do the caches earn
   * their ~2,213 LE per core? Identical to `ep4cgx150Serial` in every other
   * respect, so a JbeBench run against each isolates the caches as the only
   * variable -- and the fit report gives the area side of the same trade.
   *
   * Kfl and UdpIp are the workloads that matter here: JbeBench showed both are
   * memory-latency-bound (they do ~25 % more work per MHz at 36 MHz than at 80,
   * because SDRAM latency is fixed in nanoseconds), which is exactly what a
   * cache is supposed to fix. Lift is compute-bound and should barely move --
   * if it does, something other than the caches changed.
   *
   * SINGLE CORE ON PURPOSE. Both caches sit in the SMP coherency path, so
   * turning them off on a multi-core build would change correctness as well as
   * performance and confuse the measurement with the thing being measured.
   */
  def ep4cgx150NoCache = {
    val base = ep4cgx150Serial
    base.copy(systems = Seq(base.system.copy(
      name = "nocache",
      coreConfig = base.system.coreConfig.copy(
        memConfig = base.system.coreConfig.memConfig.copy(
          useOcache = false, useAcache = false)))))
  }

  /** EP4CGX150 + daughter board — SMP, N cores */
  // clkMhz IS the frequency: `JopTopVerilog` generates
  // fpga/qmtech-ep4cgx150-sdram/generated/dram_pll.vhd from it (multiply/divide
  // reduced against the 50 MHz input), so the PLL cannot disagree with the
  // preset any more. It also reports the baud the board will actually manage
  // and warns when the requested one is not achievable -- see DramPllGen.
  //
  // It used to be the other way round, and the comment here said "clkMhz must
  // match dram_pll.vhd". Nothing cross-checked them, the PLL decided the real
  // frequency, and a mismatch surfaced as "FPGA not responding" rather than a
  // build error. That cost time on the 4-, 8- and 12-core bring-ups.
  //
  // 80 MHz is right for 1-2 cores (PLL 8/5, the checked-in default). At 4 cores
  // the BMB arbiter misses it badly — -2.399 ns setup, worst path
  // cores_1|zeroCur -> arbiter -> cores_3|bcFillAddr, with NO CardTable nodes on
  // it, so this is arbiter scaling and not the card-table work.
  //
  // Measured on EP4CGX150, 4 cores: 65 MHz still misses (-0.070), 60 MHz closes
  // (+0.302 setup / +0.321 hold). 60 also divides cleanly — exact microsecond
  // prescaler (59) and an exact 2 Mbaud UART divider (30), where 65 would have
  // given 32.5 and ~1.5% baud error.
  //
  // The PLL follows automatically now — `ep4cgx150Smp 4 60` emits a 6/5 PLL.
  // Known-good points: 4 cores 60 MHz, 8 cores 50 MHz, 12 cores 36 MHz (and at
  // 36 the UART drops to 1.8 Mbaud, which the generator warns about).
  // useCmpSync swaps IHLU (per-object locking) for the single global lock. It
  // exists to bisect the >2-core generational deadlock (item 1): IHLU's drain
  // exempts lock owners from gcHalt, so if 4 cores run with CmpSync and hang
  // with IHLU, the fault is in that exemption rather than in gcHalt itself.
  def ep4cgx150Smp(n: Int, clkMhz: Int = 80, cmpSync: Boolean = false) = {
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
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n, clkFreq = clkMhz MHz,
      coreConfig = base.system.coreConfig.copy(useCmpSync = cmpSync))))
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
  /**
   * EP4CGX150 — microcode-fallback coverage build.
   *
   * Selects every bytecode that has a *working* `_sw` handler, so the microcode
   * paths are exercised on a board other than the Colorlight i5. Without a
   * config like this most of them are never executed anywhere: the defaults put
   * float and double on the Java trap and `lmul` on it too, so `lmul_sw` in
   * particular has no coverage at all despite `compute-unit-design.md` having
   * once recorded it as broken and `900f66a` claiming a fix.
   *
   * `idiv`/`irem` are hardware **on purpose**, not an oversight in a build whose
   * point is software paths: it keeps an ICU in the design so `imul` can stay
   * `mc` and `imul_sw` is still exercised.
   *
   * The first run of this preset immediately paid for itself: with `lmul = mc`
   * it dropped 6 DoAll tests, which `JopJvmTestsLmulSwSim` narrowed to
   * `lmul_sw` alone. That handler had never been executed anywhere, because
   * `lmul` defaults to Java on every board.
   *
   * Not included: `fadd`/`fsub`/`fmul`/`fdiv`. They appear to have `_sw`
   * handlers but those drive the removed BmbFpu peripheral over I/O — see item
   * 22 in docs/current-status.md.
   *
   * This does not currently save area. CU instantiation is unconditional again
   * after `eda6de7`; recovering that is item 17. The value here is coverage.
   */
  def ep4cgx150McFallback = {
    val base = ep4cgx150Serial
    base.copy(systems = Seq(base.system.copy(
      name = "mc-fallback",
      coreConfig = base.system.coreConfig.copy(bytecodes = Map(
        // ICU present so lmul_sw has something to drive
        "idiv" -> "hw", "irem" -> "hw",
        // imul = hw, NOT mc — and this is a genuine either/or, not a
        // preference. imul_sw and lmul_sw cannot both be exercised in one
        // build:
        //   imul = mc -> imul_sw runs (it is a self-contained shift-add loop
        //                and needs no CU), but the ICU is built without a
        //                multiplier, so lmul_sw has nothing to dispatch to
        //   imul = hw -> lmul_sw works, but the jump table now points imul at
        //                imul_hw, so imul_sw is not selected
        // This preset takes the second. imul_sw keeps its coverage from every
        // default-config sim, since imul defaults to Microcode.
        "imul" -> "hw", "lmul" -> "mc",
        "ladd" -> "mc", "lsub" -> "mc", "lneg" -> "mc", "lcmp" -> "mc",
        "lshl" -> "mc", "lshr" -> "mc", "lushr" -> "mc",
        // float microcode (no FCU needed for any of these)
        "fneg" -> "mc", "fcmpl" -> "mc", "fcmpg" -> "mc")))))
  }

  /**
   * EP4CGX150 -- BRAM main memory (512KB), no serial download.
   *
   * 60 MHz, NOT the 80 the SDRAM presets use, because the BRAM read-data path
   * will not close at 80 on this device. From an M9K address register inside
   * `BmbOnChipRam`, through the memory's clock-to-out, through the read mux
   * across the block array, through the BMB response fabric and into
   * `BmbMemoryController.rdDataReg` -- and onward to the UART FIFO -- is ONE
   * combinational cycle, measured at 15.1 ns for a 32-block array and 16.3 ns
   * for the 126 blocks this 512KB preset needs, against a 12.5 ns period.
   * `ep4cgx150Serial` closes at +0.626 ns on the same device at the same 80 MHz
   * because `BmbSdramCtrl32` REGISTERS read data and breaks the path.
   *
   * These presets were at 80 (and 100 before that) since March and never closed
   * timing; nothing looked, because the board appeared to work. It appeared to
   * work because `dram_pll.vhd` used to be hardwired to 60 regardless of what
   * the preset declared, so the silicon ran at 60 while the .sdc claimed 80.
   * `DramPllGen` builds the PLL from the preset now, so the declared frequency
   * is the real one and the violation became real with it.
   *
   * NOTE the array-size term is secondary: 32 blocks is already 15.1 ns. Making
   * the memory smaller does not fix this, and lowering the clock is not a
   * workaround for a marginal path -- the path is 2.5 ns over at its best.
   */
  def ep4cgx150Bram = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "bram",
      memory = "bram",
      bootMode = BootMode.Simulation,
      // 50, not the 60 the 128KB BRAM presets use. 512KB is 126 M9K blocks
      // against their 32, and the read mux across the array adds ~1.2 ns: this
      // preset still missed by 0.431 ns at 60 when the others had cleared it.
      clkFreq = 50 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 512 * 1024),
        memoryStyle = Some(MemoryStyle.Generic)),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"))))))

  /** EP4CGX150 — pre-initialized BRAM (128KB) for GC testing.
    *
    * Restores the 60 MHz that ep4cgx150Bram gives up for its larger array: at
    * 128KB the mux is 31 blocks, and this closes at +0.745 ns. Overridden
    * explicitly rather than inherited, so shrinking the base's memory later
    * cannot silently reclock this one. */
  def ep4cgx150BramGc = {
    val base = ep4cgx150Bram
    base.copy(systems = Seq(base.system.copy(
      clkFreq = 60 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 128 * 1024)))))
  }

  /**
   * EP4CGX150 — BRAM, serial download, N cores. THE SIM/HARDWARE BRIDGE.
   *
   * At 4 cores SmpGcTest passed in simulation on BOTH memory models (BRAM and
   * the SDRAM model) but STALLED on the board, with one core starving
   * deterministically. **That no longer reproduces** (2026-08-26): SmpGcTest
   * prints `SMPGC OK` with all 4 cores at 50 MHz. It was fixed somewhere
   * between, and which fix is NOT established -- do not assume this preset is
   * still demonstrating the original defect. Timing closure was tested as the
   * explanation and REFUTED: the violated 60 MHz build passes too. Every other variable has been matched — core count, lock
   * (Ihlu), GC mode, even the layout (cardShift 4, nurseryBase 1902394) — so the
   * remaining difference is silicon itself. This build keeps Quartus synthesis
   * and the real device while removing the SDRAM controller and the physical
   * memory from the equation, which bisects "silicon" from "the SDRAM path".
   *
   * 50 MHz by default. The single-core BRAM presets close at 60, but 4 cores do
   * not: the critical path reverses direction, running from core 3's
   * `BmbMemoryController.addrReg` INTO the shared `BmbOnChipRam` port-A address
   * and write-enable registers -- four cores arbitrating for one on-chip
   * memory, rather than the read-data path out of it that limits the
   * single-core builds (see ep4cgx150Bram). 4 cores measured -0.629 ns at 60
   * and +1.906 ns at 50.
   *
   * This note used to justify the 60 differently: that `dram_pll.vhd` was
   * hardwired to 60 and the preset frequency "only feeds the SDC constraint and
   * the UART divider". That was true, and it is why three BRAM presets sat
   * violated at 80 for months without anyone noticing -- the declared frequency
   * was understood to be decorative. `DramPllGen` generates the PLL from the
   * preset now, so it is not.
   *
   * hasCardTable is required or IO_CARD_SHIFT reads 0, GC.init falls back to the
   * classic collector, and the run exercises nothing generational.
   */
  def ep4cgx150BramSmp(n: Int, clkMhz: Int = 50) = {
    val base = ep4cgx150BramSerial
    base.copy(systems = Seq(base.system.copy(
      name = s"bram-smp$n",
      cpuCnt = n,
      clkFreq = clkMhz MHz,
      coreConfig = base.system.coreConfig.copy(
        memConfig = JopMemoryConfig(mainMemSize = 128 * 1024,
          hasCardTable = true, cardTableBudgetBytes = 16 * 1024)))))
  }

  /** EP4CGX150 — serial download into BRAM (128KB) */
  def ep4cgx150BramSerial = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "bram-serial",
      memory = "bram",
      bootMode = BootMode.Serial,
      // 60, for the read-data path described on ep4cgx150Bram. 2 Mbaud divides
      // 60 MHz exactly (30), so the console needs no scaling workaround.
      clkFreq = 60 MHz,
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
      // 4 KB L2 (64 sets), not the 512-set/32 KB default. The XC7A35T is the
      // smallest part in the set at 20800 LUTs and this preset does NOT fit
      // with the default: measured 2026-08-22, the L2 alone was 12450 LUTs --
      // more than twice the whole JOP core (5378) -- against a 1754-LUT
      // overflow at implementation.
      //
      // 64 rather than the largest that fits, and that is MEASURED, not a
      // guess. JbeBench on this board, same binary, only this parameter
      // changed:
      //
      //   sets  L2     LUTs         WNS      Kfl     UdpIp   Lift
      //   64    4 KB   9211  (44%)  +0.477   16855   7353    18586
      //   256   16 KB  12975 (62%)  +0.146   16855   7353    18575
      //   512   32 KB  18384        --       does not fit
      //
      // Kfl and UdpIp are bit-identical and Lift is 0.06 % LOWER at 4x the
      // cache. So 64 sets costs nothing measurable and keeps 18 points of LUT
      // headroom and 0.33 ns of slack. Consistent with item 50 (a 32 KB L2 is
      // worth 3-5 %): the L2's EXISTENCE may buy a little, its SIZE past 4 KB
      // buys nothing here.
      //
      // Note the LUT cost is sharply non-linear -- 64->256 sets costs 3764,
      // 256->512 costs another 5409 -- so most of the L2's logic is in the
      // last doubling.
      coreConfig = JopCoreConfig(
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw"),
        memConfig = JopMemoryConfig(l2SetCount = 64)),
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
  /** Which connector the Wukong console comes out of, stated ONCE.
    *
    * DEFAULTS TO THE ON-BOARD CH340N for every single-system build. Both are
    * physically available -- the CH340N at E3/F3 and the Pico UARTs on the J11
    * header -- so this is a choice about the rig, not a constraint of the
    * design, and the CH340N needs no extra hardware.
    *
    * The earlier reason for preferring J11 on DDR3 builds is gone: it was that
    * a second `1a86:7523` bridge on the host could not be told apart from the
    * A-E115FB's. Those adapters now pass their serial numbers through, so both
    * boards' CH340s are addressable at once (`fpga/scripts/usb_serial_map`).
    *
    * TO PICK A DIFFERENT ONE FOR A SETUP, do not add a preset:
    *
    *   sbt "runMain jop.system.JopTopVerilog wukongDdr3 uart=PICO_UART0"
    *
    * `UartPartOverride` checks the part exists on the board, so a typo is a
    * hard error rather than a silently unconnected UART.
    *
    * THE DUAL BUILDS ARE THE EXCEPTION and cannot use this: one CH340N cannot
    * serve two systems, so they take one Pico UART each. A way to switch the
    * CH340N between the two systems on the FPGA would remove that exception --
    * worth looking at, not done.
    *
    * Getting this wrong is expensive and silent: the config said CH340N while
    * the DDR3 XDC routed to J11, so the build summary named a connector the
    * bitstream did not drive, and an hour went on a silent /dev/ttyUSB3 while
    * the board answered on /dev/ttyACM0. `ConstraintDriftTest` now fails if the
    * config and the constraints disagree.
    */
  private val wukongConsolePart = "CH340N"

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
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("PICO_UART1")))),   // ddr3 half -- see wukongConsolePart
      JopSystem(
        name = "io",
        memory = "sdr",                  // by role
        bootMode = BootMode.Serial,
        clkFreq = 50 MHz,
        cpuCnt = 2,
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("PICO_UART0"))))),  // sdr half -- see wukongConsolePart
    interconnect = Some(InterconnectConfig(fifoDepth = 64)),
    monitors = Seq(WatchdogConfig(timeoutMs = 2000)))

  /** Wukong dual-independent: DDR3 + SDR with separate UARTs (no interconnect) */
  def wukongDualIndependent = wukongDualIndependentSmp()
  def wukongDualIndependentMig(mig: MigProfile) = wukongDualIndependentSmp(mig = mig)

  // sdrClkMhz must match CLKOUT1/CLKOUT2 in create_sdram_clk_wiz_1.tcl AND the
  // getOrElse default in JopTopVerilog — three places, none cross-checked.
  //
  // Do not lower it to chase the "VIOLATED" status in the dual build. That
  // WNS is measured against the hand-picked `set_max_delay 5.0` in
  // build_dual_bitstream.tcl, which exists only as placement guidance, not as a
  // real SDRAM requirement — wukongSdrFull misses the same constraint by
  // -0.774 ns and runs correctly on hardware. Lowering the clock also detunes
  // sdram_clk: the -108 degree phase shift is a ~3.75 ns lead at 80 MHz, and
  // scales with the period, so 70 MHz makes timing at the SDRAM chip worse
  // while leaving the max_delay path (an absolute 5 ns budget on a 3.35 ns
  // OBUFT) essentially unchanged. Measured: 80 -> 70 MHz moved WNS by 0.011 ns.
  // 100 MHz matches the standalone SDR presets (wukongSdram / wukongSdrAllCu /
  // wukongSdrFull), all of which run DoAll 66/66 on this board's SDRAM. The
  // dual build previously used 80 MHz while keeping create_sdram_clk_wiz_1's
  // -108 degree shift on sdram_clk. That shift is an absolute setup/hold margin
  // at the SDRAM chip but is specified in degrees, so it scales with the
  // period: 3.00 ns at 100 MHz (the only value validated on hardware) silently
  // became 3.75 ns at 80 MHz.
  /** @param mig the DDR3 system's clkFreq is DERIVED from this, never passed
    *            alongside it — the two must agree, because ui_clk is what that
    *            cluster actually runs at. Hardcoding 100 MHz here meant this
    *            preset could not be built at all against a Ddr3_366 mig.prj. */
  /** @param sdrClkMhz DECLARED frequency only. The SDR half's actual clock comes
    *        from the clk_wiz IP, fixed at clk_100 — changing this does NOT
    *        change the hardware. It changes the UART divider and the IO_US_CNT
    *        prescaler, so a value other than 100 gives a wrong baud and a wrong
    *        benchmark calibration while timing is analysed at 10.000 ns
    *        regardless. Same trap as the EP4CGX150's hardwired dram_pll. */
  def wukongDualIndependentSmp(cpuCnt: Int = 1, sdrClkMhz: Int = 100,
                               mig: MigProfile = MigProfile.Ddr3_400) = JopConfig(
    assembly = SystemAssembly.wukongWithJ11Uart,
    migProfile = Some(mig),
    systems = Seq(
      JopSystem(name = "ddr3", memory = "ddr3", bootMode = BootMode.Serial,
        clkFreq = HertzNumber(mig.uiClkHz), cpuCnt = cpuCnt,
        // hasPerfCounters IS THE POINT OF THIS PRESET -- do not make it opt-in.
        // It used to rely on the `perf` CLI switch, so `make dual-generate`
        // produced a bitstream whose IO_PERFCNT reads 0 for every category.
        // That does not fail the build, it fails silently at measurement time,
        // an hour of synthesis later.
        // CORE CONFIG MUST MATCH THE sdr SYSTEM BELOW, EXACTLY.
        // This preset exists to compare two MEMORY systems on one die, so the
        // cores either side of that comparison have to be identical. They were
        // not: this half had `useDspMul = true, bytecodes = "*" -> "hw"` while
        // the sdr half took the defaults -- imul as a ~35-cycle microcode
        // shift-add and idiv/irem as ~1300-cycle Java calls. That is a compute
        // difference inside a memory experiment, and it biased the DDR3 half
        // in its own favour.
        // `idiv`/`irem` = hw is also what every other profiled board uses
        // (A-E115FB, CYC5000, Colorlight i5, wukongDdr3), so matching DOWN to
        // it makes both halves comparable to those boards as well as to each
        // other -- and drops the CUs that `*=hw` pulled in.
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024,
          hasPerfCounters = true),
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
        // PICO_UART1 (F4/H4 = J11.2/J11.1), not CH340N. The on-board bridge at
        // E3/F3 is hardwired on the PCB and cannot be tapped, and a second
        // 1a86:7523 on the host is indistinguishable from the A-E115FB's. The
        // two systems therefore take one Pico UART each:
        //   ddr3 -> PICO_UART1 (pico uart1, gpio4/5)
        //   sdr  -> J11_UART   (A5/A4 = pico uart0, gpio12/13)
        // which is what makes a simultaneous two-backend run possible at all.
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("PICO_UART1"),
          params = Map("baudRate" -> 2000000)))),
      JopSystem(name = "sdr", memory = "sdr", bootMode = BootMode.Serial,
        clkFreq = sdrClkMhz MHz, cpuCnt = cpuCnt,
        // Identical to the ddr3 core above -- see the note there.
        coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024,
          hasPerfCounters = true),
          bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
        // 2 Mbaud on both halves. 115200 here was a bring-up leftover from when
        // the UART divided the clock by an INTEGER and an awkward core clock
        // could not express a round rate. jop.io.UartBaudTick removed that, so
        // both 91.676 MHz (DDR3) and 100 MHz (SDR) hit 2 Mbaud exactly, and a
        // 65 KB download drops from ~6 s to ~0.4 s. The measurement itself is
        // unaffected -- the counters bracket the benchmark, not the printing.
        devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("J11_UART"),
          params = Map("baudRate" -> 2000000))))),
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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some(wukongConsolePart))))))

  /**
   * Wukong SDR — SMP, N cores. The SDR counterpart of `wukongDdr3Smp`, so the
   * two memory systems can be compared on ONE board.
   *
   * Why that matters: the scaling curves so far compare EP4CGX150-SDR against
   * Wukong-DDR3, which confounds board, fabric, clock and memory system at once.
   * Running SDR and DDR3 on the same XC7A100T leaves only the memory path
   * different, which is the variable the saturation question is about.
   *
   * Unlike DDR3, the SDR clock is NOT dictated by the memory controller — it
   * comes from the clk_wiz and is freely chosen — so `clkMhz` is a real
   * parameter here rather than a profile. Keep it at 100 unless timing forces
   * lower, and normalise per MHz when comparing against DDR3's 91.68.
   */
  def wukongSdrSmp(n: Int, clkMhz: Int = 100) = {
    val base = wukongSdram
    base.copy(systems = Seq(base.system.copy(
      name = s"sdrsmp$n", cpuCnt = n, clkFreq = clkMhz MHz)))
  }

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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some(wukongConsolePart))))))

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
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some(wukongConsolePart))))))

  /**
   * Colorlight i5 v7.0 — serial download into BRAM. Stage 1 bring-up.
   *
   * 64 KB main memory, not the 128 KB the EP4CGX150 BRAM preset uses: the
   * LFE5U-25F has 1008 Kbit of EBR in total and 128 KB of it would be 1024 Kbit
   * before a single cache or the microcode store.
   *
   * 40 MHz rather than the 80-100 MHz of the mature boards. This is a first
   * bring-up on a toolchain (yosys/nextpnr) the project has never targeted, so
   * the clock is deliberately slack — but 40 is also what the design actually
   * holds honestly. It routes anywhere between 47.6 and 50.6 MHz depending only
   * on the nextpnr placer seed; 50 MHz does pass, on seed 2, by 0.56 MHz. That
   * is inside the seed noise, not headroom, and any later edit re-rolls it.
   * The critical path is the JBC RAM output -> bytecode byte select ->
   * branchOffset -> fetch.pcMux, 5.8 ns of which is DP16KD clock-to-out — the
   * same fetch/branch path that limits JOP on every other board.
   *
   * No card table, so the generational collector disables itself at boot via the
   * `genActive` guard and reports "GC: classic". That is the intended stage-1
   * state, not an oversight; a card table follows once SDRAM lands and there is
   * a heap worth collecting.
   */
  def colorlightI5Bram = JopConfig(
    assembly = SystemAssembly.colorlightI5,
    systems = Seq(JopSystem(
      name = "bram-serial",
      memory = "bram",
      bootMode = BootMode.Serial,
      clkFreq = 40 MHz,
      coreConfig = JopCoreConfig(
        memConfig = JopMemoryConfig(mainMemSize = 64 * 1024),
        // Matches colorlightI5Sdram. Keeping the two i5 cores identical apart
        // from the memory system is the whole value of having a BRAM stage: it
        // only predicts anything about the SDRAM build if the core is the same.
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw",
                        "fcmpl" -> "mc", "fcmpg" -> "mc")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("DAPLINK"),
        // 1 Mbaud, verified on hardware. 40 MHz divides exactly here
        // (UartCtrl divides by baud x 5 samples, so 40e6/(1e6*5) = 8 with no
        // remainder), which is why this rate and not a rounder-looking one —
        // there is no baud error to trade off, only the DAPLink CDC firmware's
        // own limit. Cuts the 46 KB HelloWorld download from 4.1 s to ~0.5 s.
        // 2 Mbaud also divides exactly (divider 4) if the DAPLink will take it.
        params = Map("baudRate" -> 1000000))))))

  /**
   * Colorlight i5 v7.0 — SDRAM. Stage 2.
   *
   * 8 MB of EM638325BK-6H, which is **32 bits wide** — the only such part in
   * this project. That is why it uses `BmbSdramCtrlWide` (one SDRAM access per
   * JOP word) rather than `BmbSdramCtrl32`, which splits each word into two
   * 16-bit halves. `MemoryControllerFactory.createSdr` picks between them on
   * `layout.dataWidth`, so nothing here has to say which.
   *
   * Card table enabled, unlike the BRAM preset. It is not optional once there
   * is a real heap: `GC.USE_GENERATIONAL` defaults true, and without the
   * hardware card-marking barrier the remembered set is permanently empty, so
   * the minor collector cannot see tenured->nursery references and collects
   * live young objects. The CYC5000 demonstrated exactly that failure
   * (`corrupt 23 / MAJOR FAIL`) while DoAll still passed 66/66. 8 KB of budget
   * on an 8 MB heap gives a card shift comparable to the other SDR boards.
   *
   * Still 40 MHz. The BRAM build closed at 46 MHz and this one is strictly
   * larger, so raising the clock is a separate exercise from getting SDRAM
   * working — do them one at a time.
   */
  def colorlightI5Sdram = JopConfig(
    assembly = SystemAssembly.colorlightI5,
    systems = Seq(JopSystem(
      name = "main",
      memory = "sdr",
      bootMode = BootMode.Serial,
      clkFreq = 40 MHz,
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(
        hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        // idiv/irem: same as every other hardware preset here.
        //
        // fcmpl/fcmpg: this board has no FCU, so the alternative is the Java
        // trap through SoftFloat32 at ~600 cycles; fcmpl_sw/fcmpg_sw do it in
        // ~30 for 97 ROM words, out of ~1940 free. Being the only board that
        // selects them, it is also where they get exercised on real hardware —
        // a build using the default Java path never executes them at all.
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw",
                        "fcmpl" -> "mc", "fcmpg" -> "mc")),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("DAPLINK"),
        params = Map("baudRate" -> 1000000))))))

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
      devices = uartOnly(base.system))))
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
        // 1 Mbaud, not the 2 Mbaud default. At 2 Mbaud a GcPauseTest run came
        // back with "generatlona disabled", "GsPaueTest done" and hSize=6696246
        // where the real value is 66562496 — text still readable, numbers
        // silently wrong, which is the worst kind of lossy. 100 MHz / (1e6 x 5)
        // = 20 exactly, so there is no baud error compounding a marginal cable.
        //
        // THE DEVICE BLAMED FOR THAT WAS THE WRONG ONE. This comment used to
        // say "the on-board CH340N drops characters at 2 Mbaud". It cannot have
        // been the CH340N: `devicePart` below says CH340N, but every DDR3 build
        // reads wukong_ddr3_base.xdc, which puts the UART on the J11 header ->
        // Pico uart0 and says in terms that the CH340N at E3/F3 is hardwired to
        // the PCB and "cannot be tapped". So the corruption was on the PICO CDC
        // path. The 1 Mbaud limit is real and measured; only its cause is
        // unknown, and whether 2 Mbaud works over the Pico is untested.
        // See `console` below and item 52 — devicePart is a LABEL here, not the
        // routing: the Vivado flow takes pins from the hand-written XDC.
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some(wukongConsolePart),
          params = Map("baudRate" -> 1000000)),
        "eth" -> DeviceInstance(DeviceType.Ethernet, params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 16 * 1024),
        useDspMul = true, bytecodes = Map("*" -> "hw")))))

  /** Wukong DDR3, configured to MATCH `auSerial` field for field.
    *
    * Exists to compare the Alchitry Au (XC7A35T) against the same design on a
    * bigger part of the same family (XC7A100T), and to route L2 sizes the Au
    * cannot fit. `wukongFull` is NOT usable for that: it is all four CUs
    * (`"*" -> "hw"`), DSP multiply, a 16 KB card table, plus Ethernet and SD.
    * An A/B whose arms differ in that much measures nothing -- the same trap as
    * the dual-system halves that once differed in CORES.
    *
    * Everything here is auSerial's: ICU only, no DSP multiply, no card table,
    * UART only, 100 MHz DDR3. The differences that REMAIN are the board itself
    * (assembly, DDR3 device, UART part) and are unavoidable.
    *
    * Sweep the L2 with the switch rather than editing this:
    *   sbt "runMain jop.system.JopTopVerilog wukongAuMatch l2sets=512"
    */
  def wukongAuMatch = JopConfig(
    assembly = SystemAssembly.wukong,
    systems = Seq(JopSystem(
      name = "au-match",
      memory = "ddr3",
      bootMode = BootMode.Serial,
      clkFreq = 100 MHz,
      coreConfig = JopCoreConfig(
        bytecodes = Map("idiv" -> "hw", "irem" -> "hw"),
        memConfig = JopMemoryConfig(l2SetCount = 64)),
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart,
        devicePart = Some(wukongConsolePart), params = Map("baudRate" -> 1000000))))))

  /** Just the UART entry from a system's device map, Ethernet/SD dropped.
    *
    * Rebuilding the map as `Map("uart" -> DeviceInstance(Uart, CH340N))` looks
    * equivalent and is not: it silently discards the PARAMS on the entry it
    * replaces. `wukongFull` sets `baudRate -> 1000000` there, deliberately --
    * the on-board CH340N drops characters at 2 Mbaud, and a GcPauseTest run
    * came back with "generatlona disabled" and hSize=6696246 for a real
    * 66562496, i.e. text still readable while numbers were silently wrong.
    * Every preset that rebuilt the map inherited the 2 Mbaud default instead
    * and lost that protection without saying so. Filter, do not rebuild. */
  private def uartOnly(sys: JopSystem): Map[String, DeviceInstance] =
    sys.devices.filter { case (k, _) => k == "uart" }

  /** Wukong DDR3 — all compute units, UART only (no Ethernet/SD) */
  def wukongDdr3AllCu = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(
      devices = uartOnly(base.system))))
  }

  /** Wukong DDR3 — full featured SMP (with Ethernet + SD) */
  def wukongFullSmp(n: Int) = {
    val base = wukongFull
    base.copy(systems = Seq(base.system.copy(name = s"smp$n", cpuCnt = n)))
  }

  /** Wukong DDR3 — SMP with all compute units, no Ethernet/SD (saves LUTs) */
  /** Wukong DDR3 SMP, N cores — 32 KB/64-block method cache, doubles in Java.
    *
    * BOTH DEPARTURES FROM `wukongFull` ARE REQUIRED TO BUILD AT FOUR CORES, and
    * neither is a preference. At the plain defaults this preset was 62,583 LUTs
    * (98.7 %) and failed slice packing in Vivado's placer with no elaboration-
    * time warning at all — item 53, a regression that sat undetected because
    * nobody rebuilt SMP after the method cache default changed.
    *
    * `double -> java` is the one that frees the space. Compute units are PER
    * CORE, so at four cores the DCU is ~19,800 LUTs against ~3,400 to take the
    * method cache from 16 to 64 blocks: the cache never competed with the core
    * count, it competed with the CUs. This is the DEFAULT implementation for
    * doubles anyway (`BytecodeConfig` has no double microcode at all — item 20,
    * and `bc=double:mc` is refused); only `wukongFull`'s `"*" -> "hw"` promoted
    * it. DoAll's DoubleArith/DoubleField/MathTest/BigMathTest all pass.
    *
    * `15/6` is 32 KB in 64 blocks of 512 B. Depth is free — measured -88 LUTs
    * against `13/6` — and 512 B is where the sweep shows depth saturating, so
    * the block size sits at the saturation point and the count carries the LUT
    * cost. See docs/architecture/tuning-guide.md.
    *
    * Hardware-validated 4-core, 2026-08-23: 43,399 LUTs (68.5 %), 59.5 BRAM
    * (44.1 %), WNS +0.088 ns, DoAll 66/66 over the Pico console.
    *
    * APPLIED AT EVERY CORE COUNT, not just where it is forced. One and two
    * cores would fit the DCU, but an SMP preset exists to measure SCALING, and
    * an arm that changes configuration with N measures the configuration change
    * as well as the cores — the confound that once made two halves of a dual
    * build differ in core count. Uniform beats optimal here.
    *
    * NOT YET CHECKED ABOVE FOUR CORES: 59.5 BRAM at 4 implies ~119 of 135 at 8,
    * before the L2 gets anything, so this geometry probably has to step down
    * with core count. Do not raise the core count without re-reading the fit.
    */
  def wukongSmp(n: Int) = {
    val base = wukongFull
    val cc = base.system.coreConfig
    base.copy(systems = Seq(base.system.copy(
      name = s"smp$n",
      cpuCnt = n,
      coreConfig = cc.copy(
        jpcWidth = 15,
        blockBits = 6,
        bytecodes = cc.bytecodes ++ Map("double" -> "java")),
      devices = uartOnly(base.system))))
  }

  /**
   * Wukong DDR3 — SMP, N cores, for validating the generational collector on a
   * DDR3 board (current-status item 1 lists DDR3 above 2 cores as asserted but
   * not verified).
   *
   * Derived from `wukongDdr3` rather than `wukongSmpMinimal`, which looks like
   * the obvious base and is the wrong one twice over: it replaces coreConfig
   * with a bare `JopCoreConfig()`, so `hasCardTable` goes false and `GC.init`
   * falls back to the CLASSIC collector — a generational test on it measures
   * nothing at all. `wukongDdr3` keeps the 16 KB card table.
   *
   * THE MIG PROFILE PICKS THE CLOCK, and clkFreq follows from it. The DDR3 path
   * has no `systemClk` from the clk_wiz at all (see `Board.scala`), and
   * `JopTop` clocks the cluster from `ddr3Mig.io.ui_clk` = memory clock / 4.
   * Stock is `TimePeriod 2500` ps -> 400 MHz -> 100 MHz. Lowering it for more
   * cores means regenerating the MIG AND the clk_wiz together, because MIG also
   * dictates its own sys_clk input for a given memory period:
   *
   *     TimePeriod 2727 ps -> MIG wants 97.787 MHz in -> 366.6 MHz -> ui_clk 91.65
   *
   * Getting clkHz wrong does not fail the build -- it mis-sets the microsecond
   * prescaler and the UART divider, and the board goes quiet.
   *
   * It is also ICU-only (`idiv`/`irem` hw, everything else microcode), which
   * keeps the Vivado build tractable at 4 cores and costs nothing here:
   * SmpGcTest is integer-only. Do not run DoAll on this expecting 66/66 without
   * checking the bytecode map first — see item 17.
   */
  def wukongDdr3Smp(n: Int, mig: MigProfile = MigProfile.Ddr3_400) = {
    val base = wukongDdr3
    // clkFreq is DERIVED from the profile, never passed alongside it. The two
    // must agree -- ui_clk is what the cluster actually runs at, and clkFreq is
    // what the microsecond prescaler and UART divider are computed from -- so
    // there is deliberately only one knob.
    base.copy(
      migProfile = Some(mig),
      systems = Seq(base.system.copy(
        name = s"ddr3smp$n", cpuCnt = n, clkFreq = HertzNumber(mig.uiClkHz))))
  }

  /** Wukong DDR3 SMP with a NON-BLOCKING L2 — `mshr` misses in flight.
    *
    * The DDR3 counterpart of `ae115fbDdr2SmpMshr`, and the same experiment: the
    * only difference from `wukongDdr3Smp(n, mig)` is `l2MshrCount`, so running
    * `JbeScale` on the pair isolates exactly that. DDR2 went 682 -> 1613 kacc/s
    * at eight cores on the equivalent A/B.
    *
    * DDR3 needed a second change before this could pay off at all:
    * `CacheToMigAdapter` used to serialise reads (ISSUE_READ -> WAIT_READ), so
    * the cache would offer concurrency the adapter refused. Both halves have to
    * be in place, which is why this preset arrived later than the DDR2 one.
    *
    * Derived from `wukongDdr3Smp`, NOT `wukongSmp`: only the former derives
    * clkFreq from the MigProfile, and the DDR3 path has no system PLL — the
    * cluster runs at ui_clk, so a preset that declares its own frequency simply
    * goes quiet on the board.
    */
  def wukongDdr3SmpMshr(n: Int, mshr: Int = 4, mig: MigProfile = MigProfile.Ddr3_400) = {
    val base = wukongDdr3Smp(n, mig)
    val sys = base.system
    base.copy(systems = Seq(sys.copy(
      name = s"ddr3smp${n}mshr$mshr",
      coreConfig = sys.coreConfig.copy(
        memConfig = sys.coreConfig.memConfig.copy(l2MshrCount = mshr)))))
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
      // 2 Mbaud since 2026-08-18, and this board is WHY the baud generator was
      // made fractional. It used to read: "at 75 MHz a 2 Mbaud divider is
      // 7.5 -> 7, i.e. 2.143 Mbaud: +7% and far outside UART tolerance. 75 MHz
      // divides EXACTLY into 1 M (divider 15), 1.5 M (10) and 3 M (5) — 2 M is
      // the one standard rate it cannot reach." That was true of an INTEGER
      // divider. jop.io.UartBaudTick accumulates a fractional phase instead, so
      // 75 MHz reaches 2 Mbaud to within 0.0006 % and the restriction is gone.
      // Brought up at 115200, then 1 M (4.0 s -> 0.5 s for a 44 KB download),
      // now 2 M. Hosts must match: Makefile UART_BAUD and run_bench both say
      // 2000000, but a bitstream older than that date still needs 1000000.
      devices = Map("uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340"),
        params = Map("baudRate" -> 2000000))))))

  /** A-E115FB EP4CE115 + 1 GB DDR2 — SMP.
    *
    * The third memory architecture in the scaling study, and the one that
    * separates two explanations that the SDR-vs-DDR3 comparison confounds. The
    * DDR2 path shares `LruCacheCore` with DDR3 but replaces the whole backend
    * (`CacheToDdr2Adapter` + Altera half-rate ALTMEMPHY, 256-bit local
    * interface) where DDR3 uses `CacheToMigAdapter` + MIG. So if DDR2 also
    * stalls at ~1.75x on eight cores, the limit is the shared cache; if it
    * scales like SDR, the limit is specific to the MIG adapter.
    *
    * Scaling ratios are the comparable quantity here, not absolute rates: this
    * is a different fabric (Cyclone IV E) at a different clock (75 MHz) from
    * both Artix-7 boards, so n-core/1-core normalises away what per-MHz cannot.
    */
  def ae115fbDdr2Smp(n: Int) = {
    val base = ae115fbDdr2
    base.copy(systems = Seq(base.system.copy(name = s"ddr2smp$n", cpuCnt = n)))
  }

  /** A-E115FB DDR2 SMP with a NON-BLOCKING L2 — `mshr` misses in flight.
    *
    * The measurement vehicle for docs/architecture/nonblocking-cache-mshr-plan.md.
    * `ae115fbDdr2Smp` above is the blocking baseline that measured the 1.81x
    * eight-core ceiling; this is the same system with `l2MshrCount` raised, so
    * the pair differ in exactly the thing under test. Run `JbeScale` on both.
    *
    * Two cautions before reading a number off this. The MSHR file adds logic to
    * the cmdFifo command path, which is already where the 4- and 8-core
    * critical paths terminate, so check WNS on the first fit rather than at the
    * end. And the board cannot give a timing-clean 8-core build at any legal
    * DDR2 clock regardless — JbeScale's CHECK makes a corner-violating
    * bitstream acceptable for measurement, but not shippable.
    */
  def ae115fbDdr2SmpMshr(n: Int, mshr: Int = 4) = {
    val base = ae115fbDdr2Smp(n)
    val sys = base.system
    base.copy(systems = Seq(sys.copy(
      name = s"ddr2smp${n}mshr$mshr",
      coreConfig = sys.coreConfig.copy(
        memConfig = sys.coreConfig.memConfig.copy(l2MshrCount = mshr)))))
  }

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
        "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some(wukongConsolePart)),
        "eth" -> DeviceInstance(DeviceType.Ethernet, params = Map("gmii" -> true, "phyDataWidth" -> 8),
          devicePart = Some("RTL8211EG")),
        "sdNative" -> DeviceInstance(DeviceType.SdNative, devicePart = Some("SD_CARD"))),
      coreConfig = JopCoreConfig(memConfig = JopMemoryConfig(hasCardTable = true, cardTableBudgetBytes = 8 * 1024),
        useDspMul = true, bytecodes = Map("*" -> "hw")))))
}
