package jop.io

import spinal.core._
import spinal.lib._

/**
 * SPI config flash controller with JOP I/O register interface.
 *
 * Provides byte-at-a-time SPI access to the W25Q128 configuration flash
 * connected to the FPGA's active serial pins (DCLK, DATA0, ASDO, nCSO).
 * Used for autonomous flash boot: microcode reads the .jop application
 * from a known flash offset after FPGA configuration.
 *
 * SPI Mode 0: CPOL=0, CPHA=0. SCLK idles low. Data is sampled on the
 * rising edge and shifted out on the falling edge. MSB first.
 *
 * Pin mapping (config flash pins as regular I/O):
 *   flash_dclk  -> SCLK output
 *   flash_asdo  -> MOSI output
 *   flash_data0 -> MISO input
 *   flash_ncs   -> CS active-low output
 *
 * Register map (4-bit sub-address):
 *   0x0 read  -- Status: bit0=busy
 *   0x0 write -- Control: bit0=csAssert (1=CS low)
 *   0x1 read  -- RX byte (last received) [7:0]
 *   0x1 write -- TX byte (starts 8-bit SPI transfer) [7:0]
 *   0x2 read  -- Clock divider [15:0]
 *   0x2 write -- Set clock divider [15:0]
 *
 * SPI_CLK = sys_clk / (2 * (divider + 1)).
 * Default divider=3 gives ~10 MHz at 80 MHz (proven by exerciser).
 *
 * @param clkDivInit Initial value for the clock divider register
 */
case class BmbConfigFlash(clkDivInit: Int = 3) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // SPI pins (directly to config flash)
    val dclk  = out Bool()
    val ncs   = out Bool()
    val asdo  = out Bool()
    val data0 = in Bool()
  }

  // ========================================================================
  // Control Registers
  // ========================================================================

  val csAssertReg = Reg(Bool()) init(False)    // 1 = CS driven low
  val clkDivReg = Reg(UInt(16 bits)) init(clkDivInit)

  // CS output: active low, so invert csAssertReg
  io.ncs := !csAssertReg

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
  io.dclk := sclkReg
  // MOSI always reflects shift register MSB
  io.asdo := shiftReg(7)

  when(busy) {
    divCounter := divCounter + 1
    when(divCounter === clkDivReg) {
      divCounter := 0
      sclkReg := !sclkReg

      when(!sclkReg) {
        // Rising edge of SCLK: sample MISO into shift register LSB
        shiftReg := shiftReg(6 downto 0) ## io.data0
      } otherwise {
        // Falling edge of SCLK: advance bit counter
        bitCounter := bitCounter + 1
        when(bitCounter === 7) {
          // 8 bits done: latch received byte, end transfer.
          busy := False
          sclkReg := False
          rxData := shiftReg
        }
      }
    }
  }

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      // Status: bit0=busy
      io.rdData(0) := busy
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
        // Control: bit0=csAssert
        csAssertReg := io.wrData(0)
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
