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
else ifeq ($(STAGE),sdram)
  TOP      := JopSdramI5Top
  PRESET   := colorlightI5Sdram
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

# GENERATED, not hand-written. This was the last file in the i5 flow that was
# maintained by hand, and its own header admitted the problem: "Mirrored in
# Board.ColorlightI5 ... keep the two in step" -- a promise no build checked.
# jop.generate.LpfGenerator renders it from the same board data the .qsf and
# .xdc generators use, and TimingConstraints supplies the timing third.
LPF        := $(CFG_DIR)/nextpnr/$(TOP).lpf

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
# endpoint; -if00 is the HID debug endpoint openFPGALoader uses. The alias is
# the UNIT's console (fpga/unit.mk, setup/units.toml; status item 173), and no
# unit has this assembly until a module is fitted on the ext board -- so today
# download refuses rather than guessing.
PROJECT_ROOT := $(ROOT)
CFG        := $(PRESET)
CFG_NAME   := $(PRESET)
include ../unit.mk
include ../ecp5.mk
# The first rule make reads is its default goal, included files and all:
# without this a bare `make` runs ../unit.mk's unit-info (before 2026-11-01,
# this file's own $(APP) rule) and builds no bitstream.
.DEFAULT_GOAL := all
TTY         = $(shell $(ROOT)/fpga/scripts/usb_serial_map --by-id $(CONSOLE_ALIAS) 2>/dev/null)
BAUD       := 1000000
# THE APP COMES FROM THIS CONFIG'S OWN JAVA TREE, like every other board.
# It used to read $(ROOT)/java/apps/... -- the legacy in-tree path -- which is
# why this was the ONLY board that still worked while the .jop prerequisite was
# broken everywhere else (2026-08-30): it was reading an artefact the README's
# software section happens to leave behind, not one this flow produced.
APP        := $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop

# Built here rather than assumed, so `make bitstream download` works from cold.
# ASK EVERY TIME -- the same reasoning as config.mk's `tools-fresh`.
#
# This was a file target with NO prerequisites: once the .jop existed, make
# never re-entered java/ again, so a JOPizer change, a runtime change or a new
# Const.java was compiled into nothing and the bitstream shipped the PREVIOUS
# image. That is status item 140's defect one level out, and it is worse here
# because the stale image is baked into a bitstream rather than merely linked.
#
# FORCE rather than a prerequisite list: the inputs are the whole Java tree and
# the generated Const.java, and enumerating them here would be a second copy of
# java/'s own dependency graph. The sub-make is incremental, so the cost when
# nothing changed is one sbt start, against a board build measured in minutes.
$(APP): FORCE
	cd $(ROOT)/java && $(MAKE) all JOP_PRESET=$(PRESET) BUILDTREE=1

.PHONY: all generate synth pnr bitstream program download clean

all: bitstream

# --- 1. SpinalHDL -> Verilog -----------------------------------------------
# Also emits the microcode ROM/RAM and JBC .bin files that the Verilog loads
# with $readmemb. ECP5 uses MemoryStyle.Generic, so there are no vendor memory
# blackboxes to supply -- unlike the Altera boards, which need arom.vhd/aram.vhd
# listed in their .qsf or synthesis fails on undefined entities.
generate: $(APP)
	cd $(ROOT) && sbt "runMain jop.system.JopTopVerilog $(PRESET) buildtree"
	cd $(ROOT) && sbt "runMain jop.generate.LpfGeneratorMain $(PRESET) --write build/$(PRESET)/nextpnr/$(TOP).lpf"
	cd $(ROOT) && sbt "runMain jop.generate.PllGenMain $(PRESET) --module pll_jop_i5 --out build/$(PRESET)/ip"

# The .v MUST depend on the Scala sources and the microcode. Without this the
# generated Verilog silently goes stale whenever the preset changes, and the
# failure is nasty: changing clkFreq from 50 to 40 MHz reprograms the PLL (which
# lives in this directory) but leaves the old UART baud divider baked into the
# .v, so the board comes up transmitting at 40e6/86/5 = 93023 baud while the
# host listens at 115200 and sees plausible-looking garbage rather than silence.
# SCALA_SRC: every sbt project's sources.
include ../scala.mk
UCODE     := $(wildcard $(ROOT)/build/microcode/serial/mem_*.dat)

$(GENDIR)/$(TOP).v: $(SCALA_SRC) $(UCODE) $(UCODE_SCALA)
	$(MAKE) generate

# --- 2. Synthesis -----------------------------------------------------------
# yosys must run with $(GENDIR) as its working directory: the $readmemb paths in
# the generated Verilog are bare filenames, resolved relative to the cwd. Hence
# the absolute output paths. With the block RAM's clock-to-out: ../ecp5.mk,
# status item 175. The log is no longer piped through tee, whose exit status
# hid a failed synthesis and let the previous netlist be placed.
$(BUILD)/$(TOP).json: $(GENDIR)/$(TOP).v $(ECP5_DP16KD)
	mkdir -p $(BUILD)
	cd $(GENDIR) && $(call ecp5_synth,$(TOP),$(TOP).v $(abspath $(CFG_DIR))/ip/pll_jop_i5.v,$(abspath $(BUILD))/$(TOP).json,$(abspath $(BUILD))/yosys.log)

synth: $(BUILD)/$(TOP).json

# --- 3. Place and route -----------------------------------------------------
$(BUILD)/$(TOP).config: $(BUILD)/$(TOP).json
	nextpnr-ecp5 $(DEVICE) --package $(PACKAGE) --speed $(SPEED) \
	    --json $(BUILD)/$(TOP).json \
	    --lpf $(LPF) \
	    --seed $(SEED) \
	    --textcfg $(BUILD)/$(TOP).config \
	    > $(BUILD)/nextpnr.log 2>&1 || { tail -20 $(BUILD)/nextpnr.log; exit 1; }

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
download: $(UNIT_DESC)
	@[ -n "$(CONSOLE_ALIAS)" ] || { echo "no unit has a console for $(PRESET) (setup/units.toml): no module is fitted on the ext board" >&2; exit 1; }
	@[ -n "$(TTY)" ] || { echo "console '$(CONSOLE_ALIAS)' is not attached (fpga/scripts/usb_serial_map --by-id $(CONSOLE_ALIAS))" >&2; exit 1; }
	python3 $(ROOT)/fpga/scripts/download.py -e $(APP) $(TTY) $(BAUD)

# Removes the whole configuration -- RTL, place-and-route, bitstream. Before the
# build-tree move this could only reach $(BUILD), leaving stale Verilog behind.
clean:
	rm -rf $(CFG_DIR)

# Always-out-of-date prerequisite; see the note above.
FORCE:
