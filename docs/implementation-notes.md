# JOP SpinalHDL Implementation Notes

Detailed implementation notes for the SpinalHDL JOP port. These are reference notes
captured during development — see the source code for authoritative details.

## Bugs Found & Fixed (30 total)

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
13. **SpinalHDL SdramCtrl CKE gating bug (simulation only)**: The library `SdramCtrl` has a power-saving CKE gating mechanism: `sdramCkeNext = !(readHistory.orR && !io.bus.rsp.ready)`. When `io.bus.rsp.ready` goes low while reads are in-flight (e.g., BMB arbiter backpressure from another core), CKE gates off. But `remoteCke` (which freezes the command pipeline) is 2 cycles delayed, creating a window where SDRAM commands are issued but the SDRAM ignores them (CKE=0). This misaligns the context pipeline with actual responses, causing data corruption. Fix: created `SdramCtrlNoCke` — local copy with `sdramCkeNext := True` (CKE never gates off). CKE gating is a power-saving feature not needed for JOP. Used by `BmbSdramCtrl32` when `useAlteraCtrl=false` (simulation path).
14. **DebugProtocol GOT_CORE payload length bug**: In `DebugProtocol.scala` RX state machine, `GOT_CORE` state used `(rxLenHi ## byte)` to compute payload length, but `byte` is the CORE byte (just received), not LEN_LO. For core=0, `combinedLen` always evaluated to `rxLenHi << 8` (i.e., 0 for any payload < 256 bytes), causing the parser to skip PAYLOAD and jump directly to CHECK_CRC. The CRC then mismatched (computed over only header, not payload bytes), and the command was silently dropped. All commands with payload sent to core 0 were broken (READ_STACK, READ_MEMORY, WRITE_MEMORY, SET_BREAKPOINT, etc.). Commands without payload (PING, HALT, QUERY_STATUS, READ_REGISTERS) worked because `len=0` was correct for them. Fix: use `rxLen` (from already-updated `rxLenHi`/`rxLenLo` registers). Found by JopDebugProtocolSim automated test.
15. **DebugController packWord bit extraction on narrow signals**: `packWord()` extracts `value(31 downto 24)` etc. from a `Bits` argument. Callers used `.resized` (a resize-on-assignment tag) which does NOT widen the value before extraction. SpinalHDL crashed at elaboration with "static bits extraction outside range" for signals narrower than 32 bits (PC=11, JPC=12, SP/VP/AR=8, flags=4, instr=10, bcopd=16). Fix: use `.resize(32 bits)` for explicit widening before calling `packWord`.
16. **JopCore debugHalted wired to wrong signal**: `io.debugHalted` was wired to `bmbSys.io.halted` (CmpSync halt status) instead of `io.debugHalt` (debug controller halt input). The debug halted output visible to test harnesses and FPGA top-levels always read as False in single-core configs (no CmpSync). Fix: wire to `io.debugHalt`. Found by JopDebugProtocolSim automated test.
17. **readAcache/readOcache persistence across I/O reads**: After an iaload A$ hit (or getfield O$ hit), the `readArrayCache` (or `readObjectCache`) register stays True until the state machine leaves IDLE. But I/O reads in IDLE don't change state, so the cache output MUX override persists into the next memory operation. If that operation is an I/O read, the returned data is from the cache instead of the I/O bus — causing data corruption (manifested as 4x UART output expansion matching the A$ line size). Fix: clear `readArrayCache`/`readObjectCache` on any new memory operation in IDLE. The iaload/getfield hit handler re-sets the flag via SpinalHDL "last assignment wins" semantics.
18. **Array cache SMP coherency (4-core SDRAM regression)**: Per-core Array Cache (A$) has no cross-core invalidation. `ac.io.inval` is driven only by the local core's `stidx`/`cinval` signals. When Core X does `iastore` to a shared array, Core X's A$ is invalidated and SDRAM is updated, but Core Y's A$ still holds the stale value. On the next `iaload` hit, Core Y reads stale cached data instead of the updated SDRAM value. This caused a 4-core SDRAM regression: stale data led to a wrong method pointer, which read from SDRAM word 4 (SYS_PTRI = 0xFFFFFF80, written by Core 0 during JVM init), interpreted it as a method descriptor, computed a BC_FILL address of 0x3FFFFF with length 896, fetched zeros from uninitialized SDRAM, and got Core 3 stuck executing invalid bytecodes at PC=0x01dd. Symptom: 4-core + A$ = FAIL, 4-core without A$ = PASS, single-core + A$ = PASS. Fix: cross-core snoop invalidation via `CacheSnoopBus` (see bug #18 above was later fixed properly with snoops, not by disabling A$).
19. **A$ fill interleaving corruption (4-core+ SDRAM)**: AC_FILL issued 4 individual single-word BMB reads (one per cache line element). Between each read, the BMB arbiter could switch to another core. With 4+ cores, complex response interleaving through BmbSdramCtrl32's 32-to-16-bit bridge created a corruption window: stale responses from other cores' transactions could misalign the burst word counter (`burstWordsSent`), causing a 1-word data shift. The corrupted A$ data propagated as a bad method pointer, leading to BC_FILL with garbage address (0xffff86) and core hang. Symptom: 4-core SDRAM + A$ hangs (Core 3 stuck in BC_FILL_LOOP), 2/3-core fine, BRAM fine (no BmbSdramCtrl32 bridge). Fix: converted AC_FILL from 4 single-word reads to a single burst read when `burstLen > 0` (SDRAM mode). Burst reads hold the bus via `burstActive=True` in BmbSdramCtrl32 (same mechanism that keeps BC_FILL safe). BRAM mode (`burstLen == 0`) keeps the existing single-word loop since the bug doesn't manifest there. Also widened `burstLengthWidth` in JopMemoryConfig to accommodate A$ fill burst size. Verified: 4-core and 8-core SDRAM pass with A$ enabled on QMTECH EP4CGX150 hardware.
20. **BytecodeFetchStage branch type remapping**: The bytecodes `if_acmpeq`(0xa5), `if_acmpne`(0xa6), `ifnull`(0xc6), and `ifnonnull`(0xc7) have lower 4 bits that don't match the standard branch type encoding used by `if_icmp*`/`if*` bytecodes. Without explicit remapping, these bytecodes evaluated wrong branch conditions (e.g., `if_acmpne` tested bit pattern 0x6 instead of the "not equal" condition). Symptom: JVM tests ArrayTest2, Ifacmp, BranchTest1, StackManipulation all failed. Fix: added explicit `switch(jinstr)` remapping in BytecodeFetchStage.scala matching VHDL `bcfetch.vhd` behavior: 0xa5→15 (eq type), 0xa6→0 (ne type), 0xc6→9 (ifeq type), 0xc7→10 (ifne type).
21. **System.arraycopy A$ stale data**: `System.arraycopy` (`java/runtime/src/jvm/java/lang/System.java`) copies array elements using `Native.wrMem()` — raw memory writes via the `stmwd` microcode instruction. These writes go through BmbMemoryController Layer 1 (IDLE→WRITE_WAIT) which has no A$ invalidation. A$ invalidation only fires on `stidx` (getfield/putfield/getstatic/putstatic microcode) or `cinval` (`Native.invalidate()`). After arraycopy writes new values to the destination array's data area, any A$ entries for that array contain stale pre-copy data. Subsequent `aaload` (which goes through the `iaload` hardware path and checks A$ combinationally) returns the stale cached values. Symptom: SystemCopy JVM test failed — `compare(oSrc, oDest)` returned false because `oDest[i]` via aaload returned null (pre-arraycopy value) while `rdMem` on the same address returned the correct post-arraycopy value. Confirmed by: (1) disabling A$ makes test pass, (2) adding `Native.invalidate()` before oDest aaload makes test pass. Fix: added `Native.invalidate()` after the copy loops in `System.arraycopy`. This clears all A$ entries, ensuring subsequent aaload operations fill from memory. The original VHDL JOP may not have encountered this because A$ was not always enabled in test configurations.
22. **sys_exc microcode calling wrong exception handler (latent original JOP bug)**: The `sys_exc` microcode handler (`asm/src/jvm.asm`) used `ldi 6` to invoke the method at `jjhp + 6`, which is `JVMHelp.monitorState()` — not `JVMHelp.except()`. All hardware-triggered exceptions (null pointer, array bounds) threw `IllegalMonitorStateException` regardless of the actual exception type written to `IO_EXCPT`. Root cause: `jjhp` (JVMHelp Java Help Pointer) points past the 4 inherited Object methods (`<init>`, `hashCode`, `equals`, `toString`), so `JVMHelp`'s own methods start at `jjhp+0`. The method layout is: `+0`=interrupt, `+2`=nullPoint, `+4`=arrayBound, `+6`=monitorState, `+8`=except. The code used offset 6 (monitorState) instead of 8 (except). This was a latent bug present in the original VHDL JOP that went undetected because hardware exception detection was apparently never exercised end-to-end in the original test suite. Fix: changed `ldi 6` to `ldi 8` in `asm/src/jvm.asm`. The `except()` method reads `IO_EXCPT` to determine the exception type and throws the pre-allocated `NPExc` or `ABExc` object accordingly. Symptom before fix: "Uncaught exception" for all hardware NPE/ABE — the thrown `IllegalMonitorStateException` didn't match any `catch (NullPointerException)` handler.
23. **invokevirtual/invokeinterface null pointer missing delay slots**: In `asm/src/jvm_call.inc`, the null pointer check for `invokevirtual` and `invokeinterface` used `jmp null_pointer` without the required 2 delay slot NOPs. JOP's 3-stage microcode pipeline requires `nop; nop` after any `jmp` to avoid executing instructions from the fall-through path. Without delay slots, the `add` and `stmra` instructions after the branch were executed before the jump took effect, corrupting the stack (wrong values for TOS/NOS). Fix: changed to `pop; jmp null_pointer; nop; nop` — the `pop` discards the null reference from TOS before jumping, and the NOPs fill the delay slots.
24. **GC conservative stack scanner null handle dereference**: `GC.push()` (`java/runtime/src/jop/com/jopdesign/sys/GC.java`) is called by the conservative scanner for every value on the stack. When the stack contains null (address 0), `push()` proceeds to `Native.rdMem(ref + OFF_PTR)` — a read from address 0 (plus small offset). With hardware NPE detection enabled, this triggers a spurious null pointer exception during GC scanning, crashing the system. The existing code comment says "null pointer check is in the following handle check" — the range check `ref < mem_start` was supposed to catch null, but only if `mem_start > 0`, which isn't guaranteed at all execution points. Fix: added explicit `if (ref == 0) return;` guard at the top of `push()`. This is a no-op for non-zero refs (one comparison, one branch) and prevents any hardware NPE from the scanner.
25. **Method cache tag=0 false hit (latent original JOP bug)**: MethodCache (`jop.memory.MethodCache`) uses tag=0 as the initial/cleared value for evicted blocks. When a method lookup occurs with addr=0 (which can happen during certain `.jop` file layouts, e.g., when the first method struct is at address 0 in the bytecode space), ALL evicted blocks match the lookup because their cleared tag (0) equals the search address (0). The cache reports a false HIT on a block with no valid bytecodes, causing corrupt instruction fetch. The original VHDL `mcache.vhd` has the same bug — it uses `tag(0 to ...)` with cleared values of all-zeros and no valid bit. Symptom: PutRef JVM test failed (bytecode fetch returned wrong instructions after method call/return sequences that triggered tag=0 lookups). Fix: added `tagValid` bit per block in `MethodCache.scala`. The S1 parallel tag check now requires `tagValid(i) && tag(i) === useAddr` for a hit. The S2 allocation state clears `tagValid` for evicted blocks and sets it for the newly allocated block. This prevents any cleared/evicted block from matching a lookup, regardless of the tag value.
26. **Hardware exception I/O address mismatch**: BmbMemoryController's NPE/ABE detection wrote to I/O address 0x04 instead of 0x84 (SYS_EXC). After the I/O scheme changed to use SYS_BASE=0x80, the exception writes were silently ignored by BmbSys. Fixed: 0x04→0x84 in all 3 locations (HANDLE_READ null check, HANDLE_READ negative index, HANDLE_BOUND_WAIT upper bounds). Additionally, div-by-zero in f_idiv/f_irem/f_ldiv/f_lrem changed from hardware exception (`Native.wrMem(EXC_DIVZ, IO_EXCPT)`) to direct Java throw (`throw JVMHelp.ArithExc`) — the hardware exception path corrupted the stack frame, making ArithmeticException uncatchable by Java try/catch.
27. **BmbSdNative WAIT_CRC_STATUS missing start bit wait**: After the host sends a data block on DAT0, the SD card responds with a 3-bit CRC status (start bit + 2 status bits). The `WAIT_CRC_STATUS` state sampled these bits immediately on entry, without waiting for the card's start bit (DAT0 low). The card needs Ncrc SD clock cycles (typically 2+) after the host's end bit before it drives CRC status. At 10 MHz, the first sample captured the pull-up (1), yielding status 101 (CRC error) instead of 010 (data accepted). The write itself succeeded — the card accepted the data — but `dataCrcError` was falsely set. This went undetected in simulation because the testbench responds without Ncrc delay. Symptom on FPGA: T2:WRITE reported FAIL (dataCrcError=1) even though the data was correctly written (T3:READ verified it). Fix: wait for DAT0 low (start bit) before counting CRC status bits, with a timeout (dataTimeoutCnt reaches 0xFFFFF → dataTimeout, transition to DONE). Found by SD Native Exerciser hardware test on QMTECH EP4CGX150 + DB_FPGA.
28. **BmbSdNative dataTimeoutCnt not reset on startWrite**: The `dataTimeoutCnt` counter (used for data transfer timeouts) was reset to 0 on `startRead` but not on `startWrite`. After fixing bug #27 to use `dataTimeoutCnt` for the CRC status start-bit timeout, a write following a read that had incremented the counter would start with a non-zero timeout value, potentially causing premature or delayed timeouts. Fix: added `dataTimeoutCnt := 0` to the `startWrite` handler alongside the existing `dataBusy`, `dataCrcError`, `dataTimeout`, and `dataCrcFlags` resets.

29. **BytecodeFetchStage jopd corruption during stack cache rotation stall**: `BytecodeFetchStage` had no stall input — during stack cache bank rotation (770+ cycle stall), `jopd(7 downto 0) := jbcData` was unconditionally updated every cycle. After operand accumulation for `invokestatic` completed (e.g., `jopd = 0x0003`), the JBC RAM continued reading at `jpc` (which pointed past the operand bytes to the next bytecode, `istore_3 = 0x3E`). One cycle into the stall, `jopd(7:0)` was overwritten: `0x0003 → 0x003E`. When the stall ended and `ld_opd_16u` executed, it read the corrupted operand `0x003E` (constant pool index 62) instead of `0x0003` (index 3). This fetched a bogus method struct address (`0x010D8001` from a different class's data area), causing `invoke_vpsave` to compute invalid VP/method pointers, zeroing VP+0 and hanging the processor. Only manifested with deep recursion (200+ levels) because 3 bank rotations were needed before the stall coincided with an in-flight `invokestatic` operand. Fix: added `stall` input to `BytecodeFetchStage`, gated `jopd`/`jpc`/`jinstr`/`jpc_br` updates with `when(!io.stall)`, wired `stackRotBusy` → `bcfetch.io.stall` in `JopPipeline`. Files changed: `BytecodeFetchStage.scala`, `JopPipeline.scala`, `InterruptTestTb.scala`, `JopSimulator.scala`, `BytecodeFetchStageTest.scala`, `BytecodeFetchStageFormal.scala`.

30. **BCEL-injected SWAP test operand stack imbalance**: The `InjectSwap` BCEL visitor replaced `doSwap()` with `iload_1, iload_0, SWAP, ireturn` — leaving **two** values on the operand stack at `ireturn`. JOP's `ireturn` microcode pops the return value (`stm a`), then expects the method pointer at TOS (`dup; stmrac; stm mp`), followed by the constant pool pointer and old VP. The extra integer operand (42) was misinterpreted as a method pointer address, causing the memory controller to issue a read to address 42 and hang permanently (BSY forever at the `wait` instruction). The SWAP microcode itself (`stm a; stm b; ldm a; ldm b nxt`) works correctly — pipeline analysis confirmed scratch RAM writes and reads are properly timed. **Lesson: when injecting bytecodes via BCEL, the operand stack depth at `ireturn`/`areturn`/`freturn` must be exactly 1 (the return value). JOP's microcode-based return sequence pops fixed positions from the stack — it does not discard the current frame like a standard JVM. Any extra values corrupt the frame unwinding.** Fix: changed injected sequence to `iload_0, iload_1, SWAP, POP, ireturn` — the POP removes the swapped-down value, leaving exactly 1 return value on the stack.

## Method Cache

- **MethodCache** (`jop.memory`): Tag-only lookup matching VHDL `mcache` entity
  - 16 blocks (blockBits=4), each 32 words, FIFO replacement
  - Hit: 2 cycles, Miss: 3 cycles + fill
  - `rdy` is combinational (state===IDLE), `inCache` is registered
  - `find` is combinational (same clock edge as bcRd in IDLE)
  - `clrVal` pre-computed every cycle for displaced tag clearing
  - `tagValid` bit per block prevents false hits on evicted blocks (see bug #25)
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
  - **Interrupt chain** (matching VHDL sc_sys.vhd): timer_equ → timer_int → intstate → priority encoder → irq_gate → irq pulse. NUM_INT = numIoInt + 1 (timer + I/O). Interrupt source mapping: index 0 = timer, index 1 = UART RX (ioInt(0)), index 2 = UART TX (ioInt(1)). intstate is an SR flip-flop per source (set on hwreq|swreq, cleared on ack|clearAll). Priority encoder gives highest index priority. int_ena register gates final irq output; automatically cleared on ackIrq or ackExc. SW interrupt via addr 2 write (yield). Interrupt number captured in intNr on ackIrq, readable at addr 2. End-to-end verified: `JopInterruptSim` boots InterruptTest.jop, fires 5 timer interrupts at 5000 us intervals, Java handler prints 'T' per interrupt.
  - **irq/irqEna are internal to JopCore**: BmbSys generates irq/irqEna, wired directly to JopPipeline. No external irq/irqEna ports on JopCore/JopCoreWithBram/JopCoreWithSdram. ackIrq/ackExc flow back from BytecodeFetchStage through JopPipeline to BmbSys.
- **BmbUart**: UartCtrl + TX FIFO (16) + RX FIFO (16). Status (addr 0): bit0=TDRE, bit1=RDRF, bit2=TX int enabled, bit3=RX int enabled. Data (addr 1): read consumes RX FIFO, write pushes TX FIFO. Interrupt control (addr 2 write): bit0=TX int enable, bit1=RX int enable. Interrupt outputs: `rxInterrupt` (single-cycle pulse on rising edge of RX FIFO non-empty when enabled) and `txInterrupt` (single-cycle pulse on rising edge of TX FIFO empty when enabled). Pulses are critical — BmbSys `intstate` is an SR flip-flop, level signals would cause interrupt storms. Wired to `bmbSys.io.ioInt(0)`/`ioInt(1)` in JopCore (when UART present).
- **BmbSdNative**: SD card controller in native 4-bit mode. Hardware CRC7 (CMD) and CRC16 (DATA). 512-byte block FIFO. CMD state machine (IDLE→SENDING→WAIT_RSP→RECEIVING→DONE) with 64-edge timeout. Data state machine (IDLE→SEND_START→SENDING→SEND_CRC→SEND_STOP→WAIT_CRC_STATUS→WAIT_BUSY→DONE for writes; IDLE→WAIT_START→RECEIVING→RECV_CRC→DONE for reads). Clock divider: `SD_CLK = sys_clk / (2 * (div + 1))`, init at 99 (~400 kHz). Control reg write ALWAYS updates openDrain (bit 2). Response register NOT cleared on sendCmd (stale on timeout). startRead/startWrite both clear dataCrcError, dataTimeout, dataCrcFlags. FIFO reset only on startRead or abort (not startWrite). readSync has 1-cycle pipeline latency (BlockRAM). See bugs #27-28.
- **BmbSdSpi**: SD card controller in SPI mode. SPI Mode 0 (CPOL=0, CPHA=0). Byte-at-a-time transfer: write TX byte to addr 1, poll busy (addr 0 bit 0), read RX byte from addr 1. CS control via addr 0 write (bit 0). Clock divider: `SPI_CLK = sys_clk / (2 * (div + 1))`, init at 199 (~200 kHz at 80 MHz). SD SPI and SD Native are mutually exclusive (share card slot pins).
- Both use same I/O interface: addr(4 bits), rd, wr, wrData(32), rdData(32)
- Top-levels wire: `ioSubAddr = ioAddr(3:0)`, `ioSlaveId = ioAddr(5:4)`
- **SysDevice field->address mapping**: cntInt(0), uscntTimer(1), intNr(2), wd(3), exception(4), lock(5), cpuId(6), signal(7), intMask(8), clearInt(9), deadLine(10), nrCpu(11), perfCounter(12), gcHalt(13)
- **HANDLE_ACCESS I/O routing**: HardwareObject fields (getfield/putfield) have data pointers in I/O space. BmbMemoryController checks `addrIsIo` in HANDLE_ACCESS and routes to I/O bus instead of BMB.
- **SMP sync interface**: BmbSys has `io.syncIn`/`io.syncOut` (SyncIn/SyncOut bundles) for CmpSync integration. Write to IO_LOCK (addr 5) sets `lockReqReg`, write to IO_UNLOCK (addr 6) clears it. Read IO_LOCK returns `syncIn.halted` in bit 0. `io.halted` output stalls the pipeline when another core holds the lock.

## Object Cache

- **ObjectCache** (`jop.memory`): Fully associative FIFO, 16 entries x 8 fields = 128 values, matching VHDL `ocache.vhd`
- **Getfield hit**: 0 busy cycles (stays in IDLE, `readObjectCache` selects cached data)
- **Getfield miss**: Normal HANDLE_READ path + cache fill via `wrGf`
- **Putfield**: Always through state machine, write-through on hit via `wrPf`. `chkPf` in PF_WAIT (putfield waste cycle).
- **Invalidation**: `stidx`/`cinval` clears all valid bits (per-core only). `wasHwo` suppresses I/O caching.
- **SMP**: Safe via cross-core snoop invalidation (CacheSnoopBus). Each core's putfield broadcasts on snoop bus; other cores selectively invalidate matching field bits.
- **Cacheable**: Only fields 0-7 (upper fieldIdx bits must be 0)

## Array Cache

- **ArrayCache** (`jop.memory`): Fully associative FIFO, 16 entries x 4 elements = 64 values, matching VHDL `acache.vhd`
- **iaload hit**: 0 busy cycles (stays in IDLE, `readArrayCache` selects cached data)
- **iaload miss**: HANDLE_READ → HANDLE_WAIT → HANDLE_CALC → AC_FILL_CMD → AC_FILL_WAIT → IDLE. SDRAM (burstLen>0): single burst read for all 4 elements (holds bus). BRAM (burstLen==0): 4 individual reads looping AC_FILL_CMD↔AC_FILL_WAIT.
- **iastore**: Normal handle dereference path, write-through on hit via `wrIas` in HANDLE_DATA_WAIT
- **Invalidation**: `stidx`/`cinval` clears all valid bits (per-core only — no cross-core invalidation)
- **SMP**: Safe via cross-core snoop invalidation (CacheSnoopBus) + burst AC_FILL (bug #19). Verified 4-core and 8-core SDRAM on QMTECH hardware.
- **Tags**: Handle address + upper index bits (`index[maxIndexBits-1:fieldBits]`), so different regions of the same array map to different lines
- **VHDL bug fixes**: (1) idx_upper slice uses `fieldBits` not `fieldCnt`, (2) FIFO nxt advances once per fill, not once per wrIal

## Hardware memCopy (GC)

- **States**: CP_SETUP -> CP_READ -> CP_READ_WAIT -> CP_WRITE -> LAST (+ CP_STOP for stopbit)
- **stcp timing**: At `stcp`: TOS=pos, NOS=src. After implicit pop: TOS=src, NOS=dest (read in CP_SETUP)
- **Address translation**: NOT applied (causes timing violation at 100MHz). Single-core GC doesn't need it.
- **GC.java**: `Native.memCopy(dest, addr, i)` loop + stopbit `Native.memCopy(dest, dest, -1)`

## GC App (Phase 3)

- **Runtime**: Small runtime from jopmin (56 files) — GC, Scheduler, IOFactory, more JDK stubs
- **App**: `java/apps/Small/src/test/HelloWorld.java` — allocates int[32] arrays in a loop, reports free memory
- **GC behavior**: Incremental GC triggered proactively at 25% free heap. STW fallback (`finishCycleNow()`) drains remaining work when heap exhausted. Full STW `gc()` as last resort.
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
- **IHLU** (optional, replaces CmpSync): 32-slot fully-associative CAM lock table for per-object hardware locking. Reentrant, FIFO wait queues, GC halt drain mechanism. Enabled via `useIhlu` flag in `JopCoreConfig`. See `jop.io.Ihlu`.
- **Per-core I/O**: Each core has `BmbSys(cpuId=i, cpuCnt=N)` with independent watchdog, unique CPU ID, CmpSync interface. Only core 0 has `BmbUart`.
- **Pipeline halt**: `io.halted` from CmpSync through BmbSys directly stalls pipeline
- **Boot sequence**: Core 0 runs full init (GC, class init). Other cores wait for `started` flag, then enter main loop.
- **Cache coherency**: A$ and O$ use cross-core snoop invalidation via `CacheSnoopBus`. A$ fill uses burst reads on SDRAM (bug #19 fix) to prevent interleaving corruption.
- **LED driver**: LED[i]=core i WD bit 0 (active low). 7 on-board LEDs + PMOD for 8+ cores.
- **Test app**: `NCoreHelloWorld.java` — each core reads `IO_CPU_ID`, core 0 prints + toggles WD, others just toggle WD. Small variant includes GC.
- **FPGA build**: `make full-smp` in QMTECH or CYC5000 dirs (separate Quartus projects: `jop_smp_sdram.qsf`, `jop_smp_cyc5000.qsf`)
- **Verified core counts**: 4-core (21% LE, +3.0 ns slack), 8-core (42% LE, +1.9 ns slack) at 100 MHz; 16-core (86% LE, +1.8 ns slack) at 80 MHz on EP4CGX150. 12/16-core fail at 100 MHz (insufficient timing margin). PLL frequency configurable in `dram_pll.vhd` + `JopSdramTop.scala`.

## SMP GC Stop-the-World

- **Problem**: Original JOP SMP has no stop-the-world GC mechanism. GC uses `synchronized(mutex)` which only halts cores entering synchronized blocks — cores running normal code (e.g., reading I/O registers, toggling watchdog) keep executing and can read partially-moved objects from SDRAM during the copying phase, causing corruption.
- **Symptom**: NCoreHelloWorld on CYC5000 SMP ran ~140 println iterations (~70 seconds), then output corrupted to a flood of 'C' characters before both cores hung. This matched the ~160 allocation limit of the 28KB heap (semi-space at the time, now mark-compact).
- **Fix**: Hardware GC halt signal via `IO_GC_HALT` (BmbSys addr 13):
  - `SyncIn` bundle: added `gcHalt` field (core → CmpSync direction)
  - `CmpSync`: when any core's `gcHalt` is set, all OTHER cores' pipelines are halted. The halting core itself is NOT affected. Lock owner is exempt from gcHalt (see CmpSync deadlock fix above).
  - `BmbSys`: `gcHaltReg` register, write 1 to set, write 0 to clear. Routed to `syncOut.gcHalt`.
  - `GC.java`: `Native.wr(1, Const.IO_GC_HALT)` before `gc()`, `Native.wr(0, Const.IO_GC_HALT)` after.
  - Single-core: gcHalt output is unconnected (no CmpSync), no effect.
- **Future**: Protect the halt flag with CmpSync lock for safe multi-core allocation (currently only core 0 allocates). Any core triggering GC should acquire the lock first, then set gcHalt.
- **Verified**: CYC5000 SMP hardware — NCoreHelloWorld running 3+ minutes through multiple GC cycles with no corruption. SMP GC BRAM sim shows `halted=.H` during GC rounds.

## Debug Subsystem (`jop.debug` package)

- **DebugConfig**: numBreakpoints (0-8), baudRate, hasMemAccess
- **DebugTransport**: Stream[Bits(8)] byte-stream abstraction (UART or sim direct-drive)
- **DebugUart**: Dedicated UART with 16-byte FIFOs for debug transport (separate from main UART)
- **DebugProtocol**: Framed binary protocol with CRC-8/MAXIM. RX: SYNC(0xA5) + TYPE + LEN_HI + LEN_LO + CORE + PAYLOAD + CRC → parsed command. TX: response fields → framed bytes. Streaming payload mode for WRITE_MEMORY_BLOCK.
- **DebugController**: Command FSM handling halt/resume, micro/bytecode step, register/stack/memory read/write, breakpoint management, async HALTED notifications. One instance serves all cores. BMB master for memory access.
- **DebugBreakpoints**: Per-core hardware breakpoint comparators (PC or JPC), first-free-slot allocation, hit detection gated by halted state.
- **Crc8Maxim**: CRC-8/MAXIM (polynomial 0x31, reflected 0x8C) hardware calculator + combinational helper.
- **Integration**: Optional in JopCluster via `debugConfig` parameter. `None` = zero cost. `Some(DebugConfig(...))` adds DebugProtocol + DebugController + per-core DebugBreakpoints. Debug BMB master added to arbiter when `hasMemAccess=true`.
- **Test**: `JopDebugProtocolSim` — 39 automated tests covering PING, QUERY_INFO, HALT, QUERY_STATUS, READ_REGISTERS, READ_STACK, READ_MEMORY, WRITE_MEMORY, SET/QUERY/CLEAR_BREAKPOINT, STEP_MICRO, RESUME. `JopDebugSim` — TCP bridge for Eclipse debugger (manual).

## JVM Test Suite (`java/apps/JvmTests/`)

58 tests covering JVM bytecode correctness, run via `JopJvmTestsBramSim` (BRAM, 25M cycles). All 58 pass.

**Test categories**:
- **Core bytecodes**: Basic/Basic2 (stack/local ops), TypeMix, Static, StackManipulation
- **Arrays**: ArrayTest2, ArrayTest3, ArrayMulti, MultiArray, SystemCopy (System.arraycopy)
- **Branches**: BranchTest1/2/3, Switch, Switch2, Logic1/2/3, Ifacmp
- **Arithmetic**: Imul, IntArithmetic (iadd/isub/imul/idiv/iand/ior/ixor/iinc), LongTest, LongArithmetic (ladd/lsub/lmul/ldiv/land/lor/lxor), LongField
- **Float**: FloatTest (fadd/fsub/fmul/fdiv/fneg/fcmp/f2i)
- **Type conversions**: Conversion, TypeConversion (i2b/i2s/i2c/i2l/i2f/i2d/l2i/l2f/l2d/f2i/f2l/f2d/d2i/d2l/d2f)
- **Constant loading**: ConstLoad (iconst/bipush/sipush/ldc boundary values)
- **Field access (all types)**: IntField, ShortField, ByteField, CharField, BooleanField, FloatField, DoubleField, ObjectField — each tests putfield/getfield and putstatic/getstatic with boundary values
- **OOP**: InstanceCheckcast, CheckCast, InvokeSpecial, InvokeSuper, SuperTest, InstanceOfTest, Iface, Clinit/Clinit2
- **Exceptions**: Except (throw/catch, cast, recursive, finally — throw9 disabled), AthrowTest, NullPointer (13 sub-tests: explicit throw, invokevirtual, getfield/putfield int/long/ref, invokespecial, invokeinterface, iaload, iastore, aaload), PutRef (NPE + array bounds), DivZero
- **String operations**: StringConcat (toString, "x="+42, negative/zero ints, String.valueOf, multiple appends, StringBuilder resize via System.arraycopy)
- **Other**: NativeMethods, CachePersistence, DeepRecursion (200-level recursion, exercises stack cache bank rotation)

**Ported from**: Original JOP `jvm/` suite (Martin Schoeberl) + `jvmtest/` suite (Guenther Wimpassinger). The `jvmtest/` tests used a hash-based `TestCaseResult` framework; converted to the `jvm.TestCase` boolean pattern.

**Known platform limitations discovered during porting**:
- **Div-by-zero fixed**: Changed f_idiv/f_irem/f_ldiv/f_lrem from hardware exception to `throw JVMHelp.ArithExc`. `DivZero.java` test enabled (58/58 pass).
- **No wide iinc**: JOP's PreLinker rejects iinc constants outside -128..127. IntArithmetic uses `x = x + val` (iadd) instead of `x += val` (wide iinc) for large increments.
- **Float constant folding**: javac evaluates literal float expressions (e.g., `2.0F * 3.0F`) at compile time, so actual fmul/fdiv/fneg/fcmp bytecodes never execute. FloatTest uses variables to prevent this.

## Intentionally Unused VHDL Signals

- **putref**: The `putref` memory operation (from `stprf` microcode) exists in the decode stage and BmbMemoryController but is never used by current JOP bytecodes. In the original VHDL, putref is part of the SCJ (Safety-Critical Java) scope check mechanism — it's a variant of putstatic that would validate reference assignment safety in an SCJ runtime. Our JOP port doesn't implement SCJ scope checks, so putref fires but is handled identically to putstatic (no additional logic).
- **atmstart/atmend**: The `atmstart`/`atmend` memory operations (from `statm`/`endatm` microcode) are SimpCon atomicity markers. In the original VHDL SimpCon-based memory interface, they bracketed multi-cycle memory operations to prevent bus arbitration between start and end. With BMB (which has native transactions with backpressure), atomicity is handled by the bus protocol itself and CmpSync's global lock. These signals are decoded but have no effect in BmbMemoryController.
- **IHLU per-object locking** (`jop.io.Ihlu`): 32-slot fully-associative CAM lock table for per-object hardware locking, replacing CmpSync's global lock. Reentrant (8-bit counter, depth 255), FIFO wait queues per lock slot, round-robin CPU servicing, GC halt drain mechanism. Conditional via `useIhlu` flag in `JopCoreConfig` — when enabled, `Ihlu` replaces `CmpSync` in `JopCluster`. Extended `SyncIn`/`SyncOut` with `reqPulse`, `data`, `op`, `status` fields for IHLU protocol. `BmbSys` writes to IO_LOCK set `lockDataReg` (object handle) and `lockOpReg` (lock/unlock), with `reqPulse` handshake.

## Hardware Exception Detection (NPE + Array Bounds)

Hardware-triggered exceptions for null pointer (NPE) and array bounds (ABE) violations are fully implemented and enabled. The memory controller detects violations during handle dereference and fires a synchronous exception that unwinds to the Java catch handler.

### Exception Types

| Type | EXC code | Trigger | Detection point |
|---|---|---|---|
| Null pointer | EXC_NP (2) | Handle address == 0 | HANDLE_READ state |
| Array bounds (negative) | EXC_AB (3) | Index MSB set (negative signed int) | HANDLE_READ state |
| Array bounds (upper) | EXC_AB (3) | Index >= array length | HANDLE_BOUND_WAIT state |

### Exception Path

1. **Memory controller** detects violation in HANDLE_READ or HANDLE_BOUND_WAIT
2. Writes exception type to `IO_EXCPT` (BmbSys addr 4) via I/O bus → `excPend` set + `exc` pulse
3. **BytecodeFetchStage** receives `exc` pulse → `excPendImmediate` fires (combinational OR with registered `excPend`, ensuring same-cycle `exc` + `jfetch` is caught immediately)
4. On next `jfetch`, exception has priority over interrupts → JumpTable outputs `sys_exc` microcode address
5. **sys_exc microcode** (`asm/src/jvm.asm`): decrements JPC by 1 (so exception points at faulting bytecode), loads `jjhp + 8`, calls `JVMHelp.except()` via `invoke`
6. **`JVMHelp.except()`**: reads `IO_EXCPT` to determine type, throws pre-allocated `NPExc` or `ABExc`
7. **`f_athrow`** microcode: unwinds stack frames searching exception tables for matching catch handler

### Key Design Details

- **NP_EXC/AB_EXC states are NOT busy**: The memory controller transitions to NP_EXC or AB_EXC and immediately becomes non-busy. This allows the pipeline to continue executing the remaining microcode instructions (nop/pop cleanup) of the faulting bytecode while the exception pulse propagates through BmbSys and BytecodeFetchStage. The exception is caught on the next `jfetch`, matching the VHDL `npexc`/`abexc` behavior.
- **excPendImmediate**: BytecodeFetchStage uses `excPend || io.exc` combinationally so that if the `exc` pulse and `jfetch` arrive in the same cycle, the exception is caught immediately rather than missing this jfetch and firing at the next one (which may be in a different bytecode context, outside the try block).
- **GC safety**: `GC.push()` has an explicit `ref == 0` guard to prevent the conservative stack scanner from triggering hardware NPE on null handles (bug #24).
- **sys_exc offset**: `jjhp + 8` = `except()` method. The `jjhp` pointer skips 4 inherited Object methods, so JVMHelp's own methods start at offsets 0, 2, 4, 6, 8 (each method table entry is 2 words). See bug #22 for the original offset error.

### VHDL Comparison

The original VHDL `mem_sc.vhd` has NPE/ABE detection code, but the upper bounds check is gated by `rdy_cnt /= 0` which makes it effectively dead code in normal operation. Our implementation doesn't have this gating issue — HANDLE_BOUND_READ/WAIT states execute unconditionally for array accesses.

### Known Limitations

- **Hardware div-by-zero not catchable**: JOP's `f_idiv` microcode fires a hardware exception asynchronously (after JPC has advanced), so the exception unwinds at the wrong JPC and finds no matching catch handler. The NPE/ABE path works because those fire synchronously during the faulting bytecode (JPC still correct).

### Resolved Issues

- **invokespecial on null**: Now detected. Separated `invokespecial` from `invokestatic` in `asm/src/jvm_call.inc` with explicit null check. Reads `margs` from `methodStruct+1` packed word (bits 4:0), computes objectref stack position as `sp + 2 - margs`, loads via `star`/`ldmi`, checks null with `bnz`. Delay slots (`ldvp`/`stm old_vp`) execute on both paths. Not-null path restores method struct from register `b`, jumps to `invoke_vpsave`.
- **NullPointer test "layout sensitivity"**: The reported crash when combining NullPointer tests 3-6 with the PutRef test class was NOT a layout issue. Root cause was a combination of: (1) method cache tag=0 false hit (bug #25) — PutRef's method call patterns exposed the false cache hit, corrupting bytecode fetch, and (2) 128KB BRAM was insufficient for the 50-test suite (19751 program words, only 13017 left for heap → GC fails during class init). Both are now fixed: tagValid bit in MethodCache, 256KB BRAM for test harnesses. All 9 NullPointer sub-tests (T0-T8) pass alongside PutRef.

## DDR3 TODO

- **Calibration gate**: DDR3 top-level (`JopDdr3Top`) should gate JOP reset on MIG calibration complete (`init_calib_complete`). Currently, JOP starts running before DDR3 is ready, which could cause early memory accesses to fail. In practice, MIG calibration completes well before JOP's boot sequence reaches first SDRAM access, so this hasn't been an issue.

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

## GC Architecture Analysis

### Current Implementation (Mark-Compact + Incremental)

The GC has been upgraded from semi-space copying to **mark-compact with incremental phases**:

**Mark-Compact** (replaced semi-space): Single contiguous heap, ~2x usable memory vs semi-space. Algorithm: toggle toSpace mark (1↔2) → tri-color mark from roots → sort use list by address → slide live objects down → sweep dead handles → zero free region.

**Incremental Extension**: Mark and compact phases split into bounded increments interleaved with mutator execution:
- `MARK_STEP = 20` gray objects per mark increment
- `COMPACT_STEP = 10` handles per compact increment
- Proactive trigger at 25% free heap threshold (`tryGcIncrement()` in `newObject`/`newArray`)
- STW fallback (`finishCycleNow()`) drains remaining work when heap exhausted
- Root scan remains STW (conservative stack scanning requires atomicity)

**GC Phase State Machine**: `IDLE → ROOT_SCAN (STW) → MARK (incremental) → COMPACT (incremental) → IDLE`

Verified: 58/58 JVM tests pass, BRAM GC (3+ cycles), SDRAM GC (100+ rounds), QMTECH FPGA (2000+ rounds), CYC5000 FPGA (9800+ rounds), DDR3 FPGA (1870+ rounds at 256MB), SMP 2-core BRAM GC.

### Previous Implementation (Semi-Space — Replaced)

The previous GC (`java/runtime/src/jop/com/jopdesign/sys/GC.java`) was a **semi-space copying collector** with conservative stack scanning:

**Memory Layout** (from `GC.init()`):
```
[0..mem_start):                               Application code + class structures
[mem_start .. mem_start + handle_cnt*8):      Handle pool (8-word handles)
[heapStartA .. heapStartA + semi_size):       Semi-space A
[heapStartB .. heapStartB + semi_size):       Semi-space B
```
- `handle_cnt = full_heap_size >> 4` (1/16th of heap)
- `semi_size = (full_heap_size - handle_cnt * 8) / 2`
- 50% of heap is wasted (only one semi-space usable at a time)

**Handle Structure** (8 words each):
- OFF_PTR (0): Pointer to actual object data in heap
- OFF_MTAB_ALEN (1): Method table pointer (objects) or array length (arrays)
- OFF_SPACE (2): Space marker (toSpace/fromSpace)
- OFF_TYPE (3): Object type (IS_OBJ=0, IS_REFARR=1, or primitive array types 4-11)
- OFF_NEXT (4): Free/use list threading
- OFF_GREY (5): Gray list threading (-1=end, 0=not in list)

**GC Cycle** (`gc()`):
1. `Native.wr(1, Const.IO_GC_HALT)` — halt all other cores (SMP)
2. Discard stale gray list entries
3. `flip()` — swap toSpace/fromSpace
4. `markAndCopy()` — scan roots (stack + statics), trace gray list, copy live objects
5. `sweepHandles()` — reclaim handles whose objects are still in fromSpace
6. `zapSemi()` — zero the old fromSpace (major SDRAM bandwidth cost)
7. `Native.wr(0, Const.IO_GC_HALT)` — resume other cores

**Write Barriers** (snapshot-at-beginning, in `JVM.java`): `f_aastore`, `f_putfield_ref`, `f_putstatic_ref` check the OLD value being overwritten. If it's white (not in toSpace, not in gray list), push it to the gray list. This ensures concurrent mutations don't lose references the GC hasn't traced yet.

**Conservative Stack Scanning**: `GC.push()` is called for every value on the stack. If the value falls in the handle address range and looks like a valid handle, it's treated as a root. False positives prevent collection but don't cause corruption (handle addresses never change, only OFF_PTR inside the handle changes).

### Original JOP GC (full version, `/srv/git/jop/`)

The full original JOP GC is more sophisticated than our jopmin fork:
- `concurrentGc` flag exists for switching between stop-the-world and concurrent mode
- `GCStkWalk.java` / `GCRTMethodInfo.java` provide **exact stack scanning** (stack maps computed at link time, stored as bitmaps per PC value) — currently not used (conservative scanning is the default)
- GC can run as a periodic real-time thread for concurrent operation
- Hardware address translation (`translateAddr()` in BmbMemoryController) redirects accesses to partially-copied objects — exists but unused in our STW implementation

### Hardware GC Support Already Present

| Feature | Location | Status |
|---|---|---|
| memCopy states | `BmbMemoryController.scala` (CP_SETUP→CP_READ→CP_READ_WAIT→CP_WRITE→CP_STOP) | Working |
| Address translation | `BmbMemoryController.scala` `translateAddr()` | Implemented, **unused** (causes timing violation at 100MHz) |
| GC halt (IO_GC_HALT) | BmbSys addr 13 → CmpSync → pipeline stall | Working |
| Cache snoop invalidation | `CacheSnoopBus.scala` | Working (A$/O$ cross-core invalidation) |
| Handle dereference | HANDLE_READ/CALC/ACCESS states | Working (inherent hardware read barrier) |

### Optional Future GC Enhancements

Mark-compact and incremental mark-compact are both implemented (see "Current
Implementation" above). The remaining enhancements are hardware-assisted
optimizations:

**Hardware-Assisted Concurrent GC**

- Move handle table to dual-port BRAM (eliminates SDRAM contention during GC scanning; 1024 handles × 8 words = 32KB fits in BRAM)
- Hardware read barrier in HANDLE_CALC (check if handle points to fromSpace, redirect to toSpace)
- Fix address translation timing (pipeline the comparison to meet 100MHz)
- Hardware bulk copy DMA (burst writes instead of per-word memCopy)
- Hardware handle sweep accelerator (scan handles at 1/cycle vs ~10/cycle in software)
- Concurrent mark on dedicated core (8+ core SMP only)

**Not recommended**:
- Generational GC: Over-engineered for embedded workloads, doesn't exploit JOP's handle architecture
- Pure mark-sweep: Fragmentation will crash long-running systems

### SDRAM Bandwidth Considerations

The 16-bit SDRAM bus at 80MHz gives ~160MB/s raw, but effective is much lower (CAS latency, row activation, refresh, turnaround). With 16 cores sharing, GC must minimize SDRAM traffic. Mark-compact advantage: one pass over live objects (vs semi-space which copies all live + zeroes all of fromSpace).

### Handle Table Scaling

Current formula: `handle_cnt = full_heap_size >> 4`, capped at `MAX_HANDLES = 65536`. For 64KB heap = 1024 handles (32KB). For 256MB heap = 65536 handles (512K words = 2MB, ~0.8% of heap). Without the cap, a 256MB heap would create 4.2M handles (33MB!), and GC sweep would scan all handles every cycle (~42ms at 100MHz), causing GC to hang after ~104 rounds. The MAX_HANDLES cap limits sweep to ~6ms at 100MHz. Verified: 1870+ GC rounds stable on 256MB DDR3 FPGA hardware.

## IHLU (Individual Hardware Locking Unit)

### Overview

The original JOP includes `ihlu.vhd` (`/srv/git/jop/vhdl/scio/ihlu.vhd`), a fine-grained per-object hardware locking unit designed for the Jeopard RTSJ framework. It is an alternative to CmpSync's single global lock.

**Architecture**:
- 32 lock entries (CAM-based matching on 32-bit data value / object handle)
- Per-lock FIFO queues (RAM-backed) for waiting cores
- Reentrant counting (same core can acquire same lock multiple times)
- 4-state machine: idle → ram → ram_delay → operation
- Allows cores to hold different monitors simultaneously (vs CmpSync which serializes all locks)

**Advantages over CmpSync**:
- True per-object locking: `synchronized(objA)` on core 0 doesn't block `synchronized(objB)` on core 1
- Higher SMP throughput for applications with independent critical sections
- Matches Java semantics more closely (each object has its own monitor)

### GC Interaction Problem

IHLU complicates stop-the-world GC significantly:

**Current (CmpSync)**: At most 1 lock holder at a time. GC sets IO_GC_HALT, lock owner is exempt (must finish critical section), all other cores halt immediately. Once owner releases, it halts too. GC proceeds with all cores stopped.

**With IHLU**: Multiple cores can hold different locks simultaneously. When GC fires:
- Cannot simply halt all non-owners (there may be multiple owners)
- Cannot halt owners (they must release their locks first)
- GC may never get to run if cores continuously acquire new locks

**Solution: Drain approach**:
1. GC sets a "lock freeze" flag — no new lock acquisitions allowed (IHLU returns "busy" to new requests)
2. Existing lock holders continue executing until they release
3. GC waits for all lock holders to release
4. All cores now halted (no lock holders, new acquisitions blocked)
5. GC runs, clears freeze flag when done

This adds latency to GC start (bounded by max critical section length) and requires changes to both IHLU hardware and the GC software.

### Implementation Status

IHLU is NOT implemented in the SpinalHDL port. The current CmpSync global lock is sufficient for the current SMP use case. IHLU is a future enhancement for higher SMP throughput, but the GC interaction must be designed carefully before implementation.
