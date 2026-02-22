# JOP SpinalHDL Implementation Notes

Detailed implementation notes for the SpinalHDL JOP port. These are reference notes
captured during development — see the source code for authoritative details.

## Bugs Found & Fixed (13 total)

1. **BC fill write address off-by-one**: Used `bcFillCount.resized` (not increment) for JBC write address
2. **iaload/caload +1 offset**: VHDL uses `data_ptr + index` (no +1). Both BmbMemoryController and JopSimulator had wrong `+1`
3. **JBC RAM read-write collision**: SpinalHDL `readSync` with `dontCare` returns undefined data on same-cycle read-write collision. Added manual write-first bypass in BytecodeFetchStage. NOTE: `readSync(writeFirst)` does NOT work in Verilog — SpinalHDL silently falls back to `dontCare`.
4. **Pipeline stall for high-latency memory**: READ_WAIT/WRITE_WAIT must be busy until `rsp.valid`, otherwise pipeline outruns slow responses.
5. **UartCtrl RX one-cycle pulse**: SpinalHDL UartCtrl `io.read.valid` uses `RegNext(False)` — valid for exactly ONE cycle. Must buffer with `StreamFifo` so pop.valid stays high until consumed (matches VHDL sc_uart FIFO pattern).
6. **SpinalHDL `+|` vs `+^`**: `+|` is SATURATING add (clamps at max), `+^` is EXPANDING add (adds 1 bit). Use `+^` when you need `length + 1` to not overflow (e.g., BMB length to byte count).
7. **iastore 3-operand timing**: castore/iastore has TOS=value, NOS=index, 3rd=arrayref. The `stast` microcode instruction has implicit pop (0x04x prefix = isPop). When `io.memIn.iastore` fires, the stack hasn't shifted yet: aout=value, bout=index. Must add 1-cycle IAST_WAIT state (matching VHDL `iast0`) so the pop shifts the stack: aout=index, bout=arrayref. Without this, addrReg gets INDEX (not arrayref), handleIndex gets VALUE (not index), causing writes to memory addresses equal to the castore VALUE — corrupting static fields.
8. **HANDLE_ACCESS missing I/O routing**: HardwareObject fields (getfield/putfield via IOFactory) have data pointers in I/O address space. HANDLE_ACCESS must check `addrIsIo` and route to I/O bus instead of BMB. Without this, I/O reads return 0 (SysDevice fields like nrCpu, cpuId unreadable).
9. **BmbSys missing registers**: IO_LOCK (addr 5) must return 0 (lock acquired) for monitorenter to work. IO_CPUCNT (addr 11) must return 1 for Scheduler array sizing. Missing these caused GC `synchronized` to hang and Scheduler to create wrong-sized arrays.
10. **cpuStart NPE on core 1 boot**: Original `Startup.java` initialized `cpuStart = new Runnable[nrCpu]` which requires GC. Non-zero cores called `cpuStart[cpuId]` before GC was ready, causing NPE. Fix: removed cpuStart array — non-zero cores go directly to main loop after `started` flag, no Runnable indirection needed.
11. **Putfield bcopd pipeline timing**: The `stpf` microcode instruction (putfield) has NO `opd` flag, unlike `stgf` (getfield) which has `opd`. When putfield fires in BmbMemoryController's IDLE state, the pipeline hasn't yet accumulated the bytecode operand bytes into `bcopd` — the `nop opd` instructions that follow `stpf` haven't executed yet. Result: `handleIndex` captured as 0 instead of the field offset, so all putfield writes went to field 0 of the object. Fix: added `PF_WAIT` state (matching VHDL `pf0` "waste cycle to get opd in ok position") that delays index capture by one cycle. Putfield now goes IDLE→PF_WAIT→HANDLE_READ instead of IDLE→HANDLE_READ. Getfield is unaffected (keeps IDLE→HANDLE_READ with immediate index capture). Object cache `chkPf` moved from HANDLE_READ to PF_WAIT for putfield path.

12. **BmbSdramCtrl32 burst read data corruption under multi-core traffic**: When the BMB arbiter switches from a single-word read (e.g., Core 0 getfield) to a burst read (e.g., Core 1 BC_FILL), `burstActive` becomes True while the single-word SDRAM responses are still in the SdramCtrl CAS pipeline (~6 cycles latency). Those stale responses arrive with `isHigh` context from the single-word operation, and the high-half response fires `io.bmb.rsp.fire`, which incorrectly increments `burstWordsSent`. This produces a 1-word data shift in the burst response: `got[N] = expected[N+1]` — the first 32-bit word is "consumed" by the stale response, and all subsequent words slide down by one position. Symptom: Core 1+ get corrupted bytecodes during BC_FILL, execute wrong code paths (e.g., entering `JVMHelp.wr()` despite `cpuId != 0`). Fix: added `isBurst` flag to `SdramContext` — burst SDRAM commands carry `isBurst=True`, single-word commands carry `isBurst=False`. Response-side burst counting (`burstWordsSent` increment and `burstActive` termination) is gated by `burstActive && rsp.context.isBurst`, so stale non-burst responses pass through harmlessly with `last=True`. Only manifests with SpinalHDL SdramCtrl simulation path (FPGA uses Altera controller which has separate context FIFOs).
13. **SpinalHDL SdramCtrl CKE gating bug**: The library `SdramCtrl` has a power-saving CKE gating mechanism: `sdramCkeNext = !(readHistory.orR && !io.bus.rsp.ready)`. When `io.bus.rsp.ready` goes low while reads are in-flight (e.g., BMB arbiter backpressure from another core), CKE gates off. But `remoteCke` (which freezes the command pipeline) is 2 cycles delayed, creating a window where SDRAM commands are issued but the SDRAM ignores them (CKE=0). This misaligns the context pipeline with actual responses, causing data corruption. Fix: created `SdramCtrlNoCke` — local copy with `sdramCkeNext := True` (CKE never gates off). CKE gating is a power-saving feature not needed for JOP. Used by `BmbSdramCtrl32` when `useAlteraCtrl=false` (simulation path).

## Method Cache

- **MethodCache** (`jop.memory`): Tag-only lookup matching VHDL `mcache` entity
  - 16 blocks (blockBits=4), each 32 words, FIFO replacement
  - Hit: 2 cycles, Miss: 3 cycles + fill
  - `rdy` is combinational (state===IDLE), `inCache` is registered
  - `find` is combinational (same clock edge as bcRd in IDLE)
  - `clrVal` pre-computed every cycle for displaced tag clearing
- **BC_CACHE_CHECK** state in BmbMemoryController between IDLE and BC_READ
- JBC write address offset by `bcCacheStartReg` (cache block base)
- Old dead code removed: MemoryController.scala, MemorySubsystem.scala + testbenches

## Pipelined BC Fill + Burst Reads

- **States**: BC_FILL_R1 -> BC_FILL_LOOP -> BC_FILL_CMD (pipelined, `bcFillAddr` on cmd.fire, `bcFillCount` on rsp.fire)
- **Burst**: `JopMemoryConfig(burstLen=N)` — 0=single-word (BRAM), 4=SDR SDRAM, 8=DDR3
- burstLen>0: BC_FILL_R1 issues burst cmd, BC_FILL_LOOP processes beats via rsp.last
- BmbSdramCtrl32: burst-aware (2*N SDRAM cmds, `isBurst` context for multi-core safety), BmbCacheBridge: decomposes to sequential cache lookups

## I/O Subsystem (`jop.io` package)

- **BmbSys**: Full SysDevice register set matching VHDL sc_sys, including timer interrupt generation
  - Read: addr 0=IO_CNT, 1=IO_US_CNT, 2=IO_INT_SRC (intNr), 5=IO_LOCK, 6=IO_CPU_ID, 7=IO_SIGNAL, 11=IO_CPUCNT
  - Write: addr 0=IO_INT_ENA, 1=IO_TIMER, 2=IO_SWINT, 3=IO_WD, 8=IO_INTMASK, 9=IO_INTCLEARALL, 13=IO_GC_HALT
  - Prescaler: 8-bit countdown, `divVal = clkFreqHz / 1_000_000 - 1` (99 at 100 MHz)
  - `io.wd` output drives LEDs (active low on QMTECH board)
  - `cpuCnt` parameter (default 1) for multi-core support
  - **Interrupt chain** (matching VHDL sc_sys.vhd): timer_equ → timer_int → intstate → priority encoder → irq_gate → irq pulse. NUM_INT = numIoInt + 1 (timer + I/O). intstate is an SR flip-flop per source (set on hwreq|swreq, cleared on ack|clearAll). Priority encoder gives highest index priority. int_ena register gates final irq output; automatically cleared on ackIrq or ackExc. SW interrupt via addr 2 write (yield). Interrupt number captured in intNr on ackIrq, readable at addr 2.
  - **irq/irqEna are internal to JopCore**: BmbSys generates irq/irqEna, wired directly to JopPipeline. No external irq/irqEna ports on JopCore/JopCoreWithBram/JopCoreWithSdram. ackIrq/ackExc flow back from BytecodeFetchStage through JopPipeline to BmbSys.
- **BmbUart**: UartCtrl + TX FIFO (16) + RX FIFO (16). Status (addr 0): bit0=TDRE, bit1=RDRF. Data (addr 1): read consumes RX FIFO, write pushes TX FIFO.
- Both use same I/O interface: addr(4 bits), rd, wr, wrData(32), rdData(32)
- Top-levels wire: `ioSubAddr = ioAddr(3:0)`, `ioSlaveId = ioAddr(5:4)`
- **SysDevice field->address mapping**: cntInt(0), uscntTimer(1), intNr(2), wd(3), exception(4), lock(5), cpuId(6), signal(7), intMask(8), clearInt(9), deadLine(10), nrCpu(11), perfCounter(12), gcHalt(13)
- **HANDLE_ACCESS I/O routing**: HardwareObject fields (getfield/putfield) have data pointers in I/O space. BmbMemoryController checks `addrIsIo` in HANDLE_ACCESS and routes to I/O bus instead of BMB.
- **SMP sync interface**: BmbSys has `io.syncIn`/`io.syncOut` (SyncIn/SyncOut bundles) for CmpSync integration. Write to IO_LOCK (addr 5) sets `lockReqReg`, write to IO_UNLOCK (addr 6) clears it. Read IO_LOCK returns `syncIn.halted` in bit 0. `io.halted` output stalls the pipeline when another core holds the lock.

## Object Cache

- **ObjectCache** (`jop.memory`): Fully associative FIFO, 16 entries x 8 fields = 128 values, matching VHDL `ocache.vhd`
- **Getfield hit**: 0 busy cycles (stays in IDLE, `readOcache` selects cached data)
- **Getfield miss**: Normal HANDLE_READ path + cache fill via `wrGf`
- **Putfield**: Always through state machine, write-through on hit via `wrPf`. `chkPf` in PF_WAIT (putfield waste cycle).
- **Invalidation**: `stidx`/`cinval` clears all valid bits. `wasHwo` suppresses I/O caching.
- **Cacheable**: Only fields 0-7 (upper fieldIdx bits must be 0)

## Hardware memCopy (GC)

- **States**: CP_SETUP -> CP_READ -> CP_READ_WAIT -> CP_WRITE -> LAST (+ CP_STOP for stopbit)
- **stcp timing**: At `stcp`: TOS=pos, NOS=src. After implicit pop: TOS=src, NOS=dest (read in CP_SETUP)
- **Address translation**: NOT applied (causes timing violation at 100MHz). Single-core GC doesn't need it.
- **GC.java**: `Native.memCopy(dest, addr, i)` loop + stopbit `Native.memCopy(dest, dest, -1)`

## GC App (Phase 3)

- **Runtime**: Small runtime from jopmin (56 files) — GC, Scheduler, IOFactory, more JDK stubs
- **App**: `java/apps/Small/src/test/HelloWorld.java` — allocates int[32] arrays in a loop, reports free memory
- **GC behavior**: Automatic GC triggered by `gc_alloc()` when free list empty. ~12 allocation rounds between GC cycles.
- **FPGA static field corruption (SDR SDRAM only)**: Root cause was BmbSdramCtrl32 bug, fixed by Altera controller
- **InstructionFinder.java**: Patched BCEL class needed by PreLinker's ReplaceIinc transform

## SMP Architecture

- **Unified via `cpuCnt`**: `JopSdramTop(cpuCnt=N)` / `JopCyc5000Top(cpuCnt=N)` — N `JopCore`s + round-robin BMB arbiter + shared SDRAM + per-core I/O. cpuCnt=1 uses direct BMB (no arbiter, no CmpSync).
- **BMB Arbiter**: `BmbArbiter` with `lowerFirstPriority=false` (round-robin), source width expanded by `log2Up(cpuCnt)` bits for response routing
- **CmpSync** (`jop.io`): Global lock for `monitorenter`/`monitorexit` + GC halt
  - States: IDLE / LOCKED. Round-robin fair arbiter (two-pass scan)
  - Core writes IO_LOCK (addr 5) -> `lockReqReg=true`, CmpSync grants one core (`halted=0`), halts others (`halted=1`)
  - Core writes IO_UNLOCK (addr 6) -> `lockReqReg=false`, releases lock
  - Core writes IO_GC_HALT (addr 13) -> `gcHaltReg`, CmpSync halts all OTHER cores
  - **Lock owner exempt from gcHalt**: When LOCKED, the owner is NEVER halted (even if another core has gcHalt set). This prevents deadlock: if the GC core sets gcHalt while another core holds the lock, the lock owner must complete its critical section and release before being halted. Non-owners are always halted when a lock is held. After the owner releases, it will be caught by gcHalt on the next cycle (IDLE state: `halted := gcHaltFromOthers`). Previous code OR'd gcHaltFromOthers into the owner's halted signal, causing a deadlock when GC and lock overlapped. Found by code review, confirmed by formal verification.
  - Boot signal: core 0's `IO_SIGNAL` broadcast to all cores via `s_in`/`s_out`
- **Per-core I/O**: Each core has `BmbSys(cpuId=i, cpuCnt=N)` with independent watchdog, unique CPU ID, CmpSync interface. Only core 0 has `BmbUart`.
- **Pipeline halt**: `io.halted` from CmpSync through BmbSys directly stalls pipeline
- **Boot sequence**: Core 0 runs full init (GC, class init). Other cores wait for `started` flag, then enter main loop.
- **LED driver**: LED[0]=core 0 WD bit 0, LED[1]=core 1 WD bit 0 (active low)
- **Test app**: `NCoreHelloWorld.java` — each core reads `IO_CPU_ID`, core 0 prints + toggles WD, others just toggle WD
- **FPGA build**: `make full-smp` in QMTECH or CYC5000 dirs (separate Quartus projects: `jop_smp_sdram.qsf`, `jop_smp_cyc5000.qsf`)
- **Resource usage**: ~12K LEs (8% of EP4CGX150), substantial headroom for more cores

## SMP GC Stop-the-World

- **Problem**: Original JOP SMP has no stop-the-world GC mechanism. GC uses `synchronized(mutex)` which only halts cores entering synchronized blocks — cores running normal code (e.g., reading I/O registers, toggling watchdog) keep executing and can read partially-moved objects from SDRAM during the copying phase, causing corruption.
- **Symptom**: NCoreHelloWorld on CYC5000 SMP ran ~140 println iterations (~70 seconds), then output corrupted to a flood of 'C' characters before both cores hung. This matched the ~160 allocation limit of the 28KB semi-space heap.
- **Fix**: Hardware GC halt signal via `IO_GC_HALT` (BmbSys addr 13):
  - `SyncIn` bundle: added `gcHalt` field (core → CmpSync direction)
  - `CmpSync`: when any core's `gcHalt` is set, all OTHER cores' pipelines are halted. The halting core itself is NOT affected. Lock owner is exempt from gcHalt (see CmpSync deadlock fix above).
  - `BmbSys`: `gcHaltReg` register, write 1 to set, write 0 to clear. Routed to `syncOut.gcHalt`.
  - `GC.java`: `Native.wr(1, Const.IO_GC_HALT)` before `gc()`, `Native.wr(0, Const.IO_GC_HALT)` after.
  - Single-core: gcHalt output is unconnected (no CmpSync), no effect.
- **Future**: Protect the halt flag with CmpSync lock for safe multi-core allocation (currently only core 0 allocates). Any core triggering GC should acquire the lock first, then set gcHalt.
- **Verified**: CYC5000 SMP hardware — NCoreHelloWorld running 3+ minutes through multiple GC cycles with no corruption. SMP GC BRAM sim shows `halted=.H` during GC rounds.

## Intentionally Unused VHDL Signals

- **putref**: The `putref` memory operation (from `stprf` microcode) exists in the decode stage and BmbMemoryController but is never used by current JOP bytecodes. In the original VHDL, putref is part of the SCJ (Safety-Critical Java) scope check mechanism — it's a variant of putstatic that would validate reference assignment safety in an SCJ runtime. Our JOP port doesn't implement SCJ scope checks, so putref fires but is handled identically to putstatic (no additional logic).
- **atmstart/atmend**: The `atmstart`/`atmend` memory operations (from `statm`/`endatm` microcode) are SimpCon atomicity markers. In the original VHDL SimpCon-based memory interface, they bracketed multi-cycle memory operations to prevent bus arbitration between start and end. With BMB (which has native transactions with backpressure), atomicity is handled by the bus protocol itself and CmpSync's global lock. These signals are decoded but have no effect in BmbMemoryController.
- **IO_LOCK sync_out.status**: The VHDL `sync_out` record has a `status` field (read at IO_LOCK addr 5, bit 1), but it's only driven by `ihlu.vhd` (the IHLU alternative lock implementation for the Jeopard RTSJ framework). Our port uses CmpSync (not IHLU), so `SyncOut` has no `status` field. Old Java code in `ControlChannel.java` that reads IO_LOCK status bit 1 is for Jeopard/IHLU — not applicable.

## DDR3 TODO

- **Calibration gate**: DDR3 top-level (`JopDdr3Top`) should gate JOP reset on MIG calibration complete (`init_calib_complete`). Currently, JOP starts running before DDR3 is ready, which could cause early memory accesses to fail. Deferred — DDR3 platform is deprioritized due to unresolved GC hang (see `ddr3-gc-hang.md`).

## SDR SDRAM GC Hang (Resolved)

- ROOT CAUSE: SpinalHDL's `SdramCtrl` DQ I/O timing
- Replaced with Altera `altera_sdram_tri_controller` BlackBox -> GC works on both boards (QMTECH 2000+, CYC5000 9800+ rounds)
- Altera controller has `FAST_INPUT_REGISTER=ON` / `FAST_OUTPUT_REGISTER=ON` synthesis attributes
- Files: `AlteraSdramBlackBox.scala`, `AlteraSdramAdapter.scala`, patched `altera_sdram_tri_controller.v`
- `BmbSdramCtrl32(useAlteraCtrl=true)` selects Altera path, `false` uses `SdramCtrlNoCke` (local copy with CKE gating disabled)
- See `sdr-sdram-gc-hang.md` for full investigation history

## BmbSdramCtrl32 Multi-Core Simulation Fixes (2026-02-22)

Two bugs prevented multi-core SDRAM simulation (SpinalHDL SdramCtrl path, not Altera FPGA path):

1. **CKE gating bug** (bug #13): SpinalHDL's `SdramCtrl` gates CKE when `rsp.ready` is low with reads in-flight. In multi-core configs, the BMB arbiter briefly lowers `rsp.ready` when servicing another core. The 2-cycle `remoteCke` delay creates a command/response misalignment. Fix: `SdramCtrlNoCke` — local copy with `sdramCkeNext := True`.

2. **Burst read response misattribution** (bug #12): When the arbiter switches from a single-word read to a burst read, `burstActive` becomes True while stale single-word responses are still in the CAS pipeline. Those responses incorrectly increment `burstWordsSent`, producing a 1-word data shift (`got[N] = exp[N+1]`). Fix: `isBurst` flag in `SdramContext` — response-side burst counting gated by `burstActive && rsp.context.isBurst`.

Files:
- `SdramCtrlNoCke.scala` — local SdramCtrl with CKE disabled
- `BmbSdramCtrl32.scala` — `isBurst` in SdramContext, burst response gating
- `JopSmpSdramSim.scala` — SMP SDRAM test harness (N cores, SdramModel)

Verification: 2-core and 4-core SDRAM sims pass with burstLen=4, 0 data mismatches.
