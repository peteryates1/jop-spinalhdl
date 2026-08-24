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
 * NOTE ON THE SIMULATION LAYOUT. Simulation writes to the tree ROOT while the
 * other modes get a subdirectory, so `dir` is not simply root + dirName. That
 * asymmetry is historical, and it is the reason `generatedDir` needed a special
 * case in the first place; it is preserved here so this change moves nothing,
 * and it is the thing to make uniform when the tree moves.
 */
object MicrocodePaths {

  /** Root of the generated microcode tree, relative to the repository root. */
  val root: String = "asm/generated"

  /** Directory holding one boot mode's ROM/RAM/MIF outputs. */
  def dir(mode: BootMode): String = mode match {
    case BootMode.Simulation => root
    case other               => s"$root/${other.dirName}"
  }

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
