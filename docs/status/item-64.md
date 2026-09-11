# Item 64 — `GcStressTest` free memory falls 0.42 bytes per round, on every board

Journal split out of `docs/current-status.md` on 2026-09-11, under the rule
[item 116](../current-status.md#item-116) set and [item 155](../current-status.md#item-155)
found decayed: a section of 100 lines or more moves here.
Summary and current state: [item 64](../current-status.md#item-64).

---

## 2026-09-11 — six boards, and the metric is the thing to chase first

A six-board sweep (every attached board) re-measured this, and the result
**redirects the item**. Statistics re-derived from the raw transcripts, not
remembered — `build/<preset>/hw_verify.run1.txt`:

| preset | samples | steps UP | least-squares | post-GC peak |
|---|---:|---:|---:|---|
| ep4cgx150Serial | 356,142 | **0** | 0.4209 B/rnd | — never recovers |
| cyc5000Serial | 359,976 | **0** | 0.4207 | — |
| colorlightI5Sdram | 204,853 | **0** | 0.4218 | — |
| wukongFull | 435,192 | **0** | 0.4003 | — |
| xc7a100tDbSerial | 429,303 | **0** | 0.4004 | — |
| auSerial (Alchitry) | 380,448 | **58** | 0.3000 | **flat** |

**On the one board that visibly collects, there is no leak.** The Alchitry's 58
upward steps let the right statistic be computed — the post-collection
high-water mark, i.e. free memory immediately after each recovery. It spans
**2,696 bytes over 373,406 rounds, slope −0.00055 B/round**, and its last values
(266,254,076) are *higher* than its first (266,253,692). A leak cannot look like
that. Its whole-run least-squares slope of 0.30 B/round is an artifact of where
the sawtooth's phase falls at each end; the band itself is stable to ~900 bytes
at both the top and the bottom.

**And the other five cannot be showing what the item assumes.** `GcStressTest`
allocates ten `int[32]` per round, so ~1.5 KB/round: 356k rounds puts roughly
**500 MB through a heap whose reported free space is 5.46 MB**. Memory is
demonstrably being reused about a hundred times over, and `GC.freeMemory()`
records **zero** upward steps in 1.4 M samples across those five boards. The
instrument is not seeing the collections.

`freeMemory()` is `(allocPtr - copyPtr) * 4` (`GC.java:1650`) — the gap in the
current semispace, which recovers only at a flip, not at a minor collection.
That explains a metric that declines while the collector works. What it does
**not** explain is why the same metric recovers 58 times on the Alchitry and
never once on `wukongFull`/`xc7a100tDbSerial`, whose heaps are the same order of
size (262 MB vs 266 MB). Same source, same collector, same app.

**So the first action is no longer "find the leak".** It is: determine what
makes `allocPtr - copyPtr` recover on one 262-MB board and not on another.
Until that is answered, the 0.42 B/round figure is a property of the
instrument as much as of the heap, and this item has been reasoning from it
since 2026-08-25.

**A second correction, to the checker.** `judge_soak`'s docstring describes a
"healthy sawtooth band" and `--max-free-drift` is calibrated against that shape.
Five of six boards have **no sawtooth at all** — a monotone descending staircase
with a band of exactly zero. A tolerance calibrated against a shape the data
does not have is tolerating the thing it was written to detect. The soak lines
still say "sawtooth band 135936" for a series that never once went up.
