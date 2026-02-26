package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the VGA DMA framebuffer controller (BmbVgaDma).
 *
 * Reads RGB565 pixel data from an SDRAM framebuffer via DMA and outputs
 * 640x480@60Hz VGA. Two RGB565 pixels per 32-bit word (low 16 = first pixel).
 *
 * Fields map to sequential I/O registers at Const.IO_VGA_DMA:
 *   +0  statusControl  R: status  W: control (enable/clearVsync)
 *   +1  baseAddr       R/W: framebuffer base address (byte address)
 *   +2  fbSize         R/W: framebuffer size in bytes
 */
public final class VgaDma extends HardwareObject {

	// Status bits (addr 0, read)
	public static final int STATUS_ENABLED       = 1 << 0;
	public static final int STATUS_VSYNC_PENDING  = 1 << 1;
	public static final int STATUS_UNDERRUN       = 1 << 2;

	// Control bits (addr 0, write)
	public static final int CTRL_ENABLE      = 1 << 0;
	public static final int CTRL_CLEAR_VSYNC = 1 << 1;

	// Singleton
	private static VgaDma instance = null;

	public static VgaDma getInstance() {
		if (instance == null)
			instance = (VgaDma) make(new VgaDma(), Const.IO_VGA_DMA, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=status (enabled/vsyncPending/underrun), W=control */
	public volatile int statusControl;
	/** +1: R/W framebuffer base address (byte address, word-aligned) */
	public volatile int baseAddr;
	/** +2: R/W framebuffer size in bytes (default 614400) */
	public volatile int fbSize;

	// --- Convenience methods ---

	/** Enable DMA display output. */
	public void enable() {
		statusControl = CTRL_ENABLE;
	}

	/** Disable DMA display output. */
	public void disable() {
		statusControl = 0;
	}

	/** Return true if DMA display is enabled. */
	public boolean isEnabled() {
		return (statusControl & STATUS_ENABLED) != 0;
	}

	/** Return true if a vsync event is pending. */
	public boolean isVsyncPending() {
		return (statusControl & STATUS_VSYNC_PENDING) != 0;
	}

	/** Clear the vsync pending flag and keep DMA enabled. */
	public void clearVsync() {
		statusControl = CTRL_ENABLE | CTRL_CLEAR_VSYNC;
	}

	/** Return true if a FIFO underrun has occurred. */
	public boolean isUnderrun() {
		return (statusControl & STATUS_UNDERRUN) != 0;
	}

	/**
	 * Set the framebuffer location and size.
	 * @param byteAddr SDRAM byte address of framebuffer (word-aligned)
	 * @param sizeBytes framebuffer size in bytes (640*480*2 = 614400)
	 */
	public void setFramebuffer(int byteAddr, int sizeBytes) {
		baseAddr = byteAddr;
		fbSize = sizeBytes;
	}
}
