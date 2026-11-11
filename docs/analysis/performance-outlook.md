# JOP on a custom chip, and how to make it faster

Written 2026-11-11 to answer three questions:

- How fast could JOP clock as a custom chip?
- How would it then compare with RISC-V and ARM cores?
- What would make it faster, first while keeping the timing predictability JOP
  was designed for, then without it?

Every figure says whether it is **measured** or **estimated**. The commands
behind each one are in [Reproducing the numbers](#reproducing-the-numbers).

## Summary

- **Clock.** On the i9+'s XC7A50T (-1 grade), JOP tops out near 88 MHz, and
  every part of the pipeline misses 100 MHz by a similar margin (measured). As a
  custom chip in a current process, the same RTL would plausibly run at
  0.5-1 GHz (estimated). That is the class of embedded cores such as the
  Cortex-M7.
- **Work per clock.** On Kfl and UdpIp, JOP does about as much per clock as a
  single-issue RISC core running compiled Java. It does 1/2.6 of a two-issue
  Pentium running compiled Java, and 1/51 of a Zen 4 running HotSpot's JIT. The
  first two comparisons come from jopdesign.com's table; the Zen 4 figure was
  measured here.
- **Where the cycles go.** Method calls take about half of Kfl's cycles, a
  third of UdpIp's and more than a quarter of Lift's. A call and its return cost
  about 130 cycles: roughly 95 microinstructions and four main-memory reads.
  The shares are estimated from counts; the 129-cycle call is measured.
- **Two corrections to the status file.**
  - The perf counters' `idle/direct` category is the microcode's simple reads,
    most of them a call's, at 8.1-8.6 cycles each on all three benchmarks. It is
    not the write waits [item 55](../current-status.md#item-55) blamed. Those
    writes are worth 3.5-5 % of the cycles.
  - [Item 54](../current-status.md#item-54)'s statics are 1.6-2.3 KB in every
    image checked, so one block RAM holds them all.
- **Keeping predictability**, the two large levers are cheaper calls and statics
  in on-chip RAM. Together they would take Kfl to about half its cycles, UdpIp to
  two thirds and Lift to three quarters (estimated). Calls are now
  [item 179](../current-status.md#item-179).
- **Giving it up**, the lever is translating bytecode to register code at run
  time, which is a JIT. On the same Zen 4, HotSpot's JIT does 41x the work per
  clock of HotSpot's own interpreter (measured), and JOP is in effect an
  interpreter built in hardware.

## 1. JOP on a custom chip

### 1.1 What JOP clocks at now (measured)

`colorlightDbI9PlusSdram` runs on the i9+ (XC7A50T-1, a 28 nm part) at 80 MHz,
built with `CLOSURE=1`, with a worst slack of +0.208 ns. Built for 100 MHz
(`build/colorlightDbI9PlusSdram-100`), it misses by 1.269 ns, which puts the
ceiling near 88 MHz. The failing paths end all over the core, all within 0.3 ns
of each other:

| Where the path ends | Worst slack at 100 MHz |
|---|---|
| Stack stage (`pipeline/stackStg`) | −1.269 ns |
| Microcode fetch (`pipeline/fetch`) | −1.265 ns |
| Memory controller, bytecode fill (`memCtrl`) | −1.240 ns |
| Stack-cache DMA (`stackCacheDma`) | −1.068 ns |
| Decode (`pipeline/decode`) | −1.034 ns |
| Bytecode fetch (`pipeline/bcfetch`) | −0.975 ns |

So the pipeline is balanced. No single path is worth fixing; the clock is set by
how much logic each of the four stages holds. The worst path, in the stack
cache's bank logic, has 13 levels of logic, and 67 % of its delay is routing.

The core itself (`JopCore`, after routing):

| Block | LUTs |
|---|---|
| Pipeline | 2,772 |
| Memory controller and its caches | 2,966 |
| — method cache | 1,287 |
| — object cache | 485 |
| — array cache | 416 |
| Stack-cache DMA | 232 |
| UART | 102 |
| **Total** | **6,109** |

The core also has 5,073 flip-flops, 3 RAMB36 and 8 RAMB18.

### 1.2 A RISC-V core on the same silicon

VexRiscv, also written in SpinalHDL, publishes figures for its "full"
configuration: RV32IM with 4 KB instruction and data caches, 1.21 DMIPS/MHz and
2.30 CoreMark/MHz. It reaches 199 MHz on Artix-7 in 1,840 LUTs.

That figure is the core alone, on the fastest speed grade. The -1 grade is
roughly a quarter slower (estimated), which gives about 150 MHz: 1.7x JOP's clock,
from a core with under a third of JOP's LUTs. A five-stage RISC pipeline simply
does less per stage, and the ratio would carry over to a custom chip.

### 1.3 From FPGA to a custom chip (estimated)

1. **FPGA to standard cells.** In the same process, standard-cell logic runs 3-4x
   faster than FPGA logic (Kuon and Rose, "Measuring the Gap Between FPGAs and
   ASICs", IEEE TCAD, 2007). JOP on the fastest grade would be about 115 MHz,
   which makes 350-450 MHz in a 28 nm standard-cell process.
2. **28 nm to a current process.** A current process adds roughly 1.5-2x for a
   simple core. This is an assumption, not a measurement.
3. **Result.** About **0.5-1 GHz for the RTL as it stands**. A VexRiscv-class
   core in the same process would clock about 1.7x higher.

For scale:

| Core | Clock | Chip |
|---|---|---|
| Cortex-M7 | 1 GHz | NXP i.MX RT1170, 28 nm |
| SiFive U74 | 1.5 GHz | StarFive JH7110, 28 nm |
| Cortex-A55 | about 2 GHz | |
| Large out-of-order cores | 5 GHz and more | |

A faster clock buys less than it seems to.

- Memory stalls are 30-35 % of JOP's cycles at 100 MHz on SDR SDRAM.
- At 1 GHz the same DRAM costs ten times as many cycles. With the same misses,
  Kfl would run only 2.7x faster than at 100 MHz.

So a JOP chip needs its code, statics and class structures in on-chip SRAM
before a faster clock pays off. All three are fixed when the program is linked.

### 1.4 Work per clock

Kfl and UdpIp are JOP's standard application benchmarks, which this repo runs as
JbeBench. The figures below are their geometric mean in iterations per second,
divided by the clock in MHz:

| System | Per MHz | × JOP | Source |
|---|---|---|---|
| JOP, this repo: Wukong SDR, 100 MHz, 8 KB method cache | 107 | 1 | measured, [`perfcnt/2026-08-20-wukong-sdr-mcache8k.txt`](../measurements/perfcnt/2026-08-20-wukong-sdr-mcache8k.txt) |
| JOP, original: Cyclone, 100 MHz | 104 | 1.0 | jopdesign.com |
| aJ-100 Java processor, 103 MHz | 93 | 0.9 | jopdesign.com |
| ARM720T with a JIT (EJC), 74 MHz | 71 | 0.7 | jopdesign.com |
| 266 MHz Pentium, Java compiled ahead of time (gcj) | 276 | 2.6 | jopdesign.com |
| Zen 4 at 4.98 GHz, OpenJDK 21, interpreter only | 132 | 1.2 | measured here |
| Zen 4, OpenJDK 21, C1 JIT only | 2,596 | 24 | measured here |
| Zen 4, OpenJDK 21, full JIT (C2) | 5,423 | 51 | measured here |

The jopdesign.com rows are from its table "Application benchmarks on different
Java systems" (<https://www.jopdesign.com/perf.jsp>). The same table lists two
more systems:

- Sun's KVM interpreting on a 50 MHz Nios II: 0.5 per MHz.
- Java translated to C on a 100 MHz MicroBlaze: 38 Kfl per MHz against JOP's 166.
  The table marks this result as preliminary.

What this means against RISC-V or ARM, for Java:

- **A microcontroller interpreting Java:** JOP does about 100x more per clock
  (the Nios II row).
- **A single-issue RISC core running compiled Java:** JOP was ahead per clock.
  The ARM7 managed 0.7x JOP, and the MicroBlaze a quarter. Since a VexRiscv-class
  core clocks 1.7x higher, the two come out roughly even.
- **An in-order application core (Cortex-A55, SiFive U74) with a JIT:**
  estimated at 3-5x JOP's work per clock, from the Pentium row and the C1-to-C2
  gap. At about twice the clock, that is 6-10x per core.
- **A large out-of-order core with a JIT:** 51x per clock (measured), and about
  250x per core against a 1 GHz JOP.
- **C, Rust, or anything that is not Java:** JOP does not compete.

### 1.5 Where JOP still makes sense

Not size: JOP's 6,109 LUTs are three times VexRiscv's 1,840 on the same FPGA.

JOP's case is predictability. Every bytecode has a bounded, known cycle count;
method-cache misses happen only at a call or a return; and nothing is
speculative. Worst-case execution-time (WCET) analysis for hard real-time Java
needs exactly that, and a JIT on an out-of-order core cannot give it.

Hardware Java was tried commercially for throughput: Sun's picoJava, aJile's
aJ-100 and ARM's Jazelle. JIT compilers on RISC cores won, and ARM's Cortex
cores keep only a stub of Jazelle.

## 2. Where JOP's cycles go

Three sources, combined:

- **What runs:** each benchmark loop (`LoopKfl`, `LoopUdpIp`, `LoopLift`) run for
  exactly 10,000 iterations in JopSim, which counts the bytecodes executed.
- **What each operation costs:** read off the microcode in `asm/src/`.
- **The memory stalls:** the hardware counters, from the capture in 1.4's first
  row.

### 2.1 What one iteration executes (measured, JopSim)

| Per iteration | Kfl | UdpIp | Lift |
|---|---|---|---|
| Bytecodes | 1,205 | 2,351 | 1,258 |
| Calls (each with its return) | 22.9 | 38.0 | 12.0 |
| Static field accesses | 75.3 | 79.1 | 12.1 |
| Object field accesses | 0 | 65.0 | 126.0 |
| Array accesses | 15.7 | 120.0 | 66.0 |
| Branches | 203.8 | 212.1 | 149.6 |
| Bytecodes that only move stack data (loads, stores, constants) | 690 (57 %) | 1,323 (56 %) | 695 (55 %) |
| Hardware cycles (Wukong SDR, 100 MHz) | 6,128 | 14,296 | 5,545 |

The hardware cycles are the 2026-08-20 capture's. Re-measured at de2dfe4 on
2026-11-11 they were 6,134, 14,465 and 5,539
([`2026-11-11-wukong-sdr-recheck.txt`](../measurements/perfcnt/2026-11-11-wukong-sdr-recheck.txt)),
so nothing below changes.

JOPizer's static profile of the Kfl image counts 864 methods. The median is 9
bytes of bytecode and the 90th percentile 94 bytes, so a call usually costs more
than the method it calls.

### 2.2 What the operations cost (from the microcode)

- **A call and its return.**
  - `invokestatic` runs about 73 microinstructions, through `invokestatic`,
    `invoke_main`, `invoke` and `invoke_vpsave` in `asm/src/jvm_call.inc`.
    `return` runs about 21.
  - Between them they make four simple memory reads and two method-cache
    lookups. The reads are the constant-pool entry, two words of the method
    structure, and on return the caller's structure.
  - At 8.1 stall cycles a read (2.3), that comes to about 130 cycles. NetBench
    measured 129 for an empty static call on the i9+ (2026-11-10).
- **A static access.** Five microinstructions (`getstatic` and `putstatic` in
  `asm/src/jvm.asm`) plus one memory access, counted under `statics`. JOPizer
  puts the static's address in the operand. The access stalls 10-12 cycles on
  average.
- **A branch.** Four microinstructions, taken or not.
- **A stack-only bytecode, or an ALU operation.** One microinstruction.

From those counts and costs, Kfl's cycles divide like this. The stall terms are
measured; the rest is estimated:

| Kfl, per iteration | Cycles | Share |
|---|---|---|
| Calls and returns: 22.9 × ~130 | ~2,980 | ~49 % |
| Statics: 75.3 × 5, plus 751 cycles of stall | ~1,130 | ~18 % |
| Branches: 203.8 × 4 | 815 | ~13 % |
| Stack-only bytecodes: 690 × 1 | 690 | ~11 % |
| Arrays: their microcode, plus 245 cycles of stall | ~370 | ~6 % |
| ALU operations: ~172 × 1 | ~170 | ~3 % |
| **Sum** | **~6,150** | against 6,128 measured |

The same arithmetic puts calls at roughly 35 % of UdpIp's cycles and 28 % of
Lift's. UdpIp spends more on arrays, and Lift more on object fields.

### 2.3 What `idle/direct` is

The perf counters' `idle/direct` category counts stalled cycles in the memory
controller's states 0-2 (`catOf` in `Sys.scala`):

- `IDLE`, which is never busy;
- `READ_WAIT` and `WRITE_WAIT`, which hold the microcode's simple reads and
  writes (`stmra`, `stmrac`, `stmraf`, `stmwd`).

`putfield` and `iastore` wait in `HANDLE_DATA_WAIT`, which is counted as
`element`. `putstatic` waits in `LAST`, counted as `statics`. Their write waits
were never in `idle/direct`.

Each bytecode's microcode issues a fixed number of simple reads:

| Bytecode | Simple reads |
|---|---|
| `invokestatic` | 3 |
| `invokevirtual`, `invokespecial` | 4 |
| `invokesuper` | 5 |
| `invokeinterface` | 6 |
| Each return, `ldc`, `arraylength`, `instanceof` | 1 |

Dividing `idle/direct` by the reads each benchmark makes:

| | Simple reads per iteration | Share from calls | `idle/direct` cycles | Cycles per read |
|---|---|---|---|---|
| Kfl | 93.2 | 98 % | 758 | 8.1 |
| UdpIp | 240.1 | 69 % (the rest mostly `ldc`) | 2,060 | 8.6 |
| Lift | 68.1 | 78 % (the rest mostly `arraylength`) | 578 | 8.5 |

Three different workloads give the same 8.1-8.6 cycles per read. So `idle/direct`
is these reads: most of them a call's, the rest constant-pool and array-length
reads.

The writes [item 55](../current-status.md#item-55) blamed sit elsewhere and are
smaller. Kfl makes 22.9 writes per iteration, UdpIp 56.0 and Lift 34.0, each
stalling 8-10 cycles. That is 3.5 %, 4.0 % and 5.0 % of the cycles, which is
all a write buffer could recover.

### 2.4 Statics

[Item 54](../current-status.md#item-54) asked three questions before any design:
how big the static region is, whether its cost is latency or bandwidth, and what
to do on SMP.

**Size (measured from link maps).** The whole region is 1.6-2.3 KB in every
image checked:

| Image | Static fields | Region |
|---|---|---|
| DoAll | 528 | 2.3 KB |
| DeepAll | 376 | 1.6 KB |
| NetTest | 498 | 2.0 KB |
| SmpGcTest | 402 | 1.7 KB |
| LoopKfl, LoopUdpIp, LoopLift | 552, 421, 404 | 2.3, 1.7, 1.7 KB |

One RAMB36 (4 KB) holds any of them, with no cache and no tags.

**Latency, not bandwidth (measured).**

- Kfl and UdpIp make 75-79 static accesses per iteration, each stalling 10-12
  cycles.
- Only 85, 61 and 43 distinct statics are touched (Kfl, UdpIp, Lift). Ninety
  per cent of the accesses go to 27, 15 and 4 of them.
- 11-22 % of the accesses are writes.

**SMP is still open.** Statics are shared, mutable state, so they need one shared
RAM. Fixed time-slot arbitration would keep that predictable.

## 3. Faster, keeping predictability

Every change here has a fixed cost, or one a static analysis can bound. The gains
are estimated from section 2, one change at a time.

| Change | Kfl | UdpIp | Lift |
|---|---|---|---|
| 1. Calls from ~130 to ~30 cycles | 1.6x | 1.36x | 1.28x |
| 2. Statics in on-chip RAM, ~2 cycles each | −16 % | −8 % | −3 % |
| 3. Branches from 4 cycles to 2 | −7 % | −3 % | −5 % |
| 4. Writes posted to a bounded buffer | −3.5 % | −4 % | −5 % |
| 5. Half the stack-only bytecodes folded away | −6 % | −5 % | −6 % |

Changes 1 and 2 together would take Kfl to about half its cycles, UdpIp to two
thirds and Lift to three quarters.

1. **Cheaper calls.** This is the largest lever, tracked as
   [item 179](../current-status.md#item-179). Three ways to get there:
   - **Method structures and the constant pool in on-chip memory.** They are
     read-only and fixed at link time, so they are as analysable as the method
     cache, and the four reads a call makes go away.
   - **A hardware call/return sequencer.** The microcode saves and restores the
     frame (sp, pc, vp, cp, mp) one register per microinstruction; hardware can
     do them all at once.
   - **Inlining small static, private and final methods at link time, in
     JOPizer.** The median method is 9 bytes. Inlining grows the code, and the
     method cache's block count has to absorb that (see the
     [tuning guide](../architecture/tuning-guide.md)).
2. **Statics in on-chip RAM** ([item 54](../current-status.md#item-54)). One block
   RAM holds all of them (2.4).
3. **Cheaper branches.** Resolve the target in bytecode fetch, so a branch costs a
   fixed 2 cycles.
4. **A bounded write buffer** ([item 55](../current-status.md#item-55)). A store
   would then cost at most the time to drain a full buffer. It needs
   read-after-write forwarding and the SMP ordering points.
5. **Folding by fixed rules.** Decode common sequences as one step: two loads, an
   add and a store, or a load followed by a compare-and-branch. The code alone
   decides what folds, so timing stays analysable.
6. **Keep the object and array caches across calls.** Both are flushed at every
   call and return. The switches are `JopMemoryConfig.ocacheInvalOnStidx` and
   `acacheInvalOnStidx`, both true by default for WCET safety. Turning them off
   stays analysable only if the WCET analysis follows the caches across calls.
   It would help field- and array-heavy code like Lift and UdpIp. It has not
   been measured on hardware.
7. **Hardware for the bytecodes that still run as Java calls.**
   - `i2b`, which hurts byte-handling code such as the network stack;
   - the reference stores, `putfield_ref` and its relatives;
   - `idiv` and `irem` on builds without the integer unit, which take about
     1,300 cycles in Java against about 36 in the unit.
8. **On a custom chip, put code, statics and class structures in on-chip SRAM,**
   and leave DRAM to the heap (1.3).

## 4. Faster, giving up predictability

Largest gain first.

1. **Translate bytecode to register code at run time: a JIT.**
   - It removes the stack-only bytecodes, turns static and field accesses into
     plain loads, keeps values in registers across bytecodes, and inlines calls
     speculatively.
   - On the lab's Zen 4, HotSpot's JIT does 41x the work per clock of HotSpot's
     own interpreter (5,423 against 132 per MHz), and JOP is in effect a
     hardware interpreter.
   - In hardware this means a translator that keeps a cache of translated code,
     as NVIDIA's Denver does. In software it means a RISC-V core with OpenJDK's
     JIT.
   - Most of the gap to RISC-V and ARM is here, but the result would no longer
     really be JOP.
2. **Speculative calls.** Predict the targets of virtual and interface calls,
   keep a stack of return addresses, and set up the frame before the target is
   confirmed.
3. **A general data cache.**
   - It would be set-associative, write-back and non-blocking, with prefetch for
     arrays. (The L2 already has MSHRs, which track several outstanding misses.)
   - Stalls are 30-35 % of cycles now, and each would cost ten times the cycles
     at chip clock rates.
   - Heap addresses aren't known statically, so any timing bound would be loose.
4. **Dynamic branch prediction.** Branches are 9-17 % of bytecodes, and a
   correctly predicted one would cost about one cycle.
5. **A deeper pipeline with interlocks.** The four stages are balanced (1.1), so
   one or two more could buy 1.5-2x the clock, at the price of variable stalls.
6. **Several bytecodes per cycle,** using a unit that maps stack slots onto
   registers.
7. **Direct object pointers instead of handles.** Following handles costs 1-7 % of
   cycles. Without them, though, a moving garbage collector needs forwarding
   pointers or read barriers, and its pauses get harder to bound.

## 5. First steps

- Turn `ocacheInvalOnStidx` and `acacheInvalOnStidx` off and run JbeBench on a
  board. No RTL change is needed.
- Measure a call and return cycle-accurately in simulation, including how much of
  the cost is the four reads, before choosing among change 1's three remedies.
- Design the on-chip static RAM for one core first. The SMP arbitration is the
  part still open (2.4).

## Reproducing the numbers

**Timing paths by stage.** On the 100 MHz build's routed checkpoint:

```tcl
open_checkpoint build/colorlightDbI9PlusSdram-100/vivado/build/post_route.dcp
foreach p [get_timing_paths -max_paths 5000 -nworst 1 -slack_lesser_than 1.5 \
             -group [get_clocks clk_100_sdr_clk]] {
  puts "[get_property SLACK $p] [get_property ENDPOINT_PIN $p]"
}
```

Then group the endpoints by the first four levels of their hierarchy.

**Core size.** `report_utilization -hierarchical -hierarchical_depth 4` on the
same checkpoint.

**Host JVM rows.**

1. Compile `java/apps/JbeBench/src` for the host without `DoAppPerf.java`,
   `Scale.java` and `ScaleL2.java`, which need JOP's I/O.
2. Replace `LowLevel.java` with one timed by `System.nanoTime()`:

   ```java
   package jbe;

   public class LowLevel {
   	public static final int FREQ = 0;
   	private static final long T0 = System.nanoTime();
   	public static int timeMillis() { return (int) ((System.nanoTime() - T0) / 1000000L); }
   	public static int timeMicros() { return (int) ((System.nanoTime() - T0) / 1000L); }
   	public static void msg(String msg) { System.out.print(msg); System.out.print(" "); }
   	public static void msg(int val) { System.out.print(val); System.out.print(" "); }
   	public static void msg(String msg, int val) { msg(msg); msg(val); }
   	public static void lf() { System.out.println(); }
   }
   ```

3. Run `java [-Xint | -XX:TieredStopAtLevel=1] -cp <classes> jbe.DoApp`, twice for
   each mode, and average.

The host was a Ryzen 9 PRO 8945HS (Zen 4) VM. Its clock under single-thread load
measured 4.95-4.99 GHz, from a chain of dependent adds that retires one per
cycle.

**JopSim profile.**

1. Build the images:

   ```sh
   make -C java JOP_PRESET=colorlightDbI9PlusSdram tools runtime
   make -C java/apps/JbeBench JOP_PRESET=colorlightDbI9PlusSdram APP_NAME=LoopKfl JOP_NAME=LoopKfl.jop
   ```

   Repeat the second command for `LoopUdpIp` and `LoopLift`.
2. Make one change to JopSim, in a scratch copy rather than the repo: **print
   `bcStat` after `runSimulation()`.** It holds the per-opcode execution counts
   JopSim keeps but does not print.

   The counts here came from a copy that also raised `MAX_MEM` to 8 MB, because
   until 2026-11-11 stock JopSim stopped with "writeMem: wrong address" on every
   image: it answered 0 for `IO_MEM_SIZE`, so the runtime put the heap 1 MB
   past the end of the image, beyond JopSim's 1 MB
   ([item 154](../current-status.md#item-154)). JopSim answers it now, and runs
   `LoopKfl` to `JVM exit!` in the same 12,051,089 instructions as that copy.
3. For the statics, add a hook in `getstatic()` and `putstatic()` that records each
   address. The hook sees about 5 more static accesses per iteration than the
   opcode counts do, for a reason not found. Section 2 uses the opcode counts, and
   the hook only the distinct addresses.

**Static region.** The `static` lines of each image's `.jop.link.txt` give each
field's address.
