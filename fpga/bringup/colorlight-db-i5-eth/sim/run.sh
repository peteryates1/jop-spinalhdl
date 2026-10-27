#!/bin/bash
# Verilator testbench for the i5 EthRgmiiTest (behavioural ODDRX1F/EHXPLLL in the tb):
# buttons held low at power-up, TXC phase in each mode, the MDIO writes,
# loopback capture, recovery from a forced illegal state. One PASS/FAIL line
# per check. RTL=<file> runs another copy of the design, PLL=<file> another
# Pll90.v (a red run).
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
rtl=${RTL:-$here/EthRgmiiTest.v}
pll=${PLL:-$here/Pll90.v}
mkdir -p "$here/build"
verilator --binary --timing -Wno-fatal -Wno-lint -Wno-style --Mdir "$here/build/sim" \
    --top-module tb "$here/sim/tb_rgmii.sv" "$rtl" "$pll" > "$here/build/sim_verilate.log" 2>&1 \
    || { tail -20 "$here/build/sim_verilate.log"; exit 1; }
cd "$here"     # $readmemh("frame.hex")
build/sim/Vtb > build/sim.log
python3 sim/check_rgmii.py build/sim.log
