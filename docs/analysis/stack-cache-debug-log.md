# Stack Cache Debug Log — RESOLVED (Bug #29)

> **STATUS: RESOLVED.** Bug #29 (BytecodeFetchStage jopd corruption during
> stack cache rotation stall) has been fixed. The DeepRecursion test (200-level
> recursion) passes. All 58 JVM tests pass. The investigation log below is
> preserved for reference.

## Resolution

**Root cause**: `BytecodeFetchStage` had no stall input. During stack cache bank rotation (770+ cycle stall), `jopd(7 downto 0) := jbcData` was unconditionally updated every cycle, overwriting the correctly-accumulated `invokestatic` operand (`0x0003`) with the next bytecode (`0x3E` = istore_3). This caused `invokestatic` to read the wrong constant pool index (62 instead of 3), fetching a bogus method struct address from a different class's data area, which corrupted VP+0 and hung the processor.

**Fix**: Added `stall` input to `BytecodeFetchStage`, gated `jopd`/`jpc`/`jinstr`/`jpc_br` updates with `when(!io.stall)`, wired `stackRotBusy` in `JopPipeline.scala`. See `implementation-notes.md` bug #29 for full details.

---

## Original Bug Summary (Pre-Fix Investigation)
**Symptom**: Non-deterministic stack data corruption after 3rd bank rotation (overflow case) in the DeepRecursion test. A register gets garbage value 0x1FA21706 (varies across runs) at cycle ~5242140, corrupting the BC fill address. All other 56 JVM tests pass (now 58).

**Failing test**: `DeepRecursion` (200-level recursion crossing 3+ bank boundaries) within `JopJvmTestsStackCacheBramSim`.

## Bank State at Corruption
After 3rd rotation: B0=[832,1024) resident/dirty, B1=[640,832) resident/dirty, B2=[1024,1216) resident/clean (zero-filled). SP oscillates between 1023 (B0) and 1024 (B2), causing rapid instant bank switches every cycle.

## Corruption Trace (from jvmtests_stackcache_bram_simulation.log)
```
PR[ 5242138] PC= 414 SP= 1023 ... ldmrd    MC=IDLE  A=0x00000000 B=0x00000061
PR[ 5242139] PC= 415 SP= 1024 ... stbcrd   MC=IDLE  A=0x00000000 B=0x00000061
PR[ 5242140] PC= 416 SP= 1023 ... dup      MC=IDLE  A=0x1FA21706 B=0x00000000 WR[1024]=0x61 B2@0
PR[ 5242141] PC= 417 SP= 1024 ... ldi 8    MC=BC_CHK A=0x00000000 B=0x00000061 BC:cap=0x1FA21706
```

## Critical Trace Format Discovery
The trace format has a **1-cycle pipeline shift** that initially caused incorrect analysis:

- **instrName at cycle C** = `disasmAt(prevPc)` where `prevPc = PC at cycle C-1`
- **This equals `ROM[PC_{C-1}]`** = the instruction **actually in IR at cycle C**
- **NOT** `ROM[PC_C]` as initially assumed

So the instrName at each cycle correctly identifies what's in the IR register at that cycle.

## Corrected ROM Address Mapping
```
ROM[395]=add, ROM[396]=stmrac, ROM[397]=ldm 0, ROM[398]=stm 13,
ROM[399]=stm 0, ROM[400]=wait, ROM[401]=wait, ROM[402]=ldmrd,
ROM[403]=ldjpc, ROM[404]=ldbcstart, ROM[405]=sub, ROM[406]=stm 15,
ROM[407]=ldm 0, ROM[408]=stmrac, ROM[409]=ldm 1, ROM[410]=stm 16,
ROM[411]=wait, ROM[412]=wait, ROM[413]=ldmrd, ROM[414]=stbcrd,
ROM[415]=dup
```
(This corrects the earlier WRONG mapping where I offset all addresses by 1.)

## Pipeline Timing Model

### IR Timing
- IR at cycle C = ROM[PC_{C-1}] (async ROM read from registered address)
- PC at cycle C = pcMux from cycle C-1

### Registered Decode Timing
- enaAReg at edge C = decode(IR at cycle C-1) [gated by stall]
- This fires 1 cycle after the instruction enters IR

### A Register Timing
- **A at cycle C is driven by decode of IR at cycle C-2** (2-cycle lag from IR)
- Chain: IR at C-2 → registered decode at edge C-1 → A at edge C
- The pre-edge value of enaAReg at edge C equals what was set at edge C-1

### din MUX (JopPipeline.scala line 178)
```scala
val dinMuxSel = RegNext(fetch.io.ir_out(1 downto 0)) init(0)
stack.io.din := dinMuxSel.mux(
  0 -> io.memRdData,     // ldmrd
  1 -> mul.io.dout,      // ldmul
  2 -> io.memBcStart,    // ldbcstart
  3 -> B(0, 32 bits)
)
```
dinMuxSel has the **same timing** as the registered decode (both are RegNext of IR-derived signals), so din is correctly aligned with selLmux="100".

### Verified Results Under 2-Cycle Model
| Cycle | A value | Source (decode from IR at C-2) | Verified? |
|-------|---------|-------------------------------|-----------|
| 5242122 | 0x010D8002 | add: B+A = 0x010D8001+1 | ✓ |
| 5242126 | 0x00000061 | stm 0 (POP): A:=B | ✓ |
| 5242127 | 0x00000061 | wait (NOP): A unchanged | ✓ |
| 5242128 | 0x00000061 | wait (NOP): A unchanged | ✓ |
| 5242129 | 0x00000000 | ldmrd: A:=din (memRdData) | ✓ (din=0) |
| 5242130 | 0x00000092 | ldjpc: A:=jpc | ✓ (plausible) |
| 5242131 | 0x00000080 | ldbcstart: A:=din (memBcStart=0x80) | ✓ |
| 5242132 | 0x00000012 | sub: A:=B-A=0x92-0x80 | ✓ |
| 5242133 | 0x00000000 | stm 15 (POP): A:=B=0 | ✓ |
| 5242134 | 0x010D8001 | ldm 0: A:=ramDout | **DISCREPANCY** |

### Discrepancy at Cycle 5242134
Under the 2-cycle model:
- A at 5242134 = from decode(IR at 5242132) = decode(ldm 0)
- ldm 0 is PUSH, selLmux="010" (ramDout)
- ramRdaddrReg was set from selRda="111" (dirAddr for ldm), which reads VP+0
- VP+0 = 1015, which is in B0 at physical offset 183
- B0[183] should contain the "this" reference for the method
- **Expected**: A = value at VP+0 in B0 (should be a valid handle address)
- **Actual**: A = 0x010D8001

The value 0x010D8001 IS the expected handle address (it appears consistently throughout the trace as the "this" reference). So this actually CONFIRMS the 2-cycle model: the ldm 0 correctly reads the handle from VP+0 in the bank RAM.

Wait - this means the 2-cycle model IS correct, and my earlier discrepancy analysis was wrong because I used the wrong address (1023 instead of VP+0 = 1015).

### Corrected Analysis for A at 5242134
- decode of IR at 5242132 = ldm 0 (PUSH)
- selRda = "111" → rdaddr = dirAddr
- For ldm 0: dirAddr = vpadd = VP + 0 = 1015
- ramRdaddrReg at cycle 5242133 = 1015
- Bank B0 covers [832, 1024). 1015 is in B0 at physical 1015-832 = 183
- ramDout = B0[183] = 0x010D8001 (the "this" handle)
- A at 5242134 = 0x010D8001 ✓

## Current Theory: MC Read Returns Wrong Data
Under the 2-cycle model, the corruption at cycle 5242140:
- A at 5242140 from decode(IR at 5242138) = decode(ldmrd at ROM[413])
- ldmrd: PUSH, enaA=True, selLmux="100" (din), dinMuxSel="00" (memRdData)
- A := rdDataReg (the MC's persistent read result register)
- rdDataReg was set by the stmrac MC read that completed at cycle ~5242136
- The stmrac reads from address A at cycle 5242134 = ???

Need to determine: what address does the second stmrac read from? Under the 2-cycle model, A at the time rdc fires (cycle 5242134) should be the handle address 0x010D8001. The MC reads from external memory at this handle address. The result is whatever is at that memory word. If it's uninitialized or random, we get the non-deterministic corruption value.

**Key question**: Is 0x010D8001 a valid memory address for the method handle lookup in the invoke sequence? Or is the address wrong due to earlier stack corruption?

## Theories Explored

### Theory 1: Zero-fill insufficient (RULED OUT)
Zero-fill of overflow bank completes correctly (192 cycles). Corruption persists after fix. The problem is NOT stale data in the zero-filled bank.

### Theory 2: BMB bus stuck (RULED OUT)
MC reads complete fine. BMB is not the issue.

### Theory 3: BC fill parameters from corrupted stack (CONFIRMED)
The BC fill's `cap=0x1FA21706` comes from A being corrupted before stbcrd fires.

### Theory 4: Pipeline stall edge timing (ONGOING)
The corruption happens ~31 cycles after rotation completes, during normal execution with instant bank switches. The rotBusy/rotBusyDly gating mechanism appears theoretically correct for the rotation stall itself. The issue may be in the data flow during rapid instant bank switches.

### Theory 5: ramDout reading wrong bank data (NEEDS INVESTIGATION)
If the bank RAM readAsync returns wrong data due to timing of bank metadata vs read address, A could get garbage.

### Theory 6: MC rdc address wrong due to pipeline timing (NEEDS INVESTIGATION)
If the MC reads from the wrong address because A has the wrong value when rdc fires, rdDataReg gets garbage, and the later ldmrd loads that garbage into A.

## Next Steps
1. **Add debug signals** to trace: enaA, selLmux, selAmux, ramDout, din, dinMuxSel, ramRdaddrReg at each cycle in the post-rotation window
2. **Run simulation** with debug signals to see what actually drives A at cycle 5242140
3. **Compare** the MC read address for the first stmrac (works) vs second stmrac (fails)
4. **Check** if the address 0x010D8001 wraps to valid memory in the 512KB BRAM

## Files Involved
- `spinalhdl/src/main/scala/jop/pipeline/StackStage.scala` - Stack cache, A/B regs, rotation controller
- `spinalhdl/src/main/scala/jop/pipeline/FetchStage.scala` - IR/PC pipeline
- `spinalhdl/src/main/scala/jop/pipeline/DecodeStage.scala` - Combinational + registered decode
- `spinalhdl/src/main/scala/jop/JopPipeline.scala` - din MUX, signal wiring
- `spinalhdl/src/main/scala/jop/memory/BmbMemoryController.scala` - MC, rdDataReg
- `spinalhdl/src/test/scala/jop/system/JopStackCacheSim.scala` - Test harness + trace code
- `spinalhdl/jvmtests_stackcache_bram_simulation.log` - Previous sim output
