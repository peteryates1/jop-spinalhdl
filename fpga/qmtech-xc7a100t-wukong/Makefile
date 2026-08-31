REPO_ROOT := $(shell cd ../.. && pwd)
PROJECT_ROOT = ../..

# The probe, the console and the config directory come from ../vivado.mk and
# ../console.mk. CFG is the flow being driven; each target below that needs a
# different one re-enters with it, so only the invoked flow resolves a name.
CFG          ?= wukongDdr3
BOARD_ALIAS   = wukong
CONSOLE_ALIAS = wukong

include ../vivado.mk
BITSTREAM := vivado/build/wukong_jop_bram/wukong_jop_bram.runs/impl_1/JopBramWukongTop.bit
SDRAM_BITSTREAM := vivado/build/wukong_sdram_exerciser/SdramExerciserWukongTop.bit
JOP_SDRAM_BITSTREAM = $(SDRAM_CFG_DIR)/vivado/build/JopSdramWukongTop.bit
DUAL_BITSTREAM = $(DUAL_CFG_DIR)/vivado/build/JopDualWukongTop.bit

# --------------------------------------------------------------------------
# The Vivado flow comes from fpga/scripts/, not from seven copies here.
#
# This board carried SIX non-project build scripts and one project-mode one --
# 400 lines running the same nine steps in the same order, differing in the
# top, the IP, the constraint list and the directives. Those are now arguments.
# See vivado_build_nonproject.tcl's header for what stayed a parameter (the
# impl directives, which are a real per-design choice) and what did not.
#
# create_ddr3_project.tcl went with them, and was worse than redundant: the
# DDR3 flow is NON-project, so `ddr3-build` never opened the project it made --
# and it read only wukong_ddr3.xdc, missing the GMII and base constraints the
# real build uses. Anyone opening it in the GUI saw a differently-constrained
# design than the one that ships.
# --------------------------------------------------------------------------
PART       := xc7a100tfgg676-2
CREATE_TCL := $(REPO_ROOT)/fpga/scripts/vivado_create_project.tcl
PRJ_TCL    := $(REPO_ROOT)/fpga/scripts/vivado_build_project.tcl
NP_TCL     := $(REPO_ROOT)/fpga/scripts/vivado_build_nonproject.tcl
# Generated IP under build/, board-scoped: this board's IP serves SEVEN
# configurations, so build/<config>/ would either duplicate it or arbitrarily
# elect one config to own it. The tracked mig.prj input stays at vivado/ip/.
IP         := $(REPO_ROOT)/build/ip/qmtech-xc7a100t-wukong
XDC        := $(CURDIR)/vivado/constraints
SHARED_XDC := $(REPO_ROOT)/fpga/constraints
# Standalone top: its own config directory, not a preset's.
SDR_EX_DIR := $(REPO_ROOT)/build/sdramExerciserWukong

# Every JOP build here uses the same timing-closure directives; the exercisers
# and the SDR JOP builds have margin and run the default flow.
TIMING_DIRECTIVES := JOP_SYNTH_DIRECTIVE=PerformanceOptimized JOP_SYNTH_RETIMING=yes \
                    JOP_OPT_DIRECTIVE=Explore JOP_PLACE_DIRECTIVE=ExtraTimingOpt \
                    JOP_PHYS_OPT_DIRECTIVE=AggressiveExplore JOP_ROUTE_DIRECTIVE=AggressiveExplore
# WHICH CONSOLE EACH FLOW USES.
#
# The SINGLE-SYSTEM builds come out of the board's own CH340N -- they use
# SystemAssembly.wukong with DeviceDriver.UartCh340. The DUAL-cluster builds do
# NOT: the CH340N at E3/F3 is hardwired on the PCB and cannot be tapped, so both
# halves come out of the on-board Pico 2 W instead (DDR3 -> PICO_UART1 F4/H4 ->
# the Pico's SECOND CDC; SDR -> J11_UART A5/A4 -> the first). See
# docs/architecture/system-configuration.md.
#
# All three are resolved by SERIAL through usb_serial_map. The CH340 has no
# serial number, which is why it needs its own alias -- see that script's
# header. There are also TWO Pico dirtyJtag probes on this host, so a bare
# /dev/ttyACM0 can point at the other board.
CONSOLE_DUAL_DDR3 = wukong-pico-1
CONSOLE_DUAL_SDR  = wukong-pico-0

# THE BAUD IS NOT WRITTEN DOWN HERE ANY MORE. This board carried
# UART_BAUD := 1000000 and DDR3_UART_BAUD := 2000000 as constants, plus two
# more for the dual halves -- four numbers that were right only for whichever
# bitstream was current when someone last edited them, and one of which had
# already gone stale (115200, against a preset saying 2000000). console.mk
# reads the rate the build itself recorded. Status item 70.

.PHONY: bram-generate create-ip create-project build program program-flash bram-monitor all clean
.PHONY: ddr3-generate ddr3-create-ip ddr3-build ddr3-program ddr3-monitor ddr3-download ddr3-reset ddr3-redownload
.PHONY: ddr3-smp-generate ddr3-smp-build ddr3-smp-bitstream ddr3-smp-program ddr3-smp-monitor
.PHONY: dual-generate dual-create-ip dual-build dual-program dual-monitor dual-sdr-monitor dual-sdr-download
.PHONY: sdram-generate sdram-create-ip sdram-build sdram-program sdram-monitor
.PHONY: jop-sdram-generate jop-sdram-build sdr-smp-generate jop-sdram-smp-generate jop-sdram-smp-build jop-sdram-smp-bitstream jop-sdram-program jop-sdram-monitor print-cfg-dir

all: microcode create-ip build

# === JOP BRAM targets ===
#
# CONVERTED 2026-08-29. This flow generated into spinalhdl/generated and read
# its embedded .jop from the in-tree java/apps -- the last main-board target
# still doing either, and the reason `make all` here could not be built from a
# clean clone. wukongBram is an ordinary JopConfig preset, so it takes
# `buildtree` like every other; it had simply been missed.
BRAM_CFG     = wukongBram
BRAM_CFG_DIR = $(REPO_ROOT)/build/$(BRAM_CFG)
# ONE RULE FOR EVERY CONFIG'S APP, so a flow cannot be forgotten.
#
# This board drives seven configurations from bespoke generate rules that
# bypass vivado.mk, and each needs the .jop of ITS OWN config. The first
# version wired it to the BRAM flow only: ddr3, dual and sdram got the
# microcode prerequisite but not this one, so `make ddr3-build` succeeded and
# `make ddr3-download` then died on
#   FileNotFoundError: build/wukongDdr3/java/apps/Smallest/HelloWorld.jop
# A pattern rule takes the config from the path, so adding a flow cannot repeat
# the omission.
$(REPO_ROOT)/build/%/java/apps/Smallest/HelloWorld.jop:
	cd $(REPO_ROOT)/java && $(MAKE) all JOP_PRESET=$* BUILDTREE=1

APP_JOP    = $(REPO_ROOT)/build/$(1)/java/apps/Smallest/HelloWorld.jop


# NAMED FOR ITS FLOW, not `generate`. This used to redefine `generate:`, which
# vivado.mk also defines -- make keeps the last recipe but the prerequisites of
# BOTH, and prints
#
#   Makefile:108: warning: overriding recipe for target 'generate'
#   ../vivado.mk:149: warning: ignoring old recipe for target 'generate'
#
# on every build of this board. The consequence was not cosmetic: the SMP flows
# re-enter with `$(MAKE) generate CFG="wukongSmp 4"`, which satisfied the stamp
# for that config and then ran THIS recipe, hardcoded to $(BRAM_CFG),
# elaborating wukongBram on top of it. Renaming lets vivado.mk's CFG-driven
# `generate` stand, which is what the re-entering targets always meant.
bram-generate: $(UCODE_SCALA) $(call APP_JOP,$(BRAM_CFG))
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(BRAM_CFG) buildtree"

create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_clk_wiz.tcl

create-project: bram-generate
	$(VIVADO_ENV) && JOP_PROJ=wukong_jop_bram JOP_PART=$(PART) JOP_TOP=JopBramWukongTop \
	  JOP_BUILD_DIR=$(BRAM_CFG_DIR)/vivado/build \
	  JOP_RTL=$(BRAM_CFG_DIR)/rtl/JopBramWukongTop.v \
	  JOP_BIN_GLOB='$(BRAM_CFG_DIR)/rtl/JopBramWukongTop*.bin' \
	  JOP_XDC=$(XDC)/wukong_jop_bram.xdc \
	  JOP_IP=$(IP)/bram_clk/bram_clk.xci \
	  JOP_GEN_HINT="run: make bram-generate" \
	  $(VIVADO) -mode batch -source $(CREATE_TCL)

build: create-project
	$(VIVADO_ENV) && JOP_PROJ=wukong_jop_bram JOP_BUILD_DIR=$(BRAM_CFG_DIR)/vivado/build \
	  JOP_BIT=JopBramWukongTop.bit \
	  $(VIVADO) -mode batch -source $(PRJ_TCL)

program:
	$(MAKE) program-bit BIT_FILE=$(BITSTREAM)

program-flash:
	sudo openFPGALoader -c $(LOADER_CABLE) $(BUSDEV) --write-flash $(BITSTREAM)

bram-monitor:
	$(MAKE) monitor CFG=wukongBram

# === JOP DDR3 targets ===

# CONVERTED (2026-08-25). Same shape as the SDR flow: outputs under
# build/<config>/, keyed by the invocation. DDR3_CFG selects which DDR3 preset
# -- wukongFull carries Ethernet and SD, wukongDdr3 is the plain one.
#
# PINS are generated (ConstraintDriftTest has validated them against
# wukong_ddr3_base.xdc); wukong_ddr3.xdc and rtl8211eg_gmii.xdc stay
# hand-written because they carry TIMING EXCEPTIONS and the PHY interface,
# which are not derivable from board data.
# wukongDdr3 by default, not wukongFull -- a choice about which config is the
# routine one, NOT a defect any more. wukongFull passes DoAll 66/66 on hardware
# as of 2026-08-31 (timing MET, WNS +0.349 ns); it had died at FloatTest until
# then.
#
# THE CAUSE HERE WAS STATED BACKWARDS until 2026-08-31, in this comment and in
# README alike: nothing "forces hardware for frem". frem was absent from
# BytecodeConfig.all entirely, and `resolve` only iterates that list, so a
# wildcard could never reach it. frem is implemented in Java
# (JVM.f_frem -> SoftFloat32.float_rem), and that library is compiled in only
# when Const.SUPPORT_FLOAT is set -- which was derived from "does any REGISTERED
# float bytecode still resolve to Java". Give the FCU all the registered ones
# and the flag went false, the library was dropped, and frem trapped to
# JVMHelp.noim(). Fixed by registering frem and drem as ImpConstraint.JavaOnly.
# See status items 69 and 109.
DDR3_CFG      ?= wukongDdr3
DDR3_CFG_DIR   = $(REPO_ROOT)/build/$(DDR3_CFG)

ddr3-generate: $(UCODE_SCALA) $(call APP_JOP,$(DDR3_CFG))
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(DDR3_CFG) buildtree"
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(DDR3_CFG) --write build/$(DDR3_CFG)/vivado/wukong_ddr3_base.xdc"

ddr3-create-ip:
	$(VIVADO_ENV) && JOP_MIG_TCL=$(IP)/generated/ddr3_clocks.tcl \
	  $(VIVADO) -mode batch -source vivado/tcl/create_ddr3_clk_wiz.tcl
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_mig.tcl

# GMII CONSTRAINTS FIRST. rtl8211eg_gmii.xdc does `create_clock -name e_rxc`,
# and wukong_ddr3.xdc references [get_clocks e_rxc] in its set_clock_groups.
# Read the other way round that get_clocks matches nothing -- "No clocks matched
# 'e_rxc'" -- the asynchronous exclusion silently does not apply, and the build
# reports a violation it should not. JOP_XDC is an ordered list for this reason.
ddr3-build: ddr3-generate
	$(VIVADO_ENV) && JOP_TOP=JopDdr3WukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(DDR3_CFG_DIR)/vivado/build \
	  JOP_RTL=$(DDR3_CFG_DIR)/rtl/JopDdr3WukongTop.v \
	  JOP_IP="$(IP)/ddr3_clk/ddr3_clk.xci $(IP)/mig_7series_0/mig_7series_0.xci" \
	  JOP_XDC="$(SHARED_XDC)/rtl8211eg_gmii.xdc $(DDR3_CFG_DIR)/vivado/wukong_ddr3_base.xdc $(XDC)/wukong_ddr3.xdc" \
	  $(TIMING_DIRECTIVES) JOP_DRC_WAIVE=LUTLP-1 \
	  JOP_SUMMARY=$(DDR3_CFG_DIR)/rtl/JopDdr3WukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

ddr3-program:
	$(MAKE) program-bit BIT_FILE=$(DDR3_CFG_DIR)/vivado/build/JopDdr3WukongTop.bit

ddr3-monitor:
	$(MAKE) monitor CFG=$(DDR3_CFG)

ddr3-download:
	$(MAKE) download CFG=$(DDR3_CFG)

# Reset the core over UART. On DDR3 this is a CORE-ONLY reset -- the MIG keeps
# its calibration, so it costs microseconds, not the seconds a retrain would.
ddr3-reset:
	$(MAKE) reset CFG=$(DDR3_CFG)

# Swap the running application without reprogramming the FPGA.
ddr3-redownload:
	$(MAKE) redownload CFG=$(DDR3_CFG)

# === JOP DDR3 SMP targets ===

# THE DIRECTORY IS ASKED FOR, NOT SPELLED OUT. This was
#     DDR3_SMP_DIR = $(REPO_ROOT)/build/wukongSmp-$(DDR3_SMP_CORES)
# -- a second copy of BuildLayout.configName's sanitising rules, living in Make.
# Two copies of a naming rule agree until the day they do not, and the failure
# is a path silently pointing at a stale directory or none at all. The SMP flows
# re-enter with their CFG so vivado.mk resolves the name through
# BuildLayoutMain; only the flow actually invoked pays for the sbt round trip.
DDR3_SMP_CORES ?= 4
DDR3_SMP_CFG    = wukongSmp $(DDR3_SMP_CORES)
DDR3_SMP_DIR    = $(CFG_DIR)

ddr3-smp-generate:
	$(MAKE) generate CFG="$(DDR3_SMP_CFG)"

ddr3-smp-build:
	$(MAKE) ddr3-smp-bitstream CFG="$(DDR3_SMP_CFG)"

ddr3-smp-bitstream: generate
	$(VIVADO_ENV) && JOP_TOP=JopSmpDdr3WukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(DDR3_SMP_DIR)/vivado/build \
	  JOP_RTL=$(DDR3_SMP_DIR)/rtl/JopSmpDdr3WukongTop.v \
	  JOP_IP="$(IP)/ddr3_clk/ddr3_clk.xci $(IP)/mig_7series_0/mig_7series_0.xci" \
	  JOP_XDC="$(SHARED_XDC)/rtl8211eg_gmii.xdc $(XDC)/wukong_ddr3_base.xdc $(XDC)/wukong_ddr3.xdc" \
	  $(TIMING_DIRECTIVES) JOP_DRC_WAIVE=LUTLP-1 \
	  JOP_SUMMARY=$(DDR3_SMP_DIR)/rtl/JopSmpDdr3WukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

ddr3-smp-program:
	$(MAKE) program-bit CFG="$(DDR3_SMP_CFG)" \
	    BIT_FILE=$$($(MAKE) --no-print-directory print-cfg-dir CFG="$(DDR3_SMP_CFG)")/vivado/build/JopSmpDdr3WukongTop.bit

ddr3-smp-monitor:
	$(MAKE) monitor CFG="wukongSmp $(DDR3_SMP_CORES)"

# === JOP Dual-cluster (DDR3 + SDR) targets ===

DUAL_CFG     ?= wukongDualIndependent
DUAL_CFG_DIR  = $(REPO_ROOT)/build/$(DUAL_CFG)

dual-generate: $(UCODE_SCALA) $(call APP_JOP,$(DUAL_CFG))
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(DUAL_CFG) buildtree"

dual-create-ip: ddr3-create-ip
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl

# The only build here needing constraints applied to the SYNTHESISED netlist --
# set_max_delay / set_clock_groups over the SDR controller, which resolve
# against the elaborated design. They live in an ordinary file now and are
# sourced between synth and opt; see wukong_dual_post_synth.tcl.
dual-build: dual-generate
	$(VIVADO_ENV) && JOP_TOP=JopDualWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(DUAL_CFG_DIR)/vivado/build \
	  JOP_RTL=$(DUAL_CFG_DIR)/rtl/JopDualWukongTop.v \
	  JOP_IP="$(IP)/ddr3_clk/ddr3_clk.xci $(IP)/sdr_clk/sdr_clk.xci $(IP)/mig_7series_0/mig_7series_0.xci" \
	  JOP_XDC=$(XDC)/wukong_dual.xdc \
	  JOP_POST_SYNTH_TCL=$(XDC)/wukong_dual_post_synth.tcl \
	  $(TIMING_DIRECTIVES) \
	  JOP_SUMMARY=$(DUAL_CFG_DIR)/rtl/JopDualWukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

dual-program:
	$(MAKE) program-bit BIT_FILE=$(DUAL_BITSTREAM)

# The dual build's DDR3 half. VERIFIED on hardware 2026-08-22: both halves come
# out of the Pico's two CDCs at 2 Mbaud, and the CH340 carries nothing at all.
# The two are told apart by their GC card size, which is derived from memory
# size -- 512-word cards on if03 (DDR3, PICO_UART1) and 32-word on if01 (SDR,
# J11_UART). This target used to watch UART_DEV (the CH340) at UART_BAUD
# (1 Mbaud), so it was wrong in both the port and the rate.
dual-monitor:
	$(MAKE) monitor CFG=$(DUAL_CFG) CONSOLE_ALIAS=$(CONSOLE_DUAL_DDR3)

# SDR cluster: UART via Pico ttyACM0 at 115200 baud
dual-sdr-monitor:
	$(MAKE) monitor CFG=$(DUAL_CFG) CONSOLE_ALIAS=$(CONSOLE_DUAL_SDR)

dual-sdr-download:
	$(MAKE) download CFG=$(DUAL_CFG) CONSOLE_ALIAS=$(CONSOLE_DUAL_SDR)

# === SDRAM Exerciser targets ===

sdram-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.SdramExerciserWukongTopVerilog"

sdram-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl

# Default directives: this exerciser has timing margin to spare and does not
# need the closure flow the JOP builds run.
sdram-build: sdram-generate
	$(VIVADO_ENV) && JOP_TOP=SdramExerciserWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(SDR_EX_DIR)/vivado/build \
	  JOP_RTL=$(SDR_EX_DIR)/rtl/SdramExerciserWukongTop.v \
	  JOP_IP=$(IP)/sdr_clk/sdr_clk.xci \
	  JOP_XDC="$(XDC)/wukong_sdram.xdc $(SHARED_XDC)/sdram_sdr.xdc" \
	  $(VIVADO) -mode batch -source $(NP_TCL)

sdram-program:
	$(MAKE) program-bit BIT_FILE=$(SDRAM_BITSTREAM)

sdram-monitor:
	$(MAKE) monitor CFG=$(SDRAM_CFG)

# === JOP SDRAM targets ===

# Verilog AND constraints, both from the same JopConfig. This board's XDC is
# generated rather than hand-maintained; see build_jop_sdram_bitstream.tcl.
# Generated artefacts go under build/<config>/, keyed by the invocation --
# see jop.generate.BuildLayout. JOP_CFG_DIR tells the Vivado script where; unset,
# it falls back to the old in-tree layout, so unconverted flows are unaffected.
SDRAM_CFG      ?= wukongSdram
SDRAM_CFG_DIR   = $(REPO_ROOT)/build/$(SDRAM_CFG)

jop-sdram-generate: $(UCODE_SCALA) $(call APP_JOP,$(SDRAM_CFG))
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(SDRAM_CFG) buildtree"
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(SDRAM_CFG) --write build/$(SDRAM_CFG)/vivado/wukong_jop_sdram.xdc"

jop-sdram-build: jop-sdram-generate
	$(VIVADO_ENV) && JOP_TOP=JopSdramWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(SDRAM_CFG_DIR)/vivado/build \
	  JOP_RTL=$(SDRAM_CFG_DIR)/rtl/JopSdramWukongTop.v \
	  JOP_BIN_GLOB='$(SDRAM_CFG_DIR)/rtl/JopSdramWukongTop.v_*.bin' \
	  JOP_IP=$(IP)/sdr_clk/sdr_clk.xci \
	  JOP_XDC="$(SDRAM_CFG_DIR)/vivado/wukong_jop_sdram.xdc $(XDC)/wukong_peripherals.xdc" \
	  JOP_SUMMARY=$(SDRAM_CFG_DIR)/rtl/JopSdramWukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

# SMP SDR: the counterpart of ddr3-smp-build, so SDR and DDR3 can be compared on
# one board with only the memory path differing. Entity is JopSmpSdramWukongTop
# (cpuCnt >= 2 inserts "Smp"), which the single-core script does not build.
SDR_SMP_CORES ?= 2
SDR_SMP_CFG     = wukongSdrSmp $(SDR_SMP_CORES)
SDR_SMP_DIR     = $(CFG_DIR)

# The XDC comes with it. jop-sdram-build regenerates the pin constraints for
# its config; this flow did not, and then read the TRACKED copy below -- so the
# single-core and SMP builds of the same design were pinned from two different
# sources that nothing kept in agreement.
jop-sdram-smp-generate:
	$(MAKE) sdr-smp-generate CFG="$(SDR_SMP_CFG)"

# Runs with CFG ALREADY SET to the SMP config, so $(CFG_NAME) and $(CFG_DIR)
# resolve to that config's build directory and the paths need no second copy of
# the naming rule. Doing the XDC generation in the outer target instead would
# have to reconstruct the directory name by hand -- two copies of a rule agree
# until the day they do not.
sdr-smp-generate: generate
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(CFG) --write build/$(CFG_NAME)/vivado/wukong_jop_sdram.xdc"

jop-sdram-smp-build:
	$(MAKE) jop-sdram-smp-bitstream CFG="$(SDR_SMP_CFG)"

jop-sdram-smp-bitstream: sdr-smp-generate
	$(VIVADO_ENV) && JOP_TOP=JopSmpSdramWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(SDR_SMP_DIR)/vivado/build \
	  JOP_RTL=$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.v \
	  JOP_BIN_GLOB='$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.v_*.bin' \
	  JOP_IP=$(IP)/sdr_clk/sdr_clk.xci \
	  JOP_XDC="$(SDR_SMP_DIR)/vivado/wukong_jop_sdram.xdc $(XDC)/wukong_peripherals.xdc" \
	  JOP_SUMMARY=$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

jop-sdram-program:
	$(MAKE) program-bit BIT_FILE=$(JOP_SDRAM_BITSTREAM)

jop-sdram-monitor:
	$(MAKE) monitor CFG=$(SDRAM_CFG)

# BLINK RETIRED (2026-08-25). `WukongBlink` had no SpinalHDL source and no git
# history of one -- create_blink_project.tcl read RTL that has never been in
# this repo. For a genuine bring-up jig on this board see
# bringup/wukong-uart-loopback/.
clean:
	rm -rf vivado/build vivado/ip
