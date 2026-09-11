#!/usr/bin/env python3
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-09-11:
#   test_truncated_tail_is_not_a_leak   — red with the `(?=\s*\n)` anchor removed
#                                         from judge_soak (the state before
#                                         39611af): "free floor fell 5311223".
#   test_monotone_series_is_not_called_a_sawtooth
#                                       — red against judge_soak as it stood:
#                                         the verdict read "sawtooth band 1000"
#                                         for a series that never once rose.
# If you change these, re-prove them.
# ---------------------------------------------------------------------------
# hw_verify decides whether a hardware run counts as a pass, and until now
# NOTHING tested it. Two defects it shipped were both in the judging, not the
# driving: a truncated last line read as a real datum (39611af), and a verdict
# line that describes every series as a "sawtooth band" including the five
# boards whose free memory never rises at all (item 64).
#
# Run: python3 fpga/scripts/test_hw_verify.py
# ---------------------------------------------------------------------------
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hw_verify  # noqa: E402


def soak_log(samples):
    return "".join(f"R{r} f={f}\n" for r, f in samples)


def test_truncated_tail_is_not_a_leak():
    """A run cut off mid-line ends on a VALID-LOOKING partial record.

    `R356153 f=5` is what `R356153 f=5311228` looks like when the read stops.
    A parser that accepts it takes 5 as the low-water mark and reports a
    multi-megabyte leak on a board whose free memory never moved. It fails in
    exactly one direction -- a truncated number is always smaller -- so this is
    false leaks, never missed ones.
    """
    flat = [(r, 5311228) for r in range(0, 40000, 10)]
    out = soak_log(flat) + "R356153 f=5"  # no trailing newline: cut mid-line
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok, f"truncated tail read as a leak: {msg}"
    assert "5311228" in msg, msg


def test_real_floor_drop_is_still_caught():
    """The companion: the guard above must not have disarmed the check."""
    sinking = [(r, 5311228 - r // 10) for r in range(0, 40000, 10)]
    ok, msg = hw_verify.judge_soak(soak_log(sinking), min_rounds=1000, drift=100)
    assert not ok, f"a floor falling 4000 bytes was passed: {msg}"
    assert "possible leak" in msg, msg


def test_monotone_series_is_not_called_a_sawtooth():
    """Five of six boards produce a series that NEVER rises (item 64).

    Calling that a "sawtooth band" in the verdict is what let the item reason
    for a fortnight about a leak inside a band that does not exist. The verdict
    must say which shape it saw, because the shape is the finding.
    """
    staircase = [(r, 5311228 - r // 40) for r in range(0, 40000, 10)]
    ok, msg = hw_verify.judge_soak(soak_log(staircase), min_rounds=1000, drift=100000)
    assert ok, msg
    assert "sawtooth" not in msg, f"a monotone staircase described as sawtooth: {msg}"
    assert "never rose" in msg or "0 recover" in msg, msg


def test_oscillating_series_is_still_called_a_sawtooth():
    """The companion: a series that does recover must still read as one."""
    saw = [(r, 5311228 - (r % 1000)) for r in range(0, 40000, 10)]
    ok, msg = hw_verify.judge_soak(soak_log(saw), min_rounds=1000, drift=100000)
    assert ok, msg
    assert "sawtooth" in msg, f"an oscillating series not described as sawtooth: {msg}"


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    bad = 0
    for t in tests:
        try:
            t()
            print(f"  ok   {t.__name__}")
        except AssertionError as e:
            print(f"  FAIL {t.__name__}: {e}")
            bad += 1
    print(f"  {len(tests) - bad}/{len(tests)} hw_verify judging tests passed")
    sys.exit(1 if bad else 0)
