package jop.io

import scala.io.Source

/** One of a bundle's six lines: its role (CLK, D0-D3, FR), its SODIMM pin, and
  * the FPGA ball, bank and pin function behind it on the i9+. */
case class MeshLine(role: String, pin: Int, ball: String, bank: Int, function: String)

/** How one node uses a bundle: on mesh port `port` (1-3), driving it or not. */
case class MeshUse(port: Int, tx: Boolean)

/**
 * Six pins that move together: a forwarded clock, D0-D3 and FR, with the same
 * role on every socket that uses them. Two sockets drive a bundle, or two
 * receive on it, or one does each -- never more -- and the other two leave it
 * unconnected.
 */
case class MeshBundle(lines: Seq[MeshLine], uses: Map[Int, MeshUse]) {
  def clk: MeshLine = lines.head
  def data: Seq[MeshLine] = lines.tail
  /** Named by its clock pin: `mesh58`, whose lines are mesh58_clk, mesh58_d0 ... mesh58_fr. */
  def name: String = s"mesh${clk.pin}"
  def canTx: Boolean = uses.values.exists(_.tx)
  def canRx: Boolean = uses.values.exists(!_.tx)
  def portName(line: MeshLine): String = s"${name}_${line.role.toLowerCase}"
}

/** One mesh net, driver to receiver, by node (0-3 = sockets U1-U4) and SODIMM pin. */
case class MeshNet(name: String, src: Int, srcPin: Int, dst: Int, dstPin: Int)

/** One socket strap as the PCB wires it: its SODIMM pin, the design signal
  * it is, and its tie -- "GND" (through a resistor) or "open". */
case class MeshStrap(node: Int, pin: Int, signal: String, tie: String)

/** One socket UART pin as the PCB wires it: its SODIMM pin, its net, and
  * which side drives it -- the "RP2350B" (through a series resistor) or the
  * "FPGA". */
case class MeshUartPin(node: Int, pin: Int, net: String, driver: String)

/**
 * The JOP cluster carrier's mesh pin plan, as ONE design for every socket
 * reads it (status item 178).
 *
 * The source of truth is the carrier designer's `mesh_pads.csv`: one row per
 * socket pad, with its net, direction and port. Grouped by socket, port and
 * direction it gives 24 six-pin groups, and those are only ten distinct pin
 * sets -- the BUNDLES -- because a link's two ends use the same lane on both
 * sockets. Each bundle's pins keep one role in every socket, so a socket
 * changes nothing but which bundles it drives: one bitstream can serve all
 * four, choosing at run time from the socket straps.
 *
 * The straps are `straps.csv`, the PCB netlist's strap wiring: which SODIMM
 * pin is which strap, and which sockets ground it. One bitstream needs each
 * strap on the same pin in every socket.
 *
 * The UART is `uart.csv`, the netlist's UART wiring: which side drives each
 * of a socket's two UART pins. The design receives on the pin the RP2350B
 * drives and sends on the other, so those too must match on every socket.
 *
 * `load` fails, rather than returning a plan the design cannot use, if a pin
 * changes role between sockets, a pin is in two bundles, a socket lacks a TX
 * or RX bundle on a port, or a strap or UART pin moves between sockets.
 * MeshPlanTest holds the bundles to a hand-written table, the straps to each
 * socket's node and the UART to the side that drives it.
 */
case class MeshPlan(bundles: Seq[MeshBundle], straps: Seq[MeshStrap], uart: Seq[MeshUartPin]) {
  private val byPin: Map[Int, (MeshBundle, String)] =
    bundles.flatMap(b => b.lines.map(l => l.pin -> (b, l.role))).toMap

  /** The bundle a SODIMM pin is in, and its role there. */
  def lineAt(pin: Int): (MeshBundle, String) =
    byPin.getOrElse(pin, sys.error(s"SODIMM $pin is not a mesh pin"))

  /** Carrier signals outside the mesh, by SODIMM pin: the UART -- the design
    * receives on the pin the RP2350B drives -- then the straps in signal order. */
  def carrierPins: Seq[(String, Int)] = {
    def drivenBy(driver: String) = uart.find(_.driver == driver).get.pin
    Seq("uart_tx" -> drivenBy("FPGA"), "uart_rx" -> drivenBy("RP2350B")) ++
      straps.map(s => s.signal -> s.pin).distinct.sortBy(_._1)
  }

  /** The bundle node `node` drives (tx) or receives on, for mesh port `port`. */
  def bundle(node: Int, port: Int, tx: Boolean): MeshBundle =
    bundles.find(_.uses.get(node).contains(MeshUse(port, tx))).get
}

object MeshPlan {
  val dir = "fpga/cluster-carrier-mesh/plan"

  /** A bundle's lines, in the order the design and the XDC list them. */
  val roles = Seq("CLK", "D0", "D1", "D2", "D3", "FR")

  /** The design's strap signals: grounded on every carrier socket ("on the
    * carrier"), and the node number's LSB and MSB, each grounded for a 0. */
  val strapSignals = Seq("strap_carrier_n", "strap_node[0]", "strap_node[1]")

  /** Rows of a CSV file as header -> value maps. Fields may be quoted (the
    * plan's notes hold commas); no field holds a quote. */
  def readCsv(path: String): Seq[Map[String, String]] = {
    def split(line: String): Seq[String] = {
      val out = scala.collection.mutable.ArrayBuffer[String]()
      val f = new StringBuilder
      var quoted = false
      for (c <- line) c match {
        case '"'               => quoted = !quoted
        case ',' if !quoted    => out += f.toString; f.clear()
        case _                 => f += c
      }
      out += f.toString
      out.toSeq
    }
    val src = Source.fromFile(path)
    val lines = try src.getLines().filter(_.trim.nonEmpty).toList finally src.close()
    val header = split(lines.head)
    lines.tail.map { l =>
      val v = split(l)
      require(v.size == header.size, s"$path: ${v.size} fields, header has ${header.size}: $l")
      header.zip(v).toMap
    }
  }

  private def node(socket: String): Int = {
    require(socket.matches("U[1-4]"), s"socket $socket")
    socket.drop(1).toInt - 1
  }
  private def port(link: String): Int = link match {
    case "L1" => 1
    case "L2" => 2
    case "L3a" | "L3b" => 3
    case other => sys.error(s"mesh port $other")
  }

  def load(dir: String = dir): MeshPlan = {
    val functions = readCsv(s"$dir/pads.csv").map(r => r("pin").toInt -> r("function")).toMap
    val pads = readCsv(s"$dir/mesh_pads.csv")

    // (node, port, tx) -> its six pads as (pin, line), in role order
    val groups = pads.groupBy(r => (node(r("socket")), port(r("port")), r("fpga_dir") == "OUT")).map {
      case (key, rows) =>
        val lines = rows.map { r =>
          val role = r("net").split('_').last
          require(roles.contains(role), s"net ${r("net")}: role $role")
          MeshLine(role, r("pin").toInt, r("ball"), r("bank").toInt, functions(r("pin").toInt))
        }.sortBy(l => roles.indexOf(l.role))
        require(lines.map(_.role) == roles, s"$key: lines ${lines.map(_.role)}")
        key -> lines
    }
    require(groups.size == 24, s"${groups.size} socket/port/direction groups, not 24")

    val bundles = groups.groupBy { case (_, lines) => lines.map(_.pin).toSet }.values.map { gs =>
      val lineSets = gs.values.toSeq.distinct
      require(lineSets.size == 1, s"pins ${gs.head._2.map(_.pin)} change role between sockets: $lineSets")
      val uses = gs.keys.toSeq.map { case (n, p, tx) => n -> MeshUse(p, tx) }
      require(uses.map(_._1).distinct.size == uses.size, s"a node uses bundle ${lineSets.head} twice")
      MeshBundle(lineSets.head, uses.toMap)
    }.toSeq.sortBy(_.clk.pin)

    val pins = bundles.flatMap(_.lines.map(_.pin))
    require(pins.distinct.size == pins.size, "a pin is in two bundles")

    val straps = loadStraps(dir)
    for (n <- 0 to 3; sig <- strapSignals)
      require(straps.count(s => s.node == n && s.signal == sig) == 1, s"U${n + 1}: strap $sig")
    for (sig <- strapSignals)
      require(straps.filter(_.signal == sig).map(_.pin).distinct.size == 1, s"strap $sig moves between sockets")
    require(straps.size == 12, s"${straps.size} straps, not 12")
    require(!straps.exists(s => pins.contains(s.pin)), "a strap is on a mesh pin")

    val uart = loadUart(dir)
    for (n <- 0 to 3; d <- Seq("RP2350B", "FPGA"))
      require(uart.count(u => u.node == n && u.driver == d) == 1, s"U${n + 1}: not one UART pin driven by the $d")
    for (d <- Seq("RP2350B", "FPGA"))
      require(uart.filter(_.driver == d).map(_.pin).distinct.size == 1, s"the UART pin the $d drives moves between sockets")
    require(!uart.exists(u => pins.contains(u.pin) || straps.exists(_.pin == u.pin)), "a UART pin is on a mesh or strap pin")
    val plan = MeshPlan(bundles, straps, uart)
    for (n <- 0 to 3; k <- 1 to 3; tx <- Seq(true, false))
      require(bundles.count(_.uses.get(n).contains(MeshUse(k, tx))) == 1, s"node $n port $k tx=$tx")
    plan
  }

  def loadStraps(dir: String = dir): Seq[MeshStrap] =
    readCsv(s"$dir/straps.csv").map { r =>
      require(Seq("GND", "open").contains(r("tie")), s"strap tie ${r("tie")}")
      MeshStrap(node(r("socket")), r("pin").toInt, r("signal"), r("tie"))
    }

  def loadUart(dir: String = dir): Seq[MeshUartPin] =
    readCsv(s"$dir/uart.csv").map { r =>
      require(Seq("RP2350B", "FPGA").contains(r("driver")), s"UART driver ${r("driver")}")
      MeshUartPin(node(r("socket")), r("pin").toInt, r("net"), r("driver"))
    }

  def loadNets(dir: String = dir): Seq[MeshNet] =
    readCsv(s"$dir/mesh_nets.csv").map { r =>
      MeshNet(r("net"), node(r("src")), r("src_pin").toInt, node(r("dst")), r("dst_pin").toInt)
    }
}
