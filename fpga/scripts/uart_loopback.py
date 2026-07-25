#!/usr/bin/env python3
"""
UART loopback / path sanity check for RP2040 DirtyJTAG on DB_FPGA V5.

Run with FPGA *not* programmed (so A5 is tristate and won't fight GPIO1),
with a jumper wire connecting J3 pin 7 (RP2040 GPIO0 / TX out) to
J3 pin 8 (RP2040 GPIO1 / RX in).

Two tests:
  1. Loopback: sends a known pattern, expects echo on RX.  Confirms GPIO0
     TX and GPIO1 RX paths are intact through the board.
  2. RX-only: removes loopback assumption and reads whatever the line is
     carrying (use to check FPGA TX → GPIO1 path when FPGA is running).

Usage:
  python3 uart_loopback.py [PORT [BAUD]]
  python3 uart_loopback.py --rx-only [PORT [BAUD]]

Default: /dev/ttyACM0  2000000
"""
import serial, sys, time, random

RX_ONLY = '--rx-only' in sys.argv
args = [a for a in sys.argv[1:] if not a.startswith('--')]
PORT = args[0] if len(args) > 0 else "/dev/ttyACM0"
BAUD = int(args[1]) if len(args) > 1 else 2000000

# ── RX-only mode: just show what arrives ────────────────────────────────────
if RX_ONLY:
    print(f"RX-only mode on {PORT} at {BAUD} baud")
    print("Reading UART RX (GPIO1) for 2 seconds — should show FPGA TX output:")
    ser = serial.Serial(PORT, BAUD, timeout=0.1)
    ser.dtr = True
    time.sleep(0.05)
    ser.reset_input_buffer()
    t_end = time.monotonic() + 2.0
    rx_bytes = []
    while time.monotonic() < t_end:
        b = ser.read(64)
        if b:
            rx_bytes.extend(b)
    ser.close()
    n = len(rx_bytes)
    uniq = set(rx_bytes)
    print(f"  Received {n} bytes in 2s ({n/2:.0f} bytes/s)")
    if n == 0:
        print("  SILENT — no signal on GPIO1 / J3-pin-8")
    elif uniq == {0xAA}:
        print("  All 0xAA — FPGA TX is sending rdy_send (expected)")
    else:
        print(f"  Unique byte values: {sorted(hex(v) for v in uniq)}")
        print(f"  First 32 bytes: {bytes(rx_bytes[:32]).hex()}")
    sys.exit(0)

# ── Loopback test ─────────────────────────────────────────────────────────────
print(f"UART loopback test on {PORT} at {BAUD} baud")
print("Prerequisite: FPGA NOT programmed; jumper J3-pin7 ↔ J3-pin8")
print()

# Build a test pattern that is clearly NOT all-0xAA (so we can spot FPGA noise).
# 64-byte repeating pattern, then randomised 64 bytes, repeat 4× = 512 bytes total.
random.seed(0xdeadbeef)
pattern_base = bytes(range(0x00, 0x40))          # 64 bytes: 0x00..0x3f
pattern_rand = bytes(random.getrandbits(8) for _ in range(64))
PATTERN = (pattern_base + pattern_rand) * 4      # 512 bytes

CHUNK = 64    # send/receive in chunks so we can interleave I/O
TIMEOUT = 0.5 # seconds to wait for each chunk

ser = serial.Serial(PORT, BAUD, timeout=TIMEOUT)
ser.dtr = True   # required: RP2040 CDC bridge stops forwarding when DTR=0
time.sleep(0.05)
ser.reset_input_buffer()
ser.reset_output_buffer()

sent_total = 0
recv_buf = bytearray()
errors = 0
first_error = None

print(f"Sending {len(PATTERN)} bytes in {len(PATTERN)//CHUNK} chunks of {CHUNK}...")

for i in range(0, len(PATTERN), CHUNK):
    chunk = PATTERN[i:i+CHUNK]
    ser.write(chunk)
    ser.flush()
    sent_total += len(chunk)

    # Read back up to len(chunk) bytes within TIMEOUT
    deadline = time.monotonic() + TIMEOUT
    got = bytearray()
    while len(got) < len(chunk) and time.monotonic() < deadline:
        b = ser.read(len(chunk) - len(got))
        if b:
            got.extend(b)

    recv_buf.extend(got)

    # Check received bytes against sent
    for j, (s, r) in enumerate(zip(chunk, got)):
        if s != r:
            abs_idx = i + j
            if first_error is None:
                first_error = (abs_idx, s, r)
            errors += 1
    if len(got) < len(chunk):
        missing = len(chunk) - len(got)
        if first_error is None:
            first_error = (i + len(got), chunk[len(got)], None)
        errors += missing

ser.close()

print(f"Sent:     {sent_total} bytes")
print(f"Received: {len(recv_buf)} bytes")
print()

# Check for 0xAA contamination (FPGA TX leaking in)
aa_count = recv_buf.count(0xAA)

if errors == 0:
    print("PASS: All bytes echoed correctly — GPIO0 TX and GPIO1 RX paths intact")
else:
    print(f"FAIL: {errors} byte errors in {sent_total} bytes")
    if first_error:
        idx, s, r = first_error
        print(f"  First error at byte {idx}: sent 0x{s:02x}, got {'0x{:02x}'.format(r) if r is not None else 'MISSING'}")
    if aa_count > sent_total // 4:
        print()
        print(f"  WARNING: {aa_count}/{len(recv_buf)} received bytes are 0xAA")
        print("  → FPGA is programmed and A5 (FPGA TX) is polluting GPIO1.")
        print("    Deprogram/power-cycle the FPGA, then re-run this test.")
    elif len(recv_buf) < sent_total // 2:
        print()
        print(f"  Only {len(recv_buf)} bytes returned out of {sent_total}")
        print("  → GPIO0 TX may be disconnected (no loopback wire), or J3 pin 7 not making contact.")
    else:
        print()
        print(f"  Received {len(recv_buf)} bytes but with bit errors.")
        print("  → Possible baud rate mismatch or signal integrity issue on J3 connector.")
