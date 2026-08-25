#!/usr/bin/env python3
"""
Put a build on real hardware and record what happened.

  fpga/scripts/hw_verify.py <preset> [args...] [--app <name>] [--runs N]

Step 5 of the conversion loop. Steps 1-4 (move to build/, generate constraints,
cold-build, delete the hand-written file) can only prove that nothing CHANGED --
they compare artifacts. They cannot prove that something still WORKS when a
change was intended, and every genuinely new artifact this project has produced
recently had no byte-identical predecessor to compare against.

WHY IT IS A SCRIPT AND NOT A PROCEDURE. Three boards were verified by hand in
one session and each got a different incantation: one programmed a stale
bitstream from the pre-move path, one used a bare `-c dirtyJtag` with TWO
dirtyJtag probes attached (which takes whichever enumerated first -- possibly
the other board), and one used a guessed console alias that resolved to nothing
and was reported as a broken board. Board facts restated at the point of use,
three times, three different ways.

Everything board-specific comes from HwVerifyDescriptor (the config) and the two
registries, which resolve by SERIAL because port paths move on every replug.

REFUSES rather than defaults. An unresolvable alias aborts. The failure mode
being avoided is an empty string flowing into a device path and the run looking
like a dead board.

ONE RUN IS A SAMPLE, NOT A VERDICT. A Wukong SDR build failed 1 run in 6 on
2026-08-24 and the cause was never found (status item 63). Results are appended
to <config>/hw_verify.log as one line per run so a flake stays visible instead
of being averaged into a boolean.
"""

import argparse, os, re, subprocess, sys, time
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPTS = os.path.join(ROOT, "fpga", "scripts")


def die(msg):
    print(f"hw_verify: {msg}", file=sys.stderr)
    sys.exit(2)


def descriptor(preset, extra):
    """Ask the config what this preset needs. sbt logs a forked process's stdout
    at INFO, so the prefix is stripped; `sbt -error` would suppress the answer
    along with the noise."""
    cmd = ["sbt", f'runMain jop.generate.HwVerifyDescriptorMain {preset} {" ".join(extra)}'.strip()]
    out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True).stdout
    d = {}
    for line in out.splitlines():
        line = line[7:] if line.startswith("[info] ") else line
        if re.fullmatch(r"[A-Z_]+=.*", line):
            k, v = line.split("=", 1)
            d[k] = v
    if "ENTITY" not in d:
        die(f"could not read a descriptor for preset '{preset}'.\n{out[-2000:]}")
    return d


def resolve(tool, flag, alias, what):
    r = subprocess.run([os.path.join(SCRIPTS, tool), flag, alias],
                       capture_output=True, text=True)
    val = r.stdout.strip()
    if r.returncode != 0 or not val:
        die(f"{what} alias '{alias}' did not resolve via {tool} {flag}.\n"
            f"  {r.stderr.strip() or 'no output'}\n"
            f"  Refusing to continue -- an unresolved alias reads as a dead board.")
    return val


def find_bitstream(cfg_dir):
    hits = []
    for dirpath, _, files in os.walk(cfg_dir):
        for f in files:
            if f.endswith((".sof", ".bit")):
                hits.append(os.path.join(dirpath, f))
    if not hits:
        die(f"no .sof/.bit under {cfg_dir} -- build it first")
    if len(hits) > 1:
        die("more than one bitstream under {}:\n  {}".format(cfg_dir, "\n  ".join(hits)))
    return hits[0]


def program(d, bitstream):
    if d["PROGRAM_TOOL"] == "quartus":
        cable = resolve("jtag_probe_map", "--cable", d["PROBE_ALIAS"], "JTAG probe")
        cdf = os.path.splitext(bitstream)[0] + ".cdf"
        if not os.path.exists(cdf):
            # quartus_pgm can take the .sof directly with an explicit operation.
            op = f"p;{bitstream}"
            cmd = ["quartus_pgm", "-c", cable, "-m", "JTAG", "-o", op]
        else:
            cmd = ["quartus_pgm", "-c", cable, "-m", "JTAG", cdf]
        qdir = os.environ.get("QUARTUS_BIN", "/opt/altera/25.1/quartus/bin")
        cmd[0] = os.path.join(qdir, "quartus_pgm")
    else:
        cmd = ["sudo", "openFPGALoader"]
        if d.get("LOADER_BOARD"):
            cmd += ["-b", d["LOADER_BOARD"]]
        else:
            cmd += ["-c", d["LOADER_CABLE"]]
            # Two dirtyJtag probes are attached to this host. Selecting by
            # bus:dev is the whole point; a bare -c takes whichever enumerated
            # first. Needs the patched openFPGALoader in /usr/local/bin.
            busdev = resolve("jtag_probe_map", "--busdev", d["PROBE_ALIAS"], "JTAG probe")
            cmd += ["--busdev-num", busdev]
        cmd += [bitstream]
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        die("programming failed:\n" + (r.stderr or r.stdout)[-2000:])


def monitor_only(d, timeout):
    """Just listen. A standalone exerciser has no .jop and no download step --
    it starts running the moment the FPGA is configured, so the only thing to do
    is read what it says."""
    tty = resolve("usb_serial_map", "--by-id", d["CONSOLE_ALIAS"], "console")
    import serial  # only needed on this path
    port = serial.Serial(tty, int(d["BAUD"]), timeout=1)
    buf, t0 = b"", time.time()
    while time.time() - t0 < timeout:
        buf += port.read(4096)
    port.close()
    return buf.decode("ascii", errors="replace")


def run_app(d, jop, timeout):
    tty = resolve("usb_serial_map", "--by-id", d["CONSOLE_ALIAS"], "console")
    cmd = ["python3", os.path.join(SCRIPTS, "download.py"), "-e", jop, tty, d["BAUD"]]
    try:
        r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
        out = r.stdout + r.stderr
    except subprocess.TimeoutExpired as e:
        # Expected: -e monitors until killed. Whatever arrived is the result.
        out = (e.stdout or b"").decode(errors="replace") + (e.stderr or b"").decode(errors="replace")
    return out


# A soak reports progress, not a verdict. GcStressTest prints one line per
# round: "R345124 f=5313644" -- a round counter and the free-memory count.
PROGRESS_RE = r"R(?P<round>\d+)\s+f=(?P<free>\d+)"


def judge_soak(out, min_rounds, drift, pattern=PROGRESS_RE):
    """Did it get far enough, and is memory being reclaimed?

    Written because the alternative was `--expect-text "R"`, which matches
    almost any output and passed while proving nothing. A soak asks two
    questions a substring cannot answer: did it REACH a meaningful number of
    rounds, and is the heap holding level?

    WHAT "LEVEL" MEANS, and the first version of this got it wrong. Free memory
    under a GC is SAWTOOTH -- it climbs when a collection runs and falls as
    objects allocate. The real i5 soak moves over a 130 KB band across 567
    distinct values, all of it healthy. Testing `max - min` therefore called a
    working collector a leak.

    What a leak actually looks like is the FLOOR dropping: each cycle reclaims a
    little less than the last. So compare the low-water mark of an early window
    against a late one. Oscillation inside a band is fine; a band that sinks is
    not.
    """
    samples = [(int(m.group("round")), int(m.group("free")))
               for m in re.finditer(pattern, out)]
    if not samples:
        return False, "no progress lines matched -- did the app run?"

    rounds = max(r for r, _ in samples)
    if rounds < min_rounds:
        return False, f"reached round {rounds}, wanted at least {min_rounds}"

    # Skip the first tenth: the heap settles before it is meaningfully level.
    warm = samples[len(samples) // 10:] or samples
    if len(warm) < 4:
        return True, f"{rounds} rounds (too few samples to judge the floor)"

    half = len(warm) // 2
    early_floor = min(f for _, f in warm[:half])
    late_floor = min(f for _, f in warm[half:])
    drop = early_floor - late_floor
    if drop > drift:
        return False, (f"free floor fell {drop} bytes ({early_floor} -> "
                       f"{late_floor}) between rounds {warm[0][0]} and "
                       f"{warm[-1][0]}, allowed {drift} -- possible leak")

    band = max(f for _, f in warm) - min(f for _, f in warm)
    return True, (f"{rounds} rounds, floor steady at {late_floor} "
                  f"(drop {drop}, sawtooth band {band})")


def judge(out):
    """Count what the JVM said. `ok` lines and an explicit exit are the signal;
    a test name containing 'Exception' is not a failure, which is why this
    matches 'fail' as a word rather than searching for scary substrings."""
    ok = len(re.findall(r"\bok\s*$", out, re.M))
    fails = len(re.findall(r"(?<![A-Za-z])fail(?![A-Za-z])", out, re.I))
    exited = "JVM exit!" in out
    crashed = out.count("Uncaught exception")
    return ok, fails, exited, crashed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("preset")
    ap.add_argument("args", nargs="*")
    ap.add_argument("--app", default="JvmTests/DoAll")
    # Exercisers have no .jop: they run as soon as the FPGA is configured.
    ap.add_argument("--no-app", action="store_true",
                    help="program and monitor only -- for standalone exercisers")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--expect-ok", type=int, default=66)
    # Not every app reports as a count of `ok` lines. SmpGcTest prints one
    # verdict, "SMPGC OK", and it is the ONLY test that runs all cores --
    # nothing in JvmTests writes IO_SIGNAL, so DoAll runs on core 0 whatever
    # cpuCnt says. An SMP build verified with DoAll would prove nothing about
    # the other cores.
    ap.add_argument("--expect-text", default=None)
    # Soak mode. `--expect-text` cannot express "got far enough and did not
    # leak", which is the only thing a GC soak is actually asserting.
    ap.add_argument("--min-rounds", type=int, default=None,
                    help="soak: require the round counter to reach at least N")
    ap.add_argument("--max-free-drift", type=int, default=0,
                    help="soak: bytes the free FLOOR may fall (a sawtooth band is fine)")
    ap.add_argument("--progress-re", default=PROGRESS_RE,
                    help="soak: regex with named groups 'round' and 'free'")
    ap.add_argument("--timeout", type=int, default=300)
    a = ap.parse_args()

    d = descriptor(a.preset, a.args)
    for key in ("PROBE_ALIAS", "CONSOLE_ALIAS"):
        if key not in d:
            die(f"board '{d['BOARD']}' has no {key} in its config.\n"
                f"  Add it to Board.{d['BOARD']} -- see Board.probeAlias.\n"
                f"  This board cannot be hardware-verified until then.")

    cfg_dir = os.path.join(ROOT, d["CONFIG_DIR"])
    bitstream = find_bitstream(cfg_dir)
    jop = None
    if not a.no_app:
        jop = os.path.join(cfg_dir, "java", "apps", a.app + ".jop")
        if not os.path.exists(jop):
            die(f"no image at {jop} -- build it with BUILDTREE=1 JOP_PRESET={a.preset}")

    log = os.path.join(cfg_dir, "hw_verify.log")
    passes = 0
    for i in range(1, a.runs + 1):
        program(d, bitstream)
        out = monitor_only(d, a.timeout) if a.no_app else run_app(d, jop, a.timeout)
        ok, fails, exited, crashed = judge(out)
        detail = ""
        if a.min_rounds is not None:
            good, detail = judge_soak(out, a.min_rounds, a.max_free_drift,
                                      a.progress_re)
            good = good and crashed == 0
        elif a.expect_text:
            # The expected text is necessary, not sufficient. Without the
            # `fails` term a run where T1 failed and T3 passed still went green,
            # because the string it was told to look for was present.
            good = a.expect_text in out and fails == 0 and crashed == 0
        else:
            good = ok >= a.expect_ok and fails == 0 and crashed == 0
        passes += good
        # Keep the console text for EVERY run, pass or fail. A bare "PASS" with
        # nothing behind it is not evidence, and this is the only record that
        # the board actually said what the verdict claims.
        transcript = os.path.join(cfg_dir, f"hw_verify.run{i}.txt")
        with open(transcript, "w") as f:
            f.write(out)

        line = (f"{datetime.now(timezone.utc).isoformat(timespec='seconds')} "
                f"{a.preset} {'(no app)' if a.no_app else a.app} run={i}/{a.runs} "
                f"{'expect=' + repr(a.expect_text) + ' ' if a.expect_text else ''}"
                f"{detail + ' ' if detail else ''}"
                f"ok={ok} fail={fails} exit={exited} crash={crashed} "
                f"{'PASS' if good else 'FAIL'}")
        with open(log, "a") as f:
            f.write(line + "\n")
        print(line)
        if not good:
            tail = "\n".join(out.splitlines()[-15:])
            print(f"--- last output ---\n{tail}\n-------------------")

    print(f"\n{a.preset}: {passes}/{a.runs} passed   (log: {os.path.relpath(log, ROOT)})")
    sys.exit(0 if passes == a.runs else 1)


if __name__ == "__main__":
    main()
