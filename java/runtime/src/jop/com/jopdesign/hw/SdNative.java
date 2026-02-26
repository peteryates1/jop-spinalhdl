package com.jopdesign.hw;

import com.jopdesign.sys.Const;

/**
 * Hardware object for the SD Native Mode controller (BmbSdNative).
 *
 * 4-bit SD native mode. Hardware handles clock generation, CMD shift
 * register with CRC7, DATA shift register with per-line CRC16, and
 * a 512-byte block FIFO. Software drives the SD protocol.
 *
 * Fields map to sequential I/O registers at Const.IO_SD:
 *   +0  statusControl    R: status  W: control (sendCmd/abort/openDrain)
 *   +1  cmdArgResponse0  R: CMD response[31:0]  W: CMD argument[31:0]
 *   +2  cmdIndexResponse1 R: CMD response[63:32] W: CMD index/flags
 *   +3  response2        R: CMD response[95:64] (R2 only)
 *   +4  response3        R: CMD response[127:96] (R2 only)
 *   +5  dataFifo         R: FIFO pop  W: FIFO push
 *   +6  occupancyClkDiv  R: RX FIFO occupancy  W: clock divider[9:0]
 *   +7  crcDataCtrl      R: CRC error flags  W: data xfer control
 *   +8  cardBusWidth     R: cardPresent|busWidth4  W: busWidth4
 *   +9  blockLength      W: block length in bytes
 */
public final class SdNative extends HardwareObject {

	// Status bits (addr 0, read)
	public static final int STATUS_CMD_BUSY       = 1 << 0;
	public static final int STATUS_CMD_RESP_VALID  = 1 << 1;
	public static final int STATUS_CMD_RESP_ERR    = 1 << 2;
	public static final int STATUS_CMD_TIMEOUT     = 1 << 3;
	public static final int STATUS_DATA_BUSY       = 1 << 4;
	public static final int STATUS_DATA_CRC_ERR    = 1 << 5;
	public static final int STATUS_DATA_TIMEOUT    = 1 << 6;
	public static final int STATUS_DATA_DONE       = 1 << 7;
	public static final int STATUS_CARD_PRESENT    = 1 << 8;
	public static final int STATUS_FIFO_FULL       = 1 << 9;
	public static final int STATUS_FIFO_EMPTY      = 1 << 10;
	public static final int STATUS_FIFO_HAS_DATA   = 1 << 11;

	// Control bits (addr 0, write)
	public static final int CTRL_SEND_CMD   = 1 << 0;
	public static final int CTRL_ABORT      = 1 << 1;
	public static final int CTRL_OPEN_DRAIN = 1 << 2;

	// CMD index/flags (addr 2, write)
	public static final int CMD_EXPECT_RESP = 1 << 6;
	public static final int CMD_LONG_RESP   = 1 << 7;

	// Data transfer control (addr 7, write)
	public static final int DATA_START_READ  = 1 << 0;
	public static final int DATA_START_WRITE = 1 << 1;

	// Singleton
	private static SdNative instance = null;

	public static SdNative getInstance() {
		if (instance == null)
			instance = (SdNative) make(new SdNative(), Const.IO_SD, 0);
		return instance;
	}

	// --- Hardware registers (sequential, volatile) ---

	/** +0: R=status, W=control (sendCmd/abort/openDrain) */
	public volatile int statusControl;
	/** +1: R=CMD response[31:0], W=CMD argument[31:0] */
	public volatile int cmdArgResponse0;
	/** +2: R=CMD response[63:32], W=CMD index[5:0]|expectResp|longResp */
	public volatile int cmdIndexResponse1;
	/** +3: R=CMD response[95:64] (R2 only) */
	public volatile int response2;
	/** +4: R=CMD response[127:96] (R2 only) */
	public volatile int response3;
	/** +5: R=data FIFO pop, W=data FIFO push */
	public volatile int dataFifo;
	/** +6: R=RX FIFO occupancy[15:0], W=clock divider[9:0] */
	public volatile int occupancyClkDiv;
	/** +7: R=CRC error flags[3:0], W=data xfer control */
	public volatile int crcDataCtrl;
	/** +8: R=cardPresent(0)|busWidth4(1), W=busWidth4 */
	public volatile int cardBusWidth;
	/** +9: W=block length in bytes */
	public volatile int blockLength;

	// --- Convenience methods ---

	/** Return true if a CMD operation is in progress. */
	public boolean isCmdBusy() {
		return (statusControl & STATUS_CMD_BUSY) != 0;
	}

	/** Return true if a data transfer is in progress. */
	public boolean isDataBusy() {
		return (statusControl & STATUS_DATA_BUSY) != 0;
	}

	/** Return true if a card is inserted. */
	public boolean isCardPresent() {
		return (statusControl & STATUS_CARD_PRESENT) != 0;
	}

	/** Return true if the RX FIFO has data available. */
	public boolean fifoHasData() {
		return (statusControl & STATUS_FIFO_HAS_DATA) != 0;
	}

	/** Wait for CMD to complete. Returns status. */
	public int waitCmd() {
		for (int i = 0; i < 1000000; i++) {
			int st = statusControl;
			if ((st & STATUS_CMD_BUSY) == 0) return st;
		}
		return statusControl;
	}

	/** Wait for data transfer to complete. Returns status. */
	public int waitData() {
		for (int i = 0; i < 1000000; i++) {
			int st = statusControl;
			if ((st & STATUS_DATA_BUSY) == 0) return st;
		}
		return statusControl;
	}

	/**
	 * Send a command with argument.
	 * @param index CMD index (0-63)
	 * @param arg 32-bit argument
	 * @param expectResp true if response expected
	 * @param longResp true for R2 (136-bit) response
	 */
	public void sendCmd(int index, int arg, boolean expectResp, boolean longResp) {
		cmdArgResponse0 = arg;
		int flags = index & 0x3F;
		if (expectResp) flags |= CMD_EXPECT_RESP;
		if (longResp) flags |= CMD_LONG_RESP;
		cmdIndexResponse1 = flags;
		statusControl = CTRL_SEND_CMD;
	}

	/** Set clock divider. SD_CLK = sys_clk / (2 * (div + 1)). */
	public void setClockDivider(int div) {
		occupancyClkDiv = div & 0x3FF;
	}

	/** Set block length for data transfers. */
	public void setBlockLength(int bytes) {
		blockLength = bytes;
	}

	/** Enable 4-bit bus width. */
	public void setBusWidth4(boolean enable) {
		cardBusWidth = enable ? 1 : 0;
	}

	/** Start a data read operation. */
	public void startRead() {
		crcDataCtrl = DATA_START_READ;
	}

	/** Start a data write operation. */
	public void startWrite() {
		crcDataCtrl = DATA_START_WRITE;
	}
}
