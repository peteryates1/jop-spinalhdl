package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.TestVectorUtils

/**
 * ONE mesh design in all four sockets, wired as the carrier wires them.
 *
 * Four MeshPrbsCore, each on its own clock -- the modules have their own
 * oscillators, so the periods here differ by up to 6 % -- each with its
 * socket's straps, and every net of plan/mesh_nets.csv between them. A pad
 * sees whichever end drives its net, or 0 (the pull-down) when neither does. A
 * net driven from BOTH ends is a fault, and so is any pad driven before the
 * straps have been read: a monitor checks both on every cycle.
 *
 * The wiring comes from mesh_nets.csv and the design's bundles from
 * mesh_pads.csv, so twelve links locking checks the two files against each
 * other as well as the design against both.
 *
 * LOCKING ALONE DOES NOT SHOW WHO SENT IT. The pads loop every driven bundle
 * back to its own checker, so a port that reported its node's own TX bundle
 * would lock as cleanly as one reporting its peer: proved, the first test
 * passed with exactly that mutation. The second test is the one that tells
 * them apart -- a silent peer must leave its link down -- and it failed on it
 * (node 0 port 2). Proved red as well: node straps read bit-swapped (nets
 * driven from both ends), and TX enabled without the straps (driven before
 * they were read, and off the carrier).
 */
class MeshPrbsCarrierTest extends AnyFunSuite {

  val plan = MeshPlan.load()
  val nets = MeshPlan.loadNets()
  val settle = 32
  val windowLog2 = 6
  val periods = Seq(1000, 1030, 970, 1010)

  case class PortView() extends Bundle {
    val lock, everLocked = Bool()
    val errors, windows  = UInt(32 bits)
  }
  case class NodeView(nBundles: Int) extends Bundle {
    val valid, onCarrier = Bool()
    val nodeId = UInt(2 bits)
    val drive  = Bits(nBundles bits)  // which bundles this node drives
    val ports  = Vec(PortView(), 3)   // each port's last status
  }

  case class Carrier() extends Component {
    val nb = plan.bundles.size
    val io = new Bundle {
      val carrierN = in Bits(4 bits)
      val node     = in Vec(Bits(2 bits), 4)
      val nodes    = out Vec(NodeView(nb), 4)
    }
    val cds   = (0 until 4).map(n => ClockDomain.external(s"n$n"))
    val cores = (0 until 4).map(n => cds(n)(MeshPrbsCore(plan, settle, windowLog2)))

    /** What node n's pad `pin` puts on its net: its line, while it drives. */
    def drives(n: Int, pin: Int): Bool = {
      val (b, role) = plan.lineAt(pin)
      val pads = cores(n).io.bundles(plan.bundles.indexOf(b))
      pads.txEn && pads.tx.line(role)
    }
    /** A net is a wire between two modules' clocks. A forwarded clock and its
      * data share their source, which SpinalHDL cannot see through the pads,
      * so each net is marked as the crossing it is in hardware; its timing is
      * the pins', which the XDC constrains. */
    def wire(v: Bool): Bool = {
      val w = Bool()
      w := v
      w.addTag(crossClockDomain)
      w
    }
    val net: Map[(Int, Int), Bool] = nets.flatMap { m =>
      val v = wire(drives(m.src, m.srcPin) || drives(m.dst, m.dstPin))
      Seq((m.src, m.srcPin) -> v, (m.dst, m.dstPin) -> v)
    }.toMap

    for (n <- 0 until 4) {
      val core = cores(n)
      core.io.strapCarrierN := io.carrierN(n)
      core.io.strapNode := io.node(n)
      for ((b, i) <- plan.bundles.zipWithIndex; line <- b.lines)  // an unconnected pad sees only itself
        core.io.bundles(i).rx.line(line.role) := net.getOrElse((n, line.pin), wire(drives(n, line.pin)))

      val view = cds(n)(new Area {
        val v = io.nodes(n)
        v.valid := core.io.valid
        v.onCarrier := core.io.onCarrier
        v.nodeId := core.io.nodeId
        v.drive := core.io.bundles.map(_.txEn).asBits
        for (k <- 0 until 3) {
          val last = Reg(PortView())
          last.lock init False
          last.everLocked init False
          last.errors init 0
          last.windows init 0
          val s = core.io.ports(k).status
          when(s.valid) {
            last.lock := s.payload.lock
            last.everLocked := s.payload.everLocked
            last.errors := s.payload.errors
            last.windows := s.payload.windows
          }
          v.ports(k) := last
        }
      })
    }
  }

  lazy val compiled = TestVectorUtils.simWave(SimConfig)
    .workspaceName("MeshPrbsCarrierTest")
    .addRtl("spinalhdl/src/test/resources/xilinx_oddr_model.v")
    .addRtl("spinalhdl/src/test/resources/xilinx_bufg_model.v")
    .compile(Carrier())

  def drive(dut: Carrier, n: Int): Seq[Boolean] = {
    val bits = dut.io.nodes(n).drive.toBigInt
    plan.bundles.indices.map(i => bits.testBit(i))
  }

  /** Runs the four nodes for `windows` link windows, with each socket's
    * 113 strap and node straps, then `check`. Throughout, no net may be driven
    * from both ends and no node may drive before its straps are read. */
  def run(onCarrier: Seq[Boolean], node: Seq[Int], windows: Int = 24)(check: Carrier => Unit): Unit =
    compiled.doSim { dut =>
      dut.io.carrierN #= (0 until 4).map(n => if (onCarrier(n)) 0 else 1 << n).sum
      for (n <- 0 until 4) dut.io.node(n) #= node(n)
      for (n <- 0 until 4) {
        dut.cds(n).forkStimulus(periods(n))
        dut.cds(n).assertReset()
      }
      dut.cds(0).waitSampling(4)
      for (n <- 0 until 4) dut.cds(n).deassertReset()

      var faults = Vector.empty[String]
      val monitor = fork {
        while (true) {
          dut.cds(0).waitSampling()
          val d = (0 until 4).map(drive(dut, _))
          for (n <- 0 until 4 if !dut.io.nodes(n).valid.toBoolean && d(n).contains(true))
            faults :+= s"node $n drives before its straps are read"
          for (m <- nets) {
            val bs = plan.bundles.indexOf(plan.lineAt(m.srcPin)._1)
            val bd = plan.bundles.indexOf(plan.lineAt(m.dstPin)._1)
            if (d(m.src)(bs) && d(m.dst)(bd)) faults :+= s"${m.name} driven from both ends"
          }
        }
      }
      dut.cds(0).waitSampling(windows << windowLog2)
      assert(faults.isEmpty, faults.distinct.take(8).mkString("; "))
      check(dut)
    }

  test("one design in all four sockets brings up all twelve links, with no error and no net driven twice") {
    run(onCarrier = Seq.fill(4)(true), node = Seq(0, 1, 2, 3)) { dut =>
      for (n <- 0 until 4) {
        val v = dut.io.nodes(n)
        assert(v.valid.toBoolean && v.onCarrier.toBoolean && v.nodeId.toInt == n, s"node $n straps")
        assert(drive(dut, n) == plan.bundles.map(_.uses.get(n).exists(_.tx)), s"node $n drives the wrong bundles")
        for (k <- 0 until 3) {
          val p = v.ports(k)
          val tag = s"node $n port ${k + 1}"
          assert(p.everLocked.toBoolean && p.lock.toBoolean, s"$tag did not lock")
          assert(p.errors.toBigInt == 0, s"$tag: ${p.errors.toBigInt} errors")
          assert(p.windows.toBigInt >= 10, s"$tag: only ${p.windows.toBigInt} windows")
        }
      }
    }
  }

  test("a module that is not on the carrier drives nothing, and only the links from it stay down") {
    // node 2's 113 open, as on any other board, and its node straps floating high
    run(onCarrier = Seq(true, true, false, true), node = Seq(0, 1, 3, 3)) { dut =>
      assert(!dut.io.nodes(2).onCarrier.toBoolean && dut.io.nodes(2).valid.toBoolean)
      assert(!drive(dut, 2).contains(true), "node 2 drives off the carrier")
      for (n <- Seq(0, 1, 3); k <- 1 to 3) {
        val fromNode2 = (n ^ k) == 2
        assert(dut.io.nodes(n).ports(k - 1).everLocked.toBoolean == !fromNode2, s"node $n port $k")
      }
    }
  }
}
