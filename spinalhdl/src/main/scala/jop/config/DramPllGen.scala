package jop.config

import java.nio.file.{Files, Paths, StandardCopyOption}

/**
 * Derives the EP4CGX150 DRAM PLL from the preset instead of leaving it hardwired.
 *
 * THE PROBLEM THIS REMOVES. `dram_pll.vhd` carries the real system frequency in
 * four hand-edited numbers, while the preset's `clkFreq` only feeds the
 * microsecond prescaler and the UART divider. Nothing cross-checked them, so
 * every new core count meant editing the VHDL by hand and remembering to pass a
 * matching `clkMhz` — and getting it wrong produces `FPGA not responding (no
 * ready signal)`, which looks exactly like a dead build. That has now cost time
 * on the 4-core (60 MHz), 8-core (50 MHz) and 12-core (36 MHz) bring-ups.
 *
 * The preset is now the single source of truth: the PLL ratio is computed from
 * `clkFreq` and emitted, and the UART baud the board will ACTUALLY use is
 * computed and reported alongside it.
 */
object DramPllGen {

  /** Board input clock. Fixed by the crystal, and asserted against the template. */
  val inputMhz: Int = 50

  /**
   * SDRAM clock phase, picosecond, negative = earlier than the system clock.
   *
   * -3000 is inherited from when this design ran at 80 MHz, where it was -86
   * degrees; at 60 MHz the same picoseconds are -65 degrees and at 36 MHz they
   * are -39. Left as a fixed delay rather than a fixed angle because a
   * deliberate sweep found it makes NO measurable difference: rebuilding the
   * 4-core design at -4000 ps (fitted as -85 degrees) left a run bit-identical,
   * same fault counts and same cycle counters. Exposed so it can be swept again
   * if a board ever needs it, not because it is known to matter.
   */
  val sdramPhasePs: Int = -3000

  /** Greatest common divisor, for reducing the frequency ratio. */
  private def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /**
   * The altpll multiply/divide pair for `targetMhz` from a 50 MHz input.
   * Returns the reduced fraction, which is what Quartus reports back in
   * "Implementing clock multiplication of M, clock division of D".
   */
  def ratioFor(targetMhz: Int): (Int, Int) = {
    require(targetMhz > 0, s"target clock must be positive, got $targetMhz")
    val g = gcd(targetMhz, inputMhz)
    (targetMhz / g, inputMhz / g)
  }

  /**
   * The baud rate the hardware will actually produce.
   *
   * `UartCtrl.setClockDivider` computes
   * `clockDivider = round(clkFreq / baud / rxSamplePerBit) - 1` with
   * `rxSamplePerBit = 5`, so the achievable rates are `clkFreq / (n * 5)`. An
   * exact 2 Mbaud therefore needs a clock that is a MULTIPLE OF 10 MHz — 80, 60
   * and 50 all are, which is why this went unnoticed until a 36 MHz build asked
   * for 2 Mbaud and got 1.8.
   */
  def effectiveBaud(clkMhz: Int, requestedBaud: Int, rxSamplePerBit: Int = 5): Int = {
    // Delegates to the SAME generator the RTL uses. This computed an integer
    // divider until 2026-08-24 -- `round(clkFreq / baud / 5)` -- which the RTL
    // stopped using on 2026-08-18 when 28d8d06 replaced the divider with a
    // fractional accumulator. For four days it reported a baud the hardware
    // does not transmit: at 36 MHz it said 1.8 Mbaud and printed a BAUD WARNING
    // telling the user to download at 1800000, where the board actually runs an
    // exact 2 Mbaud. Following that warning looks exactly like a dead board.
    jop.io.UartBaudTick
      .actualRate(spinal.core.HertzNumber(BigDecimal(clkMhz) * 1000000), requestedBaud, rxSamplePerBit)
      .setScale(0, BigDecimal.RoundingMode.HALF_UP).toInt
  }

  /** True when the requested baud is exactly representable at this clock. */
  def baudIsExact(clkMhz: Int, requestedBaud: Int): Boolean =
    effectiveBaud(clkMhz, requestedBaud) == requestedBaud

  /**
   * Emit a `dram_pll.vhd` for `targetMhz` next to the board project.
   *
   * The checked-in file is used as the TEMPLATE and only the five parameter
   * lines are substituted, so the megafunction boilerplate — which is generated
   * by Quartus and has no business being retyped — stays byte-identical to the
   * version that is known to work on silicon.
   *
   * Returns a one-line description for the build summary.
   */
  /** The altpll template, as a GENERATOR RESOURCE rather than a board file.
    *
    * It used to live at fpga/qmtech-ep4cgx150-sdram/dram_pll.vhd, where it
    * looked exactly like the build products around it -- so it was deleted as
    * "superseded by the generator", which broke every EP4CGX150 build, because
    * the generator is its CONSUMER. Nine call sites all passed that same path,
    * which is the other half of the evidence that it was never a per-board
    * thing.
    *
    * Kept as a template rather than synthesised in Scala on purpose: it is 441
    * lines of Quartus megafunction output with 70 generics, and emitting that
    * from string literals would be the same bytes in a less readable form,
    * with a transcription error in any one generic silently mis-clocking the
    * design. What varies with the configuration is five values, and those ARE
    * generated.
    */
  private val templateResource = "/jop/config/dram_pll.template.vhd"

  private def readTemplate(): Option[String] =
    Option(getClass.getResourceAsStream(templateResource)).map { in =>
      try new String(in.readAllBytes(), "UTF-8") finally in.close()
    }

  def emit(targetMhz: Int, configDir: String): String = {
    // PER CONFIGURATION, not per board directory and not per ENTITY either.
    // Keying on entityName was the first fix and was still wrong: that name
    // does not encode the core count, so ep4cgx150Smp 2 and ep4cgx150Smp 12
    // are both JopSmpSdramTop and would still share one PLL. See BuildLayout. Every project in
    // fpga/qmtech-ep4cgx150-sdram used to share generated/dram_pll.vhd, so
    // generating for one preset silently reclocked the others: building
    // ep4cgx150Serial (80 MHz) after generating ep4cgx150Smp left the 36 MHz
    // PLL in place, and the single-core build then ran at 36 MHz while every
    // report said 80. Timing "passed" against the wrong clock.
    //
    // The PLL is a property of one configuration, so it belongs in that
    // configuration's own directory. What is common lives in JopConfig.
    val outDir   = Paths.get(configDir, "ip")
    val out      = outDir.resolve("dram_pll.vhd")
    val loaded   = readTemplate()
    if (loaded.isEmpty)
      return s"PLL:         template $templateResource not on the classpath — not generated"

    val (mul, div) = ratioFor(targetMhz)
    var text = loaded.get

    // Assert the template still describes a 50 MHz input before trusting it.
    val inPs = 1000000 / inputMhz   // 20000 ps for 50 MHz
    require(text.contains(s"inclk0_input_frequency => $inPs,"),
      s"dram_pll.vhd template does not declare a ${inputMhz} MHz input " +
      s"(expected inclk0_input_frequency => $inPs)")

    def sub(key: String, value: String): Unit = {
      val re = s"""$key => [^,]+,"""
      val hits = re.r.findAllIn(text).size
      require(hits == 1, s"expected exactly one '$key =>' in the template, found $hits")
      text = re.r.replaceAllIn(text, s"$key => $value,")
    }
    sub("clk1_multiply_by", mul.toString)
    sub("clk1_divide_by",   div.toString)
    sub("clk2_multiply_by", mul.toString)
    sub("clk2_divide_by",   div.toString)
    sub("clk2_phase_shift", "\"" + sdramPhasePs + "\"")

    val header =
      s"""|-- GENERATED FROM THE PRESET — DO NOT EDIT.
          |--
          |-- Written by jop.config.DramPllGen at Verilog generation time from the
          |-- system clkFreq, so the PLL and the preset cannot disagree. To change the
          |-- frequency, change the preset (e.g. `ep4cgx150Smp <cores> <mhz>`), not
          |-- this file — anything edited here is overwritten on the next generate.
          |--
          |-- System clock: $targetMhz MHz  (${inputMhz} MHz in, multiply $mul, divide $div)
          |-- SDRAM clock:  $targetMhz MHz, $sdramPhasePs ps phase
          |--
          |-- Template: jop/config/dram_pll.template.vhd (a generator resource)
          |-- projects in this directory that are not driven by a JOP preset.
          |""".stripMargin

    Files.createDirectories(outDir)
    Files.write(out, (header + text).getBytes("UTF-8"))
    s"PLL:         $targetMhz MHz (x$mul /$div from ${inputMhz} MHz) -> ${out.toString}"
  }
}
