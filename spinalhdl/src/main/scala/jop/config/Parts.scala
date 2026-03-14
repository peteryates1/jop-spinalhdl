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

/** FPGA manufacturer — determines synthesis tool and primitives */
sealed trait Manufacturer
object Manufacturer {
  case object Altera extends Manufacturer   // Intel
  case object Xilinx extends Manufacturer   // AMD
  case object Lattice extends Manufacturer
}

/** FPGA family — determines DSP type, memory primitives, tool version */
sealed trait FpgaFamily {
  def manufacturer: Manufacturer
  def memoryStyle: MemoryStyle = MemoryStyle.Generic
}
object FpgaFamily {
  case object CycloneIV  extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm }
  case object CycloneV   extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm }
  case object MAX10      extends FpgaFamily { val manufacturer = Manufacturer.Altera; override def memoryStyle = MemoryStyle.AlteraLpm }
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

  def XC7A35T = FpgaDevice("XC7A35T-1FTG256C", FpgaFamily.Artix7,
    les = 33280, dspBlocks = 90, bramKbits = 1800)

  def XC7A100T = FpgaDevice("XC7A100T-1FGG676C", FpgaFamily.Artix7,
    les = 101440, dspBlocks = 240, bramKbits = 4860)
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

  /** Lookup by part name */
  def byName(name: String): Option[MemoryDevice] = name match {
    case "W9825G6JH6"          => Some(W9825G6JH6)
    case "W9864G6JT"           => Some(W9864G6JT)
    case "IS42S16160G"         => Some(IS42S16160G)
    case "MT41K128M16JT-125:K" => Some(MT41K128M16JT)
    case _                     => None
  }
}
