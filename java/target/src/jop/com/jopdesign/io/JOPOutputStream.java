package com.jopdesign.io;

import java.io.IOException;
import java.io.OutputStream;

import com.jopdesign.sys.JVMHelp;

public class JOPOutputStream extends OutputStream {

	public void write(int b) throws IOException {
		JVMHelp.wr((char) (b & 0xff));
	}

}
