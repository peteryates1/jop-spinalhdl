package com.jopdesign.fat32;

import java.io.IOException;

/**
 * Simulation test for FAT32 filesystem on standard JVM.
 * Uses a FAT32 disk image file as the block device.
 *
 * Usage: java com.jopdesign.fat32.Fat32SimTest <disk.img>
 *
 * Create test image:
 *   dd if=/dev/zero of=test.img bs=1M count=16
 *   mkfs.fat -F 32 test.img
 */
public class Fat32SimTest {

	static int passed = 0;
	static int failed = 0;

	static void check(String name, boolean condition) {
		if (condition) {
			System.out.println("  PASS: " + name);
			passed++;
		} else {
			System.out.println("  FAIL: " + name);
			failed++;
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java com.jopdesign.fat32.Fat32SimTest <disk.img>");
			System.exit(1);
		}

		String imgPath = args[0];
		FileBlockDevice dev = new FileBlockDevice(imgPath);
		check("FileBlockDevice created", dev.init());

		// --- Test 1: Mount ---
		System.out.println("\n=== Test 1: Mount FAT32 ===");
		Fat32FileSystem fs = new Fat32FileSystem(dev);
		boolean mounted = fs.mount(0);
		check("mount(0)", mounted);
		if (!mounted) {
			System.out.println("Cannot continue without mount. Is the image FAT32 with MBR?");
			System.out.println("Trying without partition table (superfloppy)...");
			// For images without MBR (mkfs.fat without partition table),
			// we can't use mount(). Let's check if it's a superfloppy.
			System.out.println("ABORT: need MBR-partitioned FAT32 image");
			dev.close();
			System.exit(1);
		}
		System.out.println("  sectorsPerCluster=" + fs.getSectorsPerCluster());
		System.out.println("  totalClusters=" + fs.getTotalClusters());
		System.out.println("  rootCluster=" + fs.getRootCluster());
		System.out.println("  freeCount=" + fs.getFreeCount());

		// --- Test 2: List root directory (should be empty or have volume label) ---
		System.out.println("\n=== Test 2: List root directory ===");
		DirEntry[] entries = fs.listDir(fs.getRootCluster());
		check("listDir not null", entries != null);
		if (entries != null) {
			System.out.println("  " + entries.length + " entries:");
			for (int i = 0; i < entries.length; i++) {
				DirEntry e = entries[i];
				if (e != null) {
					System.out.println("    " + e.getName()
						+ (e.isDirectory() ? "/" : " (" + e.fileSize + " bytes)")
						+ (e.hasLongName() ? " [LFN: " + e.longName + "]" : ""));
				}
			}
		}

		// --- Test 3: Create 8.3 file ---
		System.out.println("\n=== Test 3: Create 8.3 file (TEST.TXT) ===");
		String shortName = "TEST.TXT";
		// Delete if exists
		DirEntry existing = fs.findFile(fs.getRootCluster(), shortName);
		if (existing != null) {
			System.out.println("  Deleting existing " + shortName);
			check("delete existing", fs.deleteFile(existing));
		}

		DirEntry shortEntry = fs.createFile(fs.getRootCluster(), shortName);
		check("createFile 8.3", shortEntry != null);

		if (shortEntry != null) {
			String content = "Hello from JOP FAT32 simulation test!\r\n";
			Fat32OutputStream out = fs.openFileForWrite(shortEntry);
			for (int i = 0; i < content.length(); i++) {
				out.write(content.charAt(i));
			}
			out.close();
			check("write " + content.length() + " bytes", true);

			// Read back
			DirEntry found = fs.findFile(fs.getRootCluster(), shortName);
			check("findFile after write", found != null);
			if (found != null) {
				check("fileSize matches", found.fileSize == content.length());
				Fat32InputStream in = fs.openFile(found);
				boolean match = true;
				for (int i = 0; i < content.length(); i++) {
					int ch = in.read();
					if (ch != (content.charAt(i) & 0xFF)) {
						System.out.println("    Mismatch at byte " + i
							+ ": expected " + (int) content.charAt(i) + " got " + ch);
						match = false;
						break;
					}
				}
				int eof = in.read();
				check("EOF after content", eof == -1);
				in.close();
				check("readback matches", match);
			}
		}

		// --- Test 4: Create LFN file ---
		System.out.println("\n=== Test 4: Create LFN file (Hello from JOP.txt) ===");
		String lfnName = "Hello from JOP.txt";
		existing = fs.findFile(fs.getRootCluster(), lfnName);
		if (existing != null) {
			System.out.println("  Deleting existing " + lfnName);
			check("delete existing LFN", fs.deleteFile(existing));
		}

		check("needsLfn", Fat32FileSystem.needsLfn(lfnName));

		DirEntry lfnEntry = fs.createFile(fs.getRootCluster(), lfnName);
		check("createFile LFN", lfnEntry != null);

		if (lfnEntry != null) {
			String content = "Long filename test content.\r\nLine 2.\r\n";
			Fat32OutputStream out = fs.openFileForWrite(lfnEntry);
			for (int i = 0; i < content.length(); i++) {
				out.write(content.charAt(i));
			}
			out.close();
			check("write " + content.length() + " bytes", true);

			// Read back by LFN
			DirEntry found = fs.findFile(fs.getRootCluster(), lfnName);
			check("findFile by LFN", found != null);
			if (found != null) {
				check("has long name", found.hasLongName());
				check("long name matches", lfnName.equals(found.longName));
				check("fileSize matches", found.fileSize == content.length());
				Fat32InputStream in = fs.openFile(found);
				boolean match = true;
				for (int i = 0; i < content.length(); i++) {
					int ch = in.read();
					if (ch != (content.charAt(i) & 0xFF)) {
						System.out.println("    Mismatch at byte " + i
							+ ": expected " + (int) content.charAt(i) + " got " + ch);
						match = false;
						break;
					}
				}
				int eof = in.read();
				check("EOF after content", eof == -1);
				in.close();
				check("readback matches", match);
			}
		}

		// --- Test 5: Verify directory listing shows both files ---
		System.out.println("\n=== Test 5: Final directory listing ===");
		entries = fs.listDir(fs.getRootCluster());
		check("listDir not null", entries != null);
		if (entries != null) {
			boolean foundShort = false;
			boolean foundLfn = false;
			System.out.println("  " + entries.length + " entries:");
			for (int i = 0; i < entries.length; i++) {
				DirEntry e = entries[i];
				if (e != null) {
					System.out.println("    " + e.getName()
						+ (e.isDirectory() ? "/" : " (" + e.fileSize + " bytes)")
						+ (e.hasLongName() ? " [LFN]" : " [8.3]"));
					if (e.nameMatches(shortName)) foundShort = true;
					if (e.nameMatches(lfnName)) foundLfn = true;
				}
			}
			check("TEST.TXT in listing", foundShort);
			check("Hello from JOP.txt in listing", foundLfn);
		}

		// --- Test 6: Delete files ---
		System.out.println("\n=== Test 6: Delete files ===");
		DirEntry toDelete = fs.findFile(fs.getRootCluster(), shortName);
		check("find TEST.TXT for delete", toDelete != null);
		if (toDelete != null) {
			check("delete TEST.TXT", fs.deleteFile(toDelete));
			check("TEST.TXT gone", fs.findFile(fs.getRootCluster(), shortName) == null);
		}

		toDelete = fs.findFile(fs.getRootCluster(), lfnName);
		check("find LFN for delete", toDelete != null);
		if (toDelete != null) {
			check("delete LFN file", fs.deleteFile(toDelete));
			check("LFN file gone", fs.findFile(fs.getRootCluster(), lfnName) == null);
		}

		// Flush
		fs.flush();
		dev.close();

		// --- Summary ---
		System.out.println("\n=== Summary ===");
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed == 0) {
			System.out.println("ALL TESTS PASS");
		} else {
			System.out.println("TESTS FAILED");
			System.exit(1);
		}
	}
}
