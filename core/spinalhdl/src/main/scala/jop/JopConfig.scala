package jop

/**
 * Configuration for JOP processor
 *
 * This allows creating different configurations for different
 * FPGA boards and use cases.
 */
case class JopConfig(
  // Core configuration
  dataWidth: Int = 32,
  stackDepth: Int = 16,

  // Memory configuration
  methodCacheSize: Int = 4096,
  microCodeRomSize: Int = 1024,
  stackBufferSize: Int = 64,

  // Pipeline configuration
  fetchStages: Int = 1,

  // FPGA target specific
  fpgaTarget: FpgaTarget = FpgaTarget.Altera,

  // Feature flags
  enableCache: Boolean = true,
  enableMultiCore: Boolean = false,
  enableDebug: Boolean = false
) {
  // Validation
  require(dataWidth == 32, "Only 32-bit data width currently supported")
  require(stackDepth > 0 && stackDepth <= 256, "Stack depth must be 1-256")
  require(methodCacheSize > 0, "Method cache size must be positive")
}

/**
 * FPGA target selection
 */
sealed trait FpgaTarget
object FpgaTarget {
  case object Altera extends FpgaTarget
  case object Xilinx extends FpgaTarget
  case object Lattice extends FpgaTarget
}

/**
 * Predefined configurations for different boards
 */
object JopConfig {

  /**
   * EP4CGX150DF27 Core Board
   * Large Cyclone IV FPGA
   */
  def ep4cgx150df27: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Altera,
    methodCacheSize = 8192,
    stackBufferSize = 128,
    enableCache = true
  )

  /**
   * Cyclone IV EP4CE15
   * Smaller Cyclone IV FPGA
   */
  def cyclone4: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Altera,
    methodCacheSize = 4096,
    stackBufferSize = 64,
    enableCache = true
  )

  /**
   * Alchitry Au
   * Xilinx Artix-7 FPGA
   */
  def alchitryAu: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Xilinx,
    methodCacheSize = 4096,
    stackBufferSize = 64,
    enableCache = true
  )

  /**
   * MAX1000 IoT Maker Board
   * Intel MAX10 FPGA
   */
  def max1000: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Altera,
    methodCacheSize = 2048,
    stackBufferSize = 32,
    enableCache = true
  )

  /**
   * CYC5000
   * Cyclone V FPGA
   */
  def cyc5000: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Altera,
    methodCacheSize = 8192,
    stackBufferSize = 128,
    enableCache = true
  )

  /**
   * CYC1000
   * Cyclone 10 LP FPGA
   */
  def cyc1000: JopConfig = JopConfig(
    fpgaTarget = FpgaTarget.Altera,
    methodCacheSize = 2048,
    stackBufferSize = 32,
    enableCache = true
  )

  /**
   * Minimal configuration for testing/simulation
   */
  def minimal: JopConfig = JopConfig(
    methodCacheSize = 1024,
    stackBufferSize = 16,
    enableCache = false,
    enableDebug = true
  )
}
