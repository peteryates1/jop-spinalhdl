# Formal Verification

The JOP SpinalHDL implementation includes comprehensive formal verification using SpinalHDL's `FormalDut` framework, backed by [SymbiYosys](https://github.com/YosysHQ/sby) (Yosys + Z3 SMT solver). All verification code is in separate testbench files — no assertions are embedded in the RTL.

## Overview

**97 properties verified** across **16 test suites** covering all major components:

| Category | Suite | Tests | Properties |
|----------|-------|:-----:|------------|
| **Core Arithmetic** | MulFormal | 7 | Register init, operand loading, result stability, 8-bit functional correctness, zero/one multiplication, restart behavior |
| | ShiftFormal | 5 | USHR/SHL/SHR correctness, zero shift identity, full shift (31) |
| **Pipeline Stages** | JumpTableFormal | 5 | Exception > interrupt > normal priority chain, mutual override |
| | FetchStageFormal | 4 | PC priority (jfetch > br > jmp), pipeline freeze on wait+busy, default increment |
| | BytecodeFetchStageFormal | 4 | No double-ack, exception/interrupt ack preconditions, interrupt latching |
| | StackStageFormal | 6 | Flag computation (zf, nf, eq), SP increment/decrement/hold |
| | DecodeStageFormal | 4 | Memory op mutual exclusion, field op mutual exclusion, br/jmp exclusion, reset safety |
| **Memory Subsystem** | MethodCacheFormal | 6 | State machine transitions (IDLE/S1/S2), rdy output, find trigger, S2 always returns, inCache stability |
| | ObjectCacheFormal | 5 | Invalidation clears valid bits and FIFO pointer, uncacheable field rejection, hit implies cacheable, no hit after reset |
| | BmbMemoryControllerFormal | 12 | Initial state, busy correctness, exception/copy returns to IDLE, READ/WRITE_WAIT completion + hold, IDLE stability |
| **DDR3 Subsystem** | LruCacheCoreFormal | 9 | Initial state, busy correctness, memCmd gating, evict/refill commands, error recovery, no-deadlock, **2 bugs found and fixed** (see below) |
| | CacheToMigAdapterFormal | 8 | Initial state, busy correctness, IDLE stability, no-deadlock, MIG signal gating, read data capture, write completion |
| **I/O Subsystem** | CmpSyncFormal | 6 | Lock mutual exclusion, deadlock freedom, signal broadcast, gcHalt isolation, IDLE no-halt, **lock owner exempt from gcHalt** |
| | BmbSysFormal | 6 | Clock counter monotonicity, exception pulse, lock acquire/release/hold, halted passthrough |
| | BmbUartFormal | 5 | TX push gating, RX pop gating, no spurious TX, status register accuracy (bits 0 and 1) |
| **BMB Protocol** | BmbProtocolFormal | 5 | rsp.ready always true, cmd.last always true, cmd.valid held until ready, cmd address/opcode/data stable while not accepted |

## Toolchain

| Tool | Version | Purpose |
|------|---------|---------|
| [Yosys](https://github.com/YosysHQ/yosys) | 0.52 | Verilog synthesis front-end |
| [SymbiYosys](https://github.com/YosysHQ/sby) | 0.62 | Formal verification driver |
| [Z3](https://github.com/Z3Prover/z3) | 4.13.3 | SMT solver (primary, all tests use Z3) |

Install on Debian/Ubuntu:

```bash
sudo apt-get install -y yosys z3
git clone https://github.com/YosysHQ/sby /tmp/sby
cd /tmp/sby && sudo make install PREFIX=/usr/local
```

## Running

```bash
# Run all 97 formal tests (~100 seconds)
sbt "testOnly jop.formal.*"

# Run a specific suite
sbt "testOnly jop.formal.StackStageFormal"

# Run a specific test
sbt "testOnly jop.formal.StackStageFormal -- -t *zero*"
```

## File Structure

```
spinalhdl/src/test/scala/jop/formal/
├── SpinalFormalFunSuite.scala      # Base class (local, not from SpinalHDL lib)
├── MulFormal.scala                 # Radix-4 Booth multiplier
├── ShiftFormal.scala               # Barrel shifter
├── JumpTableFormal.scala           # Bytecode-to-microcode jump table
├── FetchStageFormal.scala          # Microcode fetch stage
├── BytecodeFetchStageFormal.scala  # Bytecode fetch + interrupt handling
├── StackStageFormal.scala          # ALU, flags, SP management
├── DecodeStageFormal.scala         # Microcode decoder
├── MethodCacheFormal.scala         # Method cache tag lookup
├── ObjectCacheFormal.scala         # Object field cache
├── BmbMemoryControllerFormal.scala # Memory controller state machine
├── LruCacheCoreFormal.scala        # DDR3 write-back cache (2 bugs found + fixed)
├── CacheToMigAdapterFormal.scala   # DDR3 MIG protocol adapter
├── CmpSyncFormal.scala             # SMP global lock
├── BmbSysFormal.scala              # System I/O slave
├── BmbUartFormal.scala             # UART I/O slave
└── BmbProtocolFormal.scala         # BMB bus protocol compliance
```

## Methodology

### Test Structure

Each test uses SpinalHDL's `FormalDut` wrapper with SymbiYosys BMC (Bounded Model Checking):

```scala
class ExampleFormal extends SpinalFormalFunSuite {
  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))

  test("property name") {
    formalConfig
      .withBMC(depth)
      .doVerify(new Component {
        val dut = FormalDut(MyComponent())
        assumeInitial(ClockDomain.current.isResetActive)
        anyseq(dut.io.input)           // unconstrained symbolic input
        when(pastValidAfterReset()) {
          assert(dut.io.output === expected)
        }
      })
  }
}
```

### Techniques

- **Unconstrained inputs** (`anyseq`): Inputs are fully symbolic — the solver explores all possible input sequences. Used for combinational properties (flags, priority logic) where the property must hold for all inputs.

- **Constrained inputs**: Inputs are driven to specific values. Used for sequential properties (SP management, state machine tests) where we need to control which path the design takes.

- **Shadow models**: For the multiplier, a shadow counter tracks cycles since `wr` and a reference model captures operands, with assertions at the expected completion cycle.

- **BMC depth selection**: Combinational components use depth 2 (1 reset + 1 check). Sequential components use depth 3-6 depending on pipeline depth. The BytecodeFetchStage uses depth 4-6 with a 120s timeout due to its large internal memories (256-entry ROM + 2KB RAM).

## Bugs Found

Formal verification of the DDR3 write-back cache (`LruCacheCore`) found two bugs confirmed by Z3 counterexamples. Both have been fixed.

### Bug 1: Read Hit Treated as Miss When rspFifo Full

**File**: `LruCacheCore.scala` lines 127-148
**Severity**: Performance / potential stall
**Test**: `LruCacheCoreFormal` — "BUG: read hit consumed when rspFifo full"

In the IDLE state read path, the `otherwise` branch catches both `!reqHit` (genuine miss) AND `reqHit && !rspFifo.io.push.ready` (hit but response FIFO full):

```scala
// READ path in IDLE state
when(reqHit && rspFifo.io.push.ready) {
  // Read hit: respond immediately ✓
} otherwise {
  // "Read miss" — BUT ALSO fires when reqHit && !rspFifo.push.ready!
  cmdFifo.io.pop.ready := True  // Consumes request
  state := ISSUE_EVICT or ISSUE_REFILL  // Unnecessary eviction!
}
```

**Impact**: A read hit to a dirty cache line, when the response FIFO is full, triggers an unnecessary eviction (write-back to DDR3) followed by a refill (read from DDR3). The request is consumed from the command FIFO regardless. Data integrity is preserved (eviction writes dirty bytes first, refill reads them back), but the operation takes ~30-50 cycles instead of 0.

**Contrast**: The write path handles this correctly — `when(reqHit && rspFifo.io.push.ready)` / `elsewhen(!reqHit)` — leaving an implicit stall for the `reqHit && !rspFifo.push.ready` case.

**Fix** (applied): Add an explicit `elsewhen(!reqHit)` guard to the read path, matching the write path pattern.

### Bug 2: Eviction Response Blocked by Unrelated FIFO State

**File**: `LruCacheCore.scala` line 165
**Severity**: Unnecessary stall
**Test**: `LruCacheCoreFormal` — "BUG: WAIT_EVICT_RSP blocks memRsp on successful eviction"

In `WAIT_EVICT_RSP`, the memory response ready signal is gated on `rspFifo.io.push.ready`:

```scala
is(LruCacheCoreState.WAIT_EVICT_RSP) {
  io.memRsp.ready := rspFifo.io.push.ready  // ← BUG: gates on rspFifo
  when(io.memRsp.valid && rspFifo.io.push.ready) {
    when(io.memRsp.payload.error) {
      rspFifo.io.push.valid := True  // Error: push to rspFifo ✓
      state := IDLE
    } otherwise {
      // Success: does NOT push to rspFifo, but still gated!
      validArray(pendingIndex) := False
      state := ISSUE_REFILL
    }
  }
}
```

**Impact**: Successful evictions (the common case) don't push to rspFifo, but the response acceptance is unnecessarily blocked when rspFifo is full. This stalls the entire DDR3 pipeline until the consumer drains the response FIFO.

**Fix** (applied): Accept the response unconditionally, only gate on `rspFifo.io.push.ready` for the error path.

### Triggering Analysis

With the current `BmbCacheBridge` frontend, these bugs are **unlikely to trigger** because the bridge serializes to at most 1 outstanding cache request, so the rspFifo (depth 4) can never fill. However, the bugs are latent correctness issues that would surface with any pipelining of the frontend, and could contribute to the [DDR3 GC hang](ddr3-gc-hang.md) if there is any scenario where backpressure accumulates.

### Performance Considerations

| Component | BMC Depth | Time per Test | Notes |
|-----------|:---------:|:------------:|-------|
| Shift (combinational) | 2 | <0.3s | Purely combinational, trivial for Z3 |
| JumpTable | 2 | <0.3s | Combinational ROM lookup |
| StackStage flags | 3 | <0.3s | Unconstrained inputs, simple property |
| MethodCache | 5-6 | <0.5s | Small state machine, 16-entry tag array |
| BmbMemoryController | 4-6 | <1.0s | Large state machine, wait-state tests use anyseq(rsp.valid) |
| BytecodeFetchStage | 4-6 | 0.5-3s | 256-entry ROM + 2KB RAM make Z3 slow |
| Mul (8-bit correctness) | 20 | ~1s | 8-bit width; 32-bit is intractable for Z3 |
| LruCacheCore | 20 | 2-47s | Deep BMC needed to reach cache hit states |
| CacheToMigAdapter | 8-12 | <1.5s | Small state machine, 128-bit data registers |

### Known Limitations

- **32-bit multiplication correctness**: Direct comparison of `dut.dout === (a * b)[31:0]` is intractable for Z3 at 32-bit width (stuck at BMC step 20 for >10 min). Verified using structural properties (register init, operand loading, result stability, zero/one multiplication) and functional correctness at 8-bit width.

- **BMB payload stability**: The cmd payload stability check (held while `valid && !ready`) constrains `rsp.valid := False` to prevent responses from triggering state transitions mid-handshake. In practice this is correct because the controller has at most one outstanding command.

- **BMC vs induction**: All properties use bounded model checking, not unbounded induction proofs. BMC at depth N guarantees the property holds for the first N clock cycles after reset, but does not prove it holds forever. For the components verified here, the BMC depths are sufficient to exercise all reachable states.
