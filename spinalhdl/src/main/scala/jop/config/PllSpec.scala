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
 * One PLL output: what it is for, how fast, and at what phase.
 *
 * @param phaseDeg degrees, positive = later. The SDRAM output is conventionally
 *                 expressed as a negative time (-3.1 ns) on Altera and as
 *                 degrees on Lattice; degrees is the portable form because it
 *                 stays correct when the frequency changes.
 */
case class PllOutput(role: PllRole, mhz: Int, phaseDeg: Int = 0)

/**
 * A clock generator described by what it must PRODUCE, with no vendor in it.
 *
 * WHY THIS EXISTS. There were nine PLL blackboxes in this tree for two vendor
 * primitives, because only the FREQUENCY was ever parameterised: `DramPllGen`
 * does not generate `dram_pll.vhd`, it text-patches a 441-line hand-written
 * file, so the shape -- how many outputs, at what phases -- was frozen in that
 * file and mirrored in a matching Scala blackbox. Every new shape needed a new
 * pair, and the wrappers ended up named for their first job: `DramPll`'s `c1`
 * is the SYSTEM clock, so a design with no DRAM still instantiates something
 * called `DramPll`.
 *
 * Three bugs trace to that: the Wukong's no-DRAM branch ties the clock wizard's
 * reset ASSERTED (each branch hand-wires its own reset), a `set_clock_groups`
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
  def systemMhz: Option[Int] = of(PllRole.System).map(_.mhz)
}
