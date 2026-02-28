package test;

import com.jopdesign.sys.*;
import com.jopdesign.fat32.*;
import java.io.IOException;

/**
 * FAT32 filesystem test for JOP.
 *
 * Exercises: SD card init, FAT32 mount, directory listing,
 * file create (with LFN), write, read back, and verify.
 */
public class Fat32Test {

	static int wd;
	static char[] hexChars = {'0','1','2','3','4','5','6','7',
	                          '8','9','A','B','C','D','E','F'};

	static void wrHex(int val) {
		for (int i = 28; i >= 0; i -= 4) {
			JVMHelp.wr(hexChars[(val >>> i) & 0xF]);
		}
	}

	static void wrInt(int val) {
		if (val < 0) {
			JVMHelp.wr('-');
			val = -val;
		}
		boolean started = false;
		for (int d = 1000000000; d >= 1; d /= 10) {
			int digit = (val / d) % 10;
			if (digit != 0 || started || d == 1) {
				JVMHelp.wr((char)('0' + digit));
				started = true;
			}
		}
	}

	static void toggleWd() {
		wd = ~wd;
		Native.wr(wd, Const.IO_WD);
	}

	static void delay(int n) {
		for (int i = 0; i < n; i++) { }
	}

	static void halt(String msg) {
		JVMHelp.wr(msg);
		JVMHelp.wr('\n');
		for (;;) { toggleWd(); delay(500000); }
	}

	public static void main(String[] args) {
		JVMHelp.wr("FAT32 Test\n\n");

		// 1. Init SD card
		JVMHelp.wr("Init SD card...\n");
		SdNativeBlockDevice sd = new SdNativeBlockDevice();
		if (!sd.init()) {
			halt("SD init failed!");
		}
		JVMHelp.wr("SD init OK\n\n");
		toggleWd();

		// 2. Mount FAT32 filesystem (partition 0)
		JVMHelp.wr("Mounting FAT32...\n");
		Fat32FileSystem fs = new Fat32FileSystem(sd);
		if (!fs.mount(0)) {
			halt("Mount failed!");
		}
		JVMHelp.wr("Mounted: ");
		wrInt(fs.getSectorsPerCluster());
		JVMHelp.wr(" sec/clust, ");
		wrInt(fs.getTotalClusters());
		JVMHelp.wr(" clusters, free=");
		wrInt(fs.getFreeCount());
		JVMHelp.wr("\n\n");
		toggleWd();

		// 3. List root directory
		JVMHelp.wr("Root directory:\n");
		DirEntry[] entries = fs.listDir(fs.getRootCluster());
		if (entries == null) {
			halt("listDir failed!");
		}
		for (int i = 0; i < entries.length; i++) {
			DirEntry e = entries[i];
			if (e == null) continue;
			JVMHelp.wr("  ");
			JVMHelp.wr(e.getName());
			if (e.isDirectory()) {
				JVMHelp.wr("/");
			} else {
				JVMHelp.wr(" (");
				wrInt(e.fileSize);
				JVMHelp.wr(" bytes)");
			}
			JVMHelp.wr('\n');
			toggleWd();
		}
		JVMHelp.wr('\n');

		// 4. Create file with LFN: "Hello from JOP.txt"
		String lfnName = "Hello from JOP.txt";
		String lfnContent = "Hello, World! Written by JOP FAT32 driver.\r\n";
		JVMHelp.wr("Creating ");
		JVMHelp.wr(lfnName);
		JVMHelp.wr("...\n");

		// Delete if already exists
		DirEntry existing = fs.findFile(fs.getRootCluster(), lfnName);
		if (existing != null) {
			JVMHelp.wr("  (deleting old copy)\n");
			fs.deleteFile(existing);
		}
		toggleWd();

		DirEntry lfnEntry = fs.createFile(fs.getRootCluster(), lfnName);
		if (lfnEntry == null) {
			halt("createFile LFN failed!");
		}
		boolean lfnOk = false;
		try {
			Fat32OutputStream out = fs.openFileForWrite(lfnEntry);
			for (int i = 0; i < lfnContent.length(); i++) {
				out.write(lfnContent.charAt(i));
			}
			out.close();
			JVMHelp.wr("  Written ");
			wrInt(lfnContent.length());
			JVMHelp.wr(" bytes\n");
			toggleWd();

			// Read back and verify
			DirEntry readEntry = fs.findFile(fs.getRootCluster(), lfnName);
			if (readEntry == null) {
				JVMHelp.wr("  FAIL: file not found after write\n");
			} else {
				Fat32InputStream in = fs.openFile(readEntry);
				boolean match = true;
				for (int i = 0; i < lfnContent.length(); i++) {
					int ch = in.read();
					if (ch != (lfnContent.charAt(i) & 0xFF)) {
						match = false;
						break;
					}
				}
				int extra = in.read();
				if (extra != -1) match = false;
				in.close();
				if (match) {
					JVMHelp.wr("  Readback PASS\n");
					lfnOk = true;
				} else {
					JVMHelp.wr("  Readback FAIL\n");
				}
			}
		} catch (IOException e) {
			JVMHelp.wr("  IOException\n");
		}
		toggleWd();

		// 5. Create 8.3 file: "TEST.TXT"
		String shortName = "TEST.TXT";
		String shortContent = "Short name test.\r\n";
		JVMHelp.wr("\nCreating ");
		JVMHelp.wr(shortName);
		JVMHelp.wr("...\n");

		existing = fs.findFile(fs.getRootCluster(), shortName);
		if (existing != null) {
			JVMHelp.wr("  (deleting old copy)\n");
			fs.deleteFile(existing);
		}
		toggleWd();

		DirEntry shortEntry = fs.createFile(fs.getRootCluster(), shortName);
		if (shortEntry == null) {
			halt("createFile 8.3 failed!");
		}
		boolean shortOk = false;
		try {
			Fat32OutputStream out = fs.openFileForWrite(shortEntry);
			for (int i = 0; i < shortContent.length(); i++) {
				out.write(shortContent.charAt(i));
			}
			out.close();
			JVMHelp.wr("  Written ");
			wrInt(shortContent.length());
			JVMHelp.wr(" bytes\n");
			toggleWd();

			// Read back and verify
			DirEntry readEntry = fs.findFile(fs.getRootCluster(), shortName);
			if (readEntry == null) {
				JVMHelp.wr("  FAIL: file not found after write\n");
			} else {
				Fat32InputStream in = fs.openFile(readEntry);
				boolean match = true;
				for (int i = 0; i < shortContent.length(); i++) {
					int ch = in.read();
					if (ch != (shortContent.charAt(i) & 0xFF)) {
						match = false;
						break;
					}
				}
				int extra = in.read();
				if (extra != -1) match = false;
				in.close();
				if (match) {
					JVMHelp.wr("  Readback PASS\n");
					shortOk = true;
				} else {
					JVMHelp.wr("  Readback FAIL\n");
				}
			}
		} catch (IOException e) {
			JVMHelp.wr("  IOException\n");
		}
		toggleWd();

		// 6. Summary
		JVMHelp.wr('\n');
		if (lfnOk && shortOk) {
			JVMHelp.wr("=== ALL TESTS PASS ===\n");
		} else {
			JVMHelp.wr("=== TESTS FAILED ===\n");
			if (!lfnOk) JVMHelp.wr("  LFN test failed\n");
			if (!shortOk) JVMHelp.wr("  8.3 test failed\n");
		}

		for (;;) { toggleWd(); delay(500000); }
	}
}
