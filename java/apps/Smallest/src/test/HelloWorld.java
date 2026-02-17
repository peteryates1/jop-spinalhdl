package test;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.JVMHelp;
import com.jopdesign.sys.Native;

public class HelloWorld {
	static final int WD_INTERVAL = 100000;

	public static void main(String[] args) {
		int w=0, n=0, c=0;
		while(true) {
			c = Native.rd(Const.IO_US_CNT);
			if(n <= c) {
				n = c + WD_INTERVAL;
				JVMHelp.wr("Hello World!\n");
				w=~w;
				Native.wr(w, Const.IO_WD);
			}
		}
	}
}
