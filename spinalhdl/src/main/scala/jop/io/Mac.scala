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
 * With `rxCapture` (a diagnostic, see the RX capture section):
 *   0x5 write — arm; bit 0 = mode (0: the next frame, 1: the next frame the MAC
 *               rejects or never commits)
 *   0x5 read  — bit 0 done, bit 1 mode, bit 2 commit seen, bit 3 error seen,
 *               [27:16] entries
 *   0x6 read  — index of the first entry; 0x6 write — read pointer
 *   0x7 read  — the entry at the read pointer, which then advances
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
 * @param rxCapture add the RX capture (2048 entries, four M9K/BRAM blocks)
 */
case class Mac(
  txCd: ClockDomain,
  rxCd: ClockDomain,
  phyTxDataWidth: Int = 4,
  phyRxDataWidth: Int = 4,
  rxBufferByteSize: Int = 2048,
  txBufferByteSize: Int = 2048,
  rxCapture: Boolean = false
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
  // RX capture (rxCapture -- a diagnostic, status item 166)
  // ========================================================================

  /**
   * A logic analyser on the receive stream as it enters MacEth: one entry per
   * RX clock -- bits 7:0 RXD, 8 RX_DV, 9 RX_ER, 10 last -- with the MAC's
   * verdict as it happens: 11 commit, 12 error, 13 drop. From arming, a ring
   * buffer records every clock. A capture opens when RX_DV rises after 4 idle
   * clocks, keeping the 8 entries before it, and closes after 32 idle clocks --
   * so a frame whose RX_DV drops for a clock stays one capture -- or when the
   * ring is full. Mode 0 keeps the first capture. Mode 1 keeps the first one
   * the MAC committed with its error flag, or never committed at all: a frame
   * that vanishes without counting as an error.
   */
  class RxCapture extends Area {
    val depth = 2048
    val pre = 8
    val tail = 32
    val addrWidth = log2Up(depth)
    val ram = Mem(Bits(16 bits), depth)

    val armToggle = RegInit(False)
    val mode = RegInit(False)

    val rxReset = ResetCtrl.asyncAssertSyncDeassert(
      input = ClockDomain.current.isResetActive,
      clockDomain = rxCd
    )

    val rx = new ClockingArea(rxCd.copy(reset = rxReset)) {
      val arm = BufferCC(armToggle, init = False)
      val armed = arm =/= RegNext(arm).init(False)
      val keepRejected = BufferCC(mode, init = False)

      val s = io.phy.rx
      val commitNow = mac.io.sim.commit
      val errorNow = commitNow && mac.io.sim.error
      val entry = B(0, 2 bits) ## mac.io.sim.drop ## mac.io.sim.error ## commitNow ##
        s.payload.last ## s.payload.fragment.error ## s.valid ## s.payload.fragment.data.resize(8)

      val running = RegInit(False)
      val open = RegInit(False)
      val done = RegInit(False)
      val wrAddr = Reg(UInt(addrWidth bits)) init(0)
      val start = Reg(UInt(addrWidth bits)) init(0)
      val length = Reg(UInt(addrWidth + 1 bits)) init(0)
      val idle = Reg(UInt(log2Up(tail) bits)) init(0) // RX_DV low before this clock
      val sawCommit = RegInit(False)
      val sawError = RegInit(False)

      when(s.valid) {
        idle := 0
      } elsewhen (idle =/= tail - 1) {
        idle := idle + 1
      }

      ram.write(wrAddr, entry, enable = running)
      when(running) {
        wrAddr := wrAddr + 1
        when(!open) {
          when(s.valid && idle >= 4) {
            open := True
            start := wrAddr - pre
            length := pre + 1
            sawCommit := False
            sawError := False
          }
        } otherwise {
          length := length + 1
          when(commitNow) { sawCommit := True }
          when(errorNow) { sawError := True }
          when((!s.valid && idle === tail - 1) || length === depth - 1) {
            open := False
            when(!keepRejected || sawError || errorNow || !(sawCommit || commitNow)) {
              running := False
              done := True
            }
          }
        }
      }
      when(armed) {
        running := True
        open := False
        done := False
      }
    }

    // Read side. start, length and the flags hold still once done is set.
    val done = BufferCC(rx.done, init = False)
    val start = RegNext(rx.start).addTag(crossClockDomain)
    val length = RegNext(rx.length).addTag(crossClockDomain)
    val sawCommit = RegNext(rx.sawCommit).addTag(crossClockDomain)
    val sawError = RegNext(rx.sawError).addTag(crossClockDomain)

    val rdPtr = Reg(UInt(addrWidth bits)) init(0)
    val rdPtrNext = CombInit(rdPtr)
    rdPtr := rdPtrNext
    // Addressed by the NEXT pointer, so the entry is ready the clock after a
    // read or a pointer write, whichever clock the CPU reads in.
    val rdEntry = ram.readSync(rdPtrNext, clockCrossing = true)
  }

  val capture = rxCapture generate new RxCapture

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
    if (rxCapture) {
      is(5) {
        bus.rdData(0) := capture.done
        bus.rdData(1) := capture.mode
        bus.rdData(2) := capture.sawCommit
        bus.rdData(3) := capture.sawError
        bus.rdData(27 downto 16) := capture.length.asBits
      }
      is(6) {
        bus.rdData := capture.start.asBits.resized
      }
      is(7) {
        bus.rdData := capture.rdEntry.resized
        when(bus.rd) {
          capture.rdPtrNext := capture.rdPtr + 1
        }
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
      if (rxCapture) {
        is(5) {
          capture.mode := bus.wrData(0)
          capture.armToggle := !capture.armToggle
        }
        is(6) {
          capture.rdPtrNext := bus.wrData.asUInt.resized
        }
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
