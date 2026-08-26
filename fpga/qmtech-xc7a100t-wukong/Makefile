REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO := /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV := export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
OPENFPGALOADER := openFPGALoader
# THERE ARE TWO dirtyJtag PROBES ON THIS HOST -- this board's Pico 2 W and the
# XC7A100T DB board's. A bare `-c dirtyJtag` takes whichever enumerated first,
# so it can silently program the WRONG BOARD. Resolved by serial, like every
# other probe and tty here. Needs the PATCHED openFPGALoader in /usr/local/bin;
# a bogus `--busdev-num 099:099` must FAIL for the selection to mean anything.
BUSDEV ?= --busdev-num $(shell $(REPO_ROOT)/fpga/scripts/jtag_probe_map --busdev wukong)
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
IP         := $(CURDIR)/vivado/ip
XDC        := $(CURDIR)/vivado/constraints
SHARED_XDC := $(REPO_ROOT)/fpga/constraints
GEN_RTL    := $(REPO_ROOT)/spinalhdl/generated
ABS_BUILD  := $(CURDIR)/vivado/build

# Every JOP build here uses the same timing-closure directives; the exercisers
# and the SDR JOP builds have margin and run the default flow.
TIMING_DIRECTIVES := JOP_SYNTH_DIRECTIVE=PerformanceOptimized JOP_SYNTH_RETIMING=yes \
                    JOP_OPT_DIRECTIVE=Explore JOP_PLACE_DIRECTIVE=ExtraTimingOpt \
                    JOP_PHYS_OPT_DIRECTIVE=AggressiveExplore JOP_ROUTE_DIRECTIVE=AggressiveExplore
# UART_DEV is the board's own CH340N, and applies to the SINGLE-SYSTEM builds
# only -- those use SystemAssembly.wukong with DeviceDriver.UartCh340. The
# DUAL-cluster builds do NOT: the CH340N at E3/F3 is hardwired on the PCB and
# cannot be tapped, so both halves come out of the on-board Pico 2 W instead
# (DDR3 -> PICO_UART1 F4/H4, SDR -> J11_UART A5/A4). See
# docs/architecture/system-configuration.md.
#
# Resolved by alias, since ttyUSBn moves on every replug and this board's CH340
# has no serial to key on -- see fpga/scripts/usb_serial_map, whose header
# explains why these two CH340s need special handling.
UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong 2>/dev/null \
              || echo /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0)
UART_BAUD := 1000000
# Exactly 2 Mbaud since 2026-08-18. It used to be 2037000: ui_clk is
# 91.6758 MHz at Ddr3_366, an integer divisor gave 2.0372 Mbaud, and this
# constant existed purely to chase that rounding error. jop.io.Uart now uses a
# fractional baud generator (UartBaudTick), so the wire rate is the requested
# one on any clock.
DDR3_UART_BAUD := 2000000
JOP_FILE ?= $(REPO_ROOT)/java/apps/Smallest/HelloWorld.jop
# This board's Pico 2 W CDC. There are TWO Pico dirtyJtag probes on this host
# (this one and the XC7A100T's E662C876C3506A26), so a bare /dev/ttyACM0 is
# whichever enumerated first -- it can point at the wrong board. The Pico DOES
# have a serial, so this one resolves the ordinary way.
DUAL_SDR_UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong-pico-0 2>/dev/null \
                       || echo /dev/serial/by-id/usb-Jean_THOMAS_Pico_Dirty_JTAG_0B383D0435D957EC-if01)
# Both dual halves run at 2 Mbaud -- see JopConfig.wukongDualIndependent, where
# PICO_UART1 and J11_UART both set baudRate 2000000. This said 115200, which no
# longer matches the preset.
DUAL_UART_BAUD := 2000000
DUAL_SDR_UART_BAUD := $(DUAL_UART_BAUD)
# DDR3 half -> PICO_UART1 -> the Pico's SECOND CDC (if03).
DUAL_DDR3_UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong-pico-1 2>/dev/null \
                        || echo /dev/serial/by-id/usb-Jean_THOMAS_Pico_Dirty_JTAG_0B383D0435D957EC-if03)

.PHONY: generate create-ip create-project build program program-flash monitor all clean
.PHONY: ddr3-generate ddr3-create-ip ddr3-build ddr3-program ddr3-monitor ddr3-download ddr3-reset ddr3-redownload
.PHONY: ddr3-smp-generate ddr3-smp-build ddr3-smp-program ddr3-smp-monitor
.PHONY: dual-generate dual-create-ip dual-build dual-program dual-monitor dual-sdr-monitor dual-sdr-download
.PHONY: sdram-generate sdram-create-ip sdram-build sdram-program sdram-monitor
.PHONY: jop-sdram-generate jop-sdram-build jop-sdram-smp-build jop-sdram-program jop-sdram-monitor

all: build

# === JOP BRAM targets ===

generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongBram"

create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_clk_wiz.tcl

create-project: generate
	$(VIVADO_ENV) && JOP_PROJ=wukong_jop_bram JOP_PART=$(PART) JOP_TOP=JopBramWukongTop \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(GEN_RTL)/JopBramWukongTop.v \
	  JOP_BIN_GLOB='$(GEN_RTL)/JopBramWukongTop*.bin' \
	  JOP_XDC=$(XDC)/wukong_jop_bram.xdc \
	  JOP_IP=$(IP)/bram_clk/bram_clk.xci \
	  JOP_GEN_HINT="run: make generate" \
	  $(VIVADO) -mode batch -source $(CREATE_TCL)

build: create-project
	$(VIVADO_ENV) && JOP_PROJ=wukong_jop_bram JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_BIT=JopBramWukongTop.bit \
	  $(VIVADO) -mode batch -source $(PRJ_TCL)

program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(BITSTREAM)

program-flash:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) --write-flash $(BITSTREAM)

monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP DDR3 targets ===

# CONVERTED (2026-08-25). Same shape as the SDR flow: outputs under
# build/<config>/, keyed by the invocation. DDR3_CFG selects which DDR3 preset
# -- wukongFull carries Ethernet and SD, wukongDdr3 is the plain one.
#
# PINS are generated (ConstraintDriftTest has validated them against
# wukong_ddr3_base.xdc); wukong_ddr3.xdc and rtl8211eg_gmii.xdc stay
# hand-written because they carry TIMING EXCEPTIONS and the PHY interface,
# which are not derivable from board data.
# wukongDdr3 by default, not wukongFull: wukongFull sets bytecodes "*" -> "hw",
# which forces hardware for `frem` -- a bytecode with no hardware implementation
# anywhere -- so DoAll dies at FloatTest on it. wukongDdr3 runs 66/66 on the same
# board through the same flow. See status item 69.
DDR3_CFG      ?= wukongDdr3
DDR3_CFG_DIR   = $(REPO_ROOT)/build/$(DDR3_CFG)

ddr3-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(DDR3_CFG) buildtree"
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(DDR3_CFG) --write build/$(DDR3_CFG)/vivado/wukong_ddr3_base.xdc"

ddr3-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_ddr3_clk_wiz.tcl
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
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(DDR3_CFG_DIR)/vivado/build/JopDdr3WukongTop.bit

ddr3-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(DDR3_UART_BAUD)

ddr3-download:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e $(JOP_FILE) $(UART_DEV) $(DDR3_UART_BAUD)

# Reset the core over UART. On DDR3 this is a CORE-ONLY reset -- the MIG keeps
# its calibration, so it costs microseconds, not the seconds a retrain would.
ddr3-reset:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -R $(UART_DEV) $(DDR3_UART_BAUD)

# Swap the running application without reprogramming the FPGA.
ddr3-redownload:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e -r $(JOP_FILE) $(UART_DEV) $(DDR3_UART_BAUD)

# === JOP DDR3 SMP targets ===

DDR3_SMP_CORES ?= 4
DDR3_SMP_CFG    = wukongSmp $(DDR3_SMP_CORES)
DDR3_SMP_DIR    = $(REPO_ROOT)/build/wukongSmp-$(DDR3_SMP_CORES)

ddr3-smp-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(DDR3_SMP_CFG) buildtree"

ddr3-smp-build: ddr3-smp-generate
	$(VIVADO_ENV) && JOP_TOP=JopSmpDdr3WukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(DDR3_SMP_DIR)/vivado/build \
	  JOP_RTL=$(DDR3_SMP_DIR)/rtl/JopSmpDdr3WukongTop.v \
	  JOP_IP="$(IP)/ddr3_clk/ddr3_clk.xci $(IP)/mig_7series_0/mig_7series_0.xci" \
	  JOP_XDC="$(SHARED_XDC)/rtl8211eg_gmii.xdc $(XDC)/wukong_ddr3_base.xdc $(XDC)/wukong_ddr3.xdc" \
	  $(TIMING_DIRECTIVES) JOP_DRC_WAIVE=LUTLP-1 \
	  JOP_SUMMARY=$(DDR3_SMP_DIR)/rtl/JopSmpDdr3WukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

ddr3-smp-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(DDR3_SMP_DIR)/vivado/build/JopSmpDdr3WukongTop.bit

ddr3-smp-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP Dual-cluster (DDR3 + SDR) targets ===

DUAL_CFG     ?= wukongDualIndependent
DUAL_CFG_DIR  = $(REPO_ROOT)/build/$(DUAL_CFG)

dual-generate:
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
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(DUAL_BITSTREAM)

# The dual build's DDR3 half. VERIFIED on hardware 2026-08-22: both halves come
# out of the Pico's two CDCs at 2 Mbaud, and the CH340 carries nothing at all.
# The two are told apart by their GC card size, which is derived from memory
# size -- 512-word cards on if03 (DDR3, PICO_UART1) and 32-word on if01 (SDR,
# J11_UART). This target used to watch UART_DEV (the CH340) at UART_BAUD
# (1 Mbaud), so it was wrong in both the port and the rate.
dual-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(DUAL_DDR3_UART_DEV) $(DUAL_UART_BAUD)

# SDR cluster: UART via Pico ttyACM0 at 115200 baud
dual-sdr-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(DUAL_SDR_UART_DEV) $(DUAL_SDR_UART_BAUD)

dual-sdr-download:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e \
	  $(REPO_ROOT)/java/apps/Smallest/HelloWorld.jop \
	  $(DUAL_SDR_UART_DEV) $(DUAL_SDR_UART_BAUD)

# === SDRAM Exerciser targets ===

sdram-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.SdramExerciserWukongTopVerilog"

sdram-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl

# Default directives: this exerciser has timing margin to spare and does not
# need the closure flow the JOP builds run.
sdram-build: sdram-generate
	$(VIVADO_ENV) && JOP_TOP=SdramExerciserWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(ABS_BUILD)/wukong_sdram_exerciser \
	  JOP_RTL=$(GEN_RTL)/SdramExerciserWukongTop.v \
	  JOP_IP=$(IP)/sdr_clk/sdr_clk.xci \
	  JOP_XDC="$(XDC)/wukong_sdram.xdc $(SHARED_XDC)/sdram_sdr.xdc" \
	  $(VIVADO) -mode batch -source $(NP_TCL)

sdram-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(SDRAM_BITSTREAM)

sdram-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP SDRAM targets ===

# Verilog AND constraints, both from the same JopConfig. This board's XDC is
# generated rather than hand-maintained; see build_jop_sdram_bitstream.tcl.
# Generated artefacts go under build/<config>/, keyed by the invocation --
# see jop.generate.BuildLayout. JOP_CFG_DIR tells the Vivado script where; unset,
# it falls back to the old in-tree layout, so unconverted flows are unaffected.
SDRAM_CFG      ?= wukongSdram
SDRAM_CFG_DIR   = $(REPO_ROOT)/build/$(SDRAM_CFG)

jop-sdram-generate:
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
SDR_SMP_DIR    = $(REPO_ROOT)/build/wukongSdrSmp-$(SDR_SMP_CORES)

jop-sdram-smp-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongSdrSmp $(SDR_SMP_CORES) buildtree"

jop-sdram-smp-build: jop-sdram-smp-generate
	$(VIVADO_ENV) && JOP_TOP=JopSmpSdramWukongTop JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(SDR_SMP_DIR)/vivado/build \
	  JOP_RTL=$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.v \
	  JOP_BIN_GLOB='$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.v_*.bin' \
	  JOP_IP=$(IP)/sdr_clk/sdr_clk.xci \
	  JOP_XDC="$(XDC)/wukong_jop_sdram.xdc $(XDC)/wukong_peripherals.xdc" \
	  JOP_SUMMARY=$(SDR_SMP_DIR)/rtl/JopSmpSdramWukongTop.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

jop-sdram-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BUSDEV) $(JOP_SDRAM_BITSTREAM)

jop-sdram-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# BLINK RETIRED (2026-08-25). `WukongBlink` had no SpinalHDL source and no git
# history of one -- create_blink_project.tcl read RTL that has never been in
# this repo. For a genuine bring-up jig on this board see
# bringup/wukong-uart-loopback/.
clean:
	rm -rf vivado/build vivado/ip
