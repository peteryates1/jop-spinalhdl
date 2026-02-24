package jop.io

import spinal.core._
import spinal.lib._

/**
 * SPI-mode SD card controller with JOP I/O register interface.
 *
 * This is a raw SPI master for SD cards in SPI mode. Software handles
 * the SD protocol (CMD0, CMD8, ACMD41, etc.); hardware handles byte-level
 * SPI shifting and chip-select control.
 *
 * SPI Mode 0: CPOL=0, CPHA=0. SCLK idles low. Data is sampled on the
 * rising edge and shifted out on the falling edge. MSB first.
 *
 * Pin mapping (directly on SD card):
 *   sd_clk      -> SCLK output
 *   sd_cmd_din  -> MOSI output
 *   sd_d[0]_dout -> MISO input
 *   sd_d[3]_cs  -> CS active-low output
 *   sd_cd       -> Card detect input (active low)
 *
 * Register map (4-bit sub-address):
 *   0x0 read  -- Status: bit0=busy, bit1=cardPresent, bit2=intEnable
 *   0x0 write -- Control: bit0=csAssert (1=CS low), bit1=intEnable
 *   0x1 read  -- RX byte (last received) [7:0]
 *   0x1 write -- TX byte (starts 8-bit SPI transfer) [7:0]
 *   0x2 read  -- Clock divider [15:0]
 *   0x2 write -- Set clock divider [15:0]
 *
 * SPI_CLK = sys_clk / (2 * (divider + 1)).
 * Default divider=199 gives ~200 kHz at 80 MHz (safe for SD init <= 400 kHz).
 *
 * @param clkDivInit Initial value for the clock divider register
 */
case class BmbSdSpi(clkDivInit: Int = 199) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    val sclk = out Bool()
    val mosi = out Bool()
    val miso = in Bool()
    val cs   = out Bool()     // Active low
    val cd   = in Bool()      // Card detect (active low)

    val interrupt = out Bool()
  }

  // ========================================================================
  // Card Detect (active-low input, 2-stage synchronizer)
  // ========================================================================

  val cdSync = BufferCC(io.cd, init = True)
  val cardPresent = !cdSync

  // ========================================================================
  // Control Registers
  // ========================================================================

  val csAssertReg = Reg(Bool()) init(False)    // 1 = CS driven low
  val intEnableReg = Reg(Bool()) init(False)
  val clkDivReg = Reg(UInt(16 bits)) init(clkDivInit)

  // CS output: active low, so invert csAssertReg
  io.cs := !csAssertReg

  // ========================================================================
  // SPI Engine (Mode 0: CPOL=0, CPHA=0)
  // ========================================================================

  val busy = Reg(Bool()) init(False)
  val sclkReg = Reg(Bool()) init(False)
  val shiftReg = Reg(Bits(8 bits)) init(0xFF)
  val rxData = Reg(Bits(8 bits)) init(0)
  val bitCounter = Reg(UInt(4 bits)) init(0)
  val divCounter = Reg(UInt(16 bits)) init(0)

  // SCLK idles low when not busy
  io.sclk := sclkReg
  // MOSI always reflects shift register MSB
  io.mosi := shiftReg(7)

  when(busy) {
    divCounter := divCounter + 1
    when(divCounter === clkDivReg) {
      divCounter := 0
      sclkReg := !sclkReg

      when(!sclkReg) {
        // Rising edge of SCLK: sample MISO into shift register LSB
        shiftReg := shiftReg(6 downto 0) ## io.miso
      } otherwise {
        // Falling edge of SCLK: advance bit counter
        bitCounter := bitCounter + 1
        when(bitCounter === 7) {
          // 8 bits done: latch received byte, end transfer.
          // shiftReg already contains all 8 received bits from the
          // preceding 8 rising edges (MSB = first bit received).
          busy := False
          sclkReg := False
          rxData := shiftReg
        }
      }
    }
  }

  // ========================================================================
  // Interrupt Generation
  // ========================================================================

  // Pulse on falling edge of busy when intEnable is set
  val busyDly = RegNext(busy) init(False)
  io.interrupt := intEnableReg && !busy && busyDly

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      // Status: bit0=busy, bit1=cardPresent, bit2=intEnable
      io.rdData(0) := busy
      io.rdData(1) := cardPresent
      io.rdData(2) := intEnableReg
    }
    is(1) {
      // RX byte
      io.rdData(7 downto 0) := rxData
    }
    is(2) {
      // Clock divider
      io.rdData(15 downto 0) := clkDivReg.asBits
    }
  }

  // ========================================================================
  // Register Write Handling
  // ========================================================================

  when(io.wr) {
    switch(io.addr) {
      is(0) {
        // Control: bit0=csAssert, bit1=intEnable
        csAssertReg := io.wrData(0)
        intEnableReg := io.wrData(1)
      }
      is(1) {
        // TX byte: load shift register, start transfer
        shiftReg := io.wrData(7 downto 0)
        busy := True
        bitCounter := 0
        divCounter := 0
        sclkReg := False
      }
      is(2) {
        // Clock divider
        clkDivReg := io.wrData(15 downto 0).asUInt
      }
    }
  }
}
