#!/usr/bin/env python3
"""Build the stage-2 test frame: a UDP broadcast, preamble+SFD+frame+FCS, one
byte per line as hex for $readmemh. Run, with the MAC of the unit it is for:
    python3 make_frame.py "$(../../scripts/jop_setup.py unit <unit> macs | head -1)" > frame.hex
The MAC is the unit's (setup/units.toml), not this design's: until 2026-11-01
it was a constant, ...05, picked before the units had MACs -- and then issued
to the Wukong, so the bring-up image and a Wukong JOP shared one."""
import struct, sys, zlib

if len(sys.argv) != 2:
    sys.exit(__doc__)
SRC_MAC = bytes.fromhex(sys.argv[1].replace(":", ""))
assert len(SRC_MAC) == 6 and SRC_MAC[0] & 0x03 == 0x02, "a locally administered unicast MAC"
DST_MAC = b"\xff" * 6
SRC_IP, DST_IP = bytes([192, 168, 0, 251]), bytes([192, 168, 0, 255])
SPORT = DPORT = 5005
payload = b"JOP colorlight-db i5 RGMII stage 2 hello!\n"   # 42 bytes: FRAME_LEN = 96

def csum(b):
    if len(b) % 2: b += b"\0"
    s = sum(struct.unpack("!%dH" % (len(b) // 2), b))
    while s >> 16: s = (s & 0xFFFF) + (s >> 16)
    return ~s & 0xFFFF

udp = struct.pack("!HHHH", SPORT, DPORT, 8 + len(payload), 0) + payload  # UDP csum 0 = none
ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(udp), 0, 0x4000, 64, 17, 0, SRC_IP, DST_IP)
ip = ip[:10] + struct.pack("!H", csum(ip)) + ip[12:]
frame = DST_MAC + SRC_MAC + b"\x08\x00" + ip + udp
frame += b"\0" * max(0, 60 - len(frame))                                 # minimum size
frame += struct.pack("<I", zlib.crc32(frame))                            # FCS, LSB first
wire = b"\x55" * 7 + b"\xd5" + frame
sys.stderr.write(f"{len(wire)} bytes on the wire ({len(frame)} frame incl FCS)\n")
print("\n".join(f"{b:02x}" for b in wire))
