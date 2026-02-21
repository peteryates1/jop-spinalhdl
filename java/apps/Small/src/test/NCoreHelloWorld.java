package test;

import com.jopdesign.io.IOFactory;
import com.jopdesign.io.SysDevice;
import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

public class NCoreHelloWorld implements Runnable {
	private static final int WD_INTERVAL  =  500000;
	
	private int cpuID;
	private SysDevice sd; 
			
	public NCoreHelloWorld(int cpuID) {
		this.cpuID = cpuID;
		this.sd = IOFactory.getFactory().getSysDevice();
	}
	
	public void run() {
		int wd = cpuID, n=0, c=0;
		if(cpuID==0) {
			sd.signal = 1;
		}
		while(true) {
			c = sd.uscntTimer;
			if(n <= c) {
				n = c + WD_INTERVAL;
				if(cpuID==0) {
					System.out.println("small - NCoreHelloWorld");
				}
				wd = ~wd;
				sd.wd = wd;
			}
		}
	}
	
	public static void main(String[] args) {
		new NCoreHelloWorld(Native.rdMem(Const.IO_CPU_ID)).run();
	}
}