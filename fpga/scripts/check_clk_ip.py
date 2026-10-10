#!/usr/bin/env python3
"""
The clock IP a build links must run at the frequency the build was GENERATED for.

    fpga/scripts/check_clk_ip.py <build summary.txt> <clk_wiz .xci>

Exit 0 when the IP's CLKOUT1 equals the summary's `Clock:` line, 1 with a
message otherwise, 2 when either file cannot be read.

WHY (status item 162). The frequency lives in the CONFIG (`clkFreq`), and the
generated RTL derives the UART divider and the microsecond counter from it.
The clock comes from a Xilinx clk_wiz IP, which is generated separately and
knows nothing about the config. Link one against the other at a different
frequency and nothing fails: Vivado times the design at the IP's frequency,
the board runs at the IP's frequency, and the console is detuned by the ratio
-- which reads as a dead board. The DDR3 side of this board has already done
exactly that (MIG `ui_clk` vs the preset, 9 % off). So the Wukong SDR flow
picks its IP from the build summary and runs this before Vivado.

The summary is the build's own record (`Clock:  80 MHz`), the same source
console.mk reads the baud from.
"""
import json
import re
import sys


def summary_mhz(path):
    with open(path) as f:
        for line in f:
            m = re.match(r"\s*Clock:\s*([0-9.]+)\s*MHz", line)
            if m:
                return float(m.group(1))
    raise ValueError(f"no 'Clock: N MHz' line in {path}")


def xci_mhz(path):
    with open(path) as f:
        d = json.load(f)
    p = d["ip_inst"]["parameters"]["component_parameters"]
    return float(p["CLKOUT1_REQUESTED_OUT_FREQ"][0]["value"])


def actual_outputs_mhz(path):
    """What CLKOUT0 (system) and CLKOUT1 (SDRAM) ACTUALLY produce, computed from
    the primitive's multiply/divide settings -- not the requested frequencies.

    Why (Wukong status item 164, and the Colorlight i9+ 2026-10-10): asked for
    80 MHz, clk_wiz picks a fractional VCO and a FRACTIONAL divide for CLKOUT0
    -- which on a 7-series MMCM is the only output that has one -- and an
    INTEGER divide for CLKOUT1. 25 MHz x 45.25 = 1131.25 MHz gives 80.09 MHz
    / 14.125 for the logic and 80.80 MHz / 14 for the SDRAM: two clocks that
    slip a whole cycle every ~1.4 us. Timing is MET (each is timed as an ideal
    clock), the download checksums, and the program never prints a line. The
    requested frequencies are both "80"."""
    with open(path) as f:
        d = json.load(f)
    p = d["ip_inst"]["parameters"]["component_parameters"]
    v = lambda k: float(p[k][0]["value"])
    # The MMCM_* fields, WHICHEVER primitive was chosen: clk_wiz keeps its
    # computed settings there for a PLLE2 too, and leaves the PLL_* fields at
    # defaults (x4, /1 -> "100 MHz"). Verified against the generated
    # sdr_clk_clk_wiz.v of a PRIMITIVE=PLL instance.
    fin = v("PRIM_IN_FREQ")
    vco = fin * v("MMCM_CLKFBOUT_MULT_F") / v("MMCM_DIVCLK_DIVIDE")
    return vco / v("MMCM_CLKOUT0_DIVIDE_F"), vco / v("MMCM_CLKOUT1_DIVIDE")


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    try:
        want = summary_mhz(argv[1])
        have = xci_mhz(argv[2])
    except (OSError, ValueError, KeyError, IndexError, json.JSONDecodeError) as e:
        print(f"check_clk_ip: cannot read: {e}", file=sys.stderr)
        return 2
    if abs(want - have) > 1e-6:
        print(f"check_clk_ip: {argv[2]} runs at {have:g} MHz, but the build was "
              f"generated for {want:g} MHz ({argv[1]}).\n"
              f"  Refusing: the design would be timed and run at the IP's "
              f"frequency, and the console detuned by {have / want:.3f}x.",
              file=sys.stderr)
        return 1
    try:
        sys_mhz, sdram_mhz = actual_outputs_mhz(argv[2])
    except (KeyError, IndexError, ValueError, ZeroDivisionError) as e:
        print(f"check_clk_ip: cannot compute the IP's actual outputs: {e}", file=sys.stderr)
        return 2
    if abs(sys_mhz - sdram_mhz) > 1e-6:
        print(f"check_clk_ip: {argv[2]}: the SDRAM clock runs at {sdram_mhz:.4f} MHz "
              f"but the system clock at {sys_mhz:.4f} MHz.\n"
              f"  Refusing: they must be the SAME clock, phase-shifted. Different "
              f"frequencies drift through each other and corrupt every SDRAM access "
              f"while timing still reads MET (status item 164). Regenerate the IP "
              f"with integer divides (create_sdram_clk_wiz.tcl -primitive PLL).",
              file=sys.stderr)
        return 1
    if abs(sys_mhz - want) / want > 0.002:
        print(f"check_clk_ip: {argv[2]}: asked for {want:g} MHz, the IP actually "
              f"makes {sys_mhz:.4f} MHz.", file=sys.stderr)
        return 1
    print(f"check_clk_ip: {have:g} MHz clock IP for a {want:g} MHz build -- ok "
          f"(system and SDRAM both {sys_mhz:.4f} MHz)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
