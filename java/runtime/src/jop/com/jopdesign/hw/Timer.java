package com.jopdesign.hw;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

public class Timer {
	public static int getTimeoutMs(int msOff) {
		return Native.rd(Const.IO_US_CNT) + 1000*msOff;
	}
	
	public static boolean timeout(int val) {
		return val - Native.rd(Const.IO_US_CNT) < 0;
	}
}
