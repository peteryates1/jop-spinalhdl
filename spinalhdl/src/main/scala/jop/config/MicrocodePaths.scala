package jop.config

/**
 * The one place microcode output paths are written down.
 *
 * WHY THIS EXISTS. Before this, `"asm/generated/mem_rom.dat"` and its `mem_ram`
 * twin were literals in SIXTY-THREE simulation files -- 125 of the 159
 * references to the microcode tree in the repository. A location written down
 * 63 times cannot be moved, and that is precisely what the build-tree work needs
 * to do (docs/current-status.md item 60). It is also the defect class that
 * BuildLayout was created to avoid, at a larger scale.
 *
 * The variants are the BOOT MODES, and only those. `asm/Makefile` produces
 * exactly three sets of outputs -- simulation, serial and flash -- which is why
 * this takes a `BootMode` rather than a free-form string.
 *
 * NOT PER CONFIGURATION, so this does NOT live under `build/<config>/`. The
 * tree is keyed by boot mode, and its `JumpTableData.scala` siblings are compile
 * INPUTS to the SpinalHDL build -- `build.sbt` lists them as
 * `unmanagedSourceDirectories`, so a per-config location would be circular.
 *
 * Every mode gets its own directory, simulation included. It used to be written
 * to the tree root while the others got subdirectories, which is why `JopConfig`
 * needed a special case and why the two LPM simulation blackboxes in
 * `fpga/ip/altera_lpm/` referenced `asm/generated/simulation/` -- a path that
 * never existed. They were right about the layout and wrong about reality.
 */
object MicrocodePaths {

  /** Root of the generated microcode tree, relative to the repository root. */
  val root: String = "build/microcode"

  /** Directory holding one boot mode's ROM/RAM/MIF outputs. */
  def dir(mode: BootMode): String = s"$root/${mode.dirName}"

  def rom(mode: BootMode): String = s"${dir(mode)}/mem_rom.dat"
  def ram(mode: BootMode): String = s"${dir(mode)}/mem_ram.dat"
  def romMif(mode: BootMode): String = s"${dir(mode)}/rom.mif"
  def ramMif(mode: BootMode): String = s"${dir(mode)}/ram.mif"

  /** The simulation pair, named because the simulations are what use it. */
  val simulationRom: String = rom(BootMode.Simulation)
  val simulationRam: String = ram(BootMode.Simulation)

  /** The serial directory, which the FPGA flows load their microcode from. */
  val serialDir: String = dir(BootMode.Serial)
}
