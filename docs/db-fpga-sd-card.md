# DB_FPGA Daughter Board -- SD Card (Native 4-bit Mode)

SD card support for the QMTECH DB_FPGA daughter board, using the
**BmbSdNative** controller in native 4-bit SD mode. Verified on hardware
with a 32 GB SanDisk SDHC card at 10 MHz SD clock.

## Architecture

BmbSdNative implements the SD card physical layer in hardware:

- **CMD line**: 48-bit command shift register with CRC7 generation/checking,
  response timeout (64 SD clock edges), short (48-bit) and long (136-bit R2)
  response reception
- **DATA lines**: 4-bit parallel data shift register with per-line CRC16
  generation/checking, 512-byte block FIFO (128 x 32-bit words), configurable
  block length
- **Clock**: Programmable divider, `SD_CLK = sys_clk / (2 * (div + 1))`.
  Default divider=99 gives ~400 kHz at 80 MHz (SD initialization speed)

Software drives the SD protocol (CMD0, CMD8, ACMD41, etc.) by writing
command arguments and indices to the register interface. See the
[Programmer's Guide](programmers-guide.md) for the Java API.

## Pin Mapping

SD card slot on the DB_FPGA daughter board (active-low card detect):

| Signal | FPGA Pin | DB_FPGA | Notes |
|--------|----------|---------|-------|
| `sd_clk` | PIN_B21 | SD_CLK | Clock output |
| `sd_cmd` | PIN_A22 | SD_CMD | Bidirectional (TriState), weak pull-up |
| `sd_dat_0` | PIN_A23 | SD_DAT0 | Bidirectional (TriState), weak pull-up |
| `sd_dat_1` | PIN_B23 | SD_DAT1 | Bidirectional (TriState), weak pull-up |
| `sd_dat_2` | PIN_B19 | SD_DAT2 | Bidirectional (TriState), weak pull-up |
| `sd_dat_3` | PIN_C19 | SD_DAT3 | Bidirectional (TriState), weak pull-up |
| `sd_cd` | PIN_B22 | SD_CD | Card detect (active low) |

Internal weak pull-ups are enabled in the QSF for CMD and all DAT lines.
These are required because the SD card tri-states these lines when idle.

## Clock Speed Constraints

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

## Bugs Found and Fixed

Two bugs were found in BmbSdNative during hardware verification with the
SD Native Exerciser (`SdNativeExerciserTop`). Both passed in simulation
because the testbench does not model real card timing.

### 1. WAIT_CRC_STATUS Missing Start Bit Wait

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

### 2. dataTimeoutCnt Not Reset on startWrite

**Bug**: The `dataTimeoutCnt` counter was reset to 0 on `startRead` but
not on `startWrite`. After fixing bug #1 to use this counter for the CRC
status start-bit timeout, a write following a read could start with a
non-zero timeout value.

**Fix**: Added `dataTimeoutCnt := 0` to the `startWrite` handler.

## Hardware Verification

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

### Build and Run

```bash
cd /home/peter/jop-spinalhdl
sbt "runMain jop.system.SdNativeExerciserTopVerilog"
cd fpga/qmtech-ep4cgx150-sdram
make build-sd-native-exerciser
make program-sd-native-exerciser
sudo timeout 45 python3 ../scripts/monitor.py /dev/ttyUSB0 1000000
```

## Sources

- BmbSdNative controller: `spinalhdl/src/main/scala/jop/io/BmbSdNative.scala`
- SD Native exerciser: `spinalhdl/src/main/scala/jop/system/SdNativeExerciserTop.scala`
- SdNative Java API: `java/runtime/src/jop/com/jopdesign/hw/SdNative.java`
- Quartus project: `fpga/qmtech-ep4cgx150-sdram/sd_native_exerciser.qsf`
- SD Physical Layer Simplified Specification: [sdcard.org](https://www.sdcard.org/downloads/pls/)
