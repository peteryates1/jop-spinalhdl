package jop.ddr3

import spinal.core._

/**
 * STARTUPE2 BlackBox for Xilinx 7 Series.
 *
 * On Xilinx 7 Series FPGAs, the SPI clock pin (CCLK) is dedicated and cannot
 * be accessed as regular I/O after configuration.  The STARTUPE2 primitive
 * must be instantiated to drive CCLK from user logic via the USRCCLKO port.
 *
 * The data pins (DQ0/MOSI, DQ1/MISO) and CS (FCS_B) are regular user I/O
 * after configuration and do not require this primitive.
 */
case class StartupE2() extends BlackBox {
  setDefinitionName("STARTUPE2")
  addGeneric("PROG_USR", "FALSE")
  addGeneric("SIM_CCLK_FREQ", 0.0)

  val io = new Bundle {
    val CFGCLK    = out Bool()
    val CFGMCLK   = out Bool()
    val EOS       = out Bool()
    val PREQ      = out Bool()
    val CLK       = in Bool()
    val GSR       = in Bool()
    val GTS       = in Bool()
    val KEYCLEARB = in Bool()
    val PACK      = in Bool()
    val USRCCLKO  = in Bool()    // SPI clock output -> CCLK pin
    val USRCCLKTS = in Bool()    // 0 = enable CCLK output
    val USRDONEO  = in Bool()
    val USRDONETS = in Bool()
  }
  noIoPrefix()
}
