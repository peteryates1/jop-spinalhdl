# ---------------------------------------------------------------------------
# JOP on the fpga-db-colorlight-i5 base board + Colorlight i9+ (XC7A50T),
# running from the module's 8 MB SDRAM.
#
#   make build       generate RTL + XDC, then Vivado (non-project). A bare
#                    `make` does NOT build: vivado.mk's microcode rule comes
#                    first and becomes the default goal (also on the Wukong).
#   make program     load the .bit through the board's RP2040-STAMP (dirtyJtag)
#   make download    send HelloWorld.jop over the stamp's CDC0 (console.mk)
#   make monitor
#
# With Ethernet (RJ1, RGMII at 100 Mb/s): CFG=colorlightDbI9PlusEth on each
# of the above; docs/peripherals/networking.md has the steps and results.
# At 1000 Mb/s (jop.io.Rgmii1000): CFG=colorlightDbI9PlusEth1000.
#
# The flow is the shared one (../vivado.mk, fpga/scripts/vivado_build_
# nonproject.tcl). What is board-specific is only: the part and the
# constraint list.
#
# THE PART IS -1 ON PURPOSE. The modules' speed grade is unknown (heat sinks
# stay on); -1 is the conservative grade and its bitstream loads on a -2.
#
# THE CLOCK IP IS THE DESIGN'S (status item 173). The preset defines its PLL
# (JopConfig.xilinxSdrPll), generating its Verilog writes the clk_wiz
# definition into build/<config>/ip/, and the build creates the IP from it
# (JOP_PLL_TCL). Until 2026-11-02 `make create-ip` made one IP per frequency
# with the Wukong's script, and check_clk_ip.py matched it to the build: an IP
# generated at one frequency silently clocks a design generated for another,
# and the console reads as a dead board.
# ---------------------------------------------------------------------------
REPO_ROOT    := $(shell cd ../.. && pwd)
PROJECT_ROOT  = ../..

CFG          ?= colorlightDbI9PlusSdram
# The probe and console are the unit's: fpga/unit.mk asks setup/units.toml.

include ../vivado.mk

PART     := xc7a50tfgg484-1
TOP      := JopSdramI9PlusTop
SUMMARY   = $(CFG_DIR)/rtl/$(TOP).summary.txt
GEN_XDC   = $(VIVADO_PRJ)/colorlight_db_i9plus_jop_sdram.xdc
BIT_FILE  = $(VIVADO_BUILD)/$(TOP).bit
.PHONY: all build program
all: build

# The XDC is generated from the same config as the RTL, so it rides on the
# same stamp. ABSOLUTE path: the recipe cd's to the repo root and the main runs
# FORKED, so a board-relative path (CFG_DIR is ../../build/...) lands two
# directories above the repo -- it did, once, and Vivado read the stale file.
$(GEN_XDC): $(GEN_STAMP)
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(CFG) --write $(abspath $(GEN_XDC))"

# The PLL has the outputs the design uses and no others: the system clock,
# the SDRAM's copy 3.0 ns early, and for CFG=colorlightDbI9PlusEth the 50 MHz
# RGMII TX clock (CFG=colorlightDbI9PlusEth1000: 125 MHz, and IDELAYCTRL's 200) (status item 164: an unused 125 MHz output once forced
# 80 MHz onto a VCO that split the system and SDRAM clocks). Its definition
# refuses an IP whose outputs miss the design, so a split stops the build.
# (The hand-made IPs' MMCM is kept: an MMCM's phase steps are VCO/8, and the
# old `PRIMITIVE=PLL` lever moved the frequency to reach a lead, 80.357 MHz
# for -3.0 ns at 80.)

# The Wukong's closure directives (its Makefile, TIMING_DIRECTIVES): what holds
# that board's same core at 100 MHz. `make CLOSURE=1` to use them; the 50 MHz
# build needs none.
ifeq ($(CLOSURE),1)
TIMING_DIRECTIVES := JOP_SYNTH_DIRECTIVE=PerformanceOptimized JOP_SYNTH_RETIMING=yes \
                    JOP_OPT_DIRECTIVE=Explore JOP_PLACE_DIRECTIVE=ExtraTimingOpt \
                    JOP_PHYS_OPT_DIRECTIVE=AggressiveExplore JOP_ROUTE_DIRECTIVE=AggressiveExplore
endif

build: $(GEN_STAMP) $(GEN_XDC)
	$(VIVADO_ENV) && JOP_TOP=$(TOP) JOP_PART=$(PART) $(TIMING_DIRECTIVES) \
	  JOP_BUILD_DIR=$(VIVADO_BUILD) \
	  JOP_RTL=$(CFG_DIR)/rtl/$(TOP).v \
	  JOP_BIN_GLOB='$(CFG_DIR)/rtl/$(TOP).v_*.bin' \
	  JOP_PLL_TCL="$(wildcard $(CFG_DIR)/ip/*.tcl)" \
	  JOP_XDC="$(GEN_XDC) $(CURDIR)/constraints/sdr_cdc.xdc" \
	  JOP_SUMMARY=$(SUMMARY) \
	  $(VIVADO) -mode batch -source $(NP_TCL)

program:
	$(MAKE) program-bit BIT_FILE=$(BIT_FILE)
