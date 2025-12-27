
Goals:

- Modernize and create a port of [jop](https://github.com/peteryates1/jop)
- Initialally port of [core](https://github.com/peteryates1/jop/tree/main/vhdl/core) to spinalhdl/scala.
- Create configurable systems based on particular FPGA based boards:
    - [EP4CGX150DF27_CORE_BOARD](https://github.com/ChinaQMTECH/EP4CGX150DF27_CORE_BOARD)
    - [CYCLONE_IV_EP4CE15](https://github.com/ChinaQMTECH/CYCLONE_IV_EP4CE15)
    - [alchitry-au](https://shop.alchitry.com/products/alchitry-au)
    - [MAX1000](https://www.trenz-electronic.de/en/MAX1000-IoT-Maker-Board-8kLE-8-MByte-SDRAM-8-MByte-Flash-6.15-x-2.5-cm/TEI0001-04-DBC87A)
    - [CYC5000](https://www.trenz-electronic.de/en/CYC5000-with-Altera-Cyclone-V-E-5CEBA2-C8-8-MByte-SDRAM/TEI0050-01-AAH13A)
    - [CYC1000](https://www.trenz-electronic.de/en/CYC1000-with-Intel-Cyclone-10-LP-10CL025-C8-8-MByte-SDRAM-8-MByte-Flash/TEI0003-03-QFCT4A)


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





