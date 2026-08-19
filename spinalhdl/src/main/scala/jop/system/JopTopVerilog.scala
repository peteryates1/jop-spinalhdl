package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.config._
import jop.utils.JopFileLoader

/**
 * Unified Verilog generation entry point.
 *
 * Resolves a preset name to a JopConfig, loads ROM/RAM, and generates Verilog
 * via JopTop. Entity name is automatically set from JopConfig.entityName.
 *
 * Usage:
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Bram"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150BramSerial"
 *   sbt "runMain jop.system.JopTopVerilog cyc5000Serial"
 *   sbt "runMain jop.system.JopTopVerilog auSerial"
 *   sbt "runMain jop.system.JopTopVerilog wukongSdram"
 *   sbt "runMain jop.system.JopTopVerilog wukongDdr3"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 8"
 */
object JopTopVerilog {

  /** Resolve a preset name to a JopConfig, INCLUDING any argument overrides.
    *
    * The overrides belong here, not in `main`. XdcGenerator and QsfGenerator
    * call resolvePreset themselves, so an override applied afterwards in main
    * reached the Verilog and nothing else -- the generated design drove one set
    * of pins while the constraints named another. That is exactly how a UART
    * ends up wired to a header nobody connected. */
  def resolvePreset(name: String, args: Array[String] = Array.empty): JopConfig = {
    val base = resolveBase(name, args)
    val withPerf = if (args.exists(_.equalsIgnoreCase("perf"))) PerfCountersOverride(base) else base
    args.find(_.toLowerCase.startsWith("uart="))
      .map(a => UartPartOverride(withPerf, a.substring(5)))
      .getOrElse(withPerf)
  }

  private def resolveBase(name: String, args: Array[String]): JopConfig = name match {
    case "ep4cgx150Serial"     => JopConfig.ep4cgx150Serial
    case "ep4cgx150Bram"       => JopConfig.ep4cgx150Bram
    case "ep4cgx150BramGc"     => JopConfig.ep4cgx150BramGc
    case "ep4cgx150BramSerial" => JopConfig.ep4cgx150BramSerial
    case "ep4cgx150HwMath"     => JopConfig.ep4cgx150HwMath
    case "ep4cgx150McFallback" => JopConfig.ep4cgx150McFallback
    case "ep4cgx150HwFloat"    => JopConfig.ep4cgx150HwFloat
    // ep4cgx150Smp <cores> [clkMhz]. clkMhz drives the generated PLL — see the
    // note on JopConfig.ep4cgx150Smp. 4 cores needs 65; 1-2 run at 80.
    // ep4cgx150BramSmp <cores> [clkMhz] — the sim/hardware bridge, see the note
    // on JopConfig.ep4cgx150BramSmp. Defaults to 60 MHz to match dram_pll.vhd.
    case "ep4cgx150BramSmp" =>
      val n   = args.drop(1).headOption.map(_.toInt).getOrElse(4)
      val mhz = args.drop(2).headOption.map(_.toInt).getOrElse(60)
      JopConfig.ep4cgx150BramSmp(n, mhz)
    case "ep4cgx150NoCache" => JopConfig.ep4cgx150NoCache
    case "ep4cgx150Smp" =>
      val n   = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      val mhz = args.drop(2).headOption.map(_.toInt).getOrElse(80)
      val cs  = args.drop(3).headOption.exists(_ == "cmpsync")
      JopConfig.ep4cgx150Smp(n, mhz, cs)
    case "cyc5000Serial"    => JopConfig.cyc5000Serial
    case "cyc5000Smp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.cyc5000Smp(n)
    case "auSerial"         => JopConfig.auSerial
    case "wukongSdram"      => JopConfig.wukongSdram
    case "wukongSdrAllCu"   => JopConfig.wukongSdrAllCu
    case "wukongDdr3"       => JopConfig.wukongDdr3
    case "wukongDdr3AllCu"  => JopConfig.wukongDdr3AllCu
    case "wukongBram"       => JopConfig.wukongBram
    case "wukongFull"       => JopConfig.wukongFull
    case "wukongSdrFull"    => JopConfig.wukongSdrFull
    case "wukongFullSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongFullSmp(n)
    case "wukongSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongSmp(n)
    case "wukongSdrSmp" =>
      val n = if (args.length > 1) args(1).toInt else 4
      val mhz = args.drop(2).headOption.map(_.toInt).getOrElse(100)
      JopConfig.wukongSdrSmp(n, mhz)
    case "wukongDdr3Smp" =>
      val n = if (args.length > 1) args(1).toInt else 4
      // argv[2] names a MigProfile, not a frequency: the clock is one of a few
      // measured points, and clkFreq is derived from whichever is picked.
      val mig = args.drop(2).headOption.map { name =>
        MigProfile.all.find(_.name.equalsIgnoreCase(name)).getOrElse(
          throw new IllegalArgumentException(
            s"unknown MIG profile '$name'; known: " +
            MigProfile.all.map(_.name).mkString(", ")))
      }.getOrElse(MigProfile.Ddr3_400)
      JopConfig.wukongDdr3Smp(n, mig)
    // <cores> [mshrs] [migProfile], e.g. "wukongDdr3SmpMshr 4 4"
    case "wukongDdr3SmpMshr" =>
      val n = if (args.length > 1) args(1).toInt else 4
      val k = args.drop(2).headOption.map(_.toInt).getOrElse(4)
      val mig = args.drop(3).headOption.map { name =>
        MigProfile.all.find(_.name.equalsIgnoreCase(name)).getOrElse(
          throw new IllegalArgumentException(
            s"unknown MIG profile '$name'; known: " +
            MigProfile.all.map(_.name).mkString(", ")))
      }.getOrElse(MigProfile.Ddr3_400)
      JopConfig.wukongDdr3SmpMshr(n, k, mig)
    case "wukongSmpMinimal" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongSmpMinimal(n)
    case "wukongNoDcu"      => JopConfig.wukongNoDcu
    case "wukongDdr3DspMul" => JopConfig.wukongDdr3DspMul
    case "wukongDdr3Fcu"    => JopConfig.wukongDdr3Fcu
    case "wukongDdr3Lcu"    => JopConfig.wukongDdr3Lcu
    case "ae115fbDdr2" => JopConfig.ae115fbDdr2
    case "ae115fbDdr2Smp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.ae115fbDdr2Smp(n)
    // <cores> <mshrs>, e.g. "ae115fbDdr2SmpMshr 8 4"
    case "ae115fbDdr2SmpMshr" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      val k = args.drop(2).headOption.map(_.toInt).getOrElse(4)
      JopConfig.ae115fbDdr2SmpMshr(n, k)
    case "xc7a100tDbSerial" => JopConfig.xc7a100tDbSerial
    case "xc7a100tDbFull"   => JopConfig.xc7a100tDbFull
    case "xc7a100tDbSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.xc7a100tDbSmp(n)
    // These defaults must match sdrClkMhz in JopConfig AND CLKOUT1/CLKOUT2 in
    // create_sdram_clk_wiz_1.tcl. Nothing cross-checks the three, and a
    // mismatch is silent: the IP generates one frequency while the design is
    // constrained for another.
    case "wukongDualIndependent" =>
      val mhz = args.drop(1).headOption.map(_.toInt).getOrElse(100)
      JopConfig.wukongDualIndependentSmp(sdrClkMhz = mhz)
    case "wukongDualSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      val mhz = args.drop(2).headOption.map(_.toInt).getOrElse(100)
      JopConfig.wukongDualIndependentSmp(n, sdrClkMhz = mhz)
    case "minimum"          => JopConfig.minimum
    case "max1000Sdram"     => JopConfig.max1000Sdram
    case "ep4ce6Sdram"      => JopConfig.ep4ce6Sdram
    case "colorlightI5Bram" => JopConfig.colorlightI5Bram
    case "colorlightI5Sdram" => JopConfig.colorlightI5Sdram
    case other =>
      throw new RuntimeException(s"Unknown preset: '$other'. Available: " +
        "ep4cgx150Serial, ep4cgx150Bram, ep4cgx150BramGc, ep4cgx150BramSerial, " +
        "ep4cgx150HwMath, ep4cgx150HwFloat, ep4cgx150Smp, ep4cgx150McFallback, " +
        "cyc5000Serial, auSerial, wukongSdram, wukongDdr3, wukongBram, " +
        "wukongFull, wukongSdrFull, wukongFullSmp, wukongSmp, " +
        "wukongDualIndependent, wukongDualSmp, " +
        "xc7a100tDbSerial, xc7a100tDbFull, xc7a100tDbSmp, ae115fbDdr2, " +
        "colorlightI5Bram, colorlightI5Sdram, " +
        "minimum, max1000Sdram, ep4ce6Sdram")
  }

  /** Build a human-readable configuration summary for a resolved JopConfig. */
  def configSummary(presetName: String, jopConfig: JopConfig): String = {
    val sb = new StringBuilder
    def line(s: String): Unit = { sb.append(s); sb.append('\n') }
    line("=== JOP Build Configuration ===")
    line(f"  Preset:        $presetName")
    line(f"  Entity:        ${jopConfig.entityName}")
    line(f"  Board:         ${jopConfig.assembly.fpgaBoard.name}")
    line(f"  FPGA:          ${jopConfig.fpga.name}")
    val systems = jopConfig.resolvedSystems
    if (systems.length > 1)
      line(f"  Topology:      ${systems.length} independent systems")
    systems.foreach { sys =>
      val p = if (systems.length > 1) s"  [${sys.name}] " else "  "
      val mhz = (sys.clkFreq.toBigDecimal / 1000000).bigDecimal.stripTrailingZeros.toPlainString
      line(f"${p}Cores:       ${sys.cpuCnt}")
      line(f"${p}Clock:       $mhz MHz")
      line(f"${p}Memory:      ${sys.memory}")
      line(f"${p}Boot mode:   ${sys.bootMode}")
      val devs = sys.effectiveDevices
      devs.values.find(_.deviceType.key == "uart").foreach { u =>
        val baud = u.params.get("baudRate").map(_.asInstanceOf[Int]).getOrElse(2000000)
        line(f"${p}UART baud:   $baud")
      }
      val bc = sys.coreConfig.bytecodes
      if (bc.nonEmpty)
        line(f"${p}HW bytecodes: ${bc.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(", ")}")
      if (sys.coreConfig.useDspMul) line(f"${p}DSP multiply: yes")
      if (sys.coreConfig.useStackCache) line(f"${p}Stack cache: on")
      val devList = devs.toSeq.sortBy(_._1).map { case (n, d) =>
        n + d.devicePart.map(pt => s" ($pt)").getOrElse("")
      }
      if (devList.nonEmpty) line(f"${p}Devices:     ${devList.mkString(", ")}")
    }
    line(f"  Generated:     ${java.time.LocalDateTime.now().withNano(0)}")
    sb.toString
  }

  /** Generate Verilog from a JopConfig */
  def generate(
    jopConfig: JopConfig,
    jopFilePath: Option[String] = None,
    presetName: String = "(custom)"
  ): Unit = {
    val sys = jopConfig.resolvedSystems.head
    val isBram = !jopConfig.resolveMemory(sys).isDefined
    val bramSize = sys.coreConfig.memConfig.mainMemSize.toInt

    val romData = JopFileLoader.loadMicrocodeRom(sys.romPath)
    val ramData = JopFileLoader.loadStackRam(sys.ramPath)

    // BRAM with pre-initialized memory (not serial boot — serial fills at runtime)
    val mainMemInit = if (isBram && sys.bootMode != BootMode.Serial) {
      val path = jopFilePath.getOrElse("java/apps/Smallest/HelloWorld.jop")
      val data = JopFileLoader.jopFileToMemoryInit(path, bramSize / 4)
      println(s"  BRAM init: $path (${data.length} words, ${bramSize / 1024}KB)")
      Some(data)
    } else None

    val summary = configSummary(presetName, jopConfig)
    val romRamLines =
      f"  ROM:           ${sys.romPath} (${romData.length} entries)%n" +
      f"  RAM:           ${sys.ramPath} (${ramData.length} entries)%n"

    // THE PLL AND THE BAUD, both derived from the preset rather than left for a
    // human to keep in step. Getting either wrong produces the SAME symptom --
    // "FPGA not responding (no ready signal)" -- which reads as a dead build,
    // so both are computed here and the baud is checked rather than assumed.
    val pllLines = if (jopConfig.assembly.fpgaBoard.name == "qmtech-ep4cgx150") {
      val mhz  = (sys.clkFreq.toBigDecimal / 1000000).toInt
      val baud = sys.effectiveDevices.values.find(_.deviceType.key == "uart")
        .flatMap(_.params.get("baudRate").map(_.asInstanceOf[Int])).getOrElse(2000000)
      val pll  = jop.config.DramPllGen.emit("fpga/qmtech-ep4cgx150-sdram", mhz)
      val eff  = jop.config.DramPllGen.effectiveBaud(mhz, baud)
      val baudLine =
        if (eff == baud) f"  Baud check:  $baud exact at $mhz MHz%n"
        else
          f"  *** BAUD WARNING: $baud is NOT achievable at $mhz MHz -- the board will%n" +
          f"  *** transmit at $eff. UartCtrl divides clkFreq by (baud x 5), so an exact%n" +
          f"  *** $baud needs a clock that is a multiple of ${baud * 5 / 1000000} MHz.%n" +
          f"  *** Download with: python3 fpga/scripts/download.py -e <app>.jop <tty> $eff%n"
      f"  $pll%n" + baudLine
    } else if (jopConfig.assembly.fpgaBoard.name.contains("wukong") && sys.memory == "ddr3") {
      // Emit the MIG + clk_wiz inputs for the preset's profile, then verify the
      // chain. The emit covers "someone forgot which numbers go together"; the
      // check covers "someone edited the tracked files by hand, or has not
      // re-run make ddr3-create-ip since changing profile".
      val dir = "fpga/qmtech-xc7a100t-wukong"
      val emitted = jopConfig.migProfile
        .map(p => f"  ${MigProfile.emit(dir, p)}%n").getOrElse("")
      emitted + f"  ${jop.config.MigClockCheck.check(dir, sys.clkFreq.toLong)}%n"
    } else ""

    print(summary)
    print(romRamLines)
    print(pllLines)

    val spinalConfig = JopSpinalConfig(jopConfig)

    spinalConfig.generate(InOutWrapper(JopTop(
      config = jopConfig,
      romInit = romData,
      ramInit = ramData,
      mainMemInit = mainMemInit,
      mainMemSize = if (isBram) bramSize else 64 * 1024
    )))

    val summaryPath = s"spinalhdl/generated/${jopConfig.entityName}.summary.txt"
    val pw = new java.io.PrintWriter(summaryPath)
    try { pw.print(summary); pw.print(romRamLines); pw.print(pllLines) } finally pw.close()

    println(s"Generated: spinalhdl/generated/${jopConfig.entityName}.v")
    println(s"Summary:   $summaryPath")
  }

  def main(args: Array[String]): Unit = {
    val preset = args.headOption.getOrElse("ep4cgx150Serial")
    // `perf` anywhere in the arguments turns on the IO_PERFCNT memory-stall
    // counters. A measurement build, not a production one -- see
    // PerfCountersOverride for why it is a switch and not four preset variants.
    val config = resolvePreset(preset, args)
    if (args.exists(_.equalsIgnoreCase("perf")))
      println("  PERF COUNTERS: enabled (IO_PERFCNT) — measurement build")
    args.find(_.toLowerCase.startsWith("uart="))
      .foreach(a => println(s"  UART part: ${a.substring(5)}"))
    val jopFile = preset match {
      case "ep4cgx150BramGc" => Some("java/apps/Small/HelloWorld.jop")
      case _ => None
    }
    generate(config, jopFilePath = jopFile, presetName = preset)
  }
}
