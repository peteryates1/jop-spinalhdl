package jop

/**
 * Configuration for JOP processor
 *
 * This allows creating different configurations for different
 * FPGA boards and use cases.
 *
 * Based on:
 * - jop_config_global.vhd: Global configuration constants
 * - sc_pack.vhd: SimpCon bus interface constants
 * - jop_types.vhd: Type definitions using these constants
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

  // SimpCon bus configuration (from sc_pack.vhd)
  scAddrSize: Int = 23,        // SimpCon address bus width (23 bits = 8MB address space)
  rdyCntSize: Int = 2,         // Ready counter size for multi-cycle operations

  // Stack configuration (from jop_config_global.vhd)
  stackSizeGlobal: Int = 8,    // # of address bits of internal RAM (stack pointer, variables, constants)

  // Object Cache configuration (from jop_config_global.vhd)
  useOcache: Boolean = true,              // Enable object field cache
  ocacheAddrBits: Int = 23,               // Memory address width (matches scAddrSize)
  ocacheWayBits: Int = 4,                 // Cache associativity (2^4 = 16-way)
  ocacheMaxIndexBits: Int = 8,            // Maximum field index (256 fields max per object)
  ocacheIndexBits: Int = 3,               // Fields per cache line (2^3 = 8 fields)

  // Array Cache configuration (from jop_config_global.vhd)
  useAcache: Boolean = true,              // Enable array element cache
  acacheAddrBits: Int = 23,               // Memory address width (matches scAddrSize)
  acacheMaxIndexBits: Int = 23,           // Maximum array index
  acacheWayBits: Int = 4,                 // Cache associativity (2^4 = 16-way)
  acacheFieldBits: Int = 2,               // Elements per cache line (2^2 = 4 elements)

  // Method cache constants (from jop_types.vhd)
  methodSizeBits: Int = 10,    // Maximum method size (2^10 = 1024 words)

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
  require(scAddrSize > 0, "SimpCon address size must be positive")
  require(ocacheAddrBits == scAddrSize, "Object cache address bits must match SimpCon address size")
  require(acacheAddrBits == scAddrSize, "Array cache address bits must match SimpCon address size")
  require(methodSizeBits == 10, "Method size bits must be 10 (hardcoded in JOP)")
}

/**
 * FPGA target selection
 */
sealed trait FpgaTarget
object FpgaTarget {
  case object Altera extends FpgaTarget
  case object Xilinx extends FpgaTarget
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
