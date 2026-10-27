# Colorlight i5 on the fpga-db-colorlight-i5 base board: Ethernet bring-up --
# see README.md. Self-contained on purpose (see ../README.md): no JOP config,
# no generators, nothing shared with JOP's flow.
#
#   make                                build both bitstreams
#   make sram  CABLE="<args>"           stage 1 (MDIO probe): load, then check
#   make check CABLE="<args>"           read-only: USERCODE 0xE7D10005 and DONE
#   make rgmii-sram  CABLE="<args>"     stage 2 (RGMII test): load, then check
#   make rgmii-check CABLE="<args>"     read-only: USERCODE 0xE7D20005 and DONE
#   make sim                            stage 2 Verilator testbench (ALL PASS)
#
# CABLE is the openFPGALoader cable arguments for the board's stamp, e.g.
# CABLE="-c dirtyJtag --busdev-num 1:41". There is no flash target on purpose:
# the module's boot image stays ../colorlight-i5-idle, and a power cycle
# returns to it.

PROBE := EthMdioProbe
RGMII := EthRgmiiTest
BUILD := build
# ecppack rejects hex, hence the printf. E7D1 = "ETh mDIo", E7D2 = stage 2; 0005 = i5.
USERCODE_PROBE := 0xE7D10005
USERCODE_RGMII := 0xE7D20005
CABLE ?=

.PHONY: all check sram rgmii-check rgmii-sram sim need-cable

all: $(BUILD)/$(PROBE).bit $(BUILD)/$(RGMII).bit

YOSYS = mkdir -p $(BUILD) && yosys -q -p "read_verilog $(1); synth_ecp5 -top $(2) -json $@" \
	    > $(BUILD)/$(2).yosys.log 2>&1 || { tail -20 $(BUILD)/$(2).yosys.log; exit 1; }

$(BUILD)/$(PROBE).json: $(PROBE).v
	$(call YOSYS,$(PROBE).v,$(PROBE))

# frame.hex is read by $readmemh, relative to this directory
$(BUILD)/$(RGMII).json: $(RGMII).v Pll90.v frame.hex
	$(call YOSYS,$(RGMII).v Pll90.v,$(RGMII))

# nextpnr reports Max frequency after placement (an estimate) and again after
# routing; print the post-route lines, one per clock with internal paths.
$(BUILD)/%.config: $(BUILD)/%.json %.lpf
	nextpnr-ecp5 --25k --package CABGA381 --speed 6 --seed 1 \
	    --json $< --lpf $*.lpf --textcfg $@ \
	    > $(BUILD)/$*.nextpnr.log 2>&1 || { tail -20 $(BUILD)/$*.nextpnr.log; exit 1; }
	@sed -n '/Routing complete/,$$p' $(BUILD)/$*.nextpnr.log | grep 'Max frequency'

$(BUILD)/$(PROBE).bit: $(BUILD)/$(PROBE).config
	ecppack --compress --usercode $$(printf '%d' $(USERCODE_PROBE)) $< $@

$(BUILD)/$(RGMII).bit: $(BUILD)/$(RGMII).config
	ecppack --compress --usercode $$(printf '%d' $(USERCODE_RGMII)) $< $@

need-cable:
	@test -n "$(CABLE)" || { echo 'set CABLE, e.g. CABLE="-c dirtyJtag --busdev-num 1:41"'; exit 1; }

# openFPGALoader plays the SVF and exits non-zero on any TDO mismatch.
check: need-cable
	openFPGALoader $(CABLE) check_probe.svf
	@echo "MDIO PROBE RUNNING (USERCODE $(USERCODE_PROBE), DONE set)"

sram: need-cable $(BUILD)/$(PROBE).bit
	openFPGALoader $(CABLE) $(BUILD)/$(PROBE).bit
	$(MAKE) --no-print-directory check CABLE="$(CABLE)"

rgmii-check: need-cable
	openFPGALoader $(CABLE) check_rgmii.svf
	@echo "RGMII TEST RUNNING (USERCODE $(USERCODE_RGMII), DONE set)"

rgmii-sram: need-cable $(BUILD)/$(RGMII).bit
	openFPGALoader $(CABLE) $(BUILD)/$(RGMII).bit
	$(MAKE) --no-print-directory rgmii-check CABLE="$(CABLE)"

sim:
	bash sim/run.sh
