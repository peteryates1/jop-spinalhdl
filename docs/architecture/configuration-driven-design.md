# Configuration-Driven JOP System

## Problem

JOP has a combinatorial explosion of microcode variants, boilerplate-heavy Verilog generation, and fragile manual configuration wiring.

**Current state:**
- 12 Makefile targets in `asm/Makefile` (3 boot modes × 4 math combos, manually maintained)
- 11 generated JumpTableData Scala objects (one per variant, created via `sed` rename)
- 11 factory methods in `JumpTableInitData` (one per variant)
- 20 Verilog generation entry points across 5 files (70%+ duplicated boilerplate)
- 31 simulation harnesses with similar duplication
- Manual wiring: `jumpTable = JumpTableInitData.serialHwMath, useDspMul = true, useHwDiv = true` — must be kept in sync or the wrong microcode executes

**Growth problem:** Adding long ALU + double FPU would create 3 boot × 8 math × 4 float × 4 double = 384 combinations with the current approach.

**Root cause:** Configuration is scattered — boot mode, jump table selection, HW peripheral flags, pipeline params, and ROM/RAM paths are all specified independently at each call site.

## Design Principle

**Configuration drives everything.** `JopSystemConfig` is the single source of truth. Every downstream artifact is derived from it — no manual synchronization between layers.

```
JopSystemConfig
  |
  +--→ Microcode assembly
  |      gcc -D flags derived from per-core Implementation
  |      Boot mode → SERIAL/FLASH/SIMULATION preprocessor define
  |      Output: superset ROM + RAM per boot mode
  |
  +--→ Java runtime generation
  |      Const.java: I/O addresses (FPU=0xF0, DIV=0xE0) from IoConfig
  |      Const.java: SUPPORT_FLOAT/SUPPORT_DOUBLE flags from core config
  |      JVM.java: f_fadd() → SoftFloat32 vs HW I/O stub (per Implementation)
  |      HW device drivers: only included when carrier board has the device
  |      Output: runtime .class files tailored to this system
  |
  +--→ SpinalHDL elaboration
  |      Per-core: jump table patching, peripheral instantiation, pipeline params
  |      System: arbiter type, memory controller, I/O wiring, debug
  |      Board: top-level ports, PLL config
  |      Output: Verilog for this specific system
  |
  +--→ FPGA build
  |      .qsf/.xdc from SystemAssembly pin maps
  |      Synthesis tool (Quartus/Vivado) from FpgaFamily
  |      Output: bitstream
  |
  +--→ Application build (JOPizer)
         Memory layout from JopMemoryConfig
         Available APIs from runtime
         Output: .jop file
```

From the config, the system derives:
- Which jump table entries route to Java / microcode / HW handler
- Which HW peripherals get instantiated (BmbFpu, BmbDiv, future long ALU, double FPU)
- Which pipeline parameters change (Mul useDsp)
- Which ROM/RAM files to load (based on boot mode)
- Which Java runtime classes are needed (SoftFloat32, SoftFloat64, HW device drivers)
- Which I/O addresses are active (generated into Const.java)
- Which carrier board peripherals have drivers available

### Three-way per-bytecode choice

Every configurable bytecode uses the same uniform model — three implementation options:

| Option | Jump Table Entry | Meaning |
|--------|-----------------|---------|
| **Java** | sys_noim | Java runtime handles it (SoftFloat, etc.) |
| **Microcode** | microcode handler addr | Pure microcode implementation, no HW peripheral |
| **Hardware** | HW microcode handler addr | Microcode that uses an HW I/O peripheral |

Example: `fadd: Hardware, fdiv: Java` → FPU is instantiated (because fadd needs it), jump table routes fadd→HW handler but fdiv→sys_noim. The fdiv bytecode traps to Java's SoftFloat32.

Not all three options exist for every bytecode today. The framework supports all three; validation ensures the selected option has a corresponding handler in ROM.

### Configurable Bytecodes (~48 total)

**Integer multiply** (1) — `imul`
- **Microcode** → bit-serial Mul unit (`stmul`/`ldmul`, 18 cycles, ~244 LCs)
- **Hardware** → DSP-inferred Mul unit (`stmul`/`ldmul`, 4 cycles, ~4 DSP18x18)
- **Java** → sys_noim → JVM.f_imul()
- Note: the Mul unit is a pipeline component with dedicated `stmul`/`ldmul` instructions rather than a generic I/O peripheral, but the config model is the same as everything else.

**Long arithmetic** (10) — `ladd` `lsub` `lneg` `lshl` `lshr` `lushr` `land` `lor` `lxor` `lcmp`
- Today: **Microcode** (existing handlers in base ROM)
- Future: **Hardware** (long ALU peripheral)
- Always available: **Java** (sys_noim)

**Long multiply** (1) — `lmul`
- Today: **Java** or **Hardware** (DSP microcode using Mul unit `doutH` for upper 32 bits)

**Integer/long divide** (4) — `idiv` `irem` `ldiv` `lrem`
- Today: **Java** or **Hardware** (BmbDiv for idiv/irem; ldiv/lrem Java only, future HW)

**Float arithmetic** (8) — `fadd` `fsub` `fmul` `fdiv` `fneg` `frem` `fcmpl` `fcmpg`
- Today: **Java** or **Hardware** (BmbFpu for fadd/fsub/fmul/fdiv; rest Java only, future HW)

**Double arithmetic** (8) — `dadd` `dsub` `dmul` `ddiv` `dneg` `drem` `dcmpl` `dcmpg`
- Today: **Java** only
- Future: **Hardware** (double FPU peripheral)

**Type conversions** (12) — `i2f` `i2d` `f2i` `f2l` `f2d` `d2i` `d2l` `d2f` `l2f` `l2d` `i2b` `i2s`
- Today: **Java** (most), **Microcode** (i2l, l2i, i2c already exist)
- Future: **Hardware** (FPU/double FPU can handle some conversions)

**Constants** (3) — `fconst_1` `fconst_2` `dconst_1`
- Today: **Java**; trivially implementable as **Microcode**

**Derived peripheral instantiation:**
- `needsFpu` = any of fadd/fsub/fmul/fdiv/fneg/frem/fcmpl/fcmpg is Hardware → instantiate BmbFpu
- `needsHwDiv` = any of idiv/irem is Hardware → instantiate BmbDiv
- `needsDspMul` = imul is Hardware OR lmul is Hardware → `Mul(useDsp=true)`
- `needsLongAlu` = any of ladd/.../lcmp is Hardware → instantiate BmbLongAlu (future)
- `needsDoubleFpu` = any of dadd/.../dcmpg is Hardware → instantiate BmbDoubleFpu (future)

## Key Insight: Superset ROM + Jump Table Patching

Optional HW handlers (FPU, DSP lmul, HW div) are **appended at the end** of the microcode ROM without shifting any base bytecode addresses. Comparing `JumpTableData` (base) vs `FpuJumpTableData` (FPU), 252 of 256 entries are identical. Only the configurable entries differ.

**Solution:** Build ONE superset ROM per boot mode (all features enabled: `-DFPU_ATTACHED -DDSP_MUL -DHW_DIV`). The superset ROM contains ALL microcode and HW handlers. At SpinalHDL elaboration, construct the jump table per-bytecode:
- **Java** → patch entry to `sys_noim`
- **Microcode** → use the microcode handler address from the superset ROM
- **Hardware** → use the HW handler address from the superset ROM

Result: **12 Makefile targets → 3.** Future features add `#ifdef` blocks to jvm.asm and `-D` flags — no new Makefile targets needed. The superset ROM grows but never splits.

Note: `imul` (0x68) has both bit-serial and DSP microcode handlers in the superset ROM (via `#ifdef DSP_MUL`). Both use the pipeline Mul unit (`stmul`/`ldmul`); the DSP handler is shorter (4 cycles vs 18). The jump table selects between them based on `imul: Microcode` (bit-serial) vs `imul: Hardware` (DSP), just like any other configurable bytecode.

### ROM Size Budget

Current base ROM: ~700-900 instructions (includes long microcode handlers). FPU handlers: ~50. DSP lmul: ~60. HW div: ~30. **Total superset: ~1040 of 2048 slots (51%).** Future long ALU HW handlers (~200) + double FPU (~100) + expanded float conversions (~50) would reach ~1390 (68%). Plenty of headroom.

---

## Phase 1: Jopa Assembler Cleanup

**File:** `java/tools/src/com/jopdesign/tools/Jopa.java`

1. **Remove 2 unused outputs:** `rom.vhd`, `jtbl.vhd` — legacy VHDL formats. **Keep** `rom.mif`, `ram.mif` — useful for reference and debug
2. **Add `-n <ObjectName>` flag:** Controls the generated Scala object name. Eliminates the fragile `sed` rename hack.
3. **Generate `extends JumpTableSource`:** Each generated object implements a common trait (see Phase 3)
4. **Keep:** `mem_rom.dat`, `mem_ram.dat`, `rom.mif`, `ram.mif`, `<ObjectName>.scala`

## Phase 2: Superset Microcode Build

**File:** `asm/Makefile`

Replace 12 variant targets with 3 superset builds:

```makefile
# Build superset ROMs — one per boot mode, ALL feature handlers included
simulation: ../java/tools/dist/jopa.jar
    mkdir -p generated/simulation
    gcc -E -C -P -DSIMULATION -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n SimulationJumpTable -s generated -d generated/simulation jvm.asm

serial: ../java/tools/dist/jopa.jar
    mkdir -p generated/serial
    gcc -E -C -P -DSERIAL -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n SerialJumpTable -s generated -d generated/serial jvm.asm

flash: ../java/tools/dist/jopa.jar
    mkdir -p generated/flash
    gcc -E -C -P -DFLASH -DFPU_ATTACHED -DDSP_MUL -DHW_DIV src/jvm.asm > generated/jvm.asm
    sed -i '1,35d' generated/jvm.asm
    java -jar jopa.jar -n FlashJumpTable -s generated -d generated/flash jvm.asm

all: simulation serial
```

**Flash variants** (flash-au with different flash addr/clk params): The flash params (`FLASH_ADDR_B2`, `FLASH_CLK_DIV`, `SKIP_FLASH_RESET`) only affect the boot loop, not the feature handlers. Single `flash` target with default params; flash-au uses same ROM with different boot params if needed, or becomes a second flash target.

**Backward compat:** Old target names (`serial-fpu`, `serial-hwmath`, etc.) become aliases: `serial-fpu: serial`.

**Deleted:** All variant subdirs (`asm/generated/fpu/`, `serial-fpu/`, `dsp/`, `serial-dsp/`, `div/`, `serial-div/`, `hwmath/`, `serial-hwmath/`).

## Phase 3: Jump Table Patching in Scala

**File:** `spinalhdl/src/main/scala/jop/pipeline/JumpTableSource.scala` (new)

```scala
package jop

/** Trait implemented by all Jopa-generated jump table objects */
trait JumpTableSource {
  def entries: Seq[BigInt]
  def sysNoimAddr: Int
  def sysIntAddr: Int
  def sysExcAddr: Int
}
```

**File:** `spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` (modified)

```scala
case class JumpTableInitData(
  entries:     Seq[BigInt],
  sysNoimAddr: Int,
  sysIntAddr:  Int,
  sysExcAddr:  Int
) {
  /** Disable HW for specific bytecodes (patch to sys_noim) */
  def disable(bytecodes: Int*): JumpTableInitData =
    copy(entries = entries.zipWithIndex.map { case (addr, i) =>
      if (bytecodes.contains(i)) BigInt(sysNoimAddr) else addr
    })
}

object JumpTableInitData {
  // One superset per boot mode — all HW handlers present
  def simulation: JumpTableInitData = from(jop.SimulationJumpTable)
  def serial:     JumpTableInitData = from(jop.SerialJumpTable)
  def flash:      JumpTableInitData = from(jop.FlashJumpTable)

  private def from(src: JumpTableSource): JumpTableInitData =
    JumpTableInitData(src.entries, src.sysNoimAddr, src.sysIntAddr, src.sysExcAddr)
}
```

**Deleted:** All 11 old factory methods (`serialFpu`, `simulationDsp`, `serialHwMath`, etc.) and their corresponding generated Scala objects.

## Phase 4: Per-Instruction Core Configuration

**File:** `spinalhdl/src/main/scala/jop/system/JopCore.scala`

### Implementation enum

```scala
/** Per-bytecode implementation selection — uniform for all configurable bytecodes */
sealed trait Implementation
object Implementation {
  case object Java extends Implementation       // sys_noim → Java runtime fallback
  case object Microcode extends Implementation  // Pure microcode handler (no HW peripheral)
  case object Hardware extends Implementation   // Microcode → HW I/O peripheral
}
```

Every configurable bytecode uses the same `Implementation` enum. The physical realization differs (imul uses a pipeline `Mul` unit with `stmul`/`ldmul`; fadd uses a BMB I/O peripheral `BmbFpu`), but the config model is uniform.

### JopCoreConfig — per-instruction fields

The config organizes bytecodes by category. Each field specifies Java/Microcode/Hardware. Not all options are available for every bytecode today — validation checks at elaboration.

```scala
case class JopCoreConfig(
  // --- Architectural params (unchanged) ---
  dataWidth: Int = 32, pcWidth: Int = 11, instrWidth: Int = 10,
  jpcWidth: Int = 11, ramWidth: Int = 8, blockBits: Int = 4,
  memConfig: JopMemoryConfig = JopMemoryConfig(),
  cpuId: Int = 0, cpuCnt: Int = 1,
  ioConfig: IoConfig = IoConfig(),
  clkFreqHz: Long = 100000000L,
  useIhlu: Boolean = false,
  useStackCache: Boolean = false,
  spillBaseAddrOverride: Option[Int] = None,

  // --- Per-instruction implementation selection ---
  // Integer multiply (Microcode=bit-serial 18cyc, Hardware=DSP 4cyc)
  imul:  Implementation = Implementation.Microcode,

  // Long arithmetic (today: Microcode or Java; future: Hardware via long ALU)
  ladd:  Implementation = Implementation.Microcode,
  lsub:  Implementation = Implementation.Microcode,
  lneg:  Implementation = Implementation.Microcode,
  lshl:  Implementation = Implementation.Microcode,
  lshr:  Implementation = Implementation.Microcode,
  lushr: Implementation = Implementation.Microcode,
  land:  Implementation = Implementation.Microcode,
  lor:   Implementation = Implementation.Microcode,
  lxor:  Implementation = Implementation.Microcode,
  lcmp:  Implementation = Implementation.Microcode,

  // Long multiply (today: Java or Hardware/DSP)
  lmul:  Implementation = Implementation.Java,

  // Integer/long divide (today: Java or Hardware/BmbDiv for idiv/irem)
  idiv:  Implementation = Implementation.Java,
  irem:  Implementation = Implementation.Java,
  ldiv:  Implementation = Implementation.Java,
  lrem:  Implementation = Implementation.Java,

  // Float (today: Java or Hardware/BmbFpu for fadd/fsub/fmul/fdiv)
  fadd:  Implementation = Implementation.Java,
  fsub:  Implementation = Implementation.Java,
  fmul:  Implementation = Implementation.Java,
  fdiv:  Implementation = Implementation.Java,
  fneg:  Implementation = Implementation.Java,
  frem:  Implementation = Implementation.Java,
  fcmpl: Implementation = Implementation.Java,
  fcmpg: Implementation = Implementation.Java,

  // Double (today: Java only; future: Hardware via double FPU)
  dadd:  Implementation = Implementation.Java,
  dsub:  Implementation = Implementation.Java,
  dmul:  Implementation = Implementation.Java,
  ddiv:  Implementation = Implementation.Java,
  dneg:  Implementation = Implementation.Java,
  drem:  Implementation = Implementation.Java,
  dcmpl: Implementation = Implementation.Java,
  dcmpg: Implementation = Implementation.Java,

  // Type conversions (today: mostly Java; i2l/l2i/i2c have Microcode)
  i2f:   Implementation = Implementation.Java,
  i2d:   Implementation = Implementation.Java,
  f2i:   Implementation = Implementation.Java,
  f2l:   Implementation = Implementation.Java,
  f2d:   Implementation = Implementation.Java,
  d2i:   Implementation = Implementation.Java,
  d2l:   Implementation = Implementation.Java,
  d2f:   Implementation = Implementation.Java,
  l2f:   Implementation = Implementation.Java,
  l2d:   Implementation = Implementation.Java,
  i2b:   Implementation = Implementation.Java,
  i2s:   Implementation = Implementation.Java,
) {
  // --- Derived: which HW peripherals to instantiate ---
  private val allFloat  = Seq(fadd, fsub, fmul, fdiv, fneg, frem, fcmpl, fcmpg)
  private val allDouble = Seq(dadd, dsub, dmul, ddiv, dneg, drem, dcmpl, dcmpg)
  private val allLong   = Seq(ladd, lsub, lneg, lshl, lshr, lushr, land, lor, lxor, lcmp)

  def needsFpu: Boolean       = allFloat.exists(_ == Implementation.Hardware)
  def needsDoubleFpu: Boolean = allDouble.exists(_ == Implementation.Hardware)
  def needsLongAlu: Boolean   = allLong.exists(_ == Implementation.Hardware)
  def needsDspMul: Boolean    = imul == Implementation.Hardware || lmul == Implementation.Hardware
  def needsHwDiv: Boolean     = Seq(idiv, irem, ldiv, lrem).exists(_ == Implementation.Hardware)

  // --- Derived: jump table resolution ---
  // Maps bytecode opcode → configured Implementation
  // resolveJumpTable() selects the right address per bytecode from the superset ROM
  def resolveJumpTable(base: JumpTableInitData): JumpTableInitData = {
    // Bytecodes to patch to sys_noim (Java fallback)
    val javaPatches: Seq[Int] = configurableBytecodes.collect {
      case (bc, Implementation.Java) => bc
    }
    // Bytecodes to patch to microcode handler (when superset ROM has HW handler
    // but config wants Microcode — uses alternate address from ROM metadata)
    // For now: Java patches only. Microcode/HW distinction needs ROM metadata.
    base.disable(javaPatches: _*)
  }
}
```

**Note on Microcode vs Hardware resolution:** The superset ROM contains both microcode-only handlers (e.g., existing `ladd` at 0x436) and HW peripheral handlers (e.g., `fadd` HW at 0x5BF). For bytecodes that have both options (future), the Jopa-generated jump table data will include addresses for each. The `resolveJumpTable` method selects the appropriate address based on the configured Implementation. Details TBD when the first bytecode gains all three options.

### Convenience presets

```scala
object JopCoreConfig {
  /** All defaults: imul=microcode (bit-serial), long=microcode, float/double/div=java */
  def software = JopCoreConfig()

  /** DSP multiply (imul + lmul → DSP Mul unit) */
  def dspMul = JopCoreConfig(imul = Implementation.Hardware, lmul = Implementation.Hardware)

  /** HW integer divide */
  def hwDiv = JopCoreConfig(idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** Full HW math (DSP mul + HW div) */
  def hwMath = JopCoreConfig(
    imul = Implementation.Hardware, lmul = Implementation.Hardware,
    idiv = Implementation.Hardware, irem = Implementation.Hardware)

  /** HW single-precision float (fadd/fsub/fmul/fdiv) */
  def hwFloat = JopCoreConfig(
    fadd = Implementation.Hardware, fsub = Implementation.Hardware,
    fmul = Implementation.Hardware, fdiv = Implementation.Hardware)

  /** Everything that has HW today */
  def hwAll = JopCoreConfig(
    imul = Implementation.Hardware, lmul = Implementation.Hardware,
    idiv = Implementation.Hardware, irem = Implementation.Hardware,
    fadd = Implementation.Hardware, fsub = Implementation.Hardware,
    fmul = Implementation.Hardware, fdiv = Implementation.Hardware)
}
```

### Deleted

- `fpuMode: FpuMode.FpuMode` → replaced by `needsFpu` (derived from per-bytecode config)
- `useDspMul: Boolean` → replaced by `needsDspMul` (derived: `imul == Hardware || lmul == Hardware`)
- `useHwDiv: Boolean` → replaced by `needsHwDiv` (derived from per-bytecode config)
- `jumpTable: JumpTableInitData` → replaced by `resolveJumpTable(base)`
- `withFpuJumpTable` / `withMathJumpTable` / `isSerialJumpTable` → deleted
- `FpuMode` enum → deleted
- `MulImpl` enum → deleted (imul uses Implementation like everything else)

### Update consumers

`JopCluster`, `JopSdramTop`, `JopCyc5000Top`, `JopDdr3Top`, all sim harnesses — replace:
- `fpuMode = FpuMode.Hardware` → `coreConfig.needsFpu`
- `useDspMul = true` → `coreConfig.needsDspMul`
- `useHwDiv = true` → `coreConfig.needsHwDiv`
- `jumpTable = JumpTableInitData.serialHwMath` → `coreConfig.resolveJumpTable(base)`

The JopSdramTop constructor simplifies — no more `jumpTable`, `fpuMode`, `useDspMul`, `useHwDiv` parameters. These are all derived from `coreConfig`.

## Phase 5: Hardware Description — System / Board / FPGA / Memory / Devices

The physical hardware forms an assembly of boards connected together. An FPGA module plugs into a carrier/daughter board. The carrier board provides peripherals (UART, ethernet, SD card, LEDs) via its own connectors. Pin assignments flow through the chain: FPGA pin → FPGA board header → carrier board connector → peripheral.

```
System (physical assembly — the complete hardware on the desk)
  |
  +-- FPGA Board: qmtech-ep4cgx150 (module)
  |     +-- FPGA: EP4CGX150DF27I7 (Cyclone IV GX)
  |     +-- On-board memory: W9825G6JH6 (SDR SDRAM)
  |     +-- On-board devices: 2 LEDs, 2 switches, 50 MHz oscillator
  |     +-- Headers: J2 (60-pin), J3 (60-pin) → expose FPGA pins
  |
  +-- Carrier Board: qmtech-fpga-db-v4 (daughter board)
  |     +-- Connectors: J2, J3 → mate with FPGA board headers
  |     +-- Devices: CP2102N (UART), RTL8211EG (Ethernet), VGA, SD card,
  |     |            7-segment display, 5 LEDs, 5 switches, 2× PMOD
  |     +-- Pin mapping: carrier connector pin → FPGA board header pin → FPGA pin
  |
  +-- JOP System (logical — what runs on the FPGA)
  |     +-- Boot: Serial | Flash | Simulation
  |     +-- Arbiter: RoundRobin | TDMA | ...
  |     +-- Debug config
  |
  +-- JOP Core(s) (per-core configuration)
        +-- core(0) → imul: Dsp, fadd: Hardware, methodCache: 4K ...
        +-- core(1) → imul: BitSerial, fadd: Java, ...
```

### Physical layer — boards and assemblies

```scala
sealed trait FpgaFamily
object FpgaFamily {
  case object CycloneIV extends FpgaFamily    // Altera/Intel
  case object CycloneV extends FpgaFamily
  case object Artix7 extends FpgaFamily       // Xilinx/AMD
}

sealed trait MemoryType
object MemoryType {
  case class SdrSdram(
    device: SdramDevice,    // W9825G6JH6 (QMTECH), W9864G6JT (CYC5000)
    dataWidth: Int = 16,
    bankWidth: Int = 2,
    columnWidth: Int = 9,
    rowWidth: Int = 13,
  ) extends MemoryType

  case class Ddr3(
    sizeBytes: Long,          // 256 * 1024 * 1024 for Au V2
    cacheKB: Int = 32,        // L2 write-back cache size
  ) extends MemoryType

  case object BramOnly extends MemoryType
}

/** FPGA module board — the board with the FPGA chip + on-board memory */
case class FpgaBoard(
  name: String,                     // "qmtech-ep4cgx150"
  fpga: FpgaFamily,
  device: String,                   // "EP4CGX150DF27I7"
  memories: Seq[MemoryType],        // on-board memory (SDRAM, DDR3, etc.)
  clkFreqHz: Long,                  // on-board oscillator frequency
  onBoardDevices: Seq[String] = Seq.empty,  // "2× LED", "2× switch"
)

/** Carrier/daughter board — provides peripherals and connectors */
case class CarrierBoard(
  name: String,                     // "qmtech-fpga-db-v4"
  devices: Seq[String] = Seq.empty, // "CP2102N UART", "RTL8211EG Ethernet", etc.
  connectors: Seq[String] = Seq("J2", "J3"),
)

/** Complete physical system — FPGA board + optional carrier board(s) */
case class SystemAssembly(
  name: String,                     // "qmtech-ep4cgx150-db-v4"
  fpgaBoard: FpgaBoard,
  carrierBoards: Seq[CarrierBoard] = Seq.empty,
) {
  // Convenience accessors
  def fpga: FpgaFamily = fpgaBoard.fpga
  def device: String = fpgaBoard.device
  def memories: Seq[MemoryType] = fpgaBoard.memories
  def clkFreqHz: Long = fpgaBoard.clkFreqHz
}
```

### Board presets

```scala
// --- FPGA Boards (modules) ---
object FpgaBoard {
  def qmtechEp4cgx150 = FpgaBoard(
    name = "qmtech-ep4cgx150",
    fpga = FpgaFamily.CycloneIV,
    device = "EP4CGX150DF27I7",
    memories = Seq(MemoryType.SdrSdram(
      device = SdramDevices.W9825G6JH6,
      dataWidth = 16, bankWidth = 2, columnWidth = 9, rowWidth = 13)),
    clkFreqHz = 50000000L,     // 50 MHz oscillator
    onBoardDevices = Seq("2× LED", "2× switch"))

  def cyc5000 = FpgaBoard(
    name = "cyc5000",
    fpga = FpgaFamily.CycloneV,
    device = "5CEBA2F17A7",
    memories = Seq(MemoryType.SdrSdram(
      device = SdramDevices.W9864G6JT,
      dataWidth = 16, bankWidth = 2, columnWidth = 8, rowWidth = 12)),
    clkFreqHz = 12000000L,     // 12 MHz oscillator
    onBoardDevices = Seq("5× LED", "2× button", "FT2232H JTAG+UART"))

  def alchitryAuV2 = FpgaBoard(
    name = "alchitry-au-v2",
    fpga = FpgaFamily.Artix7,
    device = "XC7A35T",
    memories = Seq(MemoryType.Ddr3(sizeBytes = 256L * 1024 * 1024)),
    clkFreqHz = 100000000L,    // 100 MHz oscillator
    onBoardDevices = Seq("1× LED", "1× button", "FT2232H JTAG+UART"))
}

// --- Carrier Boards ---
object CarrierBoard {
  def qmtechDbV4 = CarrierBoard(
    name = "qmtech-fpga-db-v4",
    devices = Seq(
      "CP2102N UART",         // ser_rxd, ser_txd
      "RTL8211EG Ethernet",   // RGMII
      "VGA (5-6-5 RGB)",
      "Micro SD card",
      "3-digit 7-segment display",
      "5× LED", "5× switch",
      "2× PMOD (J10, J11)"),
    connectors = Seq("J2", "J3"))

  /** Alchitry Au V2 has no separate carrier — peripherals are on FPGA board */
  def none = CarrierBoard(name = "none")
}

// --- System Assemblies ---
object SystemAssembly {
  /** QMTECH EP4CGX150 core board + DB_FPGA daughter board (primary platform) */
  def qmtechWithDb = SystemAssembly(
    name = "qmtech-ep4cgx150-db-v4",
    fpgaBoard = FpgaBoard.qmtechEp4cgx150,
    carrierBoards = Seq(CarrierBoard.qmtechDbV4))

  /** CYC5000 standalone (all peripherals on FPGA board) */
  def cyc5000 = SystemAssembly(
    name = "cyc5000",
    fpgaBoard = FpgaBoard.cyc5000)

  /** Alchitry Au V2 standalone */
  def alchitryAuV2 = SystemAssembly(
    name = "alchitry-au-v2",
    fpgaBoard = FpgaBoard.alchitryAuV2)
}
```

### Pin assignments — board-level facts

Pin assignments are constants of the physical board assembly. Currently stored in `.qsf` files, already factored into reusable includes:
- `fpga/qmtech-ep4cgx150-core.qsf` — FPGA board: clock, LEDs, switches, SDRAM
- `fpga/qmtech-ep4cgx150-db.qsf` — Carrier board: UART, Ethernet, VGA, SD, 7-seg, PMODs

Each project `.qsf` copies the relevant pins. Future: pin maps could be generated from `SystemAssembly` + `CarrierBoard` data, or the existing `.qsf` includes could be referenced directly.

### JOP System layer — processor system organization

```scala
sealed trait BootMode { def dirName: String }
object BootMode {
  case object Serial extends BootMode     { val dirName = "serial" }
  case object Flash extends BootMode      { val dirName = "flash" }
  case object Simulation extends BootMode { val dirName = "simulation" }
}

sealed trait ArbiterType
object ArbiterType {
  case object RoundRobin extends ArbiterType     // current default
  case object Tdma extends ArbiterType           // time-division multiple access
  // future: Priority, WeightedRR, ...
}

case class JopSystemConfig(
  name: String,
  system: SystemAssembly,                            // physical hardware
  bootMode: BootMode,
  arbiterType: ArbiterType = ArbiterType.RoundRobin,
  clkFreqHz: Long,                   // system clock (after PLL)
  cpuCnt: Int = 1,
  coreConfig: JopCoreConfig = JopCoreConfig(),       // default for all cores
  perCoreConfigs: Option[Seq[JopCoreConfig]] = None,  // heterogeneous override
  ioConfig: IoConfig = IoConfig(),
  debugConfig: Option[DebugConfig] = None,
  perCoreUart: Boolean = false,
) {
  // --- Derived paths from boot mode ---
  def romPath: String = s"asm/generated/${bootMode.dirName}/mem_rom.dat"
  def ramPath: String = s"asm/generated/${bootMode.dirName}/mem_ram.dat"

  def baseJumpTable: JumpTableInitData = bootMode match {
    case BootMode.Serial     => JumpTableInitData.serial
    case BootMode.Flash      => JumpTableInitData.flash
    case BootMode.Simulation => JumpTableInitData.simulation
  }

  // --- Derived: per-core configs (heterogeneous or uniform) ---
  def coreConfigs: Seq[JopCoreConfig] = perCoreConfigs.getOrElse(
    Seq.fill(cpuCnt)(coreConfig)
  )

  // --- Derived from physical assembly ---
  def fpga: FpgaFamily = system.fpga
  def memoryType: MemoryType = system.memories.head

  // --- Validation ---
  require(cpuCnt >= 1)
  perCoreConfigs.foreach(pcc =>
    require(pcc.length == cpuCnt,
      s"perCoreConfigs length (${pcc.length}) must match cpuCnt ($cpuCnt)"))
}
```

### System presets

```scala
object JopSystemConfig {
  // QMTECH EP4CGX150 + DB_FPGA daughter board — primary platform
  def qmtechSerial = JopSystemConfig("qmtech-serial",
    system = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Serial,
    clkFreqHz = 80000000L)

  def qmtechSmp(n: Int) = qmtechSerial.copy(
    name = s"qmtech-smp$n", cpuCnt = n)

  def qmtechHwMath = qmtechSerial.copy(
    name = "qmtech-hwmath", coreConfig = JopCoreConfig.hwMath)

  // Heterogeneous: core 0 = fast math, core 1 = minimal
  def qmtechHetero = qmtechSerial.copy(
    name = "qmtech-hetero", cpuCnt = 2,
    perCoreConfigs = Some(Seq(JopCoreConfig.hwMath, JopCoreConfig.software)))

  // CYC5000
  def cyc5000Serial = JopSystemConfig("cyc5000-serial",
    system = SystemAssembly.cyc5000,
    bootMode = BootMode.Serial,
    clkFreqHz = 100000000L)

  // Alchitry Au V2
  def auSerial = JopSystemConfig("au-serial",
    system = SystemAssembly.alchitryAuV2,
    bootMode = BootMode.Serial,
    clkFreqHz = 83333333L)

  // Simulation (no physical board — assembly is just a placeholder)
  def simulation = JopSystemConfig("simulation",
    system = SystemAssembly.qmtechWithDb,
    bootMode = BootMode.Simulation,
    clkFreqHz = 100000000L)
}
```

### What the system assembly drives

The physical assembly determines:
- **Memory controller instantiation**: `SdrSdram` → `BmbSdramCtrl32` (or Altera BlackBox), `Ddr3` → `BmbCacheBridge` + MIG, `BramOnly` → `BmbOnChipRam`
- **Top-level I/O ports**: SDRAM pins vs DDR3 pins vs none
- **PLL configuration**: FPGA board oscillator freq → system clock
- **FPGA family**: Affects synthesis tool (Quartus vs Vivado), DSP block type, memory primitives
- **Available peripherals**: Carrier board determines which I/O devices exist (UART, Ethernet, SD, etc.)
- Future: pin assignment / constraint file generation from board data

Currently this mapping is implicit in separate top-level files (`JopSdramTop`, `JopDdr3Top`, `JopCyc5000Top`). With `SystemAssembly`, a single generic top-level could dispatch based on memory type. Migration path: keep existing top-level files but have them read from `JopSystemConfig` instead of manual params.

### Unified generation entry point

**File:** `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` (new)

Single `sbt runMain jop.system.JopGenerate <preset>` replaces 20 separate entry points. Old `object JopSdramTopVerilog extends App` etc. become thin wrappers for backward compat.

## Phase 6: Java Runtime Generation

The Java runtime currently has manual configuration scattered across several files:

**Current state:**
- `Const.java`: `SUPPORT_FLOAT = true`, `SUPPORT_DOUBLE = true` — manual boolean flags
- `Const.java`: `IO_FPU = IO_BASE+0x70`, `IO_DIV = IO_BASE+0x60` — hardcoded I/O addresses
- `JVM.java`: `f_fadd()` → `if (Const.SUPPORT_FLOAT) SoftFloat32.float_add(a,b)` — compile-time branching
- `com/jopdesign/hw/`: Device drivers (EthMac, SdNative, VgaDma, etc.) — always compiled, even if carrier board lacks the device

**Config-driven approach:**

The runtime is generated/configured from `JopSystemConfig`:

### 1. Const.java generation

```java
// Generated from JopSystemConfig — do not edit manually
public class Const {
  // From IoConfig
  public static final int IO_BASE  = -128;
  public static final int IO_FPU   = IO_BASE + 0x70;  // present: core config has needsFpu
  public static final int IO_DIV   = IO_BASE + 0x60;  // present: core config has needsHwDiv

  // From JopCoreConfig (union of all cores' capabilities)
  public static final boolean SUPPORT_FLOAT  = true;   // any core has fadd/fsub/... != Java
  public static final boolean SUPPORT_DOUBLE = true;    // any core has dadd/dsub/... != Java

  // From SystemAssembly
  public static final boolean HAS_ETHERNET = true;      // carrier board has RTL8211EG
  public static final boolean HAS_SD_CARD  = true;      // carrier board has SD slot
  public static final boolean HAS_VGA      = true;      // carrier board has VGA output
  // ...
}
```

### 2. JVM.java bytecode handlers

Today each `f_fadd()` etc. checks `Const.SUPPORT_FLOAT` at compile time. With config-driven generation, these become:

- **Implementation.Java** → `f_fadd()` calls `SoftFloat32.float_add()` (software fallback)
- **Implementation.Hardware** → `f_fadd()` is **never called** (jump table routes to HW microcode, JVM.java handler is dead code)
- **Implementation.Microcode** → `f_fadd()` is **never called** (jump table routes to microcode handler)

For heterogeneous configs (core 0 = HW float, core 1 = SW float), the runtime must include SoftFloat32 since core 1 needs it. The union of all cores' configs determines what software fallbacks are compiled in.

### 3. Device driver inclusion

Carrier board peripherals determine which HW driver classes are included:

| Carrier Board Device | Java Driver Class | Included When |
|---------------------|-------------------|---------------|
| CP2102N UART | `SerialPort.java` | Always (boot requires UART) |
| RTL8211EG Ethernet | `EthMac.java`, `Mdio.java` | `CarrierBoard.devices` contains Ethernet |
| SD card slot | `SdNative.java`, `SdSpi.java` | `CarrierBoard.devices` contains SD |
| VGA output | `VgaDma.java`, `VgaText.java` | `CarrierBoard.devices` contains VGA |

### 4. Build integration

```makefile
# Generate Const.java from system config, then build runtime
generate-runtime:
    sbt "runMain jop.system.GenerateRuntime qmtech-serial"
    cd java && make all
```

Or as an sbt task that generates `Const.java` before the Java toolchain runs.

### Heterogeneous core considerations

With per-core configs, the runtime is built for the **union** of all cores' capabilities:
- If *any* core uses `fadd: Java` → `SoftFloat32` must be in the runtime
- If *all* cores use `fadd: Hardware` → `SoftFloat32` can be excluded (saves .jop size)
- I/O address constants reflect the superset (all cores share the same address space)

This is a constraint: the `.jop` file is shared across all cores. Core-specific behavior comes from the jump table (patched per-core at elaboration time), not from the Java code.

## Phase 7: Simulation Harness Dedup (separate, lower priority)

Factor common sim patterns into a `SimRunner` utility. New sims use it; old sims migrate incrementally. Not part of the core refactoring.

---

## Execution Order

| # | Phase | Action | Files |
|---|-------|--------|-------|
| 1 | 1 | Jopa: remove unused outputs, add `-n` flag, `extends JumpTableSource` | `Jopa.java` |
| 2 | 2 | Makefile: 3 superset targets | `asm/Makefile` |
| 3 | 2 | Build + verify superset ROMs produce correct outputs | `asm/generated/` |
| 4 | 3 | Add `JumpTableSource` trait, `disable()`, 3 factory methods | `JumpTable.scala` |
| 5 | 4 | Add Implementation/MulImpl enums, per-instruction config, derived methods | `JopCore.scala` |
| 6 | 4 | Update JopCluster/JopSdramTop/JopCyc5000Top to use derived flags | top-level files |
| 7 | 4 | Update all sim harnesses | `src/test/scala/jop/system/*.scala` |
| 8 | 4 | Delete old FpuMode, old JumpTableData variants, old variant dirs | cleanup |
| 9 | 5 | Add SystemAssembly/FpgaBoard/CarrierBoard + JopSystemConfig + JopGenerate | new files |
| 10 | 5 | Convert old entry points to thin wrappers | top-level files |
| 11 | 6 | Const.java generation from config, runtime build integration | `Const.java`, build scripts |
| 12 | 6 | Device driver conditional inclusion | `java/runtime/` build |
| 13 | — | Full verification | all sims + FPGA |

## Files to Modify

| File | Action |
|------|--------|
| `java/tools/src/com/jopdesign/tools/Jopa.java` | Remove 4 outputs, add `-n`, generate `extends JumpTableSource` |
| `asm/Makefile` | 3 superset targets (was 12 variant targets) |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTable.scala` | `disable()`, simplified factory methods |
| `spinalhdl/src/main/scala/jop/pipeline/JumpTableSource.scala` | **New**: trait for generated objects |
| `spinalhdl/src/main/scala/jop/system/JopCore.scala` | Per-instruction config, derived flags, delete old |
| `spinalhdl/src/main/scala/jop/system/JopSystemConfig.scala` | **New**: unified system config |
| `spinalhdl/src/main/scala/jop/system/SystemAssembly.scala` | **New**: FpgaBoard, CarrierBoard, SystemAssembly |
| `spinalhdl/src/main/scala/jop/system/JopSdramTop.scala` | Use derived flags, read from JopSystemConfig |
| `spinalhdl/src/main/scala/jop/system/JopCluster.scala` | Accept resolved jumpTable, per-core configs |
| `spinalhdl/src/main/scala/jop/system/JopCyc5000Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopDdr3Top.scala` | Same updates as JopSdramTop |
| `spinalhdl/src/main/scala/jop/system/JopGenerate.scala` | **New**: unified Verilog entry point |
| `spinalhdl/src/main/scala/jop/system/GenerateRuntime.scala` | **New**: generates Const.java from config |
| `java/runtime/src/jop/com/jopdesign/sys/Const.java` | Generated from config (was manual) |
| `java/Makefile` | Add `generate-runtime` target |
| `spinalhdl/src/main/scala/jop/system/IoConfig.scala` | No changes needed |
| `asm/generated/{fpu,serial-fpu,dsp,...}/` | **Delete**: old variant subdirs |
| `asm/generated/{Fpu,SerialFpu,Dsp,...}JumpTableData.scala` | **Delete**: old variant objects |

## Verification

1. `cd asm && make all` — 3 superset ROMs build
2. `sbt "runMain jop.system.GenerateRuntime simulation"` — Const.java generated correctly
3. `cd java && make all` — Java toolchain builds with generated Const.java
4. `sbt "Test / runMain jop.system.JopCoreBramSim"` — basic BRAM sim passes
5. `sbt "Test / runMain jop.system.JopJvmTestsBramSim"` — 59/60 JVM tests pass (software config)
6. `sbt "Test / runMain jop.system.JopFpuBramSim"` — 59/60 pass (hwFloat config)
7. `sbt "Test / runMain jop.system.JopHwMathBramSim"` — 59/60 pass (hwMath config)
8. `sbt "runMain jop.system.JopSdramTopVerilog"` — Verilog generates
9. FPGA build + HelloWorld.jop download — works on hardware
