package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import hw._
import jop.io.{MeshRxPrbs, MeshRxStatus, MeshTxPrbs}

/** Xilinx 7-series global clock buffer (UG472). */
class XilinxBufg extends BlackBox {
  val io = new Bundle {
    val I = in Bool()
    val O = out Bool()
  }
  noIoPrefix()
  setDefinitionName("BUFG")
}

/**
 * The JOP cluster carrier's mesh BUILD CHECK and link test (handoff test plan,
 * steps 1 and 3): every mesh port's TX runs PRBS7, every RX checks it, and a
 * report goes out on the UART once a second.
 *
 * One design, four builds: `nodeId` 0-3 is socket U1-U4, and each build reads
 * that socket's constraints, fpga/cluster-carrier-mesh/constraints/
 * carrier_U<n>.xdc. The port names are the XDCs' -- the same on every socket.
 *
 * The link runs at 50 MHz SDR, the handoff's bring-up rate: the design's PLL
 * makes it from the module's 25 MHz oscillator, and each RX clock is the
 * peer's forwarded clock, through its own BUFG (handoff section 4: a BUFR or
 * BUFIO would not reach data in another bank, as on U1's P2 and P3).
 *
 * THE REPORT, one line a second at `baud`:
 *
 *   N0 P1 L1 C05F6 E00000000 W000004C4 P2 ... P3 ...
 *
 * L the last window was clean (no error, and a 1 seen); C the RX clock's
 * activity, its 32768-cycle toggles in the second (05F6 at 50 MHz, 0000 with
 * no peer); E bit errors since the port first locked; W 2^20-cycle windows
 * since then, so the bit error rate is E / (W * 2^20 * 5).
 */
case class MeshPrbsTop(nodeId: Int, baud: Int = 115200) extends Component {
  require(nodeId >= 0 && nodeId < 4, "the carrier has four sockets, nodes 0-3")

  case class MeshTxPins() extends Bundle {
    val clk = out Bool()
    val d   = out Bits(4 bits)
    val fr  = out Bool()
  }
  case class MeshRxPins() extends Bundle {
    val clk = in Bool()
    val d   = in Bits(4 bits)
    val fr  = in Bool()
  }

  val io = new Bundle {
    val clk25   = in Bool()
    val led_n   = out Bool()
    val uart_tx = out Bool()
    val uart_rx = in Bool()
  }
  noIoPrefix()
  // p<k>_tx_clk, p<k>_tx_d, ... -- the XDCs' names, port k = 1..3
  val tx = (1 to 3).map(k => MeshTxPins().setName(s"p${k}_tx"))
  val rx = (1 to 3).map(k => MeshRxPins().setName(s"p${k}_rx"))

  val pll = jop.system.pll.Pll.build(MeshPrbsTop.pll, Board.ColorlightI9Plus, io.clk25)
  val sysClk = pll.systemClk.get

  val sysBoot = ClockDomain(sysClk, frequency = FixedFrequency(MeshPrbsTop.linkMhz MHz),
    config = ClockDomainConfig(resetKind = BOOT))
  val sysReset = new ClockingArea(sysBoot) {
    val r = RegNext(RegNext(!pll.locked) init True) init True
  }
  val sysCd = ClockDomain(sysClk, sysReset.r, frequency = FixedFrequency(MeshPrbsTop.linkMhz MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))

  // ---- TX: the PRBS sources, in the link clock ------------------------------
  val txArea = new ClockingArea(sysCd) {
    val sources = (1 to 3).map(k => MeshTxPrbs(seed = 1 + 16 * nodeId + k))
  }
  for (k <- 0 until 3) {
    tx(k).clk := txArea.sources(k).io.clk
    tx(k).d   := txArea.sources(k).io.d
    tx(k).fr  := txArea.sources(k).io.fr
  }

  // ---- RX: each port in its own received clock ------------------------------
  val rxPorts = for (k <- 0 until 3) yield new Area {
    val bufg = new XilinxBufg
    bufg.io.I := rx(k).clk
    val cd = ClockDomain(bufg.io.O, config = ClockDomainConfig(resetKind = BOOT))
    val inRx = new ClockingArea(cd) {
      val checker = MeshRxPrbs()
      checker.io.d  := rx(k).d
      checker.io.fr := rx(k).fr
      val activity = Reg(UInt(16 bits)) init 0
      activity := activity + 1
    }
    // the snapshot crosses once a window, by toggle; the clock's activity bit
    // is synchronised in the report area, which runs in the system domain
    val status = FlowCCByToggle(inRx.checker.io.status, inputClock = cd, outputClock = sysCd)
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
      val sync = BufferCC(rxPorts(k).inRx.activity.msb, init = False)
      p.lastToggle := sync
      when(sync =/= p.lastToggle) { p.toggles := p.toggles + 1 }
      when(rxPorts(k).status.valid) { p.status := rxPorts(k).status.payload }
      when(tick.willOverflow) {
        p.toggleCount := p.toggles
        p.toggles := 0
      }
    }

    // One line, built at elaboration as a list of characters: each either a
    // constant or a hex digit of a field latched when the line starts.
    val snap = ports.map(p => new Area {
      val lock = Reg(Bool()) init False
      val clk  = Reg(UInt(16 bits)) init 0
      val err  = Reg(UInt(32 bits)) init 0
      val win  = Reg(UInt(32 bits)) init 0
    })
    def lit(s: String): Seq[Bits] = s.map(c => B(c.toInt, 8 bits))
    def hex(v: UInt, digits: Int): Seq[Bits] = (digits - 1 to 0 by -1).map { i =>
      val n = v(4 * i + 3 downto 4 * i)
      Mux(n < 10, (n + U('0'.toInt, 8 bits)).asBits, (n - 10 + U('A'.toInt, 8 bits)).asBits).resize(8)
    }
    val chars = lit(s"N$nodeId") ++ (0 until 3).flatMap { k =>
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
  def cfgName(nodeId: Int) = s"meshPrbsU${nodeId + 1}"
}

/** RTL, clk_wiz definition and summary for each socket, or for the nodes
  * given: `sbt "runMain jop.system.MeshPrbsBuild [0 1 2 3]"`. */
object MeshPrbsBuild extends App {
  val nodes = if (args.nonEmpty) args.toSeq.map(_.toInt) else 0 to 3
  val clk25 = Board.ColorlightI9Plus.clocks.find(_.name == MeshPrbsTop.pll.reference).get
  for (n <- nodes) {
    val name = MeshPrbsTop.cfgName(n)
    SpinalConfig(
      mode = Verilog,
      targetDirectory = jop.generate.StandaloneBuild.rtlDir(name),
      defaultClockDomainFrequency = FixedFrequency(25 MHz)
    ).generate(MeshPrbsTop(n))
    hw.XilinxClkWiz.generate(MeshPrbsTop.pll.spec(clk25), MeshPrbsTop.pll.module,
      jop.generate.BuildLayout.default.ipDir(name, Seq.empty))
    jop.generate.StandaloneBuild.summary(name, "MeshPrbsTop",
      board = s"colorlight-i9plus in JOP cluster carrier socket U${n + 1}", fpga = "XC7A50T",
      clkMhz = MeshPrbsTop.linkMhz, uartBaud = Some(115200))
  }
}
