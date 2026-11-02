package jop.config

/** What a clock output is FOR. Named by role, not by the vendor's port name --
  * `c1` on Altera, `clkout0` on Lattice and `clk_100` on Xilinx are all the
  * system clock, and a design should ask for the role. */
sealed trait PllRole
object PllRole {
  /** The clock the design's logic runs on. */
  case object System extends PllRole
  /** SDR SDRAM clock: the system clock, phase-shifted to meet the chip's
    * setup/hold against the controller's outputs. */
  case object Sdram extends PllRole
  case object Eth extends PllRole
  case object Vga extends PllRole
  /** MIG reference clocks. The DDR3 system clock is MIG's ui_clk, NOT a PLL
    * output, which is why boards using them declare no System output. */
  case object MigSys extends PllRole
  case object MigRef extends PllRole
}

/**
 * One PLL output: what it is for, how fast, at what phase, and how exactly.
 *
 * A phase is an angle or a delay, and they are different statements. An angle
 * stays the same fraction of a cycle when the frequency changes; a delay stays
 * the same time. The i5's SDRAM clock is 315 degrees. The EP4CGX150's is
 * -3000 ps at 80, 60, 50 and 36 MHz alike, deliberately: a sweep found the
 * delay makes no measurable difference there, so it was kept rather than
 * re-derived per clock (DramPllGen, 2026-08). Each states the one it means.
 *
 * The frequency is in megahertz and need not be whole: the Xilinx MIG
 * dictates its input clock from a table, 97.787 MHz for a 2727 ps memory
 * period (MigProfile.Ddr3_366).
 *
 * The TOLERANCE is the design's statement of how far the clock it gets may be
 * from the one it asks for. Exact by default: a system clock off by a little
 * detunes the UART and the timers, an SDRAM clock off by a little drifts
 * through the system clock (status item 164), and GMII allows 100 ppm. Only
 * a clock whose user takes what it is given says otherwise -- MIG's input,
 * which one VCO cannot make exactly beside its 200 MHz reference. A builder
 * whose counters always make the ratio exactly (ALTPLL) meets any tolerance;
 * one that picks the nearest it can (clk_wiz) is held to it.
 *
 * @param phaseDeg     degrees, positive = later
 * @param phasePs      picoseconds, positive = later; at most one of the two
 * @param tolerancePpm how far the made frequency may be from `mhz`, in ppm
 */
case class PllOutput(role: PllRole, mhz: BigDecimal, phaseDeg: Int = 0, phasePs: Int = 0,
                     tolerancePpm: Int = 0) {
  require(mhz > 0, s"$role output: frequency must be positive, got $mhz MHz")
  require(phaseDeg == 0 || phasePs == 0,
    s"$role output: a phase in degrees ($phaseDeg) or in picoseconds ($phasePs), not both")
  require(tolerancePpm >= 0, s"$role output: a tolerance cannot be negative, got $tolerancePpm ppm")

  /** The phase as a delay at this output's frequency, in picoseconds. */
  def shiftPs: BigDecimal =
    if (phasePs != 0) BigDecimal(phasePs) else BigDecimal(phaseDeg) * 1000000 / mhz / 360

  def describe: String = s"$role $mhz MHz" + (
    if (phasePs != 0) s" @ $phasePs ps" else if (phaseDeg != 0) s" @ $phaseDeg deg" else "") + (
    if (tolerancePpm != 0) s" (within $tolerancePpm ppm)" else "")
}

/**
 * A clock generator described by what it must PRODUCE, with no vendor in it.
 *
 * WHY THIS EXISTS. There were nine PLL blackboxes in this tree for two vendor
 * primitives, because only the FREQUENCY was ever parameterised: `DramPllGen`
 * did not generate `dram_pll.vhd`, it text-patched a 441-line hand-written
 * file, so the shape -- how many outputs, at what phases -- was frozen in that
 * file and mirrored in a matching Scala blackbox. Every new shape needed a new
 * pair, and the wrappers ended up named for their first job: `DramPll`'s `c1`
 * was the SYSTEM clock, so a design with no DRAM instantiated something called
 * `DramPll` (until 2026-11-02, status item 173).
 *
 * Three bugs trace to that: each branch hand-wires its own reset, and the
 * Wukong's no-DRAM branch ties a port called `resetn` low -- deasserted only
 * because that IP's reset is active HIGH under that name (the SDR IP's is
 * active low), so the port's name says the opposite of what it does; a `set_clock_groups`
 * was discarded because the netlist said `dramPll` where the constraint said
 * `pll`, and a generated config under `build/` still reaches into `fpga/` for
 * the template.
 *
 * A spec is checkable without the tool that built the IP -- see
 * PllSpecConsistencyTest -- which is what lets CI verify the closed-toolchain
 * arms it cannot run.
 */
case class PllSpec(inputMhz: Int, outputs: Seq[PllOutput]) {
  require(inputMhz > 0, s"input clock must be positive, got $inputMhz")
  require(outputs.nonEmpty, "a PLL with no outputs is not a PLL")

  def of(role: PllRole): Option[PllOutput] = outputs.find(_.role == role)
  def systemMhz: Option[BigDecimal] = of(PllRole.System).map(_.mhz)
}

/**
 * A design's PLL (status item 173): the board clock it takes, what it must
 * produce, and the names its IP and its netlist instance get. The DESIGN owns
 * this -- which clocks JOP or an exerciser runs on is the design's choice, not
 * the board's -- and the FPGA family builds it (`FpgaFamily.pll`), the same
 * mechanism for every design on that family.
 *
 * @param reference a clock the design's assembly offers, by name
 * @param outputs   what it must produce, in the order the IP numbers them
 * @param module    the IP's module name: the file the flow generates
 *                  (`PllGenMain`) and synthesises
 * @param instance  the netlist instance name, STATED so that constraints can
 *                  name it (`PllType.instanceName` says why not inferred)
 */
case class PllDefinition(reference: String, outputs: Seq[PllOutput], module: String, instance: String) {
  require(outputs.nonEmpty, s"PLL $module: a PLL with no outputs is not a PLL")

  /** The spec the family builds, fed from `clock`, which must be the reference. */
  def spec(clock: BoardClock): PllSpec = {
    require(clock.name == reference, s"PLL $module takes $reference, not ${clock.name}")
    val hz = clock.freq.toBigDecimal
    require(hz % 1000000 == 0,
      s"PLL $module: clock ${clock.name} is ${hz / 1000000} MHz, and a PllSpec takes whole megahertz")
    PllSpec((hz / 1000000).toIntExact, outputs)
  }

  /** The spec on `assembly`, which must offer the reference clock. */
  def spec(assembly: SystemAssembly): PllSpec = spec(assembly.clock(reference))

  /** This PLL with its system clock at `mhz`: the System output and the
    * SDRAM's copy of it move, keeping their phases; every other output stays.
    * For a design derived from another at a new clock (`JopSystem.atClock`). */
  def atSystemClock(mhz: Int): PllDefinition = copy(outputs = outputs.map(o =>
    if (o.role == PllRole.System || o.role == PllRole.Sdram) o.copy(mhz = mhz) else o))
}
