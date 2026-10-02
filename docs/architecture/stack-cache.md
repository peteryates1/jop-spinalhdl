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
**the A register** when `selSmux = 3` (:301-307).

**AND `selSmux = 3` IS SELECTED BY EXACTLY ONE INSTRUCTION: `stsp` (0x01b).**
`DecodeStage.scala:397` is `when(ir === B"10'b0000011011")`, and 0b0000011011 is
0x1b. Nothing else reaches it.

> **CORRECTED 2026-09-30, and the old text misdirected status item 133 for two
> weeks.** This section used to say: *"an indirect stack access such as
> `Native.rdIntMem(addr)` presents its address in A, so arbitrary stack reads
> drive rotation, not just SP movement."* The premise is true and the inference
> is false. `rdIntMem` does not issue `stsp`; it issues **`star` (0x01a)**, which
> latches the address into **AR**, and then `ldmi` reads at AR
> (`asm/src/jvm.asm:2190-2193`, assembled at `build/microcode/serial/rom.mif:3106-3112`).
> `star` and `stsp` differ by one bit and do entirely different things.
>
> **AR reaches no part of the rotation controller** — `command grep` for `ar`
> over `StackStage.scala:700-1000` returns nothing. So an AR-addressed access
> outside the resident window does not rotate: the read returns **0** silently
> (`StackStage.scala:511`, `ramDout := 0` as the mux default) and the write is
> **dropped** (`:622-631`, `isPipeTarget` requires a bank hit). `spOv` is keyed
> on `sp` alone (`:1215-1222`), so nothing faults either.
>
> Every AR-addressed path therefore walks the stack unchecked. The ones that
> matter: `Native.rdIntMem`/`wrIntMem` (`jopsys_rdint`/`jopsys_wrint`),
> `Native.int2extMem`/`ext2intMem` (the RT-thread context switch), and through
> them `f_athrow`'s unwind. See item 133.
>
> **FIXED 2026-10-01 — not by rotation.** Such an access is now served from the
> spill region without moving the window; see
> [Accesses the window does not cover](#accesses-the-window-does-not-cover--served-from-the-spill-region).

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
**[VP, SP]**, not SP alone — **while VP <= SP** (qualified 2026-10-01, below).

```
rotNeedVp = !vpResident && vp0 <= smuxSignal   // VP below the window, not above SP,
            && smuxSignal - vp0 < bankSize     //   and within one bank of it
rotAddr   = Mux(rotNeedSmux, smuxSignal, vp0)  // SP has priority
needsRotation = (rotNeedSmux || rotNeedVp) && rotState == IDLE
```

**VP > SP happens, and rotating for it destroyed data.** `f_athrow` ends with
`setSP(fp+4); unlock(0); return t`: SP drops to the handler's frame while the
method still runs in its own frame, far above. A rotation for that VP moves the
window UP, and an upward rotation ZERO-fills — above the active bank is assumed
dead — so `f_athrow`'s own locals came back as 0 and `return t` returned
garbage (`jvm.DeepThrow` at depth 60, the first depth where `f_athrow`'s frame
sits past the initial window). With VP and SP more than a window apart the two
would also livelock. So VP drives rotation only while it is at or below the SP
the current instruction is moving to (`smuxSignal`: during `stsp` the `sp`
register still holds the old, high value); a local above SP that the window
does not cover is served from the spill region like an AR access.

**And only within one bank of SP** (2026-10-02). The target is one bank below
the active bank, so one step reaches VP exactly when SP - VP < bankSize: the
straddling frame. Further away the window THRASHES — SP's bank snaps back to
active between rotations, a 192-word fill per instruction — which the RT
scheduler triggers on every switch away from a deep thread (`setVP(newSP + 2)`
while still on the old stack): one local cost 769 DMA reads. Such a local is now
served from the spill region.

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
~9 slots per frame and a 576-word window, roughly 64 frames fit. A deeper call
chain's OLDER frames are not resident, and that is fine for the JVM itself — a
method only addresses its own locals, which the window covers while it runs —
but not for code that WALKS the stack. Those walks are what the next section
serves.

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

**The target is ONE bank from the active one** (`activeBase -/+ bankSize`), not
the bank holding the address that missed. For SP moving a word at a time that is
the same thing. For a JUMP of several banks (`setSP` in `f_athrow`, the RT
scheduler) the controller walks one bank per rotation, and along the way can
hold two banks with the same base. The extra one is a clean refill covering
only words above the new SP, and both copies receive every later write, so under
the stack discipline (a word above SP is written before it is read) nothing
observes it — worked through by hand, not measured. `JopStackCacheSim`'s
contiguity guard does count those cycles: 6,936 in `jvm.DeepAll`, none before
`DeepThrow`, whose unwinds are the only multi-bank jumps in it. Computing the
target from the window's ends rather than from the active bank would remove
them; not done.
`prefillThreshold = bankSize/4` was **defined and referenced nowhere**, and was
**DELETED 2026-10-01** (item 133) — a declared threshold nothing reads is
indistinguishable, to a later reader, from a mechanism that exists. The pre-fill it
named never existed, and its absence costs only latency: the DEMAND path answers
every miss correctly. Without it the window is rebased
upward on overflow and nothing brings the bank BELOW back until SP itself
descends — which is how SP came to sit seven words above the window base with
568 words above it and none below.

## Accesses the window does not cover — served from the spill region

**Added 2026-10-01, status item 133.** The window follows SP (and VP while
VP <= SP). Every other stack address is outside the rotation controller's view:

| read / write address | instructions | who walks with it |
|---|---|---|
| AR | `ldmi` / `stmi` | `Native.rdIntMem`/`wrIntMem`, `int2extMem`/`ext2intMem` — `f_athrow`, both of the collector's own-stack root scans, `JVMHelp.trace`, `RtThreadImpl`, the RT scheduler |
| `vp0..3`, `vpadd` while VP > SP | `ld0..3`, `ld` / `st0..3`, `st` | `f_athrow`'s tail after `setSP` |

Before the fix, a miss on any of these read the MUX default, 0, and a write was
dropped, with no fault. Measured: `jvm.DeepIntMem` (the primitive), `jvm.DeepGc`
(a live object held only in an evicted frame was COLLECTED, and the run went on
to print "DeepAll done" having silently skipped a test, because the same scan
also lost `DeepAll.main`'s own array) and `jvm.DeepThrow` (`f_athrow`).

**Rotation cannot serve them** — two attempts were measured worse and reverted.
The target is one bank from the active one, so a word several banks down is
never reached; and an upward rotation zero-fills, which is wrong once the window
has been dragged below SP for a walker.

**So the word is served where it lives, and the window does not move.** A second
small controller beside the rotation FSM (`ArState`) moves ONE word between the
spill region and a register through the stack DMA (`StackCacheDma.single`):

- **read** (`selRda < 6` and the address misses): detected in the decode cycle
  and stalled there exactly like a rotation, so the instruction retries once the
  word is in `rdAroundData` — which the read MUX returns when no RAM hits.
- **write** (`selWra < 6` and the delayed write misses): caught in the cycle the
  write happens (`wrEnaDly`), which is when its data exists, and parked. The
  stall that raises freezes the NEXT instruction, as a rotation would.

It is coherent because a word no bank holds has its current value in the spill
region — a bank is spilled whenever it is evicted dirty — and a word a bank does
hold is never served this way. ORDER: a parked write goes first, before any
rotation, flush or read, so a later fill or read of that address sees it; a read
yields to a rotation; a flush yields to a read. `gcFlushDone` also waits for a
parked write.

Cost: nothing for an access the window covers (`DoAll`, maxSp 216: 0 accesses
served). A served access is one DMA word, and the window does not move.
`jvm.DeepAll` serves 1,149 reads and 9 writes; the simulation prints the counts
(`aroundReads`/`aroundWrites`).

Hardware (2026-10-02, Wukong DDR3, timing met): `jvm.DeepAll` 4/4 and `DoAll`
68/68; the pre-fix RTL on the same board with the same image fails exactly as
the simulation does. Area on that build: +277 LUTs, +123 registers.

## A stall must apply every effect exactly once

A rotation, or a word served from the spill region, stalls the pipeline with the
instruction `I_T` frozen in decode — and the registered controls of `I_{T-1}`
already latched. Those take effect in the first stall cycle (`rotBusyDly` is
still low) and must NOT take effect again. The stack stage enforces that for its
own registers with `rotBusyDly`. Until 2026-10-02 nothing enforced it for the
rest: DecodeStage HELD its registered commands through the stall, so the memory
controller re-issued a held `stmwa`/`stmra`/`stmwd`/`putfield`/... in every stall
cycle, the compute unit re-fired, and the bytecode fetch dropped `stjpc`'s write
in its own (frozen) cycle and re-applied it later with the wrong A. `int2extMem`
is `stmwa; ldmi; stmwd` with the `ldmi` stalling, so the RT scheduler's stack save
wrote the stack over the program image (item 160). Commands now clear during a
stall, and `jpc_wr` lands regardless of the freeze. Guard:
`DecodeStrobeStallTest`.

## The region's edge

Each core owns `spillWords` (`stackRegionWordsPerCore`, 8192) of spill region.
SP is held inside it by `spOv` (EXC_SPOV at `64 + spillWords - 16`). Two more
paths reach memory and both now respect it:

- **An AR or local address past the region** is not served — a write-around
  there would land in the next core's stack. It raises EXC_SPOV through `spOv`
  instead (a one-cycle pulse OR'd into the sticky `spOvReg`, so `Sys` sees an
  edge).
- **The last bank straddles the edge.** Banks sit at `64 + 192k`, and 192 does
  not divide 8192: the bank at [8128, 8320) straddles the end at 8256. A whole
  bank spill wrote 64 words past the region — into the base of the next core's
  stack, or, for core 0 at the top of memory, wrapped to the bottom. No overflow
  was needed: SP anywhere in [8128, 8240) is legal. Every bank transfer (spill,
  fill, flush) is now clipped at the edge, and a bank wholly past it (only an SP
  overshoot past the spOv limit can put one there) moves nothing.

`spillWords = 0` (an explicit `spillBaseAddrOverride`, as the BRAM simulations
use) means the extent is unknown; there is no edge then.

Guards: `jop.pipeline.StackCacheArAccessTest` — served read and write, the edge
fault, the straddling bank — each proved red against the old RTL;
`StackCacheDmaFormal` adds "a single-word transfer never writes a bank", proved
able to fail by mutation.

## Consequences for the garbage collector

1. **A core scanning its OWN stack is correct** with the cache — since
   2026-10-01. The old text here said it was correct "because `rdIntMem`
   addresses reach `smuxSignal` and trigger rotation"; that was false (see the
   correction under Rotation), and `jvm.DeepGc` measured the consequence: a
   collection started ~70 frames deep collected an object that only an evicted
   frame referenced. Words outside the window are now served from the spill
   region one at a time, without moving the window.

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
