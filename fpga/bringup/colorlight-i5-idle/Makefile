# Colorlight i5 idle image -- see README.md. Self-contained on purpose (see
# ../README.md): no JOP config, no generators, nothing shared with JOP's flow.
#
#   make                          build/I5Idle.bit
#   make check CABLE="<args>"     read-only: USERCODE 0x1D1E0005 and DONE set
#   make sram  CABLE="<args>"     load into the FPGA (volatile), then check
#   make flash CABLE="<args>"     write the module's flash with --verify, then check
#
# CABLE is the openFPGALoader cable arguments for whatever reaches the module,
# e.g. CABLE="-c dirtyJtag --busdev-num 1:7" (the second base board's stamp)
# or CABLE="-b colorlight-i5" (the ext board's DAPLink).

TOP      := I5Idle
BUILD    := build
# 0x1D1E0005 = "IDLE", i5. ecppack rejects hex, hence the printf.
USERCODE := 0x1D1E0005
CABLE    ?=

.PHONY: all check sram flash need-cable

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
	@test -n "$(CABLE)" || { echo 'set CABLE, e.g. CABLE="-c dirtyJtag --busdev-num 1:7"'; exit 1; }

# openFPGALoader plays the SVF and exits non-zero on any TDO mismatch.
check: need-cable
	openFPGALoader $(CABLE) check_idle.svf
	@echo "IDLE IMAGE RUNNING (USERCODE $(USERCODE), DONE set)"

sram: need-cable $(BUILD)/$(TOP).bit
	openFPGALoader $(CABLE) $(BUILD)/$(TOP).bit
	$(MAKE) --no-print-directory check CABLE="$(CABLE)"

# The write clears SRAM first and REFRESHes from flash at the end, so the check
# that follows can only pass if the FPGA booted this image FROM FLASH.
flash: need-cable $(BUILD)/$(TOP).bit
	openFPGALoader $(CABLE) -f --verify $(BUILD)/$(TOP).bit
	$(MAKE) --no-print-directory check CABLE="$(CABLE)"
