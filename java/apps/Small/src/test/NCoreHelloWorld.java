package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

public class NCoreHelloWorld implements Runnable {
	private static final int WD_INTERVAL  =  500000;

	private int cpuID;

	public NCoreHelloWorld(int cpuID) {
		this.cpuID = cpuID;
	}

	public void run() {
		int wd = cpuID, n=0, c=0;
		if(cpuID==0) {
			Native.wr(1, Const.IO_SIGNAL);
		}
		while(true) {
			c = Native.rd(Const.IO_US_CNT);
			if(n <= c) {
				n = c + WD_INTERVAL;
				if(cpuID==0) {
					JVMHelp.wr("small - NCoreHelloWorld\r\n");
				}
				wd = ~wd;
				Native.wr(wd, Const.IO_WD);
			}
		}
	}

	public static void main(String[] args) {
		new NCoreHelloWorld(Native.rdMem(Const.IO_CPU_ID)).run();
	}
}