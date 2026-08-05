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

TOP        := JopBramSerialI5Top
PRESET     := colorlightI5Bram

ROOT       := ../..
GENDIR     := $(ROOT)/spinalhdl/generated
BUILD      := build

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
TTY        := /dev/serial/by-id/usb-ARM_DAPLink_CMSIS-DAP_07000001002700424e00000256304750a5a5a5a597969908-if01
BAUD       := 115200
APP        := $(ROOT)/java/apps/Smallest/HelloWorld.jop

.PHONY: all generate synth pnr bitstream program download clean

all: bitstream

# --- 1. SpinalHDL -> Verilog -----------------------------------------------
# Also emits the microcode ROM/RAM and JBC .bin files that the Verilog loads
# with $readmemb. ECP5 uses MemoryStyle.Generic, so there are no vendor memory
# blackboxes to supply -- unlike the Altera boards, which need arom.vhd/aram.vhd
# listed in their .qsf or synthesis fails on undefined entities.
generate:
	cd $(ROOT) && sbt "runMain jop.system.JopTopVerilog $(PRESET)"

# The .v MUST depend on the Scala sources and the microcode. Without this the
# generated Verilog silently goes stale whenever the preset changes, and the
# failure is nasty: changing clkFreq from 50 to 40 MHz reprograms the PLL (which
# lives in this directory) but leaves the old UART baud divider baked into the
# .v, so the board comes up transmitting at 40e6/86/5 = 93023 baud while the
# host listens at 115200 and sees plausible-looking garbage rather than silence.
SCALA_SRC := $(shell find $(ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE     := $(wildcard $(ROOT)/asm/generated/serial/mem_*.dat)

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
$(BUILD)/$(TOP).config: $(BUILD)/$(TOP).json jop_i5.lpf
	nextpnr-ecp5 $(DEVICE) --package $(PACKAGE) --speed $(SPEED) \
	    --json $(BUILD)/$(TOP).json \
	    --lpf jop_i5.lpf \
	    --seed $(SEED) \
	    --textcfg $(BUILD)/$(TOP).config \
	    2>&1 | tee $(BUILD)/nextpnr.log

pnr: $(BUILD)/$(TOP).config

# --- 4. Bitstream -----------------------------------------------------------
$(BUILD)/$(TOP).bit: $(BUILD)/$(TOP).config
	ecppack --compress $(BUILD)/$(TOP).config $(BUILD)/$(TOP).bit

bitstream: $(BUILD)/$(TOP).bit
	@echo "--- timing ---"
	@grep -E "Max frequency|Info: Max" $(BUILD)/nextpnr.log | tail -5
	@echo "--- utilisation ---"
	@grep -E "^Info: +(TRELLIS_COMB|TRELLIS_FF|DP16KD|MULT18X18D|EHXPLLL)" $(BUILD)/nextpnr.log | tail -10

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

clean:
	rm -rf $(BUILD)
