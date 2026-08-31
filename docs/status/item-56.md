# Item 56 — WBNI: derive the hardware configuration from the application, instead of picking a preset

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 56](../current-status.md#item-56).

---

**Raised 2026-08-23.** Build the core for the program it will run: analyse the
Java application, work out what it actually needs, emit a `JopConfig`, then
build the FPGA. Light on doubles, leave the DCU out. The flow would be

```
develop on host JDK/sim -> analyser -> .jop + JopConfig for this target
                        -> FPGA build -> remote debug if needed
```

**This is worth recording because most of the machinery already exists.**

- **JOPizer already walks the whole application.** It is the linker: it visits
  every class and method, so the bytecode usage and the method-length
  distribution are both in its hands already. It emits `code_length` per method
  in the `.jop.txt` dump — that is the input the method-cache geometry needs, and
  it is what `docs/architecture/tuning-guide.md` already tells you to grep.
- **`BytecodeConfig` already encodes which CUs may legally be dropped.** Every
  one of the 32 entries carries an `ImpConstraint`: `Asm` (JOPizer keeps the
  bytecode), `JavaOk` (a microcode `_sw` handler exists), `NoMicrocode` (only
  Java or hardware). That is the analyser's safety table, already written and
  already enforced — `bc=double:mc` is refused today with "dadd: mc is invalid —
  no SW handler exists".
- **The knobs are already CLI-addressable**: `bc=<key>:<impl>`, `mcache=`,
  `l2sets=` on `JopTopVerilog`.
- **The measurement harnesses already answer the sizing questions per app**:
  `MethodCacheSweepSim` counts misses per geometry, `ScaleL2` sweeps L2 capacity,
  and `docs/analysis/wukong-utilization-sweep.md` holds the per-feature LUT
  costs.
- **[Item 52](#item-52) is the same idea pointing the other way** — generate the
  Java tools' config FROM the preset. Both want one source of truth instead of
  hand-copied constants; doing either should consider the other.

**A manual proof of concept exists.** On 2026-08-23, by hand: the benchmark set
is integer, so the DCU is dead weight; dropping it on a 4-core Wukong freed
~17,240 LUTs and bought the 64-block method cache, taking Kfl's miss rate from
16.6 % to 0.1 % and timing from -0.043 ns to **+0.069 ns MET**. That is exactly
what the analyser would have concluded, and it took a day of measurement to
reach by hand.

**What is missing:** JOPizer emits no bytecode histogram (nothing in
`java/tools/src` counts opcodes), there is no dynamic frequency data, and
nothing emits a config.

**The traps, which are the reason this is an item and not a weekend:**

- **Static presence is not need, and static absence is not safety.**
  [Item 17](#item-17) is precisely this failure: the `needs*Compute` predicates
  under-approximated CU reachability and cost 10 JVM tests (66 -> 56) before
  being reverted. An analyser that concludes "no doubles" from the application's
  own bytecodes can be wrong via library code, `JVMHelp`, GC and exception
  paths.
- **Frequency, not presence.** One `dmul` on an error path should not buy a DCU;
  one in a hot loop should. A boolean analysis gets this wrong in both
  directions, so it needs counts — which means a profile or at least a call-graph
  weighting, not a grep.
- **Dropping a CU is only safe where the fallback is both legal AND covered.**
  `ImpConstraint` gives legality. Coverage is [item 18](#item-18), and it is
  uneven: `lmul_sw` went years unexecuted with a `require` checking the wrong
  predicate. A generated config must be validated by the JVM suite, not just
  elaborated.
- **Costs are per-part.** The LUT figures above are XC7A100T. A Cyclone IV LE
  and an ECP5 slice are not the same currency, so the cost table has to be
  per-family or the analyser will make confident wrong trades.
- **It ties the bitstream to the application.** Fine for a fixed embedded
  deployment, which is JOP's normal case, but it means changing the app can mean
  re-running P&R — and the fallback of "build the generous preset" must stay
  available.

**FIRST STEP DONE 2026-08-23 — `OpcodeStats`, a JOPizer visitor.** Every link
now writes `<app>.jop.stats.txt`: the method-length distribution, the blocks
each method would consume at 128/256/512/1024 B, and a per-opcode histogram.
A report, not a config generator, so it cannot make a wrong trade.

It validates against numbers reached by other means: JbeBench median **9 B** and
max **882 B** match what was previously grepped out of `.jop.txt`, and the block
table independently confirms the 512 B geometry policy from the application side
— 962 of 963 methods fit one 512 B slot, against 890 at today's 128 B where 73
methods burn extra tag slots and the worst needs 7. The sweep reached 512 B from
MISS COUNTS; this reaches it from the LENGTH DISTRIBUTION.

**It also immediately demonstrated why the analyser is the hard part.** JbeBench
contains double opcodes — one `dsub`, four `ddiv`, ten `dcmpl`, several
conversions — in linked library code the benchmarks never execute. A naive
"does the application mention doubles?" rule would have kept ~19,800 LUTs of DCU
at four cores, when dropping it is measurably correct (DoAll 66/66 without it).
**Static presence is not need. Absence is the only trustworthy signal.**

Deliberately emits RAW COUNTS and no conclusion: deciding what may replace a
bytecode needs `BytecodeConfig`'s `ImpConstraint` registry, which is in Scala,
and copying it into the Java tools would create exactly the drift item 52
tracks.

**Remaining, and it is the bulk of the work:** a FRAMEWORK for measuring the
existing and future hardware set — not a one-off analyser. The pieces that exist
today (`MethodCacheSweepSim`, `ScaleL2`, `DoAppPerf`, the utilization sweep,
now `OpcodeStats`) were each built for one question and are driven by hand.
Turning "which CUs does this application need" into an answer means running a
matrix of configurations against an application and comparing, repeatedly, as
boards are added. **Preference: build it in Java** — the toolchain, JOPizer and
the applications are already Java, so the analysis should live with them and
drop to Scala only where it must (the config registry, elaboration).

Note what `OpcodeStats` still cannot do, and the framework must: it is static,
so it cannot distinguish an error path from an inner loop; and it counts
everything JOPizer linked, not what is reachable. Both need execution counts,
which is a simulator or hardware-counter job, not a linker job.
