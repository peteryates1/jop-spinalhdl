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
 * I/O Address Space — 2-bit Slot Granularity
 *
 * JOP I/O addresses are negative values pushed by `bipush` (-128 to -1),
 * giving low-byte range 0x80-0xFF (128 addresses). Each "slot" is 4 addresses
 * (2 bits), and larger devices consume multiple power-of-2-aligned consecutive
 * slots. This efficiently packs devices into the limited bipush range while
 * supporting many more devices than a fixed device-ID scheme.
 *
 * Address layout (bipush range 0x80-0xFF):
 *   0x80-0x8F  BmbSys       4 slots (16 addrs)  match a(7:4) === 0x8
 *   0x90-0x93  BmbUart      1 slot  (4 addrs)   match a(7:2) === 0x24
 *   0x94-0x97  (free)       1 slot
 *   0x98-0x9F  BmbEth       2 slots (8 addrs)   match a(7:3) === 0x13
 *   0xA0-0xA7  BmbMdio      2 slots (8 addrs)   match a(7:3) === 0x14
 *   0xA8-0xAB  BmbSdSpi     1 slot  (4 addrs)   match a(7:2) === 0x2A
 *   0xAC-0xAF  BmbVgaDma    1 slot  (4 addrs)   match a(7:2) === 0x2B
 *   0xB0-0xBF  BmbSdNative  4 slots (16 addrs)  match a(7:4) === 0xB
 *   0xC0-0xCF  BmbVgaText   4 slots (16 addrs)  match a(7:4) === 0xC
 *   0xD0-0xFF  (free)       12 slots for future devices
 */
object JopIoSpace {
  // Device base addresses (within 8-bit ioAddr space, bipush range 0x80-0xFF)
  val SYS_BASE       = 0x80  // 4 slots (16 addrs), 4-bit sub-addr
  val UART_BASE      = 0x90  // 1 slot  (4 addrs),  2-bit sub-addr
  val ETH_BASE       = 0x98  // 2 slots (8 addrs),  3-bit sub-addr
  val MDIO_BASE      = 0xA0  // 2 slots (8 addrs),  3-bit sub-addr
  val SD_SPI_BASE    = 0xA8  // 1 slot  (4 addrs),  2-bit sub-addr
  val VGA_DMA_BASE   = 0xAC  // 1 slot  (4 addrs),  2-bit sub-addr
  val SD_NATIVE_BASE = 0xB0  // 4 slots (16 addrs), 4-bit sub-addr
  val VGA_TEXT_BASE  = 0xC0  // 4 slots (16 addrs), 4-bit sub-addr

  // Named register addresses (base + offset)
  def SYS_CNT      = SYS_BASE + 0   // System counter (read), Interrupt enable (write)
  def SYS_US_CNT   = SYS_BASE + 1   // Microsecond counter
  def SYS_TIMER    = SYS_BASE + 2   // Timer interrupt
  def SYS_WD       = SYS_BASE + 3   // Watchdog
  def SYS_EXC      = SYS_BASE + 4   // Exception
  def SYS_LOCK     = SYS_BASE + 5   // Lock
  def SYS_CPU_ID   = SYS_BASE + 6   // CPU ID
  def SYS_SIGNAL   = SYS_BASE + 7   // Signal

  def UART_STATUS  = UART_BASE + 0  // UART status
  def UART_DATA    = UART_BASE + 1  // UART data

  def ETH_STATUS   = ETH_BASE + 0   // Ethernet status/control
  def ETH_TX_AVAIL = ETH_BASE + 1   // TX buffer availability
  def ETH_TX_DATA  = ETH_BASE + 2   // TX data push
  def ETH_RX_DATA  = ETH_BASE + 3   // RX data pop
  def ETH_RX_STATS = ETH_BASE + 4   // RX error/drop stats

  def MDIO_CMD     = MDIO_BASE + 0  // MDIO command (go/write)
  def MDIO_DATA    = MDIO_BASE + 1  // MDIO read/write data
  def MDIO_ADDR    = MDIO_BASE + 2  // MDIO PHY/reg address
  def PHY_RESET    = MDIO_BASE + 3  // PHY hardware reset
  def ETH_INT_CTRL = MDIO_BASE + 4  // Ethernet interrupt enable/pending

  // Hardware address-match predicates (operate on 8-bit ioAddr)
  def isSys(a: UInt): Bool       = a(7 downto 4) === (SYS_BASE >> 4)
  def isUart(a: UInt): Bool      = a(7 downto 2) === (UART_BASE >> 2)
  def isEth(a: UInt): Bool       = a(7 downto 3) === (ETH_BASE >> 3)
  def isMdio(a: UInt): Bool      = a(7 downto 3) === (MDIO_BASE >> 3)
  def isSdSpi(a: UInt): Bool     = a(7 downto 2) === (SD_SPI_BASE >> 2)
  def isVgaDma(a: UInt): Bool    = a(7 downto 2) === (VGA_DMA_BASE >> 2)
  def isSdNative(a: UInt): Bool  = a(7 downto 4) === (SD_NATIVE_BASE >> 4)
  def isVgaText(a: UInt): Bool   = a(7 downto 4) === (VGA_TEXT_BASE >> 4)

  // Sub-address extraction (all return 4-bit UInt for uniform device interface)
  def sysAddr(a: UInt): UInt       = a(3 downto 0)
  def uartAddr(a: UInt): UInt      = a(1 downto 0).resize(4)
  def ethAddr(a: UInt): UInt       = a(2 downto 0).resize(4)
  def mdioAddr(a: UInt): UInt      = a(2 downto 0).resize(4)
  def sdSpiAddr(a: UInt): UInt     = a(1 downto 0).resize(4)
  def vgaDmaAddr(a: UInt): UInt    = a(1 downto 0).resize(4)
  def sdNativeAddr(a: UInt): UInt  = a(3 downto 0)
  def vgaTextAddr(a: UInt): UInt   = a(3 downto 0)
}

/**
 * Memory Controller Operation Type
 *
 * Encodes the type of memory operation being requested.
 */
object MemOpType extends SpinalEnum {
  val IDLE, READ, WRITE, GETFIELD, PUTFIELD, IALOAD, IASTORE, BCFILL, GETSTATIC, PUTSTATIC, COPY = newElement()
}
