package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth._

/**
 * MacEth variant that skips RX CRC checking.
 *
 * For GMII with mesochronous clocking, the PLL RX clock has an arbitrary
 * phase relationship with the PHY's recovered RX clock. This causes
 * intermittent data corruption that trips the MAC's CRC32 checker.
 * Upper-layer checksums (IP, ICMP, UDP, TCP) still provide integrity.
 *
 * This is a copy of spinal.lib.com.eth.MacEth with the MacRxChecker
 * replaced by a direct pass-through.
 */
case class MacEthNoCrc(p : MacEthParameter,
                       txCd : ClockDomain,
                       rxCd : ClockDomain) extends Component {
  val io = new Bundle {
    val phy = master(PhyIo(p.phy))
    val ctrl = MacEthCtrl(p)

    val sim = new Bundle {
      val drop = out Bool()
      val error = out Bool()
      val commit = out Bool()
    }
  }

  val ctrlClockDomain = this.clockDomain

  val rxReset = ResetCtrl.asyncAssertSyncDeassert(
    input = ClockDomain.current.isResetActive || io.ctrl.rx.flush,
    clockDomain = rxCd
  )
  val rxClockDomain = rxCd.copy(reset = rxReset)

  val txReset = ResetCtrl.asyncAssertSyncDeassert(
    input = ClockDomain.current.isResetActive || io.ctrl.tx.flush,
    clockDomain = txCd
  )
  val txClockDomain = txCd.copy(reset = txReset)

  val rxFrontend = rxClockDomain on new Area {
    val preamble = MacRxPreamble(dataWidth = p.phy.rxDataWidth)
    preamble.io.input << io.phy.rx

    // Skip CRC checker — pass preamble output directly to aligner.
    // CRC errors from mesochronous clocking are expected; rely on
    // upper-layer checksums (IP/ICMP/UDP/TCP) for integrity.

    val aligner = MacRxAligner(dataWidth = p.phy.rxDataWidth)
    aligner.io.input << preamble.io.output
    aligner.io.enable := BufferCC(io.ctrl.rx.alignerEnable)

    val buffer = MacRxBuffer(
      pushCd = rxClockDomain,
      popCd = ctrlClockDomain.copy(softReset = io.ctrl.rx.flush),
      pushWidth = p.phy.rxDataWidth,
      popWidth = p.rxDataWidth,
      byteSize = p.rxBufferByteSize
    )
    buffer.io.push.stream << aligner.io.output
    buffer.io.push.drop <> io.sim.drop
    buffer.io.push.commit <> io.sim.commit
    buffer.io.push.error <> io.sim.error
  }

  val rxBackend = new Area {
    rxFrontend.buffer.io.pop.stream >> io.ctrl.rx.stream
    io.ctrl.rx.stats.clear <> rxFrontend.buffer.io.pop.stats.clear
    io.ctrl.rx.stats.errors <> rxFrontend.buffer.io.pop.stats.errors
    io.ctrl.rx.stats.drops <> rxFrontend.buffer.io.pop.stats.drops
  }

  val txFrontend = new Area {
    val buffer = MacTxBuffer(
      pushCd = ctrlClockDomain.copy(softReset = io.ctrl.tx.flush),
      popCd = txClockDomain,
      pushWidth = p.rxDataWidth,
      popWidth = p.phy.txDataWidth,
      byteSize = p.txBufferByteSize
    )
    buffer.io.push.stream << io.ctrl.tx.stream
    buffer.io.push.availability <> io.ctrl.tx.availability
  }

  val txBackend = txClockDomain on new Area {
    val aligner = MacTxAligner(dataWidth = p.phy.rxDataWidth)
    aligner.io.input << txFrontend.buffer.io.pop.stream
    aligner.io.enable := BufferCC(io.ctrl.tx.alignerEnable)

    val padder = MacTxPadder(dataWidth = p.phy.txDataWidth)
    padder.io.input << aligner.io.output

    val crc = MacTxCrc(dataWidth = p.phy.txDataWidth)
    crc.io.input << padder.io.output

    val header = MacTxHeader(dataWidth = p.phy.txDataWidth)
    header.io.input << crc.io.output
    header.io.output >> io.phy.tx

    txFrontend.buffer.io.pop.redo := False
    txFrontend.buffer.io.pop.commit := RegNext(header.io.output.lastFire) init(False)
  }
}
