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
# PROVED RED 2026-10-12:
#   test_a_spliced_forward_jump_does_not_end_the_soak, test_a_round_no_32_bit_
#   counter_can_print_is_dropped -- red against judge_soak's "rounds must
#   advance" alone: the merged record became the round reached and every later
#   record was dropped as a replay.
#   test_a_merged_free_value_does_not_end_the_soak -- red with only the round
#   rules: the grown free value became the cliff reference.
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


def test_a_stale_image_is_refused(tmp=None):
    """An app image older than its source must not be run.

    THIS COST A WRONG CONCLUSION on 2026-09-14. A test vehicle was added to
    SmpGcTest to provoke item 158, hw_verify was run, and the result said the
    vehicle did not provoke anything. It had run a .jop built four hours before
    the source was edited: hw_verify downloads whatever image is present and
    never rebuilds. The rebuilt image reported haltLeak 5213 on the first round.

    A stale image fails in the most expensive direction -- it produces a clean,
    plausible result for code that was never executed.
    """
    import tempfile, time
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "App.java")
        jop = os.path.join(d, "App.jop")
        open(jop, "w").write("image")
        time.sleep(0.01)
        open(src, "w").write("source")      # source is NEWER
        stale = hw_verify.stale_sources(jop, [d])
        assert stale, "a source newer than the image was not reported"
        assert "App.java" in stale[0], stale


def test_a_current_image_is_accepted():
    """The companion: an image newer than its sources must run."""
    import tempfile, time
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "App.java")
        jop = os.path.join(d, "App.jop")
        open(src, "w").write("source")
        time.sleep(0.01)
        open(jop, "w").write("image")       # image is NEWER
        assert hw_verify.stale_sources(jop, [d]) == [], "a current image was called stale"


def test_a_stale_bitstream_is_refused():
    """A bitstream older than the RTL it was built from must not be programmed.

    THIS COST AN AFTERNOON on 2026-09-14. The CYC5000 appeared to hang at its
    first GC: IO_GC_HALTED read 0 where an identical board read 1, with
    byte-identical RTL, byte-identical constants and the same .jop. The board
    was running a .rbf from THREE DAYS EARLIER -- `make all` rebuilds the .sof
    and only `make program` regenerates the .rbf, while find_bitstream prefers
    the .rbf for openFPGALoader. So the register being polled did not exist in
    the bitstream on the chip.

    It cost so much because every artefact that was easy to check was correct.
    DoAll passed on the stale bitstream, which made it look like a software bug.
    """
    import tempfile, time
    with tempfile.TemporaryDirectory() as d:
        rtl = os.path.join(d, "rtl"); os.makedirs(rtl)
        bit = os.path.join(d, "top.rbf")
        open(bit, "w").write("bitstream")
        time.sleep(0.01)
        open(os.path.join(rtl, "Top.v"), "w").write("module")   # RTL is NEWER
        stale = hw_verify.stale_sources(bit, [rtl], exts=(".v", ".vhd"))
        assert stale, "RTL newer than the bitstream was not reported"
        assert "Top.v" in stale[0], stale


def test_a_current_bitstream_is_accepted():
    """The companion: a bitstream newer than its RTL must program."""
    import tempfile, time
    with tempfile.TemporaryDirectory() as d:
        rtl = os.path.join(d, "rtl"); os.makedirs(rtl)
        bit = os.path.join(d, "top.rbf")
        open(os.path.join(rtl, "Top.v"), "w").write("module")
        time.sleep(0.01)
        open(bit, "w").write("bitstream")                        # bitstream NEWER
        assert hw_verify.stale_sources(bit, [rtl], exts=(".v", ".vhd")) == [], \
            "a current bitstream was called stale"


def test_midstream_corruption_is_not_a_leak():
    """A UART capture can be corrupted in the MIDDLE, not only at the end.

    The 39611af fix anchored each record to a following newline, which drops a
    record truncated where the capture stops. It does nothing for this, seen on
    wukongFull 2026-09-14:

        R704395 f=261779028
        R704396 f=2            <- truncated, and followed by a newline
        R704395 f=261779028    <- the round number goes BACKWARDS

    A chunk of the stream was replayed, and the splice left one short record
    with a newline after it. That 2 became the low-water mark and the judge
    reported "free floor fell 261900882 bytes" on a board whose free memory had
    moved by 122 KB.

    One corrupt sample must not be able to define the floor.
    """
    # THE CORRUPT SAMPLE MUST LAND IN THE LATE HALF, which is where it was on
    # wukongFull (round 704396 of 726932, ~97% through). Put it in the early
    # half and the test passes against the UNFIXED code: judge_soak compares an
    # early floor against a late one, so a low outlier before the midpoint only
    # makes the drop negative. That is passing for the wrong reason, and the
    # first version of this test did exactly that.
    good = [(r, 261779028) for r in range(0, 40000, 10)]
    cut = int(len(good) * 0.97)
    out = soak_log(good[:cut]) + "R38800 f=2\n" + soak_log(good[cut:])
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok, f"one corrupt sample read as a leak: {msg}"


def test_replayed_chunk_is_dropped():
    """Rounds must advance; a replayed chunk is corruption, not data."""
    good = [(r, 500000) for r in range(0, 40000, 10)]
    out = soak_log(good) + soak_log(good[:500])      # stream replays its start
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok, f"a replayed chunk was read as a fault: {msg}"
    assert "39990" in msg, f"the replay should not change the round reached: {msg}"


def test_a_spliced_forward_jump_does_not_end_the_soak():
    """A lost burst can MERGE two lines into a record whose round runs AHEAD.

    The A-E115FB soak, 2026-10-12 (CH340 at 2 Mbaud, 3.37 M rounds) lost about
    37 bytes here and joined what was left:

        R53097 f=1067378748
        R53053100 f=1067378748     <- "R530" + "53100": two lines merged
        R53101 f=1067378748

    "Rounds must advance" took 53053100 as the new high-water mark, so every
    later record looked like a replay: 3,315,314 of 3,368,293 samples were
    thrown away and the verdict, "free floor fell 399872 bytes between rounds
    5307 and 102849328495", was judged on the 2 % that survived. Dropping
    data that freely can hide a leak as easily as invent one.

    A record out of order with two neighbours that agree with each other is
    the corrupt one, not everything after it.
    """
    good = [(r, 500000) for r in range(0, 40000)]
    cut = len(good) // 2
    out = soak_log(good[:cut]) + "R20053100 f=500000\n" + soak_log(good[cut:])
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok, f"a spliced record failed the soak: {msg}"
    assert msg.startswith("39999 rounds"), f"the splice should not set the round reached: {msg}"
    assert "1 corrupt record(s) dropped" in msg, f"only the spliced record should go: {msg}"


def test_a_round_no_32_bit_counter_can_print_is_dropped():
    """GcStressTest's counter is a Java int; a longer number is a merge."""
    good = [(r, 500000) for r in range(0, 40000)]
    out = soak_log(good[:30000]) + "R102849328495 f=500000\n" + soak_log(good[30000:])
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok and msg.startswith("39999 rounds"), f"an impossible round was taken as data: {msg}"


def test_a_merged_free_value_does_not_end_the_soak():
    """The same loss can merge the FREE value instead: the A-E115FB soak had
    "R137554 f=10673444956" between two records of f=1067344956. A value that
    grew passes the cliff rule (which catches truncation, i.e. shrinking) and
    becomes the reference, after which every real record is "below half" of it
    and is dropped. Under 2**31 here, so only the neighbour rule can see it."""
    good = [(r, 150000000) for r in range(0, 40000)]
    cut = len(good) // 2
    out = soak_log(good[:cut]) + "R20000 f=1500000000\n" + soak_log(good[cut + 1:])
    ok, msg = hw_verify.judge_soak(out, min_rounds=1000, drift=4096)
    assert ok and msg.startswith("39999 rounds"), f"a merged free value ended the soak: {msg}"
    assert "1 corrupt record(s) dropped" in msg, f"only the merged record should go: {msg}"


def test_a_failed_test_is_counted_as_a_failure():
    """`failed!` is what DoAll prints, and the judge could not see it.

    judge() matched `fail` as a whole word, to avoid flagging a test NAMED
    something like HwExceptionTest. But DoAll.java:149 prints " failed!", and
    `fail` followed by `ed` fails the trailing word-boundary, so the match never
    fired. Every hardware run reported fail=0 by construction.

    Found 2026-09-15 when DeepRecursion failed on the DB V5 and hw_verify said
    "ok=68 fail=0 ... PASS" while the console said "DeepRecursion failed!".
    """
    out = "Basic ok\nDeepRecursion failed!\nArray ok\nJVM exit!\n"
    ok, fails, exited, crashed = hw_verify.judge(out)
    assert fails == 1, f"a failing test was not counted: fails={fails}"
    assert ok == 2, f"ok count wrong: {ok}"


def test_a_test_named_for_an_exception_is_not_a_failure():
    """The companion, and the reason the regex was tight in the first place.

    A test NAME is not a failure. Widening the pattern must not start counting
    HwExceptionTest, or the judge swaps one blind spot for a noisy one.
    """
    out = "HwExceptionTest ok\nExcept ok\nNullPointer ok\nJVM exit!\n"
    ok, fails, exited, crashed = hw_verify.judge(out)
    assert fails == 0, f"test names counted as failures: fails={fails}"
    assert ok == 3, f"ok count wrong: {ok}"


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
