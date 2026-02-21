package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

public class NCoreHelloWorld {

	static final int WD_INTERVAL  =  100000;

	public static void main(String[] args) {
		int cpu_id = Native.rd(Const.IO_CPU_ID);
		if (cpu_id == 0) {
			int w=0, n=0, c=0;
			while(true) {
				c = Native.rd(Const.IO_US_CNT);
				if(n <= c) {
					n = c + WD_INTERVAL;
					JVMHelp.wr("Hello World!\n");
					Native.wr(++w, Const.IO_WD);
					Native.wr(1, Const.IO_SIGNAL);
				}
			}
		} else {
			int w=0, n=0, c=0;
			while(true) {
				c = Native.rd(Const.IO_US_CNT);
				if(n <= c) {
					n = c + WD_INTERVAL;
					Native.wr(++w, Const.IO_WD);
				}
			}
		}
	}
}
