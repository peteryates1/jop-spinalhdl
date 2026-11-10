package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.com.eth._
import spinal.lib.fsm._

/** What JOP sets: whether to echo, its MAC address (the byte first on the
  * wire in bits 47:40) and the UDP port. */
case class UdpEchoConfig() extends Bundle {
  val enable = Bool()
  val mac = Bits(48 bits)
  val port = UInt(16 bits)
}

/** Frame counts since reset: taken for echo with a good FCS, sent back, and
  * lost -- an FCS error, or no room in the buffer. */
case class UdpEchoStats() extends Bundle {
  val received = UInt(32 bits)
  val echoed = UInt(32 bits)
  val crcErrors = UInt(16 bits)
  val drops = UInt(16 bits)
}

/**
 * A UDP echo at line rate, in hardware, between the PHY adapter and MacEth:
 * a UDP datagram to the board's MAC address on one port goes straight back
 * out -- MAC addresses, IP addresses and ports exchanged, a new FCS -- without
 * the CPU, which cannot touch a gigabit link's worth of frames. Exchanging
 * them leaves the IP and UDP checksums right: each is a sum, and the UDP
 * pseudo-header holds both addresses. Every other frame reaches MacEth as
 * before. JOP sets the address and port and turns it on (EthDriver.udpEcho).
 *
 * RX (the PHY's clock): every frame is delayed `DelayCycles` clocks. A
 * classifier reads the undelayed bytes and has decided whether a frame is for
 * the echo by its UDP destination port -- the SFD and 38 bytes in -- before the
 * frame's first byte leaves the delay, where the frame is sent whole to one
 * side or the other. One frame decides at a time: the next starts at least 84
 * clocks after the last (preamble, the 64-byte minimum, the gap), more than
 * the delay. The echo's side checks the FCS (MacRxPreamble, MacRxChecker) and
 * stores the frame (MacRxBuffer, which drops a bad or unfitting frame whole)
 * for the TX clock.
 *
 * TX: the stored frame's first 40 bytes are loaded and sent with the fields
 * exchanged, the rest streamed, the old FCS left behind; MacTxCrc and
 * MacTxHeader add the new FCS and the preamble, and an arbiter takes whole
 * frames from the echo and from MacEth in turn. The PHY adapter adds the gap.
 */
case class UdpEcho(txCd: ClockDomain, rxCd: ClockDomain, bufferBytes: Int = 16384) extends Component {
  val io = new Bundle {
    val phy = master(PhyIo(PhyParameter(8, 8)))
    val mac = slave(PhyIo(PhyParameter(8, 8)))
    val config = in(UdpEchoConfig())
    val stats = out(UdpEchoStats())
  }

  /** Longer than a decision takes: preamble and SFD (8), then 38 bytes. */
  val DelayCycles = 64

  io.mac.colision := io.phy.colision
  io.mac.busy := io.phy.busy

  class RxSide extends ClockingArea(rxCd) {
    val enable = BufferCC(io.config.enable, init = False)
    val mac = RegNext(io.config.mac).addTag(crossClockDomain)
    val port = RegNext(io.config.port).addTag(crossClockDomain)
    val macBytes = mac.subdivideIn(8 bits).reverse

    // The PHY adapter cannot be held off
    val in = io.phy.rx
    in.ready := True
    val data = in.payload.fragment.data

    // ---- classify, on the undelayed bytes ----
    val inFrame = RegInit(False)
    val afterSfd = RegInit(False)
    val index = Reg(UInt(6 bits)) init(0) // header byte after the SFD, saturating
    val matching = RegInit(False)
    val decision = RegInit(False)

    val byteOk = Bool()
    byteOk := True
    switch(index) {
      for (i <- 0 until 6) is(i) { byteOk := data === macBytes(i) }
      is(12) { byteOk := data === 0x08 }           // EtherType IPv4
      is(13) { byteOk := data === 0x00 }
      is(14) { byteOk := data === 0x45 }           // version 4, a 20-byte header
      is(20) { byteOk := data(5 downto 0) === 0 }  // MF clear, offset 0: not a fragment
      is(21) { byteOk := data === 0 }
      is(23) { byteOk := data === 17 }             // UDP
      is(36) { byteOk := data === port(15 downto 8).asBits }
      is(37) { byteOk := data === port(7 downto 0).asBits }
    }

    when(in.valid) {
      when(!inFrame) {
        inFrame := True
        decision := False
        afterSfd := data === 0xD5
        index := 0
        matching := enable
      } elsewhen (!afterSfd) {
        when(data === 0xD5) {
          afterSfd := True
          index := 0
          matching := enable
        }
      } otherwise {
        when(!byteOk) { matching := False }
        when(index === 37) { decision := matching && byteOk }
        when(index =/= index.maxValue) { index := index + 1 }
      }
      when(in.payload.last) { inFrame := False }
    }

    // ---- delay, and send each frame whole one way ----
    val dValid = Delay(in.valid, DelayCycles, init = False)
    val dPayload = Delay(in.payload, DelayCycles)
    val dStart = dValid && !RegNext(dValid, init = False)
    val route = RegInit(False)
    when(dStart) { route := decision }
    val toEcho = Mux(dStart, decision, route)

    io.mac.rx.valid := dValid && !toEcho
    io.mac.rx.payload := dPayload

    val preamble = MacRxPreamble(dataWidth = 8)
    preamble.io.input.valid := dValid && toEcho
    preamble.io.input.payload := dPayload
    val checker = MacRxChecker(dataWidth = 8)
    checker.io.input << preamble.io.output
  }
  val rx = new RxSide

  val buffer = MacRxBuffer(pushCd = rxCd, popCd = txCd, pushWidth = 8, popWidth = 32, byteSize = bufferBytes)
  buffer.io.push.stream << rx.checker.io.output
  buffer.io.pop.stats.clear := False

  class RxCount extends ClockingArea(rxCd) {
    val received = Reg(UInt(32 bits)) init(0)
    val crcErrors = Reg(UInt(16 bits)) init(0)
    val drops = Reg(UInt(16 bits)) init(0)
    when(buffer.io.push.commit) {
      when(buffer.io.push.error) {
        crcErrors := crcErrors + 1
      } elsewhen (buffer.io.push.drop) {
        drops := drops + 1
      } otherwise {
        received := received + 1
      }
    }
    val grayReceived = RegNext(toGray(received)) init(0)
    val grayCrcErrors = RegNext(toGray(crcErrors)) init(0)
    val grayDrops = RegNext(toGray(drops)) init(0)
  }
  val rxCount = new RxCount

  class TxSide extends ClockingArea(txCd) {
    val pop = buffer.io.pop.stream
    val out = Stream(Fragment(PhyTx(8)))

    val length = Reg(UInt(16 bits))      // the stored frame's bytes, its FCS included
    val wordsLeft = Reg(UInt(12 bits))   // its words still in the buffer
    val head = Vec(Reg(Bits(32 bits)), 10)
    val index = Reg(UInt(11 bits))
    val headBytes = head.flatMap(_.subdivideIn(8 bits))
    /** The byte the echo sends at each of the first 40 places. */
    def from(i: Int): Int =
      if (i < 6) i + 6 else if (i < 12) i - 6
      else if (i >= 26 && i < 30) i + 4 else if (i >= 30 && i < 34) i - 4
      else if (i >= 34 && i < 36) i + 2 else if (i >= 36 && i < 38) i - 2
      else i
    val exchanged = Vec((0 until 40).map(i => headBytes(from(i))))
    val lastData = length - 5            // the byte before the FCS

    out.valid := False
    out.last := False
    out.data := 0
    pop.ready := False

    val fsm = new StateMachine {
      val LENGTH, LOAD, HEAD, BODY, DRAIN = new State
      setEntry(LENGTH)

      LENGTH.whenIsActive {
        pop.ready := True
        when(pop.valid) {
          val bytes = (pop.payload.asUInt >> 3).resize(16 bits)
          length := bytes
          wordsLeft := ((bytes + 3) >> 2).resized
          index := 0
          // Shorter than Ethernet's minimum: nothing the echo would have taken
          when(bytes < 64) { goto(DRAIN) } otherwise { goto(LOAD) }
        }
      }
      LOAD.whenIsActive {
        pop.ready := True
        when(pop.valid) {
          head(index(3 downto 0)) := pop.payload
          wordsLeft := wordsLeft - 1
          index := index + 1
          when(index === 9) {
            index := 0
            goto(HEAD)
          }
        }
      }
      HEAD.whenIsActive {
        out.valid := True
        out.data := exchanged(index(5 downto 0))
        when(out.ready) {
          index := index + 1
          when(index === 39) { goto(BODY) }
        }
      }
      BODY.whenIsActive {
        val isLast = index === lastData.resized
        out.valid := pop.valid
        out.data := pop.payload.subdivideIn(8 bits)(index(1 downto 0))
        out.last := isLast
        pop.ready := out.ready && (index(1 downto 0) === 3 || isLast)
        when(out.fire) {
          index := index + 1
          when(pop.ready) { wordsLeft := wordsLeft - 1 }
          // What follows in the buffer is only the old FCS
          when(isLast) {
            when(wordsLeft === 1) { goto(LENGTH) } otherwise { goto(DRAIN) }
          }
        }
      }
      DRAIN.whenIsActive {
        pop.ready := wordsLeft =/= 0
        when(pop.fire) { wordsLeft := wordsLeft - 1 }
        when(wordsLeft === 0 || (pop.fire && wordsLeft === 1)) { goto(LENGTH) }
      }
    }

    val crc = MacTxCrc(dataWidth = 8)
    crc.io.input << out
    val header = MacTxHeader(dataWidth = 8)
    header.io.input << crc.io.output

    io.phy.tx << StreamArbiterFactory().roundRobin.fragmentLock.on(Seq(io.mac.tx, header.io.output))

    val echoed = Reg(UInt(32 bits)) init(0)
    when(header.io.output.lastFire) { echoed := echoed + 1 }
    val grayEchoed = RegNext(toGray(echoed)) init(0)
  }
  val tx = new TxSide

  io.stats.received := fromGray(BufferCC(rxCount.grayReceived, init = B(0, 32 bits)))
  io.stats.crcErrors := fromGray(BufferCC(rxCount.grayCrcErrors, init = B(0, 16 bits)))
  io.stats.drops := fromGray(BufferCC(rxCount.grayDrops, init = B(0, 16 bits)))
  io.stats.echoed := fromGray(BufferCC(tx.grayEchoed, init = B(0, 32 bits)))
}
