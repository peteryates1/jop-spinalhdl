#!/usr/bin/env python3
"""Minimal SDRAM write diagnostic — sends 1-2 words to test if SDRAM writes complete."""

import sys
import struct
import time
import serial

READY_BYTE = 0xAA
READY_ACK = 0x55

def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 2000000
    num_words = int(sys.argv[3]) if len(sys.argv) > 3 else 2

    ser = serial.Serial(port, baud, timeout=0.1)
    ser.reset_input_buffer()
    print(f"Opened {port} at {baud} baud")

    # Wait for 0xAA ready
    print("Waiting for ready (0xAA)...", end="", flush=True)
    t0 = time.monotonic()
    while time.monotonic() - t0 < 10:
        b = ser.read(1)
        if b and b[0] == READY_BYTE:
            print(f" got it after {time.monotonic()-t0:.1f}s")
            break
    else:
        print(" TIMEOUT")
        ser.close()
        return

    # Send ACK
    ser.write(bytes([READY_ACK]))
    ser.flush()
    time.sleep(0.05)
    ser.reset_input_buffer()
    print(f"Sent ACK (0x55)")

    # Build minimal data: [num_words, 0xDEADBEEF, 0xCAFEBABE, ...]
    words = [num_words]
    for i in range(1, num_words):
        words.append(0xDEAD0000 + i)

    checksum = 0
    data = bytearray()
    for w in words:
        data += struct.pack(">I", w)
        checksum ^= w
    checksum &= 0xFFFFFFFF

    print(f"Sending {num_words} words ({len(data)} bytes)...")
    t_start = time.monotonic()

    # Send data byte by byte with timing info
    for i, byte in enumerate(data):
        ser.write(bytes([byte]))
        if (i + 1) % 4 == 0:
            word_idx = i // 4
            elapsed = time.monotonic() - t_start
            print(f"  Word {word_idx}: 0x{words[word_idx]:08X} sent at {elapsed*1000:.1f}ms")
    ser.flush()

    elapsed = time.monotonic() - t_start
    print(f"All data sent in {elapsed*1000:.1f}ms")
    print(f"Expected checksum: 0x{checksum:08X}")

    # Wait for checksum response
    print("Waiting for 4-byte checksum...", end="", flush=True)
    ser.timeout = 5
    resp = ser.read(4)
    elapsed = time.monotonic() - t_start

    if len(resp) == 4:
        fpga_cksum = struct.unpack(">I", resp)[0]
        print(f" got 0x{fpga_cksum:08X} after {elapsed*1000:.0f}ms")
        if fpga_cksum == checksum:
            print("CHECKSUM MATCH!")
        else:
            print(f"CHECKSUM MISMATCH (expected 0x{checksum:08X})")
    elif len(resp) > 0:
        print(f" got {len(resp)} bytes: {resp.hex()}")
    else:
        print(f" TIMEOUT (0 bytes after 5s)")

    # Read any additional data
    ser.timeout = 1
    extra = ser.read(64)
    if extra:
        print(f"Extra data: {extra.hex()}")
        try:
            print(f"  As text: {extra.decode('ascii', errors='replace')}")
        except:
            pass

    ser.close()


if __name__ == "__main__":
    main()
