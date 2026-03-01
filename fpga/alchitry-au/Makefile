#
# JOP DDR3 - Alchitry AU V2 (Artix-7 + DDR3)
#
# Targets:
#   make microcode - Build serial-boot microcode
#   make generate  - Generate Verilog from SpinalHDL
#   make ips       - Generate Vivado IPs (MIG + ClkWiz)
#   make project   - Create Vivado project
#   make bitstream - Full Vivado build (synth + impl + bitstream)
#   make program   - Program FPGA via JTAG
#   make download  - Send .jop via serial
#   make monitor   - Monitor UART output (1 Mbaud)
#   make all       - microcode + generate + ips + project + bitstream
#   make run       - program + download + monitor
#   make full      - all + run
#
# SMP (dual-core DDR3):
#   make generate-smp  - Generate SMP Verilog
#   make project-smp   - Create SMP Vivado project
#   make bitstream-smp - Build SMP bitstream
#   make program-smp   - Program SMP bitstream
#   make run-smp       - program-smp + download NCoreHelloWorld + monitor
#
# GC Exerciser (DDR3 stress test without JOP):
#   make generate-exerciser  - Generate exerciser Verilog
#   make project-exerciser   - Create Vivado project
#   make bitstream-exerciser - Build exerciser bitstream
#
# Trace Replayer (DDR3 BMB trace replay from GC sim):
#   make generate-replayer   - Generate replayer Verilog
#   make project-replayer    - Create Vivado project
#   make bitstream-replayer  - Build replayer bitstream
#   make program-replayer    - Program FPGA
#
# Flash Boot (autonomous boot from SPI config flash):
#   make microcode-flash  - Build flash-boot microcode
#   make generate-flash   - Generate Verilog with config flash support
#   make project-flash    - Create Vivado project (with flash constraints)
#   make bitstream-flash  - Build bitstream + SPI .bin via write_cfgmem
#   make flash-image      - Combine bitstream .bin + .jop into flash image
#   make program-flash    - Program SPI flash via Vivado indirect programming
#   make full-flash-boot  - Full flash boot flow (all of the above)
#
# UART Flash Programmer (for SST26VF032B, since Vivado doesn't support it):
#   make generate-flash-programmer  - Generate flash programmer Verilog
#   make project-flash-programmer   - Create Vivado project
#   make bitstream-flash-programmer - Build flash programmer bitstream
#   make program-flash-programmer   - Load programmer onto FPGA via JTAG
#   make program-flash              - Program flash via UART (with --sst26)
#

SHELL := /usr/bin/env bash

# Vivado installation
VIVADO_HOME ?= /opt/xilinx/2025.2/Vivado
RUN_VIVADO := VIVADO_HOME=$(VIVADO_HOME) ./scripts/run_vivado.sh

# Serial port for UART
SERIAL_PORT ?= $(shell ./scripts/find_alchitry_ports.sh --serial 2>/dev/null || echo /dev/ttyUSB1)
BAUD_RATE = 1000000

# JOP application file
JOP ?= ../../java/apps/Smallest/HelloWorld.jop

# Project paths (sbt runs from repo root)
PROJECT_ROOT = ../..
ASM_DIR = ../../asm
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/JopDdr3Top.v

# Flash boot: JOP application offset in flash
# SST26VF032B = 4 MB total; XC7A35T bitstream ~2.1 MB; offset at 2.25 MB
JOP_OFFSET ?= 0x240000

.PHONY: all microcode generate ips project bitstream program download monitor run full clean check-board find-serial-port generate-exerciser project-exerciser bitstream-exerciser generate-replayer project-replayer bitstream-replayer program-replayer generate-smp project-smp bitstream-smp program-smp run-smp microcode-flash generate-flash project-flash bitstream-flash flash-image program-flash full-flash-boot generate-flash-programmer project-flash-programmer bitstream-flash-programmer program-flash-programmer

# Default: microcode + generate + ips + project + bitstream
all: microcode generate ips project bitstream

# Full flow
full: all run

# Program + download + monitor
run: program download monitor

# --------------------------------------------------------------------------
# Serial-boot microcode
# --------------------------------------------------------------------------

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopDdr3TopVerilog"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

# --------------------------------------------------------------------------
# Vivado IP generation
# --------------------------------------------------------------------------

ips:
	$(RUN_VIVADO) vivado/tcl/create_clk_wiz.tcl
	$(RUN_VIVADO) vivado/tcl/create_mig.tcl
	@echo "=== IPs generated ==="

# --------------------------------------------------------------------------
# Vivado project and bitstream
# --------------------------------------------------------------------------

project:
	$(RUN_VIVADO) vivado/tcl/create_project.tcl

bitstream:
	$(RUN_VIVADO) vivado/tcl/build_bitstream.tcl

# --------------------------------------------------------------------------
# FPGA programming
# --------------------------------------------------------------------------

program:
	$(RUN_VIVADO) vivado/tcl/program_bitstream.tcl

# --------------------------------------------------------------------------
# Serial download and UART monitor
# --------------------------------------------------------------------------

download:
	python3 ../scripts/download.py -e $(JOP) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Board diagnostics
# --------------------------------------------------------------------------

check-board:
	./scripts/check_board.sh

find-serial-port:
	./scripts/find_alchitry_ports.sh --serial

# --------------------------------------------------------------------------
# SMP (dual-core DDR3)
# --------------------------------------------------------------------------

generate-smp:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopDdr3SmpTopVerilog"
	@echo "=== SMP Verilog generated ==="

project-smp:
	$(RUN_VIVADO) vivado/tcl/create_project_smp.tcl

bitstream-smp:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_smp.tcl

program-smp:
	$(RUN_VIVADO) vivado/tcl/program_bitstream_smp.tcl

run-smp: program-smp
	python3 ../scripts/download.py -e ../../java/apps/Small/NCoreHelloWorld.jop $(SERIAL_PORT) $(BAUD_RATE)
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# GC Exerciser (DDR3 stress test without JOP)
# --------------------------------------------------------------------------

generate-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.Ddr3GcExerciserTopVerilog"
	@echo "=== Exerciser Verilog generated ==="

project-exerciser:
	$(RUN_VIVADO) vivado/tcl/create_project_exerciser.tcl

bitstream-exerciser:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_exerciser.tcl

# --------------------------------------------------------------------------
# Trace Replayer (DDR3 BMB trace replay without JOP)
# --------------------------------------------------------------------------

generate-replayer:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.Ddr3TraceReplayerTopVerilog"
	@echo "=== Replayer Verilog generated ==="

project-replayer:
	$(RUN_VIVADO) vivado/tcl/create_project_replayer.tcl

bitstream-replayer:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_replayer.tcl

program-replayer:
	$(RUN_VIVADO) vivado/tcl/program_replayer.tcl

# --------------------------------------------------------------------------
# Flash Boot (autonomous boot from SPI config flash)
# --------------------------------------------------------------------------

microcode-flash:
	cd $(ASM_DIR) && $(MAKE) flash-au
	@echo "=== Flash microcode generated (Artix-7, offset $(JOP_OFFSET)) ==="

generate-flash:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopDdr3FlashTopVerilog"
	@echo "=== Flash-boot Verilog generated ==="

project-flash:
	$(RUN_VIVADO) vivado/tcl/create_project_flash.tcl

bitstream-flash:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_flash.tcl

flash-image:
	python3 ../scripts/make_flash_image.py --no-bitrev \
		vivado/build/jop_flash.bin $(JOP) \
		vivado/build/flash_image.bin --jop-offset $(JOP_OFFSET)

# UART flash programmer (for SST26VF032B — Vivado doesn't support this part)
generate-flash-programmer:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.FlashProgrammerDdr3TopVerilog"
	@echo "=== Flash programmer Verilog generated ==="

project-flash-programmer:
	$(RUN_VIVADO) vivado/tcl/create_project_flash_programmer.tcl

bitstream-flash-programmer:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_flash_programmer.tcl

program-flash-programmer:
	$(RUN_VIVADO) vivado/tcl/program_flash_programmer.tcl

# Program flash via UART (requires flash programmer bitstream loaded on FPGA)
program-flash:
	sudo python3 ../scripts/flash_program.py --sst26 \
		vivado/build/flash_image.bin --port $(SERIAL_PORT) -v

full-flash-boot: microcode-flash generate-flash project-flash bitstream-flash flash-image program-flash

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
