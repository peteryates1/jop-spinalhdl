package jop.sim

import org.scalatest.funsuite.AnyFunSuite

/**
 * Standalone test for SimCH376T SPI protocol.
 *
 * Drives SPI signals directly (no hardware simulation) to verify the
 * CH376T emulator's command/response protocol works correctly.
 *
 * Usage:
 *   sbt "testOnly jop.sim.SimCH376TTest"
 */
class SimCH376TTest extends AnyFunSuite {

  /** Helper: perform one SPI byte exchange (Mode 0, MSB-first).
   *  Drives SCLK with CS already low, returns MISO byte.
   *  SPI Mode 0: slave shifts MISO on falling edge, master samples on rising edge. */
  private def spiXfer(ch376: SimCH376T, txByte: Int, cs: Boolean = false): Int = {
    var rxByte = 0
    for (bit <- 7 to 0 by -1) {
      val mosi = (txByte >> bit) & 1
      // Rising edge: sample MISO (set on previous falling edge), device samples MOSI
      val miso = ch376.tick(sclk = true, mosi = mosi != 0, cs = cs)
      rxByte = (rxByte << 1) | (if (miso) 1 else 0)
      // Falling edge: device shifts out next MISO bit
      ch376.tick(sclk = false, mosi = mosi != 0, cs = cs)
    }
    rxByte
  }

  /** Assert CS low (start transaction). */
  private def csLow(ch376: SimCH376T): Unit = {
    ch376.tick(sclk = false, mosi = false, cs = false)
  }

  /** Assert CS high (end transaction). */
  private def csHigh(ch376: SimCH376T): Unit = {
    ch376.tick(sclk = false, mosi = false, cs = true)
  }

  /** Complete SPI command: CS low, send command + data bytes, read responses, CS high. */
  private def spiCommand(ch376: SimCH376T, cmd: Int, txData: Seq[Int] = Seq()): Seq[Int] = {
    csLow(ch376)
    spiXfer(ch376, cmd)  // send command byte (response is don't-care)
    val responses = txData.map(b => spiXfer(ch376, b))
    csHigh(ch376)
    responses
  }

  /** Send command, then read N response bytes (sending 0x00 as dummy). */
  private def spiCommandRead(ch376: SimCH376T, cmd: Int, txData: Seq[Int] = Seq(), readCount: Int = 0): Seq[Int] = {
    csLow(ch376)
    spiXfer(ch376, cmd)
    txData.foreach(b => spiXfer(ch376, b))
    val responses = (0 until readCount).map(_ => spiXfer(ch376, 0x00))
    csHigh(ch376)
    responses
  }

  test("GET_IC_VER returns 0x43") {
    val ch376 = new SimCH376T()
    val resp = spiCommandRead(ch376, 0x01, readCount = 1)
    assert(resp.head == 0x43, f"Expected 0x43, got 0x${resp.head}%02X")
  }

  test("CHECK_EXIST echoes bitwise complement") {
    val ch376 = new SimCH376T()

    for (input <- Seq(0x00, 0x55, 0xAA, 0xFF, 0x42)) {
      val expected = (~input) & 0xFF
      // CHECK_EXIST: send command 0x06, then 1 data byte, read response on next byte
      csLow(ch376)
      spiXfer(ch376, 0x06)     // command
      spiXfer(ch376, input)    // input byte (response loads after this)
      val resp = spiXfer(ch376, 0x00)  // dummy byte to clock out response
      csHigh(ch376)
      assert(resp == expected, f"CHECK_EXIST(0x$input%02X): expected 0x$expected%02X, got 0x$resp%02X")
    }
  }

  test("SET_USB_MODE SD host") {
    val ch376 = new SimCH376T()
    csLow(ch376)
    spiXfer(ch376, 0x15)  // SET_USB_MODE
    spiXfer(ch376, 0x03)  // SD_HOST mode
    val resp = spiXfer(ch376, 0x00)  // read status
    csHigh(ch376)
    assert(resp == 0x51, f"Expected CMD_RET_SUCCESS (0x51), got 0x$resp%02X")
  }

  test("SET_USB_MODE USB host triggers INT_CONNECT") {
    val ch376 = new SimCH376T()
    csLow(ch376)
    spiXfer(ch376, 0x15)  // SET_USB_MODE
    spiXfer(ch376, 0x06)  // USB_HOST mode
    val resp = spiXfer(ch376, 0x00)
    csHigh(ch376)
    assert(resp == 0x51, f"Expected CMD_RET_SUCCESS (0x51), got 0x$resp%02X")
    assert(ch376.intActive, "INT# should be active after USB host mode set")

    // GET_STATUS should return USB_INT_CONNECT (0x15)
    val status = spiCommandRead(ch376, 0x22, readCount = 1)
    assert(status.head == 0x15, f"Expected USB_INT_CONNECT (0x15), got 0x${status.head}%02X")
    assert(!ch376.intActive, "INT# should clear after GET_STATUS")
  }

  test("DISK_CONNECT without image returns ERR_DISK_DISCON") {
    val ch376 = new SimCH376T()
    // Set SD host mode first
    spiCommand(ch376, 0x15, Seq(0x03))

    val resp = spiCommandRead(ch376, 0x30, readCount = 1)
    assert(resp.head == 0x82, f"Expected ERR_DISK_DISCON (0x82), got 0x${resp.head}%02X")
  }

  test("DISK_CONNECT with image returns SUCCESS") {
    // Create a small temp SD image
    val tmpFile = java.io.File.createTempFile("simch376t_test", ".img")
    tmpFile.deleteOnExit()
    val raf = new java.io.RandomAccessFile(tmpFile, "rw")
    raf.setLength(512 * 16)  // 16 sectors
    // Write a known pattern to sector 0
    val pattern = Array.tabulate[Byte](512)(i => (i & 0xFF).toByte)
    raf.write(pattern)
    raf.close()

    val ch376 = new SimCH376T(sdImagePath = Some(tmpFile.getAbsolutePath))
    // Set SD host mode
    spiCommand(ch376, 0x15, Seq(0x03))

    // DISK_CONNECT
    val connResp = spiCommandRead(ch376, 0x30, readCount = 1)
    assert(connResp.head == 0x14, f"Expected USB_INT_SUCCESS (0x14), got 0x${connResp.head}%02X")
    assert(ch376.intActive, "INT# should be active after DISK_CONNECT")

    // DISK_MOUNT
    csLow(ch376)
    spiXfer(ch376, 0x31)
    val mountResp = spiXfer(ch376, 0x00)
    csHigh(ch376)
    assert(mountResp == 0x14, f"Expected USB_INT_SUCCESS (0x14), got 0x$mountResp%02X")

    ch376.close()
    tmpFile.delete()
  }

  test("DISK_READ reads sector data correctly") {
    val tmpFile = java.io.File.createTempFile("simch376t_test", ".img")
    tmpFile.deleteOnExit()
    val raf = new java.io.RandomAccessFile(tmpFile, "rw")
    raf.setLength(512 * 16)
    // Write known pattern: sector 0 = bytes 0x00..0xFF repeated twice
    val pattern = Array.tabulate[Byte](512)(i => (i & 0xFF).toByte)
    raf.seek(0)
    raf.write(pattern)
    raf.close()

    val ch376 = new SimCH376T(sdImagePath = Some(tmpFile.getAbsolutePath))
    spiCommand(ch376, 0x15, Seq(0x03))  // SD host mode
    spiCommand(ch376, 0x30)             // DISK_CONNECT
    spiCommand(ch376, 0x31)             // DISK_MOUNT

    // DISK_READ: LBA=0, count=1
    csLow(ch376)
    spiXfer(ch376, 0x54)  // DISK_READ
    spiXfer(ch376, 0x00)  // LBA byte 0
    spiXfer(ch376, 0x00)  // LBA byte 1
    spiXfer(ch376, 0x00)  // LBA byte 2
    spiXfer(ch376, 0x00)  // LBA byte 3
    spiXfer(ch376, 0x01)  // sector count
    csHigh(ch376)

    assert(ch376.intActive, "INT# should be active after DISK_READ")

    // GET_STATUS should return USB_INT_DISK_READ (0x1D)
    val status = spiCommandRead(ch376, 0x22, readCount = 1)
    assert(status.head == 0x1D, f"Expected USB_INT_DISK_READ (0x1D), got 0x${status.head}%02X")

    // RD_USB_DATA0: read first 64-byte chunk
    csLow(ch376)
    spiXfer(ch376, 0x27)  // RD_USB_DATA0
    val len = spiXfer(ch376, 0x00)  // length byte
    assert(len == 64, s"Expected chunk length 64, got $len")
    val chunk = (0 until len).map(_ => spiXfer(ch376, 0x00))
    csHigh(ch376)

    // Verify first 64 bytes match pattern
    for (i <- 0 until 64) {
      assert(chunk(i) == (i & 0xFF), f"Byte $i: expected 0x${i & 0xFF}%02X, got 0x${chunk(i)}%02X")
    }

    ch376.close()
    tmpFile.delete()
  }

  test("RESET_ALL clears state") {
    val ch376 = new SimCH376T()
    // Set USB host mode (sets intActive)
    spiCommand(ch376, 0x15, Seq(0x06))
    assert(ch376.intActive)

    // RESET_ALL
    spiCommand(ch376, 0x05)
    assert(!ch376.intActive, "INT# should be cleared after RESET_ALL")

    // GET_IC_VER still works after reset
    val resp = spiCommandRead(ch376, 0x01, readCount = 1)
    assert(resp.head == 0x43)
  }

  test("DISK_CAPACITY returns correct sector count") {
    val tmpFile = java.io.File.createTempFile("simch376t_test", ".img")
    tmpFile.deleteOnExit()
    val raf = new java.io.RandomAccessFile(tmpFile, "rw")
    raf.setLength(512 * 1024)  // 1024 sectors = 512KB
    raf.close()

    val ch376 = new SimCH376T(sdImagePath = Some(tmpFile.getAbsolutePath))
    spiCommand(ch376, 0x15, Seq(0x03))  // SD host mode

    // DISK_CAPACITY
    spiCommand(ch376, 0x3E)
    assert(ch376.intActive)

    // RD_USB_DATA0: 8 bytes (4 sectors + 4 bytes/sector)
    csLow(ch376)
    spiXfer(ch376, 0x27)
    val len = spiXfer(ch376, 0x00)
    assert(len == 8, s"Expected 8 bytes, got $len")
    val data = (0 until len).map(_ => spiXfer(ch376, 0x00))
    csHigh(ch376)

    // Total sectors: 1024 = 0x00000400 big-endian
    val sectors = (data(0) << 24) | (data(1) << 16) | (data(2) << 8) | data(3)
    assert(sectors == 1024, s"Expected 1024 sectors, got $sectors")

    // Bytes per sector: 512 = 0x00000200 big-endian
    val bps = (data(4) << 24) | (data(5) << 16) | (data(6) << 8) | data(7)
    assert(bps == 512, s"Expected 512 bytes/sector, got $bps")

    ch376.close()
    tmpFile.delete()
  }
}
