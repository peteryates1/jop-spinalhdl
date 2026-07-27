# Stage 2 — Generational GC (nursery + minorGc) — Design

> **STATUS: DESIGN / FOR REVIEW.** The high-risk `GC.java` change that the whole
> Stage 0/1 hardware foundation was built to enable. Grounded in the current
> mark-compact GC (`java/runtime/src/jop/com/jopdesign/sys/GC.java`, DoAll 66/66
> on both boards). Gated behind `USE_GENERATIONAL` so the proven collector stays
> the fallback.

---

## 0. What we're building on (current GC facts)

- **Split handle/data.** A handle is 8 words in a fixed table (`freeList`), never
  moves. `OFF_PTR` → object data; `OFF_MTAB_ALEN` → method table (obj) or length
  (array); `OFF_SPACE` → mark word (== `toSpace` when live); `OFF_TYPE` →
  IS_OBJ / IS_REFARR; `OFF_NEXT`/`OFF_GREY` → list threading.
- **One heap, two pointers.** `[heapStart, heapStart+heapSize)`. Compacted live
  data grows **up** from `heapStart` (`copyPtr`); new allocations grow **down**
  from the top (`allocPtr`). Free = `[copyPtr, allocPtr)`. Mark-compact, no
  semi-space.
- **Reference traversal** (`markChildren`): data at `OFF_PTR`; for IS_REFARR,
  children are `data[0..len)`; for IS_OBJ, the `GC_INFO` bitmap
  (`mtab[MTAB2GC_INFO]`) says which field words are references.
- **Conservative scanning** already exists (`getStackRoots` + `isValidObjectHandle`)
  — a word is treated as a root iff it looks like a valid handle. We reuse this.
- **HW ready:** `zeroMem` uses the fill DMA (`IO_ZERO_*`); the **card table**
  (`IO_CARD_*`) marks every tenure-range write. Both hardware-validated.

## 1. Generational model mapped onto the split heap

Handles are shared; only **data** is young or old. So "nursery" and "tenure" are
two **data** regions, and promotion copies data + rewrites `OFF_PTR` (the handle
stays put — much cheaper than a classic copying nursery).

```
[heapStart ................ tenureTop | nurseryBase ...... nurseryTop=heapEnd)
 └─ tenure (mark-compact, grows up)   └─ nursery (bump-alloc, grows down)
```

- **tenure** = the existing mark-compact region: `copyPtr` (up) … `tenureTop`.
- **nursery** = a fixed slice at the top: `[nurseryBase, nurseryTop)`,
  `nurseryAllocPtr` bumps **down**. Size = tuning knob (Stage 3; start ~1–4 MB).
- New object/array **data** allocated from the nursery; **handle** still from
  `freeList`. Objects whose data lives ≥ `nurseryBase` are "young".

## 2. Allocation (`newObject` / `newArray`)

```
data = nurseryAllocPtr - size
if (data < nurseryBase) { minorGc(); data = nurseryAllocPtr - size;
                          if (data < nurseryBase) { promote-directly-or-major } }
nurseryAllocPtr = data
handle = freeList; freeList = next(handle)
OFF_PTR(handle) = data; set MTAB/TYPE/SPACE as today
```

Nursery data need **not** be pre-zeroed per object if `minorGc` HW-zeros the whole
nursery after each cycle (the fill win) — but the current code zeroes per object;
keep per-object zeroing initially (correctness first), optimize to bulk-zero once
validated. Large arrays above a threshold may allocate straight into tenure to
avoid thrashing the nursery (Stage 3 tuning).

## 3. `minorGc()` — stop-the-world, bounded by nursery size

The RT win: pause ∝ nursery size + live young set, **not** total heap.

1. **Set the tenure card window** once at init: `IO_CARD_TENURE_LO/HI =
   [heapStart, tenureTop)` (data-word addresses). (Writes into the nursery also
   mark cards but are ignored by the scan — harmless.)
2. **Root scan (into nursery only).** Reuse `getStackRoots` + `getStaticRoots`;
   for each root handle whose `OFF_PTR ≥ nurseryBase`, enqueue it for copy.
3. **Inter-generational roots via cards.** For each **dirty** card (read 32 at a
   time: write `IO_CARD_IDX`, read `IO_CARD_DATA`), scan the tenure words it
   covers; a word that is a valid handle with `OFF_PTR ≥ nurseryBase` is a young
   root → enqueue. (Conservative, same rule as stack scanning.)
4. **Copy survivors (Cheney-style worklist).** For each young root not yet
   copied: `dst = tenureAlloc(size)` (bump into tenure), copy the `size` data
   words nursery→tenure, rewrite `OFF_PTR(handle)=dst`, mark copied. Then scan the
   **copied** object's reference fields (`GC_INFO`/array) for further
   `OFF_PTR ≥ nurseryBase` refs and enqueue them. Repeat until the worklist
   drains — this transitively promotes everything reachable and young.
5. **Reclaim dead young handles.** A handle whose data was in the nursery and was
   **not** copied is garbage → clear `OFF_PTR`, return to `freeList`. (Walk the
   young-handle list built at allocation, or the `useList` filtered by
   `OFF_PTR ≥ nurseryBase` pre-copy.)
6. **HW-zero the nursery**: `zeroMem(nurseryBase, nurseryTop)` (the fill DMA).
7. **Clear cards**: `IO_CARD_CLEAR = -1` (HW sweep).
8. **Reset** `nurseryAllocPtr = nurseryTop`.

Where does tenure data come from? `tenureAlloc` bumps a tenure pointer (the
existing `allocPtr`/`copyPtr` scheme — promotions are just tenure allocations).
When tenure runs low → **major GC**.

## 4. Major GC (unchanged mark-compact, on tenure)

Triggered when tenure free `< threshold` (or an allocation can't be promoted).
The existing `gc()` / mark / `compactAndSweep` runs over the **tenure** region as
today (it already handles the whole heap; here it just excludes the nursery, or
runs after a `minorGc` drains the nursery). Its marks (`OFF_SPACE`/`toSpace`)
are independent of the minor-copy mark bit (use a distinct young-mark, e.g. a
per-cycle nursery mark value or a "copied" sentinel in `OFF_PTR` scratch).

## 5. Write barriers

- **tenure → nursery**: caught by the **HW card table** (every tenure-range
  store marks a card; step 3 scans them). Zero software cost.
- **nursery → anything**: no barrier needed — `minorGc` scans the whole nursery.
- **tenure → tenure**: irrelevant to minor GC; handled by major GC's full mark.

The old software `writeBarrier` (`GC.java:1226`, pre-write snapshot) is **not**
used for generational; leave it for the incremental/concurrent path or disable
under `USE_GENERATIONAL`.

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Conservative card scan over-retains | Accepted (same as stack scanning); bounded by tenure size scanned per dirty card |
| Transitive young refs missed | Cheney worklist (step 4) drains all reachable young |
| Mark-bit clash minor vs major | Separate young-mark; never write `toSpace` during minor |
| Card scan cost at large heap | Only dirty cards' words scanned; HW next-dirty scanner deferred (Stage 1 note) |
| Interaction w/ incremental GC | `minorGc` is STW (bounded); major GC keeps its incremental machinery |
| Handle exhaustion under churn | Young dead handles freed each minor (step 5) |

## 7. Build order + validation (incremental, flag-gated)

1. **`USE_GENERATIONAL` flag + nursery layout in `init`** (no behavior change when
   false). Add `nurseryBase/Top/AllocPtr`, set card window.
2. **Java card-scan helper** — iterate dirty cards, yield candidate young roots.
   Unit-testable against a known card pattern (extends CardMarkTest).
3. **Nursery allocation** in `newObject`/`newArray` (guarded).
4. **`minorGc()`** (steps 2–8). Validate on `JopSmallGcBramSim` + `GcStressTest`
   (the test that caught the inverted-range fill bug) in sim first.
5. **Hardware**: DoAll 66/66 + a GC-stress app on **both** boards (SDR + DDR3),
   plus a minor-pause measurement (the Stage 3 bound).
6. **Stage 3**: size the nursery for the ~75 ms bound; tune promotion threshold.

## References
- [Generational GC plan](gc-generational-implementation-plan.md) · [Stage 1 card table](stage1-card-table-design.md)
- `GC.java`: `init` (214), `newObject` (920), `newArray` (1038), `markChildren`
  (437), `getStackRoots` (331), `isValidObjectHandle` (1188), `zeroMem` (896).
