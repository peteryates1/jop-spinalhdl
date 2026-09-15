# The 3-bank spill/fill stack cache

Reverse-engineered from the RTL on 2026-08-11 because nothing described it and
the GC work needed the memory mapping. Everything below cites
`spinalhdl/src/main/scala/jop/pipeline/StackStage.scala` unless stated
otherwise. Selected by `JopCoreConfig.useStackCache` (default **false**).

## Why it exists

The fixed stack is one core-private RAM of `1 << ramWidth` words — 256 with the
default `ramWidth = 8`. That caps recursion depth and it cannot grow. The stack
cache replaces it with a **sliding window over a much larger virtual stack**
held in main memory: `virtualSpWidth = 16`, so up to 65535 words per core, of
which only ~576 are resident at any moment.

## Geometry

| parameter | default | meaning |
|---|---:|---|
| `numBanks` | 3 | fixed — `require(numBanks == 3)` |
| `bankSize` | 192 | virtual words covered by one bank |
| `bankPhysicalSize` | 256 | M9K depth; 64 words per bank unused |
| `scratchSize` | 64 | fixed by JOP microcode; never spilled |
| `virtualSpWidth` | 16 | virtual stack address width |
| `spillBaseAddr` | per core | see below |
| `burstLen` | 4 | DMA burst length |

Virtual addresses **0..63** are the microcode **scratch** area, held in a
separate `scratchRam` that is never spilled. `Const.STACK_OFF` is 64, and the GC
scans from `STACK_OFF` upward, so scratch is deliberately outside the collector's
view. `initialSP` is 128.

The three banks start covering `[64,256)`, `[256,448)`, `[448,640)`
(`bankBaseVAddr(i).init(scratchSize + i * bankSize)`, :410).

## State per bank

- `bankBaseVAddr(i)` — the virtual address this bank currently covers; it holds
  `[base, base + bankSize)` (:400)
- `bankResident(i)` — contents are valid
- `bankDirty(i)` — written since it was filled (:608)

A read hits bank `i` when `rdaddr >= base(i) && rdaddr < base(i)+bankSize &&
bankResident(i)` (:454-457). Writes use the same test (:557-559).

## Memory mapping — LINEAR, and that is the important part

```scala
def extByteAddr(bankBase: UInt): UInt = {
  val spillBase  = U(cc.spillBaseAddr, wordW bits)
  val bankOffset = bankBase.resize(wordW) - cc.scratchSize
  ((spillBase + bankOffset) << 2).resize(byteW)
}
```
(:722-727)

So for any virtual word `V >= 64`:

```
external word address = spillBaseAddr + (V - 64)
```

**The spill area is a flat image of the virtual stack**, not bank-rotated and
not interleaved. Anything wanting to read another core's stack out of memory can
treat it as a plain array — no knowledge of bank assignment required.

`spillBaseAddr` is per core and carved from the top of memory
(`JopCoreConfig.scala:435-438`):

```
spillBaseAddr = memWords - (cpuId + 1) * memConfig.stackRegionWordsPerCore
```

`JopCoreConfig.scala:349` requires `stackRegionWordsPerCore > 0` (or an explicit
override) whenever `useStackCache` is set, precisely so these regions cannot
overlap the GC heap.

## Rotation

Driven by `smuxSignal` — the stack address mux — which is `sp`, `spm`, `spp`, or
**the A register** when `selSmux = 3` (:301-307). That last case matters: an
indirect stack access such as `Native.rdIntMem(addr)` presents its address in A,
so *arbitrary* stack reads drive rotation, not just SP movement.

```
needsRotation    = !smuxInScratch && !smuxInActiveBank && rotState == IDLE
canInstantSwitch = needsRotation && some OTHER resident bank already covers smux
victimChoice     = the non-active bank whose BASE is farthest from activeBase
isUnderflow      = smuxSignal < activeBase && !smuxInScratch
```
(:690-712)

### The resident set must stay CONTIGUOUS

Three banks exist so that when SP sits near a bank boundary, the banks **either
side of it are both resident** and the third is the one being spilled or filled.
That invariant only holds if the three bases are consecutive multiples of
`bankSize`.

Victim selection used `(activeBankIdx + 2) % 3`, commented "farthest from
active" — but that is farthest in INDEX space, which says nothing about where
the banks are. Measured on 2026-09-15 with SP=637 and banks `0[64] 1[256]
*2[448]`, it evicted **bank 1 (256-447), adjacent to the active bank**, and kept
bank 0 at 64-255, the farthest away:

```
    before   0[  64] 1[ 256] *2[ 448]        resident 64 .. 831
    after    0[ 640] 1[ 256] *2[ 448]        resident 64-255 | HOLE 256-447 | 448-831
```

A read into that hole is **silent**: the read MUX has no miss signal and falls
through to `ramDout := 0` (`StackStage.scala:525`). Choosing the victim by
distance from `activeBase` keeps the window contiguous.

Guard: `JopStackCacheSim` asserts the three bases are consecutive on every
cycle and reports the count. PROVED RED against the index rule —
*"RESIDENT SET NOT CONTIGUOUS at cycle 664906: bases 64 448 640 (gaps 384 192,
want 192)"*. This assertion is the only thing standing behind the fix:
`DeepRecursion` fails both with and without the hole, so it cannot witness it.

### What does NOT drive rotation — JVM locals

`smuxSignal` is SP-derived only (`sp`/`spm`/`spp`/`A`). The read and write
address MUXes have other sources, and **`vpadd` is one of them**
(`StackStage.scala:990`, `:1005`):

```
rdaddr := vpadd        // vpadd = vp0 + opd  (:1085) — a JVM local
```

**FIXED 2026-09-15.** VP is now a rotation input: the window tracks
**[VP, SP]**, not SP alone.

```
rotNeedVp = !vpResident                        // VP outside every resident bank
rotAddr   = Mux(rotNeedSmux, smuxSignal, vp0)  // SP has priority
needsRotation = (rotNeedSmux || rotNeedVp) && rotState == IDLE
```

`isUnderflow` and the target base key on `rotAddr`, so a VP-driven rotation
FILLS real data rather than zero-filling over the caller's locals. Before this,
`DeepRecursion` measured 9,181 cycles with VP outside every bank — at
`vp=1403 sp=1415`, banks 1408/1600/1792, i.e. SP seven words above the window
base with VP twelve words behind it, falling off the bottom.

Note the two kinds of "local" are different things and only one of them is
safe:

| | where | resident? |
|---|---|---|
| microcode locals / constants | scratch, addresses 0-63 | ALWAYS — `smuxInScratch` exempts them |
| JVM locals | the Java stack, addressed `vp0 + offset` | only if they fall in the window |

`vp` itself is a microcode variable; `vp + offset` is a **stack** address. With
~9 slots per frame and a 576-word window, roughly 64 frames fit — a deeper call
chain leaves older frames' locals unreachable with nothing to fetch them back.
**This is unresolved**; see [item 133](../current-status.md#item-133).

- **Instant switch** — another resident bank already covers the target, so just
  move `activeBankIdx` (:763-764). This is the common case and costs nothing;
  it is what makes "one active, one next" behaviour appear.
- **Overflow** (growing) — victim is reassigned to `activeEnd` and
  **ZERO_FILLed**, not filled from memory: fresh frames have no prior contents
  worth reading (:750-761, :787-791).
- **Underflow** (returning) — victim is reassigned to `activeBase - bankSize`
  and **FILLed** from memory.
- Either way, if the victim is **dirty** it is **SPILLed first** (:745-748).

State machine: `IDLE -> SPILL_START -> SPILL_WAIT -> {FILL_START|ZERO_FILL} -> IDLE`.
`prefillThreshold = bankSize/4` is **defined and referenced nowhere**
(`StackStage.scala:53` is its only occurrence in the tree). The pre-fill this
sentence used to describe does not exist. Without it the window is rebased
upward on overflow and nothing brings the bank BELOW back until SP itself
descends — which is how SP came to sit seven words above the window base with
568 words above it and none below.

## Consequences for the garbage collector

1. **A core scanning its OWN stack is correct** with the cache, because
   `rdIntMem` addresses reach `smuxSignal` and trigger rotation. It can be
   *slow*: every access outside the resident window costs a spill and a fill of
   192 words. A deep-stack scan is therefore O(depth) DMA transfers, not O(depth)
   reads.

2. **A core CANNOT scan another core's stack**, which is
   [current-status.md](../current-status.md) item 1's root cause. The banks are
   core-private and the memory image is stale for any bank not written back.

3. **Flush ALL resident banks, not just dirty ones**, before reading another
   core's stack out of memory. A bank that was ZERO_FILLed on overflow is
   *clean* but its memory image still holds whatever a deeper previous stack
   left there. Scanning that stale data is safe for a conservative collector but
   manufactures false roots and retains garbage. Flushing all three banks is 576
   words — trivial next to a minor pause.

4. **The top-of-stack registers `a`/`b` are not in any bank.** They are pipeline
   registers, so neither a flush nor a bank read captures them, and a freshly
   allocated handle sits in `a` before it reaches memory. Any cross-core root
   scan must read them separately, or it will look correct and still lose
   objects.

5. Scratch (0..63) needs no handling: never spilled, and below `STACK_OFF`.
