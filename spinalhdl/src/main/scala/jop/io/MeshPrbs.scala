package jop.io

import spinal.core._
import spinal.lib._

/**
 * The JOP cluster carrier's mesh link, as a PRBS test: one direction of one
 * port is a forwarded clock and five data lines, D[3:0] and FR. The pin plan
 * and the electrical choices are the carrier handoff's
 * (fpga/cluster-carrier-mesh/README.md); this is the logic its build check and
 * per-link bring-up test run.
 *
 * Every line carries PRBS7 (x^7 + x^6 + 1), five bits a clock, FR as the fifth,
 * so all five toggle. The checker predicts each received bit from the seven
 * RECEIVED before it, so it locks on to any phase within seven bits and a
 * single flipped bit costs exactly three errors: the bit, and the two
 * predictions it is a tap of.
 */
object Prbs7 {
  /** `width` bits after `state`, first in time at index 0, and the state after
    * them. `state` holds the last seven bits, newest at bit 0. */
  def advance(state: Bits, width: Int): (Bits, Bits) = {
    var s = state
    val out = Bits(width bits)
    for (i <- 0 until width) {
      val b = s(5) ^ s(6)
      out(i) := b
      s = s(5 downto 0) ## b
    }
    (out, s)
  }

  /** Which of `rx` (first in time at index 0) the received history did not
    * predict, and the history after them. */
  def check(history: Bits, rx: Bits): (Bits, Bits) = {
    var s = history
    val errs = Bits(rx.getWidth bits)
    for (i <- 0 until rx.getWidth) {
      errs(i) := rx(i) =/= (s(5) ^ s(6))
      s = s(5 downto 0) ## rx(i)
    }
    (errs, s)
  }
}

/**
 * One TX direction: a PRBS7 source, the clock forwarded through an ODDR, and
 * every data line launched from an ODDR too, so all six leave from the I/O
 * tile on the same clock.
 *
 * The data changes on the FALLING edge, which centres it on the forwarded
 * clock's rising edge at the far end (handoff section 4). With the ODDR's
 * SAME_EDGE timing -- D1 for the high half of a cycle, D2 for the low half --
 * that is D1 = the value already on the line and D2 = the next one, so a line
 * never changes at a rising edge.
 */
case class MeshTxPrbs(seed: Int) extends Component {
  require(seed > 0 && seed < 128, "a PRBS7 seed is seven bits and not zero")
  val io = new Bundle {
    val clk = out Bool()
    val d   = out Bits(4 bits)
    val fr  = out Bool()
  }

  val state = RegInit(B(seed, 7 bits))
  val (bits, next) = Prbs7.advance(state, 5)
  state := next
  val now    = RegNext(bits) init 0  // on the line from this falling edge
  val before = RegNext(now) init 0   // on the line now, across the rising edge

  private def oddr(d1: Bool, d2: Bool): Bool = {
    val o = new XilinxOddr
    o.io.C  := ClockDomain.current.readClockWire
    o.io.CE := True
    o.io.R  := False
    o.io.S  := False
    o.io.D1 := d1
    o.io.D2 := d2
    o.io.Q
  }

  io.clk := oddr(True, False)
  for (i <- 0 until 4) io.d(i) := oddr(before(i), now(i))
  io.fr := oddr(before(4), now(4))
}

/** What one RX direction reports, once a window: whether the window just ended
  * was clean, and the bit errors and windows since the first clean one. */
case class MeshRxStatus() extends Bundle {
  val lock       = Bool()     // the window just ended: no error, and a 1 seen
  val everLocked = Bool()
  val errors     = UInt(32 bits)  // bit errors since the first clean window
  val windows    = UInt(32 bits)  // windows since the first clean window
}

/**
 * One RX direction, in the received clock's domain: D and FR captured in IOB
 * flip-flops, a PRBS7 checker, and a status snapshot every `windowLog2`
 * cycles.
 *
 * A clean window needs a 1 as well as no error. The checker predicts from the
 * received bits, so a line held at 0 predicts 0 and never errs -- a dead link,
 * or a peer still configuring with its pins pulled low, would otherwise lock.
 */
case class MeshRxPrbs(windowLog2: Int = 20) extends Component {
  val io = new Bundle {
    val d      = in Bits(4 bits)
    val fr     = in Bool()
    val status = master(Flow(MeshRxStatus()))
  }

  val dIob  = RegNext(io.d)
  val frIob = RegNext(io.fr)
  dIob.addAttribute("IOB", "TRUE")
  frIob.addAttribute("IOB", "TRUE")
  val rx = frIob ## dIob  // the order the source sent them: D0..D3, then FR

  val history = RegInit(B(0, 7 bits))
  val (errs, next) = Prbs7.check(history, rx)
  history := next
  val nErrs = CountOne(errs)

  def satAdd(a: UInt, b: UInt): UInt = {
    val s = a.resize(a.getWidth + 1) + b.resize(a.getWidth + 1)
    Mux(s.msb, U(a.maxValue, a.getWidth bits), s(a.getWidth - 1 downto 0))
  }

  val window     = Reg(UInt(windowLog2 bits)) init 0
  val winErrs    = Reg(Bool()) init False
  val winOnes    = Reg(Bool()) init False
  val everLocked = Reg(Bool()) init False
  val errors     = Reg(UInt(32 bits)) init 0
  val windows    = Reg(UInt(32 bits)) init 0

  window := window + 1
  val end = window.andR
  val clean = !winErrs && !(nErrs =/= 0) && (winOnes || rx.orR)

  when(everLocked) { errors := satAdd(errors, nErrs) }
  when(end) {
    winErrs := False
    winOnes := False
    when(clean) { everLocked := True }
    when(everLocked || clean) { windows := satAdd(windows, U(1, 1 bits)) }
  } otherwise {
    winErrs := winErrs || nErrs =/= 0
    winOnes := winOnes || rx.orR
  }

  io.status.valid := RegNext(end) init False
  io.status.payload.lock       := RegNext(clean)
  io.status.payload.everLocked := everLocked
  io.status.payload.errors     := errors
  io.status.payload.windows    := windows
}
