package test;

import com.jopdesign.sys.*;
import com.jopdesign.net.*;

/**
 * What the network stack's per-byte primitives cost on this core, in clock
 * cycles per byte, over a full-size segment (1460 bytes): the Packet
 * accessors, the checksum loop TCP.checksum runs, and the TCP stream buffers
 * that processData, an echo and sendSegment pass every byte through.
 *
 * Build:
 *   make -C java/apps/Small JOP_PRESET=<preset> APP_NAME=NetBench EXTRA_SRC=../../net/src
 *
 * Prints one "<name> <cycles per byte>" line per primitive, then "NETBENCH DONE".
 */
public class NetBench {

	static final int N = 1460;

	static int sink;

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char) ('0' + digit));
				started = true;
			}
		}
	}

	/** Cycles per byte, to one decimal place. */
	static void report(String name, int cycles) {
		JVMHelp.wr(name);
		JVMHelp.wr(' ');
		int tenths = cycles * 10 / N;
		wrInt(tenths / 10);
		JVMHelp.wr('.');
		wrInt(tenths % 10);
		JVMHelp.wr('\n');
	}

	static int now() {
		return Native.rd(Const.IO_CNT);
	}

	static void empty() {
	}

	public static void main(String[] args) {
		JVMHelp.wr("NetBench\n");
		Packet.initPool();
		Packet pkt = Packet.alloc();
		for (int i = 0; i < Packet.MAX_WORDS; i++) {
			pkt.buf[i] = i * 0x01010101;
		}
		TCPInputStream in = new TCPInputStream(4096);
		TCPOutputStream out = new TCPOutputStream(4096);
		int t, s;

		t = now();
		for (int i = 0; i < N; i++) {
		}
		report("loop", now() - t);

		t = now();
		for (int i = 0; i < N; i++) {
			empty();
		}
		report("call", now() - t);

		s = 0;
		t = now();
		for (int i = 0; i < N; i++) {
			s += pkt.getByte(i);
		}
		report("getByte", now() - t);
		sink = s;

		t = now();
		for (int i = 0; i < N; i++) {
			pkt.setByte(i, i);
		}
		report("setByte", now() - t);

		// TCP.checksum's loop over the same bytes
		s = 0;
		t = now();
		int i2;
		for (i2 = 0; i2 + 1 < N; i2 += 2) {
			s += pkt.getShort(i2);
		}
		while ((s >>> 16) != 0) {
			s = (s & 0xFFFF) + (s >>> 16);
		}
		report("checksum", now() - t);
		sink = s;

		t = now();
		for (int i = 0; i < N; i++) {
			in.write(i);
		}
		report("in.write", now() - t);

		t = now();
		for (int i = 0; i < N; i++) {
			s += in.read();
		}
		report("in.read", now() - t);
		sink = s;

		t = now();
		for (int i = 0; i < N; i++) {
			out.write(i);
		}
		report("out.write", now() - t);

		t = now();
		for (int i = 0; i < N; i++) {
			s += out.read();
		}
		report("out.read", now() - t);
		out.ackData(N);
		sink = s;

		// processData's copy: packet into the receive stream
		t = now();
		for (int i = 0; i < N; i++) {
			in.write(pkt.getByte(54 + i));
		}
		report("rx-copy", now() - t);

		// NetTest.echo: receive stream into the send stream
		t = now();
		int b = in.read();
		while (b != -1) {
			out.write(b);
			b = in.read();
		}
		report("echo-copy", now() - t);

		// sendSegment's copy: send stream into a packet
		t = now();
		for (int i = 0; i < N; i++) {
			int v = out.read();
			if (v == -1) break;
			pkt.setByte(54 + i, v);
		}
		report("tx-copy", now() - t);
		out.ackData(N);

		// The bulk paths that replace them: a call per pass
		t = now();
		s = pkt.sum16(54, N);
		report("sum16", now() - t);
		sink = s;

		t = now();
		in.write(pkt, 54, N);
		report("rx-bulk", now() - t);

		byte[] tmp = new byte[N];
		t = now();
		in.read(tmp, 0, N);
		out.write(tmp, 0, N);
		report("echo-bulk", now() - t);

		t = now();
		out.read(pkt, 54, N);
		report("tx-bulk", now() - t);
		out.ackData(N);

		JVMHelp.wr("NETBENCH DONE\n");
		for (;;) {
		}
	}
}
