# Item 45 — ONE unidentified register is read before it is written; the other ~401 look benign

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 45](../current-status.md#item-45).

---

**RESCOPED 2026-08-19 after the sweep. The "~405 registers" framing is not what
the evidence supports, and two of my own diagnoses of it were wrong.**

Across five seeds x 471 tests with X-state randomised, the register class
produced **zero** failures. The one reproducible symptom is
`BytecodeFetchStage: JumpTable integration` reading `entries[0xEC]` — an
undefined bytecode — instead of NOP's `entries[0x00]`.

What that symptom is NOT, both checked rather than assumed:

- **Not `jpc`.** `BytecodeFetchStage.scala:122` — `Reg(...) init(0)`. It is not
  in the 402 at all. Item 29 named it as a suspect; it is exonerated.
- **Not an uninitialised memory.** The JBC RAM is a `Mem`, and the grep behind
  the 402 count matches `Reg(` only, so I proposed the real exposure was
  uninitialised `Mem` contents. Wrong: `BytecodeFetchStageTest.createDut` pads
  the test bytecode to the full 2048 bytes and passes it as `jbcInit`, so that
  memory is fully initialised at elaboration.

**ROOT CAUSE FOUND 2026-08-19, and it is not a register.** Instrumenting the
failing cycle (`simPublic` on the whole path) shows the JBC RAM reading back
garbage with the write port held inactive and no write ever issued:

| X-state | `jbcWordDataRaw` | bytecode | `jpaddr` |
|---|---|---|---|
| zeroed | `0x00a76000` — the `init()` contents | `0x00` (NOP) | `0x226` ✅ |
| random | `0xe03e8376` — garbage | `0x76` | `0x74c` ❌ |

**Verilator's randomising x-initial discards `Mem` initialisation.** The
`jbcRamWord.init(...)` is simply thrown away. So the "undefined bytecode" is a
random RAM word, and the index is `0x76`, not the `0xEC` recorded since item 29.

**No FPGA behaves this way.** Block RAM contents come from the bitstream and
survive any reset, soft or otherwise. So this failure is a SIMULATOR ARTEFACT,
not a hardware hazard — which is why six boards reset cleanly while the sweep
kept flagging this test.

**Consequences, and they matter beyond this item:**

1. **The register class now has ZERO demonstrated offenders.** The single
  failure across five seeds was this artefact. The other ~401 are unimplicated
  by any evidence collected so far.
2. **`JOP_SIM_XINIT=random` is NOT a faithful model of a soft reset.** A soft
  reset leaves registers holding stale values but leaves initialised memories
  intact. Randomised x-initial additionally destroys memory init, which cannot
  happen in hardware, so the sweep OVER-REPORTS. Any future use of it must
  discount memory-init failures.
3. The pinned seed in `BytecodeFetchStageTest` is still worth keeping as an
  alarm for `--x-initial 0` being removed, but its comment described the wrong
  mechanism and has been corrected.

**Five wrong diagnoses preceded this**, all killed by experiment rather than
argument, and recorded so the path is not walked again: `jpc` (has `init(0)`);
uninitialised `Mem` contents (the test pads to all 2048 bytes); `jbcByteSelect`
(adding `init` changed nothing); floating `jbcWrEn/Addr/Data` inputs (driving
them changed nothing); driving those inputs *before* `forkStimulus` (also
nothing). A sixth near-miss: the first "control" run used plain `SimConfig`
rather than `JopSimDefaults.config`, so neither arm had the flag and both
showed garbage — a comparison that controlled nothing.

**Right next step, and it is small.** Run the failing case with a waveform and
trace what feeds the JumpTable index:

```sh
SIM_WAVE=1 JOP_SIM_XINIT=random sbt 'testOnly jop.pipeline.BytecodeFetchStageTest -- -z "JumpTable integration"'
```

Then add `init()` to that one register — not to 402 of them, which would cost
fabric for no demonstrated benefit.

**What would change this verdict.** The sweep covered the UNIT suite only. The
long system sims were not swept, and item 30's `clazzinit()` hang lived exactly
there. Sweeping `JopJvmTestsBramSim` under randomised X-state is the test that
would either find more offenders or justify closing this item.

**Why it still matters at all, given six boards reset cleanly.** Configuration
zeroes every flip-flop; the runtime reset (item 48) does not. Empirically the
boot path never reads one of these, on any board — but that is one path, and CI
can no longer see the class since `--x-initial 0` became the default.


**Opened 2026-08-18, as the residue of items 29/30/32.** `--x-initial 0` makes
the simulator agree with an FPGA at power-up, which is what stopped CI being a
random number generator. It does not make the design correct: a register whose
value matters before anything writes it is a real defect, and the FPGA merely
masks it by happening to power up at zero.

```sh
grep -rE "= *Reg(Next)? *\(" spinalhdl/src/main/scala/jop/ | grep -v init(
```

counts **~405**. Not all need a reset — most are written before they are read,
and adding `init()` to a deep pipeline register costs fabric for nothing. The
ones that matter are those an X-state run can demonstrably reach, and there is
now a cheap way to find them:

```sh
JOP_SIM_XINIT=random sbt "testOnly jop.core.* jop.io.* jop.pipeline.* jop.memory.*"
```

Two are already named by the closed items: whatever the JBC RAM / `jpc` hold
after reset (item 29 — the test read an *undefined* bytecode 0xEC), and
whatever `clazzinit()` walks into on the baseline sim (item 30).

**Do not treat a green CI as evidence this is done.** CI now zeroes X-state by
construction, so it can no longer see this class at all. That is the correct
trade — a regression detector should not be a fuzzer — but it does mean this
item needs deliberate sweeps, not observation.

**First sweep run, 2026-08-19: no NEW offenders.** Five seeds (1, 20260818,
-748081925, 360571106, 99991) of the full unit suite under
`JOP_SIM_XINIT=random`, 471 tests each:

| result | reading |
|---|---|
| `BytecodeFetchStage: JumpTable integration` fails on all five | the KNOWN item 29 offender (JBC RAM / `jpc`). Its seed is pinned at 360571106, so this is **one** data point repeated five times, not five |
| `CacheMigResetSim` "early release" failed on one seed | a fault in that TEST, not the RTL — see below. Now skipped under random X-state |
| everything else | 470/471 pass on every seed |

So the demonstrated-offender count is still **one**. That is real evidence the
other ~401 are written before they are read on the paths the unit suite covers,
and it is NOT proof: the suite is not the whole design, and the long system sims
were not swept.

**Two process faults from the first attempt, both worth keeping.**

*The probe hung for 8.4 hours and nobody noticed.* `CacheMigResetSim` used
`waitSamplingWhere(req.ready)` with no bound. Under randomised X-state that wait
never completed: one `doSim` burned 30,096 s of CPU at 100 % while the parent
sbt sat at 0 %, and the sweep never got past its first seed. A hang teaches
nothing; a bounded wait that fails names the signal. `acceptOrFail` now bounds
all three call sites and the sweep script has `timeout 1500` per seed. Same
lesson as the `pgrep` waiters and the `SWEEPDONE` waiters — **anything that
waits needs a bound** — arriving three times in one session in three disguises.

*A negative-result test cannot run on a random baseline.* The "early release
corrupts" case asserts that something goes WRONG, which needs a deterministic
starting state; under random X-state the early release sometimes lands clean
(seed 20260818) and the test then reports a failure saying nothing about the
hold length — a false positive fed straight into the sweep this item depends on.
It now `assume`s zeroed X-state and is skipped otherwise.

**Also corrected:** the hang was initially blamed on `LruCacheCore` wedging
under random X-state. With the waits bounded, `req.ready` asserts normally on
every seed tested and no accept-timeout is ever recorded, so that explanation is
unsupported. The original hang has not been reproduced and its cause is not
established.
