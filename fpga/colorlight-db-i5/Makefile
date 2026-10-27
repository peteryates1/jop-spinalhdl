# ---------------------------------------------------------------------------
# JOP on the fpga-db-colorlight-i5 base board -- its SECOND unit -- with a
# Colorlight i5 v7.0 (ECP5), from the module's 8 MB SDRAM. Open-source flow:
# yosys -> nextpnr-ecp5 -> ecppack -> openFPGALoader.
#
#   make               generate RTL + LPF + PLL, then synthesise, place, pack
#   make program       --assert-device, then load the .bit into SRAM through
#                      the unit's RP2040-STAMP (dirtyJtag); volatile
#   make download      send HelloWorld.jop over the stamp's CDC0 (console.mk)
#   make monitor | reset | redownload
#
# With Ethernet (RJ1, RGMII at 100 Mb/s through jop.io.Rgmii100 on ECP5
# output cells): CFG=colorlightDbI5Eth on each of the above.
#
# This is ../colorlight-i5 (the i5 on its ext board) with the base board's
# stamp in place of the DAPLink: the same generators, toolchain and PLL. The
# preset carries the rest -- the console and parking on SODIMM pins, the reset
# button, the PHY. The module's flash holds ../bringup/colorlight-i5-idle; a
# power cycle returns to it.
# ---------------------------------------------------------------------------
CFG           ?= colorlightDbI5Sdram
BOARD_ALIAS    = colorlight-db-i5
CONSOLE_ALIAS  = colorlight-db-i5-0
TOP           := JopSdramI5Top

ROOT          := ../..
PROJECT_ROOT   = ../..
# Assigned before any rule uses them: Make expands a rule's prerequisites when
# it reads the rule (see ../colorlight-i5/Makefile).
CFG_DIR       := $(ROOT)/build/$(CFG)
GENDIR        := $(CFG_DIR)/rtl
BUILD         := $(CFG_DIR)/nextpnr
LPF           := $(BUILD)/$(TOP).lpf
BIT           := $(BUILD)/$(TOP).bit
SCRIPTS       := $(ROOT)/fpga/scripts

DEVICE        := --25k
PACKAGE       := CABGA381
SPEED         := 6
SEED          := 1

# The first rule below is $(APP)'s, and the first rule is make's default goal:
# without this a bare `make` builds only the Java app.
.DEFAULT_GOAL := all

# Built here, not assumed -- ASK EVERY TIME, as in ../colorlight-i5/Makefile.
APP           := $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop
$(APP): FORCE
	cd $(ROOT)/java && $(MAKE) all JOP_PRESET=$(CFG) BUILDTREE=1

.PHONY: all generate synth pnr bitstream program clean FORCE

all: bitstream

# --- 1. SpinalHDL -> Verilog, the LPF and the PLL, all from the preset -------
generate: $(APP)
	cd $(ROOT) && sbt "runMain jop.system.JopTopVerilog $(CFG) buildtree"
	cd $(ROOT) && sbt "runMain jop.generate.LpfGeneratorMain $(CFG) --write build/$(CFG)/nextpnr/$(TOP).lpf"
	cd $(ROOT) && sbt "runMain jop.generate.PllGenMain $(CFG) --module pll_jop_i5 --out build/$(CFG)/ip"

SCALA_SRC := $(shell find $(ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE     := $(wildcard $(ROOT)/build/microcode/serial/mem_*.dat)
$(GENDIR)/$(TOP).v: $(SCALA_SRC) $(UCODE)
	$(MAKE) generate

# --- 2. Synthesis: in $(GENDIR), where the $readmemb files are --------------
$(BUILD)/$(TOP).json: $(GENDIR)/$(TOP).v
	mkdir -p $(BUILD)
	cd $(GENDIR) && yosys -p "read_verilog $(TOP).v $(abspath $(CFG_DIR))/ip/pll_jop_i5.v; \
	    synth_ecp5 -top $(TOP) -json $(abspath $(BUILD))/$(TOP).json" \
	    > $(abspath $(BUILD))/yosys.log 2>&1 || { tail -20 $(abspath $(BUILD))/yosys.log; exit 1; }

synth: $(BUILD)/$(TOP).json

# --- 3. Place and route ------------------------------------------------------
$(BUILD)/$(TOP).config: $(BUILD)/$(TOP).json
	nextpnr-ecp5 $(DEVICE) --package $(PACKAGE) --speed $(SPEED) \
	    --json $< --lpf $(LPF) --seed $(SEED) --textcfg $@ \
	    > $(BUILD)/nextpnr.log 2>&1 || { tail -20 $(BUILD)/nextpnr.log; exit 1; }

pnr: $(BUILD)/$(TOP).config

# --- 4. Bitstream -------------------------------------------------------------
$(BIT): $(BUILD)/$(TOP).config
	ecppack --compress $< $@

# Post-route figures only: nextpnr prints an estimate after placement as well.
bitstream: $(BIT)
	@echo "--- timing (post-route) ---"
	@sed -n '/Routing complete/,$$p' $(BUILD)/nextpnr.log | grep -E 'Max frequency'
	@echo "--- utilisation ---"
	@grep -E "(TRELLIS_COMB|TRELLIS_FF|DP16KD|MULT18X18D|EHXPLLL):" $(BUILD)/nextpnr.log | tail -6

# --- 5. Program (SRAM) through the stamp, selected by SERIAL ------------------
# --assert-device first: it proves the probe reaches an ECP5 (41111043) before
# anything is sent. The patched openFPGALoader in /usr/local/bin honours
# --busdev-num for dirtyJtag; two stamps are attached to this host.
program: $(BIT)
	$(SCRIPTS)/jtag_probe_map --assert-device $(BOARD_ALIAS)
	sudo openFPGALoader -c dirtyJtag --busdev-num $$($(SCRIPTS)/jtag_probe_map --busdev $(BOARD_ALIAS)) $(BIT)
	@echo "=== programmed: $(BIT) ==="

# Guarded: `make clean CFG=` would otherwise remove the whole build tree.
clean:
	@test -n "$(strip $(CFG))" || { echo "CFG is empty -- refusing to remove $(CFG_DIR)"; exit 1; }
	rm -rf $(CFG_DIR)

# download / redownload / reset / monitor: the console by alias, the baud from
# the build's own summary.
include ../console.mk

FORCE:
