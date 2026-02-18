#
# DDR3 Exerciser Test - Alchitry AU V2 (Artix-7 + DDR3)
#
# Tests the full memory path: BMB -> BmbCacheBridge -> LruCacheCore -> CacheToMigAdapter -> MIG -> DDR3
# Reports results via UART at 1 Mbaud.
#
# Targets:
#   make generate  - Generate Verilog from SpinalHDL
#   make ips       - Generate Vivado IPs (reuses from alchitry-au if available)
#   make project   - Create Vivado project
#   make bitstream - Full Vivado build (synth + impl + bitstream)
#   make program   - Program FPGA via JTAG
#   make monitor   - Monitor UART output (1 Mbaud)
#   make all       - generate + ips + project + bitstream
#   make run       - program + monitor
#

SHELL := /usr/bin/env bash

# Vivado installation
VIVADO_HOME ?= /opt/xilinx/2025.2/Vivado
RUN_VIVADO := VIVADO_HOME=$(VIVADO_HOME) ../alchitry-au/scripts/run_vivado.sh

# Serial port for UART
SERIAL_PORT ?= $(shell ../alchitry-au/scripts/find_alchitry_ports.sh --serial 2>/dev/null || echo /dev/ttyUSB1)
BAUD_RATE = 1000000

# Project paths
PROJECT_ROOT = ../..
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/Ddr3ExerciserTop.v

.PHONY: all generate ips project bitstream program monitor run clean

all: generate ips project bitstream

run: program monitor

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.Ddr3ExerciserTopVerilog"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

# --------------------------------------------------------------------------
# Vivado IP generation (share with alchitry-au)
# --------------------------------------------------------------------------

ips:
	@if [ -f ../alchitry-au/vivado/ip/clk_wiz_0/clk_wiz_0.xci ] && \
	    [ -f ../alchitry-au/vivado/ip/mig_7series_0/mig_7series_0.xci ]; then \
	  echo "=== IPs already generated (from alchitry-au) ==="; \
	else \
	  echo "=== Generating IPs... ==="; \
	  $(RUN_VIVADO) ../alchitry-au/vivado/tcl/create_clk_wiz.tcl; \
	  $(RUN_VIVADO) ../alchitry-au/vivado/tcl/create_mig.tcl; \
	  echo "=== IPs generated ==="; \
	fi

# --------------------------------------------------------------------------
# Vivado project and bitstream
# --------------------------------------------------------------------------

project:
	$(RUN_VIVADO) vivado/tcl/create_project.tcl

bitstream:
	$(RUN_VIVADO) vivado/tcl/build_bitstream.tcl

# --------------------------------------------------------------------------
# FPGA programming and monitoring
# --------------------------------------------------------------------------

program:
	$(RUN_VIVADO) vivado/tcl/program_bitstream.tcl

monitor:
	python3 ../alchitry-au/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
