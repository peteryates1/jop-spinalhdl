package jop.sim

import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable

/**
 * Simulation-time CH376T USB host controller emulator.
 *
 * Emulates the CH376T SPI command protocol for USB keyboard (HID) and
 * SD card storage. In a real system the CH376T connects via SPI to JOP
 * and provides a USB-A host port and SD card slot.
 *
 * SPI protocol: Mode 0 (CPOL=0, CPHA=0), MSB-first, 8-bit frames.
 * CS low selects device. First byte after CS low is the command code.
 * Subsequent bytes are command-specific data (in or out).
 * INT# pin asserted low when an event is ready.
 *
 * Note: The 0x57/0xAB sync bytes are for UART mode only, not SPI.
 *
 * Usage from a SpinalHDL doSim block:
 * {{{
 *   val ch376 = new SimCH376T(sdImagePath = Some("disk.img"))
 *   ch376.setKeySource(display)
 *   fork {
 *     while (true) {
 *       dut.clockDomain.waitSampling()
 *       val miso = ch376.tick(
 *         dut.io.spiSclk.toBoolean,
 *         dut.io.spiMosi.toBoolean,
 *         dut.io.spiCs.toBoolean)
 *       dut.io.spiMiso #= miso
 *       dut.io.ch376Int #= ch376.intActive
 *     }
 *   }
 * }}}
 */
class SimCH376T(sdImagePath: Option[String] = None) {

  // ===========================================================================
  // CH376 Command codes
  // ===========================================================================
  object Cmd {
    val GET_IC_VER     = 0x01
    val RESET_ALL      = 0x05
    val CHECK_EXIST    = 0x06
    val SET_USB_MODE   = 0x15
    val GET_STATUS     = 0x22
    val RD_USB_DATA0   = 0x27
    val WR_HOST_DATA   = 0x2C
    val WR_REQ_DATA    = 0x2D
    val SET_FILE_NAME  = 0x2F
    val DISK_CONNECT   = 0x30
    val DISK_MOUNT     = 0x31
    val FILE_OPEN      = 0x32
    val FILE_CLOSE     = 0x36
    val BYTE_LOCATE    = 0x39
    val BYTE_READ      = 0x3A
    val BYTE_RD_GO     = 0x3B
    val BYTE_WRITE     = 0x3C
    val BYTE_WR_GO     = 0x3D
    val DISK_CAPACITY  = 0x3E
    val DISK_QUERY     = 0x3F
    val AUTO_SETUP     = 0x4D
    val ISSUE_TKN_X    = 0x4E
    val DISK_READ      = 0x54
    val DISK_RD_GO     = 0x55
    val DISK_WRITE     = 0x56
    val DISK_WR_GO     = 0x57
  }

  // ===========================================================================
  // USB interrupt / status codes
  // ===========================================================================
  object Status {
    val USB_INT_SUCCESS    = 0x14
    val USB_INT_CONNECT    = 0x15
    val USB_INT_DISCONNECT = 0x16
    val USB_INT_BUF_OVER   = 0x17
    val USB_INT_DISK_READ  = 0x1D
    val USB_INT_DISK_WRITE = 0x1E
    val USB_INT_DISK_ERR   = 0x1F
    val ERR_MISS_FILE      = 0x42
    val CMD_RET_SUCCESS    = 0x51
    val CMD_RET_ABORT      = 0x5F
    val ERR_DISK_DISCON    = 0x82
  }

  // ===========================================================================
  // USB modes (SET_USB_MODE parameter)
  // ===========================================================================
  object UsbMode {
    val DEFAULT   = 0x00
    val SD_HOST   = 0x03  // SD card host mode
    val USB_HOST  = 0x06  // USB host, auto-detect device
  }

  // ===========================================================================
  // SPI state
  // ===========================================================================
  private var prevSclk: Boolean = false
  private var prevCs: Boolean = true  // CS idle high

  // Bit-level SPI shift registers
  private var bitCount: Int = 0
  private var shiftIn: Int = 0       // MOSI → CH376 (command/data from host)
  private var shiftOut: Int = 0      // CH376 → MISO (response to host)
  private var misoValue: Boolean = false

  // Byte-level command state
  private var byteIndex: Int = 0     // byte position within current command
  private var currentCmd: Int = 0    // current command code
  private var cmdActive: Boolean = false

  // Response FIFO for multi-byte responses
  private val responseQueue = new mutable.Queue[Int]()

  // ===========================================================================
  // Device state
  // ===========================================================================
  private var usbMode: Int = UsbMode.DEFAULT
  private var interruptStatus: Int = 0
  private var _intActive: Boolean = false  // INT# pin (active low in hardware)

  // Keyboard state (linked to SimDisplay)
  private var keySource: Option[SimDisplay] = None
  private val hidReportQueue = new ConcurrentLinkedQueue[Array[Int]]()

  // SD card state
  private var sdImage: Option[RandomAccessFile] = None
  private var sdMounted: Boolean = false
  private var sdSectorBuf: Array[Byte] = new Array[Byte](512)
  private var sdReadLba: Long = 0
  private var sdReadRemaining: Int = 0
  private var sdWriteLba: Long = 0
  private var sdWriteRemaining: Int = 0
  private var sdWriteOffset: Int = 0

  // Data buffer for RD_USB_DATA0 responses
  private val dataBuf = new mutable.ArrayBuffer[Int]()
  private var dataBufReadIdx: Int = 0

  // Command input accumulator (for multi-byte input commands)
  private val cmdInput = new mutable.ArrayBuffer[Int]()

  // Initialize SD image if provided
  sdImagePath.foreach { path =>
    try {
      sdImage = Some(new RandomAccessFile(path, "rw"))
    } catch {
      case e: Exception =>
        println(s"SimCH376T: WARNING: Could not open SD image '$path': ${e.getMessage}")
    }
  }

  // ===========================================================================
  // Public API
  // ===========================================================================

  /**
   * Process one system clock tick.
   *
   * Samples SPI signals and processes byte-level protocol on SCLK edges.
   * Returns the current MISO value.
   *
   * @param sclk  SPI clock
   * @param mosi  Master Out Slave In
   * @param cs    Chip Select (active low: false = selected)
   * @return MISO value for this tick
   */
  def tick(sclk: Boolean, mosi: Boolean, cs: Boolean): Boolean = {
    // CS high = deselected, reset SPI state
    if (cs) {
      if (!prevCs) {
        // Rising edge of CS: end of transaction
        finishCommand()
      }
      bitCount = 0
      shiftIn = 0
      prevSclk = sclk
      prevCs = cs
      misoValue = true  // MISO idles high when deselected
      return misoValue
    }

    // CS just went low: start of new transaction
    if (prevCs && !cs) {
      byteIndex = 0
      cmdActive = false
      currentCmd = 0
      cmdInput.clear()
      bitCount = 0
      shiftIn = 0
      loadNextResponseByte()
    }

    // SPI Mode 0: sample MOSI on rising SCLK, shift out MISO on falling SCLK
    val sclkRising = !prevSclk && sclk
    val sclkFalling = prevSclk && !sclk

    if (sclkRising) {
      // Sample MOSI bit (MSB first)
      shiftIn = ((shiftIn << 1) | (if (mosi) 1 else 0)) & 0xFF
      bitCount += 1

      if (bitCount == 8) {
        // Complete byte received from host
        val rxByte = shiftIn
        processByte(rxByte)
        bitCount = 0
        shiftIn = 0

        // Load next response byte for the next 8-bit transfer
        loadNextResponseByte()
      }
    }

    if (sclkFalling) {
      // Shift out MISO (MSB first)
      misoValue = (shiftOut & 0x80) != 0
      shiftOut = (shiftOut << 1) & 0xFF
    }

    prevSclk = sclk
    prevCs = cs
    misoValue
  }

  /** INT# pin state. True = interrupt active (pin driven low in real hardware). */
  def intActive: Boolean = _intActive

  /** Link to SimDisplay for keyboard events. */
  def setKeySource(display: SimDisplay): Unit = {
    keySource = Some(display)
  }

  /** Close SD card image file. */
  def close(): Unit = {
    sdImage.foreach(_.close())
    sdImage = None
  }

  // ===========================================================================
  // SPI byte-level protocol
  // ===========================================================================

  private def processByte(rxByte: Int): Unit = {
    if (byteIndex == 0) {
      // First byte is the command code
      currentCmd = rxByte
      cmdActive = true
      cmdInput.clear()
      handleCommand()
    } else {
      // Subsequent bytes: command input data
      cmdInput += rxByte
      handleCommandData()
    }
    byteIndex += 1
  }

  private def loadNextResponseByte(): Unit = {
    if (responseQueue.nonEmpty) {
      shiftOut = responseQueue.dequeue()
    } else {
      shiftOut = 0x00  // Default response when nothing queued
    }
  }

  private def finishCommand(): Unit = {
    cmdActive = false
    responseQueue.clear()
  }

  private def respond(bytes: Int*): Unit = {
    bytes.foreach(b => responseQueue.enqueue(b & 0xFF))
  }

  // ===========================================================================
  // Command handling
  // ===========================================================================

  private def handleCommand(): Unit = {
    currentCmd match {

      case Cmd.GET_IC_VER =>
        // Returns chip version: 0x43 for CH376
        respond(0x43)

      case Cmd.CHECK_EXIST =>
        // Next byte is input; response is bitwise complement
        // Handled in handleCommandData()

      case Cmd.SET_USB_MODE =>
        // Next byte is mode; response is status
        // Handled in handleCommandData()

      case Cmd.GET_STATUS =>
        // Returns current interrupt status byte
        respond(interruptStatus)
        _intActive = false

      case Cmd.RD_USB_DATA0 =>
        // Returns: length byte, then data bytes
        sendDataBuffer()

      case Cmd.DISK_CONNECT =>
        // Check if SD card is present
        if (sdImage.isDefined) {
          interruptStatus = Status.USB_INT_SUCCESS
        } else {
          interruptStatus = Status.ERR_DISK_DISCON
        }
        _intActive = true
        respond(interruptStatus)

      case Cmd.DISK_MOUNT =>
        // Mount SD card filesystem
        if (sdImage.isDefined) {
          sdMounted = true
          interruptStatus = Status.USB_INT_SUCCESS
        } else {
          interruptStatus = Status.ERR_DISK_DISCON
        }
        _intActive = true
        respond(interruptStatus)

      case Cmd.DISK_READ =>
        // Next 5 bytes: LBA[0..3] + sectorCount
        // Handled in handleCommandData()

      case Cmd.DISK_RD_GO =>
        // Continue reading next sector
        handleDiskRdGo()

      case Cmd.DISK_WRITE =>
        // Next 5 bytes: LBA[0..3] + sectorCount
        // Handled in handleCommandData()

      case Cmd.DISK_WR_GO =>
        // Continue writing next sector
        handleDiskWrGo()

      case Cmd.WR_HOST_DATA =>
        // Next byte is length, then data bytes follow
        // Handled in handleCommandData()

      case Cmd.RESET_ALL =>
        usbMode = UsbMode.DEFAULT
        interruptStatus = 0
        _intActive = false
        sdMounted = false

      case Cmd.AUTO_SETUP =>
        // Auto-configure USB device (for keyboard)
        if (usbMode == UsbMode.USB_HOST) {
          // Simulate successful USB device enumeration
          interruptStatus = Status.USB_INT_SUCCESS
          _intActive = true
        }

      case Cmd.ISSUE_TKN_X =>
        // Next 2 bytes: toggle + token/endpoint
        // Used to poll USB HID endpoint for keyboard data
        // Handled in handleCommandData()

      case Cmd.DISK_CAPACITY =>
        // Returns disk capacity; data available via RD_USB_DATA0
        sdImage match {
          case Some(f) =>
            val sectors = (f.length() / 512).toInt
            dataBuf.clear()
            // 4 bytes: total sectors (big-endian)
            dataBuf += ((sectors >> 24) & 0xFF)
            dataBuf += ((sectors >> 16) & 0xFF)
            dataBuf += ((sectors >> 8) & 0xFF)
            dataBuf += (sectors & 0xFF)
            // 4 bytes: bytes per sector (512)
            dataBuf += 0x00
            dataBuf += 0x00
            dataBuf += 0x02
            dataBuf += 0x00
            dataBufReadIdx = 0
            interruptStatus = Status.USB_INT_SUCCESS
          case None =>
            interruptStatus = Status.ERR_DISK_DISCON
        }
        _intActive = true

      case _ =>
        // Unknown command — ignore
        if (verbose) println(f"SimCH376T: unknown command 0x$currentCmd%02X")
    }
  }

  private def handleCommandData(): Unit = {
    currentCmd match {

      case Cmd.CHECK_EXIST if cmdInput.length == 1 =>
        // Respond with bitwise complement of input
        respond((~cmdInput(0)) & 0xFF)

      case Cmd.SET_USB_MODE if cmdInput.length == 1 =>
        usbMode = cmdInput(0)
        // Mode switch always succeeds in simulation
        respond(Status.CMD_RET_SUCCESS)
        // In USB host mode, simulate device connection after a moment
        if (usbMode == UsbMode.USB_HOST) {
          interruptStatus = Status.USB_INT_CONNECT
          _intActive = true
        }

      case Cmd.DISK_READ if cmdInput.length == 5 =>
        // LBA (4 bytes little-endian) + sector count (1 byte)
        sdReadLba = (cmdInput(0) & 0xFF).toLong |
          ((cmdInput(1) & 0xFF).toLong << 8) |
          ((cmdInput(2) & 0xFF).toLong << 16) |
          ((cmdInput(3) & 0xFF).toLong << 24)
        sdReadRemaining = cmdInput(4) & 0xFF
        handleDiskRdGo()

      case Cmd.DISK_WRITE if cmdInput.length == 5 =>
        // LBA (4 bytes little-endian) + sector count (1 byte)
        sdWriteLba = (cmdInput(0) & 0xFF).toLong |
          ((cmdInput(1) & 0xFF).toLong << 8) |
          ((cmdInput(2) & 0xFF).toLong << 16) |
          ((cmdInput(3) & 0xFF).toLong << 24)
        sdWriteRemaining = cmdInput(4) & 0xFF
        sdWriteOffset = 0
        handleDiskWrGo()

      case Cmd.ISSUE_TKN_X if cmdInput.length == 2 =>
        // Toggle + (endpoint << 4 | PID)
        // Check for keyboard HID data
        pollKeyboard()
        if (dataBuf.nonEmpty) {
          interruptStatus = Status.USB_INT_SUCCESS
        } else {
          // NAK — no data available
          interruptStatus = 0x2A  // USB_INT_EP_NAK
        }
        _intActive = true

      case Cmd.WR_HOST_DATA if cmdInput.length >= 1 =>
        val len = cmdInput(0)
        if (cmdInput.length == len + 1) {
          // All data bytes received for write
          handleWriteData()
        }

      case _ =>
        // Accumulating input bytes, not yet complete
    }
  }

  // ===========================================================================
  // SD card sector read/write
  // ===========================================================================

  private def handleDiskRdGo(): Unit = {
    if (sdReadRemaining <= 0 || sdImage.isEmpty) {
      interruptStatus = if (sdReadRemaining <= 0) Status.USB_INT_SUCCESS
                        else Status.ERR_DISK_DISCON
      _intActive = true
      return
    }

    // Read one sector from image file
    val offset = sdReadLba * 512
    try {
      val f = sdImage.get
      if (offset + 512 <= f.length()) {
        f.seek(offset)
        f.readFully(sdSectorBuf)
      } else {
        // Beyond image: return zeros
        java.util.Arrays.fill(sdSectorBuf, 0.toByte)
      }

      // Load sector into data buffer (CH376 transfers in 64-byte chunks)
      dataBuf.clear()
      dataBufReadIdx = 0
      for (i <- 0 until 512) {
        dataBuf += (sdSectorBuf(i) & 0xFF)
      }

      sdReadLba += 1
      sdReadRemaining -= 1

      interruptStatus = Status.USB_INT_DISK_READ
    } catch {
      case e: Exception =>
        interruptStatus = Status.USB_INT_DISK_ERR
        if (verbose) println(s"SimCH376T: disk read error at LBA $sdReadLba: ${e.getMessage}")
    }
    _intActive = true
  }

  private def handleDiskWrGo(): Unit = {
    if (sdWriteRemaining <= 0 || sdImage.isEmpty) {
      interruptStatus = if (sdWriteRemaining <= 0) Status.USB_INT_SUCCESS
                        else Status.ERR_DISK_DISCON
      _intActive = true
      return
    }

    // Request data from host (CH376 sends WR_REQ_DATA to request 64-byte chunks)
    dataBuf.clear()
    dataBufReadIdx = 0
    sdWriteOffset = 0
    interruptStatus = Status.USB_INT_DISK_WRITE
    _intActive = true
  }

  private def handleWriteData(): Unit = {
    // Data from WR_HOST_DATA: write to SD card sector buffer
    val len = cmdInput(0)
    for (i <- 1 to len) {
      if (sdWriteOffset < 512) {
        sdSectorBuf(sdWriteOffset) = cmdInput(i).toByte
        sdWriteOffset += 1
      }
    }

    // If we've filled a sector, write it to the image
    if (sdWriteOffset >= 512) {
      sdImage.foreach { f =>
        val offset = sdWriteLba * 512
        try {
          f.seek(offset)
          f.write(sdSectorBuf)
        } catch {
          case e: Exception =>
            if (verbose) println(s"SimCH376T: disk write error at LBA $sdWriteLba: ${e.getMessage}")
        }
      }
      sdWriteLba += 1
      sdWriteRemaining -= 1
      sdWriteOffset = 0
    }
  }

  // ===========================================================================
  // RD_USB_DATA0 response
  // ===========================================================================

  private def sendDataBuffer(): Unit = {
    // CH376 sends data in 64-byte chunks max
    val remaining = dataBuf.length - dataBufReadIdx
    val chunkLen = Math.min(remaining, 64)

    // First byte: length
    respond(chunkLen)
    // Then data bytes
    for (i <- 0 until chunkLen) {
      respond(dataBuf(dataBufReadIdx + i))
    }
    dataBufReadIdx += chunkLen
  }

  // ===========================================================================
  // USB HID keyboard
  // ===========================================================================

  /** Poll keyboard source for new HID reports. */
  private def pollKeyboard(): Unit = {
    keySource.foreach { display =>
      display.pollKeyPress() match {
        case Some(keyEvent) =>
          // Build HID keyboard report from AWT KeyEvent
          val report = buildHidReport(keyEvent)
          dataBuf.clear()
          dataBufReadIdx = 0
          report.foreach(b => dataBuf += b)
        case None =>
          // No key — dataBuf stays empty
      }
    }
  }

  /**
   * Build an 8-byte USB HID keyboard report from an AWT KeyEvent.
   *
   * HID keyboard report format:
   *   Byte 0: Modifier keys (bit 0=L-Ctrl, 1=L-Shift, 2=L-Alt, 3=L-GUI,
   *                           4=R-Ctrl, 5=R-Shift, 6=R-Alt, 7=R-GUI)
   *   Byte 1: Reserved (0x00)
   *   Bytes 2-7: Up to 6 simultaneous keycodes (USB HID usage table)
   */
  private def buildHidReport(keyEvent: java.awt.event.KeyEvent): Array[Int] = {
    val report = Array.fill(8)(0)

    // Modifier keys
    val mods = keyEvent.getModifiersEx
    if ((mods & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0)   report(0) |= 0x02  // L-Shift
    if ((mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0)    report(0) |= 0x01  // L-Ctrl
    if ((mods & java.awt.event.InputEvent.ALT_DOWN_MASK) != 0)     report(0) |= 0x04  // L-Alt
    if ((mods & java.awt.event.InputEvent.META_DOWN_MASK) != 0)    report(0) |= 0x08  // L-GUI

    // Map AWT keycode to USB HID usage
    report(2) = awtToHidUsage(keyEvent.getKeyCode)

    report
  }

  /**
   * Map AWT VK_ keycode to USB HID keyboard usage code.
   *
   * See USB HID Usage Tables 1.12, Section 10 (Keyboard/Keypad Page 0x07).
   */
  private def awtToHidUsage(vk: Int): Int = {
    import java.awt.event.KeyEvent._
    vk match {
      // Letters A-Z → HID 0x04-0x1D
      case k if k >= VK_A && k <= VK_Z => 0x04 + (k - VK_A)

      // Digits 1-9 → HID 0x1E-0x26, 0 → 0x27
      case k if k >= VK_1 && k <= VK_9 => 0x1E + (k - VK_1)
      case VK_0 => 0x27

      // Special keys
      case VK_ENTER     => 0x28
      case VK_ESCAPE    => 0x29
      case VK_BACK_SPACE => 0x2A
      case VK_TAB       => 0x2B
      case VK_SPACE     => 0x2C
      case VK_MINUS     => 0x2D
      case VK_EQUALS    => 0x2E
      case VK_OPEN_BRACKET  => 0x2F
      case VK_CLOSE_BRACKET => 0x30
      case VK_BACK_SLASH    => 0x31
      case VK_SEMICOLON => 0x33
      case VK_QUOTE     => 0x34
      case VK_BACK_QUOTE => 0x35
      case VK_COMMA     => 0x36
      case VK_PERIOD    => 0x37
      case VK_SLASH     => 0x38

      // Caps Lock
      case VK_CAPS_LOCK => 0x39

      // Function keys F1-F12 → HID 0x3A-0x45
      case k if k >= VK_F1 && k <= VK_F12 => 0x3A + (k - VK_F1)

      // Navigation
      case VK_INSERT    => 0x49
      case VK_HOME      => 0x4A
      case VK_PAGE_UP   => 0x4B
      case VK_DELETE    => 0x4C
      case VK_END       => 0x4D
      case VK_PAGE_DOWN => 0x4E
      case VK_RIGHT     => 0x4F
      case VK_LEFT      => 0x50
      case VK_DOWN      => 0x51
      case VK_UP        => 0x52

      // Keypad
      case VK_NUM_LOCK  => 0x53
      case VK_DIVIDE    => 0x54
      case VK_MULTIPLY  => 0x55
      case VK_SUBTRACT  => 0x56
      case VK_ADD       => 0x57

      case _ => 0x00  // No key / unmapped
    }
  }

  // ===========================================================================
  // Debug
  // ===========================================================================

  /** Enable verbose debug output. */
  var verbose: Boolean = false
}
