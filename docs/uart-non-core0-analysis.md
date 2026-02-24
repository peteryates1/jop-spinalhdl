# UART Access Behavior for Non-Core-0 in JOP SMP

## 1. Current SpinalHDL Behavior

### Code Path for UART Access

When any core executes `Native.rd(Const.IO_UART_STATUS)` (address `0xffffff90`), the following path is taken:

1. **Memory controller** (`BmbMemoryController.scala`): Detects I/O address space, asserts `ioRd` with `ioAddr = 0x10` (lower 8 bits of the address).

2. **JopCore I/O routing** (`JopCore.scala`, lines 224-333): Decodes device ID from `ioAddr(5 downto 4)`:
   - Device 0 (`ioDeviceId = 0`): BmbSys (system registers)
   - Device 1 (`ioDeviceId = 1`): BmbUart (UART)
   - Device 2 (`ioDeviceId = 2`): BmbEth (Ethernet)
   - Device 3 (`ioDeviceId = 3`): BmbMdio

3. **UART status address**: `IO_STATUS = IO_BASE + 0x10`, so `ioAddr = 0x10`, giving `ioDeviceId = 1`, `ioSubAddr = 0`.

4. **Per-core UART instantiation** (`JopCluster.scala`, line 123): Only core 0 gets `hasUart = true`. Cores 1-15 get `hasUart = false`.

5. **When `hasUart = false`** (`JopCore.scala`, line 254): `bmbUart` is `None`. The I/O read MUX at lines 316-332 has this code:

```scala
is(1) {
  if (bmbUart.isDefined) ioRdData := bmbUart.get.io.rdData
  // No UART: ioRdData stays 0 (TDRE=0). Cores without UART must not
  // access it -- reads will return "TX busy" causing a hang, which makes
  // the bug visible. TODO: consider an exception or debug trap here.
}
```

6. **Result**: `ioRdData` stays at its default value of `0`. The UART status register's bit 0 is TDRE (Transmitter Data Register Empty). TDRE=0 means "transmitter busy, do not send."

### The Hang Mechanism

The `JVMHelp.wr()` method (`JVMHelp.java`, lines 327-332) is the ultimate UART output routine:

```java
public static void wr(int c) {
    // busy wait on free tx buffer
    while ((Native.rd(Const.IO_UART_STATUS) & 1) == 0) {
    }
    Native.wr(c, Const.IO_UART_DATA);
}
```

On cores 1-15, `Native.rd(Const.IO_UART_STATUS)` always returns 0, so the `while` loop spins forever. The core is silently hung with no diagnostic output and no exception. This affects:

- Direct `JVMHelp.wr()` calls
- `System.out.print()` / `System.out.println()` (via `PrintStream` -> `JOPOutputStream` -> `JVMHelp.wr()`)
- `JVMHelp.noim()` (unimplemented bytecode error handler)
- `JVMHelp.trace()` (stack trace)
- `Startup.msg()`, `Startup.version()`, and error paths in `Startup.interpret()`

### UART Write Path

Writes to the UART data register (`IO_UART_DATA = IO_BASE + 0x11`, `ioDeviceId = 1`, `ioSubAddr = 1`) on a non-UART core are silently discarded. The `ioWr` pulse fires with `ioDeviceId = 1`, but since `bmbUart` is `None`, there is no component to receive it. This is harmless in isolation, but the write is never reached because the status poll loop hangs first.

## 2. VHDL Reference Behavior

### Architecture in `jopmul.vhd`

The original VHDL multi-core JOP uses a fundamentally different I/O topology for non-core-0:

**Core 0** (`jopmul.vhd`, lines 300-322): Gets full `scio` (the I/O subsystem entity), which instantiates both `sc_sys` and `sc_uart` as I/O slaves. Address decoding uses `address(5 downto 4)` to select between slaves.

**Cores 1+** (`jopmul.vhd`, lines 325-353): Get ONLY `sc_sys`, connected directly:

```vhdl
gen_io: for i in 1 to cpu_cnt-1 generate
    io2: entity work.sc_sys generic map (
        addr_bits => 4,
        clk_freq => clk_freq,
        cpu_id => i,
        cpu_cnt => cpu_cnt
    )
    port map(
        clk => clk_int,
        reset => int_res,
        address => sc_io_out(i).address(3 downto 0),
        wr_data => sc_io_out(i).wr_data,
        rd => sc_io_out(i).rd,
        wr => sc_io_out(i).wr,
        rd_data => sc_io_in(i).rd_data,
        rdy_cnt => sc_io_in(i).rdy_cnt,
        ...
    );
end generate;
```

The critical difference: `sc_sys` receives ALL I/O reads and writes, regardless of the device-select bits (`address(5 downto 4)`). The `sc_sys` read MUX only decodes `address(3 downto 0)`:

```vhdl
case address(3 downto 0) is
    when "0000" => rd_data <= clock_cnt;       -- addr 0: clock counter
    when "0001" => rd_data <= us_cnt;           -- addr 1: microsecond counter
    when "0010" => rd_data(4 downto 0) <= intnr;
    ...
end case;
```

### Clock Counter Aliasing Effect

When core 1+ reads `IO_STATUS` (address `0xffffff90`), the lower 4 bits of the address are `0x0` (since `0x90 & 0x0F = 0x00`). The `sc_sys` read MUX at `address = "0000"` returns `clock_cnt` -- the free-running 32-bit clock cycle counter.

This means:
- **UART status read** (`IO_STATUS`, sub-addr 0) returns the clock counter value
- **UART data read** (`IO_UART`, sub-addr 1) returns the microsecond counter value

Since the clock counter is a rapidly changing value, bit 0 (TDRE position) toggles every clock cycle. The `while ((status & 1) == 0)` loop will exit within 1-2 iterations on average because the clock counter's LSB alternates between 0 and 1.

The practical effect: `JVMHelp.wr()` on cores 1+ does NOT hang. It proceeds to the `Native.wr(c, Const.IO_UART_DATA)` write, which hits `sc_sys` at address 1 and writes to `timer_cnt` (the timer compare register). The output is silently discarded (no UART hardware), but the write has the side effect of corrupting the microsecond timer comparison value.

**This is not a clean design** -- it was likely an accidental property of the simplified wiring rather than an intentional feature. The clock counter aliasing prevents hangs but introduces timer corruption as a side effect.

## 3. Fix Options

### Option A: Hardware Exception Trap

Fire a hardware exception (like NPE) when a non-UART core accesses the UART address range.

**Implementation**: In `JopCore.scala`, detect UART I/O access when `hasUart = false` and write an exception type to BmbSys.

**Pros**:
- Immediately visible -- Java code gets an exception with a stack trace
- Catches the bug at development time
- No silent data corruption

**Cons**:
- Requires adding a new exception type (or reusing an existing one like `IllegalMonitorStateException`)
- Pre-allocated exception objects are limited; adding a new type costs memory
- Exception fires at runtime, not compile time -- still a latent bug
- If the exception is not caught, the core dies with no output (since it has no UART)
- The error path in `JVMHelp.except()` itself calls `JVMHelp.wr()`, which would recurse into the same trap

### Option B: Clock Counter Alias (Match VHDL)

Route UART reads on non-UART cores to sc_sys registers, matching the VHDL behavior.

**Implementation**: In the `JopCore.scala` I/O read MUX, when `bmbUart.isEmpty` and `ioDeviceId === 1`, return `bmbSys.io.rdData` instead of 0.

**Pros**:
- Matches VHDL reference behavior exactly
- Prevents the hang -- clock counter LSB toggles every cycle
- Zero additional hardware cost

**Cons**:
- UART writes to non-UART cores corrupt the timer register (address 1 write → `timer_cnt`)
- Silently produces garbage behavior -- output appears to succeed but goes nowhere
- Does not help the developer find the bug
- Timer corruption could cause spurious interrupts or missed timer events

### Option C: Software Guard

Add a CPU ID check in the Java UART code (`JVMHelp.wr()`, `JOPOutputStream`, etc.).

**Implementation**:
```java
public static void wr(int c) {
    if (Native.rd(Const.IO_CPU_ID) != 0) return;  // No UART on non-core-0
    while ((Native.rd(Const.IO_UART_STATUS) & 1) == 0) {}
    Native.wr(c, Const.IO_UART_DATA);
}
```

**Pros**:
- Simple, correct, no hardware changes
- Silently discards output (graceful degradation)
- No hang, no corruption

**Cons**:
- Adds a `Native.rd()` + branch to every character output on core 0 (small performance cost)
- Must be applied to every UART access point (`JVMHelp.wr()`, `JOPInputStream.read()`, etc.)
- Does not catch the bug -- just silently drops output
- If new code accesses UART directly (bypassing `JVMHelp.wr()`), the hang returns

### Option D: Null Device (TDRE=1 Always)

Return TDRE=1 on status reads for non-UART cores, silently discarding writes.

**Implementation**: In `JopCore.scala` I/O read MUX, when `bmbUart.isEmpty` and `ioDeviceId === 1` and `ioSubAddr === 0`, return `B(1, 32 bits)` (TDRE=1, RDRF=0).

**Pros**:
- Prevents the hang -- `JVMHelp.wr()` exits the wait loop immediately
- UART writes are silently discarded (no side effects)
- Clean `/dev/null` semantics -- all output succeeds but goes nowhere
- Minimal hardware cost (one constant MUX case)
- No timer corruption (unlike clock counter alias)
- No software changes required
- Works for all UART access patterns, including direct `Native.rd()` calls

**Cons**:
- Does not help the developer find the bug (silent discard)
- `JOPInputStream.read()` (UART input) would never see RDRF=1, so `available()` returns 0 and `read()` blocks forever. However, UART input on a non-UART core is inherently meaningless (no physical RX line), so this is correct behavior.

## 4. Recommendation

**Option D (Null Device)** is recommended, with a minor enhancement.

### Rationale

1. **Safety**: The primary goal is preventing silent hangs. Option D achieves this with zero side effects -- no timer corruption (unlike B), no performance overhead (unlike C), no recursive exception traps (unlike A).

2. **Correctness**: A `/dev/null` UART is the semantically correct behavior for a core without UART hardware. Output succeeds but goes nowhere. Input is never available. This matches how operating systems handle writes to `/dev/null`.

3. **Simplicity**: The change is a single line in the `JopCore.scala` I/O read MUX. No Java changes, no new exception types, no new microcode.

4. **Robustness**: Unlike Option C, this works for ALL code paths that access the UART -- including direct `Native.rd()` calls, third-party code, and error handlers.

5. **Why not Option B**: The VHDL clock counter aliasing is an accidental property, not a deliberate design. It has the side effect of corrupting the timer register on UART writes. Option D achieves the same hang-prevention without corruption.

6. **Why not Option A**: The hardware exception approach has a fatal recursion problem: `JVMHelp.except()` calls `JVMHelp.wr()` for error reporting, which would trigger another exception on the same core. Additionally, on a core without UART, there is no way to report the exception to the user anyway.

## 5. Implementation Sketch

### Hardware Change (JopCore.scala)

In the I/O read MUX (around line 321), change the `bmbUart.isEmpty` case:

```scala
// I/O read mux
val ioRdData = Bits(32 bits)
ioRdData := 0
switch(ioDeviceId) {
  is(0) { ioRdData := bmbSys.io.rdData }
  is(1) {
    if (bmbUart.isDefined) {
      ioRdData := bmbUart.get.io.rdData
    } else {
      // Null device: TDRE=1 (transmitter always ready), RDRF=0 (no RX data).
      // Prevents hang when non-UART core accidentally reads UART status.
      // Writes are silently discarded (no hardware to receive them).
      when(ioSubAddr === 0) {
        ioRdData := B(1, 32 bits)  // Status: TDRE=1, RDRF=0
      }
      // Sub-addr 1 (data read): returns 0 (no data available)
      // Sub-addr 2 (int control): returns 0
    }
  }
  is(2) { ... }
  is(3) { ... }
}
```

### Verification

1. **Existing SMP simulations**: `JopSmpNCoreHelloWorldSim` and `JopSmpSdramNCoreHelloWorldSim` already run multi-core with cores 1+ not accessing UART. These should continue to pass.

2. **New test case**: Modify `NCoreHelloWorld.java` to have core 1 call `JVMHelp.wr()` and verify it does not hang. The output should be silently discarded, and the simulation should complete normally.

3. **Hardware test**: On QMTECH SMP, have core 1 periodically call `System.out.println()`. Verify core 0's UART output is not corrupted and core 1 does not hang.

### Optional Enhancement: Debug Counter

For diagnosing accidental UART access from non-core-0, add a debug counter:

```scala
if (bmbUart.isEmpty) {
  val nullUartAccessCount = Reg(UInt(16 bits)) init(0)
  when(memCtrl.io.ioRd && ioDeviceId === 1) {
    nullUartAccessCount := nullUartAccessCount + 1
  }
  // Expose via debug interface if needed
}
```

This counter could be read by the debug controller to detect unintended UART accesses without affecting runtime behavior.
