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

SHELL := /usr/bin/env bash

# Vivado installation
VIVADO_HOME ?= /opt/xilinx/2025.2/Vivado
RUN_VIVADO := VIVADO_HOME=$(VIVADO_HOME) ./scripts/run_vivado.sh

# Serial port for UART
SERIAL_PORT ?= $(shell ./scripts/find_alchitry_ports.sh --serial 2>/dev/null || echo /dev/ttyUSB1)
BAUD_RATE = 1000000

# JOP application file
JOP ?= ../../java/apps/Small/HelloWorld.jop

# Project paths (sbt runs from repo root)
PROJECT_ROOT = ../..
ASM_DIR = ../../asm
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/JopDdr3Top.v

.PHONY: all microcode generate ips project bitstream program download monitor run full clean check-board find-serial-port generate-exerciser project-exerciser bitstream-exerciser generate-replayer project-replayer bitstream-replayer program-replayer

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
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
