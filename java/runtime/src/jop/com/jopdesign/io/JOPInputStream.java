package com.jopdesign.io;

import java.io.InputStream;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

public class JOPInputStream extends InputStream {
	public int available() {
		if ((Native.rd(Const.IO_UART_STATUS)&Const.MSK_UA_RDRF)!=0) {
			return 1;
		} else {
			return 0;
		}
	}
	public int read() {
		while ((Native.rd(Const.IO_UART_STATUS) & Const.MSK_UA_RDRF) == 0) {
			; // block, but should enable thread switching
		}
		return Native.rd(Const.IO_UART_DATA);
	}
}
