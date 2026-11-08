# ---------------------------------------------------------------------------
# JOP cluster carrier: the mesh BUILD CHECK (the carrier handoff's test plan,
# step 1), before the carrier is made -- ONE bitstream for all four sockets.
#
# Four Colorlight i9+ (XC7A50T) in one carrier, sockets U1-U4 = nodes 0-3,
# each linked to the other three by a 36-pin source-synchronous mesh. This
# builds the mesh PRBS test (jop.system.MeshPrbsTop), which reads the socket
# straps at start-up and then drives only that socket's mesh lines, so one
# bitstream serves every socket. Vivado checks the plan: I/O placement and
# standards, the RX clocks' IBUF -> BUFG paths, the I/O-tile flip-flops, the
# tri-state controls, DRC and timing. README.md has the plan's provenance and
# the results.
#
#   make          generate the RTL and the constraints, build, report
#   make report   the verdict from the last build
#
# THE CONSTRAINTS ARE GENERATED, with the RTL (jop.system.MeshPrbsBuild), from
# the carrier designer's plan -- plan/mesh_pads.csv, the source of truth, and
# plan/pads.csv -- and the PCB's straps, plan/straps.csv, into the build tree. The four per-socket XDCs the designer
# delivered with it stay in plan/xdc/ as delivered; no build reads them.
#
# THE PART IS -1, as for the i9+ base board (fpga/colorlight-db-i9plus): the
# modules' grade is not known, and -1 is the slower.
# ---------------------------------------------------------------------------
SHELL := /usr/bin/env bash
PROJECT_ROOT = ../..
REPO_ROOT   := $(abspath $(PROJECT_ROOT))
include ../../host.mk
# SCALA_SRC: every sbt project's sources (fpga/scala.mk).
include ../scala.mk
.DEFAULT_GOAL := all

VIVADO_VERSION ?= 2025.2
VIVADO_BIN     ?= $(call host_tool,vivado,$(VIVADO_VERSION),VIVADO_BIN)/bin/vivado
PART    := xc7a50tfgg484-1
TOP     := MeshPrbsTop
NP_TCL  := $(REPO_ROOT)/fpga/scripts/vivado_build_nonproject.tcl
# Every plan table, not just the ones the generator reads today (mesh_pads,
# pads and straps): a table added later cannot then be left out, and a
# needless regeneration costs seconds where a stale XDC costs a wrong build.
PLAN    := $(wildcard plan/*.csv)

CFG_DIR   := $(REPO_ROOT)/build/meshPrbs
GEN_STAMP := $(CFG_DIR)/rtl/.generated

.PHONY: all generate build report
all: build
	@$(MAKE) --no-print-directory report

# The RTL, the XDC beside it, the clk_wiz definition and the summary, in one
# generator run: the plan is an input like the Scala.
generate: $(GEN_STAMP)
$(GEN_STAMP): $(SCALA_SRC) $(UCODE_SCALA) $(PLAN)
	cd $(REPO_ROOT) && sbt "runMain jop.system.MeshPrbsBuild"
	touch $@

# Vivado (non-project) in the build's own directory, so its .Xil and logs stay
# there, then the reports.
build: $(GEN_STAMP)
	mkdir -p $(CFG_DIR)/vivado/build
	cd $(CFG_DIR)/vivado && export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 && \
	  JOP_TOP=$(TOP) JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(CFG_DIR)/vivado/build \
	  JOP_RTL=$(CFG_DIR)/rtl/$(TOP).v \
	  JOP_PLL_TCL="$(CFG_DIR)/ip/mesh_clk.tcl" \
	  JOP_XDC="$(CFG_DIR)/rtl/$(TOP).xdc" \
	  JOP_SUMMARY=$(CFG_DIR)/rtl/$(TOP).summary.txt \
	  $(VIVADO_BIN) -mode batch -nojournal -log vivado.log -source $(NP_TCL)
	cd $(CFG_DIR)/vivado && $(VIVADO_BIN) -mode batch -nojournal -log reports.log \
	  -source $(CURDIR)/reports.tcl -tclargs $(CFG_DIR)/vivado/build

report:
	@f=$(CFG_DIR)/vivado/build/verdict.txt; \
	if [ -f $$f ]; then cat $$f; else echo "not built"; fi
