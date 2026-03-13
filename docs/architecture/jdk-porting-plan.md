# JDK Class Library Porting Plan

This document describes the plan for expanding JOP's Java runtime with classes from OpenJDK,
adapted for JOP's constraints. It covers source inventory, porting phases, threading for SMP,
and integration with the config-driven build system.

## Current Runtime

The runtime at `java/runtime/src/` contains 61 source files:

- **java.lang** (25 files): Object, String, StringBuilder, Integer, Long, Character, exceptions
- **java.io** (4 files): InputStream, OutputStream, PrintStream, IOException
- **JOP system** (15 files): Startup, JVM, GC, Memory, Native, Scheduler, RtThreadImpl, SoftFloat*
- **I/O drivers** (16 files): SerialPort, EthMac, SdNative, VgaDma, etc.
- **No java.util** — no collections, no HashMap/ArrayList

## Source Repositories

Four sources are available, each with a distinct role:

### 1. Jopmin — Primary Source (Phases 1–6)

**Location**: `/srv/git/jopmin/java/target/src/java/`

The curated JOP JDK fork. 127 classes already adapted for JOP constraints: no native methods,
no reflection, no sun.* dependencies. Sourced from GNU Classpath (GPL-compatible).

| Package | Files | Key classes |
|---------|-------|-------------|
| java.lang | 65 | All wrappers (Boolean–Void), Math, Thread stub, Enum, Class stub, full exceptions |
| java.io | 25 | Reader, Writer, DataInput/Output, BufferedReader, Serializable |
| java.util | 37 | ArrayList, HashMap, LinkedList, HashSet, Vector, Stack, Arrays, Collections, Random |
| java.lang.annotation | 7 | Annotation, Documented, Retention, Target, etc. |

**JOP adaptations already done**:
- `Float`/`Double`: use `Native.toInt()`/`Native.toLong()` for bit conversion (no native methods)
- `ArrayList`: throws `Error` on `ensureCapacity()` (SCJ no-grow constraint)
- `HashMap`: throws `Error` on `resize()`, MAXIMUM_CAPACITY reduced to `1 << 20`
- `Math`: Taylor series for trig, Newton's method for sqrt (20 iterations)
- `Thread`: minimal stub — `sleep()` delegates to `RtThread.sleepMs()`, `start()`/`run()` are no-ops
- `InputStreamReader`: ASCII-only (no charset support)

**Known bugs in jopmin**:
- `Math.sin()`/`Math.cos()`/`Math.atan()`: return `f` (loop variable) instead of `sum` (accumulated result)
- `Math.pow()`: stub that returns input unchanged

### 2. JOP Original — Bootstrap Subset

**Location**: `/srv/git/jop.original/java/target/src/jdk16/`

86 classes. Incomplete — missing Object, String, Integer, Long, NullPointerException, IOException.
This is a stripped-down bootstrap subset, not a usable runtime. Only useful for one class:

- `OutputStreamWriter.java` — present here but missing from jopmin

### 3. OpenJDK 6 — Reference + BigMath Source

**Location**: `/srv/git/java/jdk6/jdk/src/share/classes/java/`

1,341 classes. Full JDK 1.6 reference. Sole source for:
- `java.math.BigInteger` (3,168 lines), `java.math.BigDecimal` (3,860 lines)
- `java.net.*` (68 classes) — if networking is needed beyond raw Ethernet
- `java.text.*` (43 classes) — number/date formatting

Collections (ArrayList, HashMap, etc.) are identical across all three sources — same Sun JDK code.

### 4. OpenJDK 8 — Extended Reference

**Location**: `/srv/git/java/jdk8/jdk/src/share/classes/java/`

Same structure as JDK 6 plus `java.time` package (JSR-310 date/time API: LocalDate, Duration, etc.).
Useful if newer APIs are needed. JOP toolchain currently targets `-source 1.6 -target 1.6`; JDK 8
source is available for reference but would require the JDK 8 toolchain upgrade (see
`docs/architecture/configuration-driven-design.md`, AppInfo.java:1572 version check).

## JOP Constraints

These constraints apply to every ported class:

1. **No native methods** — replace with `com.jopdesign.sys.Native` calls or pure Java
2. **No reflection** — `Class.forName()`, `Array.newInstance()`, `Method.invoke()` unavailable.
   Blocks `Arrays.copyOf()` (uses `Array.newInstance`) — replace with `new T[] + System.arraycopy()`
3. **No Serializable I/O** — `ObjectInputStream`/`ObjectOutputStream` don't exist. Remove `writeObject()`/`readObject()`.
   `Serializable` can be an empty marker interface.
4. **4KB method cache** — methods >4KB cause cache thrashing. Split large methods
   (e.g., `Arrays.sort()` 4,214-line version)
5. **No `sun.*` packages** — strip internal API dependencies
6. **No SecurityManager** — remove security checks
7. **JDK 6 source level** — must compile with `-source 1.6 -target 1.6`
8. **No invokedynamic** — no lambda expressions (JDK 8 feature)
9. **32-bit stack slots** — long/double operations are software (~50 cycles each)
10. **Limited heap** — BRAM: ~200KB, SDRAM: ~8MB. Prefer small default collection sizes.
11. **Integer multiply ~18 cycles, divide ~36 cycles** — hash functions work but aren't free
12. **Float support conditional** — `Const.SUPPORT_FLOAT` controls whether float ops compile.
    HashMap's `loadFactor` (0.75f) requires float support.

## Porting Phases

### Phase 0: Build Infrastructure — DONE (2026-03-10)

**Goal**: Add `src/jdk/` to the build source path so ported classes are compiled.

**Changes**:
- Create `java/runtime/src/jdk/` directory tree
- Update `java/runtime/Makefile`: add `SRC_JDK := src/jdk` to `-sourcepath`
- Update each app Makefile's `find` command to include `$(TARGET_DIR)/src/jdk`

**Verify**: build HelloWorld.jop with expanded source paths, all existing tests pass.

**Status**: Complete. All 7 Makefiles updated (runtime + 6 apps). HelloWorld, JvmTests, Smallest all build.

### Phase 1: Foundation Interfaces — DONE (2026-03-10)

**Source**: jopmin (direct copy, no adaptation needed)

**Classes** (12 files, dependency order):

```
java/lang/Comparable.java        — pure interface
java/lang/Iterable.java          — depends on java.util.Iterator
java/lang/Appendable.java        — pure interface
java/lang/Cloneable.java         — empty marker
java/lang/Number.java            — abstract, implements Serializable
java/lang/Readable.java          — pure interface
java/lang/IllegalArgumentException.java
java/lang/IllegalStateException.java
java/lang/NumberFormatException.java
java/io/Serializable.java        — empty marker
java/io/Closeable.java           — pure interface
java/io/Flushable.java           — pure interface
```

**Note**: Some of these may overlap with or replace stubs already in `src/jvm/`. Resolve by
keeping the richer jopmin version and removing the stub.

**Status**: Complete. All 12 files copied from jopmin, no adaptation needed.

### Phase 2: Core Collections — DONE (2026-03-10)

**Source**: jopmin (re-enable growth for general-purpose use)

**Classes** (~20 files):

```
java/util/Iterator.java               — interface
java/util/ListIterator.java            — interface
java/util/Enumeration.java             — interface
java/util/RandomAccess.java            — marker interface
java/util/Comparator.java              — interface
java/util/Collection.java              — interface
java/util/List.java                    — interface
java/util/Set.java                     — interface
java/util/Map.java                     — interface + Map.Entry
java/util/NoSuchElementException.java
java/util/ConcurrentModificationException.java
java/util/AbstractCollection.java
java/util/AbstractList.java
java/util/AbstractSet.java
java/util/AbstractMap.java
java/util/ArrayList.java               — re-enable growth
java/util/HashMap.java                 — re-enable resize
java/util/HashSet.java                 — backed by HashMap
```

**JOP adaptations**:
- **ArrayList**: replace `throw new Error("no resize")` in `ensureCapacity()` with
  `elementData = new Object[newCapacity]; System.arraycopy(old, 0, elementData, 0, size)`.
  Replace `Arrays.copyOf()` calls with manual copy. Keep `toArray()` returning `Object[]` only.
- **HashMap**: re-enable `resize()` (replace `throw` with actual resize logic).
  Keep `MAXIMUM_CAPACITY = 1 << 20` (reasonable for JOP's memory).
- **HashSet**: comment out `clone()` (depends on reflection)

**Test**: CollectionTest.java in JvmTests — ArrayList add/get/remove/iterator, HashMap put/get/keySet/entrySet, HashSet, for-each loop.

**Status**: Complete. All 18 files ported. Additional adaptations beyond plan:
- ArrayList: re-enabled `addAll()` methods, added `toArray()` (without `Arrays.copyOf`)
- HashMap: removed power-of-two constructor restriction (rounds up instead of throwing)
- HashMap/HashSet/AbstractMap: removed `clone()` (JOP Object has no clone support)
- HashSet: replaced `LinkedHashMap` reference in package-private constructor with `HashMap`
- Collection interface: uncommented `toArray()` declaration
- AbstractCollection: uncommented `toArray()` with simplified implementation (no `finishToArray`)
- Existing runtime enhanced: Integer (parseInt, valueOf, compareTo, toHexString, extends Number),
  StringBuilder (append Object/boolean/long), String (valueOf Object/boolean/char, StringBuffer ctor),
  RuntimeException (chained exception ctors), ArrayIndexOutOfBoundsException (String/int ctors),
  Character (digit, forDigit)

### Phase 3: Extended Lang — DONE (2026-03-10)

**Source**: jopmin (fix Math bugs, merge with existing Integer/Long)

**Classes** (7 files):

```
java/lang/Boolean.java
java/lang/Byte.java
java/lang/Short.java
java/lang/Float.java               — uses Native.toInt() for floatToIntBits
java/lang/Double.java              — uses Native.toLong() for doubleToLongBits
java/lang/Math.java                — fix trig bugs, implement pow()
java/lang/StringBuffer.java        — synchronized StringBuilder
```

**JOP adaptations**:
- **Integer/Long**: current runtime has minimal versions (toString only). Jopmin adds
  `parseInt()`, `valueOf()`, `toHexString()`, `compareTo()`. Merge — keep JOP-specific
  fixes from current, add jopmin's extra methods.
- **Math**: fix `sin()`/`cos()`/`atan()` to `return sum` instead of `return f`.
  Implement `pow()` properly (iterative for integer exponents).
- **Float/Double**: verify `floatToIntBits()`/`intBitsToFloat()` round-trip with HW FPU.

**Status**: Complete. All 7 files ported. Additional adaptations beyond plan:
- Math: fixed all 5 trig return bugs (sin float/double, cos float/double, atan float),
  also fixed `1.0f` literals to `1.0` in double variants. Implemented `pow()` with integer
  fast path (binary exponentiation) + general case (exp/ln Taylor series).
- Float: replaced `VMFloat.toString()` with simple int+frac implementation
- Double: stubbed `parseDouble()` (was `VMFloatingDecimal` dependency)
- Byte: added `extends Number implements Comparable`, added intValue/longValue/floatValue/doubleValue
- Short: same Number/Comparable additions
- WrapperTest.java added to JvmTests (11 tests): parseInt, valueOf, compareTo, toHexString,
  Boolean, Byte, Short, Number polymorphism, Math.pow, Float wrapper (floatToIntBits/intBitsToFloat
  roundtrip, isNaN, isInfinite, equals, compare), Double wrapper (doubleToLongBits/longBitsToDouble
  roundtrip, isNaN, isInfinite, equals, compare) — all pass in BRAM simulation
- MathTest.java added to JvmTests (6 tests): abs (int/long/float/double), min/max, sqrt,
  sin (float/double), cos (float/double), atan — validates bug-fixed trig functions
- StringBufferTest.java added to JvmTests (3 tests): append, delete, reverse — excluded from
  BRAM sim (too cycle-intensive), available for SDRAM/FPGA targets

### Phase 4: Extended I/O — DONE (2026-03-10)

**Source**: jopmin (direct copy, tune buffer sizes)

**Classes** (15 files):

```
java/io/Reader.java                — abstract
java/io/Writer.java                — abstract
java/io/BufferedReader.java
java/io/InputStreamReader.java     — rewritten, ASCII-only
java/io/OutputStreamWriter.java    — new, ASCII-only (jop.original too dependent on nio)
java/io/DataInput.java             — interface
java/io/DataOutput.java            — interface
java/io/DataInputStream.java
java/io/DataOutputStream.java
java/io/ByteArrayInputStream.java
java/io/ByteArrayOutputStream.java
java/io/FilterInputStream.java
java/io/FilterOutputStream.java
java/io/EOFException.java          — new stub (required by DataInputStream)
java/io/UTFDataFormatException.java — new stub (required by DataInputStream)
```

**JOP adaptations**:
- Reduce `Reader.skip()` buffer from 8192 to 256 chars
- Reduce `Writer.writeBufferSize` from 1024 to 128
- `InputStreamReader`: rewritten from scratch (jopmin version had recursive read() bug).
  ASCII/Latin-1 only — each byte maps to Unicode 0x00-0xFF.
- `OutputStreamWriter`: written from scratch (jop.original depends on StreamEncoder/Charset).
  ASCII/Latin-1 only — chars > 0xFF become '?'.
- All `System.arraycopy` replaced with manual loops (4 files: BufferedReader, ByteArrayInputStream,
  ByteArrayOutputStream, DataInputStream) — JOP arraycopy known buggy
- `DataInputStream.readLine()`: removed PushbackInputStream dependency (deprecated method)
- `ByteArrayOutputStream.toString()`: replaced `String(byte[],int,int)` with char[] conversion
- `ByteArrayOutputStream.toString(String)`: removed (charset dependency)
- IoTest.java added to JvmTests (6 tests): ByteArrayStreams, DataStreams (int/short/byte/boolean),
  DataStreamsExtended (long/char/unsignedByte), DataStreamsUTF (writeUTF/readUTF),
  ReaderWriter (string write/read), WriterCharArray (char array write/read) — all pass
- BufferedReader test exists but disabled (readLine() too cycle-intensive for BRAM sim)

### Phase 5: Extended Collections — COMPLETE

**Source**: jopmin
**Status**: All 15 files ported, compiles, BRAM sim passes (existing tests unaffected)

**Classes** (15 files):

```
java/util/LinkedList.java          — clone() removed
java/util/Vector.java              — 6 System.arraycopy → manual loops
java/util/Stack.java               — extends Vector, direct copy
java/util/LinkedHashMap.java       — direct copy
java/util/Hashtable.java           — clone() removed, Collections.synchronized* → direct
java/util/Dictionary.java          — abstract, direct copy
java/util/SortedMap.java           — interface
java/util/SortedSet.java           — interface
java/util/Queue.java               — interface
java/util/Deque.java               — interface
java/util/AbstractSequentialList.java
java/util/AbstractQueue.java
java/util/EmptyStackException.java
java/util/Arrays.java              — 18 System.arraycopy → manual loops
java/util/Collections.java         — trimmed to core algorithms (941 lines)
```

**JOP adaptations**:
- **System.arraycopy**: broken on JOP (stack overflow). All calls replaced with manual for loops.
- **clone()**: not available on JOP Object. Removed from LinkedList, Hashtable.
- **Collections**: wrapper classes (Unmodifiable*, Synchronized*, Checked*) removed — they
  fail compilation because Collection.toArray() is declared. Kept: sort, binarySearch, reverse,
  swap, fill, copy, min, max, rotate, replaceAll, indexOfSubList, lastIndexOfSubList, eq().
  shuffle() commented out (depends on java.util.Random, Phase 6).
- **Hashtable**: Collections.emptyEnumeration/emptyIterator → inline anonymous classes.
  Collections.synchronizedSet/synchronizedCollection wrappers removed (Hashtable is already
  synchronized, wrapping is redundant).
- **Arrays**: full sort suite (mergesort for objects, dual-pivot quicksort for primitives),
  copyOf/copyOfRange for all primitive types, fill, equals, asList, toString, binarySearch.

**Known issue**: `Arrays.sort(int[])` corrupts array on JOP — likely method overload resolution
bug with 8 `swap()` overloads. Tests use inline insertion sort as workaround.

**Verification**:
- ExtCollMinimal: 4 sub-tests (Vector, Stack, LinkedList, Hashtable basics)
- ExtCollectionTest: 13 sub-tests (Vector basic/grow/remove, Stack basic, LinkedList basic/
  addRemove, Hashtable basic/enum, int sort, object sort, list ordering, reverse, binarySearch)
- Both in DoAll — pass in BRAM sim (56 tests, 55 ok) and on FPGA hardware

### Phase 6: Utility Classes — COMPLETE

**Source**: jopmin (stubs for reflection-dependent features)

**Classes** (~10 files):

```
java/util/Random.java              — long seed arithmetic (slow but functional)
java/lang/Enum.java                — stub for enum support
java/lang/Class.java               — minimal stub (no reflection)
java/lang/Runtime.java             — minimal stub
java/lang/annotation/Annotation.java
java/lang/annotation/Documented.java
java/lang/annotation/ElementType.java
java/lang/annotation/Inherited.java
java/lang/annotation/Retention.java
java/lang/annotation/RetentionPolicy.java
java/lang/annotation/Target.java
```

**JOP adaptations**:
- **Random**: functional as-is but slow (~50 cycles per `long` op). Acceptable for occasional use.
- **Enum.valueOf()**: stub with `throw new UnsupportedOperationException()` (needs reflection).

**Status**: Complete. All files ported. UtilityTest added to JvmTests.

### Phase 7: java.math (BigInteger / BigDecimal) — COMPLETE

**Source**: OpenJDK 6 only (not in jopmin or jop.original)

**Classes** (6 files):

```
java/math/BigInteger.java          — 3,168 lines
java/math/BigDecimal.java          — 3,860 lines
java/math/MathContext.java
java/math/RoundingMode.java
java/math/BitSieve.java            — internal helper
java/math/MutableBigInteger.java   — internal helper
```

**JOP adaptations**:
- Replace `Arrays.copyOf()` with manual copy
- `BigInteger` uses `int[]` internally — compatible with JOP
- Heavy `long` arithmetic for cross-digit multiply — functional but slow
- Skip crypto-oriented methods (modPow with large keys) or accept slow performance
- No native `montMul` — all modular exponentiation in pure Java
- `RoundingMode`: lazy enum constant initialization via `ensureInstances()` (JOP enum limitation)
- `BigInteger`/`BigDecimal`/`MathContext`: lazy static constant initialization via `ensureConstants()`/`ensureBdConstants()`

**Status**: Complete. BigMathTest added to JvmTests (4 tests). Verified on FPGA hardware — all pass.
Also verified as standalone (`BigMathStandalone.java`) on FPGA.

### Phase 8: java.text (Formatting) — COMPLETE

**Source**: Simplified reimplementation (OpenJDK 6 for reference)

**Strategy**: locale-independent formatting only ("C" locale). Skip ICU/Locale complexity.

```
java/text/Format.java
java/text/NumberFormat.java        — simplified
java/text/DecimalFormat.java       — simplified
java/text/DecimalFormatSymbols.java
java/text/DigitList.java           — internal helper
java/text/FieldPosition.java
java/text/ParsePosition.java
java/text/ParseException.java
```

**JOP adaptations**:
- DecimalFormat: simplified from OpenJDK ~2000 lines to ~570 lines
- Locale-independent: hardcoded US symbols (`.` decimal, `,` grouping, `-` minus)
- No currency formatting, no percent/permille, no scientific notation
- Uses StringBuffer internally — required String(StringBuffer) fix (see bugs-and-issues.md #5)

**Bugs fixed during FPGA testing**:
- Digit truncation: `digitList.set()` 3-arg version truncated to maxIntegerDigits
- Grouping leak: `NumberFormat.groupingUsed` defaults to `true`, not reset by `applyPattern()`
- maxIntegerDigits: set to pattern digit count instead of JDK default 309

**Status**: Complete. TextFormatTest added to JvmTests (10 tests). Verified on FPGA hardware —
64/64 DoAll tests pass. Also verified as standalone (`TextFormatStandalone.java`, 14 tests) on FPGA.

## Threading for SMP

### Current Model

JOP uses cooperative real-time threads (`RtThread`/`RtThreadImpl`/`Scheduler`):

- Threads are statically created before `startMission()` is called
- Each thread has a fixed period and priority
- Timer interrupt triggers the `Scheduler.run()` which selects the next ready thread
- In SMP, each core has its own `Scheduler` instance with its own thread queue
- Core 0 runs full init; cores 1–N wait for `CMPStart.started` flag
- Threads are assigned to cores at mission start, never migrated

The jopmin `Thread.java` is a minimal stub:
```java
public class Thread implements Runnable {
    public static void sleep(long l) throws InterruptedException {
        joprt.RtThread.sleepMs((int) l);
    }
    public void start() { }  // no-op
    public void run() { }    // no-op
}
```

### Vision: java.lang.Thread for SMP

A real `java.lang.Thread` implementation would enable standard Java concurrency on SMP JOP.
The SMP infrastructure is already there:

- **CmpSync**: global lock for `monitorenter`/`monitorexit` (round-robin fair arbitration)
- **IHLU**: optional per-object hardware lock unit (32-slot fully-associative CAM)
- **GC halt**: `IO_GC_HALT` freezes non-GC cores during collection
- **Shared memory**: all cores share SDRAM (with cache snoop invalidation)

**Design sketch** (future phase, after collections):

1. **Thread pool per core**: extend `Scheduler` to accept dynamically created threads
2. **Thread.start()**: allocate stack region, register with a core's scheduler
3. **Thread.join()**: busy-wait or event-based wait on completion flag
4. **Thread.sleep()**: delegate to existing `RtThread.sleepMs()` mechanism
5. **Thread assignment**: round-robin or explicit core affinity
6. **Stack allocation**: each thread needs a stack region in SDRAM
   (per-core stack regions already supported by `StackCacheConfig`)
7. **Synchronization**: `synchronized` already works via CmpSync/IHLU
8. **GC integration**: threads must be GC-safe (roots scannable, halted during compact)

**Key challenges**:
- Dynamic stack allocation (current model is static)
- Thread lifecycle management (current RtThread has no termination)
- `Thread.interrupt()` mechanism
- `wait()`/`notify()`/`notifyAll()` for monitor-based coordination
- Stack cache rotation across dynamically created threads

**Dependency**: Requires collections (Phase 2) for thread management data structures.

## Module Integration

Each phase maps to a `RuntimeModule` for the config-driven build system:

| RuntimeModule | Phases | Package paths |
|---------------|--------|---------------|
| Core | — | `src/jop/`, `src/jvm/` (always included) |
| Collections | 1, 2, 5 | `src/jdk/java/util/` |
| ExtendedLang | 3 | `src/jdk/java/lang/` |
| ExtendedIO | 4 | `src/jdk/java/io/` |
| BigMath | 7 | `src/jdk/java/math/` |
| TextFormat | 8 | `src/jdk/java/text/` |
| SoftFloat32 | — | `src/jop/.../SoftFloat32.java` (conditional) |
| SoftFloat64 | — | `src/jop/.../SoftFloat64.java` (conditional) |

When `JopConfig` includes a module, the manifest generator adds its source paths to
`runtime-sources.txt` and the Makefile compiles them.

## Source File Layout

All ported classes go under `java/runtime/src/jdk/`:

```
java/runtime/src/
  jop/                    # JOP system (existing)
  jvm/                    # Minimal JDK stubs (existing)
  jdk/                    # Ported JDK classes (new)
    java/lang/            # Phase 1+3: interfaces, wrappers, Math
    java/io/              # Phase 4: Reader, Writer, Data I/O
    java/util/            # Phase 2+5: collections
    java/math/            # Phase 7: BigInteger, BigDecimal
    java/text/            # Phase 8: formatting
    java/lang/annotation/ # Phase 6: annotation interfaces
```

Classes in `src/jdk/java/lang/` will supplement (not replace) classes in `src/jvm/java/lang/`.
The javac `-sourcepath` resolves duplicates by directory order — ensure `src/jdk` comes after
`src/jvm` so existing core classes take precedence. Where a jopmin class is strictly better
than the existing stub, move the stub to `src/jdk` and delete the old one.

## JDK 8 Toolchain Upgrade (Separate Plan)

See `docs/architecture/jdk8-upgrade-plan.md` for the full JDK 8 upgrade plan, covering:
- Toolchain changes (AppInfo version check, BCEL upgrade, desugaring)
- Default interface methods in PreLinker
- Two-stage boot (bootstrap from SPI flash, application from SD card)
- Native `invokedynamic` support (future)

The JDK class library porting (Phases 0–8 above) uses `-source 1.6 -target 1.6` and is
independent of the JDK 8 upgrade. Both can proceed in parallel.

## Verification

For each phase:

1. **Compile**: `cd java && make clean && make all` — no errors
2. **JVM test suite**: `JopJvmTestsBramSim` — 64 tests in DoAll, all pass
3. **FPGA**: 64/64 DoAll tests pass on QMTECH DB_FPGA at 80 MHz (including BigMathTest + TextFormatTest)
4. **New tests**: phase-specific tests in `java/apps/JvmTests/`
   - Phase 2: **CollectionTest** (12 tests) — ArrayList add/get/remove/grow/iterator/addAll, HashMap basic/overwrite/grow/remove/iterate, HashSet, for-each loop
   - Phase 3: **WrapperTest** (11 tests) — Integer parseInt/valueOf/compare/hex, Boolean, Byte, Short, Number polymorphism, Math.pow, Float wrapper (floatToIntBits/intBitsToFloat/isNaN/isInfinite/equals/compare), Double wrapper (doubleToLongBits/longBitsToDouble/isNaN/isInfinite/equals/compare)
   - Phase 3: **MathTest** (6 tests) — abs (int/long/float/double), min/max, sqrt, sin (float/double), cos (float/double), atan
   - Phase 3: **StringBufferTest** (3 tests) — append, delete, reverse. Excluded from BRAM sim (too cycle-intensive), runs on SDRAM/FPGA targets
   - Phase 4: **IoTest** (6 tests) — ByteArrayStreams, DataStreams (int/short/byte/boolean), DataStreamsExtended (long/char/unsignedByte), DataStreamsUTF (writeUTF/readUTF), ReaderWriter (OutputStreamWriter/InputStreamReader string), WriterCharArray (char array write/read)
   - Phase 5: **ExtCollMinimal** (4 tests) — Vector, Stack, LinkedList, Hashtable basics
   - Phase 5: **ExtCollectionTest** (13 tests) — Vector basic/grow/remove, Stack basic, LinkedList basic/addRemove, Hashtable basic/enum, int/object sort (inline), list ordering, reverse, binarySearch. All pass in BRAM sim and FPGA
   - Phase 6: **UtilityTest** — Random, Enum, Class, Runtime basics
   - Phase 7: **BigMathTest** (4 tests) — BigInteger create/arithmetic, BigDecimal create/arithmetic. Requires `ensureInstances()`/`ensureConstants()` init calls.
   - Phase 8: **TextFormatTest** (10 tests) — DecimalFormat integer/grouping/negative/pattern formatting, parse integer/negative/position, DecimalFormatSymbols, NumberFormat factory, FieldPosition. Requires BigMath + RoundingMode init calls.
   - **DeepRecursion** (3 tests) — excluded from DoAll, stack cache configs only (run via JopStackCacheSim)
5. **FPGA standalone tests**: `TextFormatStandalone.java` (14 tests: 4 BigMath + 10 TextFormat) — all pass on FPGA
6. **Heap pressure**: monitor GC round count in Small app with collections enabled
7. **DSP multiply**: all tests verified in both standard and `useDspMul=true` BRAM sims
