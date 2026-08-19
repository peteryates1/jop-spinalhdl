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
 * @param addressWidth   Word address width incl. 2 type bits; numerically = log2(mainMemSize).
 *                       26=64MB, 28=256MB, 30=1GB. (JopTop derives it as
 *                       log2Up(mainMemSize/4)+2 from the memory device.)
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
  stackRegionWordsPerCore: Int = 0,     // per-core stack spill region size (0 = legacy)
  hasBackendFill: Boolean = false,      // backend provides a MemFill block-zero mechanism
                                         // (SDR); when false the controller uses its own
                                         // per-word ZERO loop (BRAM, DDR3-for-now)
  hasCardTable: Boolean = false,        // HW card-marking write barrier (generational GC, Stage 1)
  // Memory-stall performance counters at IO_PERFCNT. OFF by default: eleven
  // 32-bit counters are not free on a marginal fit, and two boards close under
  // +0.011 ns. Turn on for measurement builds only. Required for DDR2, which
  // has no simulation model and can be profiled no other way.
  hasPerfCounters: Boolean = false,
  cardTableBudgetBytes: Int = 0,        // BRAM bytes for the card table; card size derived from
                                         // (mainMemSize, this). See docs/gc/stage1-card-table-design.md
  l2MshrCount: Int = 1                  // DRAM L2 misses allowed in flight at once. 1 = the old
                                         // behaviour, one miss at a time, which is the ~1.8x
                                         // multicore DRAM scaling ceiling. Raising it also widens
                                         // BmbCacheBridge to the same number of outstanding
                                         // requests. Costs registers per entry (a whole cache line
                                         // of write data each) and adds logic to the cmdFifo path
                                         // that already terminates the 4/8-core critical path, so
                                         // check WNS on the first build.
                                         // See docs/architecture/nonblocking-cache-mshr-plan.md
) {
  require(dataWidth == 32, "Only 32-bit data width supported")
  require(addressWidth >= 16 && addressWidth <= 32, "Address width must be 16-32 bits (30 = 1GB)")
  require(burstLen == 0 || (burstLen >= 2 && (burstLen & (burstLen - 1)) == 0),
    "burstLen must be 0 (no burst) or a power of 2 >= 2")
  require(!hasCardTable || cardTableBudgetBytes > 0, "hasCardTable requires cardTableBudgetBytes > 0")
  require(!hasCardTable || cardCount >= 32, "card table too small (need >= 32 cards)")
  require(l2MshrCount >= 1 && (l2MshrCount & (l2MshrCount - 1)) == 0,
    "l2MshrCount must be a power of two (it sizes the request-id space)")

  /** Bytes per word */
  def byteCount: Int = dataWidth / 8

  /** Main memory size in words */
  def mainMemWords: BigInt = mainMemSize / byteCount

  // --- Card table (generational GC remembered set) geometry ---
  // See docs/gc/stage1-card-table-design.md. cardShift = log2(words per card);
  // derived as the smallest shift (>= floor) making the table fit the budget.
  def cardMinShift: Int = 2   // >= 4 words (16 B) — no finer than a cache line
  def cardShift: Int = {
    if (!hasCardTable) 0
    else {
      val words = mainMemWords
      val bits  = BigInt(cardTableBudgetBytes) * 8   // bit-packed cards
      var sh = cardMinShift
      while ((words >> sh) > bits) sh += 1
      sh
    }
  }
  /** Number of card bits (covers all of main memory). */
  def cardCount: Int = if (hasCardTable) (mainMemWords >> cardShift).toInt else 0
  /** Card table storage as 32-bit words. */
  def cardWords32: Int = if (hasCardTable) cardCount / 32 else 0

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
 *   0xEE-0xEF  Boot  (2 addrs)   — UART or cfgFlash
 *   0xEC-0xED  Zero  (2 addrs)   — zero-fill DMA regs (BmbMemoryController)
 *   0x80-0xEB  Dynamic (108 addrs) — auto-allocated, packing downward from 0xEB
 */
object JopIoSpace {
  // Fixed base addresses (referenced by jvm.asm)
  val SYS_BASE  = 0xF0  // 16 addrs, 4-bit sub-addr
  val UART_BASE = 0xEE  // Boot device: 2 addrs (UART or cfgFlash), 1-bit sub-addr

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

  // Boot device named register addresses (UART or cfgFlash at 0xE0-0xE1)
  def UART_STATUS  = UART_BASE + 0  // Status register
  def UART_DATA    = UART_BASE + 1  // Data register

  // Zero-fill DMA registers, owned by BmbMemoryController (not an I/O slave).
  // Reserved just below the boot device so the auto allocator (packs down from
  // 0xED) never assigns them; markRange'd in IoAddressAllocator.
  val ZERO_BASE    = 0xEC  // 2 addrs, 1-bit sub-addr
  def ZERO_START   = ZERO_BASE + 0  // write: start word address (latch)
  def ZERO_END     = ZERO_BASE + 1  // write: end word address (launch, exclusive)

  // Card-table registers (generational GC, Stage 1), decoded in JopCore (I/O
  // slave). 8 addrs, 3-bit sub-addr, 8-aligned so a[7:3] selects the block.
  // Reserved in IoAddressAllocator so the auto allocator skips them.
  val CARD_BASE      = 0xE0  // 8 addrs, 3-bit sub-addr
  def CARD_TENURE_LO = CARD_BASE + 0  // write: tenure base word address
  def CARD_TENURE_HI = CARD_BASE + 1  // write: tenure top word address (exclusive)
  def CARD_IDX       = CARD_BASE + 2  // write: 32-card word index for the next DATA read
  def CARD_DATA      = CARD_BASE + 3  // read:  32 cards at CARD_IDX
  def CARD_SHIFT     = CARD_BASE + 4  // read:  cardShift (log2 words per card)
  def CARD_COUNT     = CARD_BASE + 5  // read:  number of 32-card words in the table
  def CARD_CLEAR     = CARD_BASE + 6  // write: clear word=value (or -1 => clear all)

  // Hardware address-match predicates for fixed devices (operate on 8-bit ioAddr)
  def isSys(a: UInt): Bool   = a(7 downto 4) === (SYS_BASE >> 4)
  def isUart(a: UInt): Bool  = a(7 downto 1) === (UART_BASE >> 1)
  def isZero(a: UInt): Bool  = a(7 downto 1) === (ZERO_BASE >> 1)
  def isCard(a: UInt): Bool  = a(7 downto 3) === (CARD_BASE >> 3)

  // Sub-address extraction for fixed devices
  def sysAddr(a: UInt): UInt  = a(3 downto 0)
  def uartAddr(a: UInt): UInt = a(0 downto 0)
  def zeroSel(a: UInt): UInt  = a(0 downto 0)  // 0 = START, 1 = END
  def cardSel(a: UInt): UInt  = a(2 downto 0)
}

/**
 * Memory Controller Operation Type
 *
 * Encodes the type of memory operation being requested.
 */
object MemOpType extends SpinalEnum {
  val IDLE, READ, WRITE, GETFIELD, PUTFIELD, IALOAD, IASTORE, BCFILL, GETSTATIC, PUTSTATIC, COPY = newElement()
}
