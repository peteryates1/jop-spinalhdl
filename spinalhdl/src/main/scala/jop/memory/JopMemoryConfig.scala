package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * JOP Memory System Configuration
 *
 * Defines the memory layout and BMB parameters for the JOP memory subsystem.
 *
 * @param dataWidth      Data path width (32 bits)
 * @param addressWidth   Physical address width (24 bits = 16M words = 64MB)
 * @param mainMemSize    Main memory size in bytes
 * @param scratchSize    Scratch pad size in bytes (optional fast local memory)
 */
case class JopMemoryConfig(
  dataWidth: Int = 32,
  addressWidth: Int = 24,
  mainMemSize: BigInt = 8 * 1024 * 1024,  // 8MB default
  scratchSize: BigInt = 4 * 1024,         // 4KB scratch pad
  burstLen: Int = 0,                      // 0=no burst (pipelined single-word), 4=SDR, 8=DDR3
  useOcache: Boolean = true,              // Enable object cache
  ocacheWayBits: Int = 4,                 // log2(entries) — 4 = 16 entries
  ocacheIndexBits: Int = 3,              // log2(fields per entry) — 3 = 8 fields
  ocacheMaxIndexBits: Int = 8,           // max field index addressable (256)
  useAcache: Boolean = true,              // Enable array cache
  acacheWayBits: Int = 4,                 // log2(entries) — 4 = 16 entries
  acacheFieldBits: Int = 2,              // log2(elements per line) — 2 = 4 elements
  acacheMaxIndexBits: Int = 24           // max array index width (full address space)
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(addressWidth >= 16 && addressWidth <= 26, "Address width must be 16-26 bits")
  require(burstLen == 0 || (burstLen >= 2 && (burstLen & (burstLen - 1)) == 0),
    "burstLen must be 0 (no burst) or a power of 2 >= 2")

  /** Bytes per word */
  def byteCount: Int = dataWidth / 8

  /** Main memory size in words */
  def mainMemWords: BigInt = mainMemSize / byteCount

  /** Scratch pad size in words */
  def scratchWords: BigInt = scratchSize / byteCount

  /** lengthWidth: 2 bits for single-word (length=3), wider for burst.
   *  When A$ is enabled with burst, must also accommodate A$ line fill
   *  (acacheFieldBits elements = fieldCnt * byteCount bytes). */
  private def burstLengthWidth: Int = {
    val bcLen = if (burstLen <= 1) 2
                else log2Up(burstLen * byteCount)  // e.g. burstLen=4 → log2Up(16) = 4
    if (burstLen > 0 && useAcache) {
      val acLen = log2Up((1 << acacheFieldBits) * byteCount)
      bcLen.max(acLen)
    } else bcLen
  }

  /**
   * Create BMB parameters for the memory interface
   *
   * Note: BMB uses byte addressing, so addressWidth is for bytes not words.
   */
  def bmbParameter: BmbParameter = BmbParameter(
    access = BmbAccessParameter(
      addressWidth = addressWidth + 2,  // Byte address = word address + 2
      dataWidth = dataWidth
    ).addSources(1, BmbSourceParameter(
      contextWidth = 4,       // Context for tracking pending transactions
      lengthWidth = burstLengthWidth,
      canWrite = true,
      canRead = true,
      alignment = BmbParameter.BurstAlignement.WORD
    )),
    invalidation = BmbInvalidationParameter()  // Use defaults (no invalidation)
  )

  /**
   * Create BMB parameters for memory slave (e.g., BmbOnChipRam)
   */
  def bmbSlaveParameter: BmbParameter = bmbParameter
}

/**
 * JOP Address Space Constants
 *
 * Memory map (matching VHDL):
 *   0x00000000 - 0x3FFFFFFF : Main memory (00)
 *   0x40000000 - 0x7FFFFFFF : Reserved    (01)
 *   0x80000000 - 0xBFFFFFFF : Scratch pad (10)
 *   0xC0000000 - 0xFFFFFFFF : I/O space   (11)
 */
object JopAddressSpace {
  /** Top 2 bits for address type */
  def ADDR_TYPE_MAIN    = 0  // 00
  def ADDR_TYPE_RESERVED = 1 // 01
  def ADDR_TYPE_SCRATCH = 2  // 10
  def ADDR_TYPE_IO      = 3  // 11

  /** I/O base address (word address, top 2 bits = 11) */
  def IO_BASE: Long = 0xC0000000L

  /** Check address type from top 2 bits */
  def getAddressType(addr: UInt, width: Int): UInt = {
    addr(width - 1 downto width - 2)
  }

  /** Check if address is I/O space */
  def isIoAddress(addr: UInt, width: Int): Bool = {
    getAddressType(addr, width) === U(ADDR_TYPE_IO, 2 bits)
  }

  /** Check if address is scratch pad */
  def isScratchAddress(addr: UInt, width: Int): Bool = {
    getAddressType(addr, width) === U(ADDR_TYPE_SCRATCH, 2 bits)
  }

  /** Check if address is main memory */
  def isMainMemAddress(addr: UInt, width: Int): Bool = {
    getAddressType(addr, width) === U(ADDR_TYPE_MAIN, 2 bits)
  }
}

/**
 * I/O Address Definitions
 *
 * JOP uses negative addresses for I/O in Java, which map to high addresses.
 * I/O space is divided into slaves (bits 5:4) and sub-addresses (bits 3:0).
 */
object JopIoSpace {
  // Slave 0: System (bits 5:4 = 00)
  def SYS_CNT      = 0x00  // System counter (read), Interrupt enable (write)
  def SYS_US_CNT   = 0x01  // Microsecond counter
  def SYS_TIMER    = 0x02  // Timer interrupt
  def SYS_WD       = 0x03  // Watchdog
  def SYS_EXC      = 0x04  // Exception
  def SYS_LOCK     = 0x05  // Lock
  def SYS_CPU_ID   = 0x06  // CPU ID
  def SYS_SIGNAL   = 0x07  // Signal

  // Slave 1: UART (bits 5:4 = 01)
  def UART_STATUS  = 0x10  // UART status
  def UART_DATA    = 0x11  // UART data

  /** Get I/O slave ID from address (bits 5:4) */
  def getSlaveId(addr: UInt): UInt = addr(5 downto 4)

  /** Get sub-address within slave (bits 3:0) */
  def getSubAddr(addr: UInt): UInt = addr(3 downto 0)
}

/**
 * Memory Controller Operation Type
 *
 * Encodes the type of memory operation being requested.
 */
object MemOpType extends SpinalEnum {
  val IDLE, READ, WRITE, GETFIELD, PUTFIELD, IALOAD, IASTORE, BCFILL, GETSTATIC, PUTSTATIC, COPY = newElement()
}
