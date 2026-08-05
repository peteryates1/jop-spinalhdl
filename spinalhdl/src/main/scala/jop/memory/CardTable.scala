package jop.memory

import spinal.core._
import spinal.lib._

/**
 * Generational-GC card table (remembered set) — Stage 1.
 *
 * A bit-packed dirty-bit array, one bit per `2^cardShift`-word card, covering
 * all of main memory. The memory subsystem snoops committed writes and, for any
 * write into the tenure window `[baseWord, topWord)`, sets the covering card.
 * The GC later reads the table (32 cards per word) to find tenured objects that
 * may point into the nursery, and clears cards after scanning.
 *
 * Marking is lossless at 1 write/cycle: a read-modify-write pipeline ORs the new
 * bit into the card word, with same-word forwarding so back-to-back writes that
 * fall in the same 32-card word never drop a bit (the BRAM read latency would
 * otherwise miss the previous set). Marking and GC read/clear are temporally
 * separate (marking runs with the mutator; read/clear run stop-the-world), so
 * they share the single BRAM read/write ports without contention.
 *
 * See docs/gc/stage1-card-table-design.md.
 *
 * @param cardCount     number of card bits (power of two, = mainMemWords >> cardShift)
 * @param cardShift     log2(words per card)
 * @param wordAddrWidth width of a physical word address (mark/base/top)
 */
class CardTable(cardCount: Int, cardShift: Int, wordAddrWidth: Int) extends Component {
  require(cardCount >= 32 && ((cardCount & (cardCount - 1)) == 0), "cardCount must be a power of two >= 32")

  val nWords    = cardCount / 32
  val idxWidth  = log2Up(nWords)
  val cardBits  = log2Up(cardCount)

  val io = new Bundle {
    // Snoop of committed mutator writes (one per cycle max).
    val markValid = in  Bool()
    val markAddr  = in  UInt(wordAddrWidth bits)   // physical word address of the write
    // Tenure window (word addresses); a write marks only if base <= addr < top.
    val baseWord  = in  UInt(wordAddrWidth bits)
    val topWord   = in  UInt(wordAddrWidth bits)
    // GC readback: present rdIdx, read rdData the next cycle (32 cards).
    val rdIdx     = in  UInt(idxWidth bits)
    val rdData    = out Bits(32 bits)
    // GC clear: clrEn clears one word (clrIdx); clrAll sweeps the whole table.
    val clrEn     = in  Bool()
    val clrIdx    = in  UInt(idxWidth bits)
    val clrAll    = in  Bool()
    val clrBusy   = out Bool()
  }

  val mem = Mem(Bits(32 bits), nWords)

  // --- snoop input register (stage 0) ---
  //
  // The mark address arrives combinationally from the core's BMB command, which
  // is itself driven by whatever is generating the write — during a GC zero-fill
  // that is the DMA's `zeroCur` counter. Without this register the whole chain
  //
  //   zeroCur -> BMB address -> inRange compare + shift -> read-port address
  //
  // is one path ending at a BRAM address pin, and it became the critical path of
  // the 4-core SMP build (-2.862 ns at 100 MHz). Registering here splits it into
  // two short halves and costs nothing that matters: a card is marked one cycle
  // later, and the collector only reads the table after halting every core, so
  // an in-flight mark has long since landed.
  val mValid = RegNext(io.markValid) init (False)
  val mAddr  = RegNext(io.markAddr)  init (0)

  // --- card index of the marked write ---
  val inRange = mValid && (mAddr >= io.baseWord) && (mAddr < io.topWord)
  val cardIdx = (mAddr >> cardShift).resize(cardBits)
  val wIdx    = cardIdx(cardBits - 1 downto 5).resize(idxWidth)  // which 32-card word
  val bIdx    = cardIdx(4 downto 0)                              // bit within the word

  // --- clear-all sweep ---
  val clrAllActive = RegInit(False)
  val clrAllCnt    = Reg(UInt(idxWidth bits)) init (0)
  when(io.clrAll && !clrAllActive) { clrAllActive := True; clrAllCnt := 0 }
  when(clrAllActive) {
    clrAllCnt := clrAllCnt + 1
    when(clrAllCnt === U(nWords - 1)) { clrAllActive := False }
  }
  io.clrBusy := clrAllActive

  // --- read port (mark RMW read has priority over GC readback; never overlap) ---
  // mValid, not io.markValid: the read must be issued for the mark now in
  // stage 0, and the GC readback path is unaffected because the collector only
  // reads with every core halted, so no mark can be in flight.
  val readAddr = Mux(mValid, wIdx, io.rdIdx)
  val memRead  = mem.readSync(readAddr)
  io.rdData := memRead

  // --- mark pipeline stage 2 registers (from stage 1 combinational above) ---
  val s1valid = RegNext(inRange) init (False)
  val s1widx  = RegNext(wIdx)  init (0)
  val s1bit   = RegNext(bIdx)  init (0)

  // Forward the just-written word when the next mark hits the same word, because
  // the BRAM read issued last cycle can't reflect this cycle's write.
  val prevWrEn   = RegInit(False)
  val prevWrIdx  = Reg(UInt(idxWidth bits)) init (0)
  val prevWrWord = Reg(Bits(32 bits)) init (0)
  val base    = Mux(prevWrEn && (prevWrIdx === s1widx), prevWrWord, memRead)
  val setMask = (U(1, 32 bits) << s1bit).resize(32).asBits   // bit s1bit (0..31)
  val newWord = base | setMask

  // --- write port: mark set | single-word clear | clear-all sweep ---
  val wrEn   = s1valid || io.clrEn || clrAllActive
  val wrIdx  = Mux(clrAllActive, clrAllCnt, Mux(io.clrEn, io.clrIdx, s1widx))
  val wrData = Mux(clrAllActive || io.clrEn, B(0, 32 bits), newWord)
  mem.write(wrIdx, wrData, enable = wrEn)

  prevWrEn   := wrEn
  prevWrIdx  := wrIdx
  prevWrWord := wrData
}
