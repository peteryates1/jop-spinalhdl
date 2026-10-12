package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth._

/**
 * Ethernet MAC I/O device — wraps SpinalHDL MacEth
 *
 * Provides buffered Ethernet TX and RX with JOP I/O interface.
 * MacEth handles CRC, preamble, padding, and cross-clock-domain buffering.
 *
 * Address map (sub-addresses relative to assigned I/O device slot):
 *   0x0 read  — Status: bit 0=TX flush, bit 1=TX ready, bit 4=RX flush, bit 5=RX valid
 *   0x0 write — Control: bit 0=TX flush (1=flush), bit 4=RX flush (1=flush)
 *   0x1 read  — TX availability (free words in TX FIFO)
 *   0x2 write — TX data push (first write = byte length * 8, then data words)
 *   0x3 read  — RX data pop (first read = bit count, then data words; auto-pop)
 *   0x4 read  — RX stats (auto-clear): bits [7:0]=errors, [15:8]=drops
 *
 * TX protocol: Write frame length in bits to addr 2, then write ceil(length/32)
 * data words. MacTxBuffer auto-commits after the correct number of words.
 *
 * RX protocol: When status bit 5 (RX valid) is set, read addr 3 repeatedly.
 * First read returns frame length in bits, subsequent reads return data words.
 * Each read pops one word from the RX stream.
 *
 * @param txCd TX PHY clock domain (25 MHz for 100M MII)
 * @param rxCd RX PHY clock domain (25 MHz for 100M MII)
 * @param phyTxDataWidth PHY TX data width (4 for MII, 8 for GMII)
 * @param phyRxDataWidth PHY RX data width (4 for MII, 8 for GMII)
 * @param rxBufferByteSize RX buffer size in bytes
 * @param txBufferByteSize TX buffer size in bytes
 */
case class Mac(
  txCd: ClockDomain,
  rxCd: ClockDomain,
  phyTxDataWidth: Int = 4,
  phyRxDataWidth: Int = 4,
  rxBufferByteSize: Int = 2048,
  txBufferByteSize: Int = 2048
) extends Component with HasBusIo {

  val macParam = MacEthParameter(
    phy = PhyParameter(
      txDataWidth = phyTxDataWidth,
      rxDataWidth = phyRxDataWidth
    ),
    rxDataWidth = 32,
    txDataWidth = 32,
    rxBufferByteSize = rxBufferByteSize,
    txBufferByteSize = txBufferByteSize
  )

  val bus = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // Interrupt outputs (active-high pulses)
    val rxInterrupt = out Bool()
    val txInterrupt = out Bool()
  }

  val io = new Bundle {
    // PHY interface (directly from MacEth)
    val phy = master(PhyIo(macParam.phy))
  }

  // ========================================================================
  // MacEth Instance
  // ========================================================================

  val mac = MacEth(macParam, txCd, rxCd)

  // Connect PHY interface
  io.phy <> mac.io.phy

  // ========================================================================
  // Control Registers
  // ========================================================================

  val txFlush = Reg(Bool()) init(True)   // Start flushed
  val rxFlush = Reg(Bool()) init(True)   // Start flushed

  mac.io.ctrl.tx.flush := txFlush
  mac.io.ctrl.rx.flush := rxFlush

  // Aligner: enable for word-aligned CPU access
  mac.io.ctrl.tx.alignerEnable := False
  mac.io.ctrl.rx.alignerEnable := False

  // ========================================================================
  // TX Path: CPU writes -> MacEth TX stream
  // ========================================================================

  // Registered TX push: hold valid/payload until MAC stream accepts (fire).
  // This prevents data loss when the MAC TX stream is temporarily not ready.
  val txPushValid = RegInit(False)
  val txPushPayload = Reg(Bits(32 bits)) init(0)

  mac.io.ctrl.tx.stream.valid := txPushValid
  mac.io.ctrl.tx.stream.payload := txPushPayload

  // Clear valid when MAC accepts the data
  when(mac.io.ctrl.tx.stream.fire) {
    txPushValid := False
  }

  // ========================================================================
  // RX Path: MacEth RX stream -> CPU reads
  // ========================================================================

  // RX stream: ready pulsed on read from addr 3
  mac.io.ctrl.rx.stream.ready := False

  // Stats: clear on read from addr 4
  mac.io.ctrl.rx.stats.clear := False

  // ========================================================================
  // Interrupt Generation
  // ========================================================================

  // RX: pulse on rising edge of "RX stream valid" (frame available)
  val rxValid = mac.io.ctrl.rx.stream.valid
  val rxValidDly = RegNext(rxValid) init(False)
  bus.rxInterrupt := rxValid && !rxValidDly

  // TX: pulse on rising edge of "TX ready" (holding register free)
  val txReady = !txPushValid
  val txReadyDly = RegNext(txReady) init(False)
  bus.txInterrupt := txReady && !txReadyDly

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  bus.rdData := 0
  switch(bus.addr) {
    is(0) {
      // Status register
      bus.rdData(0) := txFlush
      bus.rdData(1) := !txPushValid  // TX ready: holding register free
      bus.rdData(4) := rxFlush
      bus.rdData(5) := mac.io.ctrl.rx.stream.valid
    }
    is(1) {
      // TX availability (free words)
      bus.rdData := mac.io.ctrl.tx.availability.asBits.resized
    }
    is(3) {
      // RX data pop (auto-pop on read)
      bus.rdData := mac.io.ctrl.rx.stream.payload
      when(bus.rd) {
        mac.io.ctrl.rx.stream.ready := True
      }
    }
    is(4) {
      // RX stats (auto-clear on read)
      bus.rdData(7 downto 0)  := mac.io.ctrl.rx.stats.errors.asBits
      bus.rdData(15 downto 8) := mac.io.ctrl.rx.stats.drops.asBits
      when(bus.rd) {
        mac.io.ctrl.rx.stats.clear := True
      }
    }
  }

  // ========================================================================
  // Register Write Handling
  // ========================================================================

  when(bus.wr) {
    switch(bus.addr) {
      is(0) {
        // Control register
        txFlush := bus.wrData(0)
        rxFlush := bus.wrData(4)
      }
      is(2) {
        // TX data push — register valid and payload
        txPushValid := True
        txPushPayload := bus.wrData
      }
    }
  }

  // HasBusIo implementation
  override def busAddr: UInt   = bus.addr
  override def busRd: Bool     = bus.rd
  override def busWr: Bool     = bus.wr
  override def busWrData: Bits = bus.wrData
  override def busRdData: Bits = bus.rdData
  override def busInterrupts: Seq[Bool] = Seq(bus.rxInterrupt, bus.txInterrupt)
  override def busExternalIo: Option[Bundle] = Some(io)
}
