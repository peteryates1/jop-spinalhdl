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
SERIAL_PORT ?= $(shell ../scripts/usb_serial_map --by-id alchitry 2>/dev/null \
                 || echo /dev/serial/by-id/usb-Xilinx_Alchitry_Au_V2_000000-if01-port0)
BAUD_RATE = 1000000

# Project paths
PROJECT_ROOT = ../..
GENERATED_V = $(PROJECT_ROOT)/build/ddr3Exerciser/rtl/Ddr3ExerciserTop.v

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

# Both flows come from the shared Vivado scripts under fpga/scripts/ -- see
# their headers for what the per-board copies had drifted into. This board's
# create_project was the only one that pre-generated the IP output products, so
# that became an explicit option rather than being dropped or made universal.
PART       := xc7a35tftg256-2
CREATE_TCL := $(PROJECT_ROOT)/fpga/scripts/vivado_create_project.tcl
BUILD_TCL  := $(PROJECT_ROOT)/fpga/scripts/vivado_build_project.tcl
# Standalone top: build under its own config directory, not in the board dir.
ABS_BUILD  := $(PROJECT_ROOT)/build/ddr3Exerciser/vivado/build
AU_IP      := $(CURDIR)/../alchitry-au/vivado/ip

project:
	JOP_PROJ=ddr3_test JOP_PART=$(PART) JOP_TOP=Ddr3ExerciserTop \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(CURDIR)/$(GENERATED_V) \
	  JOP_XDC=$(CURDIR)/vivado/constraints/alchitry_au_v2.xdc \
	  JOP_IP="$(AU_IP)/clk_wiz_0/clk_wiz_0.xci $(AU_IP)/mig_7series_0/mig_7series_0.xci" \
	  JOP_IP_GEN_TARGET=yes \
	  JOP_GEN_HINT="run: make generate" \
	  $(RUN_VIVADO) $(CREATE_TCL)

bitstream:
	JOP_PROJ=ddr3_test JOP_BUILD_DIR=$(ABS_BUILD) JOP_BIT=Ddr3ExerciserTop.bit \
	  $(RUN_VIVADO) $(BUILD_TCL)

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
