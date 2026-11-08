package jop.io

import org.scalatest.funsuite.AnyFunSuite

/**
 * The cluster carrier's mesh pin plan, as ONE design for every socket reads it.
 *
 * The designer's table (plan/mesh_pads.csv) lists each socket's 36 mesh pads by
 * port and direction: 24 six-pin groups. They collapse into ten BUNDLES, each
 * with one clock pin and one role per pin in every socket that uses it, so the
 * only thing a socket changes is a bundle's direction -- which the bitstream
 * can set from the socket straps at run time.
 *
 * `expected` is that table, written out by hand from the plan (status item
 * 178): a plan revision that changes it fails here first, before any build.
 */
class MeshPlanTest extends AnyFunSuite {

  lazy val plan = MeshPlan.load()

  // clock pin -> (pins in line order CLK, D0, D1, D2, D3, FR; node -> (port, TX?))
  val expected: Map[Int, (Seq[Int], Map[Int, (Int, Boolean)])] = Map(
    58  -> (Seq(58, 60, 62, 64, 66, 68),       Map(0 -> (2, true), 1 -> (2, true), 2 -> (2, false), 3 -> (2, false))),
    88  -> (Seq(88, 74, 76, 78, 80, 70),       Map(0 -> (2, false), 1 -> (2, false), 2 -> (2, true), 3 -> (2, true))),
    90  -> (Seq(90, 84, 112, 86, 104, 110),    Map(0 -> (1, true), 3 -> (1, true))),
    92  -> (Seq(92, 94, 96, 98, 100, 102),     Map(0 -> (1, false), 3 -> (1, false))),
    91  -> (Seq(91, 93, 95, 97, 99, 101),      Map(1 -> (1, true), 2 -> (1, true))),
    89  -> (Seq(89, 83, 111, 85, 103, 109),    Map(1 -> (1, false), 2 -> (1, false))),
    164 -> (Seq(164, 160, 162, 166, 168, 170), Map(0 -> (3, true), 3 -> (3, false))),
    192 -> (Seq(192, 172, 174, 176, 178, 180), Map(0 -> (3, false), 3 -> (3, true))),
    131 -> (Seq(131, 123, 125, 127, 129, 133), Map(1 -> (3, true), 2 -> (3, false))),
    143 -> (Seq(143, 135, 137, 139, 141, 145), Map(1 -> (3, false), 2 -> (3, true))))

  test("the 24 socket/port/direction groups are ten bundles, with one role per pin") {
    val got = plan.bundles.map { b =>
      b.clk.pin -> (b.lines.map(_.pin), b.uses.map { case (n, u) => n -> (u.port, u.tx) })
    }.toMap
    assert(got == expected)
    assert(plan.bundles.map(_.lines.map(_.role)).distinct == Seq(MeshPlan.roles))
  }

  test("every socket has exactly one TX and one RX bundle on each port") {
    for (n <- 0 to 3; k <- 1 to 3) {
      assert(plan.bundles.count(_.uses.get(n).contains(MeshUse(k, tx = true))) == 1, s"node $n port $k TX")
      assert(plan.bundles.count(_.uses.get(n).contains(MeshUse(k, tx = false))) == 1, s"node $n port $k RX")
    }
  }

  test("every bundle that is ever an RX has its clock on the P side of a clock-capable pair") {
    val rx = plan.bundles.filter(_.canRx)
    assert(rx.size == 8)
    for (b <- rx) assert(b.clk.function.matches("""IO_L\d+P_T\d_[MS]RCC_\d+"""), s"${b.name}: ${b.clk}")
  }

  test("the bundles share no pin, and the carrier's own pins are in none of them") {
    val pins = plan.bundles.flatMap(_.lines.map(_.pin))
    assert(pins.size == 60 && pins.distinct.size == 60)
    for ((name, pin) <- MeshPlan.carrierPins) assert(!pins.contains(pin), s"$name on SODIMM $pin")
  }

  test("every mesh net joins a TX line to the same line of its peer's RX bundle, on the same port") {
    val nets = MeshPlan.loadNets()
    assert(nets.size == 72)
    for (net <- nets) {
      val (sb, sRole) = plan.lineAt(net.srcPin)
      val (db, dRole) = plan.lineAt(net.dstPin)
      val su = sb.uses(net.src)
      val du = db.uses(net.dst)
      assert(su.tx && !du.tx, net)
      assert(su.port == du.port && sRole == dRole, net)
      assert((net.src ^ net.dst) == su.port, net)
    }
  }
}
