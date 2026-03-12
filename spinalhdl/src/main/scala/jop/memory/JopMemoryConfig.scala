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
 * @param addressWidth   Word address width incl. 2 type bits (24=64MB, 26=256MB, 28=1GB)
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
  ocacheIndexBits: Int = 4,              // log2(fields per entry) — 4 = 16 fields
  ocacheMaxIndexBits: Int = 8,           // max field index addressable (256)
  useAcache: Boolean = true,              // Enable array cache
  acacheWayBits: Int = 4,                 // log2(entries) — 4 = 16 entries
  acacheFieldBits: Int = 2,              // log2(elements per line) — 2 = 4 elements
  acacheMaxIndexBits: Int = 24,          // max array index width (full address space)
  ocacheInvalOnStidx: Boolean = true,   // Invalidate O$ on stidx (method scope change).
                                         // True = WCET-safe (conservative). False = better hit rate.
  acacheInvalOnStidx: Boolean = true,   // Invalidate A$ on stidx (method scope change).
                                         // True = WCET-safe (conservative). False = better hit rate.
                                         // Array data is heap-allocated; stidx doesn't affect it.
  stackRegionWordsPerCore: Int = 0      // per-core stack spill region size (0 = legacy)
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(addressWidth >= 16 && addressWidth <= 28, "Address width must be 16-28 bits")
  require(burstLen == 0 || (burstLen >= 2 && (burstLen & (burstLen - 1)) == 0),
    "burstLen must be 0 (no burst) or a power of 2 >= 2")

  /** Bytes per word */
  def byteCount: Int = dataWidth / 8

  /** Main memory size in words */
  def mainMemWords: BigInt = mainMemSize / byteCount

  /** Scratch pad size in words */
  def scratchWords: BigInt = scratchSize / byteCount

  /** Usable memory end (words) — total minus per-core stack regions. */
  def usableMemWords(cpuCnt: Int): Int = {
    if (stackRegionWordsPerCore > 0)
      (mainMemSize / byteCount).toInt - cpuCnt * stackRegionWordsPerCore
    else
      (mainMemSize / byteCount).toInt
  }

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
   * Create BMB parameters for memory device (e.g., BmbOnChipRam)
   */
  def bmbDeviceParameter: BmbParameter = bmbParameter
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
 * I/O Address Space — Fixed Addresses for jvm.asm Decode
 *
 * JOP I/O addresses are negative values pushed by `bipush` (-128 to -1),
 * giving low-byte range 0x80-0xFF (128 addresses).
 *
 * Only Sys and Uart have fixed addresses (referenced by jvm.asm).
 * All other device addresses are assigned dynamically by IoAddressAllocator
 * at generation time and exported via ConstGenerator.
 *
 * Fixed layout:
 *   0xF0-0xFF  Sys   (16 addrs)  — always present, top of space
 *   0xE0-0xEF  Uart  (16 addrs)  — just below Sys
 *   0x80-0xDF  Dynamic  (96 addrs)  — auto-allocated, packing downward from 0xDF
 */
object JopIoSpace {
  // Fixed base addresses (referenced by jvm.asm)
  val SYS_BASE  = 0xF0  // 16 addrs, 4-bit sub-addr
  val UART_BASE = 0xE0  // 16 addrs, 4-bit sub-addr (only 3 used)

  // Sys named register addresses (for ConstGenerator and Sys wiring)
  def SYS_CNT      = SYS_BASE + 0   // System counter (read), Interrupt enable (write)
  def SYS_US_CNT   = SYS_BASE + 1   // Microsecond counter
  def SYS_TIMER    = SYS_BASE + 2   // Timer interrupt
  def SYS_WD       = SYS_BASE + 3   // Watchdog
  def SYS_EXC      = SYS_BASE + 4   // Exception
  def SYS_LOCK     = SYS_BASE + 5   // Lock
  def SYS_CPU_ID   = SYS_BASE + 6   // CPU ID
  def SYS_SIGNAL   = SYS_BASE + 7   // Signal
  def SYS_FPU_CAP  = SYS_BASE + 15  // FPU capability (bit 0 = HW float)

  // Uart named register addresses
  def UART_STATUS  = UART_BASE + 0  // UART status
  def UART_DATA    = UART_BASE + 1  // UART data

  // Hardware address-match predicates for fixed devices (operate on 8-bit ioAddr)
  def isSys(a: UInt): Bool  = a(7 downto 4) === (SYS_BASE >> 4)
  def isUart(a: UInt): Bool = a(7 downto 4) === (UART_BASE >> 4)

  // Sub-address extraction for fixed devices
  def sysAddr(a: UInt): UInt  = a(3 downto 0)
  def uartAddr(a: UInt): UInt = a(3 downto 0)
}

/**
 * Memory Controller Operation Type
 *
 * Encodes the type of memory operation being requested.
 */
object MemOpType extends SpinalEnum {
  val IDLE, READ, WRITE, GETFIELD, PUTFIELD, IALOAD, IASTORE, BCFILL, GETSTATIC, PUTSTATIC, COPY = newElement()
}
