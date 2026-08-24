# ---------------------------------------------------------------------------
# JOP on Colorlight i5 v7.0 -- open-source ECP5 flow
#
# yosys -> nextpnr-ecp5 -> ecppack -> openFPGALoader
#
# This is the project's first non-vendor toolchain: every other board here goes
# through Quartus or Vivado. Nothing is shared with those flows.
#
#   make            build the bitstream
#   make program    load it into SRAM (volatile, gone at power-off)
#   make download   send a .jop over the DAPLink serial port
#   make clean
# ---------------------------------------------------------------------------

# Stage selector. Stage 1 is BRAM-only (64 KB, proves the board and toolchain);
# stage 2 adds the 8 MB SDRAM and is what can host a real heap.
#   make              build stage 2
#   make STAGE=bram   build stage 1
STAGE      ?= sdram

ifeq ($(STAGE),bram)
  TOP      := JopBramSerialI5Top
  PRESET   := colorlightI5Bram
  LPF      := jop_i5.lpf
else ifeq ($(STAGE),sdram)
  TOP      := JopSdramI5Top
  PRESET   := colorlightI5Sdram
  LPF      := jop_i5_sdram.lpf
else
  $(error STAGE must be 'bram' or 'sdram', got '$(STAGE)')
endif

ROOT       := ../..
# Everything generated goes under build/<config>/, keyed by the PRESET rather
# than by the stage: see jop.generate.BuildLayout. Stronger than the old
# build/$(STAGE) split, which kept the two bitstreams apart but would still let
# two different configurations of the same stage overwrite each other.
#
# CFG_DIR must be assigned before GENDIR and BUILD are used in any rule: Make
# expands a rule's prerequisites when it READS the rule, so a later assignment
# would silently yield paths like "/rtl/JopSdramI5Top.v".
CFG_DIR    := $(ROOT)/build/$(PRESET)
GENDIR     := $(CFG_DIR)/rtl
BUILD      := $(CFG_DIR)/nextpnr

DEVICE     := --25k
PACKAGE    := CABGA381
SPEED      := 6

# nextpnr placer seed. This design is seed-sensitive -- at a 50 MHz target it
# routed anywhere from 47.6 to 50.6 MHz across seeds 1-5. The 40 MHz target has
# enough margin that the seed should not matter; keep the knob anyway, because
# the first thing to try after a timing failure is a different seed.
SEED       := 1

# The ext board's DAPLink presents as a CMSIS-DAP probe. openFPGALoader's
# "colorlight-i5" board profile selects the same thing plus the right freq.
LOADER_BRD := colorlight-i5

# DAPLink CDC serial. Addressed by serial number, not /dev/ttyACMn: the ACM
# numbers renumber across replugs and there are other CMSIS-DAP/CDC devices on
# this host (see fpga/scripts/usb_serial_map). Interface -if01 is the CDC data
# endpoint; -if00 is the HID debug endpoint openFPGALoader uses.
TTY        := $(shell $(ROOT)/fpga/scripts/usb_serial_map --by-id i5 2>/dev/null \
                || echo /dev/serial/by-id/usb-ARM_DAPLink_CMSIS-DAP_07000001002700424e00000256304750a5a5a5a597969908-if01)
BAUD       := 1000000
APP        := $(ROOT)/java/apps/Smallest/HelloWorld.jop

.PHONY: all generate synth pnr bitstream program download clean

all: bitstream

# --- 1. SpinalHDL -> Verilog -----------------------------------------------
# Also emits the microcode ROM/RAM and JBC .bin files that the Verilog loads
# with $readmemb. ECP5 uses MemoryStyle.Generic, so there are no vendor memory
# blackboxes to supply -- unlike the Altera boards, which need arom.vhd/aram.vhd
# listed in their .qsf or synthesis fails on undefined entities.
generate:
	cd $(ROOT) && sbt "runMain jop.system.JopTopVerilog $(PRESET) buildtree"

# The .v MUST depend on the Scala sources and the microcode. Without this the
# generated Verilog silently goes stale whenever the preset changes, and the
# failure is nasty: changing clkFreq from 50 to 40 MHz reprograms the PLL (which
# lives in this directory) but leaves the old UART baud divider baked into the
# .v, so the board comes up transmitting at 40e6/86/5 = 93023 baud while the
# host listens at 115200 and sees plausible-looking garbage rather than silence.
SCALA_SRC := $(shell find $(ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE     := $(wildcard $(ROOT)/build/microcode/serial/mem_*.dat)

$(GENDIR)/$(TOP).v: $(SCALA_SRC) $(UCODE)
	$(MAKE) generate

# --- 2. Synthesis -----------------------------------------------------------
# yosys must run with $(GENDIR) as its working directory: the $readmemb paths in
# the generated Verilog are bare filenames, resolved relative to the cwd. Hence
# the absolute output path.
$(BUILD)/$(TOP).json: $(GENDIR)/$(TOP).v pll_jop_i5.v
	mkdir -p $(BUILD)
	cd $(GENDIR) && yosys -p "read_verilog $(TOP).v $(abspath pll_jop_i5.v); \
	    synth_ecp5 -top $(TOP) -json $(abspath $(BUILD))/$(TOP).json" \
	    2>&1 | tee $(abspath $(BUILD))/yosys.log

synth: $(BUILD)/$(TOP).json

# --- 3. Place and route -----------------------------------------------------
$(BUILD)/$(TOP).config: $(BUILD)/$(TOP).json $(LPF)
	nextpnr-ecp5 $(DEVICE) --package $(PACKAGE) --speed $(SPEED) \
	    --json $(BUILD)/$(TOP).json \
	    --lpf $(LPF) \
	    --seed $(SEED) \
	    --textcfg $(BUILD)/$(TOP).config \
	    2>&1 | tee $(BUILD)/nextpnr.log

pnr: $(BUILD)/$(TOP).config

# --- 4. Bitstream -----------------------------------------------------------
$(BUILD)/$(TOP).bit: $(BUILD)/$(TOP).config
	ecppack --compress $(BUILD)/$(TOP).config $(BUILD)/$(TOP).bit

# nextpnr indents the utilisation table with a tab, so match anywhere on the
# line rather than anchoring after "Info:".
bitstream: $(BUILD)/$(TOP).bit
	@echo "--- timing ---"
	@grep -E "Max frequency" $(BUILD)/nextpnr.log | tail -1
	@echo "--- utilisation ---"
	@grep -E "(TRELLIS_COMB|TRELLIS_FF|DP16KD|MULT18X18D|EHXPLLL):" $(BUILD)/nextpnr.log | tail -6

# --- 5. Program (SRAM) ------------------------------------------------------
# SRAM only. The i5's GD25Q16 SPI flash ships write-locked, so flash boot needs
# the unlock procedure from kazkojima/colorlight-i5-tips#spiflash first.
program: $(BUILD)/$(TOP).bit
	openFPGALoader -b $(LOADER_BRD) $(BUILD)/$(TOP).bit

# --- 6. Download a .jop over the DAPLink serial port ------------------------
# The FPGA must be reprogrammed before each download -- the serial bootloader
# only listens once, right after configuration.
download:
	python3 $(ROOT)/fpga/scripts/download.py -e $(APP) $(TTY) $(BAUD)

# Removes the whole configuration -- RTL, place-and-route, bitstream. Before the
# build-tree move this could only reach $(BUILD), leaving stale Verilog behind.
clean:
	rm -rf $(CFG_DIR)
