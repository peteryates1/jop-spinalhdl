package jop.sim

import java.awt._
import java.awt.event._
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing._

/**
 * Simulation-time VGA display using Java AWT.
 *
 * Captures VGA timing signals every pixel clock tick and reconstructs
 * a 640x480 framebuffer rendered in a Swing window. Keyboard events
 * are captured when the window has focus and queued for consumption
 * by SimCH376T or other simulation components.
 *
 * Usage from a SpinalHDL doSim block:
 * {{{
 *   val display = new SimDisplay(scale = 2)
 *   fork {
 *     while (true) {
 *       vgaCd.waitSampling()
 *       display.tick(
 *         dut.io.vgaHsync.toBoolean,
 *         dut.io.vgaVsync.toBoolean,
 *         dut.io.vgaR.toInt,
 *         dut.io.vgaG.toInt,
 *         dut.io.vgaB.toInt)
 *     }
 *   }
 * }}}
 *
 * VGA sync signals are active low (normal VGA convention):
 *   hsync/vsync HIGH = not in sync pulse
 *   hsync/vsync LOW  = sync pulse active
 *
 * @param width   Active horizontal pixels (default 640)
 * @param height  Active vertical pixels (default 480)
 * @param scale   Window scale factor (1 = native, 2 = doubled)
 * @param title   Window title
 */
class SimDisplay(
  val width: Int = 640,
  val height: Int = 480,
  val scale: Int = 1,
  val title: String = "JOP VGA Sim"
) {
  // VGA timing constants (640x480@60Hz)
  private val H_ACTIVE = 640
  private val H_FRONT  = 16
  private val H_SYNC   = 96
  private val H_BACK   = 48
  private val H_TOTAL  = 800

  private val V_ACTIVE = 480
  private val V_FRONT  = 10
  private val V_SYNC   = 2
  private val V_BACK   = 33
  private val V_TOTAL  = 525

  // Sync pulse starts (in pixel clocks from start of line/frame)
  private val H_SYNC_START = H_ACTIVE + H_FRONT  // 656
  private val V_SYNC_START = V_ACTIVE + V_FRONT   // 490

  // Double-buffered framebuffers: sim writes to back, AWT paints from front
  private val frontBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  private val backBuffer  = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  @volatile private var displayBuffer: BufferedImage = frontBuffer

  // VGA timing state
  private var hCounter: Int = 0
  private var vCounter: Int = 0
  private var prevHsync: Boolean = true  // idle high (active low)
  private var prevVsync: Boolean = true

  // Frame counter for stats
  private var frameCount: Long = 0
  private var tickCount: Long = 0

  // Keyboard event queue (thread-safe: AWT EDT writes, sim thread reads)
  private val keyPressQueue   = new ConcurrentLinkedQueue[KeyEvent]()
  private val keyReleaseQueue = new ConcurrentLinkedQueue[KeyEvent]()

  // AWT window (created on EDT)
  private var frame: JFrame = _
  private var panel: JPanel = _

  initWindow()

  private def initWindow(): Unit = {
    SwingUtilities.invokeAndWait(() => {
      frame = new JFrame(title)
      frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
      frame.setResizable(false)

      panel = new JPanel() {
        override def paintComponent(g: Graphics): Unit = {
          super.paintComponent(g)
          val buf = displayBuffer
          if (scale == 1) {
            g.drawImage(buf, 0, 0, null)
          } else {
            g.drawImage(buf, 0, 0, width * scale, height * scale, null)
          }
        }

        override def getPreferredSize: Dimension =
          new Dimension(width * scale, height * scale)
      }

      panel.setFocusable(true)
      panel.addKeyListener(new KeyListener {
        override def keyPressed(e: KeyEvent): Unit = {
          keyPressQueue.add(e)
        }
        override def keyReleased(e: KeyEvent): Unit = {
          keyReleaseQueue.add(e)
        }
        override def keyTyped(e: KeyEvent): Unit = {}
      })

      frame.add(panel)
      frame.pack()
      frame.setLocationRelativeTo(null)
      frame.setVisible(true)
      panel.requestFocusInWindow()
    })
  }

  /**
   * Process one pixel clock tick.
   *
   * Call this every pixel clock cycle (25 MHz) with the current VGA signal values.
   * Sync signals are active low (standard VGA convention).
   *
   * @param hsync  Horizontal sync (active low: false = sync pulse)
   * @param vsync  Vertical sync (active low: false = sync pulse)
   * @param r      Red channel (5 bits, 0-31)
   * @param g      Green channel (6 bits, 0-63)
   * @param b      Blue channel (5 bits, 0-31)
   */
  def tick(hsync: Boolean, vsync: Boolean, r: Int, g: Int, b: Int): Unit = {
    tickCount += 1

    // Detect sync edges (active low: falling edge = start of sync pulse)
    val hsyncFalling = prevHsync && !hsync
    val vsyncFalling = prevVsync && !vsync

    // Hsync falling edge: we've reached H_SYNC_START on this line
    if (hsyncFalling) {
      hCounter = H_SYNC_START
      vCounter += 1
    }

    // Vsync falling edge: we've reached V_SYNC_START
    if (vsyncFalling) {
      vCounter = V_SYNC_START

      // Swap buffers and trigger repaint
      swapAndRepaint()
    }

    // Write pixel if in active display area
    if (hCounter < H_ACTIVE && vCounter < V_ACTIVE) {
      // RGB565 → RGB888
      val r8 = (r << 3) | (r >> 2)
      val g8 = (g << 2) | (g >> 4)
      val b8 = (b << 3) | (b >> 2)
      val rgb = (r8 << 16) | (g8 << 8) | b8
      backBuffer.setRGB(hCounter, vCounter, rgb)
    }

    // Advance horizontal counter
    hCounter += 1
    if (hCounter >= H_TOTAL) {
      hCounter = 0
    }

    prevHsync = hsync
    prevVsync = vsync
  }

  private def swapAndRepaint(): Unit = {
    frameCount += 1

    // Copy back buffer to front buffer
    val g = frontBuffer.createGraphics()
    g.drawImage(backBuffer, 0, 0, null)
    g.dispose()

    // Update display reference and schedule repaint
    displayBuffer = frontBuffer
    if (panel != null) {
      panel.repaint()
    }

    // Update title with frame count periodically
    if (frameCount % 60 == 0 && frame != null) {
      SwingUtilities.invokeLater(() => {
        frame.setTitle(s"$title — frame $frameCount")
      })
    }
  }

  /**
   * Poll for the next key press event.
   * Returns None if no key press is pending.
   */
  def pollKeyPress(): Option[KeyEvent] = Option(keyPressQueue.poll())

  /**
   * Poll for the next key release event.
   * Returns None if no key release is pending.
   */
  def pollKeyRelease(): Option[KeyEvent] = Option(keyReleaseQueue.poll())

  /**
   * Check if any key press events are pending.
   */
  def hasKeyPress: Boolean = !keyPressQueue.isEmpty

  /** Current frame count (number of vsync events processed). */
  def frames: Long = frameCount

  /** Current tick count (total pixel clock ticks). */
  def ticks: Long = tickCount

  /**
   * Close the display window and release resources.
   */
  def close(): Unit = {
    if (frame != null) {
      SwingUtilities.invokeLater(() => {
        frame.dispose()
      })
    }
  }
}
