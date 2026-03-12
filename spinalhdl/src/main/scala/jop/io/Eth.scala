package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth.{PhyIo, PhyParameter}
import jop.config.JopCoreConfig

/**
 * Ethernet Controller — combines MAC and MDIO into a single I/O device.
 *
 * Wraps Mac (Ethernet MAC with TX/RX buffers) and Mdio (PHY management)
 * as one addressable device. Address bit 3 selects the sub-device:
 *   0x0..0x7 — MAC registers (see Mac for details)
 *   0x8..0xF — MDIO registers (see Mdio for details)
 *
 * Interrupt routing is internal — Mac's RX/TX interrupts feed directly
 * into Mdio's interrupt aggregator. No cross-device wiring needed.
 *
 * @param txCd             TX PHY clock domain
 * @param rxCd             RX PHY clock domain
 * @param phyTxDataWidth   PHY TX data width (4 for MII, 8 for GMII)
 * @param phyRxDataWidth   PHY RX data width (4 for MII, 8 for GMII)
 * @param mdioClkDivider   MDC clock divider
 * @param rxBufferByteSize RX buffer size in bytes
 * @param txBufferByteSize TX buffer size in bytes
 */
case class Eth(
  txCd: ClockDomain,
  rxCd: ClockDomain,
  phyTxDataWidth: Int = 4,
  phyRxDataWidth: Int = 4,
  mdioClkDivider: Int = 40,
  rxBufferByteSize: Int = 2048,
  txBufferByteSize: Int = 2048
) extends Component with HasBusIo {

  val bus = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)
    val interrupt = out Bool()
  }

  val io = new Bundle {
    // PHY interface (MII/GMII)
    val phy      = master(PhyIo(PhyParameter(
      txDataWidth = phyTxDataWidth,
      rxDataWidth = phyRxDataWidth
    )))
    // MDIO pins
    val mdc      = out Bool()
    val mdioOut  = out Bool()
    val mdioOe   = out Bool()
    val mdioIn   = in Bool()
    // PHY reset (active-low)
    val phyReset = out Bool()
  }

  // ========================================================================
  // Sub-devices
  // ========================================================================

  val mac = Mac(txCd, rxCd, phyTxDataWidth, phyRxDataWidth,
    rxBufferByteSize, txBufferByteSize)
  val mdio = Mdio(mdioClkDivider)

  // ========================================================================
  // Address decode: bit 3 selects MAC (0) vs MDIO (1)
  // ========================================================================

  val selMdio = bus.addr(3)
  val subAddr = bus.addr(2 downto 0).resize(4)

  // MAC bus
  mac.bus.addr   := subAddr
  mac.bus.rd     := bus.rd && !selMdio
  mac.bus.wr     := bus.wr && !selMdio
  mac.bus.wrData := bus.wrData

  // MDIO bus
  mdio.bus.addr   := subAddr
  mdio.bus.rd     := bus.rd && selMdio
  mdio.bus.wr     := bus.wr && selMdio
  mdio.bus.wrData := bus.wrData

  // Read mux
  bus.rdData := Mux(selMdio, mdio.bus.rdData, mac.bus.rdData)

  // ========================================================================
  // Internal interrupt wiring (no cross-device hack needed)
  // ========================================================================

  mdio.bus.ethRxInt := mac.bus.rxInterrupt
  mdio.bus.ethTxInt := mac.bus.txInterrupt

  // Promote interrupt to this component's level
  bus.interrupt := mdio.bus.interrupt

  // ========================================================================
  // External pin wiring
  // ========================================================================

  io.phy <> mac.io.phy
  io.mdc      := mdio.io.mdc
  io.mdioOut  := mdio.io.mdioOut
  io.mdioOe   := mdio.io.mdioOe
  mdio.io.mdioIn := io.mdioIn
  io.phyReset := mdio.io.phyReset

  // ========================================================================
  // HasBusIo implementation
  // ========================================================================

  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busInterrupts: Seq[Bool] = Seq(bus.interrupt)
  override def busExternalIo: Option[Bundle] = Some(io)
}
