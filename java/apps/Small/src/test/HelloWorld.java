package test;

import com.jopdesign.hw.SysDevice;

public class HelloWorld {

	private static final int WD_INTERVAL  =  500000;

	private SysDevice sys;

	public HelloWorld() {
		this.sys = SysDevice.getInstance();
	}
	
	public void run() {
		System.out.println("HelloWorld - Started");
		int w = 0, n=0, c=0;
		for (;;) {
			c = sys.uscntTimer;
			if (n <= c) {
				n = c + WD_INTERVAL;
				System.out.println("Hello World!");
				w = ~w;
				sys.wd = w;
			}
		}
	}

	public static void main(String[] args) {
		new HelloWorld().run();
	}
}
