#!/bin/bash
# Write the idle image into the i9+ module on the colorlight-db base board and
# prove it: unprotect, write with --verify, read back independently, reload from
# flash, check DONE/CRC. See README.md for why each step is there.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
B=$("$here/../../scripts/jtag_probe_map" --busdev colorlight-db)
OFL=(sudo openFPGALoader -c dirtyJtag --busdev-num "$B" --fpga-part xc7a50tfgg484)
tmp=$(mktemp -d)
bit="$here/build/I9PlusIdle.bit"

"${OFL[@]}" --unprotect-flash > "$tmp/unprotect.log" 2>&1
"${OFL[@]}" --detect -f -v 2>&1 | tr '\r' '\n' | grep -a 'RDSR' | grep -q '0x00' \
    || { echo "FAIL: flash still protected"; exit 1; }
"${OFL[@]}" --verify -f "$bit" > "$tmp/write.log" 2>&1 \
    || { echo "FAIL: write/verify (log $tmp/write.log)"; exit 1; }
"${OFL[@]}" --dump-flash --file-size 262144 "$tmp/readback.bin" > "$tmp/dump.log" 2>&1
sudo chown "$(id -u)" "$tmp/readback.bin"
python3 - "$bit" "$tmp/readback.bin" <<'P'
import sys
bit=open(sys.argv[1],'rb').read(); fl=open(sys.argv[2],'rb').read()
s=bytes.fromhex('aa995566'); t=bit[bit.find(s):]; f=fl.find(s)
ok = f >= 0 and fl[f:f+len(t)] == t and set(fl[f+len(t):]) <= {0xff}
print('read-back matches image:', ok); sys.exit(0 if ok else 1)
P
sudo openFPGALoader -c dirtyJtag --busdev-num "$B" -r > "$tmp/reset.log" 2>&1
sleep 2
stat=$("${OFL[@]}" --read-register STAT 2>&1 | tr '\r' '\n')
echo "$stat" | grep -aE '^(Done|CRC Error|ID Error)'
echo "$stat" | grep -aqE '^Done +0x1' && echo "$stat" | grep -aq 'No CRC error' \
    || { echo "FAIL: did not configure from flash"; exit 1; }
echo "IDLE IMAGE FLASHED AND BOOTED"
