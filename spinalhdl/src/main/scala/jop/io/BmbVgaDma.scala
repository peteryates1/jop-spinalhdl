package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

/**
 * VGA controller with BMB master DMA for reading an RGB565 framebuffer
 * from SDRAM, plus a JOP I/O register interface for CPU control.
 *
 * Two interfaces:
 *   1. JOP I/O registers (system clock domain) for CPU control
 *   2. BMB master port (system clock domain) for DMA reads from SDRAM
 *
 * Reads a 640x480 RGB565 framebuffer and outputs standard VGA signals
 * through a CDC FIFO into the pixel clock domain (25 MHz).
 *
 * Register map (sub-addresses relative to assigned I/O device slot):
 *   0x0 read  -- Status: bit0=enabled, bit1=vsyncPending, bit2=underrun
 *   0x0 write -- Control: bit0=enable, bit1=clearVsync
 *   0x1 read  -- Framebuffer base address
 *   0x1 write -- Set framebuffer base address (word-aligned)
 *   0x2 read  -- Framebuffer size in bytes
 *   0x2 write -- Set framebuffer size (default: 640x480x2 = 614400)
 *   0x3       -- Reserved (H timing)
 *   0x4       -- Reserved (V timing)
 *
 * @param bmbParam  BMB parameters (must match system arbiter requirements)
 * @param vgaCd     Pixel clock domain (25 MHz for 640x480@60Hz)
 * @param fifoDepth CDC FIFO depth in 32-bit words (must be power of 2, >= 2)
 */
case class BmbVgaDma(
  bmbParam: BmbParameter,
  vgaCd: ClockDomain,
  fifoDepth: Int = 512
) extends Component {

  val io = new Bundle {
    // JOP I/O register interface (system clock domain)
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // BMB master port for DMA reads (system clock domain)
    val bmb = master(Bmb(bmbParam))

    // VGA output pins (pixel clock domain)
    val vgaHsync = out Bool()
    val vgaVsync = out Bool()
    val vgaR     = out Bits(5 bits)
    val vgaG     = out Bits(6 bits)
    val vgaB     = out Bits(5 bits)

    val vsyncInterrupt = out Bool()
  }

  // ========================================================================
  // VGA 640x480@60Hz Timing Constants
  // ========================================================================

  val hActive    = 640
  val hSyncStart = 656
  val hSyncEnd   = 752
  val hTotal     = 800

  val vActive    = 480
  val vSyncStart = 490
  val vSyncEnd   = 492
  val vTotal     = 525

  // ========================================================================
  // DMA Configuration
  // ========================================================================

  // Burst length in bytes: determined by lengthWidth from BMB parameters.
  // lengthWidth encodes (length - 1), so max burst = (1 << lengthWidth) bytes.
  // We use a burst of 8 words = 32 bytes as a reasonable default,
  // clamped to what the BMB parameters support.
  val burstBytes = 32
  val burstWords = burstBytes / (bmbParam.access.dataWidth / 8)
  val maxPending = 4

  // ========================================================================
  // Control Registers (system clock domain)
  // ========================================================================

  val enabled       = Reg(Bool()) init(False)
  val baseAddr      = Reg(UInt(bmbParam.access.addressWidth bits)) init(0)
  val fbSize        = Reg(UInt(bmbParam.access.addressWidth bits)) init(614400)
  val vsyncPending  = Reg(Bool()) init(False)
  val underrunFlag  = Reg(Bool()) init(False)

  // ========================================================================
  // CDC FIFO (system clock -> pixel clock)
  // ========================================================================

  val fifo = StreamFifoCC(
    dataType = Bits(32 bits),
    depth = fifoDepth,
    pushClock = ClockDomain.current,
    popClock = vgaCd
  )

  // ========================================================================
  // DMA Engine (system clock domain)
  // ========================================================================

  val offset       = Reg(UInt(bmbParam.access.addressWidth bits)) init(0)
  val pendingCount = Reg(UInt(log2Up(maxPending + 1) bits)) init(0)

  // Backpressure: stop issuing when FIFO is nearly full.
  // Reserve space for all in-flight bursts plus current burst.
  val fifoSpaceOk = fifo.io.pushOccupancy < (fifoDepth - (maxPending + 1) * burstWords)
  val pendingOk   = pendingCount < maxPending

  // BMB command generation
  io.bmb.cmd.valid   := enabled && pendingOk && fifoSpaceOk
  io.bmb.cmd.opcode  := B(Bmb.Cmd.Opcode.READ, 1 bits)
  io.bmb.cmd.address := baseAddr + offset
  io.bmb.cmd.length  := U(burstBytes - 1, bmbParam.access.lengthWidth bits)
  io.bmb.cmd.last    := True
  io.bmb.cmd.source  := U(0, bmbParam.access.sourceWidth bits)
  io.bmb.cmd.context := B(0, bmbParam.access.contextWidth bits)
  if (bmbParam.access.canWrite) {
    io.bmb.cmd.data := B(0, bmbParam.access.dataWidth bits)
    if (bmbParam.access.canMask) {
      io.bmb.cmd.mask := B(0, bmbParam.access.maskWidth bits)
    }
  }

  when(io.bmb.cmd.fire) {
    val nextOffset = offset + burstBytes
    when(nextOffset >= fbSize) {
      offset := 0
    } otherwise {
      offset := nextOffset
    }
  }

  // Pending transaction tracking
  val cmdFire = io.bmb.cmd.fire
  val rspLast = io.bmb.rsp.fire && io.bmb.rsp.last
  when(cmdFire && !rspLast) {
    pendingCount := pendingCount + 1
  } elsewhen (!cmdFire && rspLast) {
    pendingCount := pendingCount - 1
  }

  // BMB response handling: push data into CDC FIFO.
  // The FIFO push stream mirrors the BMB response handshake directly:
  // push.valid = rsp.valid, rsp.ready = push.ready. The FIFO accepts
  // data when both valid and ready are asserted (same as Stream protocol).
  fifo.io.push.valid   := io.bmb.rsp.valid
  fifo.io.push.payload := io.bmb.rsp.data
  io.bmb.rsp.ready     := fifo.io.push.ready

  // ========================================================================
  // Pixel Clock Domain (25 MHz VGA output)
  // ========================================================================

  val vgaArea = new ClockingArea(vgaCd) {
    val hCounter = Reg(UInt(log2Up(hTotal) bits)) init(0)
    val vCounter = Reg(UInt(log2Up(vTotal) bits)) init(0)

    val hWillOverflow = hCounter === (hTotal - 1)

    when(hWillOverflow) {
      hCounter := 0
      when(vCounter === (vTotal - 1)) {
        vCounter := 0
      } otherwise {
        vCounter := vCounter + 1
      }
    } otherwise {
      hCounter := hCounter + 1
    }

    val hSync  = !(hCounter >= hSyncStart && hCounter < hSyncEnd)
    val vSync  = !(vCounter >= vSyncStart && vCounter < vSyncEnd)
    val active = hCounter < hActive && vCounter < vActive

    // Pixel unpacking: 2 RGB565 pixels per 32-bit word
    val halfSel = Reg(Bool()) init(False)
    val wordReg = Reg(Bits(32 bits)) init(0)
    val underrun = Reg(Bool()) init(False)

    fifo.io.pop.ready := False
    when(active) {
      when(!halfSel) {
        // First pixel: pop new word from FIFO, use low 16 bits
        fifo.io.pop.ready := True
        when(fifo.io.pop.valid) {
          wordReg := fifo.io.pop.payload
          halfSel := True
          underrun := False
        } otherwise {
          // Underrun: FIFO empty during active display
          underrun := True
        }
      } otherwise {
        // Second pixel: use high 16 bits, no FIFO pop needed
        halfSel := False
      }
    } otherwise {
      halfSel := False
    }

    // Select pixel: when halfSel is True we just loaded the word, show low half.
    // When halfSel is False (second pixel cycle), show high half.
    val pixel = Mux(halfSel, wordReg(15 downto 0), wordReg(31 downto 16))

    val displayActive = active && !underrun
    io.vgaR     := Mux(displayActive, pixel(15 downto 11), B"5'd0")
    io.vgaG     := Mux(displayActive, pixel(10 downto 5), B"6'd0")
    io.vgaB     := Mux(displayActive, pixel(4 downto 0), B"5'd0")
    io.vgaHsync := hSync
    io.vgaVsync := vSync

    // Frame start detection: first pixel of first line
    val frameStart = (vCounter === 0) && (hCounter === 0)
  }

  // ========================================================================
  // Frame Restart (pixel clock -> system clock)
  // ========================================================================

  val frameStartSync = BufferCC(vgaArea.frameStart, False)
  val frameStartRise = frameStartSync.rise(False)
  when(frameStartRise) {
    offset := 0
  }

  // ========================================================================
  // VSync Interrupt (pixel clock -> system clock)
  // ========================================================================

  val vsyncSync = BufferCC(vgaArea.vSync, True)
  val vsyncFall = vsyncSync.fall(True)
  when(vsyncFall) {
    vsyncPending := True
  }
  io.vsyncInterrupt := vsyncFall

  // ========================================================================
  // Underrun Detection (pixel clock -> system clock)
  // ========================================================================

  val underrunSync = BufferCC(vgaArea.underrun, False)
  when(underrunSync.rise(False)) {
    underrunFlag := True
  }

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      // Status register
      io.rdData(0) := enabled
      io.rdData(1) := vsyncPending
      io.rdData(2) := underrunFlag
    }
    is(1) {
      // Framebuffer base address
      io.rdData := baseAddr.asBits.resized
    }
    is(2) {
      // Framebuffer size
      io.rdData := fbSize.asBits.resized
    }
  }

  // ========================================================================
  // Register Write Handling
  // ========================================================================

  when(io.wr) {
    switch(io.addr) {
      is(0) {
        // Control register: bit0=enable, bit1=clearVsync
        enabled := io.wrData(0)
        when(io.wrData(1)) {
          vsyncPending := False
        }
      }
      is(1) {
        // Set framebuffer base address
        baseAddr := U(io.wrData).resized
      }
      is(2) {
        // Set framebuffer size
        fbSize := U(io.wrData).resized
      }
    }
  }
}
