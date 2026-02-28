package com.jopdesign.fat32;

/**
 * Represents a FAT32 directory entry (32 bytes on disk).
 * Tracks both 8.3 short name and optional long filename (LFN).
 * Stores disk location for write-back of modified entries.
 */
public class DirEntry {
	// Attribute constants
	public static final int ATTR_READ_ONLY = 0x01;
	public static final int ATTR_HIDDEN    = 0x02;
	public static final int ATTR_SYSTEM    = 0x04;
	public static final int ATTR_VOLUME_ID = 0x08;
	public static final int ATTR_DIRECTORY = 0x10;
	public static final int ATTR_ARCHIVE   = 0x20;
	public static final int ATTR_LFN       = 0x0F;

	/** 8.3 short name, 11 chars (8 name + 3 ext, space-padded). */
	public String shortName;
	/** Long filename if LFN entries present, null otherwise. */
	public String longName;
	/** File attributes byte. */
	public int attr;
	/** High 16 bits of start cluster. */
	public int clusterHi;
	/** Low 16 bits of start cluster. */
	public int clusterLo;
	/** File size in bytes. */
	public int fileSize;
	/** Sector number containing the 8.3 entry on disk. */
	public int dirSectorNum;
	/** Byte offset of the 8.3 entry within its sector. */
	public int dirEntryOffset;
	/** Number of LFN entries preceding the 8.3 entry. */
	public int lfnEntryCount;

	/** Explicit constructor — JOP may not zero object memory on allocation. */
	public DirEntry() {
		shortName = null;
		longName = null;
		attr = 0;
		clusterHi = 0;
		clusterLo = 0;
		fileSize = 0;
		dirSectorNum = 0;
		dirEntryOffset = 0;
		lfnEntryCount = 0;
	}

	public int getStartCluster() {
		return (clusterHi << 16) | clusterLo;
	}

	public void setStartCluster(int cluster) {
		clusterHi = (cluster >> 16) & 0xFFFF;
		clusterLo = cluster & 0xFFFF;
	}

	public boolean isDirectory() {
		return (attr & ATTR_DIRECTORY) != 0;
	}

	public boolean hasLongName() {
		return longName != null;
	}

	/**
	 * Return the display name: long name if available, otherwise
	 * formatted 8.3 name (trailing spaces stripped, dot inserted).
	 */
	public String getName() {
		if (longName != null) return longName;
		return formatShortName(shortName);
	}

	/**
	 * Format an 11-char 8.3 short name for display:
	 * strip trailing spaces, insert dot between name and extension.
	 * Uses char[] instead of StringBuilder to avoid JOP System.arraycopy issues.
	 */
	public static String formatShortName(String sn) {
		if (sn == null || sn.length() < 11) return sn;
		// Find end of name part (strip trailing spaces)
		int nameEnd = 8;
		while (nameEnd > 0 && sn.charAt(nameEnd - 1) == ' ') nameEnd--;
		// Find end of extension part
		int extEnd = 11;
		while (extEnd > 8 && sn.charAt(extEnd - 1) == ' ') extEnd--;

		if (extEnd == 8) {
			// No extension
			return sn.substring(0, nameEnd);
		}
		int extLen = extEnd - 8;
		char[] result = new char[nameEnd + 1 + extLen];
		for (int i = 0; i < nameEnd; i++) result[i] = sn.charAt(i);
		result[nameEnd] = '.';
		for (int i = 0; i < extLen; i++) result[nameEnd + 1 + i] = sn.charAt(8 + i);
		return new String(result);
	}

	/**
	 * Case-insensitive name match against long name or formatted short name.
	 */
	public boolean nameMatches(String name) {
		if (longName != null && longName.equalsIgnoreCase(name)) return true;
		String formatted = formatShortName(shortName);
		return formatted != null && formatted.equalsIgnoreCase(name);
	}
}
