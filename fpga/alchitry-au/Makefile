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

.PHONY: all microcode generate ips project bitstream program download monitor run full clean check-board find-serial-port

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
	cd $(ASM_DIR) && make -e JVM_TYPE=SERIAL
	mkdir -p $(ASM_DIR)/generated/serial
	cp $(ASM_DIR)/generated/mem_rom.dat $(ASM_DIR)/generated/serial/mem_rom.dat
	cp $(ASM_DIR)/generated/rom.mif $(ASM_DIR)/generated/serial/rom.mif
	cp $(ASM_DIR)/generated/mem_ram.dat $(ASM_DIR)/generated/serial/mem_ram.dat
	cd $(ASM_DIR) && make -e JVM_TYPE=SIMULATION
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
	python3 download.py -e $(JOP) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Board diagnostics
# --------------------------------------------------------------------------

check-board:
	./scripts/check_board.sh

find-serial-port:
	./scripts/find_alchitry_ports.sh --serial

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
