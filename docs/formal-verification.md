# Formal Verification

The JOP SpinalHDL implementation includes comprehensive formal verification using SpinalHDL's `FormalDut` framework, backed by [SymbiYosys](https://github.com/YosysHQ/sby) (Yosys + Z3 SMT solver). All verification code is in separate testbench files — no assertions are embedded in the RTL.

## Overview

**77 properties verified** across **14 test suites** covering all major components:

| Category | Suite | Tests | Properties |
|----------|-------|:-----:|------------|
| **Core Arithmetic** | MulFormal | 7 | Register init, operand loading, result stability, 8-bit functional correctness, zero/one multiplication, restart behavior |
| | ShiftFormal | 6 | USHR/SHL/SHR correctness, zero shift identity, full shift (31), all types combined |
| **Pipeline Stages** | JumpTableFormal | 5 | Exception > interrupt > normal priority chain, mutual override |
| | FetchStageFormal | 4 | PC priority (jfetch > br > jmp), pipeline freeze on wait+busy, default increment |
| | BytecodeFetchStageFormal | 4 | No double-ack, exception/interrupt ack preconditions, interrupt latching |
| | StackStageFormal | 6 | Flag computation (zf, nf, eq), SP increment/decrement/hold |
| | DecodeStageFormal | 4 | Memory op mutual exclusion, field op mutual exclusion, br/jmp exclusion, reset safety |
| **Memory Subsystem** | MethodCacheFormal | 6 | State machine transitions (IDLE/S1/S2), rdy output, find trigger, S2 always returns, inCache stability |
| | ObjectCacheFormal | 5 | Invalidation clears valid bits and FIFO pointer, uncacheable field rejection, hit implies cacheable, no hit after reset |
| | BmbMemoryControllerFormal | 10 | Initial state, busy correctness, exception/copy returns to IDLE, READ/WRITE_WAIT completion, IDLE stability |
| **I/O Subsystem** | CmpSyncFormal | 5 | Lock mutual exclusion, deadlock freedom, signal broadcast, gcHalt isolation, IDLE no-halt |
| | BmbSysFormal | 6 | Clock counter monotonicity, exception pulse, lock acquire/release/hold, halted passthrough |
| | BmbUartFormal | 5 | TX push gating, RX pop gating, no spurious TX, status register accuracy (bits 0 and 1) |
| **BMB Protocol** | BmbProtocolFormal | 4 | rsp.ready always true, cmd.last always true, cmd address/opcode/data stable while not accepted |

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
# Run all 77 formal tests (~40 seconds)
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

### Performance Considerations

| Component | BMC Depth | Time per Test | Notes |
|-----------|:---------:|:------------:|-------|
| Shift (combinational) | 2 | <0.3s | Purely combinational, trivial for Z3 |
| JumpTable | 2 | <0.3s | Combinational ROM lookup |
| StackStage flags | 3 | <0.3s | Unconstrained inputs, simple property |
| MethodCache | 5-6 | <0.5s | Small state machine, 16-entry tag array |
| BmbMemoryController | 4 | <0.7s | Large state machine, constrained BMB slave |
| BytecodeFetchStage | 4-6 | 0.5-3s | 256-entry ROM + 2KB RAM make Z3 slow |
| Mul (8-bit correctness) | 20 | ~1s | 8-bit width; 32-bit is intractable for Z3 |

### Known Limitations

- **32-bit multiplication correctness**: Direct comparison of `dut.dout === (a * b)[31:0]` is intractable for Z3 at 32-bit width (stuck at BMC step 20 for >10 min). Verified using structural properties (register init, operand loading, result stability, zero/one multiplication) and functional correctness at 8-bit width.

- **BMB payload stability**: The cmd payload stability check (held while `valid && !ready`) constrains `rsp.valid := False` to prevent responses from triggering state transitions mid-handshake. In practice this is correct because the controller has at most one outstanding command.

- **BMC vs induction**: All properties use bounded model checking, not unbounded induction proofs. BMC at depth N guarantees the property holds for the first N clock cycles after reset, but does not prove it holds forever. For the components verified here, the BMC depths are sufficient to exercise all reachable states.
