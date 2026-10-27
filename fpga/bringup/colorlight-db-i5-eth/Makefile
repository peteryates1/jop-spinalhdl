# Colorlight i5 on the fpga-db-colorlight-i5 base board: Ethernet stage 1, an
# MDIO probe -- see README.md. Self-contained on purpose (see ../README.md): no
# JOP config, no generators, nothing shared with JOP's flow.
#
#   make                          build/EthMdioProbe.bit
#   make sram  CABLE="<args>"     load into the FPGA (volatile), then check
#   make check CABLE="<args>"     read-only: USERCODE 0xE7D10005 and DONE set
#
# CABLE is the openFPGALoader cable arguments for the board's stamp, e.g.
# CABLE="-c dirtyJtag --busdev-num 1:41". There is no flash target on purpose:
# the module's boot image stays ../colorlight-i5-idle, and a power cycle
# returns to it.

TOP      := EthMdioProbe
BUILD    := build
# 0xE7D10005 = "ETh mDIo", i5. ecppack rejects hex, hence the printf.
USERCODE := 0xE7D10005
CABLE    ?=

.PHONY: all check sram need-cable

all: $(BUILD)/$(TOP).bit

$(BUILD)/$(TOP).json: $(TOP).v
	mkdir -p $(BUILD)
	yosys -q -p "read_verilog $(TOP).v; synth_ecp5 -top $(TOP) -json $@" \
	    > $(BUILD)/yosys.log 2>&1 || { tail -20 $(BUILD)/yosys.log; exit 1; }

$(BUILD)/$(TOP).config: $(BUILD)/$(TOP).json $(TOP).lpf
	nextpnr-ecp5 --25k --package CABGA381 --speed 6 --seed 1 \
	    --json $< --lpf $(TOP).lpf --textcfg $@ \
	    > $(BUILD)/nextpnr.log 2>&1 || { tail -20 $(BUILD)/nextpnr.log; exit 1; }
	@grep -E 'Max frequency' $(BUILD)/nextpnr.log | tail -1

$(BUILD)/$(TOP).bit: $(BUILD)/$(TOP).config
	ecppack --compress --usercode $$(printf '%d' $(USERCODE)) $< $@

need-cable:
	@test -n "$(CABLE)" || { echo 'set CABLE, e.g. CABLE="-c dirtyJtag --busdev-num 1:41"'; exit 1; }

# openFPGALoader plays the SVF and exits non-zero on any TDO mismatch.
check: need-cable
	openFPGALoader $(CABLE) check_probe.svf
	@echo "MDIO PROBE RUNNING (USERCODE $(USERCODE), DONE set)"

sram: need-cable $(BUILD)/$(TOP).bit
	openFPGALoader $(CABLE) $(BUILD)/$(TOP).bit
	$(MAKE) --no-print-directory check CABLE="$(CABLE)"
