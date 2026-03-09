# JDK 8 Upgrade Plan

This document describes the plan for upgrading JOP to support JDK 8 class files, language
features, and a two-stage boot architecture (bootstrap from flash, load application from
SD card).

## Current State

JOP targets JDK 6 exclusively:
- `javac -source 1.6 -target 1.6` for runtime and application code
- BCEL 5.2 for bytecode manipulation (PreLinker, JOPizer)
- Class file version 50 only (AppInfo.java rejects >50)
- No `invokedynamic` (opcode 0xBA mapped to `f_unused_ba()` → `JVMHelp.noim()`)
- No `MethodHandle`, `MethodType`, `InvokeDynamic` constant pool types
- No default interface methods
- Single-stage boot: one .jop file loaded from UART or SPI flash

## What JDK 8 Adds

### Language features
- **Lambda expressions**: `list.forEach(x -> print(x))` — compiled to `invokedynamic`
  calling `LambdaMetafactory.metafactory()` as bootstrap method
- **Default interface methods**: `interface List { default void sort(...) {...} }`
- **Method references**: `list.forEach(System.out::println)` — also `invokedynamic`
- **Streams API**: `list.stream().filter(...).map(...)` — requires lambdas + collections
- **java.time**: `LocalDate`, `Duration`, `Instant` — modern date/time API

### Bytecode changes
- **`invokedynamic`** (opcode 0xBA): dynamically-linked call site. First call invokes a
  bootstrap method that returns a `CallSite` containing a `MethodHandle`. Subsequent calls
  use the cached target.
- **New constant pool types**: `CONSTANT_MethodHandle` (15), `CONSTANT_MethodType` (16),
  `CONSTANT_InvokeDynamic` (18)
- **Class file version 52**: must be accepted by toolchain
- **`BootstrapMethods` attribute**: class file attribute listing bootstrap methods for
  `invokedynamic` call sites

### What does NOT change
- `invokevirtual`, `invokeinterface`, `invokestatic`, `invokespecial` — unchanged
- Stack frame layout — unchanged
- 32-bit stack slots — unchanged
- Object/array layout — unchanged

## Architecture: Two-Stage Boot

### Rationale

Separating bootstrap (runtime) from application enables:
1. Runtime in SPI flash — always available, survives SD card removal
2. Application on SD card — field-updatable without FPGA reprogramming
3. Larger applications — not constrained by flash size or serial download time
4. Multiple applications — select at boot (menu, DIP switches, or filename convention)

### Boot Flow

```
Power-on
  │
  ▼
Microcode (jvm.asm)
  │ Load bootstrap.jop from SPI flash
  │ (existing #ifdef FLASH path, already implemented)
  ▼
Startup.boot() [CPU 0]
  │ GC.init(mem_size, pointers)
  │ clazzinit() — runtime class initializers
  │ JVMHelp.init()
  │
  ├─ BootMode.Flash (current): invoke main() directly
  │
  └─ BootMode.SdBoot (new):
      │ Initialize SD card controller
      │ Mount FAT32 partition
      │ Find application .jop file
      │ Load into memory at appBase
      │ Run application <clinit> methods
      │ Invoke application main()
      ▼
    Application running
```

### Memory Layout

```
Address 0x00000000:
  ┌──────────────────────────────┐
  │ bootstrap.jop image          │  Loaded from SPI flash by microcode
  │  - word[0]: app_length       │
  │  - word[1]: special pointers │
  │  - static fields             │
  │  - method area (bytecode)    │
  │  - class info / vtables      │
  │  - string constants          │
  ├──────────────────────────────┤  ← bootstrapEnd (= word[0])
  │ application.jop image        │  Loaded from SD by Startup.java
  │  - word[0]: app_length       │
  │  - word[1]: special pointers │
  │  - static fields             │
  │  - method area (bytecode)    │
  │  - class info / vtables      │
  │  - string constants          │
  ├──────────────────────────────┤  ← appEnd
  │ GC heap                      │
  │  (handle area + free memory) │
  └──────────────────────────────┘  ← mem_size (IO_MEM_SIZE)
```

### .jop Format: Current Structure

The .jop file is a text file with 32-bit words (one per line, decimal or hex):

```
word[0]:  total_length          — sets heap start
word[1]:  pointerAddr           — address of special pointers array
words 2..N: static value fields
words N..M: static reference fields
words M+: method bytecode (each method: [len|startAddr] [cp|locals|args] ...)
special pointers (at pointerAddr):
  [0]: boot method struct      — Startup.boot()
  [1]: JVM method struct       — JVM.f_* handlers
  [2]: JVMHelp method struct   — JVMHelp methods
  [3]: main method struct      — application main()
  [4]: static ref base address
  [5]: static ref count
  [6+]: <clinit> method list
string table: String objects + backing char arrays
class info: vtables, interface tables, GC metadata
```

All addresses are **absolute** (base 0). The microcode loads word-by-word starting at
address 0 in external memory.

### Two-Stage Linking Strategy

**Option A: Single image, split for storage** (recommended first step)

JOPizer links runtime + application into one .jop image as today. A post-processing tool
splits it into two parts:
- `bootstrap.bin` — runtime portion (flash at offset, e.g., 0x800000)
- `application.bin` — app portion (SD card as `APP.JOP` file)

At boot, microcode loads `bootstrap.bin` at address 0. Bootstrap code reads `application.bin`
from SD and copies it to `bootstrapEnd` in memory. Since both were linked together, all
addresses are already correct.

Advantages: no relocation, no format changes, simplest path.
Disadvantage: must rebuild both when either changes.

**Option B: Relocatable application image** (future)

JOPizer produces a relocatable .jop with a relocation table:
- All internal pointers stored as offsets from image base
- Relocation table lists addresses that need patching
- Bootstrap loader adds `appBase` to each relocated address

Advantages: app can be updated independently of runtime.
Disadvantage: requires JOPizer and loader changes.

**Option C: Runtime class loading** (most ambitious, future)

Bootstrap includes a class file parser and runtime linker:
- Reads `.class` files from SD card (FAT32 directory)
- Parses constant pool, methods, fields, attributes
- Builds vtables and interface tables at runtime
- Resolves symbolic references to memory addresses

Advantages: true dynamic loading, standard Java deployment.
Disadvantage: ~3,000–5,000 lines of class file parser, significant memory overhead.

## Implementation Phases

### Phase A: Toolchain — Accept JDK 8 Class Files

**Goal**: JOPizer and PreLinker can process class file version 52 without crashing.

**Changes**:

1. **AppInfo.java version check** — accept version 52
   - Location: `java/tools/src/com/jopdesign/common/AppInfo.java` (search for version/major)
   - Change: accept `major_version <= 52` instead of `<= 50`

2. **BCEL upgrade** — handle new constant pool types
   - Current: `java/lib/bcel-5.2.jar` — cannot parse `CONSTANT_MethodHandle` (15),
     `CONSTANT_MethodType` (16), `CONSTANT_InvokeDynamic` (18)
   - Options:
     a. Upgrade to BCEL 6.x (API changes, moderate effort)
     b. Patch BCEL 5.2 to skip/ignore unknown constant pool types (quick hack)
     c. Use a shim that pre-processes class files to strip JDK 8 attributes
   - Recommendation: start with (b), upgrade to (a) when stable

3. **JOPizer constant pool handling** — skip or handle new entry types
   - `CONSTANT_InvokeDynamic` entries reference bootstrap methods
   - If desugaring removes all `invokedynamic`, these entries may still be present but unused
   - JOPizer must not crash on them

**Verification**: compile a simple JDK 8 class (no lambdas), run through PreLinker + JOPizer,
boot on JOP.

### Phase B: Desugaring — invokedynamic → Anonymous Classes

**Goal**: Java 8 source with lambdas compiles and runs on JOP.

**Approach**: Add a desugaring step to the build pipeline that converts `invokedynamic`
bytecodes to traditional `invokestatic` + anonymous inner classes. This is the same approach
Android used before native lambda support.

**Tools** (choose one):
- **RetroLambda**: converts Java 8 bytecode to Java 6/7. Mature, single JAR.
  `java -jar retrolambda.jar -Dretrolambda.inputDir=classes -Dretrolambda.outputDir=classes6`
- **D8/R8** (Android): Google's desugarer. More actively maintained but heavier dependency.
- **Custom**: write a BCEL-based pass in PreLinker that rewrites `invokedynamic` to
  `invokestatic` + generated inner class. Most control, most effort.

**Build pipeline change**:

```
javac -source 1.8 -target 1.8 *.java     [compile with JDK 8]
  ↓
retrolambda (or D8)                        [desugar invokedynamic → inner classes, NEW]
  ↓
PreLinker                                  [existing CHA + bytecode transforms]
  ↓
JOPizer                                   [existing .jop generation]
  → .jop file
```

**What gets desugared**:
- Lambda expressions → anonymous inner classes implementing the functional interface
- Method references → static method + anonymous class
- `invokedynamic` → `invokestatic` to a synthetic factory method

**What does NOT need desugaring**:
- Default interface methods (handled in Phase C)
- `try-with-resources` (already compiles to `try/finally`, no `invokedynamic`)
- Diamond inference (compile-time only, no bytecode change)

**Verification**: compile lambda-using test class, desugar, JOPize, run on BRAM sim.

### Phase C: Default Interface Methods

**Goal**: interfaces with default methods work correctly.

**Impact on JOP**: The `invokeinterface` microcode dispatches through the interface table
(object → method table → interface table → method struct). Default methods add concrete
implementations to interfaces that must be inherited by implementing classes.

**Where the fix goes**: PreLinker's CHA (Class Hierarchy Analysis), which builds vtables
and interface tables.

**Current PreLinker transforms** (`java/tools/src/com/jopdesign/build/PreLinker.java`):
- `ReplaceIinc`: rewrites wide iinc instructions
- `InsertSynchronized`: adds monitorenter/monitorexit for synchronized methods
- `InjectSwap`: injects SWAP bytecodes where needed

**New transform needed**: `ResolveDefaultMethods`
- During vtable construction, when a class implements an interface with default methods:
  - If the class (or any superclass) overrides the method → use the override (existing behavior)
  - If no override exists → copy the default method entry into the class's vtable
- The interface table entry must point to the correct method struct (default or override)

**Corner cases**:
- Diamond inheritance: class implements two interfaces with same default method → Java compiler
  forces the class to override (compile error), so this is resolved at compile time
- `super.method()` in default methods → needs interface resolution, may require special handling

**Verification**: write interface with default method, implement without override, call via
`invokeinterface`, verify correct dispatch.

### Phase D: Flash Boot (Bootstrap from SPI Flash)

**Goal**: JOP boots from SPI flash without UART connection.

**Current state**: Flash boot microcode exists (`#ifdef FLASH` in jvm.asm). It:
- Initializes SPI flash (reset sequence, clock divider)
- Sends READ_DATA command (0x03) with 3-byte address
- Reads .jop data byte-by-byte, assembles into 32-bit words
- Stores at address 0 in external memory
- Flash offset configurable: `FLASH_ADDR_B2` (0x80 = 8MB for Cyclone IV, 0x24 = 2.25MB for Artix-7)

**What's needed**:
1. **Flash programming tool**: write .jop binary to SPI flash at the correct offset
   - Altera: `quartus_pgm` can program EPCS/EPCQ via JTAG
   - Xilinx: `vivado` can program SPI flash via JTAG
   - Or: custom UART-based flash programmer (write from JOP itself)

2. **.jop binary format**: current .jop is text (one word per line). Flash needs raw binary.
   - Add `JopBinaryWriter` to JOPizer: output big-endian 32-bit words, no text formatting
   - Or: post-process text .jop to binary with a simple tool

3. **BootMode.Flash config integration**: already exists in JopConfig:
   ```scala
   case object Flash extends BootMode { val dirName = "flash" }
   ```
   Generates microcode with `#define FLASH`. Verilog top includes `BmbConfigFlash`.

4. **Hardware**: `BmbConfigFlash` already exists in SpinalHDL. Pin assignments for SPI flash
   already in QSF/XDC generators. `HAS_CONFIG_FLASH` flag in IoConfig.

**Boards with flash**:
| Board | Flash chip | Size | JOP offset | Status |
|-------|-----------|------|------------|--------|
| QMTECH EP4CGX150 | W25Q128 | 16 MB | 0x800000 (8 MB) | Microcode ready |
| Wukong XC7A100T | SST26VF032B | 4 MB | 0x240000 (2.25 MB) | Microcode ready |
| Alchitry Au V2 | (config flash) | varies | TBD | Needs investigation |
| CYC5000 | EPCQ-L | 4 MB | TBD | No BmbConfigFlash yet |

**Verification**: program .jop binary to QMTECH flash, boot without UART, verify "Hello World".

### Phase E: SD Card Boot (Two-Stage)

**Goal**: Bootstrap from flash loads application from SD card.

**Prerequisites**: Phase D (flash boot), FAT32 filesystem (already implemented).

**Implementation** — new `SdBootLoader.java` class:

```java
package com.jopdesign.sys;

import com.jopdesign.fat32.*;
import com.jopdesign.hw.SdNative;

public class SdBootLoader {

    /** Load application .jop from SD card into memory at appBase.
     *  Returns the application's special pointers address, or -1 on failure. */
    static int loadFromSd(int appBase) {
        // 1. Initialize SD card
        SdNativeBlockDevice sd = new SdNativeBlockDevice();
        if (!sd.init()) {
            JVMHelp.wr("SD init failed\n");
            return -1;
        }

        // 2. Mount FAT32 partition 0
        Fat32FileSystem fs = new Fat32FileSystem(sd);
        if (!fs.mount(0)) {
            JVMHelp.wr("FAT32 mount failed\n");
            return -1;
        }

        // 3. Find application file
        //    Convention: APP.JOP in root directory
        //    Future: read filename from config file or DIP switches
        DirEntry entry = fs.findFile(fs.rootCluster(), "APP.JOP");
        if (entry == null) {
            JVMHelp.wr("APP.JOP not found\n");
            return -1;
        }

        // 4. Read .jop binary into memory
        Fat32InputStream in = fs.openFile(entry);
        int addr = appBase;
        int word = 0;
        int byteCount = 0;
        int b;
        while ((b = in.read()) >= 0) {
            word = (word << 8) | (b & 0xFF);
            byteCount++;
            if (byteCount == 4) {
                Native.wrMem(word, addr);
                addr++;
                word = 0;
                byteCount = 0;
            }
        }

        JVMHelp.wr("Loaded ");
        JVMHelp.wrInt(addr - appBase);
        JVMHelp.wr(" words from SD\n");

        return appBase + 1;  // special pointers at word[1]
    }
}
```

**Startup.java integration**:

```java
static void boot() {
    // ... existing CPU 0 init (GC, clazzinit, JVMHelp) ...

    if (/* SD boot mode */) {
        int appBase = Native.rdMem(0);  // bootstrap end = heap start
        int spAddr = SdBootLoader.loadFromSd(appBase);
        if (spAddr >= 0) {
            // Update heap start past application image
            int appLen = Native.rdMem(appBase);
            GC.reinit(appBase + appLen);
            // Run application <clinit>
            runAppClinit(spAddr);
            // Invoke application main
            int mainMp = Native.rdMem(spAddr + 3);
            Native.invoke(0, mainMp);
        }
    } else {
        // Direct main() invocation (current behavior)
        val = Native.rdMem(1);
        val = Native.rdMem(val + 3);
        Native.invoke(0, val);
    }
}
```

**Key design decisions**:
- **Binary .jop format**: SD card stores raw 32-bit words (big-endian), not text
- **Single application file**: `APP.JOP` in FAT32 root directory
- **No relocation** (Option A): runtime + app linked together, split for storage
- **GC reinit**: after loading app, adjust heap start to exclude app image from GC
- **App <clinit>**: must run app's class initializers after loading

**Verification**: build split image, program bootstrap to flash, copy app to SD, boot and
verify application runs.

### Phase F: BootMode.SdBoot Config Integration

**Goal**: config-driven SD boot as a first-class boot mode.

**Changes**:

```scala
// In JopConfig.scala
object BootMode {
  case object Serial extends BootMode     { val dirName = "serial" }
  case object Flash extends BootMode      { val dirName = "flash" }
  case object SdBoot extends BootMode     { val dirName = "sdboot" }  // NEW
  case object Simulation extends BootMode { val dirName = "simulation" }
}
```

**What SdBoot configures**:
- Microcode: `#define FLASH` (bootstrap still loads from flash)
- Hardware: enables both `BmbConfigFlash` and `BmbSdNative`/`BmbSdSpi` in IoConfig
- Const.java: `HAS_SD_CARD = true`
- Makefile: builds split image (bootstrap.bin + APP.JOP)

**New Makefile targets**:

```makefile
# Build two-stage image
sdboot: bootstrap.bin app.jop
    @echo "Flash bootstrap.bin to SPI flash, copy APP.JOP to SD card"

bootstrap.bin: tools runtime
    # Build full .jop, extract runtime portion
    $(MAKE) jop
    java -cp tools/dist/jopizer.jar com.jopdesign.build.JopSplitter \
        --input $(JOP_OUT) --bootstrap bootstrap.bin --app APP.JOP

app.jop: tools runtime apps
    # Already produced by JopSplitter above
```

### Phase G: Native invokedynamic (Future, Optional)

**Goal**: handle `invokedynamic` in microcode/JVM without desugaring.

This is the most ambitious phase and may never be needed if desugaring (Phase B) works well.
Included for completeness.

**What's required**:
1. **Microcode handler for opcode 0xBA**: trap to Java handler (`JVM.f_invokedynamic()`)
2. **`java.lang.invoke` package**: `MethodHandle`, `CallSite`, `MethodType`,
   `LambdaMetafactory`, `ConstantCallSite`
3. **Bootstrap method resolution**: read `BootstrapMethods` attribute from class file at
   runtime, invoke bootstrap method to create `CallSite`
4. **Call site caching**: after first resolution, cache the `MethodHandle` target for
   subsequent calls (inline cache or similar)
5. **MethodHandle dispatch**: `MethodHandle.invokeExact()` needs to dispatch to the target
   method efficiently — this is the hardest part on JOP

**Why it's hard on JOP**:
- `MethodHandle` is essentially a function pointer with type checking
- JOP's method dispatch goes through vtables with fixed offsets
- A `MethodHandle` can point to any method, requiring indirect dispatch
- Performance would be much worse than static dispatch (likely trap to Java each time)

**Recommendation**: defer this indefinitely. Desugaring (Phase B) handles the 99% case
(lambdas, method references) with zero runtime overhead.

## Hardware Requirements per Board

| Board | Flash boot | SD boot | Status |
|-------|-----------|---------|--------|
| **QMTECH EP4CGX150** | W25Q128 @ 8MB offset | SD Native (daughter board) | Both HW ready |
| **Wukong XC7A100T** | SST26VF032B @ 2.25MB | SD Native (on-board) | Both HW ready |
| **Alchitry Au V2** | Config flash | No SD slot | Flash only (unless add-on board) |
| **CYC5000** | EPCQ-L | No SD slot | Needs BmbConfigFlash, no SD |

## Implementation Order

| Phase | Description | Prerequisites | Effort |
|-------|-------------|---------------|--------|
| **A** | Accept JDK 8 class files | None | Small (1–2 days) |
| **B** | Desugaring (invokedynamic → inner classes) | Phase A | Small (2–3 days) |
| **C** | Default interface methods in PreLinker | Phase A | Medium (3–5 days) |
| **D** | Flash boot (single-stage) | None | Small (2–3 days) |
| **E** | SD card boot (two-stage) | Phase D, FAT32 | Medium (5–7 days) |
| **F** | Config-driven SdBoot mode | Phase E | Small (1–2 days) |
| **G** | Native invokedynamic | All above | Large (weeks) — defer |

**Recommended start**: Phases A + B + D in parallel (independent).
Phase C follows A. Phase E follows D.

## Relationship to JDK Porting Plan

The JDK 8 upgrade is **orthogonal** to the JDK class library porting
(`docs/architecture/jdk-porting-plan.md`):

- **JDK porting** (Phases 0–8): adds java.util, java.math, etc. to the runtime.
  Uses `-source 1.6 -target 1.6`. No toolchain changes needed.
- **JDK 8 upgrade** (Phases A–G): upgrades the toolchain to accept JDK 8 source code.
  Enables lambdas, default methods, streams.

Both can proceed in parallel. The JDK porting adds the classes; the JDK 8 upgrade lets
you write application code using JDK 8 syntax to call them.

**Combined vision**: JDK 8 application code using lambdas and collections, booting from
SD card on SMP JOP:

```java
import java.util.ArrayList;
import java.util.Collections;

public class App {
    public static void main(String[] args) {
        ArrayList<Integer> list = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) list.add(i * i);
        Collections.sort(list, (a, b) -> b - a);  // JDK 8 lambda
        list.forEach(x -> System.out.println(x));  // JDK 8 method
    }
}
```

## Testing Strategy

1. **Phase A**: compile JDK 8 class (no lambdas), JOPize, BRAM sim
2. **Phase B**: compile with lambdas, desugar, JOPize, BRAM sim, verify anonymous classes
3. **Phase C**: interface with default method, invokeinterface dispatch, BRAM sim
4. **Phase D**: program QMTECH flash, boot without UART, verify serial output
5. **Phase E**: split image, flash bootstrap, SD application, boot and verify
6. **Integration**: JDK 8 app with collections + lambdas, two-stage boot, SMP
