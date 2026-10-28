package jop.config

/**
 * Hardware Parts — Concrete Components with Datasheet Parameters
 *
 * Parts are reusable hardware facts. A W9825G6JH6 is always the same chip —
 * its parameters don't change. Parts declare their signals but not how they're
 * wired — that's the board's job.
 */

// ==========================================================================
// FPGA
// ==========================================================================

/** FPGA manufacturer — determines synthesis tool, primitives, and clock/reset conventions.
  * @param resetActiveLow   Xilinx uses active-LOW reset; Altera uses active-HIGH
  * @param explicitClockPort Altera needs explicit clk_in port; Xilinx uses default clock domain */
sealed trait Manufacturer {
  def resetActiveLow: Boolean
  def explicitClockPort: Boolean
}
object Manufacturer {
  case object Altera extends Manufacturer {
    val resetActiveLow = false
    val explicitClockPort = true
  }
  case object Xilinx extends Manufacturer {
    val resetActiveLow = true
    val explicitClockPort = false
  }
  case object Lattice extends Manufacturer {
    val resetActiveLow = false
    val explicitClockPort = true
  }
}

/** FPGA family — determines DSP type, memory primitives, tool version.
  * @param quartusFamilyName Quartus FAMILY string (Altera families only) */
sealed trait FpgaFamily {
  def manufacturer: Manufacturer
  def memoryStyle: MemoryStyle = MemoryStyle.Generic
  def quartusFamilyName: String = ""
}
object FpgaFamily {
  case object CycloneIV  extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm(); override val quartusFamilyName = "Cyclone IV GX" }
  // Cyclone IV E is a distinct Quartus family from Cyclone IV GX — the name is
  // used verbatim in the generated .qsf, so they cannot share an entry.
  // NOTE: EP4CE6E22C8 below is also a Cyclone IV E part but is still assigned to
  // CycloneIV (GX). Left alone rather than changed blind, since it would alter
  // an existing board's project file; worth checking if that board is revived.
  case object CycloneIVE extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm(); override val quartusFamilyName = "Cyclone IV E" }
  case object CycloneV   extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm(); override val quartusFamilyName = "Cyclone V" }
  case object MAX10      extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm(); override val quartusFamilyName = "MAX 10" }
  case object Artix7     extends FpgaFamily { val manufacturer = Manufacturer.Xilinx }
  case object ECP5       extends FpgaFamily { val manufacturer = Manufacturer.Lattice }
}

/** A speed grade with its temperature grade, as an ordering code spells them:
  * Xilinx XC7A100T-2FGG676C, Altera EP4CGX150DF27I7, Lattice LFE5U-25F-6BG381C.
  * `temp` is C (commercial), I (industrial) or A (automotive). */
case class SpeedGrade(speed: Int, temp: Char = 'C') {
  override def toString: String = s"$temp$speed"
}

/** A concrete FPGA device: a die in a package, at one of the grades it is sold in.
  *
  * A die and package come in several grades, listed in `grades`, and the
  * entries below default to the SLOWEST commercial one: timing closed there
  * holds on every faster grade of the same die, and the bitstream loads on
  * them. A Board that knows its chip overrides the default from its board doc
  * with `withGrade`, and a grade the device is not sold in fails at
  * elaboration instead of naming a part that does not exist.
  *
  * WHY THE GRADE MOVED HERE (2026-10-28, status item 168's rebuild). Each entry
  * used to be one fixed ordering code. The single XC7A100T was -1, shared by the
  * Wukong and the QMTECH core board, whose board docs both say -2. Vivado
  * built both for -2 from a PART constant in each Makefile, and the build
  * summary reported -1. Three of the four Vivado boards disagreed with their
  * Board; FpgaPartTest holds every flow to its board now.
  *
  * `name` is the ordering code, printed on the chip and taken by Quartus as
  * DEVICE. `vivadoPart` is the part Vivado builds and times for. The speed
  * number runs opposite ways by vendor: Xilinx and Lattice count UP to faster
  * (-1 is the slowest Artix-7, -6 the slowest ECP5), while Altera counts up to
  * SLOWER (8 is the slowest Cyclone IV). The grade lists follow the vendors'
  * ordering tables; they exist to reject a grade the die is not sold in.
  */
case class FpgaDevice(
  die: String,             // "XC7A100T", "EP4CGX150", "LFE5U-25F"
  family: FpgaFamily,
  pkg: String,             // "FGG676", "DF27", "BG381"
  grades: Seq[SpeedGrade], // what the vendor sells for this die and package
  grade: SpeedGrade,       // this device's: the slowest by default, a board's own if it says so
  suffix: String = "",     // Altera's trailing letters, e.g. MAX 10's "G"
  les: Int = 0,            // logic elements (0 = unknown)
  dspBlocks: Int = 0,      // DSP blocks (0 = unknown)
  bramKbits: Int = 0       // block RAM in Kbits (0 = unknown)
) {
  require(grades.contains(grade),
    s"$die $pkg is not sold in grade $grade (it comes in ${grades.mkString(", ")})")

  /** The ordering code: EP4CGX150DF27I7, XC7A100T-2FGG676C, LFE5U-25F-6BG381C. */
  def name: String = family.manufacturer match {
    case Manufacturer.Altera => s"$die$pkg${grade.temp}${grade.speed}$suffix"
    case _                   => s"$die-${grade.speed}$pkg${grade.temp}$suffix"
  }

  /** What Vivado builds and times for: xc7a100tfgg676-2. Xilinx devices only. */
  def vivadoPart: Option[String] =
    if (family.manufacturer == Manufacturer.Xilinx) Some(s"$die$pkg-${grade.speed}".toLowerCase)
    else None

  /** This device at another grade it is sold in; the temperature grade stays
    * unless given. Fails if the device is not sold in that grade. */
  def withGrade(speed: Int, temp: Char = grade.temp): FpgaDevice =
    copy(grade = SpeedGrade(speed, temp))
}

object FpgaDevice {
  private def g(spec: String*): Seq[SpeedGrade] = spec.map(s => SpeedGrade(s.tail.toInt, s.head))

  // Ordering-table grades per family, temperature letter first as Altera
  // writes them.
  private val artix7 = g("C1", "C2", "C3", "I1", "I2")
  private val ecp5   = g("C6", "C7", "C8", "I6", "I7", "I8")

  /** QMTECH EP4CGX150 core board: the board is I7 (`Board.QmtechEP4CGX150`). */
  def EP4CGX150DF27 = FpgaDevice("EP4CGX150", FpgaFamily.CycloneIV, "DF27",
    g("C7", "C8", "I7"), SpeedGrade(8),
    les = 149760, dspBlocks = 360, bramKbits = 6480)

  def `5CEBA2U15` = FpgaDevice("5CEBA2", FpgaFamily.CycloneV, "U15",
    g("C7", "C8", "I7", "A7"), SpeedGrade(8),
    les = 25000, dspBlocks = 50, bramKbits = 1760)

  def `10M08SAE144` = FpgaDevice("10M08SA", FpgaFamily.MAX10, "E144",
    g("C7", "C8", "I7", "A7"), SpeedGrade(8), suffix = "G",
    les = 8000, dspBlocks = 24, bramKbits = 378)

  /** The MAX1000's part: 169-ball UBGA, NOT the 144-pin EQFP above.
    *
    * Same die -- 8064 LEs either way -- but a different package, so the two
    * have entirely different pin names. Board.MAX1000 named the 144-pin part
    * while carrying 169-ball pin assignments, and every one of them came back
    * `Error (171016): illegal location assignment`. Nothing caught it because
    * the hand-written jop_max1000.qsf assigned no pins at all. */
  def `10M08SAU169` = FpgaDevice("10M08SA", FpgaFamily.MAX10, "U169",
    g("C7", "C8", "I7", "A7"), SpeedGrade(8), suffix = "G",
    les = 8000, dspBlocks = 24, bramKbits = 378)

  def EP4CE6E22 = FpgaDevice("EP4CE6", FpgaFamily.CycloneIV, "E22",
    g("C6", "C7", "C8", "I7", "A7"), SpeedGrade(8),
    les = 6272, dspBlocks = 0, bramKbits = 276)

  /** A-E115FB board: EP4CE115 + 1 GB DDR2 SODIMM; the board is I7. See
    * docs/boards/ep4ce115-ddr2-board.md */
  def EP4CE115F23 = FpgaDevice("EP4CE115", FpgaFamily.CycloneIVE, "F23",
    g("C7", "C8", "I7"), SpeedGrade(8),
    les = 114480, dspBlocks = 266, bramKbits = 3888)

  def XC7A35T = FpgaDevice("XC7A35T", FpgaFamily.Artix7, "FTG256", artix7, SpeedGrade(1),
    les = 33280, dspBlocks = 90, bramKbits = 1800)

  def XC7A100T = FpgaDevice("XC7A100T", FpgaFamily.Artix7, "FGG676", artix7, SpeedGrade(1),
    les = 101440, dspBlocks = 240, bramKbits = 4860)

  /** Colorlight i5 v7.0 module. `les` is LUT4 count, not Altera LEs — the two are
    * not comparable, so don't read across to the Cyclone entries above.
    * EBR is 56 x 18 Kbit = 1008 Kbit = 126 KB, which is what bounds a BRAM-only
    * build here (the EP4CGX150 has 6.5x as much). */
  def LFE5U25F = FpgaDevice("LFE5U-25F", FpgaFamily.ECP5, "BG381", ecp5, SpeedGrade(6),
    les = 24288, dspBlocks = 28, bramKbits = 1008)

  /** Colorlight i9+ v6.1 module. The SPEED GRADE IS UNKNOWN: the chips sit
    * under heat sinks that are staying on, so the board keeps the default, -1,
    * the conservative grade. A -1 bitstream loads on a -2 (same die), and
    * timing closed at -1 holds on either. `les` is logic cells, as XC7A100T above. */
  def XC7A50T = FpgaDevice("XC7A50T", FpgaFamily.Artix7, "FGG484", artix7, SpeedGrade(1),
    les = 52160, dspBlocks = 120, bramKbits = 2700)
}

// ==========================================================================
// Memory
// ==========================================================================

/** Memory interface type — determines which controller to instantiate */
sealed trait MemoryType
object MemoryType {
  case object BRAM extends MemoryType
  case object SDRAM_SDR extends MemoryType
  case object SDRAM_DDR2 extends MemoryType
  case object SDRAM_DDR3 extends MemoryType
}

/** A concrete memory device (datasheet parameters) */
case class MemoryDevice(
  name: String,
  memType: MemoryType,
  sizeBytes: Long,
  dataWidth: Int,
  bankWidth: Int = 0,
  columnWidth: Int = 0,
  rowWidth: Int = 0,
  casLatency: Int = 0
)

object MemoryDevice {
  /** QMTECH EP4CGX150 on-board SDR SDRAM — 256 Mbit = 32 MB */
  def W9825G6JH6 = MemoryDevice(
    name = "W9825G6JH6",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 32L * 1024 * 1024,
    dataWidth = 16, bankWidth = 2, columnWidth = 9, rowWidth = 13,
    casLatency = 3)

  /** A-E115FB DDR2 SODIMM — 1 GB, 64-bit, DDR2-667 */
  def HYS64T128021 = MemoryDevice(
    name = "HYS64T128021",
    memType = MemoryType.SDRAM_DDR2,
    sizeBytes = 1024L * 1024 * 1024,
    dataWidth = 64, bankWidth = 2, columnWidth = 10, rowWidth = 14,
    casLatency = 5)

  /** CYC5000 on-board SDR SDRAM — 64 Mbit = 8 MB */
  def W9864G6JT = MemoryDevice(
    name = "W9864G6JT",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 8L * 1024 * 1024,
    dataWidth = 16, bankWidth = 2, columnWidth = 8, rowWidth = 12,
    casLatency = 2)

  /** Alchitry Au V2 / Wukong DDR3 — 2 Gbit = 256 MB */
  def MT41K128M16JT = MemoryDevice(
    name = "MT41K128M16JT-125:K",
    memType = MemoryType.SDRAM_DDR3,
    sizeBytes = 256L * 1024 * 1024,
    dataWidth = 16, bankWidth = 3, columnWidth = 10, rowWidth = 14)

  /** MAX1000 on-board SDR SDRAM — 256 Mbit = 32 MB (same geometry as W9825G6JH6) */
  def IS42S16160G = MemoryDevice(
    name = "IS42S16160G",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 32L * 1024 * 1024,
    dataWidth = 16, bankWidth = 2, columnWidth = 9, rowWidth = 13,
    casLatency = 3)

  /** Colorlight i5 on-board SDR SDRAM — 64 Mbit = 8 MB.
    *
    * The odd one out: **32 bits wide**, where every other SDR part here is 16.
    * 2048 rows x 256 columns x 4 banks x 4 bytes = 8 MB exactly, which is what
    * the module's 11 address pins (A0-A10) and BA0/BA1 imply.
    *
    * On the i5, CKE is tied to VCC, CS to GND and all four DQM to GND — none are
    * driven by the FPGA. No byte masking is therefore possible, which is safe
    * only because JOP issues full-word writes exclusively (`BmbMemoryController`
    * drives mask := B"1111" unconditionally, as do the stack-cache DMA and the
    * debug controller). */
  def EM638325BK6H = MemoryDevice(
    name = "EM638325BK6H",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 8L * 1024 * 1024,
    dataWidth = 32, bankWidth = 2, columnWidth = 8, rowWidth = 11,
    casLatency = 2)

  /** Colorlight i9+ on-module SDR SDRAM (U6): 512K x 32 x 4 banks = 8 MB.
    *
    * Same geometry as the i5's EM638325BK6H, and wired the same way: CKE to
    * VCC, CS to GND and all four DQM to GND (wuxx colorlight_i9plus_v6.1.md),
    * so no byte masking -- safe for the same reason, JOP writes whole words.
    * Kept as its own part because it IS a different chip (ESMT M12L64322A-6B,
    * the marking in the wuxx photo), not because anything here differs. */
  def M12L64322A = MemoryDevice(
    name = "M12L64322A",
    memType = MemoryType.SDRAM_SDR,
    sizeBytes = 8L * 1024 * 1024,
    dataWidth = 32, bankWidth = 2, columnWidth = 8, rowWidth = 11,
    casLatency = 2)

  /** Lookup by part name */
  def byName(name: String): Option[MemoryDevice] = name match {
    case "EM638325BK6H"        => Some(EM638325BK6H)
    case "M12L64322A"          => Some(M12L64322A)
    case "W9825G6JH6"          => Some(W9825G6JH6)
    case "W9864G6JT"           => Some(W9864G6JT)
    case "IS42S16160G"         => Some(IS42S16160G)
    case "MT41K128M16JT-125:K" => Some(MT41K128M16JT)
    case "HYS64T128021"        => Some(HYS64T128021)
    case _                     => None
  }
}
