package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth._

/** Xilinx 7-series IDDR (UG471), SAME_EDGE_PIPELINED: D is sampled on the
  * rising edge of C and on the falling edge after it, and the pair comes out
  * together on the next rising edge, Q1 the rising-edge sample. Tests supply a
  * behavioural model, spinalhdl/src/test/resources/xilinx_iddr_model.v. */
class XilinxIddr extends BlackBox {
  addGeneric("DDR_CLK_EDGE", "SAME_EDGE_PIPELINED")
  addGeneric("INIT_Q1", False)
  addGeneric("INIT_Q2", False)
  addGeneric("SRTYPE", "SYNC")
  val io = new Bundle {
    val C  = in Bool()
    val CE = in Bool()
    val D  = in Bool()
    val R  = in Bool()
    val S  = in Bool()
    val Q1 = out Bool()
    val Q2 = out Bool()
  }
  noIoPrefix()
  setDefinitionName("IDDR")
}

/** Xilinx 7-series IDELAYE2 (UG471) in FIXED mode: the pad's input delayed by
  * `taps` steps of 1/(32 x 2 x REFCLK) -- 78 ps at 200 MHz -- on top of the
  * cell's own delay. It needs an IDELAYCTRL in its bank (XilinxIdelayctrl).
  * Tests supply a pass-through model, xilinx_idelaye2_model.v. */
class XilinxIdelaye2(taps: Int) extends BlackBox {
  require(taps >= 0 && taps <= 31, s"IDELAYE2 takes 0 to 31 taps, not $taps")
  addGeneric("CINVCTRL_SEL", "FALSE")
  addGeneric("DELAY_SRC", "IDATAIN")
  addGeneric("HIGH_PERFORMANCE_MODE", "TRUE")
  addGeneric("IDELAY_TYPE", "FIXED")
  addGeneric("IDELAY_VALUE", taps)
  addGeneric("PIPE_SEL", "FALSE")
  addGeneric("REFCLK_FREQUENCY", 200.0)
  addGeneric("SIGNAL_PATTERN", "DATA")
  val io = new Bundle {
    val C           = in Bool()
    val CE          = in Bool()
    val CINVCTRL    = in Bool()
    val CNTVALUEIN  = in Bits(5 bits)
    val DATAIN      = in Bool()
    val IDATAIN     = in Bool()
    val INC         = in Bool()
    val LD          = in Bool()
    val LDPIPEEN    = in Bool()
    val REGRST      = in Bool()
    val CNTVALUEOUT = out Bits(5 bits)
    val DATAOUT     = out Bool()
  }
  noIoPrefix()
  setDefinitionName("IDELAYE2")
}

/** Xilinx 7-series IDELAYCTRL (UG471): calibrates the IDELAYE2 taps in its
  * clock region against REFCLK, 200 MHz. RST must be pulsed once REFCLK is
  * stable; RDY rises when the taps are calibrated. Vivado replicates one into
  * every region that has IDELAYE2s. */
class XilinxIdelayctrl extends BlackBox {
  val io = new Bundle {
    val REFCLK = in Bool()
    val RST    = in Bool()
    val RDY    = out Bool()
  }
  noIoPrefix()
  setDefinitionName("IDELAYCTRL")
}

/** Xilinx 7-series BUFIO (UG472): a clock-capable pin's clock to the I/O
  * cells (ILOGIC, OLOGIC) of its own bank only, with the least delay any
  * clock buffer has. */
class XilinxBufio extends BlackBox {
  val io = new Bundle {
    val I = in Bool()
    val O = out Bool()
  }
  noIoPrefix()
  setDefinitionName("BUFIO")
}

/** Xilinx 7-series BUFR (UG472), undivided: a clock-capable pin's clock to
  * the logic of its own clock region, built to take data from I/O cells on
  * the BUFIO of the same pin. */
class XilinxBufr extends BlackBox {
  addGeneric("BUFR_DIVIDE", "BYPASS")
  addGeneric("SIM_DEVICE", "7SERIES")
  val io = new Bundle {
    val I   = in Bool()
    val CE  = in Bool()
    val CLR = in Bool()
    val O   = out Bool()
  }
  noIoPrefix()
  setDefinitionName("BUFR")
}

object Rgmii1000 {
  /** The TX clock: one byte a cycle at 1000 Mb/s. */
  val TxClockMhz = 125
}

/**
 * RGMII at 1000 Mb/s in front of a MAC in its 8-bit (GMII) mode -- the
 * B50612D PHYs on the Colorlight i9+ module. Rgmii100 is the 100 Mb/s one.
 *
 * At 1000 Mb/s RGMII moves a byte per clock on four wires: bits 3:0 on the
 * rising edge, 7:4 on the falling one. CTL carries the enable on the rising
 * edge and enable XOR error on the falling one.
 *
 * The B50612Ds run RGMII-ID: the PHY delays both clocks itself (the clock
 * delay bits read on, java/apps/Small/src/test/PhyRegs.java, 2026-11-10).
 *
 * TX: everything on the 125 MHz TX clock. The inter-frame gap, TXD and TX_EN
 * registered, then an ODDR per pin (SAME_EDGE): TXD[3:0] for the high half of
 * the clock, TXD[7:4] for the low half, and TX_CTL TX_EN on both (TX_ER is
 * never sent). TXC's ODDR is (1, 0), so TXC's edges come WITH the data's, and
 * the PHY's own delay of about 2 ns puts its sampling mid-nibble. A shift on
 * the FPGA side as well would add to the PHY's and move it back towards the
 * transitions.
 *
 * RX: `rxCd` is RXC. Every RX pin goes through an IDELAYE2 (FIXED,
 * `rxDelayTaps`) into an IDDR (SAME_EDGE_PIPELINED) on RXC -- `rxIoCd`'s, RXC
 * through a BUFIO, where the top has one, else rxCd's: Q1 is the rising-
 * edge nibble, bits 3:0, Q2 the falling-edge one, 7:4; on RX_CTL, Q1 is RX_DV
 * and Q1 ^ Q2 is RX_ER. The PHY centres RXC in each nibble at its pins. The
 * delay moves the data so that RXC still sits mid-nibble at the IDDR after
 * RXC's own way through its clock buffer. How many taps that takes is for the
 * build's timing report (the XDC constrains the PHY's window), not a guess.
 * The IDELAYE2s need an IDELAYCTRL with a 200 MHz reference in their bank; the
 * top-level wiring makes it, and the BUFIO and the BUFR that is rxCd's clock.
 *
 * WHY A BUFIO. Through a BUFG, RXC reached the IDDRs about 2 ns after the data:
 * 28 taps to make up, and Vivado's per-tap uncertainty (87.5 ps max against
 * 68.5 min) left setup + hold 0.2 to 0.7 ns short of the PHY's 2.4 ns window at
 * every count (first build, 2026-11-10). A BUFIO is the shortest clock path
 * there is, and it reaches every RX pin: all are in bank 35 with RXC (L3).
 */
case class Rgmii1000(txCd: ClockDomain, rxCd: ClockDomain, rxDelayTaps: Int = 0,
                     rxIoCd: Option[ClockDomain] = None) extends Component {
  val io = new Bundle {
    val phy   = slave(PhyIo(PhyParameter(txDataWidth = 8, rxDataWidth = 8)))
    val txc   = out Bool()
    val txd   = out Bits(4 bits)
    val txCtl = out Bool()
    val rxd   = in Bits(4 bits)
    val rxCtl = in Bool()
  }
  io.phy.colision := False
  io.phy.busy := False

  val tx = new ClockingArea(txCd) {
    val interframe = MacTxInterFrame(8)
    interframe.io.input << io.phy.tx
    val data = RegNext(interframe.io.output.fragment.data) init(0)
    val en = RegNext(interframe.io.output.valid) init(False)
  }

  def oddr(d1: Bool, d2: Bool): Bool = {
    val cell = new XilinxOddr
    cell.io.C := txCd.readClockWire
    cell.io.CE := True
    cell.io.D1 := d1
    cell.io.D2 := d2
    cell.io.R := False
    cell.io.S := False
    cell.io.Q
  }
  for (i <- 0 until 4) io.txd(i) := oddr(tx.data(i), tx.data(i + 4))
  io.txCtl := oddr(tx.en, tx.en)
  io.txc := oddr(True, False)

  /** The IDDRs' clock: RXC through the top's BUFIO, or rxCd's own. */
  val ioClock = Bool()
  ioClock := rxIoCd.getOrElse(rxCd).readClockWire

  /** One RX pin: its delay, then its IDDR on RXC; (rising, falling) samples. */
  def iddr(pad: Bool): (Bool, Bool) = {
    val delay = new XilinxIdelaye2(rxDelayTaps)
    delay.io.IDATAIN := pad
    delay.io.C := False
    delay.io.CE := False
    delay.io.CINVCTRL := False
    delay.io.CNTVALUEIN := 0
    delay.io.DATAIN := False
    delay.io.INC := False
    delay.io.LD := False
    delay.io.LDPIPEEN := False
    delay.io.REGRST := False
    val cell = new XilinxIddr
    cell.io.C := ioClock
    cell.io.CE := True
    cell.io.D := delay.io.DATAOUT
    cell.io.R := False
    cell.io.S := False
    (cell.io.Q1, cell.io.Q2)
  }
  val rxdCells = (0 until 4).map(i => iddr(io.rxd(i)))
  val rxCtlCell = iddr(io.rxCtl)

  val rx = new ClockingArea(rxCd) {
    val low = Bits(4 bits)
    val high = Bits(4 bits)
    for (i <- 0 until 4) {
      low(i) := rxdCells(i)._1
      high(i) := rxdCells(i)._2
    }
    val unbuffered = Flow(PhyRx(8))
    unbuffered.valid := rxCtlCell._1
    unbuffered.data := high ## low
    unbuffered.error := rxCtlCell._1 ^ rxCtlCell._2

    // As the GMII wiring frames it (DeviceTopWiring): `last` on the byte
    // after which RX_DV drops.
    val captured = unbuffered.stage()
    val buffered = captured.stage()
    val frame = Flow(Fragment(PhyRx(8)))
    frame.valid := buffered.valid
    frame.fragment := buffered.payload
    frame.last := !captured.valid && buffered.valid
    io.phy.rx << frame.toStream
  }
}
