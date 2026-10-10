# ---------------------------------------------------------------------------
# JOP on the fpga-db-colorlight-i5 base board + Colorlight i9+ (XC7A50T),
# running from the module's 8 MB SDRAM.
#
#   make create-ip   the clk_wiz for this build's frequency (once per MHz)
#   make             generate RTL + XDC, then Vivado (non-project)
#   make program     load the .bit through the board's RP2040-STAMP (dirtyJtag)
#   make download    send HelloWorld.jop over the stamp's CDC0 (console.mk)
#   make monitor
#
# The flow is the shared one (../vivado.mk, fpga/scripts/vivado_build_
# nonproject.tcl). What is board-specific is only: the part, the clock IP and
# the constraint list.
#
# THE PART IS -1 ON PURPOSE. The modules' speed grade is unknown (heat sinks
# stay on); -1 is the conservative grade and its bitstream loads on a -2.
#
# THE CLOCK IP FOLLOWS THE BUILD (Wukong status item 162): the frequency is
# read from the generated summary, the IP lives in a per-frequency root, and
# check_clk_ip.py refuses a mismatch before Vivado runs. Without that, an IP
# generated at one frequency silently clocks a design generated for another
# and the console reads as a dead board.
# ---------------------------------------------------------------------------
REPO_ROOT    := $(shell cd ../.. && pwd)
PROJECT_ROOT  = ../..

CFG          ?= colorlightDbI9PlusSdram
BOARD_ALIAS   = colorlight-db
CONSOLE_ALIAS = colorlight-db-0

include ../vivado.mk

PART     := xc7a50tfgg484-1
TOP      := JopSdramI9PlusTop
SUMMARY   = $(CFG_DIR)/rtl/$(TOP).summary.txt
GEN_XDC   = $(VIVADO_PRJ)/colorlight_db_i9plus_jop_sdram.xdc
BIT_FILE  = $(VIVADO_BUILD)/$(TOP).bit
CLK_TCL   = $(REPO_ROOT)/fpga/qmtech-xc7a100t-wukong/vivado/tcl/create_sdram_clk_wiz.tcl
IP_BASE   = $(REPO_ROOT)/build/ip/colorlight-db-i9plus-sdr

# The build's own frequency, once generated. Recursive (=), so it is read when
# a recipe runs, after `generate` has written the summary.
MHZ = $(shell sed -n 's/^ *Clock: *\([0-9]*\) MHz.*/\1/p' $(SUMMARY) 2>/dev/null | head -1)

.PHONY: all build create-ip program
all: build

# The XDC is generated from the same config as the RTL, so it rides on the
# same stamp. ABSOLUTE path: the recipe cd's to the repo root and the main runs
# FORKED, so a board-relative path (CFG_DIR is ../../build/...) lands two
# directories above the repo -- it did, once, and Vivado read the stale file.
$(GEN_XDC): $(GEN_STAMP)
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(CFG) --write $(abspath $(GEN_XDC))"

create-ip: $(GEN_STAMP)
	@[ -n "$(MHZ)" ] || { echo "no Clock: line in $(SUMMARY)"; exit 1; }
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(CLK_TCL) \
	  -tclargs -freq $(MHZ) -in_freq 25 -part $(PART) -shift -3.0 -ip_root $(IP_BASE)$(MHZ)

build: $(GEN_STAMP) $(GEN_XDC)
	@xci=$(IP_BASE)$(MHZ)/sdr_clk/sdr_clk.xci; \
	[ -f "$$xci" ] || { echo "No $(MHZ) MHz clock IP at $$xci -- run: make create-ip"; exit 1; }; \
	python3 $(REPO_ROOT)/fpga/scripts/check_clk_ip.py $(SUMMARY) $$xci || exit 1; \
	$(VIVADO_ENV) && JOP_TOP=$(TOP) JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(VIVADO_BUILD) \
	  JOP_RTL=$(CFG_DIR)/rtl/$(TOP).v \
	  JOP_BIN_GLOB='$(CFG_DIR)/rtl/$(TOP).v_*.bin' \
	  JOP_IP=$$xci \
	  JOP_XDC="$(GEN_XDC) $(CURDIR)/constraints/sdr_cdc.xdc" \
	  JOP_SUMMARY=$(SUMMARY) \
	  $(VIVADO) -mode batch -source $(NP_TCL)

program:
	$(MAKE) program-bit BIT_FILE=$(BIT_FILE)
