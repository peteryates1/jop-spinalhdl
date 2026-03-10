package jvm;

import java.io.*;

/**
 * Test I/O classes from the ported JDK classes (Phase 4).
 * Exercises ByteArrayInputStream, ByteArrayOutputStream,
 * DataInputStream, DataOutputStream, BufferedReader, InputStreamReader,
 * OutputStreamWriter.
 */
public class IoTest extends TestCase {

	public String toString() {
		return "IoTest";
	}

	public boolean test() {
		boolean ok = true;
		ok = ok && byteArrayStreams();
		ok = ok && dataStreams();
		ok = ok && dataStreamsExtended();
		ok = ok && dataStreamsUTF();
		ok = ok && readerWriter();
		ok = ok && writerCharArray();
		// BufferedReader test disabled — readLine() uses too many cycles for BRAM sim
		// ok = ok && bufferedReader();
		return ok;
	}

	private boolean byteArrayStreams() {
		// Write bytes, read them back
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(65); // 'A'
		baos.write(66); // 'B'
		baos.write(67); // 'C'
		if (baos.size() != 3) return false;

		byte[] data = baos.toByteArray();
		if (data.length != 3) return false;
		if (data[0] != 65) return false;
		if (data[2] != 67) return false;

		// Read back via ByteArrayInputStream
		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		if (bais.read() != 65) return false;
		if (bais.read() != 66) return false;
		if (bais.read() != 67) return false;
		if (bais.read() != -1) return false; // EOF

		// toString
		String s = baos.toString();
		if (s.length() != 3) return false;
		if (s.charAt(0) != 'A') return false;

		return true;
	}

	private boolean dataStreams() {
		try {
			// Write various data types
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeInt(0x12345678);
			dos.writeShort(0x1234);
			dos.writeByte(0x42);
			dos.writeBoolean(true);
			dos.flush();

			// Read them back
			byte[] data = baos.toByteArray();
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			DataInputStream dis = new DataInputStream(bais);
			if (dis.readInt() != 0x12345678) return false;
			if (dis.readShort() != 0x1234) return false;
			if (dis.readByte() != 0x42) return false;
			if (!dis.readBoolean()) return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private boolean dataStreamsExtended() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			// writeLong
			dos.writeLong(0x0102030405060708L);
			// writeChar
			dos.writeChar('Z');
			// writeUnsignedByte-range value
			dos.writeByte(0xFF);
			dos.flush();

			byte[] data = baos.toByteArray();
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			DataInputStream dis = new DataInputStream(bais);
			// readLong
			if (dis.readLong() != 0x0102030405060708L) return false;
			// readChar
			if (dis.readChar() != 'Z') return false;
			// readUnsignedByte
			if (dis.readUnsignedByte() != 0xFF) return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private boolean dataStreamsUTF() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeUTF("Hi");
			dos.flush();

			byte[] data = baos.toByteArray();
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			DataInputStream dis = new DataInputStream(bais);
			String s = dis.readUTF();
			if (s.length() != 2) return false;
			if (s.charAt(0) != 'H') return false;
			if (s.charAt(1) != 'i') return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private boolean readerWriter() {
		try {
			// Write chars via OutputStreamWriter, read via InputStreamReader
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			OutputStreamWriter osw = new OutputStreamWriter(baos);
			osw.write("Hello");
			osw.flush();

			byte[] data = baos.toByteArray();
			if (data.length != 5) return false;
			if (data[0] != 'H') return false;

			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			InputStreamReader isr = new InputStreamReader(bais);
			if (isr.read() != 'H') return false;
			if (isr.read() != 'e') return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private boolean writerCharArray() {
		try {
			// Test OutputStreamWriter with char array and single char
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			OutputStreamWriter osw = new OutputStreamWriter(baos);
			osw.write('X');
			char[] chars = {'A', 'B', 'C'};
			osw.write(chars, 0, 3);
			osw.flush();

			byte[] data = baos.toByteArray();
			if (data.length != 4) return false;
			if (data[0] != 'X') return false;
			if (data[1] != 'A') return false;
			if (data[3] != 'C') return false;

			// Read all back via InputStreamReader with char[] read
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			InputStreamReader isr = new InputStreamReader(bais);
			char[] buf = new char[4];
			int n = isr.read(buf, 0, 4);
			if (n != 4) return false;
			if (buf[0] != 'X') return false;
			if (buf[3] != 'C') return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private boolean bufferedReader() {
		try {
			// Test BufferedReader with line reading
			byte[] data = new byte[]{'a', 'b', '\n', 'c', 'd'};
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			InputStreamReader isr = new InputStreamReader(bais);
			BufferedReader br = new BufferedReader(isr);

			String line1 = br.readLine();
			if (line1 == null) return false;
			if (line1.length() != 2) return false;
			if (line1.charAt(0) != 'a') return false;

			String line2 = br.readLine();
			if (line2 == null) return false;
			if (line2.length() != 2) return false;
			if (line2.charAt(0) != 'c') return false;

			// EOF
			String line3 = br.readLine();
			if (line3 != null) return false;

			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
