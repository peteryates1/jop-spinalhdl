package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import jop.ddr2.Ddr2BlackBox

/** Exerciser sequencer states. */
object Ddr2ExState extends SpinalEnum {
  val IDLE, WRITE, READ_ISSUE, READ_DRAIN, SETTLE, REPORT = newElement()
}

/**
 * DDR2 exerciser for the A-E115FB board (EP4CE115 + 1 GB DDR2 SODIMM).
 *
 * First hardware milestone of the DDR2 bring-up
 * (docs/boards/ae115fb-ddr2-bringup.md). It drives the ALTMEMPHY controller's
 * local interface DIRECTLY — no cache, no adapter, no JOP — so that a failure
 * here means the IP wrapper, the pin constraints or calibration, and nothing
 * else. The cache path goes on top only once this passes.
 *
 * Test loop, over a configurable window of 128-bit words:
 *   1. write an address-derived pattern
 *   2. read it all back and compare
 *   3. report over UART and on the LEDs, then repeat with a rolling seed so a
 *      stuck-at or a stale-data fault cannot pass twice
 *
 * The pattern is `{~a, a, ~a, a}` (a = word address ^ seed), which catches
 * stuck-at bits, byte-lane swaps and — because it varies per address — aliasing
 * from wrong row/bank/rank decoding.
 *
 * CLOCKING: user logic runs on `phy_clk`, an output of the controller. This IP
 * was generated with `local_if_drate = Full` and `mem_if_clk = 166 MHz`, so the
 * local interface carries 128 bits per memory clock and **phy_clk is 166 MHz**
 * — confirmed from the timing report (6.021 ns generated clock off a 25 MHz
 * reference), not assumed.
 *
 * 166 MHz is far above JOP's fmax, so the eventual JOP integration cannot simply
 * run in this domain: either regenerate the IP at half rate (phy_clk 83 MHz,
 * 256-bit local data, which would also match a 256-bit cache line) or cross
 * clock domains. See docs/boards/ae115fb-ddr2-bringup.md.
 *
 * LED0 still toggles every 2^23 phy_clk cycles as an independent check: at
 * 166 MHz that is a ~0.10 s period (~5 Hz visible blink).
 */
case class Ddr2ExerciserTop(
    phyClkHz: Int = 166000000,  // from the IP: local_if_drate=Full, mem_if_clk=166 MHz
    baud: Int = 115200,         // low rate, tolerant of phyClkHz being off
    testWords: Int = 4096       // 128-bit words per pass = 64 KB
) extends Component {

  val io = new Bundle {
    val clk   = in Bool()       // PIN_AB11, 25 MHz core-board oscillator
    val rst_n = in Bool()       // PIN_N21, KEY1, active low
    val led   = out Bits (4 bits)   // active LOW on this board
    val uart_tx = out Bool()    // PIN_H5  -> CH340 RX
    val uart_rx = in Bool()     // PIN_N1  <- CH340 TX

    // DDR2 SODIMM. mem_ba is 3 bits at the top level to match the board's pin
    // assignments, but this IP was generated with 2 bank bits (2 banks x 2
    // ranks via mem_cs_n gives the full 1 GB), so bit 2 is tied low — the same
    // arrangement as the vendor reference design.
    val mem_odt   = out Bits (2 bits)
    val mem_cs_n  = out Bits (2 bits)
    val mem_cke   = out Bits (2 bits)
    val mem_addr  = out Bits (14 bits)
    val mem_ba    = out Bits (3 bits)
    val mem_ras_n = out Bool()
    val mem_cas_n = out Bool()
    val mem_we_n  = out Bool()
    val mem_dm    = out Bits (8 bits)
    val mem_clk   = inout(Analog(Bits(2 bits)))
    val mem_clk_n = inout(Analog(Bits(2 bits)))
    val mem_dq    = inout(Analog(Bits(64 bits)))
    val mem_dqs   = inout(Analog(Bits(8 bits)))
  }

  noIoPrefix()

  // ==========================================================================
  // Controller. Driven from the raw board clock/reset; everything else lives in
  // the phy_clk domain it produces.
  // ==========================================================================
  val ddr2 = new Ddr2BlackBox
  ddr2.io.pll_ref_clk    := io.clk
  ddr2.io.global_reset_n := io.rst_n
  ddr2.io.soft_reset_n   := io.rst_n

  io.mem_odt   := ddr2.io.mem_odt
  io.mem_cs_n  := ddr2.io.mem_cs_n
  io.mem_cke   := ddr2.io.mem_cke
  io.mem_addr  := ddr2.io.mem_addr
  io.mem_ba    := B"0" ## ddr2.io.mem_ba
  io.mem_ras_n := ddr2.io.mem_ras_n
  io.mem_cas_n := ddr2.io.mem_cas_n
  io.mem_we_n  := ddr2.io.mem_we_n
  io.mem_dm    := ddr2.io.mem_dm
  io.mem_clk   <> ddr2.io.mem_clk
  io.mem_clk_n <> ddr2.io.mem_clk_n
  io.mem_dq    <> ddr2.io.mem_dq
  io.mem_dqs   <> ddr2.io.mem_dqs

  val phyDomain = ClockDomain(
    clock = ddr2.io.phy_clk,
    reset = ddr2.io.reset_phy_clk_n,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW),
    frequency = FixedFrequency(phyClkHz Hz)
  )

  val core = new ClockingArea(phyDomain) {

    // ---- pattern -------------------------------------------------------
    val seed = Reg(UInt(26 bits)) init (0)
    def pattern(a: UInt): Bits = {
      val x = (a ^ seed).resize(32).asBits
      ~x ## x ## ~x ## x                    // 128 bits
    }

    // ---- test sequencer -------------------------------------------------
    import Ddr2ExState._
    val state = RegInit(IDLE)

    val wrAddr  = Reg(UInt(26 bits)) init (0)   // next word to write
    val rdAddr  = Reg(UInt(26 bits)) init (0)   // next read command to issue
    val rxCount = Reg(UInt(26 bits)) init (0)   // read words returned so far
    val errors  = Reg(UInt(16 bits)) init (0)
    val passes  = Reg(UInt(16 bits)) init (0)
    val settle  = Reg(UInt(2 bits)) init (0)

    // Defaults each cycle
    ddr2.io.local_write_req  := False
    ddr2.io.local_read_req   := False
    ddr2.io.local_burstbegin := False
    ddr2.io.local_address    := wrAddr.asBits
    ddr2.io.local_wdata      := pattern(wrAddr)
    ddr2.io.local_be         := B"16'hFFFF"
    ddr2.io.local_size       := B"3'd1"        // single 128-bit word per command

    switch(state) {
      is(IDLE) {
        // Nothing may be issued before calibration completes.
        when(ddr2.io.local_init_done) {
          wrAddr := 0; rdAddr := 0; rxCount := 0; errors := 0
          state := WRITE
        }
      }

      is(WRITE) {
        ddr2.io.local_address    := wrAddr.asBits
        ddr2.io.local_wdata      := pattern(wrAddr)
        ddr2.io.local_write_req  := True
        ddr2.io.local_burstbegin := True
        // One signal covers command and write data on this controller, unlike
        // the MIG's split app_rdy / app_wdf_rdy.
        when(ddr2.io.local_ready) {
          when(wrAddr === testWords - 1) {
            state := READ_ISSUE
          } otherwise {
            wrAddr := wrAddr + 1
          }
        }
      }

      is(READ_ISSUE) {
        ddr2.io.local_address    := rdAddr.asBits
        ddr2.io.local_read_req   := True
        ddr2.io.local_burstbegin := True
        when(ddr2.io.local_ready) {
          when(rdAddr === testWords - 1) {
            state := READ_DRAIN          // all commands issued; data still coming
          } otherwise {
            rdAddr := rdAddr + 1
          }
        }
      }

      is(READ_DRAIN) {
        // Let the pipelined compare drain before the verdict is sampled.
        when(rxCount === testWords) { state := SETTLE }
      }

      is(SETTLE) {
        settle := settle + 1
        when(settle === 3) { settle := 0; state := REPORT }
      }
    }

    // Read data returns out of band from the command stream, in order.
    //
    // The compare is PIPELINED: doing `local_rdata =/= pattern(rxCount)` in one
    // cycle puts a 128-bit comparator plus the pattern generator on the phy_clk
    // critical path, which cost -2.151 ns of setup slack. Capture the returned
    // word and its expected value together, then compare a cycle later.
    val rdAccept = ddr2.io.local_rdata_valid && (state === READ_ISSUE || state === READ_DRAIN)
    val cmpValid = RegNext(rdAccept) init (False)
    val cmpGot   = RegNext(ddr2.io.local_rdata)
    val cmpWant  = RegNext(pattern(rxCount))
    when(rdAccept) { rxCount := rxCount + 1 }
    // Second pipeline stage: the 128-bit compare drives a single flop rather
    // than a 16-bit counter increment. Compare-into-adder was still the
    // critical path at -0.329 ns after the first stage.
    val mismatch = RegNext(cmpValid && cmpGot =/= cmpWant) init (False)
    when(mismatch) { errors := errors + 1 }

    // ---- UART reporting ------------------------------------------------
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(baud Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := io.uart_rx

    val txFifo = StreamFifo(Bits(8 bits), 64)
    uartCtrl.io.write.valid   := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready       := uartCtrl.io.write.ready
    txFifo.io.push.valid   := False
    txFifo.io.push.payload := 0

    // "DDR2 PASS eeee\r\n" / "DDR2 FAIL eeee\r\n" — the error count is printed
    // either way so a partially working configuration is visible rather than
    // just "FAIL".
    val msg = "DDR2 xxxx eeee\r\n"
    val msgRom = Mem(Bits(8 bits), msg.map(c => B(c.toInt, 8 bits)))
    val msgIdx = Reg(UInt(5 bits)) init (0)
    val sending = Reg(Bool()) init (False)

    def hexDigit(v: UInt): Bits = {
      val d = Bits(8 bits)
      when(v < 10) { d := (B"8'd48".asUInt + v).asBits } otherwise { d := (B"8'd87".asUInt + v).asBits }
      d
    }

    val curByte = Bits(8 bits)
    curByte := msgRom.readAsync(msgIdx.resize(log2Up(msg.length)))
    // patch in the verdict and the error count
    switch(msgIdx) {
      is(5)  { curByte := Mux(errors === 0, B"8'd80", B"8'd70") }   // 'P' / 'F'
      is(6)  { curByte := Mux(errors === 0, B"8'd65", B"8'd65") }   // 'A'
      is(7)  { curByte := Mux(errors === 0, B"8'd83", B"8'd73") }   // 'S' / 'I'
      is(8)  { curByte := Mux(errors === 0, B"8'd83", B"8'd76") }   // 'S' / 'L'
      is(10) { curByte := hexDigit(errors(15 downto 12)) }
      is(11) { curByte := hexDigit(errors(11 downto 8)) }
      is(12) { curByte := hexDigit(errors(7 downto 4)) }
      is(13) { curByte := hexDigit(errors(3 downto 0)) }
    }

    when(state === REPORT && !sending) {
      sending := True
      msgIdx := 0
    }
    when(sending) {
      txFifo.io.push.valid   := True
      txFifo.io.push.payload := curByte
      when(txFifo.io.push.ready) {
        when(msgIdx === msg.length - 1) {
          sending := False
          passes := passes + 1
          seed := seed + 1        // different data next pass
          state := IDLE
        } otherwise {
          msgIdx := msgIdx + 1
        }
      }
    }

    // ---- status --------------------------------------------------------
    // LED0 toggles every 2^23 phy_clk cycles. Measure its period to determine
    // the real phy_clk frequency and correct `phyClkHz`.
    val hb = Reg(UInt(24 bits)) init (0)
    hb := hb + 1

    val sawError = Reg(Bool()) init (False)
    when(state === REPORT && errors =/= 0) { sawError := True }

    val ledReg = Bits(4 bits)
    ledReg(0) := hb.msb                    // heartbeat / frequency reference
    ledReg(1) := ddr2.io.local_init_done   // calibration complete
    ledReg(2) := passes =/= 0 && !sawError // at least one clean pass, none bad
    ledReg(3) := sawError                  // sticky failure
  }

  io.uart_tx := core.uartCtrl.io.uart.txd
  io.led := ~core.ledReg                   // board LEDs are active LOW
}

/** Generate Verilog for the DDR2 exerciser. */
object Ddr2ExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(25 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  ).generate(InOutWrapper(Ddr2ExerciserTop()))

  println("Generated: spinalhdl/generated/Ddr2ExerciserTop.v")
}
