# JOP SpinalHDL Implementation Notes

Detailed implementation notes for the SpinalHDL JOP port. These are reference notes
captured during development — see the source code for authoritative details.

## Bugs Found & Fixed

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
- BmbSdramCtrl32: burst-aware (2*N SDRAM cmds), BmbCacheBridge: decomposes to sequential cache lookups

## I/O Subsystem (`jop.io` package)

- **BmbSys**: Full SysDevice register set matching VHDL sc_sys
  - Read: addr 0=IO_CNT, 1=IO_US_CNT, 5=IO_LOCK (always 0), 6=IO_CPU_ID, 7=IO_SIGNAL, 11=IO_CPUCNT
  - Write: addr 1=IO_TIMER, 3=IO_WD, 8=IO_INTMASK (others silently accepted)
  - Prescaler: 8-bit countdown, `divVal = clkFreqHz / 1_000_000 - 1` (99 at 100 MHz)
  - `io.wd` output drives LEDs (active low on QMTECH board)
  - `cpuCnt` parameter (default 1) for multi-core support
- **BmbUart**: UartCtrl + TX FIFO (16) + RX FIFO (16). Status (addr 0): bit0=TDRE, bit1=RDRF. Data (addr 1): read consumes RX FIFO, write pushes TX FIFO.
- Both use same I/O interface: addr(4 bits), rd, wr, wrData(32), rdData(32)
- Top-levels wire: `ioSubAddr = ioAddr(3:0)`, `ioSlaveId = ioAddr(5:4)`
- **SysDevice field->address mapping**: cntInt(0), uscntTimer(1), intNr(2), wd(3), exception(4), lock(5), cpuId(6), signal(7), intMask(8), clearInt(9), deadLine(10), nrCpu(11), perfCounter(12)
- **HANDLE_ACCESS I/O routing**: HardwareObject fields (getfield/putfield) have data pointers in I/O space. BmbMemoryController checks `addrIsIo` in HANDLE_ACCESS and routes to I/O bus instead of BMB.
- **SMP sync interface**: BmbSys has `io.syncIn`/`io.syncOut` (SyncIn/SyncOut bundles) for CmpSync integration. Write to IO_LOCK (addr 5) sets `lockReqReg`, write to IO_UNLOCK (addr 6) clears it. Read IO_LOCK returns `syncIn.halted` in bit 0. `io.halted` output stalls the pipeline when another core holds the lock.

## Object Cache

- **ObjectCache** (`jop.memory`): Fully associative FIFO, 16 entries x 8 fields = 128 values, matching VHDL `ocache.vhd`
- **Getfield hit**: 0 busy cycles (stays in IDLE, `readOcache` selects cached data)
- **Getfield miss**: Normal HANDLE_READ path + cache fill via `wrGf`
- **Putfield**: Always through state machine, write-through on hit via `wrPf`. `chkPf` in HANDLE_READ.
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
- **CmpSync** (`jop.io`): Global lock for `monitorenter`/`monitorexit`
  - States: IDLE / LOCKED. Round-robin fair arbiter (two-pass scan)
  - Core writes IO_LOCK (addr 5) -> `lockReqReg=true`, CmpSync grants one core (`halted=0`), halts others (`halted=1`)
  - Core writes IO_UNLOCK (addr 6) -> `lockReqReg=false`, releases lock
  - Boot signal: core 0's `IO_SIGNAL` broadcast to all cores via `s_in`/`s_out`
- **Per-core I/O**: Each core has `BmbSys(cpuId=i, cpuCnt=N)` with independent watchdog, unique CPU ID, CmpSync interface. Only core 0 has `BmbUart`.
- **Pipeline halt**: `io.halted` from CmpSync through BmbSys directly stalls pipeline
- **Boot sequence**: Core 0 runs full init (GC, class init). Other cores wait for `started` flag, then enter main loop.
- **LED driver**: LED[0]=core 0 WD bit 0, LED[1]=core 1 WD bit 0 (active low)
- **Test app**: `NCoreHelloWorld.java` — each core reads `IO_CPU_ID`, core 0 prints + toggles WD, others just toggle WD
- **FPGA build**: `make full-smp` in QMTECH or CYC5000 dirs (separate Quartus projects: `jop_smp_sdram.qsf`, `jop_smp_cyc5000.qsf`)
- **Resource usage**: ~12K LEs (8% of EP4CGX150), substantial headroom for more cores

## SDR SDRAM GC Hang (Resolved)

- ROOT CAUSE: SpinalHDL's `SdramCtrl` DQ I/O timing
- Replaced with Altera `altera_sdram_tri_controller` BlackBox -> GC works on both boards (QMTECH 2000+, CYC5000 9800+ rounds)
- Altera controller has `FAST_INPUT_REGISTER=ON` / `FAST_OUTPUT_REGISTER=ON` synthesis attributes
- Files: `AlteraSdramBlackBox.scala`, `AlteraSdramAdapter.scala`, patched `altera_sdram_tri_controller.v`
- `BmbSdramCtrl32(useAlteraCtrl=true)` selects Altera path, `false` (default) keeps SpinalHDL SdramCtrl
- See `sdr-sdram-gc-hang.md` for full investigation history
