#!/usr/bin/env python3
"""Turn DoAppPerf UART transcripts into the comparison table used in item 50.

The counters are printed as flat `name value` pairs per benchmark. Parsing them
here rather than by eye means every board's numbers are reduced the same way,
and the archived captures under docs/measurements/perfcnt/ stay analysable
after the boards are unplugged.

Usage:
  perfcnt_report.py <capture.txt> [<capture.txt> ...]
  perfcnt_report.py --compare <a.txt> <b.txt>     # per-iteration A/B

`--compare` normalises by CYCLES PER BENCHMARK ITERATION, not by wall time or
by raw totals. DoApp calibrates each benchmark to one simulated second, so two
systems at different clocks run different iteration counts and their absolute
counter values are not comparable; cycles-per-iteration is.

The MHz argument must be the one the DESIGN was told, not one you measured.
DoApp reports iterations per SIMULATED second, derived from the declared
clkFreq, so rate/declared_MHz cancels the declaration exactly -- the per-MHz
figure stays right even if the real clock differs from the declared one. Feed
it a measured clock and you reintroduce the error you were cancelling.
"""

import re
import sys

# Categories as printed by DoAppPerf.name(), in counter order.
INDIRECTION = ("bounds check", "handle deref", "element")
ORDER = ("bytecode fill", "idle/direct", "statics", "indirection", "A$ fill", "GC copy", "other")


def parse(path):
    """-> {bench: {"cycles":, "stall":, cat: value}}, in file order."""
    txt = open(path, encoding="utf-8", errors="replace").read()
    # The transcript is one long soft-wrapped stream; work on a token basis.
    # Benchmarks are delimited by "== <name>"; counters are "<name> <int>".
    out, cur = {}, None
    for chunk in re.split(r"==\s+", txt)[1:]:
        name = chunk.split()[0]
        vals = {}
        # Longest names first so "bounds check" wins over a bare "check".
        for cat in sorted(["cycles", "stall", *INDIRECTION, "bytecode fill",
                           "idle/direct", "statics", "A$ fill", "GC copy", "other"],
                          key=len, reverse=True):
            m = re.search(re.escape(cat) + r"\s+(-?\d+)", chunk)
            if m:
                vals[cat] = int(m.group(1))
        if "cycles" in vals and "stall" in vals:
            vals["indirection"] = sum(vals.get(k, 0) for k in INDIRECTION)
            out[name] = vals
    # Throughput is printed OUTSIDE the "==" blocks, as "<bench> <n> 1/s".
    for name, rate in re.findall(r"(\w+)\s+(\d+)\s+1/s", txt):
        if name in out:
            out[name]["rate"] = int(rate)
    return out


def integrity(v):
    """Category sum vs the stall total. Should be ~+200, i.e. ~0.0002 %."""
    s = sum(v.get(k, 0) for k in ("bytecode fill", "idle/direct", "statics",
                                  "A$ fill", "GC copy", "other", *INDIRECTION))
    return s - v["stall"]


def show(path):
    d = parse(path)
    print(f"\n### {path}")
    print(f"{'bench':7} {'cycles':>13} {'stall%':>7} " +
          " ".join(f"{c:>13}" for c in ORDER if c != "other") + "   sum-stall")
    for b, v in d.items():
        pct = " ".join(f"{v.get(c,0)/v['stall']*100:12.1f}%" for c in ORDER if c != "other")
        drift = integrity(v)
        flag = "" if abs(drift) < v["stall"] * 0.001 else "   <-- BIASED"
        print(f"{b:7} {v['cycles']:13,} {v['stall']/v['cycles']*100:6.1f}% {pct}   {drift:+d}{flag}")
    return d


def compare(pa, ma, pb, mb):
    """Per-ITERATION A/B: the only like-for-like unit across two clocks.

    cycles/iteration = clkMHz*1e6 / rate. Category cost per iteration is then
    that, times the category's share of total cycles. Both sides did the same
    benchmark work, so these are directly subtractable."""
    a, b = parse(pa), parse(pb)
    print(f"\n### per-iteration A/B")
    print(f"    A = {pa} @ {ma} MHz")
    print(f"    B = {pb} @ {mb} MHz")
    print("    Negative = B spends fewer cycles per iteration than A.\n")
    print(f"  {'bench':6} {'category':14} {'A cyc/it':>10} {'B cyc/it':>10} {'B vs A':>9}")
    for bench in a:
        if bench not in b or "rate" not in a[bench] or "rate" not in b[bench]:
            continue
        sa, sb = a[bench], b[bench]
        ia = ma * 1e6 / sa["rate"]          # cycles per iteration, side A
        ib = mb * 1e6 / sb["rate"]
        ka, kb = ia / sa["cycles"], ib / sb["cycles"]
        for c in ("bytecode fill", "idle/direct", "statics", "indirection", "stall"):
            va, vb = sa.get(c, 0) * ka, sb.get(c, 0) * kb
            if va == 0 and vb == 0:
                continue
            print(f"  {bench:6} {c:14} {va:10.0f} {vb:10.0f} {(vb/va-1)*100:+8.1f}%")
        print(f"  {bench:6} {'TOTAL cyc/it':14} {ia:10.0f} {ib:10.0f} {(ib/ia-1)*100:+8.1f}%")
        print()


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    if args[0] == "--compare":
        if len(args) != 5:
            sys.exit("usage: --compare <a.txt> <a_mhz> <b.txt> <b_mhz>")
        compare(args[1], float(args[2]), args[3], float(args[4]))
    else:
        for p in args:
            show(p)
