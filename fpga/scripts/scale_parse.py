#!/usr/bin/env python3
"""Parse jbe.Scale output from a UART capture.

Derives the aggregate from the PER-CORE MICROSECOND COUNTS rather than trusting
the printed AGGREGATE line: the CH340 link at ~2 Mbaud drops a character every
few hundred, and it has already mangled "AGGREGATE" into "RAGGEGATE" and
"all cores agree" into "all corer agee". The per-core numbers are internally
redundant (us -> rate -> sum), so a corrupted digit shows up as an inconsistency
rather than passing silently.
"""
import re, sys

ACCESSES = 16384 * 24  # WORDS x ITERATIONS, from Scale.java

for path in sys.argv[1:]:
    txt = open(path, errors="replace").read()
    us = [int(m) for m in re.findall(r"core \d+:\s+(\d+) us", txt)]
    check = re.search(r"CHECK (\d+)", txt)
    cores = re.search(r"Scale: cores (\d+)", txt)
    if not us:
        print(f"{path}: no per-core timings found")
        continue
    rates = [ACCESSES / (u / 1e6) for u in us]
    agg = sum(rates) / 1000.0
    spread = (max(us) - min(us)) / min(us) * 100
    print(f"{path.split('/')[-1]:28s} cores={cores.group(1) if cores else '?':>2s} "
          f"aggregate={agg:7.1f} kacc/s  per-core spread={spread:4.1f}%  "
          f"CHECK={check.group(1) if check else 'MISSING'}")
