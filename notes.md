
Goals:

- Modernize and create a port of [jop](https://github.com/peteryates1/jop)
- Initialally port of [core](https://github.com/peteryates1/jop/tree/main/vhdl/core) to spinalhdl/scala.
- Create configurable systems based on particular FPGA based boards:
    - Altera/Intel
        - QMTECH
            - [EP4CGX150DF27_CORE_BOARD](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD)
            - [CYCLONE_IV_EP4CE15](https://github.com/ChinaQMTECH/CYCLONE_IV_EP4CE15)
            - [Daughter Board V4](https://github.com/ChinaQMTECH/DB_FPGA)
        - Trenz Electronic
            - [MAX1000](https://www.trenz-electronic.de/en/MAX1000-IoT-Maker-Board-8kLE-8-MByte-SDRAM-8-MByte-Flash-6.15-x-2.5-cm/TEI0001-04-DBC87A)
            - [CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A)
            - [CYC1000](https://www.trenz-electronic.de/en/CYC1000-with-Intel-Cyclone-10-LP-10CL025-C8-8-MByte-SDRAM-8-MByte-Flash/TEI0003-03-QFCT4A)
    - AMD/Xilinx
        - [Alchitry Au V2](https://shop.alchitry.com/products/alchitry-au)


reference: ~/git/jop - local copy of https://github.com/peteryates1/jop

Steps:
- Create regression tests for the JOP core using cocotb/ghdl.
    test every microcode to cycle accuracy
    test bytecore to cycle accuracy.
    
- Create core in spinalhdl and generate vhdl to run against cocotb regression tests.
    
- Re-create those tests as scalatest suite that can be used with spinalsim/verilator.
- Create a scala-gui JOP MIcrocode Interacive Debugger to front the spinalsim running the new core implementation.  Want to be able torun the jvm microcode. and ensure it still runs completely.

We have current working implementation. Running JDK 1.5/1.6 code.  This can serve as basis for functionality testing.

Agent - vhdl-tester - python/cocotb/ghdl/vhdl
- creates and maintains regression tests against original jop hdl

Agent - spinalhdl-developer - scala/spinalhdl
- creates and maintains reimplementation of the original jop in spinalhdl, using the pipeline structure

Agent - spinalhdl-tester - scala/scalatest/spinalhdl
- creates and maintains regression tests against new spinalhdl implementations based on cocotb/ghdl regression tests

Agent - eclipse tooling tester - java/eclipse plugin dev

Agent - eclipse tooling developer - java/eclipse plugin dev

Agent - jdk tester - java/jop microcode

Agent - jdk developer - java/jop microcode

Agent - reviewer

lower goals:
eclipse based ide:
- for jop debug and programming
- jvm implementation in microcode (microcode assembler)
    - implement simulation of core in eclipse using spinalhdl simulation using verilator to run a simulation of the hardware
- serial interface to simulation or through real usb/com ports
- build/nature - runs Jopa, java build, jopizer, run Quartus/Vivado tools, merge fpga stream and *.jop image, program fpga, upload jop image.
- run junit tests in the hardware - display results in junit view.


workflow
build tools
build target jvm
build jop application against JOP BSP
build JOP processor for FPGA (Quartus/Vivado)
    configurator - # cores, memory type ddr3/format 32Mx16, etc
    
clean up current code base

pipeline:
bytecode
fetch/microcode-rom -> microcode fetch -> microcode decode -> microcode exectute(stack).

```
┌──────────────┐                    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   bytecode   │                    │  microcode   │    │  microcode   │    │  microcode   │
│    fetch     │─────────┬─────────▶│    fetch     │─┬─▶│   decode     │───▶│   execute    │
│  translate   │         │          │              │ |  │              │    │  (tos/nos)   │
└──────┬───────┘         │          └──────┬───────┘ |  └──────────────┘    └──────┬───────┘
       │                 │                 │         |                             │spill & fill
┌──────┴───────┐  ┌──────┴───────┐  ┌──────┴───────┐ |  ┌──────────────┐    ┌──────┴───────┐ 
| method cache │  |  jump tbl    │  │microcode rom │ └──│ Address Gen  │───▶│ stack buffer │
└──────────────┘  └──────────────┘  └──────────────┘    └──────────────┘    └──────────────┘
```

- HDL
    - regression tests cocotb/ghdl against original vhdl
        - cycle accurate
        - verifiy expected TOS/NOS/SP/PC/JPC/...
        - must cover all microcode instructions
        - verify against existing blink.asm, jvm.asm code and test java targets
    - code equivalent regression tests in spinalhdl sim/scalatest specs
    - create spinalhdl version
        - generate vhdl and run against cocotb/ghdl regression tests
        - run against scalatest/spinalhdlsim regression tests

modernize target system jdk: 8 then 11 then 17 ...





merge jopa into tools 
const -> separate config from const
Found the root cause. Native.memCopy() is used in GC.markAndCopy() but the copy signal is never handled in our state machine - it's defined in MemCtrlInput but never referenced. The copy operation silently does nothing, so GC "copies" objects but the destination is garbage.
Now I understand the issue. Native.memCopy() maps to the copy hardware instruction, which our memory controller never handles — so
  the GC copies nothing and objects end up as garbage. Since we use stop-the-world GC (not concurrent), we don't need the address
  translation feature. The fix is to use the simple software copy that's already commented out in the source.

We need to fix the memory controller implementation - are there any other areas that are not implemented.

generate pll component

● Here's the completeness picture:

  Memory Controller Status

  Working (core operations)

  - Simple rd/wr/rdc/rdf/wrf
  - getfield/putfield (with I/O routing for HardwareObjects)
  - getstatic/putstatic
  - iaload/iastore (with IAST_WAIT fix)
  - Bytecode fill (with method cache, pipelined, burst-capable)
  - stmwa (address write), stidx (index latch)

  Missing

  Feature: memcopy
  Severity: CRITICAL
  Notes: GC object relocation (cp0-cpstop). GC currently works because it's semi-space copy using Native.rdMem/Native.wrMem in Java —
    but the hardware-accelerated copy path is missing
  ────────────────────────────────────────
  Feature: Exception states
  Severity: HIGH
  Notes: Null pointer (npexc), array bounds (abexc), illegal assignment (iaexc). Currently no hardware bounds/null checks —
    out-of-bounds access is undefined behavior
  ────────────────────────────────────────
  Feature: Atomic operations
  Severity: LOW
  Notes: atmstart/atmend signals exist but are unused. Only matters for multicore
  ────────────────────────────────────────
  Feature: putref / GC barriers
  Severity: LOW
  Notes: Signal captured but not used. Only needed for incremental/concurrent GC
  ────────────────────────────────────────
  Feature: Object/array caches
  Severity: LOW
  Notes: Performance optimization, not correctness
  ────────────────────────────────────────
  Feature: Cache hints
  Severity: LOW
  Notes: bypass, direct_mapped, full_assoc — all treated uniformly
  ────────────────────────────────────────
  Feature: Scoped memory checks
  Severity: LOW
  Notes: dest_level tracking. Only for RTSJ scoped memory

  Assessment

  For single-core stop-the-world GC (our current config), the memcopy and exception states are the two meaningful gaps:

  1. memcopy: The GC works today because markAndCopy() does object copying in Java via Native.rdMem/Native.wrMem loops. The VHDL copy
  operation is a hardware-accelerated version. Not broken, just slower.
  2. Exception states: Array bounds violations and null pointer dereferences silently corrupt memory instead of throwing exceptions.
  Works fine when code is correct, but dangerous for debugging.

  The other items (atomic, putref, caches, scoped memory) are for features we don't use (multicore, concurrent GC, RTSJ scopes,
  performance caches).

  Would you like to tackle any of these, or is this good for now?

interrupt handling?

check new object
java test cases -> from original jop 
/srv/git/jop/java/target/src/test/jvm
/srv/git/jop/java/target/src/test/jvmtest

