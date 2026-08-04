# Minor GC copy phase — redesign notes

Status: **not started.** This is a design note so the analysis is not lost. The
measurements below are real; the design is a proposal with open questions
flagged at the end.

Context: after the Stage 3 pause work (constants retuned, card scan bounded to
the used tenure regions, card granularity raised) the minor pause on the
A-E115FB fell 25.376 -> 14.143 ms. The copy phase did not move at any point and
is now the dominant term.

---

## 1. Why copy is the target

Worst-pause phase split, `GcPauseTest`, 2026-08-04:

| phase | A-E115FB DDR2 | XC7A100T DDR3 |
|---|---:|---:|
| **copy** | **11.300 ms (79%)** | **10.349 ms (82%)** |
| card scan | 1.931 ms (13%) | 1.498 ms (11%) |
| stack + static scan | 0.647 ms (4%) | 0.475 ms (3%) |
| mark | 0.264 ms (1%) | 0.200 ms (1%) |
| total | 14.143 ms | 12.523 ms |

Everything else is now small. Copy is where the remaining pause lives.

### It is latency-bound, not clock-bound

| board | ns/handle | clock | **cycles/handle** |
|---|---:|---:|---:|
| A-E115FB DDR2 | 1766 | 75 MHz | **132** |
| XC7A100T DDR3 | 1617 | 100 MHz | **162** |

The slower board uses *fewer cycles* per handle. The cost is wall-clock memory
latency, so raising the clock will not help and neither will shaving bytecodes.

---

## 2. Where the time goes

`copyAndSweepYoung` walks `youngList` and, per handle, the common (dead) path
does exactly three things:

```java
int next = Native.rdMem(ref+OFF_NEXT);              // read  — walk the list
if (Native.rdMem(ref+OFF_SPACE) == YOUNG_SURV) {…}  // read  — survivor test
Native.wrMem(0, ref+OFF_PTR);                       // write — mark dead
```

That is already tight: the comments record an earlier pass that cut six
accesses to two reads, and run-splicing that replaced ~33k freeList writes with
~67. **There is no easy fat left.** The cost is structural:

- The handle table is **65,536 x 8 words = 2 MB**, against a **32 KB cache**.
- `HANDLE_SIZE` = 8 words = 32 bytes = **exactly one 256-bit cache line**.
- So each handle touched is one compulsory miss, with **no intra-handle
  locality** to exploit — one line per handle, always.
- The `OFF_PTR = 0` write dirties that line, so each handle also costs a
  **writeback** on eviction: ~2 DRAM transactions per handle, not one.

Net: **~6400 line fills + ~6400 writebacks to discover ~66 survivors** (~1%).

The `youngList` traversal order is allocation order, which is not address order,
so the accesses are scattered across the 2 MB table — no DRAM row locality
either.

---

## 3. The idea

Both causes are **placement decisions, not algorithmic necessities**. The hot
young-generation metadata lives inside a 2 MB structure that cannot be cached;
move it into compact side structures that can be.

### 3a. Young set as a dense array, not a linked list

Replace the `OFF_NEXT`-threaded `youngList` with an array of handle refs.

- 8 refs per 256-bit line, so 6400 refs = **800 sequential line fetches**
  instead of 6400 scattered ones.
- Size is bounded by `MAX_YOUNG_OBJECTS` (currently 6400) = 6400 words = 25 KB.
- Sequential access also gets DRAM row hits, which the scattered walk never did.

### 3b. Survivor mark in a bitmap, not in the handle

Replace `OFF_SPACE == YOUNG_SURV` with a bit per handle.

- Indexed by handle number, `(ref - mem_start) >> 3`: 65,536 bits = **8 KB**,
  small enough to stay cache-resident across the sweep.
- The mark phase sets a bit instead of writing a handle field — no DRAM write,
  no dirtied line.

### 3c. Free list as a stack array, not a linked list

Reclaiming a dead handle currently writes its `OFF_NEXT` (mitigated by
run-splicing) and its `OFF_PTR`. If `freeList` were an array-backed stack,
reclaiming is an **append of a ref**, sequential, 8 per line.

### 3d. Removing the `OFF_PTR = 0` write — the hard part

That write exists so a freed handle cannot be mistaken for a live young object
by the **conservative** root scan: `pushYoung` reads `OFF_PTR` and treats
`>= nurseryBase` as young. A stale nursery pointer in a freed handle would let
`markYoung` set `YOUNG_SURV` on a free handle and corrupt it.

If it cannot be removed, every dead handle still costs a dirtied line and the
redesign wins much less. Two ways out:

1. **Allocated-handle bitmap.** Maintain a bit per handle meaning "currently
   allocated". `pushYoung` tests that bit (cache-resident) *before* trusting
   `OFF_PTR`. Freeing becomes a bit clear; no DRAM write, and the stale pointer
   is harmless because nothing reads it. Also speeds up `pushYoung` in the card
   and root scans, which currently read `OFF_PTR` from DRAM per candidate.
2. **Zero at allocation instead.** Allocation already writes `OFF_PTR`, so the
   field is never observed stale *by the mutator* — but the conservative
   scanner can still see it between free and reuse, so this alone is not safe
   without (1).

(1) looks like the real answer, and it is useful independently.

---

## 4. Expected payoff

Rough model, A-E115FB, per minor GC:

| | now | proposed |
|---|---:|---:|
| line fetches for the walk | 6400 | ~800 |
| writebacks for dead handles | ~6400 | ~0 |
| survivor test | 6400 DRAM reads | cache-resident bits |
| copy of survivors | ~66 objects | unchanged |

If copy is dominated by those ~12,800 DRAM transactions, removing ~90% of them
should take the copy phase from ~11.3 ms to roughly **2-4 ms**, i.e. the minor
pause from 14.1 ms to about **5-7 ms**. That is a guess from the transaction
count, not a measurement — treat the shape as more reliable than the number.

The survivor copy itself (~66 objects) is irreducible and is not the problem.

---

## 5. Constraints that must not break

- **The root scan is conservative.** Any candidate word may be a false handle.
  Nothing may assume a ref is a real object; `pushYoung`'s range and alignment
  checks and the useList-driven copy exist precisely so false positives cannot
  drive a bogus copy. Bitmaps must be indexed defensively.
- **`size == 0` is legal** (a class with no instance fields). Rejecting it once
  left zero-field objects as permanent zombies holding stale nursery pointers —
  see the `BADSZ` path. Keep that handling.
- **`OFF_TYPE` is only read by the collector**, never by `iaload`/`iastore`, so
  an array can be broken for GC while working perfectly for the mutator. Do not
  rely on mutator tests to catch GC metadata bugs.
- **`MAX_YOUNG_OBJECTS` bounds the young array**, and it is derived from the
  pause model. If the array is statically sized, changing the pause constants
  must not overflow it — assert this.
- The collector is stop-the-world with other cores halted, so no concurrency
  constraints, but SMP handle-table layout still applies.

## 6. Suggested staging

Each stage is independently measurable; do not do them at once.

1. **Allocated-handle bitmap** (3d/1) and make `pushYoung` use it. Independently
   useful — it also removes a DRAM read per candidate from the card and root
   scans. Measure those two phases before/after.
2. **Survivor bitmap** (3b). Removes one DRAM read per handle from the sweep.
3. **Young set as an array** (3a). The big one; removes the scattered walk.
4. **Free list as a stack array** (3c). Only if it still shows up after 1-3.

## 7. Validation

- `GcPauseTest` on all three boards, before and after each stage, with the phase
  split. The A-E115FB is the sensitive one.
- `MultiArrayGcTest` and `IntHandlerGcTest` — both exist because this area has
  produced subtle premature-collection bugs (the `multianewarray` `OFF_TYPE`
  bug reached `JVMHelp.ih` interrupt handlers before it was caught).
- `GcStressTest` soak — 700k+ rounds is reachable in ~5 minutes and has caught
  handle-lifecycle bugs that short tests miss.
- `DoAll` (66/66) as the functional backstop.
- Watch `gcBadYoungCnt` / `born-bad`: non-zero means a handle reached the sweep
  with an impossible size, which is the signature of exactly this class of bug.

## 8. Open questions

- **Cache pressure.** A 25 KB young array streaming through a 32 KB 4-way cache
  will evict the bitmaps. Does the array need to be processed in blocks so the
  bitmaps stay resident? Worth simulating before committing to sizes.
- **Where do the side structures live?** They need a reserved region; the
  handle-table carve in `GC.init` is the obvious place, but it changes the heap
  layout and therefore `IO_CARD_TENURE_LO/HI`.
- Is the young array better kept **sorted by address**? That would give DRAM row
  locality on the survivor copies too, at the cost of insertion order.
- Does any of this help the **major** GC, whose 2.2 s at 36k live objects is
  still unexplained? `compactAndSweep` walks useList the same way, so the same
  placement argument may apply — worth checking before designing separately.
