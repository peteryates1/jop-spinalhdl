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

/** A concrete FPGA device */
case class FpgaDevice(
  name: String,            // "EP4CGX150DF27I7"
  family: FpgaFamily,
  les: Int = 0,            // logic elements (0 = unknown)
  dspBlocks: Int = 0,      // DSP blocks (0 = unknown)
  bramKbits: Int = 0       // block RAM in Kbits (0 = unknown)
)

object FpgaDevice {
  def EP4CGX150DF27I7 = FpgaDevice("EP4CGX150DF27I7", FpgaFamily.CycloneIV,
    les = 149760, dspBlocks = 360, bramKbits = 6480)

  def `5CEBA2U15C8` = FpgaDevice("5CEBA2U15C8", FpgaFamily.CycloneV,
    les = 25000, dspBlocks = 50, bramKbits = 1760)

  def `10M08SAE144C8G` = FpgaDevice("10M08SAE144C8G", FpgaFamily.MAX10,
    les = 8000, dspBlocks = 24, bramKbits = 378)

  def EP4CE6E22C8 = FpgaDevice("EP4CE6E22C8", FpgaFamily.CycloneIV,
    les = 6272, dspBlocks = 0, bramKbits = 276)

  /** A-E115FB board: EP4CE115 + 1 GB DDR2 SODIMM. See docs/boards/ep4ce115-ddr2-board.md */
  def EP4CE115F23I7 = FpgaDevice("EP4CE115F23I7", FpgaFamily.CycloneIVE,
    les = 114480, dspBlocks = 266, bramKbits = 3888)

  def XC7A35T = FpgaDevice("XC7A35T-1FTG256C", FpgaFamily.Artix7,
    les = 33280, dspBlocks = 90, bramKbits = 1800)

  def XC7A100T = FpgaDevice("XC7A100T-1FGG676C", FpgaFamily.Artix7,
    les = 101440, dspBlocks = 240, bramKbits = 4860)

  /** Colorlight i5 v7.0 module. `les` is LUT4 count, not Altera LEs — the two are
    * not comparable, so don't read across to the Cyclone entries above.
    * EBR is 56 x 18 Kbit = 1008 Kbit = 126 KB, which is what bounds a BRAM-only
    * build here (the EP4CGX150 has 6.5x as much). */
  def LFE5U25F = FpgaDevice("LFE5U-25F-6BG381C", FpgaFamily.ECP5,
    les = 24288, dspBlocks = 28, bramKbits = 1008)
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

  /** Lookup by part name */
  def byName(name: String): Option[MemoryDevice] = name match {
    case "EM638325BK6H"        => Some(EM638325BK6H)
    case "W9825G6JH6"          => Some(W9825G6JH6)
    case "W9864G6JT"           => Some(W9864G6JT)
    case "IS42S16160G"         => Some(IS42S16160G)
    case "MT41K128M16JT-125:K" => Some(MT41K128M16JT)
    case "HYS64T128021"        => Some(HYS64T128021)
    case _                     => None
  }
}
