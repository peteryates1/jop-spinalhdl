package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.file.{Files, Paths}
import jop.generate.{DesignPlls, XdcGenerator}

/**
 * Xilinx designs define their PLLs, and the clk_wiz builder makes them (status
 * item 173, step 8): the Wukong and the Colorlight i9+ (8b), the XC7A100T and
 * the Alchitry Au (8c), whose flows created their clock IP by hand with
 * `make create-ip`, from scripts that knew nothing of the preset.
 */
class XilinxDesignPllTest extends AnyFunSuite {

  private def read(p: String) = new String(Files.readAllBytes(Paths.get(p)), "UTF-8")

  private def presets: Seq[(String, JopConfig)] = for {
    m <- JopConfig.getClass.getMethods.toSeq
    if m.getParameterCount == 0 && m.getReturnType == classOf[JopConfig]
    cfg = m.invoke(JopConfig).asInstanceOf[JopConfig]
    if cfg.fpgaFamily == FpgaFamily.Artix7
  } yield (m.getName, cfg)

  /** Parameterised points the flows build: the clocks a design moves to. */
  private def points: Seq[(String, JopConfig)] = Seq(
    "wukongSdramAt 80" -> JopConfig.wukongSdramAt(80),
    "wukongSdrSmp 2 75" -> JopConfig.wukongSdrSmp(2, 75),
    "wukongDdr3Smp 6 Ddr3_366" -> JopConfig.wukongDdr3Smp(6, MigProfile.Ddr3_366),
    "wukongDdr3SmpMshr 4" -> JopConfig.wukongDdr3SmpMshr(4),
    "wukongDualIndependentSmp 2 80 Ddr3_366" ->
      JopConfig.wukongDualIndependentSmp(2, 80, MigProfile.Ddr3_366),
    "colorlightDbI9PlusSdram 80" -> JopConfig.colorlightDbI9PlusSdram(80),
    "xc7a100tDbSmp 2" -> JopConfig.xc7a100tDbSmp(2))

  /** The -3.0 ns SDRAM clock lead every SDR board here runs (Wukong, i5,
    * i9+): a delay, so it holds at every clock. */
  private def sdr(mhz: BigDecimal) =
    Seq(PllOutput(PllRole.System, mhz), PllOutput(PllRole.Sdram, mhz, phasePs = -3000))

  /** What MIG takes: its input from the profile, within 1000 ppm, and the
    * 200 MHz IDELAYCTRL reference, within its +-10 MHz. */
  private def mig(p: MigProfile) =
    Seq(PllOutput(PllRole.MigSys, BigDecimal(p.migInputMhz), tolerancePpm = 1000),
        PllOutput(PllRole.MigRef, 200, tolerancePpm = 50000))

  test("no Artix-7 board declares a PLL: every design on one defines its own") {
    val artix = SystemAssembly.all.flatMap(_.boards).filter(_.fpga.exists(_.family == FpgaFamily.Artix7))
    assert(artix.map(_.name).distinct.size == 4, artix.map(_.name))
    // No board declares a PLL any more (AlteraDesignPllTest holds that).
    val names = presets.map(_._1)
    for (n <- Seq("wukongSdram", "wukongDdr3", "wukongFull", "wukongBram", "wukongSdrFull",
                  "wukongDualIndependent", "colorlightDbI9PlusEth", "xc7a100tDbSerial", "xc7a100tDbEth",
                  "xc7a100tDbMii", "xc7a100tDbFull", "auSerial", "auMinimal"))
      assert(names.contains(n), names)
    // The blackboxes that stood in for them, matched to IPs by name only.
    for (c <- Seq("jop.ddr3.ClkWizBlackBox", "jop.system.SdramExerciserClkWiz",
                  "jop.system.pll.WukongClkWizBlackBox", "jop.config.PllType$XilinxDdr3ClkWiz$"))
      assert(scala.util.Try(Class.forName(c)).isFailure, s"$c is back")
  }

  test("each design's PLL makes exactly the clocks it uses, from the board's clock") {
    // SDR: the system clock and its SDRAM copy; DDR3: MIG's two; BRAM: the
    // system clock. Plus the Ethernet transmit clock where the design has
    // one -- GMII's 125 MHz, RGMII's 50 (jop.io.Rgmii100) -- and the 25 MHz
    // pixel clock where it has VGA, and no unused output, which constrains
    // the shared VCO for nothing (status item 164).
    for ((name, cfg) <- presets ++ points; s <- cfg.systems) {
      val mem = cfg.resolveMemory(s).map(_.memType)
      val eth =
        if (s.ethGmii) Seq(PllOutput(PllRole.Eth, 125))
        else if (s.ethRgmii) Seq(PllOutput(PllRole.Eth, jop.io.Rgmii100.TxClockMhz))
        else Nil
      val vga = if (s.hasDevice(DeviceType.VgaText)) Seq(PllOutput(PllRole.Vga, 25)) else Nil
      val mhz = s.clkFreq.toBigDecimal / 1000000
      val want = mem match {
        case Some(MemoryType.SDRAM_SDR)  => sdr(mhz) ++ eth ++ vga
        case Some(MemoryType.SDRAM_DDR3) => mig(cfg.migProfile.getOrElse(MigProfile.Ddr3_400)) ++ eth ++ vga
        case _                           => Seq(PllOutput(PllRole.System, mhz)) ++ eth ++ vga
      }
      assert(s.plls.size == 1, s"$name/${s.name}: ${s.plls}")
      assert(s.plls.head.outputs == want, s"$name/${s.name}: ${s.plls.head.outputs}")
      assert(s.plls.head.reference == cfg.inputClock.name, name)
    }
  }

  test("a MIG design's PLL feeds MIG the input its profile demands") {
    // MIG dictates its sys_clk from a table and silently retunes a mismatch
    // (MigProfile): a PLL built for another profile than the preset's is
    // refused when the preset is built, not found on the board.
    val base = JopConfig.wukongDdr3
    val e = intercept[IllegalArgumentException](base.copy(migProfile = Some(MigProfile.Ddr3_366)))
    assert(e.getMessage.contains("97.787") && e.getMessage.contains("100"), e.getMessage)
  }

  test("the dual design's two systems each define their PLL, under their own names") {
    for (cfg <- Seq(JopConfig.wukongDualIndependent, JopConfig.wukongDual)) {
      val ps = cfg.systems.flatMap(_.plls)
      assert(ps.map(_.module).distinct.size == 2 && ps.map(_.instance).distinct.size == 2, ps)
    }
    // A clash across systems is refused like one within a system.
    val base = JopConfig.wukongDualIndependent
    val clash = base.systems(1).plls.map(_.copy(module = base.systems(0).plls.head.module))
    assertThrows[IllegalArgumentException](
      base.copy(systems = Seq(base.systems(0), base.systems(1).copy(plls = clash))))
  }

  test("the dual design generates: its two systems share one microcode image") {
    // JopTopVerilog read the microcode's boot mode from `config.system`, which
    // refuses a two-system design, so `make dual-generate` failed from
    // 2026-08-26 until this step's first dual build found it. Both clusters
    // take the one ROM and RAM image the top is given.
    val dual = JopConfig.wukongDualIndependent
    assert(jop.system.JopTopVerilog.bootModeOf(dual) == BootMode.Serial)
    val mixed = dual.copy(systems = Seq(dual.systems(0), dual.systems(1).copy(bootMode = BootMode.Flash)))
    assertThrows[IllegalArgumentException](jop.system.JopTopVerilog.bootModeOf(mixed))
  }

  test("the generated XDC groups the GMII transmit clock by the name Vivado derives from the design's PLL") {
    for ((cfg, clk) <- Seq(JopConfig.wukongFull -> "clk_eth_ddr3_clk", JopConfig.wukongSdrFull -> "clk_eth_sdr_clk",
                           JopConfig.xc7a100tDbEth -> "clk_eth_ddr3_clk")) {
      val xdc = XdcGenerator.generate(cfg)
      assert(xdc.contains(s"set_clock_groups -asynchronous -group [get_clocks $clk]"), xdc)
      assert(!xdc.contains("clk_125"), "the clock is named for its role now")
    }
    // With no Ethernet output to name, the group would match nothing and the
    // crossings would be timed as synchronous (2.591 ns on xc7a100tDbEth,
    // status item 58): refused, not guessed.
    val base = JopConfig.xc7a100tDbEth
    val noEthClock = base.copy(systems = Seq(base.system.copy(
      plls = base.system.plls.map(p => p.copy(outputs = p.outputs.filterNot(_.role == PllRole.Eth))))))
    assertThrows[IllegalArgumentException](XdcGenerator.generate(noEthClock))
  }

  test("Verilog generation writes each design's clk_wiz definition into its ip/") {
    for ((name, cfg, file) <- Seq(("wukongDdr3", JopConfig.wukongDdr3, "ddr3_clk.tcl"),
                                  ("wukongSdram", JopConfig.wukongSdram, "sdr_clk.tcl"),
                                  ("colorlightDbI9PlusSdram", JopConfig.colorlightDbI9PlusSdram(), "sdr_clk.tcl"))) {
      val dir = Files.createTempDirectory("ip").toString
      assert(DesignPlls.emit(cfg, dir) == Seq(Paths.get(dir, file).toString), name)
      assert(DesignPlls.files(cfg) == Seq(file), name)
    }
    val dual = DesignPlls.files(JopConfig.wukongDualIndependent)
    assert(dual.toSet == Set("ddr3_clk.tcl", "sdr_clk.tcl"), dual)
  }

  private lazy val romData = jop.utils.JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
  private lazy val ramData = jop.utils.JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))

  /** The top's Verilog for `cfg`, and the connections of its PLL instance. */
  private def pllPorts(name: String, cfg: JopConfig): String = {
    val dir = jop.generate.BuildLayout.default.standaloneDir(s"XilinxDesignPllTest-$name")
    jop.system.JopSpinalConfig(cfg, dir).generate(spinal.lib.io.InOutWrapper(jop.system.JopTop(
      config = cfg, romInit = romData, ramInit = ramData, mainMemInit = None, mainMemSize = 64 * 1024)))
    val top = Files.list(Paths.get(dir)).toArray.map(_.toString).find(_.endsWith("Top.v"))
      .getOrElse(fail(s"no top in $dir"))
    val p = cfg.system.plls.head
    s"""(?s)\\b${p.module}\\s+${p.instance}\\s*\\((.*?)\\);""".r.findFirstMatchIn(read(top))
      .getOrElse(fail(s"no `${p.module} ${p.instance}` in $top")).group(1)
  }

  test("a DDR3 design holds its PLL in reset with the board's reset; an SDR design never resets it") {
    // As the hand-wired clock wizards did: pressing reset on a DDR3 board
    // drops the lock, which resets MIG and recalibrates.
    val ddr3 = pllPorts("ddr3", JopConfig.wukongDdr3)
    assert("""\.reset\s*\(\s*1'b0\s*\)""".r.findFirstIn(ddr3).isEmpty, ddr3)
    val sdr = pllPorts("sdr", JopConfig.wukongSdram)
    assert("""\.reset\s*\(\s*1'b0\s*\)""".r.findFirstIn(sdr).isDefined, sdr)
  }

  test("the exercisers on clk_wiz define their PLLs: the Wukong's SDRAM, the Au's DDR3") {
    assert(jop.system.SdramExerciserWukongTop.pll ==
      PllDefinition("CLK50M", sdr(100), module = "sdr_clk", instance = "clkWiz"))
    assert(jop.system.Ddr3ExerciserTop.pll ==
      PllDefinition("CLK100M", mig(MigProfile.Ddr3_400), module = "ddr3_clk", instance = "clkWiz"))
  }

  // The flows. A clk_wiz made by hand -- `make create-ip`, from a script that
  // knew nothing of the preset -- is what this step removes; every Vivado run
  // that builds a design sources the definitions generated with it.
  private val flows = Seq("fpga/qmtech-xc7a100t-wukong/Makefile", "fpga/colorlight-db-i9plus/Makefile",
    "fpga/qmtech-xc7a100t-dbfpga-v5/Makefile", "fpga/alchitry-au/Makefile", "fpga/alchitry-au-ddr3-test/Makefile")

  /** Builds with no PLL: the DB_FPGA V5's bring-up jigs, and the Au's flash
    * programmer, which runs on the board's 100 MHz clock. */
  private val noPll = Seq("JOP_TOP=UartEchoTop", "JOP_TOP=UartLoopback", "JOP_TOP=UartTxGen",
                          "JOP_TOP=FlashProgrammerDdr3Top")

  /** A Makefile's logical lines: backslash continuations joined. */
  private def logical(path: String): Seq[String] =
    read(path).replace("\\\n", " ").linesIterator.toSeq

  test("every Vivado build in the converted flows sources the design's PLL definitions") {
    for (f <- flows) {
      val runs = logical(f).filter(l => (l.contains("$(NP_TCL)") || l.contains("$(CREATE_TCL)")) &&
        !noPll.exists(l.contains))
      assert(runs.nonEmpty, s"$f runs no Vivado build")
      val missing = runs.filterNot(_.contains("JOP_PLL_TCL="))
      assert(missing.isEmpty, s"$f: a build that does not source its PLL definitions:\n${missing.mkString("\n")}")
    }
  }

  test("no converted flow creates a clk_wiz by hand or checks one afterwards") {
    // Commands, not the comments recording what they replaced.
    for (f <- flows; l <- logical(f) if !l.trim.startsWith("#"))
      assert(!l.matches(""".*(create_\w*clk_wiz|check_clk_ip|sdr_clk\.xci|ddr3_clk\.xci|bram_clk\.xci).*"""),
        s"$f: $l")
    import scala.sys.process._
    val scripts = Seq("git", "ls-files", "fpga").lineStream_!.filter(_.matches(""".*create_\w*clk_wiz\.tcl"""))
    assert(scripts.isEmpty, s"clk_wiz made by hand again: ${scripts.mkString(", ")}")
    assert(!Files.exists(Paths.get("fpga/scripts/check_clk_ip.py")), "check_clk_ip.py is back")
  }

  test("the hand-written constraints name the clocks the designs' PLLs make") {
    // Vivado names a clock it derives from a clk_wiz output <port>_<module>.
    // A constraint naming one the design does not make matches nothing, and
    // Vivado only warns.
    val old = """clk_(100|125|200)(_shift)?_(sdr|ddr3|bram)_clk""".r
    for (f <- Seq("fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_sdr_cdc.xdc",
                  "fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_ddr3.xdc",
                  "fpga/qmtech-xc7a100t-wukong/vivado/constraints/wukong_dual_post_synth.tcl",
                  "fpga/colorlight-db-i9plus/constraints/sdr_cdc.xdc"))
      assert(old.findFirstIn(read(f)).isEmpty, s"$f still names ${old.findFirstIn(read(f)).getOrElse("")}")
  }
}
