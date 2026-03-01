# IHLU (In-Hardware Lock Unit) Design Analysis

Design analysis for replacing CmpSync (global lock) with fine-grained per-object
hardware locking in JOP SMP.

## 1. Current System: CmpSync Global Lock

### 1.1 Hardware (CmpSync.scala)

CmpSync is a centralized global lock arbiter located in `jop.io.CmpSync`. It
has two states: IDLE and LOCKED. When any core acquires the lock, **all other
cores are halted** regardless of whether they need the same lock.

**Source**: `/home/peter/workspaces/jop-spinalhdl/spinalhdl/src/main/scala/jop/io/CmpSync.scala`

Key characteristics:
- Round-robin fair arbitration (two-pass downto scan, smallest i > rrIndex wins,
  else smallest i <= rrIndex)
- One lock for the entire system -- no per-object distinction
- Lock owner is exempt from gcHalt (prevents deadlock when GC core halts others
  while another core holds the lock)
- No-gap handoff: when owner releases, the arbiter immediately checks for new
  requesters in the same cycle

Signals per core:
- `SyncIn`: req (lock request), s_in (boot signal), gcHalt (GC halt request)
- `SyncOut`: halted (pipeline stall), s_out (boot broadcast)

### 1.2 I/O Interface (BmbSys.scala)

**Source**: `/home/peter/workspaces/jop-spinalhdl/spinalhdl/src/main/scala/jop/io/BmbSys.scala`

The lock protocol uses two I/O addresses:
- **IO_LOCK (addr 5)**: Write sets `lockReqReg = true` (acquire). Read returns
  `syncIn.halted` in bit 0.
- **IO_UNLOCK (addr 6)**: Write clears `lockReqReg = false` (release). Read
  returns `cpuId`.

BmbSys also provides:
- **IO_GC_HALT (addr 13)**: Write sets/clears `gcHaltReg`. When set, CmpSync
  halts all other cores' pipelines.

### 1.3 Microcode (monitorenter / monitorexit)

**Source**: `/home/peter/workspaces/jop-spinalhdl/asm/src/jvm.asm` (lines 1470-1544)

**monitorenter** (bytecode 0xC2):
1. Disable interrupts (write 0 to IO_INT_ENA)
2. Increment `lockcnt` (reentrant lock counter in microcode variable)
3. Write IO_LOCK to acquire global lock (sets `lockReqReg`)
4. Read IO_LOCK -- pipeline stalls here if another core holds the lock
   (CmpSync sets `halted=1`, which feeds through BmbSys to stall the pipeline)
5. Check read value: if 0, lock acquired. If non-zero, throw
   IllegalMonitorStateException (exc_mon).
6. Pop the objectref from the stack (the objectref is **ignored** -- the lock
   is global, not per-object)

**monitorexit** (bytecode 0xC3):
1. Write IO_CPU_ID (addr 6, i.e. IO_UNLOCK) to release global lock
   (clears `lockReqReg`)
2. Decrement `lockcnt`
3. If lockcnt reaches 0, re-enable interrupts (write 1 to IO_INT_ENA)
4. Pop the objectref from the stack (again, objectref is **ignored**)

**Critical observation**: The objectref parameter of monitorenter/monitorexit is
completely discarded by the current implementation. The lock is purely global.
The `lockcnt` variable provides reentrancy counting but at the global level, not
per-object.

### 1.4 Java-Level Usage

**Source**: `/home/peter/workspaces/jop-spinalhdl/java/runtime/src/jop/com/jopdesign/sys/JVM.java`

Java `synchronized` blocks compile to monitorenter/monitorexit bytecodes. The
JOP toolchain maps `Native.lock(ref)` to monitorenter and `Native.unlock(ref)`
to monitorexit (see `JopInstr.java` line 423-424).

In practice, synchronized blocks appear in:
- **GC.java**: ~15 synchronized(mutex) blocks for heap/handle manipulation
- **JVM.java**: Write barriers (aastore, putfield_ref, putstatic_ref), athrow
- **PrintStream.java**: print/println methods
- **Memory.java**: Memory allocation
- **Startup.java**: Exit loop

All of these use the same global lock because CmpSync has no object distinction.
This means a `println` on one core serializes with a GC allocation on another
core, even though they operate on completely independent data.

### 1.5 Performance Impact

With CmpSync, any `synchronized` block on any core halts **all** other cores.
For N cores:

- Every monitorenter/monitorexit pair stalls N-1 cores for the duration of the
  critical section
- GC's `synchronized(mutex)` blocks (which are frequent during marking/copying)
  create serial bottlenecks
- PrintStream.println is synchronized, so UART output serializes all cores
- The global lock is the primary SMP scalability bottleneck

For a 16-core system at 80 MHz, if each core spends even 5% of its time in
synchronized blocks, the effective throughput loss is 5% * 15 = 75% of total
non-owner core cycles wasted on stalls.

## 2. VHDL IHLU Reference Design

**Source**: `/srv/git/jop/vhdl/scio/ihlu.vhd`

Author: Torur Biskopsto Strom (2014). The IHLU was designed as a drop-in
replacement for CmpSync, using the same `sync_in_array_type` / `sync_out_array_type`
interface but with extended signal records.

### 2.1 Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `cpu_cnt` | 4 | Number of CPU cores |
| `lock_cnt` | 32 | Number of hardware lock slots |

### 2.2 Interface

The VHDL IHLU extends the sync interface with per-core signals:

**sync_in_type** (core -> IHLU):
- `req`: Lock/unlock request pulse (one-cycle trigger)
- `op`: Operation -- 0 = lock (acquire), 1 = unlock (release)
- `data`: 32-bit lock identifier (the object reference / handle address)
- `s_in`: Boot synchronization signal

**sync_out_type** (IHLU -> core):
- `halted`: Core is waiting for a lock (pipeline stall)
- `status`: Error flag -- 1 if all lock slots are full (no room for new lock)
- `s_out`: Boot synchronization broadcast

### 2.3 Architecture

The IHLU uses a 4-state state machine that processes one core's request at a
time in round-robin order:

**States**: `state_idle` -> `state_ram` -> `state_ram_delay` -> `state_operation`

**Per-lock-slot storage** (32 slots):
- `entry(i)`: 32-bit lock identifier (the object handle address)
- `empty(i)`: Whether this slot is free
- `count(i)`: 8-bit reentrant lock counter
- `current(i)`: CPU ID of current lock owner
- `queue_head(i)` / `queue_tail(i)`: FIFO queue head/tail pointers

**FIFO queue RAM**: A `lock_cnt * cpu_cnt` array of `cpu_cnt_width`-bit entries,
implemented as block RAM. Each lock slot has a circular queue of waiting CPUs,
indexed by `(lock_slot_index, queue_pointer)`.

**Request buffering**: Each core has a registered input buffer:
- `data_r(i)`: Captured lock identifier
- `op_r(i)`: Captured operation (lock/unlock)
- `register_i(i)` / `register_o(i)`: Toggle-based handshake to detect new
  requests. A core is halted while `register_i != register_o` (request pending)
  or `sync(i) = 1` (waiting in queue).

### 2.4 Lock Acquisition Algorithm

The state machine cycles through cores round-robin. When a pending request is
found for core `cpu`:

1. **Match check** (combinational, registered): Compare `data_r(cpu)` against
   all non-empty `entry(i)`. If match found at index `match_index`, set `match=1`.

2. **Empty slot check** (combinational, registered): Find first empty slot via
   priority encoder. Result in `empty_index`.

3. **RAM read** (state_ram): Read the queue entry at
   `(match_index, queue_head)` -- needed for unlock (to determine next waiter).
   Write address prepared at `(match_index, queue_tail)` -- needed for lock
   enqueue.

4. **RAM delay** (state_ram_delay): One-cycle pipeline delay for synchronous RAM
   read.

5. **Operation** (state_operation): Execute the lock/unlock:

   **Lock (op=0)**:
   - **Match + current owner == cpu**: Reentrant -- increment `count(match_index)`
   - **Match + current owner != cpu**: Contention -- write `cpu` to queue tail
     (RAM write), advance `queue_tail`, set `sync(cpu)=1` (core stalled)
   - **No match + slots available**: Allocate `empty_index`, set
     `entry = data_r(cpu)`, `current = cpu`, mark non-empty
   - **No match + slots full**: Set `status(cpu)=1` (error)

   **Unlock (op=1, assumes only owner unlocks)**:
   - **count > 0**: Reentrant unlock -- decrement count
   - **count == 0, queue empty** (head == tail): Deallocate slot (`empty=1`),
     decrement `total_lock_count`
   - **count == 0, queue non-empty**: Transfer ownership to `ram_data_out`
     (next CPU in queue), clear `sync(next_cpu)=0` (un-stall), advance
     `queue_head`

### 2.5 Latency Analysis

The IHLU processes one request every 4 cycles (idle -> ram -> delay -> operation
-> idle). With N cores, worst-case latency to process a single request is
4*N cycles (if all cores have pending requests). For 16 cores, this is 64 cycles.

However, the common case (uncontended lock) takes only 4 cycles from request to
completion, regardless of core count. Contended locks add queue insertion time
(also 4 cycles) plus waiting time until the owner releases.

### 2.6 Limitations

1. **Fixed 32 lock slots**: If an application uses more than 32 distinct monitor
   objects simultaneously, the IHLU returns status=1 (error). The microcode
   currently throws IllegalMonitorStateException on status=1. This is a hard
   limit.

2. **No hash function**: Lock lookup is a full associative search (CAM-style
   comparison of `data_r` against all 32 entries). This is expensive in hardware
   for large slot counts.

3. **Serial processing**: Only one core's request is processed per 4-cycle
   window. Under heavy contention from many cores, the round-robin scheduling
   adds latency.

4. **Queue depth limited by cpu_cnt**: Each lock slot's FIFO can hold at most
   `cpu_cnt` entries. This is sufficient since there are only `cpu_cnt` cores.

5. **No GC halt integration**: The VHDL IHLU has no `gcHalt` signal. It was
   designed before the STW GC mechanism was added. This is a major gap.

6. **No boot signal passthrough for gcHalt**: Only `s_in`/`s_out` is wired.

## 3. Proposed SpinalHDL IHLU Design

### 3.1 Lock Table Structure

Retain the VHDL approach but with refinements:

```
case class IhluConfig(
  cpuCnt: Int = 4,
  lockSlots: Int = 32,     // Number of hardware lock slots
  reentrantBits: Int = 8   // Max reentrant depth per lock (255)
)
```

**Per-slot registers**:
- `entry(i)`: `Bits(32 bits)` -- lock identifier (object handle address)
- `valid(i)`: `Bool` -- slot in use
- `owner(i)`: `UInt(log2Up(cpuCnt) bits)` -- current owner CPU ID
- `count(i)`: `UInt(reentrantBits bits)` -- reentrant lock count
- `queueHead(i)` / `queueTail(i)`: `UInt(log2Up(cpuCnt) bits)` -- FIFO pointers

**FIFO queue**: Block RAM of size `lockSlots * cpuCnt`, storing `log2Up(cpuCnt)`-
bit CPU IDs. Addressed as `(slotIndex ## queuePointer)`.

### 3.2 Lookup: Fully Associative CAM

For 32 slots, the CAM comparison is 32 parallel 32-bit comparators. At 100 MHz
on Cyclone IV GX, each comparator is ~32 LEs. Total: ~1024 LEs for the CAM.
This is acceptable (EP4CGX150 has 149,760 LEs; 16-core JOP uses 86%).

For scalability beyond 32 slots, a hash-indexed approach could be used, but
32 slots should be sufficient for typical Java programs (see Section 6).

### 3.3 Request Interface

Extend the existing `SyncIn` / `SyncOut` bundles:

```scala
case class IhluSyncIn() extends Bundle {
  val req     = Bool()         // Request pulse
  val op      = Bool()         // 0=lock, 1=unlock
  val data    = Bits(32 bits)  // Lock identifier (object handle)
  val s_in    = Bool()         // Boot sync
  val gcHalt  = Bool()         // GC halt (NEW: not in VHDL IHLU)
}

case class IhluSyncOut() extends Bundle {
  val halted  = Bool()         // Pipeline stall
  val status  = Bool()         // Error: lock table full
  val s_out   = Bool()         // Boot sync broadcast
}
```

### 3.4 State Machine

Replicate the VHDL 4-state design with improvements:

```
IDLE -> RAM_READ -> RAM_DELAY -> EXECUTE -> IDLE
```

**Optimization**: The match encoder and empty encoder can be pipelined. In the
VHDL design, they are registered (1-cycle delay), meaning the actual lookup
result is available at the start of `state_ram`. This is correct and should be
preserved.

**Improvement over VHDL**: Add a fast path for uncontended locks. If the match
result shows the requesting core is already the owner (reentrant case), skip
the RAM access states and go directly to EXECUTE. This reduces reentrant
lock/unlock from 4 cycles to 2 cycles.

### 3.5 GC Halt Integration

This is the most significant addition over the VHDL IHLU. The `gcHalt` signal
must be integrated with the IHLU's per-lock stall mechanism.

The halted output for each core must combine:
1. **Lock wait**: Core is in a lock queue (`sync(cpu) = 1`) or has a pending
   request (`register_i != register_o`)
2. **GC halt**: Another core's gcHalt is set AND this core is not a lock owner

**Critical rule (matching CmpSync)**: A core that currently owns ANY lock must
NOT be halted by gcHalt. This prevents the deadlock where the GC core sets
gcHalt, but another core holds a lock needed to complete its critical section.

```scala
// Per-core halted output
val isLockOwner = (0 until lockSlots).map { s =>
  valid(s) && owner(s) === U(i)
}.reduce(_ || _)

val gcHaltFromOthers = (0 until cpuCnt).filter(_ != i)
  .map(j => io.syncIn(j).gcHalt).reduce(_ || _)

io.syncOut(i).halted := lockWait(i) ||
  (gcHaltFromOthers && !isLockOwner)
```

**Timing concern**: The `isLockOwner` check requires comparing the core's CPU
ID against all 32 slot owners. This is 32 parallel comparators (log2Up(cpuCnt)
bits each), ORed together. For 16 cores (4-bit CPU ID), this is 32x4-bit
comparators -- minimal. But it's in the halted output path, which must be
combinational for same-cycle pipeline stall. This should meet timing at 80-100 MHz.

### 3.6 Microcode Changes

The monitorenter/monitorexit microcode must be modified to pass the object
reference to the IHLU via the `data` field of `SyncIn`.

**Current monitorenter** (CmpSync):
```asm
monitorenter:
    // disable interrupts
    ldi io_int_ena
    stmwa
    ldi 0
    stmwd
    wait
    wait
    // increment lockcnt
    ldm lockcnt
    ldi 1
    add
    stm lockcnt
    // request global lock
    ldi io_lock
    stmwa
    stmwd          // write data = don't care (global lock)
    wait
    wait
    // read lock status
    ldi io_lock
    stmra
    wait
    wait
    ldmrd
    // check status
    nop
    nop
    bz monitorenter_ok
    nop
    nop
    // error: throw IllegalMonitorStateException
    ldi io_exc
    stmwa
    ldi exc_mon
    stmwd
    wait
    wait
    nop nxt
monitorenter_ok:
    nop nxt
```

**Proposed monitorenter** (IHLU):
```asm
monitorenter:
    // disable interrupts
    ldi io_int_ena
    stmwa
    ldi 0
    stmwd
    wait
    wait
    // increment lockcnt
    ldm lockcnt
    ldi 1
    add
    stm lockcnt
    // request per-object lock: write objectref to IO_LOCK
    // TOS has the objectref (pushed by bytecode operand stack)
    ldi io_lock
    stmwa
    stmwd          // write data = objectref (the handle address)
    wait
    wait
    // read lock status -- pipeline stalls here until lock granted
    ldi io_lock
    stmra
    wait
    wait
    ldmrd
    // check status bit (1 = lock table full)
    nop
    nop
    bz monitorenter_ok
    nop
    nop
    // error: lock table full, throw IllegalMonitorStateException
    ldi io_exc
    stmwa
    ldi exc_mon
    stmwd
    wait
    wait
    nop nxt
monitorenter_ok:
    nop nxt
```

The key change is that `stmwd` now writes the **objectref** (the Java handle
address) as the lock identifier. In CmpSync, this value was ignored. In IHLU,
it becomes the lock table key.

**Proposed monitorexit** (IHLU):
```asm
monitorexit:
    // release per-object lock: write objectref to IO_UNLOCK (addr 6)
    ldi io_cpu_id       // addr 6 = IO_UNLOCK
    stmwa
    stmwd              // write data = objectref (for IHLU to match)
    wait
    wait
    // decrement lockcnt
    ldm lockcnt
    ldi 1
    sub
    dup
    stm lockcnt
    // if lockcnt != 0, skip interrupt re-enable
    bnz monitorexit_no_ena
    nop
    nop
    // re-enable interrupts
    ldi io_int_ena
    stmwa
    ldi 1
    stmwd
    wait
    wait
monitorexit_no_ena:
    nop nxt
```

**BmbSys changes**: The write handler for IO_LOCK (addr 5) and IO_UNLOCK
(addr 6) must route the write data to the IHLU's `data` field and set the
appropriate `op` bit:

```scala
is(5) {  // IO_LOCK: acquire
  lockReqReg := True       // For CmpSync backward compat
  ihluDataReg := io.wrData // Lock identifier for IHLU
  ihluOpReg := False       // 0 = lock
  ihluReqPulse := True     // One-cycle pulse
}
is(6) {  // IO_UNLOCK: release
  lockReqReg := False      // For CmpSync backward compat
  ihluDataReg := io.wrData // Lock identifier for IHLU
  ihluOpReg := True        // 1 = unlock
  ihluReqPulse := True
}
```

### 3.7 BMB I/O Interface

The IHLU connects through the existing `SyncIn` / `SyncOut` vectors in
JopCluster, replacing CmpSync. The connection pattern is identical:

```scala
// In JopCluster, replace CmpSync with IHLU:
val ihlu = Ihlu(IhluConfig(cpuCnt = cpuCnt))
for (i <- 0 until cpuCnt) {
  ihlu.io.syncIn(i) := cores(i).io.syncOut
  cores(i).io.syncIn := ihlu.io.syncOut(i)
}
```

The `SyncIn` and `SyncOut` bundles need to be extended (or a new bundle type
used) to carry the `op`, `data`, and `status` fields. This is a breaking
interface change that affects BmbSys, JopCore, and JopCluster.

## 4. GC Interaction

### 4.1 The Problem

With CmpSync (global lock), GC interaction is simple: the lock owner is exempt
from gcHalt. There is exactly one lock owner at any time. When the GC core sets
gcHalt, the single lock owner finishes its critical section and releases, then
gets halted.

With IHLU, **multiple cores can hold different locks simultaneously**. When the
GC core sets gcHalt:
- Core A may hold lock on object X
- Core B may hold lock on object Y
- Core C (GC) sets gcHalt to freeze A and B
- But A and B are in critical sections and cannot be frozen mid-operation

### 4.2 Approach 1: Drain (Recommended)

**Mechanism**: When gcHalt is asserted, all lock owners are allowed to continue
executing until they release all their locks. Only then are they halted.

**Implementation**:
```scala
val isLockOwner = (0 until lockSlots).exists(s =>
  valid(s) && owner(s) === U(i))

io.syncOut(i).halted := lockWait(i) ||
  (gcHaltFromOthers && !isLockOwner)
```

This is identical to the CmpSync exemption but extended to multiple locks. A
core is halted by gcHalt only if it owns zero locks.

**Advantages**:
- Simple extension of existing CmpSync behavior
- No timeout needed
- Guaranteed to complete (assuming critical sections are bounded)
- No Java code changes needed

**Risks**:
- If a core holds a lock and enters a long-running critical section (e.g.,
  printing a long string while holding println's monitor), GC drain time could
  be long
- Nested monitors could delay drain further: if core A holds lock X and tries
  to acquire lock Y (which is held by core B, who is also trying to acquire
  lock X), this is a Java-level deadlock that prevents drain. However, this
  is a programmer bug, not an IHLU issue.

**Bounded drain time**: In JOP's current codebase, all synchronized blocks are
short (GC mutex operations, write barriers, println). The longest is println
which loops over characters with UART waits. For a 100-character string at
115200 baud, this is ~8.7 ms. At 100 MHz, that's ~870,000 cycles. This is
acceptable for GC pause time (already in the same order as the GC itself).

### 4.3 Approach 2: Priority Elevation

**Mechanism**: When gcHalt is asserted, lock owners get priority bus access
(elevated arbitration priority) to complete their critical sections faster.

**Implementation**: Feed a `gcDrain` signal to the BMB arbiter to give
lock-holding cores higher priority.

**Advantages**: Faster drain time under bus contention.

**Disadvantages**: Adds complexity to the BMB arbiter. Marginal benefit since
critical sections are short and bus contention during GC drain is low (other
cores are halted).

**Verdict**: Not worth the complexity.

### 4.4 Approach 3: Force Release with Timeout

**Mechanism**: If drain takes longer than a configurable timeout, force-release
all locks and halt the cores.

**Implementation**: Counter starts when gcHalt is asserted. If any core still
holds a lock after N cycles, force `valid(slot) = 0` for all slots owned by
that core and assert halt.

**Advantages**: Bounded worst-case GC pause.

**Disadvantages**:
- Violates Java memory model: releasing a lock without executing the
  monitorexit code can corrupt shared state
- The core's `lockcnt` microcode variable becomes inconsistent with hardware
  state
- Extremely hard to recover from safely

**Verdict**: Unsafe. Do not implement.

### 4.5 Recommendation: Drain Approach

Use Approach 1 (drain). It is simple, safe, matches the existing CmpSync
philosophy, and has acceptable worst-case drain time given JOP's short critical
sections.

**Additional safeguard**: Add a debug counter that tracks maximum drain cycles.
If drain exceeds a configurable threshold in simulation, assert a warning. This
helps detect pathological critical sections during development.

## 5. Performance Analysis

### 5.1 Expected Speedup vs CmpSync

**CmpSync bottleneck**: When core A enters a synchronized block, cores B through
N are ALL halted for the entire duration, regardless of which object is locked.

**IHLU improvement**: When core A locks object X and core B locks object Y,
both can execute concurrently. Only cores contending for the SAME object are
serialized.

**Speedup model**: Let `p` = fraction of time a core spends in synchronized
blocks, and `N` = number of cores. Let `k` = number of distinct lock objects.

With CmpSync: effective throughput = `1 + (N-1)(1-p)` (N-1 cores waste p
fraction of time).

With IHLU (k distinct locks, uniform distribution): effective throughput
~= `1 + (N-1)(1 - p/k)` (each core only waits when contending on the same lock).

For typical values:
| N | p | k | CmpSync throughput | IHLU throughput | Speedup |
|---|---|---|-------------------|-----------------|---------|
| 4 | 10% | 3 | 3.70 | 3.90 | 1.05x |
| 8 | 10% | 3 | 7.30 | 7.77 | 1.06x |
| 16 | 10% | 3 | 14.50 | 15.50 | 1.07x |
| 4 | 30% | 3 | 3.10 | 3.70 | 1.19x |
| 8 | 30% | 3 | 6.10 | 7.30 | 1.20x |
| 16 | 30% | 3 | 11.50 | 14.50 | 1.26x |
| 4 | 30% | 10 | 3.10 | 3.91 | 1.26x |
| 8 | 30% | 10 | 6.10 | 7.79 | 1.28x |
| 16 | 30% | 10 | 11.50 | 15.55 | 1.35x |

**Key insight**: The benefit scales with both the number of distinct lock objects
and the percentage of time in synchronized blocks. For JOP's current workloads
(low p, few distinct locks), the speedup is modest. The benefit becomes
significant when:
1. Applications use multiple independent monitors (e.g., producer-consumer with
   separate buffer locks)
2. Time in synchronized blocks is non-trivial (GC-heavy workloads with many
   write barriers)

### 5.2 Latency Comparison

| Operation | CmpSync | IHLU |
|-----------|---------|------|
| Uncontended lock | 1 cycle (combinational grant) | 4 cycles (state machine) |
| Uncontended unlock | 1 cycle | 4 cycles |
| Reentrant lock | 1 cycle | 2 cycles (fast path) |
| Contended lock | 1+ cycles (stall until release) | 4 cycles (enqueue) + wait |
| Lock handoff | 0 cycles (no-gap) | 4 cycles (next request processing) |

**Observation**: IHLU has **higher base latency** (4 cycles vs 1 cycle) for
uncontended operations. This is because the VHDL IHLU uses a serial state
machine rather than combinational logic. For applications with very frequent
short synchronized blocks, IHLU could actually be **slower** than CmpSync if
contention is low.

**Mitigation**: The 4-cycle overhead is constant and small. The win comes from
avoiding N-1 core stalls on unrelated locks. For N >= 4, the avoided stall
cycles (hundreds to thousands per critical section) dominate the 4-cycle
overhead.

### 5.3 Practical JOP Workload Analysis

In current JOP applications:
- **GC.mutex**: Most frequently contended lock. Used in newObject, newArray,
  flip, markAndCopy, sweepHandles. During STW GC, only one core runs, so
  contention is zero. Outside GC, mutex protects allocation.
- **PrintStream monitor**: Used by println. On SMP, multiple cores printing
  contend on this lock. IHLU allows other cores to allocate (GC.mutex) while
  one core prints.
- **f_athrow**: Uses `Native.lock(0)` -- locks on address 0 as a global lock.
  This is a special case that would still serialize all cores (same lock
  identifier 0).

The primary benefit of IHLU for current workloads is decoupling PrintStream
locking from GC mutex locking.

## 6. FPGA Resource Estimates

### 6.1 Logic Elements

| Component | LEs (estimated) | Notes |
|-----------|----------------|-------|
| CAM (32x 32-bit comparators) | ~1,024 | Parallel match |
| Entry registers (32x 32-bit) | ~1,024 | Lock identifiers |
| Owner registers (32x 4-bit) | ~128 | CPU IDs (16-core) |
| Count registers (32x 8-bit) | ~256 | Reentrant counters |
| Queue pointers (32x 2x 4-bit) | ~256 | Head/tail |
| Valid/sync registers | ~100 | Control bits |
| State machine + mux logic | ~500 | Request processing |
| GC halt owner check | ~200 | 32x CPU comparators + OR |
| Request buffers (16 cores) | ~600 | Per-core data/op/toggle |
| **Total** | **~4,088** | |

For comparison:
- CmpSync (16 cores): ~200 LEs
- Single JopCore: ~7,500 LEs (approximate, based on 16-core = 86% of 149,760 LE)
- IHLU overhead: ~4,000 LEs = ~0.5 additional core equivalent

### 6.2 Block RAM

The FIFO queue RAM is `32 * 16 = 512` entries of 4 bits = 256 bytes. This fits
in a single M9K block (Cyclone IV GX has 720 M9K blocks). At 16-core 86% LE
utilization, M9K usage is well below capacity.

### 6.3 Timing

The critical path is the CAM match logic: 32 parallel 32-bit comparisons, then
a priority encoder. At 100 MHz (10 ns period), this should meet timing -- a
32-bit equality comparator is ~3 ns on Cyclone IV, and the priority encoder adds
~2 ns. Total ~5 ns with margin.

However, the registered match/empty encoders in the VHDL design (combinational
logic registered before use) already solve this: the match result is available
one cycle after the request data is captured.

## 7. Implementation Complexity

### 7.1 Effort Estimate

| Task | Effort | Risk |
|------|--------|------|
| Ihlu.scala (core state machine) | 2-3 days | Low (direct VHDL translation) |
| Extended SyncIn/SyncOut bundles | 0.5 day | Low |
| BmbSys modifications | 0.5 day | Low |
| JopCluster wiring (replace CmpSync) | 0.5 day | Low |
| GC halt integration | 1 day | Medium (correctness critical) |
| Microcode changes (jvm.asm) | 0.5 day | Medium (objectref routing) |
| Simulation test harness | 1-2 days | Low |
| SMP hardware verification | 1-2 days | Medium |
| **Total** | **7-10 days** | |

### 7.2 Risk Areas

1. **Microcode objectref routing**: The current monitorenter microcode pops
   the objectref from the stack. The modified version must write the objectref
   to IO_LOCK's data field before popping it. The stack ordering must be
   carefully verified.

2. **GC halt + lock owner exemption**: Must ensure that the `isLockOwner` check
   is cycle-accurate. If a core releases its last lock on the same cycle that
   gcHalt goes high, the transition from "owner" to "halted" must be
   glitch-free.

3. **Lock table overflow**: If the application creates more than 32 distinct
   monitored objects simultaneously, the IHLU returns an error. The microcode
   currently throws IllegalMonitorStateException, which may not be the ideal
   response. A fallback to polling / retry could be considered.

4. **Backward compatibility**: The sync interface change affects all top-levels
   and simulation harnesses. A configuration flag (`useIhlu: Boolean`) in
   JopCoreConfig would allow selecting CmpSync vs IHLU at elaboration time,
   minimizing risk during development.

5. **Native.lock(0) in f_athrow**: The athrow handler uses `Native.lock(0)` as
   a global lock. With IHLU, this becomes a per-object lock on handle 0. Since
   handle 0 is never a valid object handle in JOP (null), this effectively
   creates a dedicated "global lock" slot in the IHLU. This is actually fine --
   all athrow operations will serialize on the same IHLU slot, matching current
   behavior. No change needed.

### 7.3 Testing Strategy

1. **Unit test**: Cocotb testbench for IHLU in isolation (lock/unlock sequences,
   reentrant, contention, queue overflow)
2. **Integration sim**: JopSmpBramSim with IHLU replacing CmpSync, running
   NCoreHelloWorld (validates basic locking)
3. **GC sim**: JopSmpBramSim with IHLU running Small GC app (validates GC halt
   drain)
4. **Stress test**: Custom multi-core test with high lock contention on multiple
   objects (validates FIFO queues, reentrant counting)
5. **Hardware**: QMTECH EP4CGX150 SMP with IHLU, running NCoreHelloWorld +
   GC for extended duration

## 8. Recommendation

### 8.1 Should IHLU be implemented?

**Yes, but with lower priority than mark-compact GC.**

The IHLU provides genuine SMP scalability improvement by allowing independent
locks to be held concurrently. However, the benefit is modest for current JOP
workloads (few distinct lock objects, low contention). The ~4,000 LE cost is
acceptable on EP4CGX150 but significant on smaller FPGAs.

### 8.2 Priority Assessment

1. **Mark-compact GC** (higher priority): Recovers 50% heap waste. Direct and
   measurable benefit for all workloads. No hardware changes needed.

2. **IHLU** (lower priority): SMP scalability improvement. Benefit proportional
   to number of distinct lock objects and contention level. Requires hardware +
   microcode + Java runtime changes across multiple layers.

### 8.3 Recommended Approach

If implementing IHLU:

1. **Make it configurable**: Add `useIhlu: Boolean` to `JopCoreConfig`. When
   false, use existing CmpSync (zero additional cost). When true, instantiate
   IHLU. This allows gradual rollout and A/B testing.

2. **Start with the drain approach for GC**: Simple, safe, bounded. Add the
   debug drain counter for monitoring.

3. **Keep 32 lock slots**: Sufficient for any JOP application. Java programs
   rarely have more than 5-10 distinct monitor objects alive simultaneously.

4. **Preserve backward compatibility**: The microcode changes should be
   conditionally compiled (similar to existing `#ifdef` usage in jvm.asm).
   When IHLU is not present, the objectref write to IO_LOCK is harmless
   (CmpSync ignores the data).

5. **Test incrementally**: Start with BRAM SMP simulation, then SDRAM, then
   hardware. Verify GC halt drain behavior thoroughly before hardware deployment.

### 8.4 Alternative: Software IHLU

Instead of hardware IHLU, consider a software-only optimization: replace the
global lock with a software lock table using compare-and-swap (CAS) emulated
via the existing global lock. This approach:
- Requires no hardware changes
- Uses CmpSync only for short CAS operations (much shorter critical sections)
- Implements per-object FIFO queues in Java heap memory
- Is much more complex in software but zero hardware cost

This is not recommended because:
- JOP lacks a hardware CAS instruction (CmpSync IS the CAS)
- Software lock table adds significant overhead per lock/unlock
- The global lock duration would be reduced but not eliminated
- Debugging is much harder than hardware IHLU

### 8.5 Summary

| Criterion | CmpSync (current) | IHLU (proposed) |
|-----------|-------------------|-----------------|
| Hardware cost | ~200 LE | ~4,000 LE |
| Uncontended lock latency | 1 cycle | 4 cycles |
| Independent locks concurrent | No | Yes (32 max) |
| GC halt interaction | Simple (1 owner) | Drain (N owners) |
| Implementation effort | Done | 7-10 days |
| SMP scalability | Poor (global) | Good (per-object) |
| Risk | None (proven) | Medium |

The IHLU is a worthwhile enhancement for SMP-intensive workloads but should be
treated as a performance optimization, not a correctness fix. The existing
CmpSync is functionally correct and should remain the default until IHLU is
fully verified.
