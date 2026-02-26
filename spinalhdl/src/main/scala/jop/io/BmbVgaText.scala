package jop.io

import spinal.core._
import spinal.lib._

/**
 * Text-mode VGA controller — 80x30 characters, 8x16 pixel font, 640x480@60Hz
 *
 * Uses on-chip BRAM for character buffer, attribute buffer, palette, and font ROM.
 * RGB565 output (5-6-5) to match QMTECH daughter board DAC.
 *
 * Register map (addr is 4-bit sub-address within I/O device slot):
 *   0x0 read  — Status: bit0=enabled, bit1=vblank, bit2=scrollBusy
 *   0x0 write — Control: bit0=enable output
 *   0x1 read  — Cursor position: col[6:0], row[12:8]
 *   0x1 write — Set cursor position: col[6:0], row[12:8]
 *   0x2 write — Write char+attr at cursor, auto-advance: char[7:0], attr[15:8]
 *   0x3 read  — Default attribute [7:0]
 *   0x3 write — Set default attribute [7:0]
 *   0x4 write — Palette write: index[19:16], rgb565[15:0]
 *   0x5 read  — Columns (80)
 *   0x6 read  — Rows (30)
 *   0x7 write — Direct write: char[7:0], attr[15:8], col[22:16], row[28:24]
 *   0x8 write — Clear screen (fill with space 0x20 + default attr)
 *   0x9 write — Scroll up 1 row (shift rows up, clear bottom row)
 *
 * @param vgaCd 25 MHz pixel clock domain
 */
case class BmbVgaText(
  vgaCd: ClockDomain
) extends Component {

  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // VGA output pins
    val vgaHsync = out Bool()
    val vgaVsync = out Bool()
    val vgaR     = out Bits(5 bits)
    val vgaG     = out Bits(6 bits)
    val vgaB     = out Bits(5 bits)

    val vsyncInterrupt = out Bool()
  }

  // ========================================================================
  // Constants
  // ========================================================================

  val COLS = 80
  val ROWS = 30
  val CHAR_W = 8
  val CHAR_H = 16
  val CHAR_COUNT = COLS * ROWS  // 2400

  // VGA 640x480@60Hz timing (25 MHz pixel clock)
  val H_ACTIVE     = 640
  val H_FRONT      = 16
  val H_SYNC       = 96
  val H_BACK       = 48
  val H_TOTAL      = 800   // 640 + 16 + 96 + 48

  val V_ACTIVE     = 480
  val V_FRONT      = 10
  val V_SYNC       = 2
  val V_BACK       = 33
  val V_TOTAL      = 525   // 480 + 10 + 2 + 33

  val H_SYNC_START = H_ACTIVE + H_FRONT   // 656
  val H_SYNC_END   = H_SYNC_START + H_SYNC // 752
  val V_SYNC_START = V_ACTIVE + V_FRONT    // 490
  val V_SYNC_END   = V_SYNC_START + V_SYNC // 492

  // ========================================================================
  // Default 16-color palette (CGA colors in RGB565)
  // ========================================================================

  val defaultPalette = Seq(
    0x0000, // 0: Black
    0x0015, // 1: Blue
    0x0540, // 2: Green
    0x0555, // 3: Cyan
    0xA800, // 4: Red
    0xA815, // 5: Magenta
    0xAAA0, // 6: Brown/Dark Yellow
    0xAD55, // 7: Light Gray
    0x52AA, // 8: Dark Gray
    0x52BF, // 9: Light Blue
    0x57EA, // 10: Light Green
    0x57FF, // 11: Light Cyan
    0xFAAA, // 12: Light Red
    0xFABF, // 13: Light Magenta
    0xFFEA, // 14: Yellow
    0xFFFF  // 15: White
  )

  // ========================================================================
  // Font ROM data (256 chars x 16 rows = 4096 bytes)
  // Minimal set: printable ASCII (0x20-0x7E), rest are blank.
  // To load a real font, replace fontData entries or load from file.
  // ========================================================================

  val fontData = {
    val data = Array.fill(4096)(BigInt(0))

    // Helper: define a character's 16-row bitmap
    def defChar(code: Int, rows: Seq[Int]): Unit = {
      for (r <- rows.indices) {
        data(code * 16 + r) = BigInt(rows(r) & 0xFF)
      }
    }

    // Space (0x20) — already all zeros

    // '!' = 0x21
    defChar(0x21, Seq(0x00,0x00,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x00,0x18,0x18,0x00,0x00,0x00,0x00))

    // '#' = 0x23
    defChar(0x23, Seq(0x00,0x00,0x6C,0x6C,0xFE,0x6C,0x6C,0x6C,0xFE,0x6C,0x6C,0x00,0x00,0x00,0x00,0x00))

    // '.' = 0x2E
    defChar(0x2E, Seq(0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x18,0x18,0x00,0x00,0x00,0x00))

    // '0' = 0x30
    defChar(0x30, Seq(0x00,0x00,0x3C,0x66,0x66,0x6E,0x76,0x66,0x66,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    // '1' = 0x31
    defChar(0x31, Seq(0x00,0x00,0x18,0x38,0x18,0x18,0x18,0x18,0x18,0x18,0x7E,0x00,0x00,0x00,0x00,0x00))
    // '2' = 0x32
    defChar(0x32, Seq(0x00,0x00,0x3C,0x66,0x06,0x0C,0x18,0x30,0x60,0x66,0x7E,0x00,0x00,0x00,0x00,0x00))
    // '3' = 0x33
    defChar(0x33, Seq(0x00,0x00,0x3C,0x66,0x06,0x1C,0x06,0x06,0x06,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    // '4' = 0x34
    defChar(0x34, Seq(0x00,0x00,0x0C,0x1C,0x3C,0x6C,0xCC,0xFE,0x0C,0x0C,0x0C,0x00,0x00,0x00,0x00,0x00))
    // '5' = 0x35
    defChar(0x35, Seq(0x00,0x00,0x7E,0x60,0x60,0x7C,0x06,0x06,0x06,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    // '6' = 0x36
    defChar(0x36, Seq(0x00,0x00,0x1C,0x30,0x60,0x7C,0x66,0x66,0x66,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    // '7' = 0x37
    defChar(0x37, Seq(0x00,0x00,0x7E,0x06,0x0C,0x18,0x18,0x18,0x18,0x18,0x18,0x00,0x00,0x00,0x00,0x00))
    // '8' = 0x38
    defChar(0x38, Seq(0x00,0x00,0x3C,0x66,0x66,0x3C,0x66,0x66,0x66,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    // '9' = 0x39
    defChar(0x39, Seq(0x00,0x00,0x3C,0x66,0x66,0x66,0x3E,0x06,0x06,0x0C,0x38,0x00,0x00,0x00,0x00,0x00))

    // A-Z (0x41-0x5A)
    defChar(0x41, Seq(0x00,0x00,0x18,0x3C,0x66,0xC3,0xC3,0xFF,0xC3,0xC3,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x42, Seq(0x00,0x00,0xFC,0x66,0x66,0x7C,0x66,0x66,0x66,0x66,0xFC,0x00,0x00,0x00,0x00,0x00))
    defChar(0x43, Seq(0x00,0x00,0x3C,0x66,0xC0,0xC0,0xC0,0xC0,0xC0,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x44, Seq(0x00,0x00,0xF8,0x6C,0x66,0x66,0x66,0x66,0x66,0x6C,0xF8,0x00,0x00,0x00,0x00,0x00))
    defChar(0x45, Seq(0x00,0x00,0xFE,0x60,0x60,0x7C,0x60,0x60,0x60,0x60,0xFE,0x00,0x00,0x00,0x00,0x00))
    defChar(0x46, Seq(0x00,0x00,0xFE,0x60,0x60,0x7C,0x60,0x60,0x60,0x60,0x60,0x00,0x00,0x00,0x00,0x00))
    defChar(0x47, Seq(0x00,0x00,0x3C,0x66,0xC0,0xC0,0xCE,0xC6,0xC6,0x66,0x3E,0x00,0x00,0x00,0x00,0x00))
    defChar(0x48, Seq(0x00,0x00,0xC3,0xC3,0xC3,0xFF,0xC3,0xC3,0xC3,0xC3,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x49, Seq(0x00,0x00,0x7E,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x7E,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4A, Seq(0x00,0x00,0x1E,0x06,0x06,0x06,0x06,0x06,0xC6,0xC6,0x7C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4B, Seq(0x00,0x00,0xC6,0xCC,0xD8,0xF0,0xE0,0xF0,0xD8,0xCC,0xC6,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4C, Seq(0x00,0x00,0x60,0x60,0x60,0x60,0x60,0x60,0x60,0x60,0xFE,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4D, Seq(0x00,0x00,0xC3,0xE7,0xFF,0xDB,0xDB,0xC3,0xC3,0xC3,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4E, Seq(0x00,0x00,0xC3,0xE3,0xF3,0xDB,0xCF,0xC7,0xC3,0xC3,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x4F, Seq(0x00,0x00,0x3C,0x66,0xC3,0xC3,0xC3,0xC3,0xC3,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x50, Seq(0x00,0x00,0xFC,0x66,0x66,0x66,0x7C,0x60,0x60,0x60,0x60,0x00,0x00,0x00,0x00,0x00))
    defChar(0x51, Seq(0x00,0x00,0x3C,0x66,0xC3,0xC3,0xC3,0xC3,0xCB,0x6E,0x3C,0x06,0x00,0x00,0x00,0x00))
    defChar(0x52, Seq(0x00,0x00,0xFC,0x66,0x66,0x7C,0x78,0x6C,0x66,0x66,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x53, Seq(0x00,0x00,0x3C,0x66,0x60,0x30,0x18,0x0C,0x06,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x54, Seq(0x00,0x00,0xFF,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x00,0x00,0x00,0x00,0x00))
    defChar(0x55, Seq(0x00,0x00,0xC3,0xC3,0xC3,0xC3,0xC3,0xC3,0xC3,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x56, Seq(0x00,0x00,0xC3,0xC3,0xC3,0x66,0x66,0x3C,0x3C,0x18,0x18,0x00,0x00,0x00,0x00,0x00))
    defChar(0x57, Seq(0x00,0x00,0xC3,0xC3,0xC3,0xC3,0xDB,0xDB,0xFF,0x66,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x58, Seq(0x00,0x00,0xC3,0xC3,0x66,0x3C,0x18,0x3C,0x66,0xC3,0xC3,0x00,0x00,0x00,0x00,0x00))
    defChar(0x59, Seq(0x00,0x00,0xC3,0xC3,0x66,0x3C,0x18,0x18,0x18,0x18,0x18,0x00,0x00,0x00,0x00,0x00))
    defChar(0x5A, Seq(0x00,0x00,0xFE,0x06,0x0C,0x18,0x30,0x60,0xC0,0xC0,0xFE,0x00,0x00,0x00,0x00,0x00))

    // a-z (0x61-0x7A)
    defChar(0x61, Seq(0x00,0x00,0x00,0x00,0x00,0x3C,0x06,0x3E,0x66,0x66,0x3E,0x00,0x00,0x00,0x00,0x00))
    defChar(0x62, Seq(0x00,0x00,0x60,0x60,0x60,0x7C,0x66,0x66,0x66,0x66,0x7C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x63, Seq(0x00,0x00,0x00,0x00,0x00,0x3C,0x66,0x60,0x60,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x64, Seq(0x00,0x00,0x06,0x06,0x06,0x3E,0x66,0x66,0x66,0x66,0x3E,0x00,0x00,0x00,0x00,0x00))
    defChar(0x65, Seq(0x00,0x00,0x00,0x00,0x00,0x3C,0x66,0x7E,0x60,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x66, Seq(0x00,0x00,0x1C,0x36,0x30,0x30,0x7C,0x30,0x30,0x30,0x30,0x00,0x00,0x00,0x00,0x00))
    defChar(0x67, Seq(0x00,0x00,0x00,0x00,0x00,0x3E,0x66,0x66,0x66,0x3E,0x06,0x66,0x3C,0x00,0x00,0x00))
    defChar(0x68, Seq(0x00,0x00,0x60,0x60,0x60,0x7C,0x66,0x66,0x66,0x66,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x69, Seq(0x00,0x00,0x18,0x18,0x00,0x38,0x18,0x18,0x18,0x18,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x6A, Seq(0x00,0x00,0x06,0x06,0x00,0x0E,0x06,0x06,0x06,0x06,0x66,0x3C,0x00,0x00,0x00,0x00))
    defChar(0x6B, Seq(0x00,0x00,0x60,0x60,0x60,0x66,0x6C,0x78,0x6C,0x66,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x6C, Seq(0x00,0x00,0x38,0x18,0x18,0x18,0x18,0x18,0x18,0x18,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x6D, Seq(0x00,0x00,0x00,0x00,0x00,0xE6,0xFF,0xDB,0xDB,0xDB,0xDB,0x00,0x00,0x00,0x00,0x00))
    defChar(0x6E, Seq(0x00,0x00,0x00,0x00,0x00,0x7C,0x66,0x66,0x66,0x66,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x6F, Seq(0x00,0x00,0x00,0x00,0x00,0x3C,0x66,0x66,0x66,0x66,0x3C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x70, Seq(0x00,0x00,0x00,0x00,0x00,0x7C,0x66,0x66,0x66,0x7C,0x60,0x60,0x60,0x00,0x00,0x00))
    defChar(0x71, Seq(0x00,0x00,0x00,0x00,0x00,0x3E,0x66,0x66,0x66,0x3E,0x06,0x06,0x06,0x00,0x00,0x00))
    defChar(0x72, Seq(0x00,0x00,0x00,0x00,0x00,0x6C,0x76,0x60,0x60,0x60,0x60,0x00,0x00,0x00,0x00,0x00))
    defChar(0x73, Seq(0x00,0x00,0x00,0x00,0x00,0x3E,0x60,0x3C,0x06,0x06,0x7C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x74, Seq(0x00,0x00,0x30,0x30,0x30,0x7C,0x30,0x30,0x30,0x36,0x1C,0x00,0x00,0x00,0x00,0x00))
    defChar(0x75, Seq(0x00,0x00,0x00,0x00,0x00,0x66,0x66,0x66,0x66,0x66,0x3E,0x00,0x00,0x00,0x00,0x00))
    defChar(0x76, Seq(0x00,0x00,0x00,0x00,0x00,0x66,0x66,0x66,0x3C,0x3C,0x18,0x00,0x00,0x00,0x00,0x00))
    defChar(0x77, Seq(0x00,0x00,0x00,0x00,0x00,0xC3,0xC3,0xDB,0xDB,0xFF,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x78, Seq(0x00,0x00,0x00,0x00,0x00,0x66,0x3C,0x18,0x3C,0x66,0x66,0x00,0x00,0x00,0x00,0x00))
    defChar(0x79, Seq(0x00,0x00,0x00,0x00,0x00,0x66,0x66,0x66,0x66,0x3E,0x06,0x66,0x3C,0x00,0x00,0x00))
    defChar(0x7A, Seq(0x00,0x00,0x00,0x00,0x00,0x7E,0x0C,0x18,0x30,0x60,0x7E,0x00,0x00,0x00,0x00,0x00))

    data.map(b => BigInt(b.toInt & 0xFF)).toSeq
  }

  // ========================================================================
  // Memories (system clock domain, dual-port for VGA read)
  // ========================================================================

  val charRam = Mem(Bits(8 bits), CHAR_COUNT)
  val attrRam = Mem(Bits(8 bits), CHAR_COUNT)
  val fontRom = Mem(Bits(8 bits), 4096) init(fontData.map(b => B(b, 8 bits)))

  // Palette: 16 entries of RGB565, register-based for easy cross-domain access
  val palette = Vec(Reg(Bits(16 bits)), 16)
  for (i <- 0 until 16) {
    palette(i).init(B(defaultPalette(i), 16 bits))
  }

  // ========================================================================
  // Control Registers (system clock domain)
  // ========================================================================

  val enabled     = Reg(Bool()) init(False)
  val cursorCol   = Reg(UInt(7 bits)) init(0)
  val cursorRow   = Reg(UInt(5 bits)) init(0)
  val defaultAttr = Reg(Bits(8 bits)) init(0x0F)  // White on black
  val vblankSync  = Bool()  // Will be driven from pixel clock domain

  // ========================================================================
  // Scroll / Clear State Machine
  // ========================================================================

  val scrollBusy = Reg(Bool()) init(False)
  val clearBusy  = Reg(Bool()) init(False)

  // Scroll state: copy row by row, column by column
  val scrollSrcAddr  = Reg(UInt(log2Up(CHAR_COUNT) bits)) init(0)
  val scrollDstAddr  = Reg(UInt(log2Up(CHAR_COUNT) bits)) init(0)
  val scrollCount    = Reg(UInt(log2Up(CHAR_COUNT + 1) bits)) init(0)
  val scrollCharData = Reg(Bits(8 bits)) init(0)
  val scrollAttrData = Reg(Bits(8 bits)) init(0)
  val scrollPhase    = Reg(UInt(2 bits)) init(0)  // 0=read, 1=have data, 2=write

  // Clear state
  val clearAddr  = Reg(UInt(log2Up(CHAR_COUNT) bits)) init(0)

  // Memory write port arbitration for system clock
  val sysCharWrEn   = False
  val sysCharWrAddr = UInt(log2Up(CHAR_COUNT) bits)
  val sysCharWrData = Bits(8 bits)
  val sysAttrWrEn   = False
  val sysAttrWrAddr = UInt(log2Up(CHAR_COUNT) bits)
  val sysAttrWrData = Bits(8 bits)

  sysCharWrAddr := 0
  sysCharWrData := 0
  sysAttrWrAddr := 0
  sysAttrWrData := 0

  // Memory read port for scroll (system clock side)
  val sysCharRdAddr = UInt(log2Up(CHAR_COUNT) bits)
  sysCharRdAddr := 0
  val sysCharRdData = charRam.readSync(sysCharRdAddr)
  val sysAttrRdData = attrRam.readSync(sysCharRdAddr)

  // Write ports (system clock domain)
  charRam.write(
    address = sysCharWrAddr,
    data    = sysCharWrData,
    enable  = sysCharWrEn
  )
  attrRam.write(
    address = sysAttrWrAddr,
    data    = sysAttrWrData,
    enable  = sysAttrWrEn
  )

  // Scroll state machine
  when(scrollBusy) {
    switch(scrollPhase) {
      is(0) {
        // Phase 0: issue read for source address
        sysCharRdAddr := scrollSrcAddr
        scrollPhase := 1
      }
      is(1) {
        // Phase 1: read data available (1-cycle latency), capture it
        sysCharRdAddr := scrollSrcAddr  // Keep address stable for readSync
        scrollCharData := sysCharRdData
        scrollAttrData := sysAttrRdData
        scrollPhase := 2
      }
      is(2) {
        // Phase 2: write captured data to destination
        sysCharWrEn   := True
        sysCharWrAddr := scrollDstAddr
        sysCharWrData := scrollCharData
        sysAttrWrEn   := True
        sysAttrWrAddr := scrollDstAddr
        sysAttrWrData := scrollAttrData

        when(scrollCount === 0) {
          scrollBusy := False
          scrollPhase := 0
        } otherwise {
          scrollSrcAddr := scrollSrcAddr + 1
          scrollDstAddr := scrollDstAddr + 1
          scrollCount   := scrollCount - 1
          scrollPhase := 0
        }
      }
    }
  }

  // Clear state
  val clearEndAddr = Reg(UInt(log2Up(CHAR_COUNT) bits)) init(CHAR_COUNT - 1)

  // Clear state machine
  when(clearBusy) {
    sysCharWrEn   := True
    sysCharWrAddr := clearAddr
    sysCharWrData := 0x20  // Space
    sysAttrWrEn   := True
    sysAttrWrAddr := clearAddr
    sysAttrWrData := defaultAttr

    when(clearAddr === clearEndAddr) {
      clearBusy := False
    } otherwise {
      clearAddr := clearAddr + 1
    }
  }

  // ========================================================================
  // Cursor auto-advance with scroll
  // ========================================================================

  def advanceCursor(): Unit = {
    when(cursorCol >= (COLS - 1)) {
      cursorCol := 0
      when(cursorRow >= (ROWS - 1)) {
        // Need to scroll: initiate scroll, cursor stays at last row
        scrollBusy   := True
        scrollSrcAddr := COLS      // Start from row 1
        scrollDstAddr := 0         // Copy to row 0
        scrollCount  := ((ROWS - 1) * COLS - 1)  // Copy (ROWS-1)*COLS entries
        scrollPhase  := 0

        // After scroll completes, the clear of last row is handled:
        // We start a clear of just the last row after scroll completes.
        // For simplicity, we'll clear the last row as part of the scroll
        // by extending the scroll to fill last row with spaces.
        // Actually, let's keep it simple: scroll copies rows 1-29 to 0-28,
        // then we need to clear row 29. We'll do this by using a two-phase approach:
        // 1. scrollBusy copies rows 1-29 to 0-28
        // 2. When scrollBusy finishes, we set clearBusy for just the last row
      } otherwise {
        cursorRow := cursorRow + 1
      }
    } otherwise {
      cursorCol := cursorCol + 1
    }
  }

  // When scroll finishes, clear the last row
  val scrollWasBusy = RegNext(scrollBusy) init(False)
  when(scrollWasBusy && !scrollBusy) {
    // Scroll just completed — clear last row
    clearBusy    := True
    clearAddr    := ((ROWS - 1) * COLS)
    clearEndAddr := (CHAR_COUNT - 1)
  }

  // Note: clearEndAddr is set to CHAR_COUNT-1 for full clear, or
  // to the last address of the bottom row for scroll-triggered clear.

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      // Status: bit0=enabled, bit1=vblank, bit2=scrollBusy
      io.rdData(0) := enabled
      io.rdData(1) := vblankSync
      io.rdData(2) := scrollBusy || clearBusy
    }
    is(1) {
      // Cursor position: col[6:0], row[12:8]
      io.rdData(6 downto 0)  := cursorCol.asBits
      io.rdData(12 downto 8) := cursorRow.asBits
    }
    is(3) {
      // Default attribute
      io.rdData(7 downto 0) := defaultAttr
    }
    is(5) {
      // Columns
      io.rdData := B(COLS, 32 bits)
    }
    is(6) {
      // Rows
      io.rdData := B(ROWS, 32 bits)
    }
  }

  // ========================================================================
  // Register Write Handling
  // ========================================================================

  when(io.wr && !scrollBusy && !clearBusy) {
    switch(io.addr) {
      is(0) {
        // Control: bit0=enable
        enabled := io.wrData(0)
      }
      is(1) {
        // Set cursor position
        cursorCol := io.wrData(6 downto 0).asUInt
        cursorRow := io.wrData(12 downto 8).asUInt
      }
      is(2) {
        // Write char+attr at cursor, auto-advance
        val charCode = io.wrData(7 downto 0)
        val attr     = io.wrData(15 downto 8)
        val addr     = (cursorRow * COLS + cursorCol).resize(log2Up(CHAR_COUNT))

        sysCharWrEn   := True
        sysCharWrAddr := addr
        sysCharWrData := charCode
        sysAttrWrEn   := True
        sysAttrWrAddr := addr
        sysAttrWrData := attr

        advanceCursor()
      }
      is(3) {
        // Set default attribute
        defaultAttr := io.wrData(7 downto 0)
      }
      is(4) {
        // Palette write: index[19:16], rgb565[15:0]
        val index  = io.wrData(19 downto 16).asUInt
        val rgb565 = io.wrData(15 downto 0)
        palette(index) := rgb565
      }
      is(7) {
        // Direct write: char[7:0], attr[15:8], col[22:16], row[28:24]
        val charCode = io.wrData(7 downto 0)
        val attr     = io.wrData(15 downto 8)
        val col      = io.wrData(22 downto 16).asUInt
        val row      = io.wrData(28 downto 24).asUInt
        val addr     = (row * COLS + col).resize(log2Up(CHAR_COUNT))

        sysCharWrEn   := True
        sysCharWrAddr := addr
        sysCharWrData := charCode
        sysAttrWrEn   := True
        sysAttrWrAddr := addr
        sysAttrWrData := attr
      }
      is(8) {
        // Clear screen
        clearBusy   := True
        clearAddr   := 0
        clearEndAddr := (CHAR_COUNT - 1)
      }
      is(9) {
        // Scroll up 1 row
        scrollBusy    := True
        scrollSrcAddr := COLS
        scrollDstAddr := 0
        scrollCount   := ((ROWS - 1) * COLS - 1)
        scrollPhase   := 0
      }
    }
  }

  // ========================================================================
  // VGA Pixel Generation (pixel clock domain)
  // ========================================================================

  val vgaArea = new ClockingArea(vgaCd) {

    // Horizontal and vertical counters
    val hCounter = Reg(UInt(10 bits)) init(0)
    val vCounter = Reg(UInt(10 bits)) init(0)

    when(hCounter === (H_TOTAL - 1)) {
      hCounter := 0
      when(vCounter === (V_TOTAL - 1)) {
        vCounter := 0
      } otherwise {
        vCounter := vCounter + 1
      }
    } otherwise {
      hCounter := hCounter + 1
    }

    // ====================================================================
    // 3-stage pixel pipeline
    // Pipeline compensates for RAM read latency.
    // We use (hCounter + 3) to look ahead, so the data arrives just in time.
    // hSync, activeDisplay, and bitIndex all use hLookAhead so that after
    // 3 stages of pipelining they align exactly with the pixel data output.
    // This produces standard VGA timing at the output pins.
    // ====================================================================

    // Stage 0: Compute character address from look-ahead pixel position
    val nextLineWrap = (hCounter + 3 >= H_TOTAL)
    val hLookAhead = Mux(
      nextLineWrap,
      hCounter + 3 - H_TOTAL,
      (hCounter + 3).resize(10)
    )
    // When hLookAhead wraps, we're fetching for the next scan line
    val vLookAhead = Mux(
      nextLineWrap,
      Mux(vCounter === (V_TOTAL - 1), U(0, 10 bits), vCounter + 1),
      vCounter
    )

    // Sync and blanking derived from look-ahead to align with pixel data
    val hSync = RegNext(hLookAhead >= H_SYNC_START && hLookAhead < H_SYNC_END) init(False)
    val vSync = RegNext(vLookAhead >= V_SYNC_START && vLookAhead < V_SYNC_END) init(False)
    val activeDisplay = (hLookAhead < H_ACTIVE && vLookAhead < V_ACTIVE)

    // VBlank detection (for interrupt)
    val vBlank = (vCounter >= V_ACTIVE)
    val vBlankEdge = vBlank && !RegNext(vBlank, False)
    val charCol_s0 = (hLookAhead >> 3).resize(7)   // hPixel / 8
    val charRow_s0 = (vLookAhead >> 4).resize(5)    // vPixel / 16
    val charAddr_s0 = (charRow_s0 * COLS + charCol_s0).resize(log2Up(CHAR_COUNT))

    // Read character and attribute RAM (port B, pixel clock domain)
    val charData_s1 = charRam.readSync(charAddr_s0, clockCrossing = true)
    val attrData_s1 = attrRam.readSync(charAddr_s0, clockCrossing = true)

    // Stage 1 -> 2: Read font ROM using character code and row
    val vRow_s1 = RegNext(vLookAhead(3 downto 0))  // vPixel % 16
    // charCode * 16 = charCode << 4; + row offset
    val fontAddrCalc = (charData_s1.asUInt << 4).resize(12) + vRow_s1.resize(12)
    val fontByte_s2 = fontRom.readSync(fontAddrCalc, clockCrossing = true)

    // Pipeline attribute and hCounter for color lookup
    val attrData_s2 = RegNext(attrData_s1)
    val hLookAhead_s1 = RegNext(hLookAhead)
    val hLookAhead_s2 = RegNext(hLookAhead_s1)
    val activeDisplay_s1 = RegNext(activeDisplay)
    val activeDisplay_s2 = RegNext(activeDisplay_s1)
    val activeDisplay_s3 = RegNext(activeDisplay_s2)
    val hSync_s2 = RegNext(hSync)
    val vSync_s2 = RegNext(vSync)
    val hSync_s3 = RegNext(hSync_s2)
    val vSync_s3 = RegNext(vSync_s2)

    // Stage 2 -> 3: Select pixel bit and color
    val bitIndex_s2 = (7 - hLookAhead_s2(2 downto 0)).resize(3)  // 7 - (hPixel % 8)
    val fgIndex_s2 = attrData_s2(3 downto 0).asUInt
    val bgIndex_s2 = attrData_s2(7 downto 4).asUInt

    // Palette lookup (register-based, no latency concern)
    // Use BufferCC for cross-domain palette access
    val paletteSynced = Vec(Bits(16 bits), 16)
    for (i <- 0 until 16) {
      paletteSynced(i) := BufferCC(palette(i))
    }

    val fgColor_s2 = paletteSynced(fgIndex_s2)
    val bgColor_s2 = paletteSynced(bgIndex_s2)

    // Stage 3: Final pixel output
    val pixelBit_s3 = RegNext(fontByte_s2(bitIndex_s2))
    val fgColor_s3  = RegNext(fgColor_s2)
    val bgColor_s3  = RegNext(bgColor_s2)

    val pixelColor = Mux(pixelBit_s3, fgColor_s3, bgColor_s3)

    // Synchronized enable signal from system clock domain
    val enabledSync = BufferCC(enabled)

    // Output assignment
    io.vgaHsync := !hSync_s3   // Active low
    io.vgaVsync := !vSync_s3   // Active low
    io.vgaR := Mux(activeDisplay_s3 && enabledSync, pixelColor(15 downto 11), B"00000")
    io.vgaG := Mux(activeDisplay_s3 && enabledSync, pixelColor(10 downto 5),  B"000000")
    io.vgaB := Mux(activeDisplay_s3 && enabledSync, pixelColor(4 downto 0),   B"00000")

    // VSync interrupt: rising edge of vSync, synchronized to system clock
    val vsyncPulse = vBlankEdge
  }

  // ========================================================================
  // Cross-clock-domain: VSync interrupt and VBlank status
  // ========================================================================

  // Synchronize vblank to system clock
  vblankSync := BufferCC(vgaArea.vBlank)

  // VSync interrupt: synchronize pulse from pixel domain to system domain
  // Use a toggle-based CDC for the single-cycle pulse
  val vgaVsyncToggle = new ClockingArea(vgaCd) {
    val toggle = Reg(Bool()) init(False)
    when(vgaArea.vBlankEdge) {
      toggle := !toggle
    }
  }
  val vsyncToggleSys = BufferCC(vgaVsyncToggle.toggle)
  val vsyncToggleSysPrev = RegNext(vsyncToggleSys) init(False)
  io.vsyncInterrupt := vsyncToggleSys ^ vsyncToggleSysPrev
}
