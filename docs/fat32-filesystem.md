# FAT32 Filesystem

Read-write FAT32 filesystem with long filename (LFN) support, built for JOP.
Runs entirely in Java on top of the SD card block device layer. No `java.util`
dependencies — uses `int[128]` sector buffers and manual byte packing.

Memory footprint: ~2.7 KB RAM (three `Sector` buffers at 512 bytes each + FAT
and directory state).

---

## Module Structure

All source files are in `java/fat32/src/com/jopdesign/fat32/`:

| File | Description |
|------|-------------|
| `BlockDevice.java` | Interface for 512-byte sector I/O (`init`, `readBlock`, `writeBlock`) |
| `Sector.java` | `int[128]` buffer with byte/LE16/LE32 accessors, dirty tracking, load/flush |
| `FsInfo.java` | FSInfo sector: free cluster count and next-free hint |
| `DirEntry.java` | Directory entry with 8.3 short name, optional LFN, attribute flags |
| `Fat32FileSystem.java` | Core filesystem: mount, FAT read/write, directory ops, file create/delete |
| `Fat32InputStream.java` | `InputStream` for reading files (follows FAT chain) |
| `Fat32OutputStream.java` | `OutputStream` for writing files (extends FAT chain, updates dir entry on close) |
| `SdNativeBlockDevice.java` | `BlockDevice` for SD Native mode (4-bit bus via `SdNative` hardware object) |
| `SdSpiBlockDevice.java` | `BlockDevice` for SD SPI mode (1-bit bus via `SdSpi` hardware object) |

---

## Quick Start

```java
import com.jopdesign.fat32.*;
import java.io.IOException;

// 1. Initialize SD card
SdNativeBlockDevice sd = new SdNativeBlockDevice();
if (!sd.init()) { /* handle error */ }

// 2. Mount FAT32 partition 0
Fat32FileSystem fs = new Fat32FileSystem(sd);
if (!fs.mount(0)) { /* handle error */ }

// 3. List root directory
DirEntry[] entries = fs.listDir(fs.getRootCluster());
for (int i = 0; i < entries.length; i++) {
    DirEntry e = entries[i];
    if (e == null) continue;
    JVMHelp.wr(e.getName());
    JVMHelp.wr(e.isDirectory() ? "/\n" : "\n");
}

// 4. Create and write a file (LFN supported)
DirEntry file = fs.createFile(fs.getRootCluster(), "Hello from JOP.txt");
Fat32OutputStream out = fs.openFileForWrite(file);
String msg = "Hello, World!\r\n";
for (int i = 0; i < msg.length(); i++) {
    out.write(msg.charAt(i));
}
out.close();  // mandatory: flushes final sector, updates dir entry and FAT

// 5. Read it back
DirEntry found = fs.findFile(fs.getRootCluster(), "Hello from JOP.txt");
Fat32InputStream in = fs.openFile(found);
int ch;
while ((ch = in.read()) != -1) {
    JVMHelp.wr((char) ch);
}
in.close();
```

---

## API Reference

### Fat32FileSystem

| Method | Description |
|--------|-------------|
| `Fat32FileSystem(BlockDevice dev)` | Constructor. Does not mount. |
| `boolean mount(int partIndex)` | Mount partition 0–3 from MBR. Returns true on success. |
| `boolean isMounted()` | Check if filesystem is mounted. |
| `int getSectorsPerCluster()` | Sectors per cluster (from BPB). |
| `int getRootCluster()` | Root directory cluster number. |
| `int getTotalClusters()` | Total data clusters on partition. |
| `int getFreeCount()` | Free cluster count from FSInfo (-1 if unknown). |
| `int clusterToSector(int cluster)` | Convert cluster number to first sector LBA. |
| `int readFatEntry(int cluster)` | Read FAT entry for cluster. |
| `boolean writeFatEntry(int cluster, int value)` | Write FAT entry (preserves upper 4 bits). |
| `boolean flushFatSector()` | Flush dirty FAT sector to all FAT copies. |
| `int allocateCluster()` | Allocate a free cluster, mark as EOF. Returns cluster or -1. |
| `int extendChain(int lastCluster)` | Allocate and link new cluster after `lastCluster`. |
| `void freeClusterChain(int startCluster)` | Free all clusters in a chain. |
| `int getLastCluster(int startCluster)` | Follow chain to last cluster. |
| `int getClusterN(int startCluster, int n)` | Follow chain to Nth cluster (0-indexed). |
| `DirEntry[] listDir(int dirCluster)` | List directory entries (two-pass: count then fill). |
| `DirEntry findFile(int dirCluster, String name)` | Find file by name (case-insensitive, LFN and 8.3). |
| `DirEntry createFile(int dirCluster, String name)` | Create file with 8.3 or LFN entries. Returns DirEntry or null. |
| `boolean deleteFile(DirEntry entry)` | Delete file: mark entries as 0xE5, free cluster chain. |
| `boolean updateDirEntry(DirEntry entry)` | Write back modified dir entry (size, cluster). |
| `Fat32InputStream openFile(DirEntry entry)` | Open file for reading. |
| `Fat32OutputStream openFileForWrite(DirEntry entry)` | Open file for writing (overwrites from start). |
| `boolean flush()` | Flush FAT cache and FSInfo to disk. |

### Fat32InputStream

Extends `java.io.InputStream`. Reads bytes sequentially following the FAT chain.

| Method | Description |
|--------|-------------|
| `int read()` | Read one byte. Returns -1 at EOF. |
| `int available()` | Bytes remaining in file. |
| `void close()` | Mark stream as finished. |

### Fat32OutputStream

Extends `java.io.OutputStream`. Writes bytes sequentially, extending the FAT
chain as needed. **`close()` is mandatory** — it flushes the final partial
sector, updates the directory entry's file size, and flushes FAT/FSInfo.

| Method | Description |
|--------|-------------|
| `void write(int b)` | Write one byte. Flushes full sectors automatically. |
| `void close()` | Flush final sector, update dir entry size, flush FAT. **Must call.** |

### DirEntry

Represents a 32-byte FAT32 directory entry. Fields:

| Field | Type | Description |
|-------|------|-------------|
| `shortName` | `String` | 11-char 8.3 name (space-padded) |
| `longName` | `String` | Long filename (null if none) |
| `attr` | `int` | Attribute byte (ATTR_DIRECTORY, ATTR_ARCHIVE, etc.) |
| `fileSize` | `int` | File size in bytes |
| `dirSectorNum` | `int` | Sector containing this entry (for write-back) |
| `dirEntryOffset` | `int` | Byte offset within sector |
| `lfnEntryCount` | `int` | Number of LFN entries preceding the 8.3 entry |

| Method | Description |
|--------|-------------|
| `int getStartCluster()` | Combine clusterHi/clusterLo. |
| `boolean isDirectory()` | Check ATTR_DIRECTORY flag. |
| `String getName()` | Long name if available, else formatted 8.3 name. |
| `boolean nameMatches(String name)` | Case-insensitive match against long and short names. |
| `static String formatShortName(String sn)` | Format 11-char short name for display (strip spaces, insert dot). |

### Sector

512-byte buffer stored as `int[128]` in big-endian word order (matching SD
FIFO format).

| Method | Description |
|--------|-------------|
| `int getByte(int off)` | Extract byte at offset 0–511. |
| `int getLE16(int off)` | Read little-endian 16-bit value. |
| `int getLE32(int off)` | Read little-endian 32-bit value. |
| `void setByte(int off, int val)` | Set byte, marks dirty. |
| `void setLE16(int off, int val)` | Write LE16, marks dirty. |
| `void setLE32(int off, int val)` | Write LE32, marks dirty. |
| `boolean load(BlockDevice dev, int sector)` | Load sector (flushes dirty first, caches). |
| `boolean flush(BlockDevice dev)` | Write dirty sector to device. |
| `void invalidate()` | Force re-read on next load. |

---

## Architecture

### Sector Buffer Byte Order

The SD card FIFO transfers data as 32-bit words. Bytes are packed big-endian
within each word: byte 0 is at bits [31:24] of word 0, byte 1 at [23:16],
etc. All `Sector` accessors handle this transparently.

### FAT Caching

`Fat32FileSystem` uses a single `Sector` buffer for FAT access. When a FAT
entry is read or written, the containing sector is loaded (with automatic
flush of any dirty previous sector). On `flushFatSector()`, the sector is
written to both FAT copies (FAT1 and FAT2).

### Directory Listing (Two-Pass)

`listDir()` uses a two-pass approach to avoid dynamic allocation:
1. **Count pass**: Walk the directory chain counting non-deleted, non-LFN entries
2. **Fill pass**: Allocate `DirEntry[]` of exact size, walk again to populate

This avoids `ArrayList` or growing arrays (not available on JOP).

### Long Filename (LFN) Entries

LFN support follows the VFAT specification:
- Each LFN entry stores 13 UCS-2 characters across three byte ranges
- Entries are stored in reverse order (last fragment first on disk)
- A checksum of the 8.3 short name links LFN entries to their owner
- `createFile()` automatically generates LFN entries when the name doesn't
  fit 8.3 format, with `~N` collision avoidance for the short name alias

### FSInfo Sector

The FSInfo sector (typically sector 1 of the partition) tracks free cluster
count and a next-free hint for faster allocation. Updated on `flush()`.

---

## JOP-Specific Workarounds

### 1. Explicit Constructors Required

JOP does not zero object memory on `new`. All fields (especially references)
contain garbage unless explicitly initialized. Every class includes a
constructor that sets all fields to safe defaults.

```java
// DirEntry constructor — all fields initialized
public DirEntry() {
    shortName = null;
    longName = null;
    attr = 0;
    // ... etc.
}
```

### 2. char[] Instead of StringBuilder for String Building (Historical)

> **Note**: `System.arraycopy` and `StringBuilder` resize both work correctly
> now (see [Bugs and Issues](bugs-and-issues.md) — issue #1 was misdiagnosed,
> issue #2 fixed with `StringBuilder.toString()`). The two-pass `char[]`
> approach used here is no longer necessary but remains harmless.

String building uses a two-pass approach: count characters first, then
allocate a `char[]` of exact size and fill it.

```java
// stripIllegalChars: count valid chars, then fill char[]
int validCount = 0;
for (int i = 0; i < s.length(); i++) {
    if (!isIllegalShortNameChar(s.charAt(i))) validCount++;
}
char[] result = new char[validCount];
// ... fill result
return new String(result);
```

### 3. String Concatenation with int (Historical)

> **Note**: `"text" + intValue` now works correctly on JOP. The original crash
> was caused by a missing `StringBuilder.toString()` method (see
> [Bugs and Issues](bugs-and-issues.md) issue #2). The `wrInt()`/`wrHex()`
> helpers used in the FAT32 code are no longer necessary but remain harmless.

```java
// Both work on JOP now:
JVMHelp.wr("count=" + n);    // StringBuilder path — fixed
JVMHelp.wr("count=");        // Manual path — still valid
wrInt(n);
```

---

## Build Instructions

### Hardware Test

Build and run `Fat32Test.java` on the FPGA:

```bash
cd java/apps/Small

# Build with FAT32 module
make clean && make all APP_NAME=Fat32Test EXTRA_SRC=../../fat32/src

# Program FPGA (if not already running)
cd ../../fpga/qmtech-ep4cgx150-sdram
make program-dbfpga

# Download and run
make download SERIAL_PORT=/dev/ttyUSB0 JOP_FILE=../../java/apps/Small/dist/bin/Fat32Test.jop

# Monitor UART output
make monitor SERIAL_PORT=/dev/ttyUSB0
```

Expected output:
```
FAT32 Test

Init SD card...
SD init OK

Mounting FAT32...
Mounted: 8 sec/clust, 7633 clusters, free=7618

Root directory:
  Hello from JOP.txt (44 bytes)
  TEST.TXT (18 bytes)

Creating Hello from JOP.txt...
  (deleting old copy)
  Written 44 bytes
  Readback PASS

Creating TEST.TXT...
  (deleting old copy)
  Written 18 bytes
  Readback PASS

=== ALL TESTS PASS ===
```

### Simulation Test

Run `Fat32SimTest` on the host JVM with a FAT32 disk image:

```bash
cd java/fat32/test

# Create a 64 MB MBR-partitioned FAT32 test image (one-time setup)
dd if=/dev/zero of=test.img bs=1M count=64
parted test.img mklabel msdos
parted test.img mkpart primary fat32 1MiB 100%
LOOP=$(sudo losetup --find --show -P test.img)
sudo mkfs.fat -F 32 ${LOOP}p1
sudo losetup -d $LOOP

# Compile and run (30 tests)
javac -d classes -sourcepath ../src:. Fat32SimTest.java FileBlockDevice.java
java -cp classes com.jopdesign.fat32.Fat32SimTest test.img
```

The simulation test exercises: mount, empty directory listing, 8.3 file
create/write/read/verify, LFN file create/write/read/verify, directory listing
with both files, and delete with verification. 30 checks total.

---

## Sources

| File | Description |
|------|-------------|
| `java/fat32/src/com/jopdesign/fat32/BlockDevice.java` | Block device interface |
| `java/fat32/src/com/jopdesign/fat32/Sector.java` | Sector buffer with byte accessors |
| `java/fat32/src/com/jopdesign/fat32/FsInfo.java` | FSInfo sector management |
| `java/fat32/src/com/jopdesign/fat32/DirEntry.java` | Directory entry with LFN |
| `java/fat32/src/com/jopdesign/fat32/Fat32FileSystem.java` | Core filesystem |
| `java/fat32/src/com/jopdesign/fat32/Fat32InputStream.java` | File read stream |
| `java/fat32/src/com/jopdesign/fat32/Fat32OutputStream.java` | File write stream |
| `java/fat32/src/com/jopdesign/fat32/SdNativeBlockDevice.java` | SD Native block device |
| `java/fat32/src/com/jopdesign/fat32/SdSpiBlockDevice.java` | SD SPI block device |
| `java/fat32/test/Fat32SimTest.java` | Simulation test (30 checks, host JVM) |
| `java/fat32/test/FileBlockDevice.java` | File-backed block device for simulation |
| `java/fat32/src/test/Fat32Test.java` | Hardware test (JOP FPGA) |
| `spinalhdl/src/main/scala/jop/io/BmbSdNative.scala` | SD Native controller (RTL) |
| `spinalhdl/src/main/scala/jop/io/BmbSdSpi.scala` | SD SPI controller (RTL) |

See also:
- [DB_FPGA SD Card](db-fpga-sd-card.md) — SD controller hardware, pin mapping, bugs found
- [Programmer's Guide](programmers-guide.md) — SD Native/SPI register maps and Java API
