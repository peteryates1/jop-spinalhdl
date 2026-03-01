# Remote Debugging over Serial Port with Eclipse

## Goal

Enable source-level debugging of JOP applications from Eclipse using the
standard JDWP (Java Debug Wire Protocol) over UART. Set breakpoints,
inspect variables, step through code — the same experience as debugging a
standard JVM application.

## Architecture

```
Eclipse (JDI)                  Host PC                     JOP Target
+-----------+    JDWP/TCP    +------------+    UART     +-------------+
| Debug UI  | <-----------> | Debug Proxy | <--------> | Debug Stub  |
| (JDT)     |   localhost   | (Python/    |  1 Mbaud   | (Java +     |
|           |    :8000      |  Java)      |            |  microcode) |
+-----------+               +------------+             +-------------+
                             |            |
                             | Symbol DB  |
                             | (.class    |
                             |  metadata) |
                             +------------+
```

Three components:

1. **Debug Stub** — runs on JOP, handles breakpoints, reads memory/stack
2. **Debug Proxy** — runs on host PC, translates JDWP to a simple serial
   protocol, holds all symbolic debug metadata
3. **Eclipse** — connects to the proxy via standard JDWP socket transport

## Why a Proxy

JOPizer resolves all symbolic references (class names, method names,
field names) to absolute memory addresses at link time. The resulting
`.jop` image has no metadata — no constant pool strings, no line number
tables, no local variable tables.

JDWP requires this metadata to map between source locations and runtime
state. Rather than bloating the on-target image, the proxy holds the
metadata host-side (extracted from `.class` files) and correlates it with
JOPizer's address assignments.

## Component Details

### 1. Debug Stub (on JOP)

A small Java module linked into the application `.jop`, plus microcode
extensions.

#### Microcode Extensions

**BREAKPOINT trap** — When the processor fetches bytecode 0xCA
(`BREAKPOINT`), it:

1. Saves the current PC, stack pointer, and variable pointer
2. Flushes the method cache line for the current method
3. Transfers control to the debug stub's trap handler
4. The trap handler communicates the stop event to the host over UART

**Method cache invalidation** — When setting a breakpoint, the original
bytecode at the target address is replaced with 0xCA. If that method is
in the cache, the cache line must be invalidated so the processor fetches
the modified bytecode from main memory.

**Stack read** — A new `Native` call to read the hardware stack cache
contents (current frame's locals and operand stack) and walk the saved
frame chain in main memory.

**Single step mode** — A microcode flag that triggers a trap after every
bytecode execution. Set by the debug stub when Eclipse requests a step
command, cleared on resume.

#### Java Debug Stub

A `DebugStub` class linked into every debuggable `.jop`:

- **UART command handler**: Parses commands from the proxy, sends responses
- **Breakpoint table**: Maps addresses to original bytecodes (restored on
  breakpoint removal)
- **Trap handler**: Called by microcode on breakpoint/step hit. Sends
  stop event with PC, thread ID, frame info
- **Memory read/write**: Exposes `Native.rd()`/`Native.wr()` to the proxy
- **Thread enumeration**: Reports JOP's hardware thread states

#### Serial Protocol (Stub <-> Proxy)

Binary protocol, minimal overhead:

| Command | Direction | Payload | Description |
|---------|-----------|---------|-------------|
| `0x01` SET_BP | Proxy->Stub | addr(4) | Set breakpoint, returns saved bytecode |
| `0x02` CLR_BP | Proxy->Stub | addr(4) | Clear breakpoint, restores bytecode |
| `0x03` RESUME | Proxy->Stub | thread(1) | Resume execution |
| `0x04` STEP | Proxy->Stub | thread(1), depth(1) | Single step (into/over/out) |
| `0x05` READ_MEM | Proxy->Stub | addr(4), len(2) | Read memory region |
| `0x06` WRITE_MEM | Proxy->Stub | addr(4), len(2), data... | Write memory |
| `0x07` GET_STACK | Proxy->Stub | thread(1), depth(1) | Read stack frame |
| `0x08` SUSPEND | Proxy->Stub | thread(1) | Suspend thread |
| `0x09` THREADS | Proxy->Stub | — | List thread states |
| `0x81` HIT_BP | Stub->Proxy | thread(1), addr(4) | Breakpoint hit event |
| `0x82` HIT_STEP | Stub->Proxy | thread(1), addr(4) | Step complete event |
| `0x83` EXCEPTION | Stub->Proxy | thread(1), addr(4), type(4) | Exception thrown |

Each message: `[cmd(1)] [len(2)] [payload...] [crc8(1)]`

### 2. Debug Proxy (on Host PC)

A host application (Python or Java) that bridges JDWP and the serial
debug protocol.

#### Symbol Database

Built from two sources:

1. **`.class` files** — line number tables, local variable tables,
   method/field/class names, constant pools. Parsed via a bytecode library
   (ASM, BCEL, or javap).

2. **JOPizer address map** — a new output from JOPizer that records the
   mapping from each class/method/field to its absolute address in the
   `.jop` image. Format: a JSON or text file emitted alongside the `.jop`.

The proxy joins these two data sources to answer JDWP queries: "what
source line is PC 0x1A3F?" or "what is the address of local variable `i`
in method `Foo.bar()` at line 42?"

#### JDWP Translation

The proxy implements the JDWP transport (`dt_socket`) and handles the
subset of JDWP command sets needed for basic debugging:

| JDWP Command Set | Proxy Handling |
|------------------|----------------|
| VirtualMachine | Version info, class list from symbol DB, suspend/resume all |
| ReferenceType | Class metadata from symbol DB |
| ClassType | Superclass chain from symbol DB |
| Method | Method metadata, line table, variable table from symbol DB |
| ObjectReference | Read fields via READ_MEM |
| ThreadReference | Suspend/resume/status via serial commands |
| StackFrame | GET_STACK via serial, map addresses to methods/lines via symbol DB |
| EventRequest | Set/clear breakpoints via SET_BP/CLR_BP, step via STEP |
| Event | Forward HIT_BP/HIT_STEP as JDWP events |

Command sets not relevant to JOP (ClassLoader, ArrayReference, etc.)
return `NOT_IMPLEMENTED` errors. Eclipse handles this gracefully.

### 3. JOPizer Modifications

JOPizer must emit an **address map file** alongside the `.jop`:

```json
{
  "classes": {
    "test.HelloWorld": {
      "classInfoAddr": 7850,
      "methods": {
        "main([Ljava/lang/String;)V": {
          "codeAddr": 5200,
          "codeLength": 48,
          "maxLocals": 2
        }
      },
      "fields": {
        "counter": { "addr": 15, "type": "I" }
      }
    }
  }
}
```

This requires adding a write pass after `SetMethodAddress` and
`SetStaticAddresses` that dumps the address assignments. The information
is already available internally — it just needs to be serialized.

## Implementation Phases

### Phase 1: Microcode Breakpoint Support

- Add `BREAKPOINT` (0xCA) bytecode handling to `jvm.asm`
- On trap: save PC and stack state, call a fixed address (debug stub entry)
- Add method cache invalidation for a given method address
- Add `Native.readStackFrame()` for hardware stack cache access
- Test: manually insert 0xCA into a `.jop`, verify trap fires

### Phase 2: Debug Stub

- Implement `DebugStub.java` with the serial protocol handler
- Breakpoint set/clear (bytecode patching + cache invalidation)
- Memory read/write
- Stack frame reading
- Thread enumeration
- Test: send serial commands from a Python script, verify responses

### Phase 3: JOPizer Address Map

- Add address map output pass to JOPizer
- Emit JSON file with class/method/field to address mappings
- Include line number and local variable tables from the `.class` files
- Test: verify map against manual `.jop` inspection

### Phase 4: Debug Proxy

- Implement JDWP socket listener
- Parse JDWP handshake and command packets
- Load symbol database (`.class` metadata + JOPizer address map)
- Translate JDWP commands to serial debug protocol
- Forward debug events (breakpoint hit, step complete) as JDWP events
- Test: connect Eclipse, set breakpoint, verify it hits

### Phase 5: Eclipse Integration

- Test with Eclipse JDT debug perspective
- Create a launch configuration guide (Remote Java Application, socket
  attach, localhost:8000)
- Verify: breakpoints, step into/over/out, variable inspection, stack
  frames, thread list

## Limitations

- **No hot code replacement** — cannot modify classes while debugging.
  Must rebuild and reflash to change code.
- **No conditional breakpoints** — Eclipse evaluates these by running
  expressions on the target, which requires class loading. Could be
  approximated by having the stub check a simple condition (address
  equals value) without full expression evaluation.
- **No expression evaluation** — the Expressions view won't work since
  it requires compiling and loading code on the target. Variable
  inspection (Locals, Watch with simple names) will work.
- **Single step granularity** — JOP executes some bytecodes in microcode
  (e.g., `Native` calls). Single step operates at the bytecode level,
  which maps well to source lines via the line number table.
- **Method cache interaction** — setting a breakpoint invalidates the
  cache line. This causes a one-time performance hit when the method is
  re-fetched. Acceptable for debugging.
- **Serial bandwidth** — at 1 Mbaud, reading a 1 KB stack frame takes
  ~10ms. Acceptable for interactive debugging. Variable inspection of
  large arrays may feel slow.
