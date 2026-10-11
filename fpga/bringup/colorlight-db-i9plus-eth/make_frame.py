#!/usr/bin/env python3
"""Build the stage-2 test frame: a UDP broadcast, preamble+SFD+frame+FCS, one
byte per line as hex for $readmemh. Run: python3 make_frame.py > frame.hex"""
import struct, sys, zlib

SRC_MAC = bytes.fromhex("024a4f500001")      # locally administered
DST_MAC = b"\xff" * 6
SRC_IP, DST_IP = bytes([192, 168, 0, 250]), bytes([192, 168, 0, 255])
SPORT = DPORT = 5005
payload = b"JOP colorlight-db i9+ RGMII stage 2 hello\n"

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
