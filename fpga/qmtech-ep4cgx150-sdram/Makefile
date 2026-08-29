# JOP SDRAM — QMTECH EP4CGX150 Core Board (+ DB_FPGA daughter board)
#
# CONVERTED 2026-08-26, from 701 lines to this. The Quartus flow, the console
# and the microcode come from ../quartus.mk; what remains below is what is
# genuinely specific to this board.
#
# WHAT WENT. Three groups, and only the first was ordinary duplication:
#
#  1. Duplicated flow -- its own copy of the CFG_NAME resolution, the generate
#     stamp, the .sdc/.tcl/.qsf rules, the four quartus_* commands, and its own
#     download/reset/redownload/monitor with a hardcoded BAUD_RATE=2000000
#     rather than the rate the build recorded (status item 70).
#
#  2. DEAD flows. `generate-smp8` ran `jop.system.JopSmp8TestVerilog` and
#     `generate-flash-boot` ran `jop.system.JopCfgFlashTopVerilog`; both mains
#     were deleted in commit 7258661 on 2026-03-13. Five and a half months of
#     build/program/full targets that could not run, plus flash-image,
#     flash-jic and program-flash hanging off the flash-boot .sof. Retired,
#     with jop_smp8_test.qsf and jop_flash_boot.qsf. `microcode-flash` invoked
#     `make flash` in asm/, where the target is `flash-altera`. Also dead.
#
#  3. NINE .cdf generators, eleven near-identical lines each. `quartus_pgm`
#     takes an explicit operation, so the .sof goes to it directly and the
#     whole class is gone. This was not cosmetic: `program-smp` programmed
#     $(OUTPUT_DIR)/jop_smp_sdram.sof -- the pre-build-tree in-tree path, whose
#     newest copy here was from 23 August -- while the current bitstream sat in
#     build/<config>/. The comment on `program` recorded that exact bug being
#     fixed for `program`; the SMP twin next to it was left as it was.

PROJECT_ROOT  = ../..
CFG          ?= ep4cgx150Serial
REV          ?= jop_sdram
BOARD_ALIAS   = ep4cgx150
CONSOLE_ALIAS = ep4cgx150

include ../quartus.mk

.PHONY: all full program assert-device \
        mc-fallback mc-fallback-program generate-mc-fallback \
        smp smp-program smp-verify program-smp \
        dbfpga dbfpga-vgadma dbfpga-program generate-dbfpga generate-dbfpga-vgadma \
        sd-native sd-spi program-sd-native program-sd-spi \
        config-flash-exerciser flash-programmer uart-test clean

all:  microcode generate build
full: microcode generate build program download monitor

# --------------------------------------------------------------------------
# Programming
# --------------------------------------------------------------------------

# This board now has its OWN cable -- a level-shifted pico-usb-blaster (serial
# e6616408), proven on hardware 2026-08-29 -- so it no longer shares the Terasic
# with the A-E115FB. The IDCODE check stayed anyway: it lives in quartus.mk now,
# `program-sof` depends on it, and it is what catches a powered-off board, since
# quartus_pgm reports success on a chain it never read.
program: program-sof

# --------------------------------------------------------------------------
# Other configurations of this same hardware project
#
# Each re-enters the parameterised rules with a different CFG instead of
# carrying its own copy of them. BuildLayout keys on the invocation, so each
# lands in its own build/<config>/ and none can overwrite another's PLL.
# --------------------------------------------------------------------------

# Every bytecode with a working _sw handler, so the microcode paths get exercised
# on a board other than the Colorlight i5 — see ep4cgx150McFallback in
# JopConfig.scala and item 19 in docs/current-status.md.
mc-fallback:
	$(MAKE) generate build CFG=ep4cgx150McFallback REV=$(REV)

mc-fallback-program:
	$(MAKE) program CFG=ep4cgx150McFallback REV=$(REV)

#   make smp                 2 cores (the default)
#   make smp CORES=4
#   make smp CORES=12 MHZ=36
CORES ?= 2
MHZ   ?=
SMP_CFG = ep4cgx150Smp $(CORES) $(MHZ)

smp:
	$(MAKE) generate build CFG="$(strip $(SMP_CFG))" REV=jop_smp_sdram

smp-program:
	$(MAKE) program CFG="$(strip $(SMP_CFG))" REV=jop_smp_sdram

smp-verify:
	$(PROJECT_ROOT)/fpga/scripts/hw_verify.py $(strip $(SMP_CFG)) \
	    --app SmpGcTest/SmpGcTest --expect-text "SMPGC OK"

# Both DB_FPGA configs share REV because the PINS are identical -- VGA is VGA
# whether driven by the text controller or the DMA one -- but they are different
# CONFIGS, so they get separate build directories.
#
#   make dbfpga            Ethernet + VGA text + SD
#   make dbfpga-vgadma     Ethernet + VGA DMA  + SD
dbfpga:
	$(MAKE) generate build CFG=ep4cgx150DbFull REV=jop_dbfpga

dbfpga-vgadma:
	$(MAKE) generate build CFG=ep4cgx150DbVgaDma REV=jop_dbfpga

dbfpga-program:
	$(MAKE) program CFG=ep4cgx150DbFull REV=jop_dbfpga

# Old names kept as aliases; they used to emit Verilog only, into the legacy tree.
generate-mc-fallback: mc-fallback
generate-dbfpga: dbfpga
generate-dbfpga-vgadma: dbfpga-vgadma
program-smp: smp-program
program-dbfpga: dbfpga-program

# --------------------------------------------------------------------------
# SD card exercisers
#
# Standalone BoardDesigns, not JopConfig presets, so they have their own
# generator mains rather than re-entering `generate`. Everything else --
# project, PLL, SDC, output location -- is generated into build/<name>/ the
# same way. The hand-written .qsf files they replaced were proven pin-identical
# first, then deleted.
# --------------------------------------------------------------------------

# One variable per exerciser, re-entering the SAME rules the JOP builds use.
# This replaced a `define build_exerciser` macro that carried its own copy of
# quartus_sh and the four quartus_* commands -- a sixth copy of the flow, in the
# file whose own header records deleting the other five. quartus.mk grew
# GEN_MAIN / GEN_MAKES_PROJECT instead; the cfgName and revision in each
# *Build main are exactly the CFG and REV passed here.
SD_NATIVE = CFG=sdNativeExerciser REV=sd_native_exerciser \
            GEN_MAIN=jop.system.SdNativeExerciserBuild GEN_ARGS= GEN_MAKES_PROJECT=yes
SD_SPI    = CFG=sdSpiExerciser    REV=sd_spi_exerciser \
            GEN_MAIN=jop.system.SdSpiExerciserBuild    GEN_ARGS= GEN_MAKES_PROJECT=yes

sd-native:
	$(MAKE) build $(SD_NATIVE)

sd-spi:
	$(MAKE) build $(SD_SPI)

program-sd-native: assert-device
	$(MAKE) program-sof $(SD_NATIVE)

program-sd-spi: assert-device
	$(MAKE) program-sof $(SD_SPI)

# Old names, kept because the docs use them.
generate-sd-native-exerciser: sd-native
generate-sd-spi-exerciser: sd-spi
full-sd-native-exerciser: sd-native program-sd-native monitor
full-sd-spi-exerciser: sd-spi program-sd-spi monitor

# --------------------------------------------------------------------------
# NOT CONVERTED — still build in-tree from hand-written .qsf files
#
# Both are blocked on the same thing: the config-flash pads have no device
# ALL THREE ARE CONVERTED NOW, and the in-tree dram_pll.vhd and
# sd_exerciser.sdc went with them -- the PLL comes from DramPllGen and the .sdc
# from TimingConstraints, both into build/<config>/, as for every other flow.
# --------------------------------------------------------------------------

# CONVERTED 2026-08-29. Both now go through quartus.mk like every other flow:
# the Build object emits RTL, PLL, .sdc and setup_proj.tcl into
# build/<config>/, and the hand-written .qsf files are gone. Their generated
# pins were diffed against those .qsf files first and matched exactly, all
# eight each.
config-flash-exerciser:
	$(MAKE) generate build CFG=configFlashExerciser REV=config_flash_exerciser \
	  GEN_MAIN=jop.system.ConfigFlashExerciserBuild GEN_ARGS= GEN_MAKES_PROJECT=yes

flash-programmer:
	$(MAKE) generate build CFG=flashProgrammer REV=flash_programmer \
	  GEN_MAIN=jop.system.FlashProgrammerBuild GEN_ARGS= GEN_MAKES_PROJECT=yes

# The smallest thing on this board that proves a console end to end. It had a
# uart_test.qsf and no target at all, so neither the project nor the RTL could
# be built by anything.
uart-test:
	$(MAKE) generate build CFG=uartTest REV=uart_test \
	  GEN_MAIN=jop.system.UartTestBuild GEN_ARGS= GEN_MAKES_PROJECT=yes

# `clean` removes the in-tree leftovers only. The generated tree is
# quartus.mk's `quartus-clean`, which removes build/<config>/ for the CFG in
# force -- one configuration, not all of them.
clean:
	rm -rf output_files db incremental_db
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
