package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth._

/** Xilinx 7-series ODDR (UG471), SAME_EDGE: D1 and D2 are both taken on the
  * rising edge of C; Q shows D1 for the high half of that cycle and D2 for the
  * low half. Tests supply a behavioural model,
  * spinalhdl/src/test/resources/xilinx_oddr_model.v. */
class XilinxOddr extends BlackBox {
  addGeneric("DDR_CLK_EDGE", "SAME_EDGE")
  addGeneric("INIT", False)
  addGeneric("SRTYPE", "SYNC")
  val io = new Bundle {
    val C  = in Bool()
    val CE = in Bool()
    val D1 = in Bool()
    val D2 = in Bool()
    val R  = in Bool()
    val S  = in Bool()
    val Q  = out Bool()
  }
  noIoPrefix()
  setDefinitionName("ODDR")
}

/** Lattice ECP5 ODDRX1F: D0 and D1 are both taken on the rising edge of SCLK;
  * Q shows D0 for the high half of that cycle and D1 for the low half -- the
  * Xilinx ODDR's SAME_EDGE timing, D1/D2 renamed D0/D1. yosys maps it to the
  * I/O cell; tests supply a behavioural model,
  * spinalhdl/src/test/resources/lattice_oddrx1f_model.v. */
class LatticeOddrx1f extends BlackBox {
  val io = new Bundle {
    val D0   = in Bool()
    val D1   = in Bool()
    val SCLK = in Bool()
    val RST  = in Bool()
    val Q    = out Bool()
  }
  noIoPrefix()
  setDefinitionName("ODDRX1F")
}

object Rgmii100 {
  /** The clock `txDomain` is built on: twice the 25 MHz nibble rate. */
  val TxClockMhz = 50

  /**
   * The MAC's TX clock domain: `raw`, the 50 MHz Ethernet clock, with a clock
   * enable that is high on every other cycle, so the MAC moves one nibble per
   * 40 ns. Rgmii100 reads the same enable to place TXC's edges. The enable
   * register is created in the component that calls this, in `raw`.
   */
  def txDomain(raw: ClockDomain): ClockDomain = {
    val phase = new ClockingArea(raw) {
      val enable = RegInit(False)
      enable := !enable
    }
    raw.copy(clockEnable = phase.enable)
  }
}

/**
 * RGMII at 100 Mb/s in front of a MAC in its 4-bit (MII) mode -- the B50612D
 * PHYs on the Colorlight i9+ module. Gigabit would need DDR data and is not
 * done here: the driver must not advertise 1000BASE-T (Const.ETH_GIGABIT).
 *
 * At 100 Mb/s RGMII is MII with two differences. TXC comes from the MAC, not
 * the PHY. And TX_CTL is sampled on BOTH edges of TXC -- TX_EN on the rising
 * edge, TX_EN ^ TX_ER on the falling one -- so neither edge may sit near a
 * change of TX_CTL, or the PHY sees TX_ER. A first hardware design with TXC on
 * a data transition sent frames nothing on the LAN accepted. TXC shifted 90
 * degrees, with the data in IOB flops, worked
 * (2026-10-11, fpga/bringup/colorlight-db-i9plus-eth).
 *
 * TX: everything runs on one 50 MHz clock. `txCd` is that clock enabled every
 * other cycle (Rgmii100.txDomain), so TXD/TX_CTL change only on the enabled
 * edges. TXC comes from an ODDR on the raw clock with (D1, D2) = (!enable,
 * enable): low for the first 10 ns after a data change, high from 10 to 30 ns,
 * low again to 40 ns. Both edges are 10 ns from any change. No second clock is
 * needed, and so no change to the clock IP the Wukong shares.
 *
 * ECP5 (`vendor` Lattice, the Colorlight i5): there are no IOB flops to pack
 * TXD/TX_CTL into, and the ODDRX1F's own latency is not in anything we have
 * (yosys ships no model of the cell). So EVERY TX pin leaves an ODDRX1F on the
 * raw clock, and whatever that latency is, TXC and the data share it. The data
 * cells take the txCd registers one raw cycle after those change, so TXC's
 * pattern is the Xilinx one inverted, (D0, D1) = (enable, !enable): high from
 * 10 ns after a (delayed) data change to 10 ns before the next, both edges 10
 * ns clear. The i5's RGMII bring-up ran with every TX pin through ODDRX1F the
 * same way (fpga/bringup/colorlight-db-i5-eth, 2026-10-27).
 *
 * RX: `rxCd` is RXC (25 MHz at 100 Mb/s). RXD/RX_CTL are sampled on its
 * FALLING edge, mid-nibble whether or not the PHY delays RXC; this is how they
 * were verified byte-exact on hardware. RX_CTL there is RX_DV ^ RX_ER, which
 * is RX_DV for any frame without a receive error, and an errored frame fails
 * its CRC.
 */
case class Rgmii100(txCd: ClockDomain, rxCd: ClockDomain,
                    vendor: jop.config.Manufacturer = jop.config.Manufacturer.Xilinx) extends Component {
  val io = new Bundle {
    val phy   = slave(PhyIo(PhyParameter(txDataWidth = 4, rxDataWidth = 4)))
    val txc   = out Bool()
    val txd   = out Bits(4 bits)
    val txCtl = out Bool()
    val rxd   = in Bits(4 bits)
    val rxCtl = in Bool()
  }
  io.phy.colision := False
  io.phy.busy := False

  val tx = new ClockingArea(txCd) {
    val interframe = MacTxInterFrame(4)
    interframe.io.input << io.phy.tx
    val txd = RegNext(interframe.io.output.fragment.data) init(0)
    val txCtl = RegNext(interframe.io.output.valid) init(False)
  }

  vendor match {
    case jop.config.Manufacturer.Lattice =>
      def cell(d0: Bool, d1: Bool): Bool = {
        val c = new LatticeOddrx1f
        c.io.SCLK := txCd.readClockWire
        c.io.RST := False
        c.io.D0 := d0
        c.io.D1 := d1
        c.io.Q
      }
      io.txc := cell(txCd.readClockEnableWire, !txCd.readClockEnableWire)
      io.txCtl := cell(tx.txCtl, tx.txCtl)
      for (i <- 0 until 4) io.txd(i) := cell(tx.txd(i), tx.txd(i))

    case _ =>
      io.txd := tx.txd
      io.txCtl := tx.txCtl
      val txcOut = new XilinxOddr
      txcOut.io.C := txCd.readClockWire
      txcOut.io.CE := True
      txcOut.io.D1 := !txCd.readClockEnableWire
      txcOut.io.D2 := txCd.readClockEnableWire
      txcOut.io.R := False
      txcOut.io.S := False
      io.txc := txcOut.io.Q
  }

  val rx = new ClockingArea(rxCd) {
    val capture = new ClockingArea(rxCd.withRevertedClockEdge()) {
      val rxd = RegNext(io.rxd) init(0)
      val rxCtl = RegNext(io.rxCtl) init(False)
    }
    val unbuffered = Flow(PhyRx(4))
    unbuffered.valid := RegNext(capture.rxCtl) init(False)
    unbuffered.data := RegNext(capture.rxd) init(0)
    unbuffered.error := False

    // As the MII/GMII wiring frames it: `last` on the nibble after which
    // RX_CTL drops.
    val buffered = unbuffered.stage()
    val frame = Flow(Fragment(PhyRx(4)))
    frame.valid := buffered.valid
    frame.fragment := buffered.payload
    frame.last := !unbuffered.valid && buffered.valid
    io.phy.rx << frame.toStream
  }
}
