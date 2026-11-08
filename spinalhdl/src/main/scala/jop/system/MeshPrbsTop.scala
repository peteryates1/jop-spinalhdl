package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import hw._
import jop.io.{MeshPlan, MeshPrbsCore, MeshRxStatus}

/** Xilinx 7-series bidirectional I/O buffer (UG471): drives the pad from I
  * while T is low, and always reads it on O. */
class XilinxIobuf extends BlackBox {
  val io = new Bundle {
    val I  = in Bool()
    val T  = in Bool()
    val O  = out Bool()
    val IO = inout(Analog(Bool()))
  }
  noIoPrefix()
  setDefinitionName("IOBUF")
}

/** Xilinx 7-series tri-state output buffer (UG471): drives O while T is low. */
class XilinxObuft extends BlackBox {
  val io = new Bundle {
    val I = in Bool()
    val T = in Bool()
    val O = out Bool()
  }
  noIoPrefix()
  setDefinitionName("OBUFT")
}

/**
 * The JOP cluster carrier's mesh BUILD CHECK and link test (handoff test plan,
 * steps 1 and 3), as ONE bitstream for all four sockets (status item 178).
 *
 * The pins are the plan's ten bundles (jop.io.MeshPlan), named by their clock
 * pin -- mesh58_clk, mesh58_d0 .. mesh58_d3, mesh58_fr -- not by port: which
 * port a bundle is, and which way it runs, depends on the socket. The design
 * reads the socket straps (plan/straps.csv) and then drives that socket's TX
 * bundles only. Until then, and for good on any board that does not ground the
 * presence strap, every mesh pin is an input.
 *
 * Each pad gets the buffer its bundle needs: an IOBUF where some sockets drive
 * the bundle and others receive on it, an OBUFT where every socket that uses
 * it drives it, a plain input where every socket receives on it.
 *
 * The link runs at 50 MHz SDR, the handoff's bring-up rate: the design's PLL
 * makes it from the module's 25 MHz oscillator, and each RX clock is the
 * peer's forwarded clock, through its own BUFG (a BUFR or BUFIO would not
 * reach data in another bank, as on U1's P2 and P3).
 *
 * THE REPORT, one line a second at `baud`:
 *
 *   N0 P1 L1 C05F6 E00000000 W000004C4 P2 ... P3 ...
 *
 * N the node the straps say, or '-' on a board that is not the carrier, or '?'
 * before they are read; L the last window was clean (no error, and a 1 seen);
 * C the RX clock's activity, its 32768-cycle toggles in the second (05F6 at 50
 * MHz, 0000 with no peer); E bit errors since the port first locked; W 2^20-
 * cycle windows since then, so the bit error rate is E / (W * 2^20 * 5).
 */
case class MeshPrbsTop(plan: MeshPlan, baud: Int = 115200) extends Component {
  val io = new Bundle {
    val clk25           = in Bool()
    val led_n           = out Bool()
    val uart_tx         = out Bool()
    val uart_rx         = in Bool()
    val strap_carrier_n = in Bool()
    val strap_node      = in Bits(2 bits)
  }
  noIoPrefix()

  val pll = jop.system.pll.Pll.build(MeshPrbsTop.pll, Board.ColorlightI9Plus, io.clk25)
  val sysClk = pll.systemClk.get

  val sysBoot = ClockDomain(sysClk, frequency = FixedFrequency(MeshPrbsTop.linkMhz MHz),
    config = ClockDomainConfig(resetKind = BOOT))
  val sysReset = new ClockingArea(sysBoot) {
    val r = RegNext(RegNext(!pll.locked) init True) init True
  }
  val sysCd = ClockDomain(sysClk, sysReset.r, frequency = FixedFrequency(MeshPrbsTop.linkMhz MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))

  // 1 ms for the straps to settle: the FPGA's pull-ups are weak
  val core = sysCd(MeshPrbsCore(plan, settleCycles = MeshPrbsTop.linkMhz * 1000))
  core.io.strapCarrierN := io.strap_carrier_n
  core.io.strapNode := io.strap_node

  // ---- the pads -------------------------------------------------------------
  for ((b, i) <- plan.bundles.zipWithIndex; line <- b.lines) {
    val pads = core.io.bundles(i)
    val name = b.portName(line)
    if (b.canTx && b.canRx) {
      val pad = inout(Analog(Bool())).setName(name)
      val buf = new XilinxIobuf
      buf.io.IO <> pad
      buf.io.I := pads.tx.line(line.role)
      buf.io.T := !pads.txEn
      pads.rx.line(line.role) := buf.io.O
    } else if (b.canTx) {
      val pad = out(Bool()).setName(name)
      val buf = new XilinxObuft
      buf.io.I := pads.tx.line(line.role)
      buf.io.T := !pads.txEn
      pad := buf.io.O
      pads.rx.line(line.role) := False
    } else {
      val pad = in(Bool()).setName(name)
      pads.rx.line(line.role) := pad
    }
  }

  // ---- Report ---------------------------------------------------------------
  val report = new ClockingArea(sysCd) {
    val tick = CounterFreeRun(MeshPrbsTop.linkMhz * 1000000)
    val led = Reg(Bool()) init False
    when(tick.value === 0 || tick.value === MeshPrbsTop.linkMhz * 500000) { led := !led }

    case class Port() extends Area {
      val status = Reg(MeshRxStatus())
      status.lock init False
      status.everLocked init False
      status.errors init 0
      status.windows init 0
      val toggles, toggleCount = Reg(UInt(16 bits)) init 0
      val lastToggle = Reg(Bool()) init False
    }
    val ports = Seq.fill(3)(Port())
    for (k <- 0 until 3) {
      val p = ports(k)
      val active = core.io.ports(k).active
      p.lastToggle := active
      when(active =/= p.lastToggle) { p.toggles := p.toggles + 1 }
      when(core.io.ports(k).status.valid) { p.status := core.io.ports(k).status.payload }
      when(tick.willOverflow) {
        p.toggleCount := p.toggles
        p.toggles := 0
      }
    }

    def lit(s: String): Seq[Bits] = s.map(c => B(c.toInt, 8 bits))
    def hex(v: UInt, digits: Int): Seq[Bits] = (digits - 1 to 0 by -1).map { i =>
      val n = v(4 * i + 3 downto 4 * i)
      Mux(n < 10, (n + U('0'.toInt, 8 bits)).asBits, (n - 10 + U('A'.toInt, 8 bits)).asBits).resize(8)
    }
    val nodeNow = Bits(8 bits)
    nodeNow := lit("?").head
    when(core.io.valid) {
      nodeNow := Mux(core.io.onCarrier, hex(core.io.nodeId.resize(4), 1).head, lit("-").head)
    }

    // One line, built at elaboration as a list of characters: each either a
    // constant or a field latched when the line starts.
    val snap = ports.map(p => new Area {
      val lock = Reg(Bool()) init False
      val clk  = Reg(UInt(16 bits)) init 0
      val err  = Reg(UInt(32 bits)) init 0
      val win  = Reg(UInt(32 bits)) init 0
    })
    val snapNode = Reg(Bits(8 bits)) init lit("?").head
    val chars = lit("N") ++ Seq(snapNode) ++ (0 until 3).flatMap { k =>
      lit(s" P${k + 1} L") ++ hex(snap(k).lock.asUInt.resize(4), 1) ++
        lit(" C") ++ hex(snap(k).clk, 4) ++ lit(" E") ++ hex(snap(k).err, 8) ++
        lit(" W") ++ hex(snap(k).win, 8)
    } ++ lit("\r\n")
    val line = Vec(chars)

    val uart = new UartCtrl(UartCtrlGenerics())
    uart.io.config.setClockDivider(baud Hz)
    uart.io.config.frame.dataLength := 7  // 8 bits
    uart.io.config.frame.parity := UartParityType.NONE
    uart.io.config.frame.stop := UartStopType.ONE
    uart.io.writeBreak := False
    uart.io.uart.rxd := io.uart_rx
    uart.io.read.ready := True

    val sending = Reg(Bool()) init False
    val idx = Reg(UInt(log2Up(chars.size) bits)) init 0
    when(tick.willOverflow && !sending) {
      sending := True
      idx := 0
      snapNode := nodeNow
      for (k <- 0 until 3) {
        snap(k).lock := ports(k).status.lock
        snap(k).clk  := ports(k).toggleCount
        snap(k).err  := ports(k).status.errors
        snap(k).win  := ports(k).status.windows
      }
    }
    uart.io.write.valid := sending
    uart.io.write.payload := line(idx)
    when(uart.io.write.fire) {
      idx := idx + 1
      when(idx === chars.size - 1) { sending := False }
    }
  }
  io.uart_tx := report.uart.io.uart.txd
  io.led_n   := report.led
}

object MeshPrbsTop {
  val linkMhz = 50
  /** The design's PLL (status item 173): the module's 25 MHz to the link clock. */
  val pll = PllDefinition("CLK25M", Seq(PllOutput(PllRole.System, linkMhz)), module = "mesh_clk", instance = "pll")
  val cfgName = "meshPrbs"

  /**
   * The design's constraints, generated from the plan: every pin, the clocks,
   * the input-delay template, and the clock groups and false paths. The groups
   * are the DESIGN's facts -- its crossings are FlowCCByToggle and BufferCC --
   * which is why they are made here, with the RTL, and not in the plan.
   *
   * `balls` maps a SODIMM pin to its i9+ ball and bank (plan/pads.csv), for the
   * carrier's own pins.
   */
  def xdc(plan: MeshPlan, balls: Map[Int, (String, Int)]): String = {
    val sb = new StringBuilder
    def pin(port: String, ball: String, props: String, note: String): Unit =
      sb ++= s"set_property -dict {PACKAGE_PIN $ball IOSTANDARD LVCMOS33$props} [get_ports {$port}]  ;# $note\n"
    val socket = (n: Int) => s"U${n + 1}"

    sb ++= "## JOP cluster carrier: the mesh PRBS test, ONE design for all four sockets.\n"
    sb ++= s"## GENERATED by jop.system.MeshPrbsBuild from ${MeshPlan.dir}/mesh_pads.csv\n"
    sb ++= "## and pads.csv -- edit the plan or the generator, not this file.\n"
    sb ++= "## ASSUMPTION: bank VCCO = 3.3 V (not yet measured).\n\n"

    sb ++= "## The module\n"
    val osc = Board.ColorlightI9Plus.clocks.find(_.name == pll.reference).get
    pin("clk25", osc.pin, "", "the 25 MHz oscillator")
    sb ++= "create_clock -name clk25 -period 40.000 [get_ports clk25]\n"
    pin("led_n", "A18", "", "LED D2")

    sb ++= "\n## The carrier: the UART to the RP2350B, and the socket straps (grounded = 0)\n"
    for ((port, sodimm) <- plan.carrierPins) {
      val (ball, bank) = balls(sodimm)
      pin(port, ball, if (port.startsWith("strap")) " PULLTYPE PULLUP" else "", s"SODIMM $sodimm, bank $bank")
    }

    for (b <- plan.bundles) {
      val uses = b.uses.toSeq.sortBy(_._1).map { case (n, u) => s"${socket(n)} P${u.port} ${if (u.tx) "TX" else "RX"}" }
      sb ++= s"\n## ${b.name}: ${uses.mkString(", ")}\n"
      for (l <- b.lines)
        pin(b.portName(l), l.ball, (if (b.canTx) " DRIVE 8 SLEW SLOW" else "") + " PULLTYPE PULLDOWN",
          s"SODIMM ${l.pin}, bank ${l.bank}, ${l.role}")
      if (b.canRx) {
        val clk = b.portName(b.clk)
        val data = b.data.map(b.portName).mkString(" ")
        sb ++= s"create_clock -name $clk -period 20.000 [get_ports $clk]\n"
        sb ++= s"set_input_delay -clock [get_clocks $clk] -clock_fall -max  1.500 [get_ports {$data}]\n"
        sb ++= s"set_input_delay -clock [get_clocks $clk] -clock_fall -min -1.500 [get_ports {$data}] -add_delay\n"
      }
    }

    sb ++= "\n## Every RX clock is a peer's oscillator: asynchronous to this module's and to\n"
    sb ++= "## each other. The design crosses each by FlowCCByToggle and BufferCC.\n"
    sb ++= "set_clock_groups -asynchronous -group [get_clocks -include_generated_clocks clk25]"
    for (b <- plan.bundles if b.canRx) sb ++= s" \\\n  -group [get_clocks ${b.portName(b.clk)}]"
    sb ++= "\n\n## Slow, or static after start-up\n"
    sb ++= "set_false_path -from [get_ports {uart_rx strap_carrier_n strap_node[*]}]\n"
    sb ++= "set_false_path -to [get_ports {uart_tx led_n}]\n"

    sb ++= "\n## Bitstream: SPI flash boot\n"
    sb ++= "set_property CONFIG_VOLTAGE 3.3 [current_design]\n"
    sb ++= "set_property CFGBVS VCCO [current_design]\n"
    sb ++= "set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]\n"
    sb.toString
  }
}

/** RTL, constraints, clk_wiz definition and summary:
  * `sbt "runMain jop.system.MeshPrbsBuild"`. */
object MeshPrbsBuild extends App {
  val plan = MeshPlan.load()
  val name = MeshPrbsTop.cfgName
  val rtlDir = jop.generate.StandaloneBuild.rtlDir(name)
  val clk25 = Board.ColorlightI9Plus.clocks.find(_.name == MeshPrbsTop.pll.reference).get
  SpinalConfig(
    mode = Verilog,
    targetDirectory = rtlDir,
    defaultClockDomainFrequency = FixedFrequency(25 MHz)
  ).generate(MeshPrbsTop(plan))
  hw.XilinxClkWiz.generate(MeshPrbsTop.pll.spec(clk25), MeshPrbsTop.pll.module,
    jop.generate.BuildLayout.default.ipDir(name, Seq.empty))

  val balls = MeshPlan.readCsv(s"${MeshPlan.dir}/pads.csv").map(r => r("pin").toInt -> (r("ball"), r("bank").toInt)).toMap
  val xdc = new java.io.File(s"$rtlDir/MeshPrbsTop.xdc")
  val w = new java.io.PrintWriter(xdc)
  try w.print(MeshPrbsTop.xdc(plan, balls)) finally w.close()
  println(s"Wrote ${xdc.getPath}")

  jop.generate.StandaloneBuild.summary(name, "MeshPrbsTop",
    board = "colorlight-i9plus in any JOP cluster carrier socket", fpga = "XC7A50T",
    clkMhz = MeshPrbsTop.linkMhz, uartBaud = Some(115200))
}
