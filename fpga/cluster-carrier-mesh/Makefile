# ---------------------------------------------------------------------------
# JOP cluster carrier: the mesh BUILD CHECK (the carrier handoff's test plan,
# step 1), for all four sockets, before the carrier is made.
#
# Four Colorlight i9+ (XC7A50T) in one carrier, sockets U1-U4 = nodes 0-3,
# each linked to the other three by a 36-pin source-synchronous mesh. This
# builds the mesh PRBS test (jop.system.MeshPrbsTop) for each socket against
# that socket's pin plan, so Vivado checks the plan: I/O placement and
# standards, the RX clocks' IBUF -> BUFG paths, the I/O-tile flip-flops, DRC
# and timing. README.md has the plan's provenance and the results.
#
#   make            generate RTL for all four sockets, then build each
#   make u1 .. u4   build one socket (make -j4 builds them side by side)
#   make report     each socket's verdict line, from its last build
#
# THE PIN PLAN IS THE HANDOFF'S, AS DELIVERED: constraints/carrier_U<n>.xdc,
# which the carrier's designer generated from mesh_pads.csv (plan v2,
# 2026-11-05). It is the artefact under test, so it is read, not regenerated;
# once the carrier is a hw.Board it should be generated (item 149).
# constraints/mesh_prbs.xdc is this design's own and is read after it.
# `make u1 DESIGN_XDC=` builds against the plan alone.
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
PART        := xc7a50tfgg484-1
TOP         := MeshPrbsTop
CONSTRAINTS := constraints
NP_TCL      := $(REPO_ROOT)/fpga/scripts/vivado_build_nonproject.tcl

XDC_U1 := $(CONSTRAINTS)/carrier_U1.xdc
XDC_U2 := $(CONSTRAINTS)/carrier_U2.xdc
XDC_U3 := $(CONSTRAINTS)/carrier_U3.xdc
XDC_U4 := $(CONSTRAINTS)/carrier_U4.xdc
DESIGN_XDC ?= $(CONSTRAINTS)/mesh_prbs.xdc

# One generator run writes all four sockets (jop.system.MeshPrbsBuild).
CFG_DIR   = $(REPO_ROOT)/build/meshPrbsU$(1)
GEN_STAMP := $(REPO_ROOT)/build/meshPrbsU1/rtl/.generated-all

.PHONY: all generate u1 u2 u3 u4 report
all: u1 u2 u3 u4
	@$(MAKE) --no-print-directory report

generate: $(GEN_STAMP)
$(GEN_STAMP): $(SCALA_SRC)
	cd $(REPO_ROOT) && sbt "runMain jop.system.MeshPrbsBuild"
	touch $@

# u<n>: Vivado (non-project) in the socket's own build directory, so its .Xil
# and logs stay there, then the reports. $(1) = socket number 1-4.
define socket
u$(1): $(GEN_STAMP)
	mkdir -p $(call CFG_DIR,$(1))/vivado/build
	cd $(call CFG_DIR,$(1))/vivado && export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 && \
	  JOP_TOP=$(TOP) JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(call CFG_DIR,$(1))/vivado/build \
	  JOP_RTL=$(call CFG_DIR,$(1))/rtl/$(TOP).v \
	  JOP_PLL_TCL="$(call CFG_DIR,$(1))/ip/mesh_clk.tcl" \
	  JOP_XDC="$(abspath $(XDC_U$(1))) $(if $(DESIGN_XDC),$(abspath $(DESIGN_XDC)))" \
	  JOP_SUMMARY=$(call CFG_DIR,$(1))/rtl/$(TOP).summary.txt \
	  $(VIVADO_BIN) -mode batch -nojournal -log vivado.log -source $(NP_TCL)
	cd $(call CFG_DIR,$(1))/vivado && $(VIVADO_BIN) -mode batch -nojournal -log reports.log \
	  -source $(CURDIR)/reports.tcl -tclargs $(call CFG_DIR,$(1))/vivado/build U$(1)
endef
$(foreach n,1 2 3 4,$(eval $(call socket,$(n))))

report:
	@for n in 1 2 3 4; do \
	  f=$(REPO_ROOT)/build/meshPrbsU$$n/vivado/build/verdict.txt; \
	  if [ -f $$f ]; then cat $$f; else echo "U$$n: not built"; fi; \
	done
