# DB_FPGA Daughter Board -- SD Card

SD card support for the QMTECH DB_FPGA daughter board. Two mutually
exclusive controllers are available:

| Controller | Mode | Data Bus | Config Flag | Java Class |
|---|---|---|---|---|
| **SdNative** | Native SD | 4-bit parallel (DAT0-3) | `hasSdNative` | `SdNative` |
| **SdSpi** | SPI | 1-bit serial (MISO/MOSI) | `hasSdSpi` | `SdSpi` |

Both share the same physical SD card slot on the DB_FPGA board. SD Native
offers higher throughput (4-bit data bus, hardware CRC). SD SPI is simpler
(byte-at-a-time transfers, no hardware CRC on data).

See the [Programmer's Guide](../programmers-guide.md) for the Java API
(register maps, status bits, code examples).

---

## Pin Mapping

SD card slot on the DB_FPGA daughter board (active-low card detect):

| SD Signal | FPGA Pin | SPI Signal | Notes |
|-----------|----------|------------|-------|
| SD_CLK | PIN_B21 | SCLK | Clock output |
| SD_CMD | PIN_A22 | MOSI | Host → card data (bidirectional in native mode) |
| SD_DAT0 | PIN_A23 | MISO | Card → host data (bidirectional in native mode) |
| SD_DAT1 | PIN_B23 | — | Native mode only (TriState, weak pull-up) |
| SD_DAT2 | PIN_B19 | — | Native mode only (TriState, weak pull-up) |
| SD_DAT3 | PIN_C19 | CS | Chip select in SPI mode (active low) |
| SD_CD | PIN_B22 | SD_CD | Card detect (active low) |

Internal weak pull-ups are enabled in the QSF for CMD and all DAT lines.
These are required because the SD card tri-states these lines when idle.

---

## SD Native Mode (SdNative)

### Architecture

SdNative implements the SD card physical layer in hardware:

- **CMD line**: 48-bit command shift register with CRC7 generation/checking,
  response timeout (64 SD clock edges), short (48-bit) and long (136-bit R2)
  response reception
- **DATA lines**: 4-bit parallel data shift register with per-line CRC16
  generation/checking, 512-byte block FIFO (128 x 32-bit words), configurable
  block length
- **Clock**: Programmable divider, `SD_CLK = sys_clk / (2 * (div + 1))`.
  Default divider=99 gives ~400 kHz at 80 MHz (SD initialization speed)

Software drives the SD protocol (CMD0, CMD8, ACMD41, etc.) by writing
command arguments and indices to the register interface.

### Clock Speed Constraints

On the QMTECH EP4CGX150 + DB_FPGA board:

| Divider | SD Clock | CMD17 (Read) | CMD24 (Write) |
|---------|----------|:------------:|:-------------:|
| 1 | 20 MHz | PASS | FAIL (cmdTimeout) |
| 2 | 13.3 MHz | PASS | FAIL (cmdTimeout) |
| 3 | 10 MHz | PASS | PASS |
| 99 | 400 kHz | PASS | PASS |

CMD24 (WRITE_BLOCK) consistently times out at 20 MHz and 13.3 MHz while
CMD17 (READ_SINGLE_BLOCK) succeeds at all speeds tested. The root cause
is signal integrity on the SD card traces between the FPGA and the
DB_FPGA card slot -- the write command requires the card to respond with
a CRC status on DAT0, which may have marginal setup/hold timing at higher
clock rates.

**Recommendation**: Use divider=3 (10 MHz) or slower for data transfers.
Use the default divider=99 (~400 kHz) during card initialization (required
by the SD specification).

### Bugs Found and Fixed

Two bugs were found in SdNative during hardware verification with the
SD Native Exerciser (`SdNativeExerciserTop`). Both passed in simulation
because the testbench does not model real card timing.

#### 1. WAIT_CRC_STATUS Missing Start Bit Wait

**Bug**: After the host sends a data block, the SD card responds with a
3-bit CRC status on DAT0 (start bit `0`, then 2 status bits: `01` =
accepted, `10` = CRC error). The `WAIT_CRC_STATUS` state sampled these
3 bits immediately on entry, without waiting for the card's start bit
(DAT0 going low).

The card needs Ncrc SD clock cycles (typically 2+) after the host's end
bit before it drives the CRC status. At 10 MHz, the first sample captured
the pull-up resistor value (1), yielding status `101` which decodes as a
CRC error. The actual status `010` (data accepted) was never seen.

The write itself succeeded -- the card accepted the data -- but the
`dataCrcError` flag was falsely set.

**Fix**: Wait for DAT0 low (start bit) before counting CRC status bits.
Added a timeout using `dataTimeoutCnt` (reaches 0xFFFFF -> `dataTimeout`,
transition to DONE) to prevent hanging if the card never responds (e.g.,
when the preceding CMD24 timed out at high clock speeds).

This is analogous to the existing `WAIT_RSP` state for CMD responses,
which correctly waits for CMD line low before sampling response bits.

#### 2. dataTimeoutCnt Not Reset on startWrite

**Bug**: The `dataTimeoutCnt` counter was reset to 0 on `startRead` but
not on `startWrite`. After fixing bug #1 to use this counter for the CRC
status start-bit timeout, a write following a read could start with a
non-zero timeout value.

**Fix**: Added `dataTimeoutCnt := 0` to the `startWrite` handler.

#### 3. WAIT_BUSY Incomplete Busy Wait

**Bug**: After CRC status reception, the card holds DAT0 low during internal
write programming. The `WAIT_BUSY` state only checked for DAT0 high, but
didn't first confirm DAT0 was low. If the state machine entered `WAIT_BUSY`
before the card pulled DAT0 low (due to propagation delay), it would
immediately see DAT0 high and proceed — potentially issuing the next command
while the card was still programming.

**Fix**: Two-phase wait: first wait for DAT0 low (card is busy), then wait
for DAT0 high (card done). Added a timeout for the first phase using the
existing `dataTimeoutCnt` counter.

Found during FAT32 filesystem testing (sequential write-then-read patterns).

#### 4. Write Start Bit Sent Inside NWR (2026-11-01, status item 65)

**Bug**: `startWrite` drove the data start bit on the next SD clock falling
edge, so a host that wrote it the moment `cmdBusy` cleared put the start bit
one clock after the CMD24 response's end bit. The SD spec's NWR asks for at
least two. The Java driver never hit it -- software is slow; the exerciser's
state machine always did, and every write came back with a CRC error status.

**Fix**: `SEND_START` lets two falling edges pass, lines released, before the
start bit. `SdNativeTest`'s `SdNative_write_waits_nwr` holds it (red before:
the start bit was sampled 2 rising edges after the end bit; NWR needs >= 3).

The same day the exerciser's own checks were found to pass a silent card: T2
looked only at the data CRC flag, and T3 compared the FIFO memory, which T2
had just filled with the expected pattern and a read that receives nothing
leaves alone. T2 now also fails on a CMD24 or data timeout, T3 on a CMD17 or
data timeout or a short FIFO, and each prints the status register (or the
first wrong word) after FAIL. T3 calls `startRead` before CMD17, as the Java
driver does.

### Hardware Verification

The SD Native Exerciser (`SdNativeExerciserTop`) runs continuously on the
FPGA, performing this test sequence in a loop:

| Test | Description | Result |
|------|-------------|--------|
| T1: DETECT | Card presence check | PASS |
| INIT | CMD0/CMD8/ACMD41/CMD2/CMD3/CMD7 sequence | PASS |
| T2: WRITE | Write 512 bytes to block 1000, verify CRC status | PASS |
| T3: READ | Read block 1000 back, compare all 128 words | PASS |

Output via UART at 1 Mbaud:
```
SD NATIVE TEST
T1:DETECT PASS
INIT      PASS
T2:WRITE  PASS
T3:READ   PASS
LOOP 00000001
```

#### Build and Run

```bash
cd fpga/qmtech-ep4cgx150-sdram
make full-sd-native-exerciser
# Or step by step:
make sd-native             # generate and build
make program-sd-native
make monitor
```

---

## SPI Mode (SdSpi)

### Architecture

SdSpi is a raw SPI master. Hardware handles byte-level SPI shifting
and chip-select control. Software handles the full SD protocol.

- **SPI Mode 0**: CPOL=0, CPHA=0. SCLK idles low. Data sampled on rising
  edge, shifted out on falling edge. MSB first.
- **Byte-at-a-time**: Write a TX byte to register +1, poll busy (register +0
  bit 0), read RX byte from register +1.
- **CS control**: Write 1 to register +0 to assert CS (drive low), write 0
  to deassert.
- **Clock**: Programmable divider, `SPI_CLK = sys_clk / (2 * (div + 1))`.
  Default divider=199 gives ~200 kHz at 80 MHz (safe for SD init <= 400 kHz).
- **No hardware CRC**: SPI mode CRC is optional (disabled by default after
  CMD0). The exerciser sends dummy CRC bytes (0xFF).

### Bugs Found and Fixed (2026-11-01, status item 65)

From August to November the SPI exerciser failed INIT on every loop. Four
causes, found in this order on the EP4CGX150 + DB V4 with a cold card:

1. **MOSI moved with SCLK.** SdSpi shifted its register on the system clock
   edge that raised SCLK, so MOSI changed at the instant the card samples it:
   zero hold time. It now samples MISO on the rising edge and shifts on the
   falling one. `SdSpiTest`'s `SdSpi_mosi_holds_across_sclk_rise` (red: MOSI
   moved with SCLK at 15 of 16 edges).
2. **No idle byte before a command.** The exerciser sent each command straight
   after the last response byte. On a cold card CMD0 answered, CMD8 came back
   `0x05` (illegal), later responses arrived 2-3 bits off the byte boundary,
   and the card then answered nothing until power-off. One `0xFF` byte before
   every command -- as FatFs and Linux clock -- and every response is
   byte-aligned.
3. **ACMD41 gave up too soon.** 1000 rounds is ~0.7 s at 200 kHz; this card
   took 118 to over 1000 rounds to leave idle, and the spec allows 1 s. Now
   2000 (~1.4 s).
4. **The read-token wait was ~5 ms.** The spec allows an SDHC read 100 ms; the
   first read after power-up failed. Now 2^19 polls (~240 ms).

A failed INIT prints its first failing step, the last response byte and the
ACMD41 count, then every non-`0xFF` byte the card returned, tagged with its
command (`ccrr`), and skips T2/T3. A card left in SPI mode stays there until
power-off (a native exerciser then fails INIT).

### SPI Pin Mapping

In SPI mode, the SD card pins are used as follows:

| SD Pin | SPI Function | Direction |
|--------|-------------|-----------|
| SD_CLK | SCLK | Output |
| SD_CMD | MOSI | Output |
| SD_DAT0 | MISO | Input |
| SD_DAT3 | CS (active low) | Output |
| SD_CD | Card detect | Input |

DAT1 and DAT2 are unused in SPI mode.

### Hardware Verification

The SD SPI Exerciser (`SdSpiExerciserTop`) runs continuously on the FPGA,
performing this test sequence in a loop:

| Test | Description | Result |
|------|-------------|--------|
| T1: DETECT | Card presence check | PASS |
| INIT | 80 clocks, CMD0, CMD8, CMD55+ACMD41, set fast clock | PASS |
| T2: WRITE | CMD24, data token (0xFE), 512 bytes, data response check | PASS |
| T3: READ | CMD17, wait for data token (0xFE), 512 bytes, compare | PASS |

Verified 2026-11-01: 3028 loops, every test PASS. T3 prints how many polls
its data token took (about 0x320, ~0.35 ms, on this card).

The SPI exerciser uses divider=1 (~20 MHz) for data transfers after
initialization. Unlike native mode, SPI write succeeds at 20 MHz because
the data response is received on MISO (a dedicated input), not on a
bidirectional data line.

Output via UART at 1 Mbaud:
```
SD SPI TEST
T1:DETECT PASS
INIT      PASS
T2:WRITE  PASS
T3:READ   PASS 00000314
LOOP 00000001
```

#### Build and Run

```bash
cd fpga/qmtech-ep4cgx150-sdram
make full-sd-spi-exerciser
# Or step by step:
make sd-spi             # generate and build
make program-sd-spi
make monitor
```

---

## Choosing Between Native and SPI Mode

| | SD Native | SD SPI |
|---|---|---|
| Data bus | 4-bit parallel | 1-bit serial |
| Hardware CRC | Yes (CRC7 + CRC16) | No |
| Max tested clock | 10 MHz | 20 MHz |
| Throughput | ~4 MB/s (10 MHz x 4 bits) | ~2.5 MB/s (20 MHz x 1 bit) |
| Protocol complexity | Hardware handles CMD/DATA framing | Software handles everything |
| Preset device | `DeviceType.SdNative` | `DeviceType.SdSpi` |

SD Native is the one the standard DB_FPGA build has (`ep4cgx150DbFull`). No JOP
preset includes SD SPI today; the SPI controller is exercised by the stand-alone
SD SPI exerciser (`make sd-spi` in `fpga/qmtech-ep4cgx150-sdram`), and both
SD exercisers currently fail on hardware (status
[item 65](../current-status.md#item-65)).

---

See [FAT32 Filesystem](fat32-filesystem.md) for the filesystem layer built on
these SD controllers.

---

## Sources

| File | Description |
|------|-------------|
| `spinalhdl/src/main/scala/jop/io/SdNative.scala` | SD Native controller |
| `spinalhdl/src/main/scala/jop/io/SdSpi.scala` | SD SPI controller |
| `spinalhdl/src/main/scala/jop/system/SdNativeExerciserTop.scala` | SD Native exerciser |
| `spinalhdl/src/main/scala/jop/system/SdSpiExerciserTop.scala` | SD SPI exerciser |
| `java/runtime/src/jop/com/jopdesign/hw/SdNative.java` | SD Native Java API |
| `java/runtime/src/jop/com/jopdesign/hw/SdSpi.java` | SD SPI Java API |
| `java/fat32/src/com/jopdesign/fat32/SdNativeBlockDevice.java` | SD Native block device (FAT32 layer) |
| `java/fat32/src/com/jopdesign/fat32/Fat32FileSystem.java` | FAT32 filesystem |
| `java/fat32/src/test/Fat32Test.java` | FAT32 hardware test |
| `build/<config>/quartus/` | SD Native Quartus project, generated by `SdNativeExerciserBuild` |
| `build/<config>/quartus/` | SD SPI Quartus project, generated by `SdSpiExerciserBuild` |

SD Physical Layer Simplified Specification: [sdcard.org](https://www.sdcard.org/downloads/pls/)
