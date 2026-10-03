#!/usr/bin/env python3
"""
check_clk_ip.py must REFUSE a clock IP whose frequency differs from the build's
-- the silent mismatch it exists to stop (status item 162). A check that cannot
fail is worse than none, so the refusal is what is tested first.

Run by `make check-build`.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check_clk_ip.py")


def xci(mhz):
    return {"ip_inst": {"parameters": {"component_parameters": {
        "CLKOUT1_REQUESTED_OUT_FREQ": [{"value": f"{mhz}"}]}}}}


def run(summary_mhz, ip_mhz, summary_line=None):
    with tempfile.TemporaryDirectory() as d:
        s = os.path.join(d, "x.summary.txt")
        x = os.path.join(d, "sdr_clk.xci")
        with open(s, "w") as f:
            f.write("  Cores:       1\n")
            f.write(summary_line if summary_line is not None else f"  Clock:       {summary_mhz} MHz\n")
        with open(x, "w") as f:
            json.dump(xci(ip_mhz), f)
        return subprocess.run([sys.executable, CHECK, s, x], capture_output=True, text=True).returncode


CASES = [
    ("a mismatched IP is refused (80 MHz build, 100 MHz IP)", run(80, "100.000"), 1),
    ("a mismatched IP is refused (100 MHz build, 75 MHz IP)", run(100, "75.0"), 1),
    ("a matching IP is accepted (80 / 80.0)", run(80, "80.0"), 0),
    ("a matching IP is accepted (100 / 100.000)", run(100, "100.000"), 0),
    ("a summary with no Clock line is an error, not a pass", run(0, "80.0", summary_line="  Cores: 1\n"), 2),
]

failed = 0
for name, got, want in CASES:
    ok = got == want
    failed += not ok
    print(f"  {'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f" (exit {got}, want {want})"))
print(f"  {len(CASES) - failed}/{len(CASES)} check_clk_ip tests passed")
sys.exit(1 if failed else 0)
