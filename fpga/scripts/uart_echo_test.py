#!/usr/bin/env python3
"""UART echo test for QMTECH XC7A100T + DB_FPGA V5.

Tests:
  1. Reads startup banner ("ECHO\r\n")
  2. Sends test strings and verifies echo
  3. Sends individual bytes (0x00-0xFF) and verifies echo

Usage: ./uart_echo_test.py [PORT [BAUDRATE]]
  Default: /dev/ttyACM0 at 115200 baud
"""

import sys
import time
import serial


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 115200

    print(f"UART Echo Test — {port} @ {baud} baud")
    print("=" * 50)

    try:
        ser = serial.Serial(port, baud, timeout=2)
    except serial.SerialException as e:
        print(f"FAIL: Cannot open {port}: {e}")
        sys.exit(1)

    # Flush any stale data
    ser.reset_input_buffer()
    time.sleep(0.1)

    passed = 0
    failed = 0

    # Test 1: Check for startup banner
    # The FPGA may have already sent it — try reading, or send a reset pulse
    print("\n[Test 1] Startup banner...")
    banner = ser.read(6)
    if banner == b"ECHO\r\n":
        print(f"  PASS: Got banner: {banner!r}")
        passed += 1
    else:
        print(f"  SKIP: No banner (got {banner!r}) — FPGA may have booted earlier")
        # Drain any remaining data
        ser.reset_input_buffer()

    # Test 2: Echo single bytes
    print("\n[Test 2] Single byte echo...")
    test_bytes = [0x00, 0x41, 0x5A, 0x61, 0x7A, 0x80, 0xFE, 0xFF]
    for b in test_bytes:
        ser.reset_input_buffer()
        ser.write(bytes([b]))
        ser.flush()
        resp = ser.read(1)
        if resp == bytes([b]):
            passed += 1
        else:
            print(f"  FAIL: Sent 0x{b:02X}, got {resp!r}")
            failed += 1
    if failed == 0:
        print(f"  PASS: All {len(test_bytes)} bytes echoed correctly")

    # Test 3: Echo string
    print("\n[Test 3] String echo...")
    test_str = b"Hello, XC7A100T!\r\n"
    ser.reset_input_buffer()
    ser.write(test_str)
    ser.flush()
    time.sleep(0.1)
    resp = ser.read(len(test_str))
    if resp == test_str:
        print(f"  PASS: Got back {resp!r}")
        passed += 1
    else:
        print(f"  FAIL: Sent {test_str!r}, got {resp!r}")
        failed += 1

    # Test 4: Burst — 256 bytes
    print("\n[Test 4] 256-byte burst...")
    burst = bytes(range(256))
    ser.reset_input_buffer()
    ser.write(burst)
    ser.flush()
    time.sleep(0.5)
    resp = ser.read(256)
    if resp == burst:
        print(f"  PASS: All 256 bytes echoed correctly")
        passed += 1
    else:
        matched = sum(1 for a, b in zip(resp, burst) if a == b)
        print(f"  FAIL: {matched}/256 bytes matched, got {len(resp)} bytes back")
        failed += 1

    # Summary
    print("\n" + "=" * 50)
    total = passed + failed
    if failed == 0:
        print(f"PASS: All {passed} tests passed")
    else:
        print(f"FAIL: {passed}/{total} passed, {failed} failed")

    ser.close()
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
