#!/usr/bin/env python3
"""UART monitor for JOP FPGA boards.

Usage: ./monitor.py [PORT [BAUDRATE]]
  Default: /dev/ttyUSB0 at 1000000 baud
  Press Ctrl+C to exit.
"""

import sys
import serial

def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 1000000

    # exclusive=True -- item 143: a non-exclusive open lets a leaked reader
    # share the stream, and the resulting partial reads look like dead
    # hardware rather than a busy port.
    ser = serial.Serial(port, baud, timeout=0.1, exclusive=True)
    ser.dtr = True   # required for RP2040 DirtyJTAG CDC bridge
    print(f"Listening on {ser.port} at {ser.baudrate} baud (Ctrl+C to exit)...")

    try:
        while True:
            data = ser.read(256)
            if data:
                sys.stdout.buffer.write(data)
                sys.stdout.flush()
    except KeyboardInterrupt:
        print("\nDone.")
    finally:
        ser.close()

if __name__ == "__main__":
    main()
