#!/usr/bin/env python3
"""
check_clk_ip.py must REFUSE a clock IP whose frequency differs from the build's
-- the silent mismatch it exists to stop (status item 162) -- and one whose
system and SDRAM outputs differ from EACH OTHER although both were asked for
the same frequency (status item 164). A check that cannot fail is worse than
none, so the refusals are what is tested first.

Run by `make check-build`.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check_clk_ip.py")


def xci(mhz, mmcm=None):
    """A clk_wiz .xci as check_clk_ip reads it. `mmcm` = (input MHz, CLKFBOUT
    mult, DIVCLK divide, CLKOUT0 divide, CLKOUT1 divide); by default a
    consistent integer solution at the requested frequency (VCO = 10 x f,
    both outputs /10), i.e. a well-formed IP."""
    fin, mult, divclk, d0, d1 = mmcm or (50.0, float(mhz) / 5.0, 1, 10.0, 10)
    v = lambda x: [{"value": f"{x}"}]
    return {"ip_inst": {"parameters": {"component_parameters": {
        "CLKOUT1_REQUESTED_OUT_FREQ": v(mhz),
        "PRIM_IN_FREQ": v(fin),
        "MMCM_CLKFBOUT_MULT_F": v(mult),
        "MMCM_DIVCLK_DIVIDE": v(divclk),
        "MMCM_CLKOUT0_DIVIDE_F": v(d0),
        "MMCM_CLKOUT1_DIVIDE": v(d1)}}}}


def run(summary_mhz, ip_mhz, summary_line=None, mmcm=None):
    with tempfile.TemporaryDirectory() as d:
        s = os.path.join(d, "x.summary.txt")
        x = os.path.join(d, "sdr_clk.xci")
        with open(s, "w") as f:
            f.write("  Cores:       1\n")
            f.write(summary_line if summary_line is not None else f"  Clock:       {summary_mhz} MHz\n")
        with open(x, "w") as f:
            json.dump(xci(ip_mhz, mmcm), f)
        return subprocess.run([sys.executable, CHECK, s, x], capture_output=True, text=True).returncode


CASES = [
    ("a mismatched IP is refused (80 MHz build, 100 MHz IP)", run(80, "100.000"), 1),
    ("a mismatched IP is refused (100 MHz build, 75 MHz IP)", run(100, "75.0"), 1),
    ("a matching IP is accepted (80 / 80.0)", run(80, "80.0"), 0),
    ("a matching IP is accepted (100 / 100.000)", run(100, "100.000"), 0),
    ("a summary with no Clock line is an error, not a pass", run(0, "80.0", summary_line="  Cores: 1\n"), 2),
    # Status item 164. Both ASK for 80 MHz, which is all the check used to read.
    # The Wukong's 2026-09-22 -sdr80 IP, as generated: 50 x 27.375 = 1368.75 MHz,
    # system /17.125 = 79.93, SDRAM /17 = 80.51. Met timing; never printed.
    ("a split IP is refused (Wukong -sdr80: 79.93 / 80.51 MHz)",
     run(80, "80.0", mmcm=(50.0, 27.375, 1, 17.125, 17)), 1),
    # The i9+'s PLL attempt: both outputs agree, but at 25 x 45 / 14 = 80.357.
    ("an off-frequency IP is refused (i9+ PLL: 80.357 MHz on both)",
     run(80, "80.0", mmcm=(25.0, 45.0, 1, 14.0, 14)), 1),
    # The fix: 50 x 16 = 800 MHz, /10 on both outputs.
    ("the fixed Wukong -sdr80 IP is accepted (800 MHz / 10)",
     run(80, "80.0", mmcm=(50.0, 16.0, 1, 10.0, 10)), 0),
]

failed = 0
for name, got, want in CASES:
    ok = got == want
    failed += not ok
    print(f"  {'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f" (exit {got}, want {want})"))
print(f"  {len(CASES) - failed}/{len(CASES)} check_clk_ip tests passed")
sys.exit(1 if failed else 0)
