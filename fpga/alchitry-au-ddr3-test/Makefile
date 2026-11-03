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
#   make program   - Load the bitstream through the unit's probe (openFPGALoader)
#   make monitor   - Monitor UART output (console.mk: the unit's console, at
#                    the rate the build summary records)
#   make all       - generate + ips + project + bitstream
#   make run       - program + monitor
#

SHELL := /usr/bin/env bash

# Vivado installation
include ../../host.mk
VIVADO_HOME ?= $(call host_tool,vivado,2025.2,VIVADO_HOME)
RUN_VIVADO   = VIVADO_HOME=$(VIVADO_HOME) ../alchitry-au/scripts/run_vivado.sh

# Project paths
PROJECT_ROOT = ../..

# The serial console is console.mk's, as on every other board: the console of
# the unit this design runs on, at the rate its build summary records. The
# design is named by its generator, GEN_MAIN=...Build, so fpga/unit.mk asks
# for jop.system.Ddr3ExerciserDesign. Until 2026-11-03 this file kept its own:
# a 1 Mbaud constant, and a `monitor` calling ../alchitry-au/monitor.py, which
# does not exist. The exerciser's UART only transmits.
CFG            = ddr3Exerciser
CFG_NAME      := $(CFG)
CFG_DIR        = $(PROJECT_ROOT)/build/$(CFG_NAME)
GEN_MAIN       = jop.system.Ddr3ExerciserBuild
CONSOLE_TXONLY = yes
include ../console.mk
# console.mk's first rule (fpga/unit.mk's unit-info) would be the default goal.
.DEFAULT_GOAL := all
GENERATED_V = $(PROJECT_ROOT)/build/ddr3Exerciser/rtl/Ddr3ExerciserTop.v

.PHONY: all generate ips project bitstream program run clean

all: generate ips project bitstream

run: program monitor

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(PROJECT_ROOT) && sbt "runMain $(GEN_MAIN)"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

# --------------------------------------------------------------------------
# Vivado IP generation (share with alchitry-au)
# --------------------------------------------------------------------------

# The Au's MIG IP, shared with ../alchitry-au. The clock IP is this design's
# own (Ddr3ExerciserTop.pll, status item 173): `make generate` writes its
# clk_wiz definition, and the project creates it (JOP_PLL_TCL). This target
# looked for both IPs under ../alchitry-au/vivado/ip/, where nothing has
# written them since the IP moved under build/ (2026-08), so it rebuilt them
# every time.
ips:
	@if [ -f $(AU_IP)/mig_7series_0/mig_7series_0.xci ]; then \
	  echo "=== MIG IP already generated (from alchitry-au) ==="; \
	else \
	  $(RUN_VIVADO) ../alchitry-au/vivado/tcl/create_mig.tcl; \
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
# Where `bitstream` leaves it: the project's own implementation run.
BIT_FILE    = $(ABS_BUILD)/ddr3_test/ddr3_test.runs/impl_1/Ddr3ExerciserTop.bit
AU_IP      := $(PROJECT_ROOT)/build/ip/alchitry-au

project:
	JOP_PROJ=ddr3_test JOP_PART=$(PART) JOP_TOP=Ddr3ExerciserTop \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(CURDIR)/$(GENERATED_V) \
	  JOP_XDC=$(CURDIR)/vivado/constraints/alchitry_au_v2.xdc \
	  JOP_IP=$(AU_IP)/mig_7series_0/mig_7series_0.xci \
	  JOP_PLL_TCL="$(wildcard $(PROJECT_ROOT)/build/ddr3Exerciser/ip/*.tcl)" \
	  JOP_IP_GEN_TARGET=yes \
	  JOP_GEN_HINT="run: make generate" \
	  $(RUN_VIVADO) $(CREATE_TCL)

bitstream:
	JOP_PROJ=ddr3_test JOP_BUILD_DIR=$(ABS_BUILD) JOP_BIT=Ddr3ExerciserTop.bit \
	  $(RUN_VIVADO) $(BUILD_TCL)

# --------------------------------------------------------------------------
# FPGA programming and monitoring
# --------------------------------------------------------------------------

# Loaded as hw_verify loads this board: by openFPGALoader, through the probe of
# the unit this design runs on (fpga/unit.mk), with the loader the unit names.
# Until 2026-11-03 this ran a Vivado script of its own, which looked for the
# bitstream in this directory's vivado/build/ -- where nothing has written
# since the build moved to build/ddr3Exerciser/ -- and programmed whichever
# JTAG target hw_server listed first.
LOADER = $(if $(call unit_fact,LOADER_BOARD),-b $(call unit_fact,LOADER_BOARD),-c $(call unit_fact,LOADER_CABLE))

program: $(UNIT_DESC)
	@test -n "$(BOARD_ALIAS)" || { echo "make: no unit has a probe for $(UNIT_DESIGN) (setup/units.toml)" >&2; exit 1; }
	@test -f "$(BIT_FILE)" || { echo "make: no bitstream at $(BIT_FILE) -- run: make bitstream" >&2; exit 1; }
	busdev=$$($(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --busdev $(BOARD_ALIAS)) && \
	  sudo openFPGALoader $(LOADER) --busdev-num $$busdev $(BIT_FILE)
	@echo "=== programmed: $(BIT_FILE) ==="

# monitor (and download/reset, which refuse: the UART only transmits) come
# from console.mk.

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
