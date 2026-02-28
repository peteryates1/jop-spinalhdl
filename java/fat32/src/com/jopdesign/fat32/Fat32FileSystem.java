package com.jopdesign.fat32;

import java.io.IOException;

/**
 * FAT32 filesystem with read-write support and LFN (long filename) handling.
 *
 * Provides mount, directory listing, file open/create/delete, and FAT management.
 * Uses single-sector caching for FAT and directory operations.
 */
public class Fat32FileSystem {
	// FAT entry constants
	private static final int FAT_EOF = 0x0FFFFFFF;
	private static final int FAT_FREE = 0x00000000;
	private static final int FAT_MASK = 0x0FFFFFFF;

	// BPB fields
	private int sectorsPerCluster;
	private int reservedSectors;
	private int numFATs;
	private int fatSectors;
	private int rootCluster;
	private int totalClusters;
	private int partitionStart; // LBA of partition start

	// Computed
	private int fatStartSector;
	private int clusterStart; // first sector of cluster 2

	// Caches
	private Sector fatSector;
	private Sector dirSector;
	private BlockDevice dev;
	private FsInfo fsInfo;

	private boolean mounted;

	public Fat32FileSystem(BlockDevice dev) {
		this.dev = dev;
		this.fatSector = new Sector();
		this.dirSector = new Sector();
		this.mounted = false;
	}

	/** Mount partition at given index (0-3). Returns true on success. */
	public boolean mount(int partIndex) {
		if (partIndex < 0 || partIndex > 3) return false;

		// Read MBR (sector 0)
		Sector mbr = new Sector();
		if (!mbr.load(dev, 0)) return false;

		// Check boot signature
		if (mbr.getByte(510) != 0x55 || mbr.getByte(511) != 0xAA) return false;

		// Parse partition table entry
		int base = 446 + partIndex * 16;
		int type = mbr.getByte(base + 4);
		if (type != 0x0B && type != 0x0C) return false; // Not FAT32

		partitionStart = mbr.getLE32(base + 8);
		if (partitionStart == 0) return false;

		// Read BPB (Boot Parameter Block)
		Sector bpb = new Sector();
		if (!bpb.load(dev, partitionStart)) return false;

		// Validate BPB signature
		if (bpb.getByte(510) != 0x55 || bpb.getByte(511) != 0xAA) return false;

		int bytesPerSector = bpb.getLE16(11);
		if (bytesPerSector != 512) return false; // Only support 512-byte sectors

		sectorsPerCluster = bpb.getByte(13);
		reservedSectors = bpb.getLE16(14);
		numFATs = bpb.getByte(16);
		fatSectors = bpb.getLE32(36);
		rootCluster = bpb.getLE32(44);
		int fsInfoSector = bpb.getLE16(48);

		fatStartSector = partitionStart + reservedSectors;
		int dataStart = fatStartSector + numFATs * fatSectors;
		clusterStart = dataStart - 2 * sectorsPerCluster; // cluster 2 maps to dataStart

		// Compute total clusters (approximate)
		int totalSectors = bpb.getLE32(32);
		if (totalSectors == 0) totalSectors = bpb.getLE16(19);
		int dataSectors = totalSectors - reservedSectors - numFATs * fatSectors;
		totalClusters = dataSectors / sectorsPerCluster;

		// Load FSInfo
		fsInfo = new FsInfo(dev);
		if (fsInfoSector != 0 && fsInfoSector != 0xFFFF) {
			fsInfo.load(partitionStart + fsInfoSector);
		}

		mounted = true;
		return true;
	}

	public boolean isMounted() { return mounted; }
	public int getSectorsPerCluster() { return sectorsPerCluster; }
	public int getRootCluster() { return rootCluster; }
	public int getTotalClusters() { return totalClusters; }
	public int getFreeCount() { return fsInfo != null ? fsInfo.freeCount : -1; }

	/** Convert cluster number to first sector number. */
	public int clusterToSector(int cluster) {
		return clusterStart + cluster * sectorsPerCluster;
	}

	// ---- FAT access ----

	/** Read a FAT entry for the given cluster. */
	public int readFatEntry(int cluster) {
		int fatOffset = cluster * 4;
		int fatSectorNum = fatStartSector + (fatOffset / 512);
		int entryOffset = fatOffset % 512;
		if (!fatSector.load(dev, fatSectorNum)) return FAT_EOF;
		return fatSector.getLE32(entryOffset) & FAT_MASK;
	}

	/** Write a FAT entry for the given cluster. */
	public boolean writeFatEntry(int cluster, int value) {
		int fatOffset = cluster * 4;
		int fatSectorNum = fatStartSector + (fatOffset / 512);
		int entryOffset = fatOffset % 512;
		if (!fatSector.load(dev, fatSectorNum)) return false;
		// Preserve upper 4 bits
		int old = fatSector.getLE32(entryOffset);
		fatSector.setLE32(entryOffset, (old & 0xF0000000) | (value & FAT_MASK));
		return true;
	}

	/** Flush FAT sector to all FAT copies. */
	public boolean flushFatSector() {
		if (!fatSector.dirty || !fatSector.valid) return true;
		int originalSector = fatSector.sectorNum;
		// Write to FAT1
		if (!dev.writeBlock(originalSector, fatSector.buf)) return false;
		// Write to FAT2
		if (numFATs > 1) {
			int fat2sector = originalSector + fatSectors;
			if (!dev.writeBlock(fat2sector, fatSector.buf)) return false;
		}
		fatSector.dirty = false;
		return true;
	}

	// ---- Cluster allocation ----

	/** Allocate a free cluster, mark as EOF. Returns cluster number or -1. */
	public int allocateCluster() {
		int start = (fsInfo != null && fsInfo.nextFree >= 2) ? fsInfo.nextFree : 2;
		// Scan from hint
		for (int c = start; c < totalClusters + 2; c++) {
			if (readFatEntry(c) == FAT_FREE) {
				writeFatEntry(c, FAT_EOF);
				if (!flushFatSector()) return -1;
				if (fsInfo != null) {
					fsInfo.nextFree = c + 1;
					if (fsInfo.freeCount != -1) fsInfo.freeCount--;
				}
				return c;
			}
		}
		// Wrap around
		for (int c = 2; c < start; c++) {
			if (readFatEntry(c) == FAT_FREE) {
				writeFatEntry(c, FAT_EOF);
				if (!flushFatSector()) return -1;
				if (fsInfo != null) {
					fsInfo.nextFree = c + 1;
					if (fsInfo.freeCount != -1) fsInfo.freeCount--;
				}
				return c;
			}
		}
		return -1; // Disk full
	}

	/** Allocate a new cluster and link it after lastCluster. Returns new cluster or -1. */
	public int extendChain(int lastCluster) {
		int newCluster = allocateCluster();
		if (newCluster == -1) return -1;
		writeFatEntry(lastCluster, newCluster);
		if (!flushFatSector()) return -1;
		return newCluster;
	}

	/** Free all clusters in a chain starting at startCluster. */
	public void freeClusterChain(int startCluster) {
		int c = startCluster;
		while (c >= 2 && c < FAT_EOF) {
			int next = readFatEntry(c);
			writeFatEntry(c, FAT_FREE);
			flushFatSector();
			if (fsInfo != null && fsInfo.freeCount != -1) fsInfo.freeCount++;
			c = next;
		}
	}

	/** Follow FAT chain to find the last cluster. */
	public int getLastCluster(int startCluster) {
		int c = startCluster;
		while (true) {
			int next = readFatEntry(c);
			if (next >= 0x0FFFFFF8) return c;
			if (next < 2) return c;
			c = next;
		}
	}

	/** Follow FAT chain and return the Nth cluster (0-indexed). */
	public int getClusterN(int startCluster, int n) {
		int c = startCluster;
		for (int i = 0; i < n; i++) {
			c = readFatEntry(c);
			if (c >= 0x0FFFFFF8 || c < 2) return -1;
		}
		return c;
	}

	// ---- Directory operations ----

	/**
	 * List directory entries for the directory starting at the given cluster.
	 * Returns array of DirEntry (excludes LFN-only entries and deleted entries).
	 * Supports long filenames by accumulating LFN entries.
	 */
	public DirEntry[] listDir(int dirCluster) {
		// Two-pass: count entries, then allocate + fill
		int count = countDirEntries(dirCluster);
		if (count < 0) return null;
		DirEntry[] entries = new DirEntry[count];
		fillDirEntries(dirCluster, entries);
		return entries;
	}

	private int countDirEntries(int dirCluster) {
		int count = 0;
		int cluster = dirCluster;
		while (cluster >= 2 && cluster < 0x0FFFFFF8) {
			int baseSector = clusterToSector(cluster);
			for (int s = 0; s < sectorsPerCluster; s++) {
				if (!dirSector.load(dev, baseSector + s)) return -1;
				for (int off = 0; off < 512; off += 32) {
					int first = dirSector.getByte(off);
					if (first == 0x00) return count; // End of directory
					if (first == 0xE5) continue; // Deleted
					int attr = dirSector.getByte(off + 11);
					if (attr == DirEntry.ATTR_LFN) continue; // LFN entry
					if ((attr & DirEntry.ATTR_VOLUME_ID) != 0) continue; // Volume label
					count++;
				}
			}
			cluster = readFatEntry(cluster);
		}
		return count;
	}

	private void fillDirEntries(int dirCluster, DirEntry[] entries) {
		int idx = 0;
		int cluster = dirCluster;
		StringBuilder lfnBuf = new StringBuilder(256);
		int lfnCount = 0;

		while (cluster >= 2 && cluster < 0x0FFFFFF8) {
			int baseSector = clusterToSector(cluster);
			for (int s = 0; s < sectorsPerCluster; s++) {
				int sector = baseSector + s;
				if (!dirSector.load(dev, sector)) return;
				for (int off = 0; off < 512; off += 32) {
					int first = dirSector.getByte(off);
					if (first == 0x00) return; // End of directory
					if (first == 0xE5) {
						lfnBuf.setLength(0);
						lfnCount = 0;
						continue;
					}
					int attr = dirSector.getByte(off + 11);
					if (attr == DirEntry.ATTR_LFN) {
						// LFN entry: accumulate characters
						int seq = first & 0x3F;
						if ((first & 0x40) != 0) {
							// First (last on disk) LFN entry
							lfnBuf.setLength(0);
							lfnCount = 0;
						}
						lfnCount++;
						// Extract 13 UCS-2 characters from LFN entry
						char[] lfnChars = extractLfnChars(off);
						// Insert at correct position: (seq-1)*13
						int pos = (seq - 1) * 13;
						// Ensure buffer is large enough
						while (lfnBuf.length() < pos + 13) lfnBuf.append('\0');
						for (int i = 0; i < 13; i++) {
							if (lfnChars[i] == 0x0000 || lfnChars[i] == 0xFFFF) break;
							lfnBuf.setCharAt(pos + i, lfnChars[i]);
						}
						continue;
					}
					if ((attr & DirEntry.ATTR_VOLUME_ID) != 0) {
						lfnBuf.setLength(0);
						lfnCount = 0;
						continue;
					}

					// Standard 8.3 entry
					if (idx >= entries.length) return;
					DirEntry e = new DirEntry();
					e.shortName = readShortName(off);
					e.attr = attr;
					e.clusterHi = dirSector.getLE16(off + 20);
					e.clusterLo = dirSector.getLE16(off + 26);
					e.fileSize = dirSector.getLE32(off + 28);
					e.dirSectorNum = sector;
					e.dirEntryOffset = off;

					// Attach LFN if accumulated
					if (lfnBuf.length() > 0) {
						// Trim null characters
						int len = lfnBuf.length();
						while (len > 0 && lfnBuf.charAt(len - 1) == '\0') len--;
						if (len > 0) {
							lfnBuf.setLength(len);
							e.longName = lfnBuf.toString();
						}
						e.lfnEntryCount = lfnCount;
					}
					lfnBuf.setLength(0);
					lfnCount = 0;

					entries[idx++] = e;
				}
			}
			cluster = readFatEntry(cluster);
		}
	}

	/** Extract 13 UCS-2 characters from an LFN directory entry at the given offset. */
	private char[] extractLfnChars(int off) {
		char[] chars = new char[13];
		// Bytes 1-10: chars 0-4
		for (int i = 0; i < 5; i++) {
			chars[i] = (char) dirSector.getLE16(off + 1 + i * 2);
		}
		// Bytes 14-25: chars 5-10
		for (int i = 0; i < 6; i++) {
			chars[5 + i] = (char) dirSector.getLE16(off + 14 + i * 2);
		}
		// Bytes 28-31: chars 11-12
		for (int i = 0; i < 2; i++) {
			chars[11 + i] = (char) dirSector.getLE16(off + 28 + i * 2);
		}
		return chars;
	}

	/** Read 11-byte short name from directory entry at offset. */
	private String readShortName(int off) {
		char[] name = new char[11];
		for (int i = 0; i < 11; i++) {
			name[i] = (char) dirSector.getByte(off + i);
		}
		return new String(name);
	}

	/**
	 * Find a file/directory in the given directory cluster by name.
	 * Case-insensitive match against both long and short names.
	 */
	public DirEntry findFile(int dirCluster, String name) {
		int cluster = dirCluster;
		StringBuilder lfnBuf = new StringBuilder(256);
		int lfnCount = 0;

		while (cluster >= 2 && cluster < 0x0FFFFFF8) {
			int baseSector = clusterToSector(cluster);
			for (int s = 0; s < sectorsPerCluster; s++) {
				int sector = baseSector + s;
				if (!dirSector.load(dev, sector)) return null;
				for (int off = 0; off < 512; off += 32) {
					int first = dirSector.getByte(off);
					if (first == 0x00) return null;
					if (first == 0xE5) {
						lfnBuf.setLength(0);
						lfnCount = 0;
						continue;
					}
					int attr = dirSector.getByte(off + 11);
					if (attr == DirEntry.ATTR_LFN) {
						int seq = first & 0x3F;
						if ((first & 0x40) != 0) {
							lfnBuf.setLength(0);
							lfnCount = 0;
						}
						lfnCount++;
						char[] lfnChars = extractLfnChars(off);
						int pos = (seq - 1) * 13;
						while (lfnBuf.length() < pos + 13) lfnBuf.append('\0');
						for (int i = 0; i < 13; i++) {
							if (lfnChars[i] == 0x0000 || lfnChars[i] == 0xFFFF) break;
							lfnBuf.setCharAt(pos + i, lfnChars[i]);
						}
						continue;
					}
					if ((attr & DirEntry.ATTR_VOLUME_ID) != 0) {
						lfnBuf.setLength(0);
						lfnCount = 0;
						continue;
					}

					DirEntry e = new DirEntry();
					e.shortName = readShortName(off);
					e.attr = attr;
					e.clusterHi = dirSector.getLE16(off + 20);
					e.clusterLo = dirSector.getLE16(off + 26);
					e.fileSize = dirSector.getLE32(off + 28);
					e.dirSectorNum = sector;
					e.dirEntryOffset = off;

					if (lfnBuf.length() > 0) {
						int len = lfnBuf.length();
						while (len > 0 && lfnBuf.charAt(len - 1) == '\0') len--;
						if (len > 0) {
							lfnBuf.setLength(len);
							e.longName = lfnBuf.toString();
						}
						e.lfnEntryCount = lfnCount;
					}
					lfnBuf.setLength(0);
					lfnCount = 0;

					if (e.nameMatches(name)) return e;
				}
			}
			cluster = readFatEntry(cluster);
		}
		return null;
	}

	// ---- File creation ----

	/**
	 * Check if a filename is a valid 8.3 short name (no LFN needed).
	 */
	public static boolean needsLfn(String name) {
		if (name.length() > 12) return true;
		int dotIdx = name.lastIndexOf('.');
		int nameLen, extLen;
		if (dotIdx == -1) {
			nameLen = name.length();
			extLen = 0;
		} else if (dotIdx == 0) {
			return true; // Starts with dot
		} else {
			nameLen = dotIdx;
			extLen = name.length() - dotIdx - 1;
		}
		if (nameLen > 8 || extLen > 3) return true;

		// Check for illegal characters
		String upper = name.toUpperCase();
		for (int i = 0; i < upper.length(); i++) {
			char c = upper.charAt(i);
			if (c == '.') continue;
			if (c >= 'A' && c <= 'Z') continue;
			if (c >= '0' && c <= '9') continue;
			if (c == '!' || c == '#' || c == '$' || c == '%' || c == '&' ||
				c == '\'' || c == '(' || c == ')' || c == '-' || c == '@' ||
				c == '^' || c == '_' || c == '`' || c == '{' || c == '}' ||
				c == '~') continue;
			return true; // Needs LFN
		}

		// Check if lowercase letters present (need LFN if mixed case)
		boolean hasLower = false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'a' && c <= 'z') { hasLower = true; break; }
		}
		// Actually FAT32 short names are case-insensitive, so lowercase is fine
		// as long as the name fits 8.3 format. We just uppercase it.
		return false;
	}

	/**
	 * Compute LFN checksum from 11-byte short name.
	 */
	public static int lfnChecksum(String shortName) {
		int sum = 0;
		for (int i = 0; i < 11; i++) {
			sum = (((sum & 1) << 7) | ((sum & 0xFF) >> 1)) + shortName.charAt(i);
			sum &= 0xFF;
		}
		return sum;
	}

	/**
	 * Generate a valid 8.3 short name from a long name.
	 * Uses ~N suffix if needed. Checks for collisions in directory.
	 */
	public String generateShortName(String longName, int dirCluster) {
		// Strip leading/trailing spaces and dots, uppercase
		String name = longName.toUpperCase().trim();

		// Split on last dot
		int dotIdx = name.lastIndexOf('.');
		String basePart;
		String extPart;
		if (dotIdx > 0) {
			basePart = name.substring(0, dotIdx);
			extPart = name.substring(dotIdx + 1);
		} else {
			basePart = name;
			extPart = "";
		}

		// Strip illegal characters and spaces from base and ext
		basePart = stripIllegalChars(basePart);
		extPart = stripIllegalChars(extPart);

		// Truncate extension to 3
		if (extPart.length() > 3) extPart = extPart.substring(0, 3);

		// Build 11-char short name candidate with ~N suffix
		for (int n = 1; n < 100; n++) {
			char[] sn = new char[11];
			for (int i = 0; i < 11; i++) sn[i] = ' ';

			int suffLen = (n < 10) ? 2 : 3;
			int maxBase = 8 - suffLen;
			int bLen = basePart.length();
			if (bLen > maxBase) bLen = maxBase;
			for (int i = 0; i < bLen; i++) {
				sn[i] = basePart.charAt(i);
			}
			sn[bLen] = '~';
			if (n < 10) {
				sn[bLen + 1] = (char)('0' + n);
			} else {
				sn[bLen + 1] = (char)('0' + n / 10);
				sn[bLen + 2] = (char)('0' + n % 10);
			}
			int eLen = extPart.length();
			if (eLen > 3) eLen = 3;
			for (int i = 0; i < eLen; i++) {
				sn[8 + i] = extPart.charAt(i);
			}

			String snStr = new String(sn);
			// Check for collision
			if (findFile(dirCluster, DirEntry.formatShortName(snStr)) == null) {
				return snStr;
			}
		}
		return null;
	}

	/** Strip illegal chars for 8.3 short name generation. Uses char[] to avoid StringBuilder. */
	private static String stripIllegalChars(String s) {
		// Count valid chars first
		int validCount = 0;
		for (int i = 0; i < s.length(); i++) {
			if (!isIllegalShortNameChar(s.charAt(i))) validCount++;
		}
		// Build result array
		char[] result = new char[validCount];
		int pos = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!isIllegalShortNameChar(c)) result[pos++] = c;
		}
		return new String(result);
	}

	private static boolean isIllegalShortNameChar(char c) {
		return c == ' ' || c == '.' || c == '+' || c == ',' || c == ';' ||
			c == '=' || c == '[' || c == ']';
	}

	/**
	 * Create a new file in the given directory. Returns the DirEntry or null on failure.
	 * For names that fit 8.3, writes a single entry. Otherwise generates LFN entries.
	 */
	public DirEntry createFile(int dirCluster, String name) {
		// Check if file already exists
		if (findFile(dirCluster, name) != null) return null;

		String shortName;
		boolean useLfn;
		if (needsLfn(name)) {
			useLfn = true;
			shortName = generateShortName(name, dirCluster);
			if (shortName == null) return null;
		} else {
			useLfn = false;
			// Build 8.3 short name from input
			shortName = buildShortName(name);
		}

		int totalEntries = useLfn ? (((name.length() + 12) / 13) + 1) : 1;

		// Find consecutive free slots
		int[] slotInfo = findFreeSlots(dirCluster, totalEntries);
		if (slotInfo == null) return null;
		int slotSector = slotInfo[0];
		int slotOffset = slotInfo[1];

		// Allocate cluster for the file
		int fileCluster = allocateCluster();
		if (fileCluster == -1) return null;

		// Write LFN entries if needed
		if (useLfn) {
			int lfnEntries = totalEntries - 1;
			int checksum = lfnChecksum(shortName);
			writeLfnEntries(slotSector, slotOffset, name, lfnEntries, checksum);
			// Advance to 8.3 entry position
			slotOffset += lfnEntries * 32;
			while (slotOffset >= 512) {
				slotOffset -= 512;
				slotSector++;
			}
		}

		// Write 8.3 entry
		if (!dirSector.load(dev, slotSector)) return null;
		// Write short name
		for (int i = 0; i < 11; i++) {
			dirSector.setByte(slotOffset + i, shortName.charAt(i));
		}
		dirSector.setByte(slotOffset + 11, DirEntry.ATTR_ARCHIVE); // attr
		dirSector.setByte(slotOffset + 12, 0); // NTRes
		dirSector.setByte(slotOffset + 13, 0); // CrtTimeTenth
		dirSector.setLE16(slotOffset + 14, 0); // CrtTime
		dirSector.setLE16(slotOffset + 16, 0); // CrtDate
		dirSector.setLE16(slotOffset + 18, 0); // LstAccDate
		dirSector.setLE16(slotOffset + 20, (fileCluster >> 16) & 0xFFFF); // FstClusHI
		dirSector.setLE16(slotOffset + 22, 0); // WrtTime
		dirSector.setLE16(slotOffset + 24, 0x0021); // WrtDate = 1980-01-01
		dirSector.setLE16(slotOffset + 26, fileCluster & 0xFFFF); // FstClusLO
		dirSector.setLE32(slotOffset + 28, 0); // FileSize
		if (!dirSector.flush(dev)) return null;

		DirEntry e = new DirEntry();
		e.shortName = shortName;
		if (useLfn) e.longName = name;
		e.attr = DirEntry.ATTR_ARCHIVE;
		e.setStartCluster(fileCluster);
		e.fileSize = 0;
		e.dirSectorNum = slotSector;
		e.dirEntryOffset = slotOffset;
		e.lfnEntryCount = useLfn ? (totalEntries - 1) : 0;
		return e;
	}

	/** Build 11-char 8.3 short name from a name that fits (uppercase, padded). */
	private String buildShortName(String name) {
		String upper = name.toUpperCase();
		int dotIdx = upper.lastIndexOf('.');
		char[] sn = new char[11];
		for (int i = 0; i < 11; i++) sn[i] = ' ';

		if (dotIdx == -1) {
			for (int i = 0; i < upper.length() && i < 8; i++) {
				sn[i] = upper.charAt(i);
			}
		} else {
			for (int i = 0; i < dotIdx && i < 8; i++) {
				sn[i] = upper.charAt(i);
			}
			int extStart = dotIdx + 1;
			for (int i = 0; i < 3 && extStart + i < upper.length(); i++) {
				sn[8 + i] = upper.charAt(extStart + i);
			}
		}
		return new String(sn);
	}

	/**
	 * Find totalEntries consecutive free slots (0x00 or 0xE5) in the directory.
	 * Extends directory cluster chain if needed.
	 * Returns int[] {sectorNum, byteOffset} of first slot, or null.
	 */
	private int[] findFreeSlots(int dirCluster, int totalEntries) {
		int consecutive = 0;
		int firstSector = -1, firstOffset = -1;
		int cluster = dirCluster;

		while (cluster >= 2 && cluster < 0x0FFFFFF8) {
			int baseSector = clusterToSector(cluster);
			for (int s = 0; s < sectorsPerCluster; s++) {
				int sector = baseSector + s;
				if (!dirSector.load(dev, sector)) return null;
				for (int off = 0; off < 512; off += 32) {
					int first = dirSector.getByte(off);
					if (first == 0x00 || first == 0xE5) {
						if (consecutive == 0) {
							firstSector = sector;
							firstOffset = off;
						}
						consecutive++;
						if (consecutive >= totalEntries) {
							return new int[] { firstSector, firstOffset };
						}
					} else {
						consecutive = 0;
					}
				}
			}
			int next = readFatEntry(cluster);
			if (next >= 0x0FFFFFF8) {
				// Extend directory
				int newCluster = extendChain(cluster);
				if (newCluster == -1) return null;
				// Zero out new cluster
				Sector zeroSec = new Sector();
				for (int i = 0; i < 128; i++) zeroSec.buf[i] = 0;
				int newBase = clusterToSector(newCluster);
				for (int s = 0; s < sectorsPerCluster; s++) {
					if (!dev.writeBlock(newBase + s, zeroSec.buf)) return null;
				}
				cluster = newCluster;
				continue;
			}
			cluster = next;
		}
		return null;
	}

	/** Write LFN entries starting at the given sector/offset. */
	private void writeLfnEntries(int startSector, int startOffset, String name,
			int lfnEntries, int checksum) {
		int sector = startSector;
		int offset = startOffset;

		for (int seq = lfnEntries; seq >= 1; seq--) {
			if (!dirSector.load(dev, sector)) return;

			int seqByte = seq;
			if (seq == lfnEntries) seqByte |= 0x40; // Last LFN entry flag

			dirSector.setByte(offset, seqByte);
			dirSector.setByte(offset + 11, DirEntry.ATTR_LFN);
			dirSector.setByte(offset + 12, 0); // Type
			dirSector.setByte(offset + 13, checksum);
			dirSector.setLE16(offset + 26, 0); // Cluster = 0

			// Fill 13 characters for this LFN entry
			int charBase = (seq - 1) * 13;
			// Bytes 1-10: chars 0-4
			for (int i = 0; i < 5; i++) {
				int ci = charBase + i;
				int ch = ci < name.length() ? name.charAt(ci) : (ci == name.length() ? 0x0000 : 0xFFFF);
				dirSector.setLE16(offset + 1 + i * 2, ch);
			}
			// Bytes 14-25: chars 5-10
			for (int i = 0; i < 6; i++) {
				int ci = charBase + 5 + i;
				int ch = ci < name.length() ? name.charAt(ci) : (ci == name.length() ? 0x0000 : 0xFFFF);
				dirSector.setLE16(offset + 14 + i * 2, ch);
			}
			// Bytes 28-31: chars 11-12
			for (int i = 0; i < 2; i++) {
				int ci = charBase + 11 + i;
				int ch = ci < name.length() ? name.charAt(ci) : (ci == name.length() ? 0x0000 : 0xFFFF);
				dirSector.setLE16(offset + 28 + i * 2, ch);
			}

			dirSector.flush(dev);

			offset += 32;
			if (offset >= 512) {
				offset = 0;
				sector++;
			}
		}
	}

	/**
	 * Delete a file by marking its directory entries as deleted (0xE5)
	 * and freeing its cluster chain.
	 */
	public boolean deleteFile(DirEntry entry) {
		// Mark LFN entries as deleted
		if (entry.lfnEntryCount > 0) {
			// LFN entries are before the 8.3 entry
			int totalBytes = entry.lfnEntryCount * 32;
			int sector = entry.dirSectorNum;
			int offset = entry.dirEntryOffset - totalBytes;
			// Handle cross-sector LFN entries
			while (offset < 0) {
				sector--;
				offset += 512;
			}
			for (int i = 0; i < entry.lfnEntryCount; i++) {
				if (!dirSector.load(dev, sector)) return false;
				dirSector.setByte(offset, 0xE5);
				dirSector.flush(dev);
				offset += 32;
				if (offset >= 512) {
					offset = 0;
					sector++;
				}
			}
		}

		// Mark 8.3 entry as deleted
		if (!dirSector.load(dev, entry.dirSectorNum)) return false;
		dirSector.setByte(entry.dirEntryOffset, 0xE5);
		if (!dirSector.flush(dev)) return false;

		// Free cluster chain
		int startCluster = entry.getStartCluster();
		if (startCluster >= 2) {
			freeClusterChain(startCluster);
		}
		return true;
	}

	/**
	 * Update the 8.3 directory entry on disk (file size, cluster, etc).
	 */
	public boolean updateDirEntry(DirEntry entry) {
		if (!dirSector.load(dev, entry.dirSectorNum)) return false;
		int off = entry.dirEntryOffset;
		dirSector.setLE16(off + 20, entry.clusterHi);
		dirSector.setLE16(off + 26, entry.clusterLo);
		dirSector.setLE32(off + 28, entry.fileSize);
		return dirSector.flush(dev);
	}

	// ---- Stream creation ----

	public Fat32InputStream openFile(DirEntry entry) throws IOException {
		if (entry == null) throw new IOException("null entry");
		return new Fat32InputStream(this, dev, entry);
	}

	public Fat32OutputStream openFileForWrite(DirEntry entry) throws IOException {
		if (entry == null) throw new IOException("null entry");
		return new Fat32OutputStream(this, dev, entry);
	}

	// ---- Flush ----

	/** Flush FAT cache and FSInfo to disk. */
	public boolean flush() {
		boolean ok = flushFatSector();
		if (fsInfo != null) {
			ok = fsInfo.flush() && ok;
		}
		return ok;
	}
}
