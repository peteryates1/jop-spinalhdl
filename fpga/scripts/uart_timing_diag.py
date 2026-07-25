#!/usr/bin/env python3
"""
JOP serial-boot timing diagnostic.

After the 0xAA/0x55 handshake, sends a 1-word payload and times exactly
when the 4-byte checksum arrives back.

Timing tells us what the FPGA is doing:
  < 5 ms  → FPGA never left rdy_send (drain exited while FPGA still sending 0xAA)
  50-500 µs → FPGA entered download mode, received the word, sent checksum
  5 s (timeout) → FPGA in download mode but received nothing / waiting for more bytes

Also peeks at FPGA UART TX bytes *before* the drain to see if FPGA stops sending 0xAA.
"""
import serial, struct, time, sys

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
BAUD = int(sys.argv[2]) if len(sys.argv) > 2 else 2000000

ser = serial.Serial(PORT, BAUD, timeout=5)
ser.dtr = True
time.sleep(0.1)
ser.reset_input_buffer()
ser.reset_output_buffer()

print(f"Port: {PORT}  Baud: {BAUD}")
print("Waiting for FPGA 0xAA (up to 30s)...")

# ── Wait for 0xAA ────────────────────────────────────────────────────────────
deadline = time.monotonic() + 30
got_ready = False
while time.monotonic() < deadline:
    b = ser.read(1)
    if b and b[0] == 0xAA:
        t_ready = time.monotonic()
        print(f"  Got 0xAA at t=0")
        got_ready = True
        break
    sys.stdout.write(".")
    sys.stdout.flush()

if not got_ready:
    print("\nFPGA not sending 0xAA — not programmed?")
    sys.exit(1)

# ── Send 0x55 ACK ────────────────────────────────────────────────────────────
ser.write(bytes([0x55]))
ser.flush()
t_ack = time.monotonic()
print(f"  Sent 0x55 ACK at +{(t_ack-t_ready)*1000:.1f} ms")

# ── Peek at FPGA TX for 50 ms to see if 0xAA stops ──────────────────────────
# We read bytes with a short timeout and report what we see.
ser.timeout = 0.005  # 5 ms per read
t_peek_start = time.monotonic()
peek_bytes = []
peek_end = t_peek_start + 0.050  # peek window = 50 ms
while time.monotonic() < peek_end:
    b = ser.read(64)
    if b:
        peek_bytes.extend(b)

all_aa = all(x == 0xAA for x in peek_bytes)
any_non_aa = any(x != 0xAA for x in peek_bytes)
t_peek_done = time.monotonic()
print(f"  50ms peek: {len(peek_bytes)} bytes from FPGA TX "
      f"({'all 0xAA' if all_aa else 'mixed: ' + bytes(peek_bytes[:8]).hex()})")
if peek_bytes:
    print(f"  → FPGA {'still in rdy_send' if all_aa else 'sending something else'} "
          f"50ms after 0x55 ACK")
else:
    print("  → FPGA TX silent: FPGA entered download mode (received 0x55)")

# ── Drain and clear ──────────────────────────────────────────────────────────
ser.reset_input_buffer()
ser.timeout = 0.020  # 20 ms drain timeout
drain_bytes = 0
while True:
    b = ser.read(64)
    if not b:
        break
    drain_bytes += len(b)

t_drain_done = time.monotonic()
print(f"  Drain complete at +{(t_drain_done-t_ready)*1000:.1f} ms ({drain_bytes} residual bytes cleared)")

# ── Send 1-word payload ───────────────────────────────────────────────────────
# word[0] = N = 1  →  FPGA reads 1 word, XOR = 0x00000001, checksum = 0x00000001
payload = struct.pack(">I", 1)
ser.timeout = 5
t_send = time.monotonic()
ser.write(payload)
ser.flush()
print(f"\n  Sent payload {payload.hex()} at +{(t_send-t_ready)*1000:.1f} ms")

# ── Read checksum ─────────────────────────────────────────────────────────────
# Read byte-by-byte with timestamps
checksum_bytes = []
t_first_byte = None
while len(checksum_bytes) < 4:
    b = ser.read(1)
    if b:
        if t_first_byte is None:
            t_first_byte = time.monotonic()
        checksum_bytes.append(b[0])
    else:
        break  # 5 s timeout

t_cs = time.monotonic()

if len(checksum_bytes) < 4:
    print(f"\n  TIMEOUT: got only {len(checksum_bytes)} bytes in 5s")
    ser.close()
    sys.exit(1)

cs = struct.unpack(">I", bytes(checksum_bytes))[0]
dt_from_send = (t_first_byte - t_send) * 1000
print(f"  Checksum {bytes(checksum_bytes).hex()} (0x{cs:08x}) arrived")
print(f"  Time from payload send to first checksum byte: {dt_from_send:.2f} ms")

# ── Interpret ─────────────────────────────────────────────────────────────────
print()
if cs == 0x00000001:
    print("PASS: Checksum correct — FPGA received the payload correctly!")
elif cs == 0xAAAAAAAA:
    if dt_from_send < 5:
        print("FAIL: Checksum arrived < 5 ms after payload send.")
        print("  → FPGA was STILL IN rdy_send (0x55 not received, drain exited early).")
        print("  → Root cause: FPGA never received our 0x55 ACK.")
        print("  → Check: RP2040 GPIO0→B5 path, or FPGA A5→B5 loopback overwhelms GPIO0.")
    else:
        print("FAIL: Checksum 0xAAAAAAAA arrived after a delay.")
        print(f"  → dt={dt_from_send:.2f} ms suggests FPGA DID enter download mode,")
        print("     but received 0xAA bytes instead of the payload.")
        print("  → Root cause: B5 is receiving 0xAA, not GPIO0 payload data.")
        print("     Likely: A5 (FPGA TX output) shorted to B5 (FPGA RX input).")
else:
    print(f"PARTIAL: checksum=0x{cs:08x} (not 0x00000001 and not 0xAAAAAAAA)")
    print("  → Baud rate mismatch or partial data corruption.")
    print(f"  dt={dt_from_send:.2f} ms")

ser.close()
