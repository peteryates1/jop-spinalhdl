#!/usr/bin/env python3
"""
UART-based flash programmer via FlashProgrammerTop/FlashProgrammerDdr3Top.

Supports:
  - W25Q128 (Winbond, 128Mbit/16MB) on Cyclone IV (default)
  - SST26VF032B (Microchip, 32Mbit/4MB) on Artix-7 (--sst26 flag)

All flash command sequencing is done here; the FPGA only provides
CS assert/deassert and single-byte SPI transfers.

Protocol:
  0xBB       -> CS low,  echo 0xBB
  0xCC       -> CS high, echo 0xCC
  0xDD <b>   -> SPI transfer byte b (escape for 0xBB/CC/DD), echo MISO
  other byte -> SPI transfer, echo MISO

Usage:
  flash_program.py <flash_image.bin> [--port /dev/ttyUSB0] [--baud 1000000] [-v]
  flash_program.py <flash_image.bin> --sst26 [--port /dev/ttyUSB1] [-v]
"""

import argparse
import sys
import time

import serial

# Protocol command bytes
CS_LOW  = 0xBB
CS_HIGH = 0xCC
ESCAPE  = 0xDD
SPECIAL = {CS_LOW, CS_HIGH, ESCAPE}

# SPI flash commands (common to W25Q128 and SST26VF032B)
CMD_WRITE_ENABLE  = 0x06
CMD_READ_STATUS1  = 0x05
CMD_READ_DATA     = 0x03
CMD_PAGE_PROGRAM  = 0x02
CMD_CHIP_ERASE    = 0xC7
CMD_JEDEC_ID      = 0x9F

# SST26VF032B-specific commands
CMD_ULBPR         = 0x98  # Global Block Protection Unlock
CMD_RSTQIO        = 0xFF  # Reset QPI/QIO mode to SPI
CMD_RSTEN         = 0x66  # Reset Enable
CMD_RST           = 0x99  # Reset Device


class FlashProgrammer:
    def __init__(self, port, baud):
        self.ser = serial.Serial(port, baud, timeout=2)
        self.ser.reset_input_buffer()
        self.ser.reset_output_buffer()

    def close(self):
        self.ser.close()

    # ------------------------------------------------------------------
    # Low-level protocol helpers
    # ------------------------------------------------------------------

    def spi_cs_low(self):
        """Assert CS (active low)."""
        self.ser.write(bytes([CS_LOW]))
        echo = self.ser.read(1)
        if len(echo) != 1 or echo[0] != CS_LOW:
            raise IOError(f"CS_LOW echo mismatch: got {echo!r}")

    def spi_cs_high(self):
        """Deassert CS."""
        self.ser.write(bytes([CS_HIGH]))
        echo = self.ser.read(1)
        if len(echo) != 1 or echo[0] != CS_HIGH:
            raise IOError(f"CS_HIGH echo mismatch: got {echo!r}")

    def spi_byte(self, val):
        """Transfer one SPI byte, return received byte."""
        val &= 0xFF
        if val in SPECIAL:
            self.ser.write(bytes([ESCAPE, val]))
        else:
            self.ser.write(bytes([val]))
        echo = self.ser.read(1)
        if len(echo) != 1:
            raise IOError("SPI byte: no echo received")
        return echo[0]

    def spi_bytes(self, data):
        """Bulk SPI transfer. Returns list of received bytes."""
        # Build the entire TX buffer, then read all echoes at once
        tx_buf = bytearray()
        for b in data:
            b &= 0xFF
            if b in SPECIAL:
                tx_buf.append(ESCAPE)
                tx_buf.append(b)
            else:
                tx_buf.append(b)
        self.ser.write(tx_buf)
        rx = self.ser.read(len(data))
        if len(rx) != len(data):
            raise IOError(f"spi_bytes: expected {len(data)} echoes, got {len(rx)}")
        return list(rx)

    # ------------------------------------------------------------------
    # Flash command helpers
    # ------------------------------------------------------------------

    def reset_flash(self):
        """Reset flash from QPI/QIO mode back to standard SPI.

        If the flash was left in QPI mode (e.g., by factory firmware),
        standard SPI commands are interpreted as QPI nibbles and fail.
        Sending 0xFF with CS asserted acts as RSTQIO (FFh) in QPI mode
        (where D3-D0 are all 1s with our pull-ups). In SPI mode, 0xFF
        is a NOP.  Follow with Reset Enable + Reset Device for good measure.
        """
        # RSTQIO: 0xFF in QPI mode = reset to SPI
        self.spi_cs_low()
        self.spi_byte(0xFF)
        self.spi_cs_high()
        time.sleep(0.001)
        # Also try Reset Enable (0x66) + Reset Device (0x99) in SPI mode
        self.spi_cs_low()
        self.spi_byte(CMD_RSTEN)
        self.spi_cs_high()
        time.sleep(0.001)
        self.spi_cs_low()
        self.spi_byte(CMD_RST)
        self.spi_cs_high()
        time.sleep(0.1)  # tRST recovery time (typ 30us, use 100ms for safety)

    def jedec_id(self):
        """Read JEDEC ID (manufacturer, device_hi, device_lo)."""
        self.spi_cs_low()
        self.spi_byte(CMD_JEDEC_ID)
        mfr = self.spi_byte(0x00)
        dev_hi = self.spi_byte(0x00)
        dev_lo = self.spi_byte(0x00)
        self.spi_cs_high()
        return (mfr, dev_hi, dev_lo)

    def read_status(self):
        """Read Status Register 1."""
        self.spi_cs_low()
        self.spi_byte(CMD_READ_STATUS1)
        sr1 = self.spi_byte(0x00)
        self.spi_cs_high()
        return sr1

    def wait_ready(self, timeout=200):
        """Poll SR1 bit 0 (BUSY) until clear."""
        t0 = time.time()
        while True:
            sr1 = self.read_status()
            if not (sr1 & 0x01):
                return
            if time.time() - t0 > timeout:
                raise TimeoutError(f"Flash still busy after {timeout}s (SR1=0x{sr1:02x})")
            time.sleep(0.01)

    def write_enable(self):
        """Send Write Enable (WREN) command."""
        self.spi_cs_low()
        self.spi_byte(CMD_WRITE_ENABLE)
        self.spi_cs_high()

    def ulbpr(self):
        """Global Block Protection Unlock (SST26VF032B).
        Must be preceded by WREN.  Clears all block protection bits."""
        self.write_enable()
        self.spi_cs_low()
        self.spi_byte(CMD_ULBPR)
        self.spi_cs_high()

    def chip_erase(self):
        """Chip Erase (blocking, waits for completion)."""
        self.write_enable()
        self.spi_cs_low()
        self.spi_byte(CMD_CHIP_ERASE)
        self.spi_cs_high()
        self.wait_ready(timeout=200)

    def sector_erase_4k(self, addr):
        """4KB Sector Erase at given address."""
        self.write_enable()
        self.spi_cs_low()
        self.spi_byte(0x20)
        self.spi_byte((addr >> 16) & 0xFF)
        self.spi_byte((addr >> 8) & 0xFF)
        self.spi_byte(addr & 0xFF)
        self.spi_cs_high()
        self.wait_ready(timeout=10)

    def page_program(self, addr, data):
        """Page Program (up to 256 bytes)."""
        assert len(data) <= 256
        self.write_enable()
        self.spi_cs_low()
        self.spi_byte(CMD_PAGE_PROGRAM)
        self.spi_byte((addr >> 16) & 0xFF)
        self.spi_byte((addr >> 8) & 0xFF)
        self.spi_byte(addr & 0xFF)
        self.spi_bytes(data)
        self.spi_cs_high()
        self.wait_ready(timeout=5)

    def read_data(self, addr, length):
        """Read data from flash."""
        self.spi_cs_low()
        self.spi_byte(CMD_READ_DATA)
        self.spi_byte((addr >> 16) & 0xFF)
        self.spi_byte((addr >> 8) & 0xFF)
        self.spi_byte(addr & 0xFF)
        data = self.spi_bytes([0x00] * length)
        self.spi_cs_high()
        return bytes(data)

    # ------------------------------------------------------------------
    # High-level operations
    # ------------------------------------------------------------------

    def program_file(self, filepath, verify=False, sst26=False):
        """Full flow: identify, erase, program, optionally verify."""

        # Flash parameters
        if sst26:
            expected_jedec = "bf2642"
            expected_name = "SST26VF032B"
            flash_size = 4 * 1024 * 1024   # 4 MB
        else:
            expected_jedec = "ef4018"
            expected_name = "W25Q128"
            flash_size = 16 * 1024 * 1024  # 16 MB

        # 1. Drain any banner / stale data
        time.sleep(0.5)
        stale = self.ser.read(self.ser.in_waiting or 0)
        if stale:
            print(f"Drained {len(stale)} stale bytes: {stale!r}")

        # 1b. Reset flash from possible QPI mode
        print("Resetting flash (RSTQIO + soft reset)...")
        self.reset_flash()

        # 2. JEDEC ID check
        mfr, dh, dl = self.jedec_id()
        jedec = f"{mfr:02x}{dh:02x}{dl:02x}"
        print(f"JEDEC ID: {jedec}")
        if jedec != expected_jedec:
            print(f"ERROR: Expected {expected_name} ({expected_jedec}), got {jedec}")
            return False

        # 3. SST26: unlock block protection (required before any erase/write)
        if sst26:
            print("Unlocking block protection (ULBPR)...")
            self.ulbpr()

        # 4. Read file
        with open(filepath, "rb") as f:
            data = f.read()
        file_size = len(data)
        print(f"File: {filepath} ({file_size} bytes, {file_size / 1024 / 1024:.2f} MB)")

        if file_size > flash_size:
            print(f"ERROR: File exceeds {flash_size // (1024 * 1024)} MB flash capacity")
            return False

        # 5. Chip erase
        print("Erasing flash (chip erase)...", end="", flush=True)
        t0 = time.time()
        self.write_enable()
        self.spi_cs_low()
        self.spi_byte(CMD_CHIP_ERASE)
        self.spi_cs_high()
        # Poll with progress
        while True:
            sr1 = self.read_status()
            if not (sr1 & 0x01):
                break
            elapsed = time.time() - t0
            print(f"\rErasing flash (chip erase)... {elapsed:.0f}s", end="", flush=True)
            time.sleep(1.0)
        erase_time = time.time() - t0
        print(f"\rErase complete in {erase_time:.1f}s" + " " * 20)

        # 6. Page program
        total_pages = (file_size + 255) // 256
        print(f"Programming {total_pages} pages...")
        t0 = time.time()
        for i in range(total_pages):
            addr = i * 256
            chunk = data[addr:addr + 256]
            # Skip pages that are all 0xFF (already erased)
            if all(b == 0xFF for b in chunk):
                continue
            self.page_program(addr, chunk)
            # Progress
            pct = (i + 1) * 100 // total_pages
            elapsed = time.time() - t0
            if i > 0:
                rate = (i + 1) / elapsed
                eta = (total_pages - i - 1) / rate
                print(f"\r  [{pct:3d}%] page {i + 1}/{total_pages}  "
                      f"{elapsed:.0f}s elapsed, ~{eta:.0f}s remaining   ",
                      end="", flush=True)
            else:
                print(f"\r  [{pct:3d}%] page {i + 1}/{total_pages}",
                      end="", flush=True)
        prog_time = time.time() - t0
        print(f"\rProgram complete in {prog_time:.1f}s" + " " * 40)

        # 7. Verify (optional)
        if verify:
            print("Verifying...", end="", flush=True)
            t0 = time.time()
            errors = 0
            # Read back in 256-byte chunks
            for i in range(total_pages):
                addr = i * 256
                expected = data[addr:addr + 256]
                actual = self.read_data(addr, len(expected))
                if actual != expected:
                    # Find first mismatch
                    for j in range(len(expected)):
                        if j < len(actual) and actual[j] != expected[j]:
                            print(f"\n  MISMATCH at 0x{addr + j:06x}: "
                                  f"expected 0x{expected[j]:02x}, got 0x{actual[j]:02x}")
                            errors += 1
                            if errors >= 10:
                                print("  (stopping after 10 errors)")
                                break
                    if errors >= 10:
                        break
                pct = (i + 1) * 100 // total_pages
                print(f"\rVerifying... [{pct:3d}%]", end="", flush=True)
            verify_time = time.time() - t0
            if errors == 0:
                print(f"\rVerify PASSED in {verify_time:.1f}s" + " " * 20)
            else:
                print(f"\rVerify FAILED: {errors} errors in {verify_time:.1f}s")
                return False

        # 8. Summary
        total = erase_time + prog_time
        print(f"\nDone! Total: {total:.1f}s (erase {erase_time:.1f}s + program {prog_time:.1f}s)")
        return True


def main():
    parser = argparse.ArgumentParser(description="UART-based SPI flash programmer")
    parser.add_argument("image", help="Flash image file (.bin)")
    parser.add_argument("--port", default="/dev/ttyUSB0", help="Serial port (default: /dev/ttyUSB0)")
    parser.add_argument("--baud", type=int, default=1000000, help="Baud rate (default: 1000000)")
    parser.add_argument("-v", "--verify", action="store_true", help="Verify after programming")
    parser.add_argument("--sst26", action="store_true",
                        help="SST26VF032B flash (Artix-7): ULBPR unlock, 4MB size, JEDEC bf2642")
    args = parser.parse_args()

    prog = FlashProgrammer(args.port, args.baud)
    try:
        ok = prog.program_file(args.image, verify=args.verify, sst26=args.sst26)
    finally:
        prog.close()

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
