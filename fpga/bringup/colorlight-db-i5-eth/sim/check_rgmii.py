# Checks for sim/tb_rgmii.sv: python3 check_rgmii.py <sim log>. Times are the tb's (50 ms "second").
import re, sys
log = open(sys.argv[1]).read().splitlines()
def t(l): return int(l.split()[0]) / 1e6          # ps -> us ... time unit is ps
ok = True
def check(name, cond, detail=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name + (" -- " + detail if detail else ""))
    ok &= bool(cond)
F = [(int(l.split()[0]) / 1e9, l.split("UART: ")[1].split()) for l in log if "UART: F" in l]
S = [(int(l.split()[0]) / 1e9, l.split("UART: ")[1].split()) for l in log if "UART: S" in l]
W = [(int(l.split()[0]) / 1e9, l) for l in log if "MDIO WRITE" in l]
R = [(int(l.split()[0]) / 1e9, float(l.split()[3])) for l in log if "TXC rise" in l]
FA = [(int(l.split()[0]) / 1e9, float(l.split()[3])) for l in log if "TXC fall" in l]
E = [(int(l.split()[0]) / 1e9, int(l.split()[3])) for l in log if "TXC0 edges" in l]
mode = lambda f: f[1][5]
check("power-up mode 0d (buttons held low at power-up are no press)", F and mode(F[0]) == "0d", F and mode(F[0]))
w0 = [x for x in W if x[0] < 60]
check("power-up writes reg9=0000, reg0=1200", [("reg 9 = 0000" in w[1], "reg 0 = 1200" in w[1]) for w in w0] == [(True, False), (False, True)], str([w[1][-30:] for w in w0]))
r90 = [r for (tm, r) in R if tm < 120]; r270 = [r for (tm, r) in R if 120 < tm < 170]
f90 = [r for (tm, r) in FA if tm < 120]; f270 = [r for (tm, r) in FA if 120 < tm < 170]
check("90: TXC rises 10 ns into the nibble", r90 and all(abs(r - 10) < 0.5 for r in r90), str(r90))
check("90: TXC falls 30 ns into the nibble", f90 and all(abs(r - 30) < 0.5 for r in f90), str(f90))
check("270: TXC rises 30 ns into the nibble", r270 and all(abs(r - 30) < 0.5 for r in r270), str(r270))
check("270: TXC falls 10 ns into the nibble", f270 and all(abs(r - 10) < 0.5 for r in f270), str(f270))
off = [n for (tm, n) in E if 180 <= tm <= 210]   # OPTION -> off at 165 ms, -> on at 210 ms
check("off: no TXC edges", off and all(n == 0 for n in off), str(off))
on2 = [n for (tm, n) in E if tm >= 240]
check("back on: 250000 TXC edges per 10 ms", on2 and all(n == 250000 for n in on2[1:]), str(on2[:4]))
w1 = [x[1] for x in W if 240 < x[0] < 260]
check("START: reg9=0000 then BMCR=6100 (loopback)", len(w1) == 2 and "reg 9 = 0000" in w1[0] and "reg 0 = 6100" in w1[1], str(w1))
m = {round(tm): f[5] for (tm, f) in F}
check("loopback shows in mode (1d) after START", any(v == "1d" for k, v in m.items() if 250 < k < 290), str(m))
check("SELECT: TX off -> mode 19", any(v == "19" for k, v in m.items() if 300 < k < 340), str(m))
txc = [(round(tm), int(s[9] + s[10], 16)) for (tm, s) in S]   # txcount bytes
after = [c for (tm, c) in txc if tm > 300]
before = [c for (tm, c) in txc if tm < 280]
check("TX on: one frame per report", before == list(range(1, len(before) + 1)), str(txc))
check("TX off: frame count frozen", after and len(set(after)) == 1, str(txc))
fl = [(round(tm), f[1:4]) for (tm, f) in F]
check("fault recorded: state 1e", any(f[2] == "1e" for (tm, f) in fl if 340 < tm < 380), str(fl))
check("RESET clears the record", all(f == ["00", "00", "00", "00"][:3] or f[:3] == ["00", "00", "00"] for (tm, f) in fl if tm > 390), str(fl))
w2 = [x[1] for x in W if x[0] > 400]
check("START again: BMCR=1200 (loopback off)", len(w2) == 2 and "reg 0 = 1200" in w2[1], str(w2))
check("loopback off in mode (09)", any(v == "09" for k, v in m.items() if k > 440), str(m))
lb = [l for l in log if "UART: 0 55 55 55 55 55 55 55 d5 ff ff ff ff ff ff 02 4a 4f 50 00 05" in l]
check("PHY0 loop captures the frame", len(lb) > 0)
print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
