package jop.io

import spinal.core._
import spinal.lib._

/** Xilinx 7-series global clock buffer (UG472). Tests supply a pass-through
  * model, spinalhdl/src/test/resources/xilinx_bufg_model.v. */
class XilinxBufg extends BlackBox {
  val io = new Bundle {
    val I = in Bool()
    val O = out Bool()
  }
  noIoPrefix()
  setDefinitionName("BUFG")
}

/** One bundle's six lines. */
case class MeshLines() extends Bundle {
  val clk = Bool()
  val d   = Bits(4 bits)
  val fr  = Bool()

  /** The line a plan role names: CLK, D0-D3 or FR. */
  def line(role: String): Bool = role match {
    case "CLK" => clk
    case "FR"  => fr
    case d0 if d0.matches("D[0-3]") => d(d0.drop(1).toInt)
    case other => sys.error(s"mesh line $other")
  }
}

/** One bundle's pads, from the design's side: what it would drive, whether it
  * drives, and what the pads read. The top puts the I/O buffers between. */
case class MeshBundleIo() extends Bundle with IMasterSlave {
  val tx   = MeshLines()  // to the pads, from the ODDRs
  val txEn = Bool()       // drive them
  val rx   = MeshLines()  // from the pads
  override def asMaster(): Unit = {
    out(tx, txEn)
    in(rx)
  }
}

/** One mesh port's receive side, in the core's clock: each window's status as
  * it arrives, and the RX clock's activity bit (it toggles every 32768 RX
  * clocks; a stopped clock stops it). */
case class MeshPortReport() extends Bundle {
  val status = Flow(MeshRxStatus())
  val active = Bool()
}

/**
 * The carrier's socket straps, read once at start-up (status item 178).
 *
 * SODIMM 113 is grounded on every carrier socket, and 115 (LSB) and 117 (MSB)
 * are grounded for a 0 and left open for a 1, so with the FPGA's pull-ups
 * sockets U1-U4 read as nodes 0-3. A module anywhere else reads 113 high:
 * `onCarrier` stays low and the design drives nothing.
 *
 * The straps count as read once all three have held one value for
 * `settleCycles` (the pull-ups are weak); they are never read again.
 */
case class MeshStraps(settleCycles: Int) extends Component {
  val io = new Bundle {
    val carrierN  = in Bool()        // SODIMM 113
    val node      = in Bits(2 bits)  // SODIMM 117 (bit 1) and 115 (bit 0)
    val valid     = out Bool()
    val onCarrier = out Bool()
    val nodeId    = out UInt(2 bits)
  }

  val now    = BufferCC(io.carrierN ## io.node, init = B"111")
  val last   = RegNext(now) init B"111"
  val stable = Reg(UInt(log2Up(settleCycles + 1) bits)) init 0
  val read   = Reg(Bool()) init False
  val value  = Reg(Bits(3 bits)) init B"111"

  when(!read) {
    when(now =/= last) {
      stable := 0
    } elsewhen (stable === settleCycles) {
      read := True
      value := now
    } otherwise {
      stable := stable + 1
    }
  }
  io.valid := read
  io.onCarrier := read && !value(2)
  io.nodeId := value(1 downto 0).asUInt
}

/**
 * The mesh PRBS test as ONE design for all four sockets, without its pads,
 * PLL or report: those are MeshPrbsTop's, so a simulation can wire four of
 * these together as the carrier does (MeshPrbsCarrierTest).
 *
 * Every bundle that some socket drives has a PRBS7 source, and every bundle
 * that some socket receives on has a checker in its own received clock. Nothing
 * drives until the straps are read; then a socket drives exactly its own TX
 * bundles, and each port reports the RX bundle its socket receives on. A
 * bundle this socket drives is also looped back through its pads to its own
 * checker, which nothing reports.
 *
 * `ports` and the straps are in the core's clock; each checker is in its RX
 * clock, through a BUFG, and crosses by FlowCCByToggle (its status, once a
 * window) and BufferCC (its activity bit).
 */
case class MeshPrbsCore(plan: MeshPlan, settleCycles: Int, windowLog2: Int = 20) extends Component {
  val io = new Bundle {
    val strapCarrierN = in Bool()
    val strapNode     = in Bits(2 bits)
    val valid         = out Bool()
    val onCarrier     = out Bool()
    val nodeId        = out UInt(2 bits)
    val bundles       = Vec.fill(plan.bundles.size)(master(MeshBundleIo()))
    val ports         = out(Vec(MeshPortReport(), 3))
  }

  val straps = MeshStraps(settleCycles)
  straps.io.carrierN := io.strapCarrierN
  straps.io.node := io.strapNode
  io.valid := straps.io.valid
  io.onCarrier := straps.io.onCarrier
  io.nodeId := straps.io.nodeId

  // ---- TX: a source per bundle some socket drives, enabled for this socket's
  for ((b, i) <- plan.bundles.zipWithIndex) {
    val pads = io.bundles(i)
    if (b.canTx) new Area {
      val source = MeshTxPrbs(seed = 1 + i)
      pads.tx.clk := source.io.clk
      pads.tx.d := source.io.d
      pads.tx.fr := source.io.fr
      val drivers = b.uses.collect { case (n, u) if u.tx => n }.toSeq
      val enable = RegNext(straps.io.onCarrier && drivers.map(n => straps.io.nodeId === n).reduce(_ || _)) init False
      pads.txEn := enable
    }.setName(s"tx_${b.name}")
    else {
      pads.tx.clk := False
      pads.tx.d := 0
      pads.tx.fr := False
      pads.txEn := False
    }
  }

  // ---- RX: a checker per bundle some socket receives on, in its own clock
  class Rx(pads: MeshBundleIo) extends Area {
    val bufg = new XilinxBufg
    bufg.io.I := pads.rx.clk
    val cd = ClockDomain(bufg.io.O, config = ClockDomainConfig(resetKind = BOOT))
    val inRx = new ClockingArea(cd) {
      val checker = MeshRxPrbs(windowLog2)
      checker.io.d := pads.rx.d
      checker.io.fr := pads.rx.fr
      val activity = Reg(UInt(16 bits)) init 0
      activity := activity + 1
    }
    val status = FlowCCByToggle(inRx.checker.io.status, inputClock = cd, outputClock = ClockDomain.current)
    val active = BufferCC(inRx.activity.msb, init = False)
  }
  val rx: Map[Int, Rx] = plan.bundles.zipWithIndex.collect { case (b, i) if b.canRx =>
    i -> new Rx(io.bundles(i)).setName(s"rx_${b.name}")
  }.toMap

  // ---- each port reports the bundle its socket receives on
  for (k <- 1 to 3) {
    val p = io.ports(k - 1)
    p.status.valid := False
    p.status.payload.assignDontCare()
    p.active := False
    switch(straps.io.nodeId) {
      for (n <- 0 to 3) is(n) {
        val r = rx(plan.bundles.indexOf(plan.bundle(n, k, tx = false)))
        p.status := r.status
        p.active := r.active
      }
    }
  }
}
