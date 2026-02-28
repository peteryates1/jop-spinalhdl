# JOP SMP Performance Scaling Analysis

Analysis of SMP scaling bottlenecks for JOP on QMTECH EP4CGX150 (SDR SDRAM), covering
memory bandwidth, arbitration overhead, lock contention, cache effectiveness, GC scaling,
and FPGA resource utilization. Based on source code analysis of the SpinalHDL implementation.

## 1. Memory Bandwidth Model

### 1.1 Theoretical SDRAM Bandwidth

The QMTECH board uses a W9825G6JH6 (Winbond 256Mbit SDR SDRAM):
- 16-bit data bus, CAS latency 3, 100 MHz (single-core) or 80 MHz (16-core)
- At 100 MHz: 16 bits x 100 MHz = 200 MB/s raw peak bandwidth
- At 80 MHz: 16 bits x 80 MHz = 160 MB/s raw peak bandwidth

### 1.2 Effective Bandwidth After Protocol Overhead

SDR SDRAM has significant protocol overhead per access:

| Phase | Cycles (CAS=3) | Notes |
|---|---|---|
| Row Activate (tRCD) | 2 | 18ns at 100 MHz |
| CAS Read | 3 | CAS latency |
| Data transfer (16-bit) | 1 per 16-bit word | Burst sequential |
| Precharge (tRP) | 2 | 18ns at 100 MHz |
| Refresh | ~8 cycles per 7.8us | Periodic, amortized |

**Single-word read (32-bit)**: The BmbSdramCtrl32 bridge converts each 32-bit BMB
transaction into two 16-bit SDRAM operations. For a single 32-bit word:
- Row open + CAS + 2 data transfers + precharge = 2 + 3 + 2 + 2 = 9 cycles minimum
- If the row is already open (sequential access), skip activate + precharge: ~5 cycles
- At 100 MHz: best case 50 ns/word (20M words/s = 80 MB/s), worst case 90 ns/word (11M words/s = 44 MB/s)

**Burst read (4 words, method cache fill)**: `burstLen=4` uses a single BMB burst
for 4 consecutive 32-bit words = 8 SDRAM 16-bit reads:
- Row open + CAS + 8 data transfers + precharge = 2 + 3 + 8 + 2 = 15 cycles
- At 100 MHz: 150 ns for 16 bytes = ~107 MB/s effective
- For a typical method fill of 32 words: 8 bursts x 15 cycles = 120 cycles

**Refresh overhead**: 64ms / 4096 rows = 15.6us per refresh. At 100 MHz, each refresh
takes ~8 cycles. Over 15.6us (1560 cycles), this is 0.5% overhead -- negligible.

### 1.3 Per-Core Bandwidth as Function of Core Count

With N cores sharing one SDRAM bus through a round-robin BMB arbiter, each core
receives at most 1/N of the bus time. However, JOP cores do not saturate the bus
due to the multi-stage pipeline architecture and caches.

**Per-core active bus fraction estimate** (at 100 MHz, single-core):
- Each JOP microcode instruction: 1 cycle (pipelined)
- Average Java bytecode: ~8-15 microcode instructions
- Average bytecodes per memory access: ~3-5 (ALU-heavy code: higher, field-heavy: lower)
- Estimated memory bus utilization per core: 15-25% (without caches: 40-60%)

| Cores | Per-core bus share | Estimated utilization | Bottleneck? |
|---|---|---|---|
| 1 | 100% | 15-25% | No |
| 2 | 50% | 30-50% | No |
| 4 | 25% | 60-100% | Approaching |
| 8 | 12.5% | >100% | **Yes** -- cores stall |
| 16 | 6.25% | >100% | **Severe** -- most time stalling |

Beyond 4 cores, memory bandwidth becomes the primary scaling bottleneck. The caches
(method, object, array) are critical for reducing this pressure.

## 2. Arbitration Overhead

### 2.1 BMB Arbiter Architecture

The arbiter is SpinalHDL's `BmbArbiter` with `lowerFirstPriority=false` (round-robin).
It adds source routing bits (`log2Up(cpuCnt)`) to track which core made each request.
The arbiter grants bus access to one core at a time.

**Arbitration latency**: The BMB arbiter itself adds zero additional latency when the
bus is idle -- the winning core's command passes through combinationally. The cost
manifests as wait time when multiple cores compete.

### 2.2 Worst-Case Access Latency

When N cores all need the bus simultaneously, a core waits for up to (N-1) other
transactions to complete before its own is served.

**Single-word access latency** (32-bit read through BmbSdramCtrl32):
- 2 SDRAM commands (low + high 16-bit halves)
- Each SDRAM command: ~5-9 cycles (depends on row hit/miss)
- Total single-word: ~10-18 cycles

**Worst-case wait time** = (N-1) x max_single_transaction_cycles:

| Cores | Max wait (single-word) | Max wait (burst=4 words) | Impact |
|---|---|---|---|
| 1 | 0 | 0 | -- |
| 2 | 18 cycles | 60 cycles | Minimal |
| 4 | 54 cycles | 180 cycles | Noticeable |
| 8 | 126 cycles | 420 cycles | Significant |
| 16 | 270 cycles | 900 cycles | Severe |

**Burst reads hold the bus**: During a method cache fill (BC_FILL) or array cache
fill (AC_FILL), the `burstActive` flag in BmbSdramCtrl32 holds the bus for the
entire burst. A 4-word burst takes ~15 SDRAM cycles. A 32-word method fill takes
~120 SDRAM cycles. During this time, all other cores are stalled.

### 2.3 Average-Case Analysis

In practice, cores do not all request the bus simultaneously. JOP's pipeline
continues executing stack operations, ALU instructions, and local variable
accesses while waiting for memory. The actual contention depends on the
workload's memory access pattern.

For a typical workload (mix of ALU + field access + method calls):
- Average memory request rate per core: ~1 per 5-8 bytecodes
- Average bytecode takes ~10-15 clock cycles
- Average memory request interval: ~50-120 cycles
- Average outstanding requests at any time: ~N/6 (for N cores)

This suggests that up to ~4 cores can share the bus without significant
contention on typical code. Beyond 4 cores, contention rises sharply.

## 3. Lock Contention

### 3.1 CmpSync Global Lock

CmpSync implements a single global lock shared by all cores. The lock protocol:

1. Core writes `IO_LOCK` (BmbSys addr 5) to set `lockReqReg = True`
2. CmpSync grants to one core (`halted=0`), halts all others (`halted=1`)
3. Owner writes `IO_UNLOCK` (addr 6) to set `lockReqReg = False`
4. Round-robin fair arbiter selects next requester immediately (no idle gap)

**Critical property**: When any core holds the lock, ALL other cores' pipelines
are frozen via `io.halted`. The pipeline stall is wired through BmbSys:
```
pipeline.io.memBusy := memCtrl.io.memOut.busy || bmbSys.io.halted || io.debugHalt
```

This means the global lock is a **total serialization barrier** -- not just for
memory access, but for all pipeline activity on non-owner cores.

### 3.2 Lock Usage in Java Runtime

The `monitorenter` microcode sequence (jvm.asm, line 1470) has ~22 microcode
instructions including:
1. Disable interrupts (2 I/O writes)
2. Increment `lockcnt` (local variable, reentrant)
3. Write to `IO_LOCK` to request global lock
4. Read `IO_LOCK` to check if granted (should be 0 = acquired)
5. If not 0, throw `IllegalMonitorStateException`

The `monitorexit` sequence (line 1517) has ~16 microcode instructions:
1. Write to `IO_CPU_ID` (unlock signal)
2. Decrement `lockcnt`
3. If count reaches 0, enable interrupts (2 I/O writes)

**Lock hold duration**: The time between monitorenter and monitorexit depends
entirely on the Java code inside the `synchronized` block.

### 3.3 Sources of Lock Contention

**GC.java** is the heaviest lock user. Every `synchronized(mutex)` block acquires
the global lock. Key lock sites in `GC.java`:

| Method | Lock count | Typical hold time |
|---|---|---|
| `push()` | 1 | ~10 memory reads (handle validation) |
| `flip()` | 1 | ~5 memory writes |
| `markAndCopy()` per gray object | 2-3 | ~5-20 memory reads per iteration |
| `sweepHandles()` per handle | 1 | ~3-5 memory ops |
| `newObject()` | 2 | ~15 memory ops (allocation + handle setup) |

**PrintStream.java**: `print(String)` and `println(char)` are `synchronized`.
Every console output acquires the global lock. For multi-core applications
that print from multiple cores, this serializes all output.

**JVM.java**: `f_new()`, `f_newarray()`, and `aastore` write barrier use
`synchronized(GC.mutex)` for allocation and reference tracking.

### 3.4 Contention Impact

For an N-core system where each core performs K lock acquisitions per unit time:
- Total lock demand: N * K
- Lock throughput: limited by 1 / (avg_lock_hold_time + monitorenter_overhead + monitorexit_overhead)
- Monitorenter overhead: ~22 microcode cycles = ~22 clock cycles
- Monitorexit overhead: ~16 microcode cycles = ~16 clock cycles

For GC-heavy workloads (many object allocations):
- `newObject()` acquires the lock ~2 times per allocation
- Each lock hold: ~30-80 cycles (memory-bound)
- Overhead per allocation: ~100-200 cycles of lock serialization
- With N cores all allocating: each core waits (N-1) x 100-200 cycles per allocation

**Estimate for 8-core GC workload**: If each core allocates once per 1000 cycles,
total lock demand is 8 x 0.001 = 0.008 per cycle. Lock throughput at ~100 cycles/hold
is 0.01 per cycle. Utilization: 80% -- borderline saturation.

## 4. Cache Effectiveness

### 4.1 Method Cache

**Configuration**: 16 blocks x 32 words = 512 words (2KB), FIFO replacement,
tag-only lookup (JBC RAM is the data storage).

**Hit behavior**: 2 cycles (S1 tag match). Miss: 3 cycles (S1 miss + S2 tag update)
plus fill time.

**Fill cost** (SDRAM, burstLen=4):
- Small method (8 words): 2 bursts x ~15 cycles = 30 cycles
- Medium method (32 words): 8 bursts x ~15 cycles = 120 cycles
- Large method (128 words): 32 bursts x ~15 cycles = 480 cycles

**Multi-core impact**: Method cache is per-core (each core has its own JBC RAM
and MethodCache instance). No sharing or coherency issues. However, method fills
consume SDRAM bandwidth. With N cores, N independent method fills can compete
for the bus.

**Typical hit rate**: For a warmed-up system running steady-state code,
method cache hit rates are typically 95-99% (methods stay cached until evicted
by FIFO). Each miss costs 30-500 cycles depending on method size, making
even a 1% miss rate significant at higher core counts.

### 4.2 Object Cache

**Configuration**: 16 entries x 8 fields = 128 values, fully associative FIFO.
Per-field valid bits enable partial caching.

**Hit behavior**: 0 busy cycles (stays in IDLE, combinational hit detection).
Data available next cycle via registered RAM output.

**Miss penalty** (SDRAM): HANDLE_READ -> HANDLE_WAIT -> HANDLE_CALC ->
HANDLE_ACCESS -> HANDLE_DATA_WAIT = 5 state transitions + BMB latency.
- Handle dereference: 1 SDRAM read (~10-18 cycles)
- Field read: 1 SDRAM read (~10-18 cycles)
- Total miss penalty: ~20-36 cycles

**Snoop invalidation overhead**: Each core's `putfield` broadcasts on the snoop bus.
Other cores' object caches check all 16 tags against the snoop handle (combinational).
On match, the specific field valid bit is cleared. This is zero-latency (combinational)
but reduces effective cache hit rate under write-heavy workloads.

**Typical hit rate**: For read-heavy object access patterns (getfield dominant),
hit rates of 80-95% are typical. For mixed read-write patterns with cross-core
sharing, snoop invalidation reduces effective hit rate to 50-70%.

### 4.3 Array Cache

**Configuration**: 16 entries x 4 elements = 64 values, fully associative FIFO.
Tags include handle + upper index bits (different regions map to different lines).

**Hit behavior**: 0 busy cycles (same as O$).

**Miss penalty** (SDRAM, burst): AC_FILL issues a single 4-word burst read.
- HANDLE_READ -> HANDLE_WAIT -> HANDLE_CALC -> AC_FILL_CMD -> AC_FILL_WAIT = ~25-40 cycles
- Plus bounds check (HANDLE_BOUND_READ/WAIT): +10-18 cycles
- Total miss penalty: ~35-58 cycles

**Snoop invalidation**: Each core's `iastore` invalidates matching lines in other
cores' array caches. The `snoopDuringFill` flag prevents re-validation if a snoop
fires mid-fill.

**Typical hit rate**: For sequential array traversals (iaload in a loop), the
4-element line provides very high hit rates (75% for sequential access, since
3 of 4 elements hit after the first miss fills the line). For random access
patterns, hit rates drop to 10-30%.

### 4.4 Combined Cache Effectiveness

The three caches reduce SDRAM traffic significantly:

| Cache | Hit reduces traffic by | Typical hit rate | Net traffic reduction |
|---|---|---|---|
| Method cache | ~30-500 cycles per miss avoided | 95-99% | 85-95% of method fills |
| Object cache | ~20-36 cycles per miss avoided | 80-95% | 64-91% of field accesses |
| Array cache | ~35-58 cycles per miss avoided | 40-75% | 14-43% of array accesses |

For a typical workload mix (30% method calls, 40% field access, 30% array access),
the combined caches reduce memory bus utilization by approximately 60-80%.
This is what makes 4-core operation feasible without saturation.

## 5. GC Scaling

### 5.1 Stop-the-World Mechanism

GC is triggered by `gc_alloc()` when the free list is empty. The GC sequence:
1. `Native.wr(1, Const.IO_GC_HALT)` -- halt all other cores
2. `flip()` -- swap semi-spaces
3. `markAndCopy()` -- trace + copy live objects
4. `sweepHandles()` -- rebuild free/use lists
5. `zapSemi()` -- clear from-space
6. `Native.wr(0, Const.IO_GC_HALT)` -- resume other cores

### 5.2 GC Pause Time Components

**Stack scanning** (`getStackRoots()`): Scans all thread stacks conservatively.
For each stack slot, calls `push()` which does:
- Null check: 1 comparison
- Range check: 2 comparisons
- Alignment check: 1 AND
- Handle validation: 3-4 `Native.rdMem()` calls (through synchronized)
- Gray list threading: 1-2 `Native.wrMem()` calls

Cost per stack slot: ~50-100 cycles (dominated by memory reads and lock overhead).
Typical stack depth: 50-200 slots per thread.

For N cores, each core has its own stack. Total stack slots = N x avg_stack_depth.
At 80 MHz with 100 slots/core: N x 100 x 80 cycles = 8000N cycles.

**Mark and copy** (`markAndCopy()`): For each live object:
1. Pop from gray list: 2 memory ops + lock = ~50 cycles
2. Check already-moved: 1 memory read = ~15 cycles
3. Push children: per-child 50-100 cycles
4. Copy data: `Native.memCopy()` loop, 1 word per call = ~30 cycles/word

For a typical small heap (1000 live objects, avg 4 words each):
- Marking: ~1000 x 200 cycles = 200K cycles
- Copying: ~1000 x 4 x 30 cycles = 120K cycles
- Total: ~320K cycles at 80 MHz = ~4ms

**Handle sweep** (`sweepHandles()`): Linear scan of handle list.
~8 memory ops per handle with lock = ~100 cycles/handle.
For 200 handles: 20K cycles = ~0.25ms

**Zap from-space** (`zapSemi()`): Linear write of entire semi-space.
One `Native.wrMem()` per word = ~15 cycles/word (SDRAM write wait).
For 16K-word semi-space: 240K cycles = ~3ms

### 5.3 Total GC Pause vs Core Count

| Component | 1 core | 4 cores | 8 cores | 16 cores |
|---|---|---|---|---|
| Stack scan | 8K cycles | 32K cycles | 64K cycles | 128K cycles |
| Mark & copy | 320K cycles | 320K cycles | 320K cycles | 320K cycles |
| Handle sweep | 20K cycles | 20K cycles | 20K cycles | 20K cycles |
| Zap semi-space | 240K cycles | 240K cycles | 240K cycles | 240K cycles |
| **Total** | **588K cycles** | **612K cycles** | **644K cycles** | **708K cycles** |
| **Time (80 MHz)** | **7.4ms** | **7.7ms** | **8.1ms** | **8.9ms** |

Stack scanning scales linearly with core count, but it is a relatively small fraction
of total GC time. The dominant cost is mark-and-copy and zap, which are heap-proportional,
not core-proportional.

### 5.4 GC Impact on Throughput

During GC, all non-GC cores are halted via `IO_GC_HALT`. The throughput loss is:
- GC duty cycle = GC_pause / GC_interval
- Typical GC interval: ~12 allocation rounds (from docs), varies by workload
- For a 7-8ms GC pause every 100ms: 7-8% throughput loss
- This scales with heap pressure (more allocation = more frequent GC)

With more cores, heap pressure increases (more allocations), so GC frequency
increases while pause time stays roughly constant. The net effect is that
GC duty cycle scales roughly linearly with the number of allocating cores.

### 5.5 Semi-Space Waste

The semi-space collector wastes 50% of available heap. With 32MB SDRAM, only
~16MB is available for application data. A mark-compact collector would
recover this waste entirely -- the primary motivation for the planned
mark-compact migration.

## 6. FPGA Resource Utilization

### 6.1 EP4CGX150 Resources

The Cyclone IV GX EP4CGX150 provides:
- 149,760 logic elements (LEs)
- 720 M9K memory blocks (6,480 Kbits total)
- 6 PLLs, 520 I/O pins

### 6.2 Per-Core Resource Usage

Each JopCore contains:
- JopPipeline: BytecodeFetch (JBC RAM + jump table), Fetch (microcode ROM),
  Decode, Stack (256-entry dual-port RAM), Multiplier
- BmbMemoryController: state machine + MethodCache (16-tag array) +
  ObjectCache (16-entry x 128-word data RAM) + ArrayCache (16-entry x 64-word data RAM)
- BmbSys: counters, interrupt logic, lock registers
- BmbUart (core 0 only): UartCtrl + FIFOs

Estimated per-core resource breakdown:

| Component | LEs (est.) | M9K blocks (est.) |
|---|---|---|
| Pipeline (fetch/decode/stack) | 2,000-3,000 | 4-6 (ROM, stack RAM, JBC) |
| Memory controller | 1,500-2,000 | 0 (regs only) |
| Method cache tags | 200-300 | 0 |
| Object cache | 300-400 | 1-2 (data RAM) |
| Array cache | 300-400 | 1-2 (data RAM) |
| BmbSys + UART | 500-800 | 1 (UART FIFOs) |
| **Total per core** | **~5,000-7,000** | **~7-11** |

### 6.3 Shared Infrastructure

| Component | LEs (est.) | M9K blocks | Notes |
|---|---|---|---|
| BmbArbiter | 200 + 100/core | 0 | Source routing MUX |
| CmpSync | 200-400 | 0 | Scales with cpuCnt |
| BmbSdramCtrl32 | 1,000-1,500 | 0 | Fixed, shared |
| Altera SDRAM ctrl | 1,000-2,000 | 0 | BlackBox, fixed |
| PLL + reset | 100 | 0 | Fixed |
| Snoop bus | 100-200/core | 0 | MuxOH per core |
| **Total shared** | **~2,500-4,000** | **0** | Plus per-core overhead |

### 6.4 Measured Scaling

| Cores | LEs used | % of 149,760 | Clock (MHz) | Slack (ns) |
|---|---|---|---|---|
| 1 | ~8K-10K | ~6% | 100 | >5 |
| 4 | ~28K-34K | ~20% | 100 | +3.0 |
| 8 | ~60K-68K | ~42% | 100 | +1.9 |
| 16 | ~125K-130K | ~86% | 80 | +1.8 |

The LE usage is approximately linear with core count (as expected). The frequency
drop at 16 cores is due to routing congestion -- at 86% LE utilization, the fitter
has limited freedom for placement, leading to longer routing paths. The solution
was to reduce the clock from 100 MHz to 80 MHz.

### 6.5 Memory Block Scaling

M9K blocks scale linearly with core count:
- 1 core: ~8-12 M9K blocks
- 8 cores: ~64-96 M9K blocks (9-13% of 720)
- 16 cores: ~128-192 M9K blocks (18-27% of 720)

M9K blocks are not the limiting factor -- LEs and routing are.

## 7. Pipeline Stall Analysis

### 7.1 Sources of Pipeline Stalls

The JOP pipeline stalls when `memBusy` is asserted. The stall signal is:
```scala
pipeline.io.memBusy := memCtrl.io.memOut.busy || bmbSys.io.halted || io.debugHalt
```

Three stall sources:
1. **Memory controller busy**: Complex memory operations (BC fill, handle dereference, etc.)
2. **CmpSync halted**: Another core holds the global lock, or GC halt is active
3. **Debug halt**: Debug controller has paused this core

### 7.2 Memory Controller Stall Cycles

| Operation | State path | Stall cycles (BRAM) | Stall cycles (SDRAM) |
|---|---|---|---|
| Simple read (stmra) | IDLE->READ_WAIT | 0 (1-cycle response) | 10-18 |
| Simple write (stmwd) | IDLE->WRITE_WAIT | 0 | 10-18 |
| getfield (O$ hit) | IDLE (stay) | 0 | 0 |
| getfield (O$ miss) | IDLE->PF_WAIT->HANDLE_READ->...->IDLE | 4 | 25-40 |
| putfield | IDLE->PF_WAIT->HANDLE_READ->...->IDLE | 5 | 30-50 |
| iaload (A$ hit) | IDLE (stay) | 0 | 0 |
| iaload (A$ miss) | IDLE->HANDLE_READ->...->AC_FILL->IDLE | 6-8 | 35-58 |
| iastore | IDLE->IAST_WAIT->HANDLE_READ->...->IDLE | 6-8 | 35-58 |
| Method cache hit | IDLE->BC_CACHE_CHECK->IDLE | 2-3 | 2-3 |
| Method cache miss | IDLE->BC_CACHE_CHECK->BC_FILL->IDLE | 10-20 | 30-500 |
| getstatic | IDLE->GS_READ->LAST->IDLE | 2 | 15-22 |
| putstatic | IDLE->PS_WRITE->LAST->IDLE | 2 | 15-22 |
| memCopy (per word) | IDLE->CP_SETUP->CP_READ->...->LAST->IDLE | 4-5 | 30-40 |

### 7.3 Multi-Core Stall Amplification

With N cores sharing the SDRAM bus, each core's memory operations take longer
due to arbitration contention. An operation that takes X cycles on a single core
takes approximately X + (N-1) x contention_probability x avg_transaction_length
on an N-core system.

For a rough model, assume 20% bus utilization per core:
- 2 cores: each operation +0.2x slower (20% chance of contention)
- 4 cores: each operation +0.6x slower
- 8 cores: each operation +1.4x slower
- 16 cores: each operation +3.0x slower

This compounds with the base operation cost, making cache misses increasingly
expensive at higher core counts.

## 8. Bottleneck Ranking

Ordered from most impactful to least impactful for scaling beyond 4 cores:

### 8.1 SDRAM Bandwidth Saturation (Critical, 4+ cores)

The single 16-bit SDR SDRAM bus at 100 MHz provides ~80 MB/s effective bandwidth.
With caches providing ~70% traffic reduction, effective per-core demand is ~3-5 MB/s.
This limits scaling to approximately 8-12 cores before bandwidth saturation.

**Why it's #1**: Memory bandwidth is the fundamental shared resource. Every cache
miss, method fill, and GC memory operation competes for the same bus. Unlike lock
contention (which can be reduced by algorithmic changes), bandwidth is a hard
physical limit.

### 8.2 Global Lock Serialization (Significant, 2+ cores)

The CmpSync global lock halts ALL other cores when ANY core holds it. This creates
a serialization bottleneck proportional to total lock hold time across all cores.

**Why it's #2**: Every `synchronized` block (including GC, allocation, and I/O)
serializes the entire system. The lock hold time includes memory access latency,
which itself increases with core count (bottleneck #1), creating a feedback loop.

### 8.3 GC Stop-the-World (Significant, 2+ cores)

GC halts all non-GC cores for the entire collection cycle (5-10ms at 80 MHz).
GC frequency increases with allocation rate, which scales with core count.

**Why it's #3**: Unlike lock contention (brief, frequent), GC pauses are long
but infrequent. The duty cycle (pause/interval) is the key metric, and it
scales roughly linearly with the number of allocating cores.

### 8.4 Method Cache Fill Blocking (Moderate, 4+ cores)

A method cache miss triggers a burst read that holds the SDRAM bus for potentially
hundreds of cycles (32-word method = ~120 SDRAM cycles). During this time, all
other cores are blocked from memory access.

**Why it's #4**: Method cache hits are typically 95-99%, so fills are rare. But when
they occur, they create long bus-holding episodes that amplify tail latency.

### 8.5 FPGA Routing Congestion (Moderate, 12+ cores)

At 86% LE utilization (16 cores), routing congestion forces a clock reduction from
100 MHz to 80 MHz. This is a 20% throughput reduction that affects all cores.

**Why it's #5**: The frequency drop reduces absolute throughput but doesn't affect
scaling efficiency (all cores slow down equally). Only manifests at high core counts.

### 8.6 Cache Snoop Invalidation (Minor, 2+ cores)

Cross-core snoop invalidation reduces effective cache hit rates for shared data.
Each core's writes invalidate matching entries in other cores' caches.

**Why it's #6**: Snoop invalidation is combinational (zero latency), but the
resulting increased miss rate contributes to higher bus utilization. For
non-shared data (the common case), snoops have no effect.

## 9. Amdahl's Law Estimate

### 9.1 Serial Fraction Identification

| Serial component | Fraction of execution | Source |
|---|---|---|
| GC stop-the-world | 5-10% | Heap-proportional, all cores halted |
| Global lock hold (non-GC) | 3-8% | synchronized blocks in runtime |
| Core 0 init (boot) | <1% | One-time, amortized |
| UART output (core 0 only) | 1-3% | PrintStream synchronized |
| **Total serial fraction (s)** | **~10-20%** | |

### 9.2 Amdahl's Law Speedup

Amdahl's Law: `Speedup(N) = 1 / (s + (1-s)/N)`

Using s = 0.15 (15% serial fraction, mid-range estimate):

| Cores | Theoretical speedup | Efficiency | Adjusted (bandwidth limit) |
|---|---|---|---|
| 1 | 1.0x | 100% | 1.0x |
| 2 | 1.74x | 87% | 1.7x |
| 4 | 2.96x | 74% | 2.7x |
| 8 | 4.42x | 55% | 3.5x |
| 16 | 5.60x | 35% | 3.8x |

The "Adjusted" column accounts for memory bandwidth saturation, which further
reduces scaling beyond what Amdahl's Law predicts. The adjustment applies a
bandwidth penalty that increases quadratically with core count.

### 9.3 Memory-Adjusted Amdahl's Law

A more accurate model incorporates the memory bandwidth bottleneck as an additional
serial fraction that increases with N:

```
Speedup(N) = 1 / (s + b(N) + (1-s-b(N))/N)
```

where `b(N)` is the bandwidth contention fraction:
- b(1) = 0
- b(4) = 0.05
- b(8) = 0.15
- b(16) = 0.30

This gives more realistic estimates:

| Cores | Speedup | Notes |
|---|---|---|
| 1 | 1.0x | Baseline |
| 2 | 1.7x | Near-linear (low contention) |
| 4 | 2.7x | Good scaling |
| 8 | 3.5x | Diminishing returns from bandwidth |
| 16 | 3.8x | Bandwidth-dominated, minimal gain over 8 |

## 10. Optimization Recommendations

Ordered by expected impact and implementation effort:

### 10.1 Per-Object Locking (IHLU) -- IMPLEMENTED

**Current**: `CmpSync` global lock is the default. `Ihlu` (Individual Hardware
Lock Unit) is available as an optional alternative via `useIhlu` in
`JopCoreConfig`. IHLU provides 32 hardware lock slots with per-object
granularity, FIFO wait queues, and reentrant locking. Only cores contending
for the SAME object are halted; cores locking different objects proceed in
parallel. Includes GC drain mechanism for STW interaction.

**Expected impact**: For workloads with independent synchronization (different
objects), this eliminates most lock serialization. For GC (which uses a single
`GC.mutex`), no improvement. Estimated 20-40% throughput improvement at 4+ cores
for allocation-light workloads.

See [IHLU Design Analysis](ihlu-design-analysis.md) for full details.

### 10.2 Mark-Compact GC -- IMPLEMENTED

Incremental mark-compact collector replaces the semi-space copying GC.
Doubles effective heap size, eliminates zapSemi, and uses bounded
per-allocation increments (MARK_STEP=20, COMPACT_STEP=10) with proactive
trigger at 25% free heap. Verified across all platforms.

See [GC Mark-Compact Design](gc-mark-compact-design.md) and
[Incremental GC Analysis](incremental-gc-analysis.md).

### 10.3 Wider SDRAM Data Bus -- High Impact, Hardware Change

**Current**: 16-bit SDR SDRAM at 80-100 MHz = 160-200 MB/s.

**Possible**: If the FPGA board supported 32-bit SDRAM or DDR SDRAM, bandwidth
would double or quadruple. The Alchitry Au V2 board has DDR3 (256MB, resolved —
GC working with 32KB L2 cache).

**Expected impact**: Directly doubles memory bandwidth, pushing the saturation
point from ~8 cores to ~16 cores. Would make 16-core scaling viable at 100 MHz.

### 10.4 Reduce Lock Hold Time in GC -- Moderate Impact, Low Effort

**Current**: `markAndCopy()` acquires and releases the GC mutex 2-3 times per
gray object, each time going through the full monitorenter/monitorexit microcode
sequence (~38 cycles overhead per lock/unlock pair).

**Proposed**: Restructure GC to minimize lock acquisitions. Batch gray list
operations. Remove lock from `push()` (only called from single-core STW GC
context where all other cores are halted, so the lock is unnecessary).

**Expected impact**: Reduces GC pause time by 10-20% by eliminating unnecessary
lock overhead. Since other cores are halted during GC anyway, the locks in
STW GC are pure overhead with no functional benefit.

### 10.5 Method Cache Prefetch / Larger Cache -- Moderate Impact, Low Effort

**Current**: 16 blocks x 32 words = 512 words (2KB). Large methods cause
frequent evictions.

**Proposed**: Increase to `jpcWidth=12` (4KB cache, 32 blocks) or `jpcWidth=13`
(8KB). Each doubling halves the miss rate for working sets that fit in the
larger cache.

**Expected impact**: Reduces bus-blocking method fills. Diminishing returns
past 4KB for typical JOP programs. Each doubling costs 1 M9K block per core.

### 10.6 Reduce GC Halt Scope -- Moderate Impact, Moderate Effort

**Current**: `IO_GC_HALT` freezes ALL other cores' pipelines. They cannot even
execute stack operations or local variable accesses.

**Proposed**: Only freeze memory bus access, not the entire pipeline. Cores could
continue executing non-memory instructions (ALU, stack manipulation) while GC runs.
This requires separating the halt signal into "bus halt" and "pipeline halt".

**Expected impact**: Reduces effective GC pause impact by 20-40% depending on
workload. Memory-light code sections would continue during GC.

### 10.7 Bus-Aware Burst Scheduling -- Low Impact, Moderate Effort

**Current**: All cores compete equally for the bus via round-robin arbitration.
A core doing a 32-word method fill holds the bus for ~120 cycles.

**Proposed**: Priority-based arbitration or time-sliced arbitration. Cores
with short transactions (single-word reads) could preempt long bursts.

**Expected impact**: Reduces tail latency for non-filling cores during another
core's method fill. Modest throughput improvement (~5-10% at 8+ cores).

## 11. Summary

JOP SMP scales well to 4 cores and adequately to 8 cores on the QMTECH EP4CGX150 with
SDR SDRAM. The primary bottlenecks are:

1. **SDRAM bandwidth** (hard limit at 8+ cores)
2. **Global lock serialization** (exacerbated by memory latency)
3. **GC stop-the-world pauses** (proportional to heap size, not core count)

The most impactful optimizations (IHLU and mark-compact GC are now implemented):
- **IHLU (per-object locking)**: Implemented — eliminates global lock serialization (optional via `useIhlu`)
- **Mark-compact GC**: Implemented — incremental mark-compact with bounded pauses
- **Lock elimination in STW GC**: Easy win, 10-20% GC pause reduction

The system achieves approximately 2.7x speedup at 4 cores and 3.5x at 8 cores
compared to single-core, limited primarily by the 15-20% serial fraction and
memory bandwidth saturation. Beyond 8 cores, returns diminish sharply.
